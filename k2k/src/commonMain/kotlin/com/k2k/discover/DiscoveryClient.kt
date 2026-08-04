package com.k2k.discover

import com.k2k.Constants.BROADCAST_ADDRESS
import com.k2k.NetInterface
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.core.*
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

            val datagram = Datagram(
                packet = ByteReadPacket(data),
                address = InetSocketAddress(address, port)
            )
            socketConnection.outgoing.send(datagram)
            socketConnection.close()

//            val output = socketConnection.outgoing // openWriteChannel(autoFlush = true)
//            output.writeFully(data, 0, data.size)
//            output.close()
//            socketConnection.close()
        }.onFailure {
            println("failed to write socket: ${it.message}")
        }

        writeToSocket(BROADCAST_ADDRESS, port)
        for (address in NetInterface.getAddresses()) {
            writeToSocket(address, port)
        }
    }
}
