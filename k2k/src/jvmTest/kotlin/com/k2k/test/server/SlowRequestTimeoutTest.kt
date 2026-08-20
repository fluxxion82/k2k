package com.k2k.test.server

import com.k2k.test.startOnEphemeralPort
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The byte caps bound how much a caller may send; they say nothing about how long it may take.
 * Ktor's Netty engine defaults to no read timeout at all, so a caller that opens a connection and
 * then dribbles — or simply stops mid-request — holds a worker indefinitely. That costs the
 * attacker nothing and is cheapest against the plaintext pairing listener, which any host on the
 * LAN can reach.
 */
class SlowRequestTimeoutTest {
    private var port = 0
    private lateinit var tempDir: String
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("k2k-slow-request").toFile().absolutePath
    }

    @AfterTest
    fun tearDown() {
        // This test deliberately leaves a connection mid-timeout, so unlike the other suites it
        // must not be torn down with stop(0, 0): killing Netty's event loops while a timeout is
        // still firing destabilises whichever test class runs next in the same JVM. Give it a
        // grace period to drain.
        server?.stop(500, 2_000)
    }

    /**
     * Announces a body and then never sends it. The headers are complete, so the request reaches
     * routing and the handler blocks waiting for content that never arrives — the shape a slowloris
     * uses to pin a worker. With no read timeout the connection stays open until the client gives
     * up; with one, the server drops it, which the test observes as end-of-stream.
     */
    @Test
    fun stalledRequestBody_isDroppedByTheServerReadTimeout() = runBlocking {
        server = startServer(
            port = 0,
            tempFilePath = tempDir,
            getFileFromName = { ByteArray(0) },
            onFileUploaded = { _, _, _ -> },
            requestReadTimeoutSeconds = 1,
        ).also { port = it.startOnEphemeralPort() }

        Socket("127.0.0.1", port).use { socket ->
            // Generously longer than the server's 1s read timeout, so whichever fires first is
            // unambiguous: the server's, if it has one at all.
            socket.soTimeout = 10_000
            val out = socket.getOutputStream()
            // Complete headers announcing a body, then silence. The request is well-formed enough
            // to reach the handler, which then waits on content that never comes.
            out.write(
                (
                    "POST /upload HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:$port\r\n" +
                        "Content-Type: multipart/form-data; boundary=k2ktestboundary\r\n" +
                        "Content-Length: 4096\r\n\r\n"
                    ).toByteArray()
            )
            out.flush()

            // What matters is that the server stops waiting — whether it answers with an error or
            // hangs up is Netty's business. Either ends the stall; only silence is the bug.
            val startedAt = System.nanoTime()
            val reacted = try {
                socket.getInputStream().read()
                true
            } catch (_: SocketTimeoutException) {
                false
            } catch (_: java.io.IOException) {
                // A connection reset is the server dropping us just as forcefully.
                true
            }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue(
                reacted && elapsedMillis < 5_000,
                "server held a stalled request open for ${elapsedMillis}ms: with no read timeout a " +
                    "half-sent request pins a worker for free",
            )
        }
    }
}
