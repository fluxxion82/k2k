package com.k2k

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

actual class NetworkScanner(
    private val context: Context
) {
    actual suspend fun scanNetwork(): List<String> {
        val deviceIps = mutableListOf<String>()

        // Get WiFi manager
        val context = context // You'll need to set this up
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcpInfo = wifiManager.dhcpInfo

        // Get device's IP address
        val localIp = formatIp(dhcpInfo.ipAddress)

        // Scan subnet
        val subnet = localIp.substring(0, localIp.lastIndexOf('.') + 1)
        for (i in 1..254) {
            val testIp = "$subnet$i"
            if (testIp != localIp && isAppRunning(testIp)) {
                deviceIps.add(testIp)
            }
        }

        return deviceIps
    }

    actual suspend fun isAppRunning(ip: String): Boolean {

        val socket = PlatformSocket()
        return try {
            socket.connect(ip, 8080, 100)
            true
        } catch (e: Exception) {
            false
        } finally {
            socket.close()
        }
    }

    private fun formatIp(ipAddress: Int): String {
        return String.format(
            "%d.%d.%d.%d",
            ipAddress and 0xff,
            ipAddress shr 8 and 0xff,
            ipAddress shr 16 and 0xff,
            ipAddress shr 24 and 0xff
        )
    }
}

actual class PlatformSocket {
    private var socket: java.net.Socket? = null

    actual suspend fun connect(ip: String, port: Int, timeoutMs: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                socket = java.net.Socket()
                socket?.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            } catch (e: Exception) {
                false
            }
        }

    actual fun close() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // Handle closing error
        }
    }
}
