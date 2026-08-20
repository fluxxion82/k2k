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
import io.ktor.server.plugins.origin
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslHandler
import java.security.cert.X509Certificate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.asFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The sole non-legacy operation exposed by the plaintext pairing listener.
 *
 * This is deliberately a separate [startServer] mode rather than another set of routes layered on
 * the data server. When non-null, the data/upload/sync routes are not registered at all.
 */
data class PairingBundleExchange(
    val localBundle: suspend () -> ByteArray,
    val validatePeerBundle: (ByteArray) -> Boolean,
    /**
     * Receives the raw peer bundle, the [PAIRING_PROOF_HEADER] value (null when the peer sent none)
     * and the caller's literal IP address, no DNS. The address is read straight off the socket, so
     * it is never a name an attacker could plant in reverse DNS or mDNS and never a value forged in
     * an X-Forwarded-For header — it is safe to show the user as "who is pairing with you".
     *
     * Return true when the application accepted this bundle as the listener lifecycle's ONE pairing
     * exchange — only then does the route burn its single-accept slot. Returning false leaves the
     * slot free, so a push the application ignored (an unverifiable QR possession proof, a probe
     * from any host on the LAN) cannot deny the honest peer its retry. The network response is the
     * same either way; the boolean never reaches the wire.
     *
     * Contract for implementations:
     * - It may be invoked concurrently: two pushes can be inside it at once, and the route only
     *   arbitrates which of them burns the slot afterwards.
     * - It must not throw for attacker-controllable input — return false instead. A throw is treated
     *   as a local delivery failure and answered 500, which is distinguishable from the uniform 200
     *   an accepted and an ignored push both get, so a prober could use it as an oracle.
     * - Enforcing "only one peer is ever accepted" is the application's job (an atomic consume of
     *   the pairing nonce, say). The route's single-accept slot is a backstop, not the mechanism.
     */
    val onPeerBundle: suspend (bundle: ByteArray, proof: String?, remoteAddress: String) -> Boolean,
    val maxBundleBytes: Int = 16 * 1024,
    val maxRequestsPerWindow: Int = 8,
    val rateLimitWindowMillis: Long = 60_000,
) {
    init {
        require(maxBundleBytes in 1 until 64 * 1024) { "pairing bundle cap must be below 64 KiB" }
        require(maxRequestsPerWindow > 0) { "pairing rate limit must allow at least one request" }
        require(rateLimitWindowMillis > 0) { "pairing rate-limit window must be positive" }
    }
}

/** Carries the pusher's proof that it saw the peer's pairing QR. Optional: absent on manual pairing. */
const val PAIRING_PROOF_HEADER = "X-Passman-Pairing-Proof"

/**
 * Bound on the proof header before it reaches the application. It is attacker-supplied plaintext
 * input and an honest proof is a base64url SHA-256 (43 chars), so anything longer is junk.
 *
 * Public so the pushing side can refuse to send what this side would only reject.
 */
const val MAX_PAIRING_PROOF_CHARS = 128

