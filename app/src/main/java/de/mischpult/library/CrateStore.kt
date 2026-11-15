package de.mischpult.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A named list of tracks: the record collection itself, or an imported playlist. */
@Serializable
data class Crate(
    val name: String,
    val tracks: List<Track> = emptyList(),
    /** Kept ready for offline use: all tracks decoded ahead, never evicted from the cache. */
    val offline: Boolean = false,
)

/**
 * "Plattensammlung": crates persisted as one JSON file. Deliberately simple;
 * swap for Room once tagging/sorting/BPM metadata arrives.
 */
class CrateStore(dir: File) {
    private val file = File(dir, "crates.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val writer = JsonFileWriter<List<Crate>>(file) { json.encodeToString(it) }
    private val _crates = MutableStateFlow(load())
    val crates: StateFlow<List<Crate>> = _crates.asStateFlow()

    private fun load(): List<Crate> {
        val loaded = runCatching {
            json.decodeFromString<List<Crate>>(file.readText())
        }.getOrDefault(emptyList())
        return if (loaded.any { it.name == COLLECTION }) loaded
        else listOf(Crate(COLLECTION)) + loaded
    }

    @Synchronized
    private fun update(transform: (List<Crate>) -> List<Crate>) {
        val next = transform(_crates.value)
        _crates.value = next
        writer.save(next)
    }

    fun addTracks(crateName: String, tracks: List<Track>) = update { all ->
        val exists = all.any { it.name == crateName }
        val base = if (exists) all else all + Crate(crateName)
        base.map { crate ->
            if (crate.name != crateName) crate
            else {
                val ids = crate.tracks.mapTo(HashSet()) { it.id }
                crate.copy(tracks = crate.tracks + tracks.filter { ids.add(it.id) })
            }
        }
    }

    fun removeTrack(crateName: String, trackId: String) = update { all ->
        all.map { if (it.name == crateName) it.copy(tracks = it.tracks.filter { t -> t.id != trackId }) else it }
    }

    fun setOffline(crateName: String, offline: Boolean) = update { all ->
        all.map { if (it.name == crateName) it.copy(offline = offline) else it }
    }

    /** Ids of all tracks in offline crates: their decoded audio must stay. */
    fun offlineTrackIds(): Set<String> =
        crates.value.filter { it.offline }.flatMapTo(HashSet()) { c -> c.tracks.map { it.id } }

    fun deleteCrate(crateName: String) = update { all ->
        if (crateName == COLLECTION) all else all.filter { it.name != crateName }
    }

    companion object {
        const val COLLECTION = "Plattensammlung"
        /** Files imported from the device. */
        const val LOCAL = "Local files"
    }
}
