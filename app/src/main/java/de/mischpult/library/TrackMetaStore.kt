package de.mischpult.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What the app learned or the user set about one track. */
@Serializable
data class TrackMeta(
    /** Hot cue positions in source frames of the decoded audio; null = empty slot. */
    val hotCues: List<Double?> = emptyList(),
    val bpm: Double? = null,
    /** Detected key: 0..11 = C..B major, 12..23 = C..B minor. */
    val key: Int? = null,
)

/**
 * Per-track metadata in `files/track_meta.json`, keyed by track id. Small
 * (a few hundred bytes per track), so it is simply rewritten on change.
 */
class TrackMetaStore(dir: File) {
    private val file = File(dir, "track_meta.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val writer = JsonFileWriter<Map<String, TrackMeta>>(file) { json.encodeToString(it) }
    private val _meta = MutableStateFlow(load())
    val meta: StateFlow<Map<String, TrackMeta>> = _meta.asStateFlow()

    private fun load(): Map<String, TrackMeta> = runCatching {
        json.decodeFromString<Map<String, TrackMeta>>(file.readText())
    }.getOrDefault(emptyMap())

    operator fun get(trackId: String): TrackMeta? = _meta.value[trackId]

    @Synchronized
    fun update(trackId: String, transform: (TrackMeta) -> TrackMeta) {
        val next = _meta.value + (trackId to transform(_meta.value[trackId] ?: TrackMeta()))
        _meta.value = next
        writer.save(next)
    }
}
