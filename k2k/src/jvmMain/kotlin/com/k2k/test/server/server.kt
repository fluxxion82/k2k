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
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    return embeddedServer(Netty, port = port) {
        install(ContentNegotiation)
        routing {
            post("/upload") {
                handleUpload(tempFilePath, onFileUploaded)
            }

            get("/download/{fileName}") {
                handleDownload(call.parameters["fileName"]!!, getFileFromName)
            }

            for ((kind, handler) in artifactUploadHandlers) {
                post("/upload/$kind") {
                    handleUpload(tempFilePath, handler)
                }
            }

            for ((kind, handler) in artifactDownloadHandlers) {
                get("/download/$kind/{fileName}") {
                    handleDownload(call.parameters["fileName"]!!, handler)
                }
            }

            for ((kind, handler) in syncPullHandlers) {
                post("/sync-pull/$kind") {
                    val clientPubkey = call.receive<ByteArray>()
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

private suspend fun io.ktor.server.routing.RoutingContext.handleUpload(
    tempFilePath: String,
    onFileUploaded: suspend (ByteArray, String) -> Unit,
) {
    println("upload file")
    val multipart = call.receiveMultipart()
    var tempFile: File? = null
    var tempOutputStream: OutputStream? = null
    var rejected = false
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
                    part.provider.asFlow().collect {
                        tempOutputStream?.write(it.toByteArray())
                    }
                }
            }
            part.dispose()
        }
    } finally {
        tempOutputStream?.close()
    }
    println("upload complete")

    val uploaded = tempFile
    if (rejected || uploaded == null || !uploaded.exists()) {
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
