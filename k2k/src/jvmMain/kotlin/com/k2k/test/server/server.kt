package com.k2k.test.server

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import com.k2k.test.tls.K2kServerTls
import com.k2k.test.tls.buildNettySslContext
import io.netty.handler.ssl.SslContext
import kotlinx.coroutines.flow.asFlow
import java.io.File
import java.io.OutputStream

fun startServer(
    port: Int,
    tempFilePath: String,
    getFileFromName: suspend (String) -> ByteArray,
    onFileUploaded: suspend (ByteArray, String) -> Unit,
    artifactUploadHandlers: Map<String, suspend (ByteArray, String) -> Unit> = emptyMap(),
    artifactDownloadHandlers: Map<String, suspend (String) -> ByteArray> = emptyMap(),
    syncPullHandlers: Map<String, suspend (ByteArray) -> ByteArray?> = emptyMap(),
    serverTls: K2kServerTls? = null,
    // DoS caps applied BEFORE any crypto/parse: reject an upload once its streamed body exceeds
    // maxUploadBytes, and reject a sync-pull whose request body (the client public key) is larger
    // than maxSyncPullRequestBytes. Both bound memory/disk an unauthenticated-shaped flood can force.
    maxUploadBytes: Long = 25L * 1024 * 1024,
    maxSyncPullRequestBytes: Long = 64L * 1024,
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    // Built once and shared; a fresh SslHandler is created per channel below. When null the
    // server stays plaintext (legacy behaviour); when set, every connection is mutually
    // authenticated and the peer certificate is SPKI-pinned to a paired device.
    val sslContext: SslContext? = serverTls?.buildNettySslContext()
    return embeddedServer(
        Netty,
        configure = {
            // The configure-capable overload takes no `port`, so bind the connector here.
            connector { this.port = port }
            if (sslContext != null) {
                channelPipelineConfig = { pipeline ->
                    pipeline.addFirst("ssl", sslContext.newHandler(pipeline.channel().alloc()))
                }
            }
        },
    ) {
        install(ContentNegotiation)
        routing {
            post("/upload") {
                handleUpload(tempFilePath, onFileUploaded, maxUploadBytes)
            }

            get("/download/{fileName}") {
                handleDownload(call.parameters["fileName"]!!, getFileFromName)
            }

            for ((kind, handler) in artifactUploadHandlers) {
                post("/upload/$kind") {
                    handleUpload(tempFilePath, handler, maxUploadBytes)
                }
            }

            for ((kind, handler) in artifactDownloadHandlers) {
                get("/download/$kind/{fileName}") {
                    handleDownload(call.parameters["fileName"]!!, handler)
                }
            }

            for ((kind, handler) in syncPullHandlers) {
                post("/sync-pull/$kind") {
                    val declaredLength = call.request.contentLength()
                    if (declaredLength != null && declaredLength > maxSyncPullRequestBytes) {
                        call.respond(HttpStatusCode.PayloadTooLarge, "request too large")
                        return@post
                    }
                    val clientPubkey = call.receive<ByteArray>()
                    if (clientPubkey.size > maxSyncPullRequestBytes) {
                        call.respond(HttpStatusCode.PayloadTooLarge, "request too large")
                        return@post
                    }
                    val result = try {
                        handler(clientPubkey)
                    } catch (t: Throwable) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            t.message ?: "sync-pull failed",
                        )
                        return@post
                    }
                    if (result != null && result.isNotEmpty()) {
                        call.respondBytes(result)
                    } else {
                        call.respond(HttpStatusCode.NoContent, "no local data")
                    }
                }
            }
        }
    }
}

private class UploadTooLargeException : Exception("upload exceeds size cap")

private suspend fun io.ktor.server.routing.RoutingContext.handleUpload(
    tempFilePath: String,
    onFileUploaded: suspend (ByteArray, String) -> Unit,
    maxUploadBytes: Long,
) {
    println("upload file")
    // Reject early on a declared oversize body so a flood never gets to stream to disk.
    val declaredLength = call.request.contentLength()
    if (declaredLength != null && declaredLength > maxUploadBytes) {
        call.respond(HttpStatusCode.PayloadTooLarge, "upload too large")
        return
    }
    val multipart = call.receiveMultipart()
    var tempFile: File? = null
    var tempOutputStream: OutputStream? = null
    var rejected = false
    var tooLarge = false
    var written = 0L
    try {
        multipart.forEachPart { part ->
            if (part is PartData.FileItem) {
                if (tempFile == null && !rejected) {
                    val rawName = part.originalFileName ?: "blob"
                    val safeName = File(rawName).name
                    if (safeName.isEmpty() || safeName == "." || safeName == "..") {
                        rejected = true
                    } else {
                        tempFile = File(tempFilePath, safeName).apply { createNewFile() }
                        tempOutputStream = tempFile!!.outputStream().buffered()
                    }
                }
                if (!rejected) {
                    part.provider.asFlow().collect { chunk ->
                        val arr = chunk.toByteArray()
                        written += arr.size
                        // Enforce the cap mid-stream even when Content-Length lied or was chunked.
                        if (written > maxUploadBytes) throw UploadTooLargeException()
                        tempOutputStream?.write(arr)
                    }
                }
            }
            part.dispose()
        }
    } catch (e: UploadTooLargeException) {
        tooLarge = true
    } finally {
        tempOutputStream?.close()
    }
    println("upload complete")

    val uploaded = tempFile
    if (tooLarge) {
        uploaded?.delete()
        call.respond(HttpStatusCode.PayloadTooLarge, "upload too large")
    } else if (rejected || uploaded == null || !uploaded.exists()) {
        uploaded?.delete()
        call.respond(HttpStatusCode.BadRequest, "no valid file uploaded")
    } else {
        try {
            onFileUploaded(uploaded.readBytes(), uploaded.name)
            call.respondText("200")
        } catch (t: Throwable) {
            call.respond(
                HttpStatusCode.InternalServerError,
                t.message ?: "upload processing failed",
            )
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleDownload(
    fileName: String,
    getFileFromName: suspend (String) -> ByteArray,
) {
    println("download file")
    val fileBytes = getFileFromName(fileName)
    if (fileBytes.isNotEmpty()) {
        println("file bytes exist")
        call.respondBytes(fileBytes)
    } else {
        call.respondText("File not found", status = HttpStatusCode.NotFound)
    }
}
