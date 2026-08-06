package com.k2k.test.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class UploadValidationIntegrationTest {
    private var port = 0
    private lateinit var tempDir: String
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private val client = HttpClient(CIO)

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        tempDir = Files.createTempDirectory("k2k-upload-validation").toFile().absolutePath
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server?.stop(0, 0)
    }

    @Test
    fun upload_withMultipleFileItems_isRejectedWithoutInvokingHandler() = runBlocking {
        var uploadedCount = 0
        server = startServer(
            port = port,
            tempFilePath = tempDir,
            getFileFromName = { ByteArray(0) },
            onFileUploaded = { _, _ -> uploadedCount++ },
        ).also { it.start(wait = false) }
        awaitListening()

        val response = client.submitFormWithBinaryData(
            url = "http://127.0.0.1:$port/upload",
            formData = formData {
                append("first", "first".encodeToByteArray(), fileHeaders("first.bin"))
                append("second", "second".encodeToByteArray(), fileHeaders("second.bin"))
            },
        )

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, uploadedCount)
        assertTrue(java.io.File(tempDir).listFiles().isNullOrEmpty())
    }

    private fun fileHeaders(fileName: String): Headers = Headers.build {
        append(HttpHeaders.ContentDisposition, "filename=$fileName")
    }

    private fun awaitListening() {
        repeat(100) {
            try {
                Socket("127.0.0.1", port).close()
                return
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        error("server did not start")
    }
}
