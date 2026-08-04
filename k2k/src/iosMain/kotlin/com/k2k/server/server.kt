//package com.k2k.server
//
//import io.ktor.http.*
//import io.ktor.http.content.*
//import io.ktor.server.application.*
//import io.ktor.server.cio.*
//import io.ktor.server.engine.*
//import io.ktor.server.plugins.contentnegotiation.*
//import io.ktor.server.request.*
//import io.ktor.server.response.*
//import io.ktor.server.routing.*
//import io.ktor.utils.io.*
//import kotlinx.cinterop.ExperimentalForeignApi
//import kotlinx.cinterop.addressOf
//import kotlinx.cinterop.usePinned
//import kotlinx.coroutines.flow.asFlow
//import platform.Foundation.NSData
//import platform.Foundation.NSFileHandle
//import platform.Foundation.NSFileManager
//import platform.Foundation.NSURL
//import platform.Foundation.closeFile
//import platform.Foundation.create
//import platform.Foundation.dataWithContentsOfURL
//import platform.Foundation.fileHandleForWritingAtPath
//import platform.Foundation.getBytes
//import platform.Foundation.writeData
//
//@OptIn(ExperimentalForeignApi::class)
//fun startServer(
//    port: Int,
//    tempFilePath: String,
//    getFileFromName: suspend (String) -> ByteArray,
//    onFileUploaded: suspend (ByteArray, String) -> Unit,
//): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
//    return embeddedServer(CIO, port = port) {
//        install(ContentNegotiation)
//        routing {
//            post("/upload") {
//                println("upload file")
//                val multipart = call.receiveMultipart()
//                var tempFileUrl: NSURL? = null
//                var tempFileHandle: NSFileHandle? = null
//
//                multipart.forEachPart { part ->
//                    if (part is PartData.FileItem) {
//                        if (tempFileUrl == null) {
//                            val fileName = part.originalFileName ?: "temp"
//                            val fileManager = NSFileManager.defaultManager
//                            val tempPath = "$tempFilePath/$fileName"
//
//                            // Create the directory if it doesn't exist
//                            fileManager.createDirectoryAtPath(
//                                tempFilePath,
//                                true, // withIntermediateDirectories
//                                null, // attributes
//                                null  // error
//                            )
//
//                            // Create the file
//                            fileManager.createFileAtPath(
//                                tempPath,
//                                null, // empty initial content
//                                null // attributes
//                            )
//
//                            tempFileUrl = NSURL.fileURLWithPath(tempPath)
//                            tempFileHandle = NSFileHandle.fileHandleForWritingAtPath(tempPath)
//                        }
//
//                        val fileBytes = part.provider.asFlow()
//                        fileBytes.collect { bytes ->
//                            val nsData = bytes.toByteArray().toNSData()
//                            tempFileHandle?.writeData(nsData)
//                        }
//                    }
//                    part.dispose()
//                }
//
//                tempFileHandle?.closeFile()
//                println("upload complete")
//                call.respondText("200")
//
//                tempFileUrl?.let { url ->
//                    val fileData = NSData.dataWithContentsOfURL(url)
//                    fileData?.let { data ->
//                        val byteArray = data.toByteArray()
//                        onFileUploaded(byteArray, url.lastPathComponent ?: "unknown")
//                    }
//                }
//            }
//
//            get("/download/{fileName}") {
//                println("download file")
//                val fileName = call.parameters["fileName"]!!
//
//                val fileBytes = getFileFromName(fileName)
//                if (fileBytes.isNotEmpty()) {
//                    println("file bytes exist")
//                    call.respondBytes(fileBytes)
//                } else {
//                    call.respondText("File not found", status = HttpStatusCode.NotFound)
//                }
//            }
//        }
//    }
//}
//
//@OptIn(ExperimentalForeignApi::class)
//fun NSData.toByteArray(): ByteArray {
//    val byteArray = ByteArray(this.length.toInt())
//    byteArray.usePinned {
//        this.getBytes(it.addressOf(0), this.length)
//    }
//    return byteArray
//}
//
//@OptIn(ExperimentalForeignApi::class)
//fun ByteArray.toNSData(): NSData {
//    return this.usePinned { pinnedByteArray ->
//        NSData.create(bytes = pinnedByteArray.addressOf(0), length = this.size.toULong())
//    }
//}
