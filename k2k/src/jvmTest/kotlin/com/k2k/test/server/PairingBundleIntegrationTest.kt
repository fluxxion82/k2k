package com.k2k.test.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class PairingBundleIntegrationTest {
    private val localBundle = ByteArray(3_312) { 0x41 }
    private val peerBundle = ByteArray(3_312) { 0x42 }
    private val secondPeerBundle = ByteArray(3_312) { 0x43 }

    private var port = 0
    private lateinit var tempDir: String
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private val client = HttpClient(CIO)

    @Volatile
    private var receivedPeerBundle: ByteArray? = null
    private var validationCalls = 0

    @Volatile
    private var receivedProof: String? = null

    @Volatile
    private var receivedRemoteHost: String? = null
    private val callbackInvocations = AtomicInteger(0)

    /** What the application answers: true = "this was my one pairing exchange", false = ignored. */
    @Volatile
    private var acceptPeerBundle = true

    /** Optional hook run inside the callback, used to hold two pushes inside it at once. */
    @Volatile
    private var onPeerBundleGate: (suspend () -> Unit)? = null

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
            onFileUploaded = { _, _, _ -> error("pairing listener must not accept upload") },
            pairingBundleExchange = PairingBundleExchange(
                localBundle = { localBundle },
                validatePeerBundle = { candidate ->
                    validationCalls++
                    candidate.contentEquals(peerBundle) || candidate.contentEquals(secondPeerBundle)
                },
                onPeerBundle = { bundle, proof, remoteHost ->
                    callbackInvocations.incrementAndGet()
                    receivedPeerBundle = bundle.copyOf()
                    receivedProof = proof
                    receivedRemoteHost = remoteHost
                    onPeerBundleGate?.invoke()
                    acceptPeerBundle
                },
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
        // The burnt slot short-circuits before the application is bothered a second time.
        assertEquals(1, callbackInvocations.get())
    }

    @Test
    fun pairingBundle_passesProofHeaderAndRemoteHostToTheApplication() = runBlocking {
        val proof = "aGVsbG8tcGFpcmluZy1wcm9vZg"

        val pushed = client.post("http://127.0.0.1:$port/pairing-bundle") {
            header(PAIRING_PROOF_HEADER, proof)
            setBody(peerBundle)
        }

        assertEquals(HttpStatusCode.OK, pushed.status)
        assertEquals(1, callbackInvocations.get())
        assertEquals(proof, receivedProof)
        assertTrue(assertNotNull(receivedRemoteHost).isNotBlank())
    }

    @Test
    fun pairingBundle_passesNullProofWhenTheHeaderIsAbsent() = runBlocking {
        val pushed = client.post("http://127.0.0.1:$port/pairing-bundle") {
            setBody(peerBundle)
        }

        assertEquals(HttpStatusCode.OK, pushed.status)
        assertEquals(1, callbackInvocations.get())
        assertNull(receivedProof)
    }

    @Test
    fun pairingBundle_rejectsOversizedProofHeaderWithoutInvokingTheApplication() = runBlocking {
        val oversized = client.post("http://127.0.0.1:$port/pairing-bundle") {
            header(PAIRING_PROOF_HEADER, "A".repeat(129))
            setBody(peerBundle)
        }

        assertEquals(HttpStatusCode.BadRequest, oversized.status)
        assertEquals(0, callbackInvocations.get())
        assertNull(receivedPeerBundle)

        // The cap itself is inclusive: a proof of exactly the maximum length still gets through.
        val atCap = client.post("http://127.0.0.1:$port/pairing-bundle") {
            header(PAIRING_PROOF_HEADER, "A".repeat(128))
            setBody(peerBundle)
        }

        assertEquals(HttpStatusCode.OK, atCap.status)
        assertEquals(1, callbackInvocations.get())
        assertEquals("A".repeat(128), receivedProof)
    }

    @Test
    fun pairingBundle_ignoredPushLeavesTheSlotOpenForAnHonestRetry() = runBlocking {
        acceptPeerBundle = false

        val ignored = client.post("http://127.0.0.1:$port/pairing-bundle") {
            setBody(peerBundle)
        }

        assertEquals(HttpStatusCode.OK, ignored.status)
        // Indistinguishable from an accepted push: a prober learns nothing about the listener.
        assertEquals("pairing bundle received", ignored.bodyAsText())
        assertEquals(1, callbackInvocations.get())

        val retry = client.post("http://127.0.0.1:$port/pairing-bundle") {
            setBody(secondPeerBundle)
        }

        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(2, callbackInvocations.get())
        assertContentEquals(secondPeerBundle, receivedPeerBundle)
    }

    @Test
    fun pairingBundle_acceptedPushBurnsTheSlotWithTheSameResponseBodyAsAnIgnoredOne() = runBlocking {
        val accepted = client.post("http://127.0.0.1:$port/pairing-bundle") {
            setBody(peerBundle)
        }

        assertEquals(HttpStatusCode.OK, accepted.status)
        assertEquals("pairing bundle received", accepted.bodyAsText())

        val afterBurn = client.post("http://127.0.0.1:$port/pairing-bundle") {
            setBody(secondPeerBundle)
        }

        assertEquals(HttpStatusCode.Conflict, afterBurn.status)
        assertEquals(1, callbackInvocations.get())
    }

    @Test
    fun pairingBundle_concurrentAcceptedPushesBurnTheSlotExactlyOnce() = runBlocking {
        val firstInside = CompletableDeferred<Unit>()
        val secondInside = CompletableDeferred<Unit>()
        // Hold the first push inside the application until the second one has entered too, so both
        // observe a free slot and genuinely race on the compare-and-set that follows.
        onPeerBundleGate = {
            if (firstInside.complete(Unit)) {
                withTimeoutOrNull(10_000) { secondInside.await() }
            } else {
                secondInside.complete(Unit)
            }
        }

        val statuses = listOf(peerBundle, secondPeerBundle)
            .map { body ->
                async(Dispatchers.IO) {
                    client.post("http://127.0.0.1:$port/pairing-bundle") { setBody(body) }.status
                }
            }
            .awaitAll()

        assertTrue(firstInside.isCompleted && secondInside.isCompleted, "both pushes must overlap")
        assertEquals(2, callbackInvocations.get())
        assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Conflict), statuses.toSet())

        onPeerBundleGate = null
        val afterBurn = client.post("http://127.0.0.1:$port/pairing-bundle") {
            setBody(peerBundle)
        }

        assertEquals(HttpStatusCode.Conflict, afterBurn.status)
        assertEquals(2, callbackInvocations.get())
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
