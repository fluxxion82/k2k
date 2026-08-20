package com.k2k.tls

import at.asitplus.signum.indispensable.CryptoPublicKey
import at.asitplus.signum.indispensable.CryptoSignature
import at.asitplus.signum.indispensable.ECCurve
import at.asitplus.signum.indispensable.X509SignatureAlgorithm
import at.asitplus.signum.indispensable.asn1.Asn1String
import at.asitplus.signum.indispensable.asn1.Asn1Time
import at.asitplus.signum.indispensable.pki.AttributeTypeAndValue
import at.asitplus.signum.indispensable.pki.RelativeDistinguishedName
import at.asitplus.signum.indispensable.pki.TbsCertificate
import at.asitplus.signum.indispensable.pki.X509Certificate
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyRef
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageRFC4754SHA256
import kotlin.time.Instant

/**
 * A self-signed device identity generated on the device itself.
 *
 * iOS has no API that creates or signs a certificate — every `SecCertificate*` call parses — so this
 * assembles the X.509 structure with signum and signs it with the Security framework, which is the
 * only part Apple does provide.
 *
 * The key is deliberately software-held rather than Secure Enclave-backed. SE keys cannot be used
 * here with confidence: TLS client auth with an SE identity was definitively broken in 2016 (Apple
 * radar 25978027, wrong signature algorithm in CertificateVerify) and in ten years Apple has never
 * affirmatively documented it as fixed — the most recent guidance from DTS is still "should work".
 * For ephemeral LAN peer sessions a software key is the right trade; revisit only with a device test
 * proving otherwise.
 *
 * P-256 throughout, because that is what the Secure Enclave would require if we ever move to it, and
 * because ECDSA-P256-SHA256 is a standard TLS 1.2/1.3 signature scheme on every peer we talk to.
 */
