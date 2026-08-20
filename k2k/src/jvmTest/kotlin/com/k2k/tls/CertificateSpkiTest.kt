package com.k2k.tls

import java.security.KeyStore
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The whole trust model rests on one value agreeing across platforms: SHA-256 over the certificate's
 * DER SubjectPublicKeyInfo. The JVM gets that for free from `publicKey.encoded`. Darwin has no
 * equivalent — `SecKeyCopyExternalRepresentation` returns PKCS#1 for RSA and a bare X9.63 point for
 * EC, neither of which is SPKI, and `SecCertificateCopySubjectPublicKeyInfo` is SPI. So iOS has to
 * derive the same bytes by parsing them out of the certificate.
 *
 * That parser is shared code, and this is the test that anchors it: extract the SPKI from the DER
 * and assert it is byte-identical to what the JVM independently reports. If those ever diverge, an
 * honest paired device fails to connect and keeps failing, with a pin mismatch and no diagnostic —
 * so the parser is checked against an oracle rather than against its own expectations.
 */
class CertificateSpkiTest {
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
    fun extractedSpki_matchesTheJvmEncoding_forEveryTestCertificate() {
        for (name in listOf("alice", "bob", "mallory")) {
            val certificate = certificateOf(name)

            val parsed = CertificateSpki.extract(certificate.encoded)

            assertContentEquals(
                certificate.publicKey.encoded,
                parsed,
                "SPKI parsed from $name's certificate DER must equal the JVM's publicKey.encoded",
            )
        }
    }

    /**
     * The SPKI is a contiguous run of bytes inside the certificate, not something reassembled. If
     * that stops holding, the parser is building bytes rather than locating them and the equality
     * above could pass for the wrong reason.
     */
    @Test
    fun extractedSpki_isASubstringOfTheCertificateDer() {
        val certificate = certificateOf("alice")
        val der = certificate.encoded

        val parsed = CertificateSpki.extract(der)

        val index = der.toList().windowed(parsed.size).indexOfFirst { it.toByteArray().contentEquals(parsed) }
        assertTrue(index >= 0, "the SPKI should appear verbatim inside the certificate DER")
    }

    @Test
    fun malformedInput_failsRatherThanReturningWrongBytes() {
        assertFailsWith<IllegalArgumentException> { CertificateSpki.extract(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { CertificateSpki.extract(byteArrayOf(0x30, 0x05, 1, 2, 3)) }
        // A well-formed SEQUENCE that is not a certificate: must not yield plausible-looking bytes.
        assertFailsWith<IllegalArgumentException> {
            CertificateSpki.extract(byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x00))
        }
    }
}
