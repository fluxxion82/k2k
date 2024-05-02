package com.k2k

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

internal object Constants {
    @OptIn(ExperimentalSerializationApi::class)
    internal val json: Json = Json {
        isLenient = true
        explicitNulls
    }
    const val BROADCAST_ADDRESS = "255.255.255.255"
    const val BROADCAST_SOCKET = "0.0.0.0"
}
