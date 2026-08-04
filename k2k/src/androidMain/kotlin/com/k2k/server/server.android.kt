package com.k2k.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob


actual class PlatformServer private constructor(
    private val server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
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
            // Bind failures (EADDRINUSE) are raised inside the engine's own
            // coroutine; a handler on the parent context keeps them from
            // reaching the global (crashing) handler.
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default +
                    CoroutineExceptionHandler { _, error ->
                        println("k2k server error: ${error.message}")
                    },
            )
            val server = scope.embeddedServer(
                CIO,
                port = port,
                parentCoroutineContext = scope.coroutineContext,
            ) {
                install(ContentNegotiation)
                routing {
                    post("/upload") {
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
