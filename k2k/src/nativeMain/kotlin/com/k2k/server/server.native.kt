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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.getBytes

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
            // coroutine. Without a handler on the parent context they reach the
            // global handler, which terminates the process on Kotlin/Native.
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
//                        println("upload file")
//                        val multipart = call.receiveMultipart()
//                        var tempFileUrl: NSURL? = null
//                        var tempFileHandle: NSFileHandle? = null
//
//                        multipart.forEachPart { part ->
//                            println("part: $part")
//                            if (part is PartData.FileItem) {
//                                if (tempFileUrl == null) {
//                                    val fileName = part.originalFileName ?: "temp"
//                                    val fileManager = NSFileManager.defaultManager
//                                    val tempPath = "$tempFilePath/$fileName"
//
//                                    println("tempPath: $tempPath")
//                                    // Create the directory if it doesn't exist
//                                    fileManager.createDirectoryAtPath(
//                                        tempFilePath,
//                                        true, // withIntermediateDirectories
//                                        null, // attributes
//                                        null  // error
//                                    )
//
//                                    println("created dir")
//                                    // Create the file
//                                    fileManager.createFileAtPath(
//                                        tempPath,
//                                        null, // empty initial content
//                                        null // attributes
//                                    )
//
//                                    println("created file")
//                                    tempFileUrl = NSURL.fileURLWithPath(tempPath)
//                                    tempFileHandle = NSFileHandle.fileHandleForWritingAtPath(tempPath)
//                                }
//
//                                println("get file bytes")
//                                val fileBytes = part.provider.asFlow()
//                                fileBytes.collect { bytes ->
//                                    val nsData = bytes.toByteArray().toNSData()
//                                    tempFileHandle?.writeData(nsData)
//                                }
//                            }
//                            part.dispose()
//                        }
//
//                        tempFileHandle?.closeFile()
//                        println("upload complete")
//                        call.respondText("200")
//
//                        tempFileUrl?.let { url ->
//                            val fileData = NSData.dataWithContentsOfURL(url)
//                            fileData?.let { data ->
//                                val byteArray = data.toByteArray()
//                                onFileUploaded(byteArray, url.lastPathComponent ?: "unknown")
//                            }
//                        }
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

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val byteArray = ByteArray(this.length.toInt())
    byteArray.usePinned {
        this.getBytes(it.addressOf(0), this.length)
    }
    return byteArray
}

@OptIn(ExperimentalForeignApi::class)
fun ByteArray.toNSData(): NSData {
    return this.usePinned { pinnedByteArray ->
        NSData.create(bytes = pinnedByteArray.addressOf(0), length = this.size.toULong())
    }
}
