package com.k2k.connect

import com.k2k.NetInterface
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.utils.io.core.use
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
object ConnectionServer {
    private var receiveJob: Job = Job()
    internal val receiveData: MutableStateFlow<Pair<String, ByteArray>?> = MutableStateFlow(null)
    private val socket = aSocket(SelectorManager(Dispatchers.Default)).tcp()

    fun startServer(port: Int, scope: CoroutineScope) {
        println("connection server, start $port")
        receiveJob.cancel()
        receiveJob = scope.launch(Dispatchers.Default) {
            while (true) {
                val socketAddress = InetSocketAddress(NetInterface.getLocalAddress(), port)
                socket
                    .bind(socketAddress) {
                        reuseAddress = true
                        // reusePort = true
                    }
                    .accept()
                    .use { boundSocket ->
                        runCatching {
                            println("bound socket")

                            val readChannel = boundSocket.openReadChannel()
                            val buffer = ByteArray(readChannel.availableForRead)
                            while (true) {
                                val bytesRead = readChannel.readAvailable(buffer)
                                if (bytesRead <= 0) {
                                    break
                                }

                                receiveData.emit(boundSocket.remoteAddress.toString() to buffer)
                            }
                        }.onFailure {
                            println("failure opening read channel and reading, ${it.message}")
                            boundSocket.close()
                        }
                    }
            }
        }
    }

    fun stopServer() {
        receiveJob.cancel()
    }
}
