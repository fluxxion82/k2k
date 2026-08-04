package com.k2k.test.tls

import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * SPKI (Subject Public Key Info) pinning primitives for the peer-to-peer transport.
 *
 * There is no CA and no hostname trust: two devices pair by exchanging the SHA-256 hash of each
 * other's certificate SubjectPublicKeyInfo (the DER-encoded public key, `cert.publicKey.encoded`).
 * A TLS connection is trusted iff the presented leaf certificate's SPKI pin is one we stored at
 * pairing time. This binds trust to the long-term device key, independent of who issued the cert
 * or what host it claims to be.
 */
object SpkiPinning {
    /** SHA-256 over the certificate's DER-encoded SubjectPublicKeyInfo, as lowercase hex. */
    fun pinOf(certificate: X509Certificate): String = pinOfSpki(certificate.publicKey.encoded)

    /** SHA-256 over raw DER SubjectPublicKeyInfo bytes (e.g. `PublicKey.getEncoded()`), lowercase hex. */
    fun pinOfSpki(derSubjectPublicKeyInfo: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(derSubjectPublicKeyInfo)
        return digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
    }
}

/**
 * X509 trust manager that trusts a peer certificate chain only when the leaf's SPKI pin is in
 * [allowedPins]. Used on both ends of mTLS: the client pins the server, the server pins its
 * paired clients. An empty pin set trusts nothing (fail closed).
 *
 * Extends [X509ExtendedTrustManager] so the JSSE endpoint-identification/SNI paths also route
 * through pin checking rather than falling back to default (CA/hostname) verification.
 */
class SpkiPinningTrustManager(
    allowedPins: Collection<String>,
) : X509ExtendedTrustManager() {

    private val pins: Set<String> = allowedPins.map { it.lowercase() }.toSet()

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull()
            ?: throw java.security.cert.CertificateException("no certificate presented")
        // The leaf must still be structurally valid (not expired / not-yet-valid).
        leaf.checkValidity()
        val pin = SpkiPinning.pinOf(leaf)
        if (pin !in pins) {
            throw java.security.cert.CertificateException("certificate SPKI pin not trusted: $pin")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Convenience: expose as the plain [X509TrustManager] type where the SSL API wants it. */
fun SpkiPinningTrustManager.asX509(): X509TrustManager = this
