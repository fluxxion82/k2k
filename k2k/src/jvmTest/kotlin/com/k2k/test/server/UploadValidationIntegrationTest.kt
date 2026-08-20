package com.k2k.test.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import com.k2k.test.startOnEphemeralPort
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
            port = 0,
            tempFilePath = tempDir,
            getFileFromName = { ByteArray(0) },
            onFileUploaded = { _, _, _ -> uploadedCount++ },
        ).also { port = it.startOnEphemeralPort() }

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

    /**
     * A plaintext (no-TLS) listener has no verified caller identity, so the handler must receive a
     * null pin — never a fabricated one. The receive policy fails closed on null, so inventing a pin
     * here would be the bug that turns "unauthenticated" into "authenticated as somebody".
     */
    @Test
    fun plaintextUpload_deliversNullCallerPinToHandler() = runBlocking {
        var invoked = false
        var receivedPin: String? = "unset"
        server = startServer(
            port = 0,
            tempFilePath = tempDir,
            getFileFromName = { ByteArray(0) },
            onFileUploaded = { _, _, pin ->
                invoked = true
                receivedPin = pin
            },
        ).also { port = it.startOnEphemeralPort() }

        val response = client.submitFormWithBinaryData(
            url = "http://127.0.0.1:$port/upload",
            formData = formData {
                append("file", "payload".encodeToByteArray(), fileHeaders("blob"))
            },
        )

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(invoked, "the upload handler must run for a valid plaintext upload")
        assertEquals(null, receivedPin)
    }

    private fun fileHeaders(fileName: String): Headers = Headers.build {
        append(HttpHeaders.ContentDisposition, "filename=$fileName")
    }

}
