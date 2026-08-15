package com.k2k.connect

import com.k2k.NetInterface
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
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
        receiveJob.cancel()
        receiveJob = scope.launch(Dispatchers.Default) {
            while (true) {
                val socketAddress = InetSocketAddress(NetInterface.getLocalAddress(), port)
                socket
                    .bind(socketAddress) {
                        reuseAddress = true
                        reusePort = true
                    }
                    .accept()
                    .use { boundSocket ->
                        runCatching {

                            val readChannel = boundSocket.openReadChannel()
                            val output = boundSocket.openWriteChannel(autoFlush = true)
                            val toRead = readChannel.availableForRead
                            val buffer = ByteArray(toRead)
                            while (true) {
                                val bytesRead = readChannel.readAvailable(buffer)
                                if (bytesRead <= 0) {
                                    break
                                }

                                receiveData.emit(boundSocket.remoteAddress.toString() to buffer)
                            }
                        }.onFailure {
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
