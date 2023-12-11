package com.k2k.presenter

import com.k2k.Host
import com.k2k.connect.Connection
import com.k2k.discover.Discovery
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

open class MainPresenter(val name: String, val receiving: Boolean): CoroutineScope {
    private val job = SupervisorJob(null)
    override val coroutineContext: CoroutineContext
        get() = job

    private var clickCounter = 0
    private var connect: Connection? = null
    private var discoveryJob: Job? = null

    private val discover = Discovery.Builder(this)
            .setPort(1337)
            .setPing(5000)
            .setDiscoverableTimeout(30000)
            .setDiscoveryTimeout(30000)
            .setHostIsClient(false)
            .build()

    @NativeCoroutinesState
    val foundPeers = MutableStateFlow(listOf<Host>())

    fun onStart() {
        if (clickCounter == 1) {
            println("already clicked start")
            return
        }

        clickCounter++
        println("Starting discovery")
        discover.makeDiscoverable(Host(name))
        discover.startDiscovery()
        discoveryJob = launch {
            discover.peersFlow.distinctUntilChanged().collect { peers ->
                println("discovered peer")
                val hosts = peers.filter { it.name != name }

                if (hosts.isNotEmpty()) {
                    println("found peer emit")
                    foundPeers.emit(hosts)
                }
            }
        }
    }

    fun onConnect(host: Host) {
        if (connect == null) {
            println("create connection, peers: $host}")
            discover.stopDiscovery()
            connect = Connection.Builder(this)
                .forPeers(setOf(host))
                .setPort(7331)
                .build()

            if (receiving) {
                launch {
                    connect?.receiveData?.collectLatest {
                        println("connection received data: ${it.second.decodeToString()}")
                    }
                }

                connect?.startReceiving()
            } else {
                launch {
                    connect?.send("Hello world".encodeToByteArray(), host)
                }
            }
        }
    }

    fun onStop() {
        if (clickCounter == 0) {
            println("already clicked stop")
            return
        }

        launch {
            foundPeers.emit(emptyList())
        }

        clickCounter--

        println("stopping discovery")
        println("--------")
        discover.stopDiscovery()

        // connect?.stopReceiving()
        connect = null
        discoveryJob?.cancel()
    }

    fun onCleared() {
        job.cancel()
    }
}
