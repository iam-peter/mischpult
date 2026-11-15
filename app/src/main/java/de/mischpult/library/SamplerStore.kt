package de.mischpult.library

import de.mischpult.audio.PadMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class PadConfig(
    val index: Int,
    val name: String,
    val mode: PadMode = PadMode.ONE_SHOT,
    val volume: Float = 0.9f,
    val color: Int = index % PAD_COLORS,
    /** The sample's tempo, if known (from the deck it was grabbed from, or detected). */
    val bpm: Double? = null,
    /** Play at the leading deck's tempo. */
    val followTempo: Boolean = false,
    /** Keep the pitch while following the tempo. */
    val keyLock: Boolean = true,
)

const val PAD_COLORS = 8

/**
 * Sampler pads that have a sample: their settings in `pads.json`, their audio
 * as `pad_<n>.pcm` next to it. Lives in the app's files, not the cache, so
 * "Clear all decoded audio" and the system never throw a gig's samples away.
 */
class SamplerStore(val dir: File) {
    private val file = File(dir, "pads.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val writer = JsonFileWriter<Map<Int, PadConfig>>(file) { json.encodeToString(it.values.sortedBy { c -> c.index }) }
    private val _pads = MutableStateFlow(load())
    /** Pad index → config, only for pads with a sample. */
    val pads: StateFlow<Map<Int, PadConfig>> = _pads.asStateFlow()

    init {
        dir.mkdirs()
    }

    fun audioFile(index: Int) = File(dir, "pad_$index.pcm")

    private fun load(): Map<Int, PadConfig> = runCatching {
        json.decodeFromString<List<PadConfig>>(file.readText())
    }.getOrDefault(emptyList()).filter { audioFile(it.index).exists() }.associateBy { it.index }

    @Synchronized
    private fun save(next: Map<Int, PadConfig>) {
        _pads.value = next
        writer.save(next)
    }

    fun put(config: PadConfig) = save(_pads.value + (config.index to config))

    fun update(index: Int, transform: (PadConfig) -> PadConfig) {
        val current = _pads.value[index] ?: return
        put(transform(current))
    }

    fun remove(index: Int) {
        audioFile(index).delete()
        save(_pads.value - index)
    }
}
