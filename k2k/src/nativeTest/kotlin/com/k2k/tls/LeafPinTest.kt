package com.k2k.tls

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFArrayCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecCertificateRef
import platform.Security.SecPolicyCreateBasicX509
import platform.Security.SecTrustCreateWithCertificates
import platform.Security.SecTrustRefVar
import platform.Security.errSecSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * `leafPinOf` is where every trust decision on the client bottoms out: the handler compares its
 * result against the set recorded at pairing. If it returned the wrong pin, an attacker's peer could
 * be accepted; if it threw, a legitimate peer would be refused with no explanation.
 *
 * It is exercised here against a real `SecTrust` built from a real certificate, rather than against
 * bytes we hand it directly, so the CoreFoundation chain walk is covered too — that is the part most
 * likely to be subtly wrong, and the part a unit test on raw DER would skip entirely.
 */
@OptIn(ExperimentalForeignApi::class)
class LeafPinTest {
    private fun newIdentity() = DeviceIdentity.generate(
        commonName = "k2k-leaf-pin-test",
        serialNumber = byteArrayOf(0x0A, 0x0B, 0x0C),
        notBefore = Instant.fromEpochSeconds(1_700_000_000),
        notAfter = Instant.fromEpochSeconds(1_900_000_000),
    )

    @Test
    fun leafPin_matchesThePinDerivedFromTheCertificateItself() {
        val identity = newIdentity()
        try {
            val trust = assertNotNull(trustFor(identity.certificateDer), "could not build a SecTrust")
            try {
                val fromTrust = leafPinOf(trust)

                assertEquals(
                    identity.pin,
                    fromTrust,
                    "the pin read back off a SecTrust must equal the one derived from the DER, or a " +
                        "peer we generated could not authenticate to us",
                )
            } finally {
                CFRelease(trust as CFTypeRef)
            }
        } finally {
            identity.close()
        }
    }

    /**
     * Two identities must not collide, otherwise pinning cannot tell devices apart — which is the
     * entire point of it.
     */
    @Test
    fun differentIdentities_produceDifferentLeafPins() {
        val first = newIdentity()
        val second = newIdentity()
        try {
            val firstTrust = assertNotNull(trustFor(first.certificateDer))
            val secondTrust = assertNotNull(trustFor(second.certificateDer))
            try {
                assertNotNull(leafPinOf(firstTrust))
                assertEquals(false, leafPinOf(firstTrust) == leafPinOf(secondTrust))
            } finally {
                CFRelease(firstTrust as CFTypeRef)
                CFRelease(secondTrust as CFTypeRef)
            }
        } finally {
            first.close()
            second.close()
        }
    }

    /**
     * A certificate we cannot parse must yield null, not an exception. The handler treats null as a
     * refusal; a thrown error would escape into Ktor's challenge callback on a system queue, where
     * it would be far less legible than a declined connection.
     */
    @Test
    fun unparseableCertificate_yieldsNullRatherThanThrowing() {
        // A DER SEQUENCE that is well-formed ASN.1 but not a certificate. Apple's parser rejects
        // this outright, so we never even reach our own — which is itself the assertion.
        assertNull(trustFor(byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x00)))
    }

    private fun trustFor(certificateDer: ByteArray): platform.Security.SecTrustRef? = memScoped {
        val certificate = certificateDer.toSecCertificate() ?: return@memScoped null
        try {
            val values = allocArrayOf(certificate as CFTypeRef)
            val array = CFArrayCreate(kCFAllocatorDefault, values.reinterpret(), 1, null)
                ?: return@memScoped null
            try {
                val policy = SecPolicyCreateBasicX509() ?: return@memScoped null
                try {
                    val trustVar = alloc<SecTrustRefVar>()
                    val status = SecTrustCreateWithCertificates(array, policy, trustVar.ptr)
                    if (status != errSecSuccess) return@memScoped null
                    return@memScoped trustVar.value
                } finally {
                    CFRelease(policy as CFTypeRef)
                }
            } finally {
                CFRelease(array as CFTypeRef)
            }
        } finally {
            CFRelease(certificate as CFTypeRef)
        }
    }

    private fun ByteArray.toSecCertificate(): SecCertificateRef? {
        if (isEmpty()) return null
        return usePinned { pinned ->
            val data = NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
            val cfData = CFBridgingRetain(data) as CFDataRef
            try {
                SecCertificateCreateWithData(null, cfData)
            } finally {
                CFRelease(cfData as CFTypeRef)
            }
        }
    }
}
