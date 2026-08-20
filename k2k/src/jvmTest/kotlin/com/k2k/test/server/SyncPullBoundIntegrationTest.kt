package com.k2k.test.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import com.k2k.test.startOnEphemeralPort
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The sync-pull cap has to bound *memory*, not merely reject an advertised size.
 *
 * A chunked request carries no Content-Length, so a declared-length check never fires and a
 * buffering read holds the whole body before anything inspects it. That is invisible to a status
 * code — a buffering server and a streaming server both answer 413 in the end — so the test that
 * separates them asks *when* the answer arrives: a bounded reader must reject as soon as the cap is
 * passed, while still mid-body, without waiting for the client to finish sending.
 */
class SyncPullBoundIntegrationTest {
    private var port = 0
    private lateinit var tempDir: String
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private val client = HttpClient(CIO)

    private val maxRequestBytes = 1024L

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("k2k-sync-pull-bound").toFile().absolutePath
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server?.stop(0, 0)
    }

    /**
     * Writes chunks past the cap and then stops — deliberately withholding the terminating chunk.
     * A server that buffers the body first is still waiting for a body end that never comes, so it
     * cannot have answered; reaching a 413 here proves the read was bounded mid-stream.
     */
    @Test
    fun syncPull_rejectsOversizedChunkedBody_beforeTheClientFinishesSending() = runBlocking {
        var handlerInvocations = 0
        server = startTestServer { bytes, _ ->
            handlerInvocations++
            bytes
        }
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            out.write(
                (
                    "POST /sync-pull/passwords HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:$port\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            out.flush()

            // Four times the cap, in chunks, with no terminating "0\r\n\r\n".
            val chunk = ByteArray(512) { 'a'.code.toByte() }
            repeat(8) {
                out.write("${chunk.size.toString(16)}\r\n".toByteArray())
                out.write(chunk)
                out.write("\r\n".toByteArray())
                out.flush()
            }

            val statusLine = try {
                socket.getInputStream().bufferedReader().readLine()
            } catch (_: SocketTimeoutException) {
                null
            }

            assertTrue(
                statusLine != null,
                "server never answered: it is buffering the whole body instead of bounding the read",
            )
            assertTrue(
                statusLine!!.contains(HttpStatusCode.PayloadTooLarge.value.toString()),
                "expected 413 once the cap was passed, got: $statusLine",
            )
        }

        assertEquals(0, handlerInvocations, "an oversized body must be rejected before the handler runs")
    }

    @Test
    fun syncPull_withChunkedBodyUnderTheCap_stillReachesTheHandler() = runBlocking {
        val payload = ByteArray(64) { 'k'.code.toByte() }
        var received: ByteArray? = null
        server = startTestServer { bytes, _ ->
            received = bytes
            bytes
        }
        val response = client.post("http://127.0.0.1:$port/sync-pull/passwords") {
            setBody(chunkedBody(payload))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertContentEquals(payload, received, "a body under the cap must reach the handler intact")
    }

    private suspend fun startTestServer(
        handler: suspend (ByteArray, String?) -> ByteArray?,
    ): io.ktor.server.engine.EmbeddedServer<*, *> = startServer(
        port = 0,
        tempFilePath = tempDir,
        getFileFromName = { ByteArray(0) },
        onFileUploaded = { _, _, _ -> },
        syncPullHandlers = mapOf("passwords" to handler),
        maxSyncPullRequestBytes = maxRequestBytes,
    ).also { port = it.startOnEphemeralPort() }

    /** A body with no known length, which Ktor transfers with chunked encoding. */
    private fun chunkedBody(bytes: ByteArray): OutgoingContent =
        object : OutgoingContent.WriteChannelContent() {
            override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeFully(bytes)
                channel.flush()
            }
        }

}
