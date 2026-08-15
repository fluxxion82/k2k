package com.k2k

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.darwin.inet_ntop
import platform.darwin.inet_pton
import platform.posix.AF_INET
import platform.posix.INET_ADDRSTRLEN
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.close
import platform.posix.connect
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval

@OptIn(ExperimentalForeignApi::class)
actual class NetworkScanner {

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun scanNetwork(): List<String> = withContext(Dispatchers.Default) {
        val deviceIps = mutableListOf<String>()

        // Get the local IP address
        val localIp = memScoped {
            val ifaddrsPtr = alloc<CPointerVar<ifaddrs>>()
            if (getifaddrs(ifaddrsPtr.ptr) != 0) return@withContext emptyList<String>()

            var currentInterface = ifaddrsPtr.value
            while (currentInterface != null) {
                val interfaceName = currentInterface.pointed.ifa_name?.toKString()
                if (interfaceName == "en0") { // Wi-Fi interface
                    val addr = currentInterface.pointed.ifa_addr?.reinterpret<sockaddr_in>()
                    if (addr != null && addr.pointed.sin_family.toInt() == AF_INET) {
                        val ipBytes = ByteArray(INET_ADDRSTRLEN)
                        val ip = inet_ntop(
                            AF_INET,
                            addr.pointed.sin_addr.ptr,
                            ipBytes.refTo(0),
                            ipBytes.size.toUInt()
                        )?.toKString()
                        freeifaddrs(ifaddrsPtr.value)
                        return@memScoped ip
                    }
                }
                currentInterface = currentInterface.pointed.ifa_next
            }

            freeifaddrs(ifaddrsPtr.value)
            null
        }

        // If local IP is null, return an empty list
        if (localIp == null) return@withContext emptyList<String>()

        // Calculate the subnet by removing the last segment of the IP
        val subnet = localIp.substringBeforeLast('.') + "."

        // Scan all IPs in the subnet except the local IP
        for (i in 1..254) {
            val testIp = "$subnet$i"
            if (testIp != localIp && isAppRunning(testIp)) {
                deviceIps.add(testIp)
            }
        }

        deviceIps
    }

    actual suspend fun isAppRunning(ip: String): Boolean = withContext(Dispatchers.Default) {
        try {
            val sockfd = socket(AF_INET, SOCK_STREAM, 0)
            if (sockfd < 0) return@withContext false

            memScoped {
                val addr = alloc<sockaddr_in>().apply {
                    sin_family = AF_INET.toUShort().convert()
                    sin_port = htons(8080.toUShort())
                    inet_pton(AF_INET, ip, this.sin_addr.ptr)
                }

                val result = connect(
                    sockfd,
                    addr.ptr.reinterpret(),
                    sizeOf<sockaddr_in>().toUInt()
                )

                close(sockfd)
                result == 0
            }
        } catch (e: Exception) {
            false
        }
    }
}

actual class PlatformSocket {
    private var sockfd: Int = -1

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun connect(ip: String, port: Int, timeoutMs: Int): Boolean =
        withContext(Dispatchers.Default) {
            memScoped {
                try {
                    sockfd = socket(AF_INET, SOCK_STREAM, 0)
                    if (sockfd < 0) return@withContext false

                    // Set socket timeout
                    val timeout = alloc<timeval>().apply {
                        tv_sec = (timeoutMs / 1000).convert()
                        tv_usec = (timeoutMs % 1000) * 1000
                    }
                    if (setsockopt(sockfd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().toUInt()) != 0) {
                        close()
                        return@withContext false
                    }

                    // Prepare the sockaddr_in structure
                    val addr = alloc<sockaddr_in>().apply {
                        sin_family = AF_INET.convert()
                        sin_port = htons(port.toUShort())
                        val addrBytes = allocArray<ByteVar>(4) // For IPv4, allocate 4 bytes
                        if (inet_pton(AF_INET, ip, addrBytes) != 1) {
                            close()
                            return@withContext false
                        }
                        sin_addr.s_addr = addrBytes.reinterpret<IntVar>().pointed.value.toUInt()
                    }

                    // Attempt to connect
                    val result = connect(
                        sockfd,
                        addr.ptr.reinterpret(),
                        sizeOf<sockaddr_in>().toUInt()
                    )

                    result == 0
                } catch (e: Exception) {
                    close()
                    false
                }
            }
        }

    actual fun close() {
        if (sockfd >= 0) {
            close(sockfd)
            sockfd = -1
        }
    }
}

fun htons(value: UShort): UShort {
    return ((value.toInt() shl 8) or (value.toInt() shr 8)).toUShort()
}
