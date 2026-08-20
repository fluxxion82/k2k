package com.k2k.tls

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.Network.nw_connection_send
import platform.Network.nw_connection_t
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_get_global_queue
import kotlin.concurrent.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * End-to-end mutual TLS on iOS: a pinned client completing a handshake against a pinned listener,
 * and an unpinned one being refused.
 *
 * This is the test the whole iOS server effort exists to pass. Everything before it — the SPKI
 * extractor, the pin, the generated identity, the client challenge handler — is only worth having if
 * two devices can actually authenticate each other, and nothing short of a real handshake
 * demonstrates that. Compilation certainly does not: `sec_protocol_options_set_peer_authentication_required`
 * defaults to false on servers, so a listener that never calls it still negotiates TLS happily and
 * looks entirely healthy while asking the peer for nothing.
 *
 * The negative case matters as much as the positive one. A pinning implementation that accepts
 * everyone passes every connectivity test ever written.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeTlsListenerTest {
    private val server = DeviceIdentity.generate(
        commonName = "k2k-server",
        serialNumber = byteArrayOf(0x11, 0x22),
        notBefore = Instant.fromEpochSeconds(1_700_000_000),
        notAfter = Instant.fromEpochSeconds(1_900_000_000),
    )
    private val client = DeviceIdentity.generate(
        commonName = "k2k-client",
        serialNumber = byteArrayOf(0x33, 0x44),
        notBefore = Instant.fromEpochSeconds(1_700_000_000),
        notAfter = Instant.fromEpochSeconds(1_900_000_000),
    )
    private val stranger = DeviceIdentity.generate(
        commonName = "k2k-stranger",
        serialNumber = byteArrayOf(0x55, 0x66),
        notBefore = Instant.fromEpochSeconds(1_700_000_000),
        notAfter = Instant.fromEpochSeconds(1_900_000_000),
    )

    private var listener: NativeTlsListener? = null

    @AfterTest
    fun tearDown() {
        listener?.stop()
        server.close()
        client.close()
        stranger.close()
    }

    @Test
    fun pinnedClient_completesMutualTlsAndGetsAResponse() = runBlocking {
        val port = startListener(allowing = setOf(client.pin))

        val http = HttpClient(Darwin) {
            engine { installMutualTls(identity = client, serverPins = setOf(server.pin)) }
        }
        try {
            val response = withTimeoutOrNull(15_000) {
                http.get("https://127.0.0.1:$port/")
            }

            assertNotNull(response, "the pinned client never completed a request")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("k2k", response.bodyAsText())
        } finally {
            http.close()
        }
    }

    /**
     * Attribution, not merely authentication. Passman selects both its per-operation permissions and
     * its inbound decryption policy from this value, so a handler that learns *a* peer authenticated
     * without learning *which* is not usable. Getting it wrong would fail open silently, which is
     * why this reads the pin from the connection's own TLS metadata rather than from anything the
     * shared verify block stashed.
     */
    @Test
    fun handlerLearnsWhichPeerAuthenticated() = runBlocking {
        val seen = AtomicReference<String?>(null)
        val port = startListener(allowing = setOf(client.pin), observePeer = { seen.value = it })

        val http = HttpClient(Darwin) {
            engine { installMutualTls(identity = client, serverPins = setOf(server.pin)) }
        }
        try {
            withTimeoutOrNull(15_000) { http.get("https://127.0.0.1:$port/") }
        } finally {
            http.close()
        }

        assertEquals(
            client.pin,
            seen.value,
            "the server must attribute the connection to the client that actually authenticated",
        )
        assertEquals(
            false,
            seen.value == server.pin,
            "attribution must not report our own identity back as the peer's",
        )
    }

    /**
     * The stranger presents a valid, well-formed certificate — it is simply not one we paired with.
     * That is the case pinning exists for, and the one a chain-validation-only server would let
     * through if the certificate happened to be CA-signed.
     */
    @Test
    fun unpinnedClient_isRefused() = runBlocking {
        val port = startListener(allowing = setOf(client.pin))

        val http = HttpClient(Darwin) {
            engine { installMutualTls(identity = stranger, serverPins = setOf(server.pin)) }
        }
        try {
            val response = withTimeoutOrNull(10_000) {
                runCatching { http.get("https://127.0.0.1:$port/") }.getOrNull()
            }

            assertNull(response, "a client whose pin we do not hold must not complete a request")
        } finally {
            http.close()
        }
    }

    /** An empty allow-list must reject everyone. Fail closed, never fail open. */
    @Test
    fun emptyPinSet_refusesEvenAValidClient() = runBlocking {
        val port = startListener(allowing = emptySet())

        val http = HttpClient(Darwin) {
            engine { installMutualTls(identity = client, serverPins = setOf(server.pin)) }
        }
        try {
            val response = withTimeoutOrNull(10_000) {
                runCatching { http.get("https://127.0.0.1:$port/") }.getOrNull()
            }

            assertNull(response, "an empty pin set must never mean 'allow all'")
        } finally {
            http.close()
        }
    }

    /**
     * Starts a listener that answers any request with a fixed minimal response, so a successful
     * handshake is observable as a completed HTTP round trip rather than inferred from a hang.
     */
    private suspend fun startListener(
        allowing: Set<String>,
        observePeer: (String?) -> Unit = {},
    ): Int {
        val started = NativeTlsListener(
            identity = server,
            allowedClientPins = allowing,
            onConnection = { connection, peerPin ->
                observePeer(peerPin)
                respond(connection)
            },
        )
        listener = started
        started.start(0)

        repeat(100) {
            started.boundPort?.let { return it }
            delay(50)
        }
        error("listener never reached ready")
    }

    /** The listener has already started the connection; we only write the reply. */
    private fun respond(connection: nw_connection_t) {
        val queue = dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0uL)
        val body = "k2k"
        val response = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body
            ).encodeToByteArray()

        response.usePinned { pinned ->
            val data = dispatch_data_create(
                pinned.addressOf(0),
                response.size.toULong(),
                queue,
                null,
            )
            nw_connection_send(connection, data, null, true) { }
        }
    }
}
