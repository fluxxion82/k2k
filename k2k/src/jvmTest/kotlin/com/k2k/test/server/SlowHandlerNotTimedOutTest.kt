package com.k2k.test.server

import com.k2k.test.startOnEphemeralPort
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * The data server must not impose a deadline on handler execution.
 *
 * Netty's ReadTimeoutHandler fires when nothing has been read on the connection for the period, and
 * that clock does not stop while a handler is busy. If it stays armed during handler execution then
 * the timeout silently doubles as a ceiling on handler runtime, and any upload whose processing
 * outlives it has its connection killed mid-work. For a consumer decrypting and re-sealing a vault
 * inside onFileUploaded, that is a live failure, and it would present as an intermittent sync error
 * rather than anything resembling a timeout.
 */
class SlowHandlerNotTimedOutTest {
    private var port = 0
    private lateinit var tempDir: String
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private val client = HttpClient(CIO) {
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("k2k-slow-handler").toFile().absolutePath
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server?.stop(500, 2_000)
    }

    @Test
    fun handlerSlowerThanTheReadTimeout_stillCompletes() = runBlocking {
        var handlerFinished = false
        server = startServer(
            port = 0,
            tempFilePath = tempDir,
            getFileFromName = { ByteArray(0) },
            onFileUploaded = { _, _, _ ->
                // Comfortably longer than the read timeout below. Stands in for a consumer
                // decrypting, re-sealing and atomically publishing a vault before returning.
                delay(3_000)
                handlerFinished = true
            },
            // Deliberately NOT setting a timeout: this asserts the DATA SERVER'S DEFAULT is safe
            // for slow handlers. Setting one explicitly would test the caller's choice instead of
            // the library's, and the library's default is what shipped the bug.
        ).also { port = it.startOnEphemeralPort() }

        val response = client.submitFormWithBinaryData(
            url = "http://127.0.0.1:$port/upload",
            formData = formData {
                append("file", "payload".encodeToByteArray(), fileHeaders("vault.bin"))
            },
        )

        assertEquals(
            HttpStatusCode.OK,
            response.status,
            "a handler outliving requestReadTimeoutSeconds must not have its connection killed",
        )
        assertEquals(true, handlerFinished, "the handler should have run to completion")
    }

    private fun fileHeaders(fileName: String): Headers = Headers.build {
        append(HttpHeaders.ContentDisposition, "filename=$fileName")
    }
}
