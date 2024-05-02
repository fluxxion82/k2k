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

suspend fun uploadFile(file: ByteArray, fileName: String, ipAddress: String, port: Int) {
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }

    val response = client.submitFormWithBinaryData(
        url = "http://$ipAddress:$port/upload",
        formData = formData {
            append("file", file, Headers.build {
                append(HttpHeaders.ContentDisposition, "filename=$fileName")
            })
        }
    ) {
        onUpload { bytesSentTotal, contentLength ->
            println("Uploaded $bytesSentTotal bytes from $contentLength")
        }
    }
    println("Server response: ${response.bodyAsText()}")
    client.close()
}

suspend fun downloadFile(fileName: String, ipAddress: String, port: Int): ByteArray? {
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }
    val response = client.get("http://$ipAddress:$port/download/$fileName")

    return if (response.status == HttpStatusCode.OK) {
        val bytes = response.readBytes()
        bytes
    } else {
        println("Error: ${response.status.description}")
        null
    }.also { client.close() }
}
