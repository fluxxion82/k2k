package com.k2k.test.client

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.forms.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.*

suspend fun uploadFile(
    file: ByteArray,
    fileName: String,
    ipAddress: String,
    port: Int,
    path: String = "/upload",
) {
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }

    try {
        println("k2k.uploadFile to http://$ipAddress:$port$path (file=$fileName, ${file.size} bytes)")
        val response = client.submitFormWithBinaryData(
            url = "http://$ipAddress:$port$path",
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
): ByteArray? {
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }
    val url = "http://$ipAddress:$port$basePath/$fileName"
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

suspend fun requestSyncPull(
    kind: String,
    clientPublicKey: ByteArray,
    ipAddress: String,
    port: Int,
): ByteArray? {
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }
    val url = "http://$ipAddress:$port/sync-pull/$kind"
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
