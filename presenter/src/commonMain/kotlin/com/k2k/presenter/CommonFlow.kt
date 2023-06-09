package com.k2k.presenter

import io.ktor.utils.io.core.Closeable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// https://stackoverflow.com/questions/64175099/listen-to-kotlin-coroutine-flow-from-ios
fun <T> Flow<T>.asCommonFlow(): CommonFlow<T> = CommonFlow(this)
class CommonFlow<T>(private val origin: Flow<T>) : Flow<T> by origin {
    fun watch(block: (T) -> Unit): Closeable {
        val job = Job()

        onEach {
            block(it)
        }.launchIn(CoroutineScope(Dispatchers.Default + job))

        return object : Closeable {
            override fun close() {
                job.cancel()
            }
        }
    }
}
