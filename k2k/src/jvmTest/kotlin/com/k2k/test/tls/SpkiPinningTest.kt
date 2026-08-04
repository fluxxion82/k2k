package com.k2k.test.tls

import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpkiPinningTest {

    // A long-dated (~30y) self-signed RSA cert and the SHA-256 of its DER SubjectPublicKeyInfo,
    // generated with openssl. The pin binds to the public key, so re-issuing the cert with the
    // same key would keep the same pin.
    private val certDerB64 =
        "MIIDETCCAfmgAwIBAgIUKB7tUUMQFNNyRQLfRq5xb0ItrAowDQYJKoZIhvcNAQELBQAwFzEVMBMGA1UEAwwMcGFzc21hbi10ZXN0MCAXDTI2MDgwNDA2MTE1MVoYDzIwNTYwNzI3MDYxMTUxWjAXMRUwEwYDVQQDDAxwYXNzbWFuLXRlc3QwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQD31c1tsdModyyH9TGAN1EeOmsdenNm9pDx1HWRslyoJTZFgKOAlZmwU0iVGOjQ0Ced1cv6PHRRVg5pJ43k6LQ9g18dwdaZrlideLQxWU/mqlyHfceAuCpBG8l/KEIt4Ujur+0au4Q5WnDt1ayiEDy9TpChax2lam7k0rMY3rNXEF2NJC8yBbr9IvlgZBCkAQ4x6pm31iLgRL2Emt1IuSrNFxXEIXa/bS5Qn4DfnU5oo1K6DFlLUJ/RC3HOrJcWX9mh2xw47alYyZUxV6HIHh86qKFoS9ZYblLJH4PHGV93LJRx3bJwV4GuTIob5R+MkAw3NYb3zmTMIIgcuXUX3WBvAgMBAAGjUzBRMB0GA1UdDgQWBBT+ao+II32NlslOVKPDw6Dy1md1xzAfBgNVHSMEGDAWgBT+ao+II32NlslOVKPDw6Dy1md1xzAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQC8pbrErpuib/EE4V7NUoKPNqjr3GZ5C71m/r2DX2sMEU+BnKdS1rs0J/DlLiawOO2aE+Stmgp1ytd7X58/lxnI7uTv8gtb3P/jcbEf69Mnv6wilL1zePD7lWfT4okAiI/0cbuoK3XKY0hlL6WJ5BS0L1Jf60POeNB4JafvHwbH7SeCyxKNdWzhTe28QgO7Z8r8tPViMhx71Tdp1w0eM2DQGRyKWZuQzwoy9UUy1ETJmxF9crulMKWs7w6kJLbbyUkLyXiIvAOwMJgMJZNbXjxPhaNuD0ROcGy6WVYmBEaS30O17+qTgDaifF4lf30pH+x/aP+fJo+egpUN96EN+/+M"
    private val expectedPin = "866801cd0450202c813780cc6cf2c7adeea2ce0659681bdd06343f9847ec95f6"

    private fun cert(): X509Certificate {
        val der = Base64.getDecoder().decode(certDerB64)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    @Test
    fun pinOfSpki_isDeterministicSha256Hex() {
        // SHA-256("abc") — known answer.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SpkiPinning.pinOfSpki("abc".toByteArray()),
        )
    }

    @Test
    fun pinOf_matchesOpensslComputedPin() {
        assertEquals(expectedPin, SpkiPinning.pinOf(cert()))
    }

    @Test
    fun trustManager_acceptsPinnedCertificate() {
        val tm = SpkiPinningTrustManager(setOf(expectedPin))
        // Should not throw.
        tm.checkServerTrusted(arrayOf(cert()), "RSA")
        tm.checkClientTrusted(arrayOf(cert()), "RSA")
    }

    @Test
    fun trustManager_isCaseInsensitiveOnPin() {
        val tm = SpkiPinningTrustManager(setOf(expectedPin.uppercase()))
        tm.checkServerTrusted(arrayOf(cert()), "RSA")
    }

    @Test
    fun trustManager_rejectsUnpinnedCertificate() {
        val tm = SpkiPinningTrustManager(setOf("00".repeat(32)))
        assertFailsWith<CertificateException> { tm.checkServerTrusted(arrayOf(cert()), "RSA") }
    }

    @Test
    fun trustManager_emptyPinsFailsClosed() {
        val tm = SpkiPinningTrustManager(emptySet())
        assertFailsWith<CertificateException> { tm.checkServerTrusted(arrayOf(cert()), "RSA") }
    }

    @Test
    fun trustManager_rejectsEmptyChain() {
        val tm = SpkiPinningTrustManager(setOf(expectedPin))
        assertFailsWith<CertificateException> { tm.checkServerTrusted(emptyArray(), "RSA") }
    }
}
