package com.k2k.discover

import com.k2k.Constants
import com.k2k.Constants.BROADCAST_SOCKET
import com.k2k.Host
import com.k2k.NetInterface
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.utils.io.core.readUTF8Line
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.decodeFromString
import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
object DiscoveryServer {
    private var socket = aSocket(SelectorManager(Dispatchers.Default)).udp()

    private val currentHostIps: MutableStateFlow<Set<String>> = MutableStateFlow(setOf())
    internal val hosts: MutableStateFlow<Map<Host, Long>> = MutableStateFlow(mapOf())
    private var listenJob: Job = Job()

    internal fun startListening(
        port: Int,
        ping: Long,
        puffer: Long,
        hostFilter: Regex,
        hostIsClient: Boolean,
        scope: CoroutineScope
    ) {
        listenJob.cancel()
        listenJob = scope.launch(Dispatchers.Default) {
            updateCurrentDeviceIps()
            listen(port, ping, puffer, hostFilter, hostIsClient)
        }
    }

    internal fun stopListening() {
        listenJob.cancel()
        socket = aSocket(SelectorManager(Dispatchers.Default)).udp()
    }

    private suspend fun updateCurrentDeviceIps() {
        val updatedIps: MutableSet<String> = mutableSetOf()
        updatedIps.addAll(NetInterface.getAddresses())
        currentHostIps.emit(currentHostIps.value.toMutableSet().apply {
            retainAll(updatedIps)
            addAll(updatedIps)
        })
    }

    private suspend fun listen(port: Int, ping: Long, puffer: Long, filter: Regex, hostIsClientToo: Boolean) {
        val socketAddress = InetSocketAddress(BROADCAST_SOCKET, port)
        val serverSocket = socket.bind(socketAddress) {
            broadcast = true
            reuseAddress = true
        }

        while (true) {
            serverSocket.openReadChannel()
            serverSocket.incoming.consumeEach { datagram ->
                try {
                    val receivedPacket = datagram.packet.readUTF8Line()
                    if (receivedPacket != null) {
                        val host = Constants.json.decodeFromString<Host>(receivedPacket).apply {
                            val inetSocketAddress = datagram.address as InetSocketAddress
                            hostAddress = inetSocketAddress.hostname
                            this.port = inetSocketAddress.port
                        }

                        val keepHosts = hosts.value.filterValues {
                            it + puffer >= Clock.System.now().toEpochMilliseconds()
                        }.toMutableMap()

                        if (hostIsClientToo || !currentHostIps.value.contains(host.hostAddress)) {
                            if (host.filterMatch.matches(filter)) {
                                keepHosts[host] = Clock.System.now().toEpochMilliseconds()
                            }
                        }
                        println("discovery server, emit $keepHosts")
                        hosts.emit(keepHosts)
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    println("error: ${e.message}")
                    serverSocket.close()
                }
            }

            delay(ping)
        }
    }
}
