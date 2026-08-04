package com.k2k

actual class NetworkScanner {
    actual suspend fun scanNetwork(): List<String> {
        TODO("Not yet implemented")
    }

    actual suspend fun isAppRunning(ip: String): Boolean {
        TODO("Not yet implemented")
    }
}

actual class PlatformSocket {
    actual suspend fun connect(ip: String, port: Int, timeoutMs: Int): Boolean {
        TODO("Not yet implemented")
    }

    actual fun close() {
    }
}
