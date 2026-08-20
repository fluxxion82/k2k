package com.k2k.tls

/**
 * Locates the DER-encoded SubjectPublicKeyInfo inside an X.509 certificate.
 *
 * This exists because pinning needs the same bytes on every platform and only the JVM hands them
 * over. `X509Certificate.publicKey.encoded` is exactly the SPKI; Darwin has no equivalent.
 * `SecKeyCopyExternalRepresentation` returns PKCS#1 for RSA and a bare X9.63 point (`04 || X || Y`)
 * for EC, and hashing either produces a pin that can never match the JVM's.
 * `SecCertificateCopySubjectPublicKeyInfoSHA256Digest` does exactly the right thing but is SPI —
 * absent from public headers, present in the SDK stub, so it links cleanly and quietly risks App
 * Store review.
 *
 * The alternative usually recommended — prepend a hardcoded ASN.1 header chosen by key type and
 * size — is rejected here. It covers only the handful of type/size combinations someone tabulated,
 * and its failure mode is silent: an unusual key size yields a wrong pin with no error, which
 * presents as an honest paired device that can never connect and no diagnostic anywhere.
 *
 * Parsing is strictly better for this use because we only need to *locate* bytes, never interpret
 * them. Nothing here decodes a key, so it is algorithm-agnostic by construction, works for any key
 * type or size, and — being shared code — is provably identical to the JVM's answer. It also fails
 * closed and loudly: a certificate this cannot parse throws rather than returning something
 * plausible.
 */
object CertificateSpki {
    /**
     * Returns the SubjectPublicKeyInfo as it appears in [certificateDer], tag and length included.
     *
     * The result is a verbatim slice, not a re-encoding, so it is byte-identical to the JVM's
     * `publicKey.encoded` for the same certificate.
     *
     * @throws IllegalArgumentException if [certificateDer] is not a parseable X.509 certificate.
     */
    fun extract(certificateDer: ByteArray): ByteArray {
        // Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
        val certificate = readSequenceContent(certificateDer, 0, "certificate")
        // TBSCertificate ::= SEQUENCE { [0] version, serialNumber, signature, issuer, validity,
        //                               subject, subjectPublicKeyInfo, ... }
        val tbs = readSequenceContent(certificateDer, certificate.start, "tbsCertificate")

        var offset = tbs.start
        // [0] EXPLICIT version is optional and defaults to v1. Skipping it when present is what
        // makes the field index below correct for both v1 and v3 certificates.
        if (offset < tbs.end && certificateDer[offset] == CONTEXT_0) {
            offset = readField(certificateDer, offset, tbs.end, "version").end
        }
        // serialNumber, signature, issuer, validity, subject — then the SPKI.
        for (field in SKIPPED_FIELDS) {
            offset = readField(certificateDer, offset, tbs.end, field).end
        }

        val spki = readField(certificateDer, offset, tbs.end, "subjectPublicKeyInfo")
        require(certificateDer[spki.tagStart] == SEQUENCE) {
            "subjectPublicKeyInfo should be a SEQUENCE, found tag 0x${certificateDer[spki.tagStart].toHex()}"
        }
        return certificateDer.copyOfRange(spki.tagStart, spki.end)
    }

    private const val SEQUENCE: Byte = 0x30
    private const val CONTEXT_0: Byte = 0xA0.toByte()
    private val SKIPPED_FIELDS = listOf("serialNumber", "signature", "issuer", "validity", "subject")

    /** A parsed TLV: [tagStart] is its first byte, [start] its content, [end] one past its last. */
    private class Field(val tagStart: Int, val start: Int, val end: Int)

    private fun readSequenceContent(bytes: ByteArray, from: Int, what: String): Field {
        val field = readField(bytes, from, bytes.size, what)
        require(bytes[field.tagStart] == SEQUENCE) {
            "$what should be a SEQUENCE, found tag 0x${bytes[field.tagStart].toHex()}"
        }
        return field
    }

    /**
     * Reads one DER TLV starting at [from], bounded by [limit].
     *
     * Every length is validated against the remaining input, so a truncated or hostile certificate
     * throws here rather than producing a slice that happens to look like a key.
     */
    private fun readField(bytes: ByteArray, from: Int, limit: Int, what: String): Field {
        require(from < limit) { "ran out of input looking for $what" }
        // Multi-byte tags (low 5 bits all set) do not occur in the certificate fields walked here.
        require(bytes[from].toInt() and 0x1F != 0x1F) { "unsupported multi-byte tag for $what" }
        var cursor = from + 1
        require(cursor < limit) { "truncated length for $what" }

        val first = bytes[cursor].toInt() and 0xFF
        cursor++
        val length: Int
        if (first and 0x80 == 0) {
            length = first
        } else {
            val lengthBytes = first and 0x7F
            // 0x80 is the indefinite form: legal in BER, forbidden in DER, and a certificate using
            // it is not one we should be pinning.
            require(lengthBytes in 1..4) { "unsupported length encoding for $what" }
            require(cursor + lengthBytes <= limit) { "truncated length for $what" }
            var value = 0
            repeat(lengthBytes) {
                value = (value shl 8) or (bytes[cursor].toInt() and 0xFF)
                cursor++
            }
            require(value >= 0) { "length overflow for $what" }
            length = value
        }

        val end = cursor + length
        require(end in cursor..limit) { "$what claims $length bytes but only ${limit - cursor} remain" }
        return Field(tagStart = from, start = cursor, end = end)
    }

    private fun Byte.toHex(): String {
        val v = toInt() and 0xFF
        return "${HEX[v shr 4]}${HEX[v and 0x0F]}"
    }

    private const val HEX = "0123456789abcdef"
}