@OptIn(ExperimentalForeignApi::class)
internal class DeviceIdentity private constructor(
    /** Retained. The caller owns this and must [close] the identity when done. */
    val privateKey: SecKeyRef,
    /** The self-signed certificate, DER-encoded — the exact bytes a peer will pin. */
    val certificateDer: ByteArray,
) {
    /** What a peer stores at pairing time and checks on every later handshake. */
    val pin: String by lazy { SpkiPin.ofCertificate(certificateDer) }

    fun close() {
        CFRelease(privateKey)
    }

    companion object {
        private const val CURVE_KEY_SIZE = 256

        /**
         * Generates a fresh P-256 key and a self-signed certificate binding it to [commonName].
         *
         * [serialNumber] and the validity window are caller-supplied rather than derived here so
         * this stays deterministic and testable — there is no clock or randomness hidden inside it.
         */
        fun generate(
            commonName: String,
            serialNumber: ByteArray,
            notBefore: Instant,
            notAfter: Instant,
        ): DeviceIdentity {
            require(serialNumber.isNotEmpty()) { "certificate serial number must not be empty" }
            require(notAfter > notBefore) { "certificate validity window must be non-empty" }

            val privateKey = createP256Key()
            try {
                val publicKey = SecKeyCopyPublicKey(privateKey)
                    ?: error("could not derive the public key from a freshly generated private key")
                val x963 = try {
                    copyExternalRepresentation(publicKey)
                } finally {
                    CFRelease(publicKey)
                }

                // SecKeyCopyExternalRepresentation gives ANSI X9.63 (04 || X || Y) for EC keys,
                // which is exactly what fromAnsiX963Bytes expects. Note this is NOT SPKI — see
                // CertificateSpki for why that distinction matters for pinning.
                val cryptoPublicKey = CryptoPublicKey.EC.fromAnsiX963Bytes(ECCurve.SECP_256_R_1, x963)
                val name = listOf(
                    RelativeDistinguishedName(AttributeTypeAndValue.CommonName(Asn1String.UTF8(commonName))),
                )

                val tbs = TbsCertificate(
                    serialNumber = serialNumber,
                    signatureAlgorithm = X509SignatureAlgorithm.ES256,
                    // Self-signed: the certificate is its own issuer.
                    issuerName = name,
                    subjectName = name,
                    validFrom = Asn1Time(notBefore),
                    validUntil = Asn1Time(notAfter),
                    publicKey = cryptoPublicKey,
                )

                // RFC4754 rather than X962 deliberately: it yields a raw r||s pair, which is what
                // CryptoSignature.EC.fromRawBytes takes. The X962 variant returns a DER SEQUENCE and
                // would need unwrapping first.
                val rawSignature = sign(privateKey, tbs.encodeToDer())
                val signature = CryptoSignature.EC.fromRawBytes(ECCurve.SECP_256_R_1, rawSignature)

                val certificate = X509Certificate(tbs, X509SignatureAlgorithm.ES256, signature)
                return DeviceIdentity(privateKey, certificate.encodeToDer())
            } catch (t: Throwable) {
                CFRelease(privateKey)
                throw t
            }
        }

        private fun createP256Key(): SecKeyRef = memScoped {
            val attributes: CFMutableDictionaryRef =
                CFDictionaryCreateMutable(kCFAllocatorDefault, 2, null, null)
                    ?: error("could not allocate key generation attributes")
            try {
                CFDictionarySetValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
                val size = CFBridgingRetain(NSNumber(int = CURVE_KEY_SIZE))
                try {
                    CFDictionarySetValue(attributes, kSecAttrKeySizeInBits, size)
                    val error = alloc<CFErrorRefVar>()
                    val key = SecKeyCreateRandomKey(attributes, error.ptr)
                    if (key == null) {
                        val detail = error.value?.let { CFBridgingRelease(it).toString() }
                        error("could not generate a P-256 key pair: ${detail ?: "unknown error"}")
                    }
                    return@memScoped key
                } finally {
                    CFRelease(size)
                }
            } finally {
                CFRelease(attributes)
            }
        }

        private fun sign(key: SecKeyRef, payload: ByteArray): ByteArray = memScoped {
            val data = payload.usePinned {
                NSData.create(bytes = it.addressOf(0), length = payload.size.toULong())
            }
            val cfData = CFBridgingRetain(data) as CFDataRef
            try {
                val error = alloc<CFErrorRefVar>()
                val signature = SecKeyCreateSignature(
                    key,
                    kSecKeyAlgorithmECDSASignatureMessageRFC4754SHA256,
                    cfData,
                    error.ptr,
                )
                if (signature == null) {
                    val detail = error.value?.let { CFBridgingRelease(it).toString() }
                    error("could not sign the certificate: ${detail ?: "unknown error"}")
                }
                try {
                    return@memScoped signature.toByteArray()
                } finally {
                    CFRelease(signature)
                }
            } finally {
                CFRelease(cfData)
            }
        }

        private fun copyExternalRepresentation(key: SecKeyRef): ByteArray = memScoped {
            val error = alloc<CFErrorRefVar>()
            val data = SecKeyCopyExternalRepresentation(key, error.ptr)
                ?: error("could not export the public key: it may be non-exportable")
            try {
                return@memScoped data.toByteArray()
            } finally {
                CFRelease(data)
            }
        }

        private fun CFDataRef.toByteArray(): ByteArray {
            val length = CFDataGetLength(this).toInt()
            if (length == 0) return ByteArray(0)
            val bytes = CFDataGetBytePtr(this) ?: return ByteArray(0)
            return bytes.readBytes(length)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CFRelease(ref: CPointer<*>?) {
    if (ref != null) platform.CoreFoundation.CFRelease(ref as CFTypeRef)
}

/**
 * Wraps this identity as a `sec_identity_t` for Network.framework.
 *
 * Returns null when the certificate cannot be re-parsed or when SecIdentityCreate rejects the
 * pairing — Apple returns null there when the private key does not correspond to the certificate's
 * public key, which for a locally generated identity should be impossible and is worth failing on
 * rather than papering over.
 *
 * The caller owns the result and must CFRelease it.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun DeviceIdentity.asSecIdentity(): platform.Security.sec_identity_t? {
    val certificate = certificateDer.usePinned { pinned ->
        val data = platform.Foundation.NSData.create(
            bytes = pinned.addressOf(0),
            length = certificateDer.size.toULong(),
        )
        val cfData = platform.Foundation.CFBridgingRetain(data) as platform.CoreFoundation.CFDataRef
        try {
            platform.Security.SecCertificateCreateWithData(null, cfData)
        } finally {
            platform.CoreFoundation.CFRelease(cfData as CFTypeRef)
        }
    } ?: return null
    try {
        val secIdentity = platform.Security.SecIdentityCreate(null, certificate, privateKey)
            ?: return null
        try {
            return platform.Security.sec_identity_create(secIdentity)
        } finally {
            platform.CoreFoundation.CFRelease(secIdentity as CFTypeRef)
        }
    } finally {
        platform.CoreFoundation.CFRelease(certificate as CFTypeRef)
    }
}
