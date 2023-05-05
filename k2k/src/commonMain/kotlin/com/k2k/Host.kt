package com.k2k

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.properties.Delegates

@Serializable
data class Host(
    @Serializable
    val name: String,
    @Serializable
    val filterMatch: String = String(),
) {
    @Transient
    lateinit var hostAddress: String
    @Transient
    var port by Delegates.notNull<Int>()
}