fun startServer(
    port: Int,
    tempFilePath: String,
    getFileFromName: suspend (String) -> ByteArray,
    // Upload and sync-pull handlers receive the verified SPKI pin of the mTLS caller as their last
    // parameter, so the application can resolve WHICH paired device sent the payload before touching
    // it. Null means there is no verified TLS identity (a plaintext listener) — deliberately so: the
    // pairing listener is plaintext by design and its handlers must not be handed a fabricated pin.
    onFileUploaded: suspend (ByteArray, String, String?) -> Unit,
    artifactUploadHandlers: Map<String, suspend (ByteArray, String, String?) -> Unit> = emptyMap(),
    artifactDownloadHandlers: Map<String, suspend (String) -> ByteArray> = emptyMap(),
    syncPullHandlers: Map<String, suspend (ByteArray, String?) -> ByteArray?> = emptyMap(),
    serverTls: K2kServerTls? = null,
    // DoS caps applied BEFORE any crypto/parse: reject an upload once its streamed body exceeds
    // maxUploadBytes, and reject a sync-pull whose request body (the client public key) is larger
    // than maxSyncPullRequestBytes. Both bound memory/disk an unauthenticated-shaped flood can force.
    maxUploadBytes: Long = 25L * 1024 * 1024,
    maxSyncPullRequestBytes: Long = 64L * 1024,
    // Time-based DoS caps, the counterpart to the byte caps above: those bound how MUCH a caller
    // may send, these bound how LONG it may take. Ktor's default is 0 — no timeout — so a caller
    // that opens a connection and dribbles a request forever holds a worker for free. That is
    // cheapest to do against the pairing listener, which is plaintext and reachable by any host on
    // the LAN.
    requestReadTimeoutSeconds: Int = 15,
    responseWriteTimeoutSeconds: Int = 15,
    // Per-operation authorization. Called with the op ("passwords"/"pgp-keys"/"keystore") and the
    // connecting client's verified SPKI pin (null if unavailable). Return false to reject with 403.
    // Null (default) = allow all, so the plaintext pairing server and legacy callers are unaffected.
    // ClientAuth.REQUIRE already limits callers to paired devices; this narrows *which op* each may do.
    authorizer: (suspend (op: String, pin: String?) -> Boolean)? = null,
    // Optional diagnostics hook. It is invoked from download handling with the protocol negotiated
    // by the connecting peer, allowing integration tests to observe the actual client handshake.
    onPeerTlsProtocol: ((String?) -> Unit)? = null,
    // Invoked when an upload temp file could NOT be created owner-only, because the filesystem
    // backing tempFilePath reports no POSIX attribute view. The upload still proceeds, but the file
    // holding the payload is created at the process umask instead — on a shared machine that is a
    // local disclosure window for the length of the transfer.
    //
    // This exists because the alternative is a SILENT security downgrade: without it, a device where
    // the check unexpectedly fails writes payload bytes at umask permissions, logs nothing, and
    // shows nothing in a bug report. Applications handling sensitive payloads should treat this
    // firing as a real finding rather than ignoring it.
    onInsecureTempFile: ((path: String) -> Unit)? = null,
    // A non-null exchange creates the intentionally tiny plaintext pairing route table: the legacy
    // RSA public-key download plus the bounded mutual pairing-bundle exchange. It never registers
    // upload, artifact, sync-pull, or arbitrary download routes.
    pairingBundleExchange: PairingBundleExchange? = null,
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    // Built once and shared; a fresh SslHandler is created per channel below. When null the
    // server stays plaintext (legacy behaviour); when set, every connection is mutually
    // authenticated and the peer certificate is SPKI-pinned to a paired device.
    val sslContext: SslContext? = serverTls?.buildNettySslContext()
    val routesModule: Application.() -> Unit = {
        install(ContentNegotiation)
        routing {
            if (pairingBundleExchange != null) {
                installPairingRoutes(getFileFromName, pairingBundleExchange)
                return@routing
            }

            post("/upload") {
                if (!authorizeOp("passwords", authorizer)) return@post
                handleUpload(tempFilePath, onFileUploaded, maxUploadBytes, onInsecureTempFile)
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
                    handleUpload(tempFilePath, handler, maxUploadBytes, onInsecureTempFile)
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
                    // Cheap rejection first: a caller that honestly advertises an oversized body is
                    // turned away without reading any of it.
                    val declaredLength = call.request.contentLength()
                    if (declaredLength != null && declaredLength > maxSyncPullRequestBytes) {
                        call.respond(HttpStatusCode.PayloadTooLarge, "request too large")
                        return@post
                    }
                    // A chunked request declares no length, so the check above cannot fire and a
                    // buffering read would hold the whole body before any size test ran. Read
                    // incrementally instead and abort the moment the cap is passed.
                    val clientPubkey = try {
                        readBoundedBody(maxSyncPullRequestBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    } catch (_: BodyTooLargeException) {
                        call.respond(HttpStatusCode.PayloadTooLarge, "request too large")
                        return@post
                    }
                    val result = try {
                        handler(clientPubkey, peerSpkiPin())
                    } catch (t: Throwable) {
                        // Log the detail locally; the response body stays generic so internal
                        // paths/crypto errors never leak to the network.
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

    return embeddedServer(
        Netty,
        serverConfig {
            // Ktor's default watchPaths is the working directory. A non-empty list makes every
            // stop() lazily create a WatchService purely to close it, and Android's
            // LinuxWatchService finalizer then closes it a second time, logging an uncaught
            // ClosedWatchServiceException per server stop. There is no auto-reload here: watch
            // nothing.
            watchPaths = emptyList()
            module(body = routesModule)
        },
        configure = {
            // This overload takes no `port`, so bind the connector here.
            connector { this.port = port }
            this.requestReadTimeoutSeconds = requestReadTimeoutSeconds
            this.responseWriteTimeoutSeconds = responseWriteTimeoutSeconds
            if (sslContext != null) {
                channelPipelineConfig = { pipeline ->
                    pipeline.addFirst("ssl", sslContext.newHandler(pipeline.channel().alloc()))
                }
            }
        },
    )
}

private fun Route.installPairingRoutes(
    getFileFromName: suspend (String) -> ByteArray,
    exchange: PairingBundleExchange,
) {
    val peerBundleAccepted = AtomicBoolean(false)
    val rateLimiter = PairingRateLimiter(exchange.maxRequestsPerWindow, exchange.rateLimitWindowMillis)

    // Retain the old bootstrap endpoint for peers that have not yet learned the identity bundle.
    get("/download/publicKey") {
        handleDownload("publicKey", getFileFromName)
    }

    get("/pairing-bundle") {
        if (!rateLimiter.tryAcquire()) {
            call.respond(HttpStatusCode.TooManyRequests, "pairing exchange rate limited")
            return@get
        }
        val localBundle = try {
            exchange.localBundle()
        } catch (_: Throwable) {
            call.respond(HttpStatusCode.InternalServerError, "pairing bundle unavailable")
            return@get
        }
        if (localBundle.size > exchange.maxBundleBytes) {
            call.respond(HttpStatusCode.InternalServerError, "pairing bundle unavailable")
            return@get
        }
        call.respondBytes(localBundle)
    }

    post("/pairing-bundle") {
        if (!rateLimiter.tryAcquire()) {
            call.respond(HttpStatusCode.TooManyRequests, "pairing exchange rate limited")
            return@post
        }
        val declaredLength = call.request.contentLength()
        if (declaredLength != null && declaredLength > exchange.maxBundleBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge, "pairing bundle too large")
            return@post
        }
        // Header hygiene before the body is read at all: both checks are free, and a push that fails
        // either of them is junk whatever its body says. The rejection is the same generic 400 the
        // body checks give, so moving them earlier only saves work — it tells the wire nothing new.
        val proofHeaders = call.request.headers.getAll(PAIRING_PROOF_HEADER)
        if (proofHeaders != null && proofHeaders.size > 1) {
            // Which of two proofs the application would have checked is a decision nobody should be
            // able to smuggle past it; an honest pusher sends exactly one.
            call.respond(HttpStatusCode.BadRequest, "invalid pairing bundle")
            return@post
        }
        val proof = proofHeaders?.firstOrNull()
        if (proof != null && proof.length > MAX_PAIRING_PROOF_CHARS) {
            call.respond(HttpStatusCode.BadRequest, "invalid pairing bundle")
            return@post
        }
        val peerBundle = try {
            readBoundedBody(exchange.maxBundleBytes)
        } catch (_: BodyTooLargeException) {
            call.respond(HttpStatusCode.PayloadTooLarge, "pairing bundle too large")
            return@post
        } catch (_: Throwable) {
            call.respond(HttpStatusCode.BadRequest, "invalid pairing bundle")
            return@post
        }
        val valid = try {
            exchange.validatePeerBundle(peerBundle)
        } catch (_: Throwable) {
            false
        }
        if (!valid) {
            call.respond(HttpStatusCode.BadRequest, "invalid pairing bundle")
            return@post
        }
        if (peerBundleAccepted.get()) {
            call.respond(HttpStatusCode.Conflict, "pairing bundle already received")
            return@post
        }
        val accepted = try {
            // remoteAddress, not remoteHost: the latter is Netty's hostName, a blocking reverse-DNS
            // lookup whose answer the caller can influence via PTR or mDNS records, and this string
            // is shown to the user as the device asking to pair. Take the literal off the socket.
            exchange.onPeerBundle(peerBundle, proof, call.request.origin.remoteAddress)
        } catch (cancellation: CancellationException) {
            // A cancelled call is the server tearing this request down, not an application failure:
            // let it unwind instead of swallowing it into a 500 on a connection that is already gone.
            throw cancellation
        } catch (_: Throwable) {
            // The peer has not been accepted when local delivery failed; the slot is untouched so a
            // retry can still land while the listener is open, and the response never leaks the
            // local failure to the network.
            call.respond(HttpStatusCode.InternalServerError, "pairing exchange failed")
            return@post
        }
        // The slot burns only on an accepted bundle, and only for the request that wins the race: a
        // concurrent push that slipped past the check above must not also count as "the" exchange.
        if (accepted && !peerBundleAccepted.compareAndSet(false, true)) {
            call.respond(HttpStatusCode.Conflict, "pairing bundle already received")
            return@post
        }
        // Identical response for an accepted and an ignored bundle: a prober on the plaintext port
        // learns nothing about what this listener is armed for.
        call.respond(HttpStatusCode.OK, "pairing bundle received")
    }
}

/**
 * Creates the per-request upload temp file, owner-readable only where the filesystem supports it.
 *
 * Vault bytes pass through this file, so the process umask is not an acceptable default —
 * `File.createTempFile` leaves it world-readable under a typical umask, giving any local user a
 * read window for the length of the transfer. `Files.createTempFile` also drops the three-character
 * prefix minimum that made single-character upload names fail the request with a 500.
 *
 * The POSIX guard is not dead code on Android: `FileSystems.getDefault()` there is
 * `LinuxFileSystemProvider`, whose `standardFileAttributeViews()` is a hardcoded
 * `[basic, posix, unix, owner]` with no platform branching, so the owner-only attribute really is
 * applied rather than silently skipped. (Read from android-34 libcore sources; unchanged since
 * java.nio.file landed at API 26. Not yet confirmed by a device run.) Note this whole file is also
 * why the library's Android minSdk is 26 — java.nio.file does not exist below it.
 */
internal fun createUploadTempFile(
    directory: File,
    safeName: String,
    onInsecureTempFile: ((path: String) -> Unit)? = null,
): File {
    val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    val posixSupported = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
    val path = if (posixSupported) {
        Files.createTempFile(
            directory.toPath(),
            "$safeName.",
            ".part",
            PosixFilePermissions.asFileAttribute(ownerOnly),
        )
    } else {
        // Never take this branch quietly. The upload proceeds, but the hardening did not apply, and
        // that fact has to leave a trace somewhere the application can act on.
        onInsecureTempFile?.invoke(directory.path)
        Files.createTempFile(directory.toPath(), "$safeName.", ".part")
    }
    return path.toFile()
}

private class BodyTooLargeException : Exception()

/**
 * Reads a request body, aborting as soon as it passes [maxBytes].
 *
 * Every route that accepts a body from the network must go through this rather than
 * `call.receive<ByteArray>()`. A buffering read holds the entire body in heap before any size check
 * can run, and a chunked request advertises no Content-Length to check in the first place — so the
 * declared-length test alone bounds nothing. Reading incrementally is what makes the cap bind
 * memory, which is the whole point of having one.
 */
private suspend fun io.ktor.server.routing.RoutingContext.readBoundedBody(maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(maxBytes, 4 * 1024))
    val buffer = ByteArray(minOf(maxBytes, 4 * 1024))
    val channel = call.receiveChannel()
    var total = 0
    while (true) {
        val count = channel.readAvailable(buffer, 0, buffer.size)
        if (count == -1) break
        total += count
        if (total > maxBytes) throw BodyTooLargeException()
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

/** A small process-local fixed-window limiter for the short-lived plaintext pairing listener. */
private class PairingRateLimiter(
    private val maxRequests: Int,
    private val windowMillis: Long,
) {
    private var windowStartedAt = 0L
    private var requestCount = 0

    @Synchronized
    fun tryAcquire(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (nowMillis - windowStartedAt >= windowMillis) {
            windowStartedAt = nowMillis
            requestCount = 0
        }
        if (requestCount >= maxRequests) return false
        requestCount++
        return true
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
    onFileUploaded: suspend (ByteArray, String, String?) -> Unit,
    maxUploadBytes: Long,
    onInsecureTempFile: ((path: String) -> Unit)? = null,
) {
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
                            tempFile = createUploadTempFile(File(tempFilePath), safeName, onInsecureTempFile)
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

    val uploaded = tempFile
    if (tooLarge) {
        uploaded?.delete()
        call.respond(HttpStatusCode.PayloadTooLarge, "upload too large")
    } else if (rejected || uploaded == null || !uploaded.exists()) {
        uploaded?.delete()
        call.respond(HttpStatusCode.BadRequest, "no valid file uploaded")
    } else {
        try {
            onFileUploaded(uploaded.readBytes(), uploadName ?: uploaded.name, peerSpkiPin())
            call.respondText("200")
        } catch (t: Throwable) {
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
    val fileBytes = getFileFromName(fileName)
    if (fileBytes.isNotEmpty()) {
        call.respondBytes(fileBytes)
    } else {
        call.respondText("File not found", status = HttpStatusCode.NotFound)
    }
}
