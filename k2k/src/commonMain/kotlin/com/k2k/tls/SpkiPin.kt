package com.k2k.tls

/**
 * SHA-256 of [bytes].
 *
 * Platform-provided because there is no multiplatform digest in the Kotlin or kotlinx libraries and
 * a hand-rolled one would be a liability in the one place correctness is not negotiable: JVM uses
 * `MessageDigest`, Darwin uses CommonCrypto's `CC_SHA256`.
 */
internal expect fun sha256(bytes: ByteArray): ByteArray

/** Lowercase hex. The pin's wire and storage form — case drift here is a silent trust mismatch. */
internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val v = byte.toInt() and 0xFF
        out.append(HEX[v shr 4]).append(HEX[v and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"

/**
 * The value two paired devices compare to decide whether to trust a TLS peer.
 *
 * There is no CA and no hostname trust here. Devices exchange these at pairing time and a connection
 * is trusted iff the presented leaf's pin is one that was stored then, which binds trust to the
 * long-term device key rather than to who issued the certificate or what host it claims to be.
 *
 * Shared across platforms deliberately: the JVM could compute this from `publicKey.encoded`, but iOS
 * cannot (see [CertificateSpki]), and two implementations of a value that must agree exactly is a
 * standing invitation to drift.
 */
object SpkiPin {
    /**
     * The pin for a DER-encoded X.509 certificate, as lowercase hex.
     *
     * @throws IllegalArgumentException if the certificate cannot be parsed.
     */
    fun ofCertificate(certificateDer: ByteArray): String =
        ofSubjectPublicKeyInfo(CertificateSpki.extract(certificateDer))

    /** The pin for raw DER SubjectPublicKeyInfo bytes, as lowercase hex. */
    fun ofSubjectPublicKeyInfo(derSubjectPublicKeyInfo: ByteArray): String =
        sha256(derSubjectPublicKeyInfo).toHex()
}
