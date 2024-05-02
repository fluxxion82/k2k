package com.k2k.connect

import com.k2k.Host
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openWriteChannel
import kotlinx.coroutines.Dispatchers

object ConnectionClient {
    internal suspend fun send(
        bytes: ByteArray,
        host: Host,
        port: Int,
    ) {
        runCatching {
            println("client send, addy: ${host.hostAddress}, port: ${host.port}")
            val socketAddress = InetSocketAddress(host.hostAddress, port)
            val socket = aSocket(SelectorManager(Dispatchers.Default))
                .tcp()
                .connect(socketAddress) {
                    socketTimeout = 20000
                    reuseAddress = true
                }

            println("client sent: $socket, ${bytes.size}")
            val writeChannel = socket.openWriteChannel(autoFlush = true)
            writeChannel.writeFully(bytes, 0, bytes.size)
            println("client written")
        }.onFailure {
            it.cause?.printStackTrace()
            println("exception sending: ${it.message}")
        }
    }
}