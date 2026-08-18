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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    private var receivedRemoteAddress: String? = null
    private val callbackInvocations = AtomicInteger(0)

    /** What the application answers: true = "this was my one pairing exchange", false = ignored. */
    @Volatile
    private var acceptPeerBundle = true

    /** Optional hook run inside the callback, used to hold two pushes inside it at once. */
    @Volatile
    private var onPeerBundleGate: (suspend () -> Unit)? = null

    /** Injected local-delivery failure: what an application that throws instead of returning false does. */
    @Volatile
    private var onPeerBundleFailure: Throwable? = null

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
                onPeerBundle = { bundle, proof, remoteAddress ->
                    callbackInvocations.incrementAndGet()
                    onPeerBundleFailure?.let { throw it }
                    receivedPeerBundle = bundle.copyOf()
                    receivedProof = proof
                    receivedRemoteAddress = remoteAddress
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
    fun pairingBundle_passesProofHeaderAndCallerAddressToTheApplication() = runBlocking {
        val proof = "aGVsbG8tcGFpcmluZy1wcm9vZg"

        val pushed = client.post("http://127.0.0.1:$port/pairing-bundle") {
            header(PAIRING_PROOF_HEADER, proof)
            setBody(peerBundle)
        }

        assertEquals(HttpStatusCode.OK, pushed.status)
        assertEquals(1, callbackInvocations.get())
        assertEquals(proof, receivedProof)
        // The literal address the socket reported, never a name: a reverse-DNS form here would mean
        // the route resolved attacker-influenced PTR/mDNS data and handed the result to the UI.
        assertEquals(LOOPBACK_LITERAL, receivedRemoteAddress)
    }

    @Test
    fun pairingBundle_ignoresForwardedHeadersWhenReportingTheCaller() = runBlocking {
        // No ForwardedHeaders plugin is installed, deliberately: on a LAN pairing port these headers
        // are just more attacker-controlled text, and the address the user compares to their other
        // device must be the one the packets actually came from.
        val pushed = client.post("http://127.0.0.1:$port/pairing-bundle") {
            header("X-Forwarded-For", "203.0.113.7")
            header("X-Forwarded-Host", "attacker.example")
            header("Forwarded", "for=203.0.113.9;host=attacker.example;proto=https")
            setBody(peerBundle)
        }

        assertEquals(HttpStatusCode.OK, pushed.status)
        assertEquals(LOOPBACK_LITERAL, receivedRemoteAddress)
    }

    @Test
    fun pairingBundle_rejectsDuplicateProofHeadersWithoutInvokingTheApplication() = runBlocking {
        // Written straight onto the socket: an HTTP client of its own accord folds a repeated header
        // into one value, and the request this rejects is precisely the one no client would build.
        val duplicated = rawPairingPush(
            headers = listOf(
                PAIRING_PROOF_HEADER to "aGVsbG8tcGFpcmluZy1wcm9vZg",
                PAIRING_PROOF_HEADER to "c29tZXRoaW5nLWVsc2UtZW50aXJlbHk",
            ),
        )

        // Same generic rejection as any other malformed push, so which of the two the application
        // would have picked is not something the wire can probe for.
        assertEquals("HTTP/1.1 400 Bad Request", duplicated.statusLine)
        assertEquals("invalid pairing bundle", duplicated.body)
        assertEquals(0, callbackInvocations.get())
        assertNull(receivedPeerBundle)

        // A single proof over the same raw transport still gets through, so the rejection above is
        // about the repetition and not about hand-writing the request.
        val single = rawPairingPush(headers = listOf(PAIRING_PROOF_HEADER to "aGVsbG8tcGFpcmluZy1wcm9vZg"))

        assertEquals("HTTP/1.1 200 OK", single.statusLine)
        assertEquals(1, callbackInvocations.get())
        assertEquals("aGVsbG8tcGFpcmluZy1wcm9vZg", receivedProof)
    }

    @Test
    fun pairingBundle_applicationFailureStaysGenericAndLeavesTheSlotForTheRetry() = runBlocking {
        onPeerBundleFailure = IllegalStateException("keystore at /Users/alice/.passman/keys.p12 is locked")

        val failed = client.post("http://127.0.0.1:$port/pairing-bundle") {
            setBody(peerBundle)
        }
        val failedBody = failed.bodyAsText()

        assertEquals(HttpStatusCode.InternalServerError, failed.status)
        assertEquals("pairing exchange failed", failedBody)
        assertFalse(failedBody.contains("keystore"))
        assertFalse(failedBody.contains("Exception"))
        assertFalse(failedBody.contains("at com.k2k"))
        assertEquals(1, callbackInvocations.get())
        assertNull(receivedPeerBundle)

        // A local delivery that blew up is not the peer's fault and must not cost it the exchange:
        // the retry reaches the application again and is allowed to win.
        onPeerBundleFailure = null
        val retry = client.post("http://127.0.0.1:$port/pairing-bundle") {
            setBody(secondPeerBundle)
        }

        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(2, callbackInvocations.get())
        assertContentEquals(secondPeerBundle, receivedPeerBundle)

        // The retry burnt the slot, which is what proves the failed push had not quietly spent it.
        val afterBurn = client.post("http://127.0.0.1:$port/pairing-bundle") {
            setBody(peerBundle)
        }
        assertEquals(HttpStatusCode.Conflict, afterBurn.status)
        assertEquals(2, callbackInvocations.get())
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
        // Both deferreds complete even when the two pushes run strictly one after the other (the
        // first times out waiting, the second then completes the one it was waiting on), so the
        // *timeout not firing* is the only thing that actually witnesses the overlap.
        val overlapped = AtomicBoolean(false)
        // Hold the first push inside the application until the second one has entered too, so both
        // observe a free slot and genuinely race on the compare-and-set that follows.
        onPeerBundleGate = {
            if (firstInside.complete(Unit)) {
                if (withTimeoutOrNull(10_000) { secondInside.await() } != null) overlapped.set(true)
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

        assertTrue(overlapped.get(), "both pushes must be inside the application at once to race the slot")
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

    private companion object {
        /** The literal the loopback client connects from; the route must never turn it into a name. */
        const val LOOPBACK_LITERAL = "127.0.0.1"
    }

    private class RawResponse(val statusLine: String, val body: String)

    /**
     * Pushes [peerBundle] with hand-written headers, so a test can send a request no HTTP client
     * library would produce (a header repeated verbatim, say).
     */
    private fun rawPairingPush(headers: List<Pair<String, String>>): RawResponse =
        Socket(LOOPBACK_LITERAL, port).use { socket ->
            val request = buildString {
                append("POST /pairing-bundle HTTP/1.1\r\n")
                append("Host: $LOOPBACK_LITERAL:$port\r\n")
                append("Content-Length: ${peerBundle.size}\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(request.encodeToByteArray())
                write(peerBundle)
                flush()
            }
            val raw = socket.getInputStream().readBytes().decodeToString()
            val separator = raw.indexOf("\r\n\r\n")
            RawResponse(
                statusLine = raw.substringBefore("\r\n"),
                body = if (separator < 0) "" else raw.substring(separator + 4),
            )
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
