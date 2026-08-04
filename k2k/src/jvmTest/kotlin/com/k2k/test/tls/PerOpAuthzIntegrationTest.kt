package com.k2k.test.tls

import com.k2k.test.client.uploadFile
import com.k2k.test.server.startServer
import java.net.ServerSocket
import java.security.KeyStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFails
import kotlinx.coroutines.runBlocking

/**
 * End-to-end proof of per-operation authorization: a device that passes the TLS pin check is still
 * only allowed the ops its record permits. Bob is a pinned, trusted client, but the server's
 * authorizer allows him only "passwords" — his "pgp-keys" upload is rejected (403).
 */
class PerOpAuthzIntegrationTest {

    private val password = "testpass".toCharArray()
    private val alias = "passmanMain"
    private val alicePin = "a5710166b97c6195d0dc955dbc2390f22c7b2c82b64df758002819c5ad7dd52c"
    private val bobPin = "a3c0d701af7418ea7cd2a38bb2036a99251740cd0d863094dca74b49212d69fb"

    private lateinit var alice: KeyStore
    private lateinit var bob: KeyStore
    private var port = 0
    private lateinit var tempDir: String
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    private fun load(name: String): KeyStore {
        val stream = javaClass.getResourceAsStream("/tls/$name.p12")
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("tls/$name.p12")
            ?: error("missing test resource tls/$name.p12 on classpath")
        return KeyStore.getInstance("PKCS12").apply {
            stream.use { load(it, password) }
        }
    }

    @BeforeTest
    fun setUp() {
        alice = load("alice")
        bob = load("bob")
        port = ServerSocket(0).use { it.localPort }
        tempDir = java.nio.file.Files.createTempDirectory("k2k-authz").toFile().absolutePath
    }

    @AfterTest
    fun tearDown() {
        server?.stop(0, 0)
    }

    // Alice trusts Bob at the TLS layer, but authorizes him only for "passwords".
    private fun startAliceServer() {
        server = startServer(
            port = port,
            tempFilePath = tempDir,
            getFileFromName = { ByteArray(0) },
            onFileUploaded = { _, _ -> },
            artifactUploadHandlers = mapOf("pgp-keys" to { _, _ -> }),
            serverTls = K2kServerTls(alice, password, alias, allowedClientPins = setOf(bobPin)),
            authorizer = { op, pin -> pin == bobPin && op == "passwords" },
        ).also { it.start(wait = false) }
        awaitListening(port)
    }

    private fun awaitListening(port: Int) {
        repeat(100) {
            try {
                java.net.Socket("127.0.0.1", port).close()
                return
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
    }

    private fun bobTls() = K2kClientTls(bob, password, alias, serverPins = setOf(alicePin))

    @Test
    fun allowedOp_passwordsUpload_succeeds() = runBlocking<Unit> {
        startAliceServer()
        // uploadFile throws on a non-success status; reaching here without throwing = authorized.
        uploadFile(
            file = "pw-db".toByteArray(),
            fileName = "blob",
            ipAddress = "127.0.0.1",
            port = port,
            path = "/upload",
            tls = bobTls(),
        )
    }

    @Test
    fun deniedOp_pgpUpload_isRejected() = runBlocking<Unit> {
        startAliceServer()
        assertFails {
            uploadFile(
                file = "pgp-bundle".toByteArray(),
                fileName = "blob",
                ipAddress = "127.0.0.1",
                port = port,
                path = "/upload/pgp-keys",
                tls = bobTls(),
            )
        }
    }
}
