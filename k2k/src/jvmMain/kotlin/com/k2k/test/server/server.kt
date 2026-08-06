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
import com.k2k.test.tls.SpkiPinning
import com.k2k.test.tls.buildNettySslContext
import io.ktor.server.netty.NettyApplicationCall
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslHandler
import java.security.cert.X509Certificate
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
    // Per-operation authorization. Called with the op ("passwords"/"pgp-keys"/"keystore") and the
    // connecting client's verified SPKI pin (null if unavailable). Return false to reject with 403.
    // Null (default) = allow all, so the plaintext pairing server and legacy callers are unaffected.
    // ClientAuth.REQUIRE already limits callers to paired devices; this narrows *which op* each may do.
    authorizer: (suspend (op: String, pin: String?) -> Boolean)? = null,
    // Optional diagnostics hook. It is invoked from download handling with the protocol negotiated
    // by the connecting peer, allowing integration tests to observe the actual client handshake.
    onPeerTlsProtocol: ((String?) -> Unit)? = null,
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
                if (!authorizeOp("passwords", authorizer)) return@post
                handleUpload(tempFilePath, onFileUploaded, maxUploadBytes)
            }

            get("/download/{fileName}") {
                // Scope the op to the requested name so the authorizer can allow specific
                // artifacts (e.g. public keys) without blanket-approving future handlers.
                if (!authorizeOp("download/${call.parameters["fileName"]}", authorizer)) return@get
                onPeerTlsProtocol?.invoke(peerTlsProtocol())
                handleDownload(call.parameters["fileName"]!!, getFileFromName)
            }

            for ((kind, handler) in artifactUploadHandlers) {
                post("/upload/$kind") {
                    if (!authorizeOp(kind, authorizer)) return@post
                    handleUpload(tempFilePath, handler, maxUploadBytes)
                }
            }

            for ((kind, handler) in artifactDownloadHandlers) {
                get("/download/$kind/{fileName}") {
                    if (!authorizeOp(kind, authorizer)) return@get
                    onPeerTlsProtocol?.invoke(peerTlsProtocol())
                    handleDownload(call.parameters["fileName"]!!, handler)
                }
            }

            for ((kind, handler) in syncPullHandlers) {
                post("/sync-pull/$kind") {
                    if (!authorizeOp(kind, authorizer)) return@post
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
                        // Log the detail locally; the response body stays generic so internal
                        // paths/crypto errors never leak to the network.
                        println("sync-pull '$kind' failed: ${t.message}")
                        call.respond(HttpStatusCode.InternalServerError, "sync-pull failed")
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

/**
 * Verified SPKI pin of the connecting client's leaf certificate, or null if there is no TLS session
 * (plaintext) or the peer is unverified. With ClientAuth.REQUIRE the peer cert is always present and
 * already pin-checked by the trust manager; this identifies *which* paired device is calling.
 */
private fun io.ktor.server.routing.RoutingContext.peerSpkiPin(): String? = runCatching {
    // In ktor routing, `call` is a RoutingCall wrapper -> pipelineCall -> engineCall (the Netty call).
    val engineCall = (call as? io.ktor.server.routing.RoutingCall)?.pipelineCall?.engineCall ?: call
    val ctx = (engineCall as? NettyApplicationCall)?.context ?: return null
    val ssl = ctx.pipeline().get(SslHandler::class.java) ?: return null
    val leaf = ssl.engine().session.peerCertificates.firstOrNull() as? X509Certificate ?: return null
    SpkiPinning.pinOf(leaf)
}.getOrNull()

/** Negotiated TLS protocol for the connecting peer, or null for plaintext/unavailable sessions. */
private fun io.ktor.server.routing.RoutingContext.peerTlsProtocol(): String? = runCatching {
    val engineCall = (call as? io.ktor.server.routing.RoutingCall)?.pipelineCall?.engineCall ?: call
    val ctx = (engineCall as? NettyApplicationCall)?.context ?: return null
    val ssl = ctx.pipeline().get(SslHandler::class.java) ?: return null
    ssl.engine().session.protocol
}.getOrNull()

/**
 * Runs [authorizer] for [op] against the caller's pin. Returns true to proceed; on denial responds
 * 403 and returns false so the route short-circuits. A null authorizer allows everything.
 */
private suspend fun io.ktor.server.routing.RoutingContext.authorizeOp(
    op: String,
    authorizer: (suspend (op: String, pin: String?) -> Boolean)?,
): Boolean {
    if (authorizer == null) return true
    if (authorizer(op, peerSpkiPin())) return true
    call.respond(HttpStatusCode.Forbidden, "operation '$op' not permitted for this device")
    return false
}

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
    var uploadName: String? = null
    var tempOutputStream: OutputStream? = null
    var rejected = false
    var tooLarge = false
    var written = 0L
    var fileItemSeen = false
    var partProcessingFailure: Throwable? = null
    try {
        multipart.forEachPart { part ->
            try {
                if (part is PartData.FileItem) {
                    if (fileItemSeen) {
                        rejected = true
                    } else {
                        fileItemSeen = true
                        val rawName = part.originalFileName ?: "blob"
                        val safeName = File(rawName).name
                        if (safeName.isEmpty() || safeName == "." || safeName == "..") {
                            rejected = true
                        } else {
                            // Unique temp file per request: concurrent uploads of the same logical
                            // name must not share (and truncate) one on-disk file.
                            uploadName = safeName
                            tempFile = File.createTempFile("$safeName.", ".part", File(tempFilePath))
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
            } finally {
                part.dispose()
            }
        }
    } catch (e: UploadTooLargeException) {
        tooLarge = true
    } catch (t: Throwable) {
        partProcessingFailure = t
    } finally {
        try {
            tempOutputStream?.close()
        } catch (t: Throwable) {
            tempFile?.delete()
            throw t
        }
        if (partProcessingFailure != null) tempFile?.delete()
    }
    partProcessingFailure?.let { throw it }
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
            onFileUploaded(uploaded.readBytes(), uploadName ?: uploaded.name)
            call.respondText("200")
        } catch (t: Throwable) {
            println("upload processing failed: ${t.message}")
            call.respond(HttpStatusCode.InternalServerError, "upload processing failed")
        } finally {
            uploaded.delete()
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
