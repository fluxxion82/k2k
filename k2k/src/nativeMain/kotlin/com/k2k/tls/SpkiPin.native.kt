package com.k2k.tls

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(bytes: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    digest.usePinned { pinnedDigest ->
        if (bytes.isEmpty()) {
            // addressOf(0) on an empty array is out of bounds; CC_SHA256 accepts a null input of
            // length 0 and yields the empty-input digest.
            CC_SHA256(null, 0u, pinnedDigest.addressOf(0).reinterpret())
        } else {
            bytes.usePinned { pinnedInput ->
                CC_SHA256(pinnedInput.addressOf(0), bytes.size.toUInt(), pinnedDigest.addressOf(0).reinterpret())
            }
        }
    }
    return digest
}
