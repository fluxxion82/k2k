package com.k2k.tls

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecCertificateCopyKey
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecIdentityCreate
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Generating a certificate that *we* can parse proves very little — our parser and our writer could
 * agree on the same misreading. These assertions are all made by Apple's own code instead.
 *
 * The strongest is [generatedIdentity_isAcceptedBySecIdentityCreate]: Apple documents
 * SecIdentityCreate as returning null when the private key does not correspond to the public key in
 * the certificate, so a non-null result is the platform confirming the binding we constructed by
 * hand is real. That is the property mutual TLS actually depends on.
 */
@OptIn(ExperimentalForeignApi::class)
class DeviceIdentityTest {
    private val notBefore = Instant.fromEpochSeconds(1_700_000_000)
    private val notAfter = Instant.fromEpochSeconds(1_900_000_000)

    private fun newIdentity() = DeviceIdentity.generate(
        commonName = "k2k-test-device",
        serialNumber = byteArrayOf(0x01, 0x23, 0x45, 0x67),
        notBefore = notBefore,
        notAfter = notAfter,
    )

    @Test
    fun generatedCertificate_isAcceptedByApplesX509Parser() {
        val identity = newIdentity()
        try {
            val certificate = identity.certificateDer.asSecCertificate()

            assertNotNull(
                certificate,
                "SecCertificateCreateWithData rejected our DER, so it is not a valid X.509 certificate",
            )
            CFRelease(certificate as CFTypeRef)
        } finally {
            identity.close()
        }
    }

    /**
     * Apple returns null here when the key does not match the certificate. A non-null identity is
     * the platform agreeing that the certificate really carries this key's public half — which is
     * exactly what a TLS peer will rely on.
     */
    @Test
    fun generatedIdentity_isAcceptedBySecIdentityCreate() {
        val identity = newIdentity()
        try {
            val certificate = assertNotNull(identity.certificateDer.asSecCertificate())
            try {
                val secIdentity = SecIdentityCreate(null, certificate, identity.privateKey)

                assertNotNull(
                    secIdentity,
                    "SecIdentityCreate returned null: the private key does not correspond to the " +
                        "public key in the certificate we built",
                )
                CFRelease(secIdentity as CFTypeRef)
            } finally {
                CFRelease(certificate as CFTypeRef)
            }
        } finally {
            identity.close()
        }
    }

    /** The certificate must embed the key we generated, not some other key. */
    @Test
    fun certificatePublicKey_matchesTheGeneratedKey() {
        val identity = newIdentity()
        try {
            val certificate = assertNotNull(identity.certificateDer.asSecCertificate())
            try {
                val fromCertificate = assertNotNull(SecCertificateCopyKey(certificate))
                val fromPrivateKey = assertNotNull(SecKeyCopyPublicKey(identity.privateKey))
                try {
                    assertTrue(
                        fromCertificate.externalRepresentation()
                            .contentEquals(fromPrivateKey.externalRepresentation()),
                        "the certificate carries a different public key than the one generated",
                    )
                } finally {
                    CFRelease(fromCertificate as CFTypeRef)
                    CFRelease(fromPrivateKey as CFTypeRef)
                }
            } finally {
                CFRelease(certificate as CFTypeRef)
            }
        } finally {
            identity.close()
        }
    }

    @Test
    fun pin_isDerivableAndDistinctPerIdentity() {
        val first = newIdentity()
        val second = newIdentity()
        try {
            assertEquals(64, first.pin.length, "a SHA-256 pin is 64 hex characters")
            assertEquals(first.pin.lowercase(), first.pin)
            assertTrue(
                first.pin != second.pin,
                "two generated identities must not share a pin, or pairing cannot distinguish devices",
            )
        } finally {
            first.close()
            second.close()
        }
    }

    private fun ByteArray.asSecCertificate(): platform.Security.SecCertificateRef? {
        val data = usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
        val cfData = CFBridgingRetain(data) as CFDataRef
        return try {
            SecCertificateCreateWithData(null, cfData)
        } finally {
            CFRelease(cfData as CFTypeRef)
        }
    }

    private fun platform.Security.SecKeyRef.externalRepresentation(): ByteArray = memScoped {
        val error = alloc<CFErrorRefVar>()
        val data = SecKeyCopyExternalRepresentation(this@externalRepresentation, error.ptr)
            ?: error("could not export public key")
        try {
            val length = CFDataGetLength(data).toInt()
            val bytes = CFDataGetBytePtr(data) ?: return@memScoped ByteArray(0)
            return@memScoped bytes.readBytes(length)
        } finally {
            CFRelease(data as CFTypeRef)
        }
    }
}
