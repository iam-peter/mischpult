package de.mischpult.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The decoded-PCM cache (one `<trackId>.pcm` per track). Decoded audio is big
 * (~10 MB per minute), so users can see what it costs and free it: per track,
 * implicitly when a track leaves the library, or all at once.
 *
 * Deleting a file that a deck is playing is safe: the deck keeps its memory
 * mapping until it loads something else.
 */
class DecodeCache(val dir: File) {

    data class Usage(val bytes: Long = 0, val tracks: Int = 0)

    private val _decoded = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Track id → decoded size in bytes, for every finished decode. */
    val decoded: StateFlow<Map<String, Long>> = _decoded.asStateFlow()

    private val _usage = MutableStateFlow(Usage())
    val usage: StateFlow<Usage> = _usage.asStateFlow()

    init {
        dir.mkdirs()
        refresh()
    }

    fun fileFor(trackId: String) = File(dir, "$trackId.pcm")

    @Synchronized
    fun refresh() {
        val files = dir.listFiles { f -> f.extension == "pcm" }.orEmpty()
        val map = files.associate { it.nameWithoutExtension to it.length() }
        _decoded.value = map
        _usage.value = Usage(map.values.sum(), map.size)
    }

    fun delete(trackId: String) {
        fileFor(trackId).delete()
        refresh()
    }

    fun delete(trackIds: Collection<String>) {
        trackIds.forEach { fileFor(it).delete() }
        refresh()
    }

    /** Removes every finished decode. Decodes still in progress (`.part`) are left alone. */
    fun clearAll() {
        dir.listFiles { f -> f.extension == "pcm" }?.forEach { it.delete() }
        refresh()
    }

    fun touch(trackId: String) {
        fileFor(trackId).setLastModified(System.currentTimeMillis())
    }

    /**
     * Keeps the cache under [maxBytes], least recently used first. [protected]
     * tracks (offline crates) are never evicted, even if that means going over.
     */
    fun trim(maxBytes: Long, keep: String, protected: Set<String>) {
        val files = dir.listFiles { f -> f.extension == "pcm" }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            val id = f.nameWithoutExtension
            if (id == keep || id in protected) continue
            total -= f.length()
            f.delete()
        }
        refresh()
    }
}
