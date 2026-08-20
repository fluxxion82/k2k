package com.k2k.tls

import com.k2k.test.tls.SpkiPinning
import java.security.KeyStore
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pin is the value two devices compare to decide whether to trust each other, so it has to be
 * identical on every platform down to the character. This checks the shared implementation against
 * the JVM's existing one, which is what Passman has been pairing with in production.
 *
 * A divergence here would not look like a bug. It would look like an honest paired device that
 * cannot connect, forever, with a pin mismatch and nothing else to go on.
 */
class SpkiPinTest {
    private val password = "testpass".toCharArray()

    private fun certificateOf(name: String): X509Certificate {
        val stream = javaClass.getResourceAsStream("/tls/$name.p12")
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("tls/$name.p12")
            ?: error("missing test resource tls/$name.p12 on classpath")
        val keyStore = KeyStore.getInstance("PKCS12").apply { stream.use { load(it, password) } }
        val alias = keyStore.aliases().toList().first { keyStore.isCertificateEntry(it) || keyStore.isKeyEntry(it) }
        return keyStore.getCertificate(alias) as X509Certificate
    }

    @Test
    fun sharedPin_matchesTheJvmImplementation_forEveryTestCertificate() {
        for (name in listOf("alice", "bob", "mallory")) {
            val certificate = certificateOf(name)

            val shared = SpkiPin.ofCertificate(certificate.encoded)

            assertEquals(
                SpkiPinning.pinOf(certificate),
                shared,
                "the shared pin for $name must equal the JVM implementation Passman pairs with",
            )
        }
    }

    /** Known-answer test, so a broken digest cannot agree with a broken digest. */
    @Test
    fun sha256_matchesPublishedVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(ByteArray(0)).toHex(),
            "SHA-256 of the empty input",
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).toHex(),
            "SHA-256 of \"abc\"",
        )
    }

    @Test
    fun pinsAreLowercaseHex_ofTheExpectedWidth() {
        val pin = SpkiPin.ofCertificate(certificateOf("alice").encoded)

        assertEquals(64, pin.length, "a SHA-256 pin is 32 bytes, so 64 hex characters")
        assertEquals(pin.lowercase(), pin, "pins are lowercase; case drift is a silent mismatch")
    }
}
