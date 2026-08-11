package com.k2k.test.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PairingBundleIntegrationTest {
    private val localBundle = ByteArray(3_312) { 0x41 }
    private val peerBundle = ByteArray(3_312) { 0x42 }
    private val secondPeerBundle = ByteArray(3_312) { 0x43 }

    private var port = 0
    private lateinit var tempDir: String
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private val client = HttpClient(CIO)
    private var receivedPeerBundle: ByteArray? = null
    private var validationCalls = 0

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        tempDir = Files.createTempDirectory("k2k-pairing-bundle").toFile().absolutePath
        server = startServer(
            port = port,
            tempFilePath = tempDir,
            getFileFromName = { fileName ->
                if (fileName == "publicKey") "legacy-rsa-public-key".encodeToByteArray() else ByteArray(0)
            },
            onFileUploaded = { _, _ -> error("pairing listener must not accept upload") },
            pairingBundleExchange = PairingBundleExchange(
                localBundle = { localBundle },
                validatePeerBundle = { candidate ->
                    validationCalls++
                    candidate.contentEquals(peerBundle) || candidate.contentEquals(secondPeerBundle)
                },
                onPeerBundle = { receivedPeerBundle = it.copyOf() },
            ),
        ).also { it.start(wait = false) }
        awaitListening()
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server?.stop(0, 0)
    }

    @Test
    fun pairingBundle_exchangeReturnsLocalBundleAndAcceptsExactlyOnePeerBundle() = runBlocking {
        val fetched = client.get("http://127.0.0.1:$port/pairing-bundle")

        assertEquals(HttpStatusCode.OK, fetched.status)
        assertContentEquals(localBundle, fetched.readBytes())

        val pushed = client.post("http://127.0.0.1:$port/pairing-bundle") {
            setBody(peerBundle)
        }
        assertEquals(HttpStatusCode.OK, pushed.status)
        assertContentEquals(peerBundle, receivedPeerBundle)

        val secondPush = client.post("http://127.0.0.1:$port/pairing-bundle") {
            setBody(secondPeerBundle)
        }
        assertEquals(HttpStatusCode.Conflict, secondPush.status)
        assertContentEquals(peerBundle, receivedPeerBundle)
    }

    @Test
    fun pairingListener_retainsLegacyPublicKeyRouteForOldPeers() = runBlocking {
        val response = client.get("http://127.0.0.1:$port/download/publicKey")

        assertEquals(HttpStatusCode.OK, response.status)
        assertContentEquals("legacy-rsa-public-key".encodeToByteArray(), response.readBytes())
    }

    @Test
    fun pairingListener_refusesDataTransferRoutes() = runBlocking {
        val upload = client.post("http://127.0.0.1:$port/upload") { setBody("vault") }
        val vault = client.get("http://127.0.0.1:$port/download/vault")
        val pgp = client.post("http://127.0.0.1:$port/upload/pgp-keys") { setBody("pgp") }
        val keystore = client.post("http://127.0.0.1:$port/upload/keystore") { setBody("keystore") }
        val syncPull = client.post("http://127.0.0.1:$port/sync-pull/passwords") { setBody(ByteArray(32)) }

        listOf(upload, vault, pgp, keystore, syncPull).forEach { response ->
            assertTrue(response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.MethodNotAllowed)
        }
    }

    @Test
    fun pairingBundle_rejectsOversizedBodyBeforeInvokingHandler() = runBlocking {
        val response = client.post("http://127.0.0.1:$port/pairing-bundle") {
            setBody(ByteArray(16 * 1024 + 1))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(null, receivedPeerBundle)
        assertEquals(0, validationCalls)
    }

    @Test
    fun pairingBundle_rejectsMalformedBodyWithoutLeakingStackTrace() = runBlocking {
        val response = client.post("http://127.0.0.1:$port/pairing-bundle") {
            setBody("not-a-device-identity-bundle")
        }
        val responseBody = response.bodyAsText()

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertFalse(responseBody.contains("Exception"))
        assertFalse(responseBody.contains("at com.k2k"))
        assertEquals(1, validationCalls)
        assertEquals(null, receivedPeerBundle)
    }

    @Test
    fun pairingBundle_rateLimitsExchangeRequests() = runBlocking {
        repeat(8) {
            assertEquals(HttpStatusCode.OK, client.get("http://127.0.0.1:$port/pairing-bundle").status)
        }

        val limited = client.get("http://127.0.0.1:$port/pairing-bundle")
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
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
