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
            getFileFromName: suspend (String) -> ByteArray,
            onFileUploaded: suspend (ByteArray) -> Unit,
        ): PlatformServer {
            val server = embeddedServer(Netty, port = port) {
                install(ContentNegotiation)
                routing {
                    post("/upload") {
                        try {
                            val byteArray = call.receive<ByteArray>()

                            onFileUploaded(byteArray)

                            call.respond(HttpStatusCode.OK, "Data received successfully")
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.InternalServerError, "Upload failed: ${e.message}")
                        }
                    }

                    get("/download/{fileName}") {
                        val fileName = call.parameters["fileName"]!!

                        val fileBytes = getFileFromName(fileName)
                        if (fileBytes.isNotEmpty()) {
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
