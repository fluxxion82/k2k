package com.k2k.test.tls

import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * TLS material for the k2k server side of a mutually-authenticated, SPKI-pinned connection.
 *
 * @param keyStore PKCS#12 (or equivalent) holding this device's identity key + self-signed cert.
 * @param keyStorePassword password for the keystore and the key entry (they are the same here).
 * @param keyAlias entry holding the identity key (the app uses `passmanMain`).
 * @param allowedClientPins SPKI pins (SHA-256 hex of DER SubjectPublicKeyInfo) of paired devices
 *        permitted to connect. Empty = trust nobody (fail closed).
 */
class K2kServerTls(
    val keyStore: KeyStore,
    val keyStorePassword: CharArray,
    val keyAlias: String,
    val allowedClientPins: Set<String>,
)

/**
 * TLS material for the k2k client side. Presents this device's cert and trusts the server only
 * when the server's leaf SPKI pin is in [serverPins].
 */
class K2kClientTls(
    val keyStore: KeyStore,
    val keyStorePassword: CharArray,
    val keyAlias: String,
    val serverPins: Set<String>,
)

/**
 * TLS state for an OkHttp client: the socket factory presents this device's certificate and the
 * trust manager accepts only a paired server's SPKI pin.
 */
class K2kClientSslContext internal constructor(
    val sslContext: SSLContext,
    val pinningTrustManager: SpkiPinningTrustManager,
)

/** Build the JSSE state used by the TLS 1.3-capable OkHttp client engine. */
fun K2kClientTls.buildSslContext(): K2kClientSslContext {
    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    keyManagerFactory.init(keyStore, keyStorePassword)
    val pinningTrustManager = SpkiPinningTrustManager(serverPins)
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.keyManagers, arrayOf<TrustManager>(pinningTrustManager), null)
    return K2kClientSslContext(sslContext, pinningTrustManager)
}

/**
 * Resolve the key-entry alias tolerantly: exact match, then case-insensitive (PKCS#12 loaders
 * lowercase friendlyNames), then the first key entry. Avoids a null-key crash when a keystore
 * reports the alias in different case than it was written.
 */
internal fun KeyStore.resolveKeyAlias(preferred: String): String {
    if (isKeyEntry(preferred)) return preferred
    val all = aliases().toList()
    return all.firstOrNull { it.equals(preferred, ignoreCase = true) && isKeyEntry(it) }
        ?: all.firstOrNull { isKeyEntry(it) }
        ?: preferred
}

internal fun K2kServerTls.privateKey(): PrivateKey {
    val alias = keyStore.resolveKeyAlias(keyAlias)
    return keyStore.getKey(alias, keyStorePassword) as? PrivateKey
        ?: throw IllegalStateException("no private key for alias '$keyAlias' in keystore")
}

internal fun K2kServerTls.certificateChain(): Array<X509Certificate> =
    (keyStore.getCertificateChain(keyStore.resolveKeyAlias(keyAlias)) ?: emptyArray())
        .map { it as X509Certificate }
        .toTypedArray()

/**
 * Build a Netty server [SslContext] that presents this device's certificate, REQUIRES a client
 * certificate, and validates the client cert against [SpkiPinningTrustManager] (paired pins only).
 * TLS 1.3 preferred, 1.2 permitted; no plaintext fallback.
 */
fun K2kServerTls.buildNettySslContext(): SslContext =
    SslContextBuilder.forServer(privateKey(), *certificateChain())
        .clientAuth(ClientAuth.REQUIRE)
        .trustManager(SpkiPinningTrustManager(allowedClientPins))
        .protocols("TLSv1.3", "TLSv1.2")
        .build()
