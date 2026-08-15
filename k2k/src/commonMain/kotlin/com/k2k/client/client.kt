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

    } catch (e: Exception) {
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
    val response = client.get("http://$ipAddress:$port/download/$fileName")
    return if (response.status == HttpStatusCode.OK) {
        val bytes = response.readRawBytes()
        bytes
    } else {
        null
    }.also { client.close() }
}
