package com.k2k.test.tls

import com.k2k.test.client.downloadFile
import com.k2k.test.server.startServer
import java.net.ServerSocket
import java.security.KeyStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlinx.coroutines.runBlocking

/**
 * End-to-end proof that the k2k transport is mutually authenticated and SPKI-pinned: a pinned peer
 * completes the handshake and receives data, an unpinned peer is rejected at the TLS layer BEFORE
 * any vault bytes are served, and a client that pins the wrong server refuses to connect.
 *
 * Uses three throwaway self-signed PKCS#12 device keystores under src/jvmTest/resources/tls.
 */
class MutualTlsIntegrationTest {

    private val password = "testpass".toCharArray()
    private val alias = "passmanMain"

    private val alicePin = "a5710166b97c6195d0dc955dbc2390f22c7b2c82b64df758002819c5ad7dd52c"
    private val bobPin = "a3c0d701af7418ea7cd2a38bb2036a99251740cd0d863094dca74b49212d69fb"
    private val malloryPin = "c9b46187f2531987f1d79bc94feda4120e13752337ec3d509f90dd86fb357e41"

    private lateinit var alice: KeyStore
    private lateinit var bob: KeyStore
    private lateinit var mallory: KeyStore
    private var port = 0
    private lateinit var tempDir: String
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    private fun load(name: String): KeyStore {
        val stream = javaClass.getResourceAsStream("/tls/$name.p12")
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("tls/$name.p12")
            ?: error("missing test resource tls/$name.p12 on classpath")
        return KeyStore.getInstance("PKCS12").apply {
            stream.use { load(it, password) }
            check(aliases().hasMoreElements()) { "keystore $name loaded empty" }
        }
    }

    @BeforeTest
    fun setUp() {
        alice = load("alice")
        bob = load("bob")
        mallory = load("mallory")
        port = ServerSocket(0).use { it.localPort }
        tempDir = java.nio.file.Files.createTempDirectory("k2k-mtls").toFile().absolutePath
    }

    @AfterTest
    fun tearDown() {
        server?.stop(0, 0)
    }

    // Server is Alice; she trusts only Bob as a client.
    private fun startAliceServer() {
        server = startServer(
            port = port,
            tempFilePath = tempDir,
            getFileFromName = { "vault-bytes".toByteArray() },
            onFileUploaded = { _, _ -> },
            serverTls = K2kServerTls(alice, password, alias, allowedClientPins = setOf(bobPin)),
        ).also { it.start(wait = false) }
        awaitListening(port)
    }

    private fun awaitListening(port: Int) {
        // start(wait=false) returns before the socket is necessarily bound; poll briefly.
        repeat(100) {
            try {
                java.net.Socket("127.0.0.1", port).close()
                return
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
    }

    @Test
    fun pinnedPeer_completesHandshakeAndReceivesData() = runBlocking<Unit> {
        startAliceServer()
        val bytes = downloadFile(
            fileName = "anything",
            ipAddress = "127.0.0.1",
            port = port,
            tls = K2kClientTls(bob, password, alias, serverPins = setOf(alicePin)),
        )
        assertContentEquals("vault-bytes".toByteArray(), bytes)
    }

    @Test
    fun unpinnedClient_isRejectedBeforeAnyDataIsServed() = runBlocking<Unit> {
        // Alice trusts only Bob. Mallory presents her own (untrusted) client cert.
        startAliceServer()
        assertFails {
            downloadFile(
                fileName = "anything",
                ipAddress = "127.0.0.1",
                port = port,
                tls = K2kClientTls(mallory, password, alias, serverPins = setOf(alicePin)),
            )
        }
    }

    @Test
    fun clientPinningWrongServer_refusesToConnect() = runBlocking<Unit> {
        // Bob is a valid client, but pins Mallory as the server — Alice's cert must be rejected.
        startAliceServer()
        assertFails {
            downloadFile(
                fileName = "anything",
                ipAddress = "127.0.0.1",
                port = port,
                tls = K2kClientTls(bob, password, alias, serverPins = setOf(malloryPin)),
            )
        }
    }
}
