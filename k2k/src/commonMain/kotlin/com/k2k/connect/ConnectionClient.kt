package com.k2k.connect

import com.k2k.Host
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers

object ConnectionClient {
    internal suspend fun send(
        bytes: ByteArray,
        host: Host,
        port: Int,
    ) {
        runCatching {
            val socketAddress = InetSocketAddress(host.hostAddress, port)
            val socket = aSocket(SelectorManager(Dispatchers.Default))
                .tcp()
                .connect(socketAddress) {
                    socketTimeout = 20000
                    reuseAddress = true
                }

            val writeChannel = socket.openWriteChannel(autoFlush = true)
            writeChannel.writeFully(bytes, 0, bytes.size)
        }.onFailure {
        }
    }
}