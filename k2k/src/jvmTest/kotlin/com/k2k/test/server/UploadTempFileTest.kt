package com.k2k.test.server

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Upload payloads land in a temp file before the application ever sees them. For a password
 * manager that file holds vault bytes, so its permissions are part of the transport's security
 * surface, not an implementation detail.
 */
class UploadTempFileTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("k2k-upload-temp").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /**
     * `File.createTempFile` creates under the process umask, which on a typical machine is
     * world-readable — any local user can read a vault mid-transfer.
     */
    @Test
    fun uploadTempFile_isReadableAndWritableByOwnerOnly() {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return

        val file = createUploadTempFile(tempDir, "vault.kdbx")

        val permissions = Files.getPosixFilePermissions(file.toPath())
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            permissions,
            "upload temp files must not be readable by other local users",
        )
    }

    /**
     * `File.createTempFile` requires a prefix of at least three characters, so a single-character
     * upload name threw IllegalArgumentException and surfaced as a 500.
     */
    @Test
    fun uploadTempFile_acceptsSingleCharacterName() {
        val file = createUploadTempFile(tempDir, "a")

        assertTrue(file.exists(), "a one-character upload name must still produce a temp file")
        assertTrue(file.name.startsWith("a"), "the temp file should keep the upload name as its prefix")
    }

    /**
     * The fallback must never be silent. A filesystem with no POSIX view still gets its upload, but
     * the payload lands at the process umask — a security downgrade that, unreported, is invisible
     * in a bug report and to every build-time check.
     */
    @Test
    fun uploadTempFile_reportsWhenOwnerOnlyCouldNotBeApplied() {
        val reported = mutableListOf<String>()
        createUploadTempFile(tempDir, "vault.kdbx") { reported += it }

        val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        if (posix) {
            assertTrue(
                reported.isEmpty(),
                "owner-only succeeded here, so nothing should have been reported as insecure",
            )
        } else {
            assertEquals(
                listOf(tempDir.path),
                reported,
                "a filesystem without a POSIX view must report the downgrade, not swallow it",
            )
        }
    }

    @Test
    fun uploadTempFile_isUniquePerCall() {
        val first = createUploadTempFile(tempDir, "same.bin")
        val second = createUploadTempFile(tempDir, "same.bin")

        assertTrue(first != second, "concurrent uploads of one logical name must not share a file")
    }
}
