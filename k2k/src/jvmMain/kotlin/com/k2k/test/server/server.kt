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
import java.io.File
import kotlin.reflect.KSuspendFunction1

fun startServer(
    port: Int,
    tempFilePath: String,
    getFileFromName: suspend (String) -> ByteArray,
    onFileUploaded: suspend (ByteArray, String) -> Unit,
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    return embeddedServer(Netty, port = port) {
        install(ContentNegotiation)
        routing {
            post("/upload") {
                println("upload file")
                val multipart = call.receiveMultipart()
                var tempFile: File? = null
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        if (tempFile == null) {
                            tempFile = File("$tempFilePath/${part.originalFileName}")
                            tempFile?.createNewFile()
                        }

                        val fileBytes = part.streamProvider().readBytes()
                        tempFile!!.writeBytes(fileBytes)
                    }
                    part.dispose()
                }
                println("upload complete")
                call.respondText("200")

                tempFile?.let { onFileUploaded(it.readBytes(), it.name) }
            }

            get("/download/{fileName}") {
                println("download file")
                val fileName = call.parameters["fileName"]!!

                val fileBytes = getFileFromName(fileName)
                if (fileBytes.isNotEmpty()) {
                    println("file bytes exist")
                    call.respondBytes(fileBytes)
                } else {
                    call.respondText("File not found", status = HttpStatusCode.NotFound)
                }
            }
        }
    }
}
