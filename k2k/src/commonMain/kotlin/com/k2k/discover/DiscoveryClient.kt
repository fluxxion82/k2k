package com.k2k.discover

import com.k2k.Constants.BROADCAST_ADDRESS
import com.k2k.NetInterface
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
object DiscoveryClient {
    private val socket = aSocket(SelectorManager(Dispatchers.Default)).udp()

    private var broadcastJob: Job = Job()

    internal fun startBroadcasting(
        port: Int,
        ping: Long,
        data: ByteArray,
        scope: CoroutineScope
    ) {
        broadcastJob.cancel()
        broadcastJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                send(port, data)
                delay(ping)
            }
        }
    }

    internal fun stopBroadcasting() {
        broadcastJob.cancel()
    }

    private suspend fun send(port: Int, data: ByteArray) {
        suspend fun writeToSocket(address: String, port: Int) = runCatching {
            val socketConnection = socket.connect(InetSocketAddress(address, port)) {
                broadcast = true
                reuseAddress = true
            }

            val output = socketConnection.openWriteChannel(autoFlush = true)
            output.writeFully(data, 0, data.size)
            output.close()
            socketConnection.close()
        }

        writeToSocket(BROADCAST_ADDRESS, port)
        for (address in NetInterface.getAddresses()) {
            writeToSocket(address, port)
        }
    }
}
