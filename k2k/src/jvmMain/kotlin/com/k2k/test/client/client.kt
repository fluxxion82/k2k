package com.k2k.test.client

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.*
import io.ktor.utils.io.*
import com.k2k.test.tls.K2kClientTls
import com.k2k.test.tls.buildSslContext
import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import java.io.ByteArrayOutputStream

/** The pairing endpoint is intentionally smaller than the data-server request cap. */
const val MAX_PAIRING_BUNDLE_BYTES = 16 * 1024

private fun httpScheme(tls: K2kClientTls?): String = if (tls != null) "https" else "http"

/**
 * Builds a client. When [tls] is set the OkHttp engine presents this device's certificate (mTLS) and
 * pins the server's certificate by SPKI, so a MITM or unpaired host fails the handshake before any
 * vault bytes move. When null the client is plaintext (legacy behaviour).
 */
private fun k2kHttpClient(tls: K2kClientTls?): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) { json() }
    engine {
        config {
            if (tls != null) {
                val clientTls = tls.buildSslContext()
                sslSocketFactory(clientTls.sslContext.socketFactory, clientTls.pinningTrustManager)
                hostnameVerifier { _, _ -> true }
                connectionSpecs(
                    listOf(
                        ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                            .build(),
                    ),
                )
            } else {
                connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
            }
        }
    }
}

suspend fun uploadFile(
    file: ByteArray,
    fileName: String,
    ipAddress: String,
    port: Int,
    path: String = "/upload",
    tls: K2kClientTls? = null,
) {
    val client = k2kHttpClient(tls)
    val scheme = httpScheme(tls)

    try {
        println("k2k.uploadFile to $scheme://$ipAddress:$port$path (file=$fileName, ${file.size} bytes)")
        val response = client.submitFormWithBinaryData(
            url = "$scheme://$ipAddress:$port$path",
            formData = formData {
                append("file", file, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=$fileName")
                })
            }
        ) {
            onUpload { bytesSentTotal, contentLength ->
                println("k2k.uploadFile $ipAddress:$port: $bytesSentTotal/$contentLength bytes")
            }
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "k2k.uploadFile $ipAddress:$port$path failed: ${response.status} ${response.bodyAsText()}",
            )
        }
        println("k2k.uploadFile $ipAddress:$port$path response: ${response.bodyAsText()}")
    } catch (t: Throwable) {
        println("k2k.uploadFile $ipAddress:$port$path threw: ${t::class.simpleName}: ${t.message}")
        throw t
    } finally {
        client.close()
    }
}

suspend fun downloadFile(
    fileName: String,
    ipAddress: String,
    port: Int,
    basePath: String = "/download",
    tls: K2kClientTls? = null,
): ByteArray? {
    val client = k2kHttpClient(tls)
    val url = "${httpScheme(tls)}://$ipAddress:$port$basePath/$fileName"
    println("k2k.downloadFile GET $url")
    return try {
        val response = client.get(url)
        println("k2k.downloadFile $url response: ${response.status}")
        if (response.status == HttpStatusCode.OK) {
            response.readBytes()
        } else {
            println("k2k.downloadFile $url non-200: ${response.status.description}")
            null
        }
    } catch (t: Throwable) {
        println("k2k.downloadFile $url threw: ${t::class.simpleName}: ${t.message}")
        throw t
    } finally {
        client.close()
    }
}

/** Fetch the bounded public identity bundle from the plaintext pairing listener. */
suspend fun downloadPairingBundle(
    ipAddress: String,
    port: Int,
): ByteArray? {
    val client = k2kHttpClient(tls = null)
    val url = "http://$ipAddress:$port/pairing-bundle"
    return try {
        val response = client.get(url)
        if (response.status != HttpStatusCode.OK) return null
        response.readBoundedPairingBundle()
    } finally {
        client.close()
    }
}

/** Push the local public identity bundle to the peer during the same pairing ceremony. */
suspend fun uploadPairingBundle(
    bundle: ByteArray,
    ipAddress: String,
    port: Int,
) {
    require(bundle.size <= MAX_PAIRING_BUNDLE_BYTES) { "pairing bundle too large" }
    val client = k2kHttpClient(tls = null)
    val url = "http://$ipAddress:$port/pairing-bundle"
    try {
        val response = client.post(url) { setBody(bundle) }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("pairing bundle exchange failed: ${response.status}")
        }
    } finally {
        client.close()
    }
}

private suspend fun HttpResponse.readBoundedPairingBundle(): ByteArray {
    val declaredLength = contentLength()
    if (declaredLength != null && declaredLength > MAX_PAIRING_BUNDLE_BYTES) {
        throw IllegalStateException("pairing bundle too large")
    }
    val out = ByteArrayOutputStream(minOf(MAX_PAIRING_BUNDLE_BYTES, 4 * 1024))
    val buffer = ByteArray(4 * 1024)
    val channel = bodyAsChannel()
    var total = 0
    while (true) {
        val count = channel.readAvailable(buffer, 0, buffer.size)
        if (count == -1) break
        total += count
        if (total > MAX_PAIRING_BUNDLE_BYTES) throw IllegalStateException("pairing bundle too large")
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

suspend fun requestSyncPull(
    kind: String,
    clientPublicKey: ByteArray,
    ipAddress: String,
    port: Int,
    tls: K2kClientTls? = null,
): ByteArray? {
    val client = k2kHttpClient(tls)
    val url = "${httpScheme(tls)}://$ipAddress:$port/sync-pull/$kind"
    println("k2k.requestSyncPull POST $url (${clientPublicKey.size} pubkey bytes)")
    return try {
        val response = client.post(url) {
            setBody(clientPublicKey)
        }
        println("k2k.requestSyncPull $url response: ${response.status}")
        when {
            response.status.isSuccess() && response.status.value != HttpStatusCode.NoContent.value ->
                response.readBytes()
            response.status == HttpStatusCode.NoContent -> null
            else -> throw IllegalStateException(
                "k2k.requestSyncPull $url failed: ${response.status} ${response.bodyAsText()}",
            )
        }
    } catch (t: Throwable) {
        println("k2k.requestSyncPull $url threw: ${t::class.simpleName}: ${t.message}")
        throw t
    } finally {
        client.close()
    }
}
