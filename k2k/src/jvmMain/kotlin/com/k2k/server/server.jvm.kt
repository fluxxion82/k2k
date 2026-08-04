package com.k2k.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

actual class PlatformServer private constructor(
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine. Configuration>
) {
    actual fun start() {
        server.start()
    }

    actual fun stop() {
        server.stop()
    }

    actual companion object {
        actual fun create(
            port: Int,
            tempFilePath: String,
            getFileFromName: suspend (String) -> ByteArray,
            onFileUploaded: suspend (ByteArray) -> Unit,
        ): PlatformServer {
            val server = embeddedServer(Netty, port = port) {
                install(ContentNegotiation)
                routing {
                    post("/upload") {
//                        println("upload file")
//                        val multipart = call.receiveMultipart()
//                        var tempFile: File? = null
//                        multipart.forEachPart { part ->
//                            println("part: $part")
//                            if (part is PartData.FileItem) {
//                                if (tempFile == null) {
//                                    println("create temp file")
//                                    tempFile = File("$tempFilePath/${part.originalFileName}")
//                                    tempFile?.createNewFile()
//                                }
//
//                                println("get file bytes")
//                                val fileBytes = part.provider.asFlow()
//                                fileBytes.collect  {
//                                    tempFile!!.writeBytes(it.toByteArray())
//                                }
//                            }
//                            part.dispose()
//                        }
//                        println("upload complete")
//                        call.respondText("200")
//
//                        tempFile?.let { onFileUploaded(it.readBytes(), it.name) }

                        try {
                            println("Upload endpoint hit")
                            val byteArray = call.receive<ByteArray>()

                            println("Received ${byteArray.size} bytes")
                            onFileUploaded(byteArray)

                            call.respond(HttpStatusCode.OK, "Data received successfully")
                        } catch (e: Exception) {
                            println("Upload error: ${e.message}")
                            e.printStackTrace()
                            call.respond(HttpStatusCode.InternalServerError, "Upload failed: ${e.message}")
                        }
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

            return PlatformServer(server)
        }
    }
}
