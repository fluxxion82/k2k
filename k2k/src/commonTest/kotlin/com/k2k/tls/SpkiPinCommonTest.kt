package com.k2k.tls

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The cross-platform anchor for pinning.
 *
 * This runs on every target, and that is the entire point. A pin computed on iOS must equal one
 * computed on the JVM exactly, or two honest paired devices refuse each other forever with nothing
 * but a mismatch to go on. Testing the JVM alone would certify nothing about the platform the work
 * actually exists for — the same trap as reading a green loopback suite as proof about a device.
 *
 * The fixture is `alice.p12` from the mTLS suite, embedded as DER so it needs no filesystem. Its
 * expected pin is not this code's own output: it is the value `MutualTlsIntegrationTest` already
 * pins with, independently reproduced with `openssl x509 -pubkey | openssl pkey -pubin -outform der
 * | openssl dgst -sha256`. Three derivations, one number.
 */
class SpkiPinCommonTest {
    @Test
    fun pinOfTheFixtureCertificate_isStableAcrossPlatforms() {
        assertEquals(ALICE_PIN, SpkiPin.ofCertificate(ALICE_CERTIFICATE_DER))
    }

    @Test
    fun extractedSpki_isAContiguousRunInsideTheCertificate() {
        val spki = CertificateSpki.extract(ALICE_CERTIFICATE_DER)
        val der = ALICE_CERTIFICATE_DER

        var found = -1
        for (start in 0..(der.size - spki.size)) {
            if (der.copyOfRange(start, start + spki.size).contentEquals(spki)) {
                found = start
                break
            }
        }
        assertEquals(true, found >= 0, "the SPKI must appear verbatim in the certificate DER")
    }

    @Test
    fun sha256_matchesPublishedVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(ByteArray(0)).toHex(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).toHex(),
        )
    }

    private companion object {
        const val ALICE_PIN = "a5710166b97c6195d0dc955dbc2390f22c7b2c82b64df758002819c5ad7dd52c"

        val ALICE_CERTIFICATE_DER: ByteArray = (
        "30820303308201eba003020102021405462881ee2e68fe7c9ef921e0f0cbeccd94b17f300d06092a864886f7" +
        "0d01010b05003010310e300c06035504030c05616c6963653020170d3236303830343036323933315a180f32" +
        "303536303732373036323933315a3010310e300c06035504030c05616c69636530820122300d06092a864886" +
        "f70d01010105000382010f003082010a0282010100ba783f52dd422e212a22aace493cc2599bc2ffcabc7b94" +
        "f71e26eef9f128d5c1aeda08c89bf955a73736f36c35642653d174fe4698d9a9cbc2bb553cf4ac4cf7cf89ad" +
        "7f2fc50a92a07074e3ce8d375ed38160a04781c032da7499ed51a78e88b39136fb68f455b59adb9356a07374" +
        "1a0ec44f6606a100bf38b5193e0c45bccfad148a497cf050e45dcba5f3c76bf83e0d894bacaf75feccaa7f4d" +
        "b96047c15aeda9e883ba21a017bd61b21b8368b365d1805cb4cf3c2028b58c7925525b1253564d1d7c5018cb" +
        "edb34f3ce67d3047e687e2e7a4e15499a2a454b75adce653c32cbb1f937e8872b470ce8108928394950ca9bf" +
        "2cc6657211b5f17b088bc93b790203010001a3533051301d0603551d0e04160414a8b61aad3edb38bf71b8b7" +
        "f3e7509dad65642f7f301f0603551d23041830168014a8b61aad3edb38bf71b8b7f3e7509dad65642f7f300f" +
        "0603551d130101ff040530030101ff300d06092a864886f70d01010b05000382010100b409db2cbe54c4f7e5" +
        "a94faa161e69fff45a42b69793f2740f0622e0459c14a79b4936e6adad59731ba74f25c894f672836684ea93" +
        "6b560b0c9d5dcc8fea1dc67ffad314b475f7fb2b8287061123b4e7525bf4ea7d6351df371a46108d701fea62" +
        "d7ac553a3c997bde4c4d6a2c260e13d727461cb958003cd1cd2e415bf09f72c55fa6bbf60772d09892f05e81" +
        "bbfa1828e5d79262e0271fe2c706371a12e6e7598c1c848b95f572ff04bb816a63972c09c2beb887416af7de" +
        "d968ba1705301149403e2a696d71568f1aa10e904a0c977be23869c814924647a75e63302786435bc650029d" +
        "ae706d95d6669b7964dd73e9e400c60ad443e2bed5962fe21099b8"
            ).hexToByteArray()

        private fun String.hexToByteArray(): ByteArray =
            ByteArray(length / 2) { i ->
                ((digit(this[i * 2]) shl 4) or digit(this[i * 2 + 1])).toByte()
            }

        private fun digit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> throw IllegalArgumentException("not hex: $c")
        }
    }
}
