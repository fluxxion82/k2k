package com.k2k

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

actual object NetInterface {
    actual fun getAddresses(): Set<String> {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        val updatedIps: MutableSet<String> = mutableSetOf()

        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            try {
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                networkInterface.interfaceAddresses.forEach {
                    if (it.broadcast != null) {
                        updatedIps.add(
                            it.broadcast.hostAddress
                        )
                    }
                }
            } catch (ignored: Exception) {
                // cannot access inetAddresses
            }
        }

        return updatedIps
    }

    fun ipToString(address: ByteArray): String {
        var addressStr = ""
        for (i in 0..3) {
            val t = 0xFF and address[i].toInt()
            addressStr += ".$t"
        }
        addressStr = addressStr.substring(1)

        return addressStr
    }

    actual fun getLocalAddress(): String {
        // Interfaces that are down or loopback are skipped here, matching getAddresses(); the choice
        // among what remains is selectLocalAddress's, and is tested there rather than depending on
        // whatever this particular machine happens to enumerate.
        val candidates = mutableListOf<Inet4Address>()
        for (networkInterface in NetworkInterface.getNetworkInterfaces()) {
            try {
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
            } catch (ignored: Exception) {
                continue
            }
            for (address in networkInterface.inetAddresses) {
                if (address is Inet4Address) candidates.add(address)
            }
        }
        return selectLocalAddress(candidates) ?: InetAddress.getLocalHost().hostAddress
    }
}
