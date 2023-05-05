package com.k2k

expect object NetInterface {
    // udp, no isLoopback, broadcastAddress is not null
    fun getAddresses(): Set<String>
    fun getLocalAddress(): String
}
