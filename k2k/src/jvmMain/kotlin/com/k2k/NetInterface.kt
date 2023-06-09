package com.k2k

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
                println("error accessing inetAddresses")
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
        return InetAddress.getLocalHost().hostAddress
    }
}