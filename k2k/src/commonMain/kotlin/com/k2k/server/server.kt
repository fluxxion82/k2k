package com.k2k.server

expect class PlatformServer {
    fun start(): Unit
    fun stop(): Unit

    companion object {
        fun create(
            port: Int,
            /**
             * DEAD PARAMETER — no actual reads it. Verified 2026-08-19 across all three: in
             * server.jvm.kt and server.native.kt the only references are commented-out lines, and
             * server.android.kt never mentions it after the declaration. This server streams
             * nothing to disk; uploads are received in memory.
             *
             * It is not harmless. It reads as security-relevant, sits next to genuine temp-file
             * hardening in com.k2k.test.server, and invites someone to "start using" a parameter
             * that was never wired. It also costs a consumer real code: moviePicker maintains a
             * PlatformUtil.getTempDirectory() expect/actual pair on android and native solely to
             * supply it.
             *
             * Removing it is an API break on PlatformServer, so it wants its own commit rather than
             * riding along with unrelated work — and coordination with moviePicker, who can delete
             * getTempDirectory() in the same pass.
             */
            tempFilePath: String,
            getFileFromName: suspend (String) -> ByteArray,
            onFileUploaded: suspend (ByteArray) -> Unit,
        ): PlatformServer
    }
}
