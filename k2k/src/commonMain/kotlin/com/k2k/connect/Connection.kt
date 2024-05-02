package com.k2k.connect

import com.k2k.Host
import com.k2k.discover.Discovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlin.properties.Delegates

class Connection private constructor(
    private val peers: StateFlow<Set<Host>>,
    private val port: Int,
    private val scope: CoroutineScope
) {
    val receiveData: Flow<Pair<Host, ByteArray>> = channelFlow {
        ConnectionServer.receiveData.collectLatest {
            println("receive data connection, ${it?.first}, ${it?.second?.decodeToString()}")
            if (it != null) {
                val peer = peers.value.firstOrNull { aPeer ->
                    println("apeer host address: ${aPeer.hostAddress}")
                    aPeer.hostAddress == it.first.substringBefore(":").substringAfter("/")
                }
                peer?.let { p -> send(Pair(p, it.second)) }
            }
        }
    }.flowOn(Dispatchers.Default)

    suspend fun send(bytes: ByteArray, peer: Host) = ConnectionClient.send(bytes, peer, port)

    fun startReceiving() {
        ConnectionServer.startServer(port, scope)
    }

    fun stopReceiving() {
        ConnectionServer.stopServer()
    }

    class Builder(private var scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {
        private var peerFlow: MutableStateFlow<Set<Host>> = MutableStateFlow(setOf())
        private var port by Delegates.notNull<Int>()

        fun fromDiscovery(discovery: Discovery) = forPeers(discovery.peersFlow)

        fun forPeers(peers: Flow<Set<Host>>) = apply {
            scope.launch(Dispatchers.Default) {
                peerFlow.emitAll(peers)
            }
        }

        fun forPeers(peers: Set<Host>) = apply {
            scope.launch(Dispatchers.Default) {
                peerFlow.emit(peers)
            }
        }

        fun forPeer(peer: Host) = forPeers(setOf(peer))

        fun setPort(port: Int) = apply {
            this.port = port
        }

        fun setScope(scope: CoroutineScope) = apply {
            this.scope = scope
        }

        fun build() = Connection(peerFlow.asStateFlow(), port, scope)
    }
}

fun CoroutineScope.connection(builder: Connection.Builder.() -> Unit) = Connection.Builder(this).apply(builder).build()
