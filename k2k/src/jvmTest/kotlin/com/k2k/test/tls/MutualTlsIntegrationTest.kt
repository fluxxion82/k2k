package com.k2k.test.tls

import com.k2k.test.startOnEphemeralPort
import com.k2k.test.client.downloadFile
import com.k2k.test.client.requestSyncPull
import com.k2k.test.client.uploadFile
import com.k2k.test.server.startServer
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
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
        tempDir = java.nio.file.Files.createTempDirectory("k2k-mtls").toFile().absolutePath
    }

    @AfterTest
    fun tearDown() {
        server?.stop(0, 0)
    }

    // Server is Alice; she trusts only Bob as a client.
    private suspend fun startAliceServer() {
        server = startServer(
            port = 0,
            tempFilePath = tempDir,
            getFileFromName = { "vault-bytes".toByteArray() },
            onFileUploaded = { _, _, _ -> },
            serverTls = K2kServerTls(alice, password, alias, allowedClientPins = setOf(bobPin)),
        ).also { port = it.startOnEphemeralPort() }
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

    @Test
    fun k2kClient_negotiatesTls13() = runBlocking<Unit> {
        val negotiatedProtocol = AtomicReference<String?>()
        server = startServer(
            port = 0,
            tempFilePath = tempDir,
            getFileFromName = { "vault-bytes".toByteArray() },
            onFileUploaded = { _, _, _ -> },
            serverTls = K2kServerTls(alice, password, alias, allowedClientPins = setOf(bobPin)),
            onPeerTlsProtocol = negotiatedProtocol::set,
        ).also { port = it.startOnEphemeralPort() }

        downloadFile(
            fileName = "anything",
            ipAddress = "127.0.0.1",
            port = port,
            tls = K2kClientTls(bob, password, alias, serverPins = setOf(alicePin)),
        )

        kotlin.test.assertEquals("TLSv1.3", negotiatedProtocol.get())
    }

    /**
     * The verified caller identity reaches the application handlers: an upload and a sync-pull from
     * Bob must hand the server-side handler Bob's SPKI pin — not null, and not any other device's
     * pin — so the receiver can resolve WHICH paired device sent the payload before processing it.
     */
    @Test
    fun verifiedCallerPin_isThreadedToUploadAndSyncPullHandlers() = runBlocking<Unit> {
        val uploadPin = AtomicReference<String?>("unset")
        val syncPullPin = AtomicReference<String?>("unset")
        server = startServer(
            port = 0,
            tempFilePath = tempDir,
            getFileFromName = { ByteArray(0) },
            onFileUploaded = { _, _, pin -> uploadPin.set(pin) },
            syncPullHandlers = mapOf("passwords" to { _, pin -> syncPullPin.set(pin); "data".toByteArray() }),
            serverTls = K2kServerTls(alice, password, alias, allowedClientPins = setOf(bobPin)),
        ).also { port = it.startOnEphemeralPort() }
        val bobTls = K2kClientTls(bob, password, alias, serverPins = setOf(alicePin))

        uploadFile(
            file = "payload".toByteArray(),
            fileName = "blob",
            ipAddress = "127.0.0.1",
            port = port,
            tls = bobTls,
        )
        requestSyncPull("passwords", "client-key".toByteArray(), "127.0.0.1", port, tls = bobTls)

        kotlin.test.assertEquals(bobPin, uploadPin.get())
        kotlin.test.assertEquals(bobPin, syncPullPin.get())
    }
}
