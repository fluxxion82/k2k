package com.k2k.client

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*

suspend fun uploadFile(file: ByteArray, ipAddress: String, port: Int) {
    val client = HttpClient(CIO) {
        install(ContentNegotiation)
    }

    try {
        val response = client.post("http://$ipAddress:$port/upload") {
            setBody(file)
            contentType(ContentType.Application.OctetStream)
        }

        println("Upload completed. Status: ${response.status}")
        println("Response body: ${response.bodyAsText()}")
    } catch (e: Exception) {
        println("Upload failed: ${e.message}")
        throw e
    } finally {
        client.close()
    }
}

suspend fun downloadFile(fileName: String, ipAddress: String, port: Int): ByteArray? {
    val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }
    println("download file from client")
    val response = client.get("http://$ipAddress:$port/download/$fileName")
    println("download file from client with response: $response")
    return if (response.status == HttpStatusCode.OK) {
        val bytes = response.readRawBytes()
        bytes
    } else {
        println("Error: ${response.status.description}")
        null
    }.also { client.close() }
}
