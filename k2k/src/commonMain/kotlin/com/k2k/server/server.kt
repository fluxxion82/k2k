package com.k2k.server

expect class PlatformServer {
    fun start(): Unit
    fun stop(): Unit

    companion object {
        fun create(
            port: Int,
            getFileFromName: suspend (String) -> ByteArray,
            onFileUploaded: suspend (ByteArray) -> Unit,
        ): PlatformServer
    }
}
