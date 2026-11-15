package de.mischpult.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Writes a store's state to its JSON file off the main thread. Changes that
 * come in a burst (dragging a slider, typing a name, setting hot cues
 * mid-set) end up in one write of the latest state, [settleMs] after the
 * first of them. Writes go to a temporary file renamed over the old one, so
 * a crash never leaves half a file.
 */
class JsonFileWriter<T : Any>(
    private val file: File,
    private val settleMs: Long = 250,
    private val encode: (T) -> String,
) {
    private val latest = AtomicReference<T?>(null)
    private val scheduled = AtomicBoolean(false)

    fun save(value: T) {
        latest.set(value)
        if (!scheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(settleMs)
            // Cleared before reading, so a change from here on schedules another write
            scheduled.set(false)
            latest.get()?.let(::write)
        }
    }

    private fun write(value: T) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(encode(value))
            tmp.renameTo(file)
        }
    }

    private companion object {
        /** One writer thread for all stores: writes never overlap and keep their order. */
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    }
}
