package com.k2k

expect class NetworkScanner {
    suspend fun scanNetwork(): List<String>
    suspend fun isAppRunning(ip: String): Boolean
}

expect class PlatformSocket {
    suspend fun connect(ip: String, port: Int, timeoutMs: Int): Boolean
    fun close()
}
