package com.k2k

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.darwin.inet_ntop
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.INET_ADDRSTRLEN
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.getnameinfo
import platform.posix.sockaddr_in
import platform.posix.socklen_t

actual object NetInterface {
    @OptIn(ExperimentalForeignApi::class)
    actual fun getAddresses(): Set<String> {
        val addresses = mutableSetOf<String>()
        memScoped {
            val ifaddrs = allocPointerTo<ifaddrs>()
            var ifaPtr: CPointer<ifaddrs>? = ifaddrs.value
            if (getifaddrs(ifaddrs.ptr) == 0) {
                while (ifaPtr != null) {
                    if (ifaPtr.pointed.ifa_addr?.pointed?.sa_family?.toInt() == AF_INET
                        || ifaPtr.pointed.ifa_addr?.pointed?.sa_family?.toInt() == AF_INET6
                    ) {
                        val host = allocArray<ByteVar>(NI_MAXHOST)
                        val res = getnameinfo(
                            ifaPtr.pointed.ifa_addr?.reinterpret(),
                            (ifaPtr.pointed.ifa_addr?.pointed?.sa_len ?: 0) as socklen_t, host,
                            NI_MAXHOST.toUInt(), null, 0u, NI_NUMERICHOST
                        )
                        if (res == 0) {
                            addresses.add(host.toKString())
                        }
                    }
                    ifaPtr = ifaPtr.pointed.ifa_next?.reinterpret()
                }
            }
            freeifaddrs(ifaPtr)
        }
        return addresses
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun getLocalAddress(): String  {
        return memScoped {
            val ifaddrsPtr = alloc<CPointerVar<ifaddrs>>()
            if (getifaddrs(ifaddrsPtr.ptr) != 0) return ""

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
                        return@memScoped ip.orEmpty()
                    }
                }
                currentInterface = currentInterface.pointed.ifa_next
            }

            freeifaddrs(ifaddrsPtr.value)
            ""
        }
    }
}