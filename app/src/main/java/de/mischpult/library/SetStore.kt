package de.mischpult.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One track in a set list: when it went on air, relative to the recording start. */
@Serializable
data class SetEntry(
    val atMs: Long,
    val trackId: String,
    val title: String,
    val artist: String = "",
    val deck: Int,
    val bpm: Double? = null,
    val key: Int? = null,
)

/** A recorded set: the audio file and the tracks that were played. */
@Serializable
data class RecordedSet(
    val id: String,
    val name: String,
    /** content:// (MediaStore) or file:// URI of the .m4a. */
    val uri: String,
    val startedAt: Long,
    val durationMs: Long,
    val entries: List<SetEntry> = emptyList(),
)

/** Recorded sets in `files/sets.json`, newest first. */
class SetStore(dir: File) {
    private val file = File(dir, "sets.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val writer = JsonFileWriter<List<RecordedSet>>(file) { json.encodeToString(it) }
    private val _sets = MutableStateFlow(load())
    val sets: StateFlow<List<RecordedSet>> = _sets.asStateFlow()

    private fun load(): List<RecordedSet> = runCatching {
        json.decodeFromString<List<RecordedSet>>(file.readText())
    }.getOrDefault(emptyList())

    @Synchronized
    private fun save(next: List<RecordedSet>) {
        _sets.value = next
        writer.save(next)
    }

    fun add(set: RecordedSet) = save(listOf(set) + _sets.value.filter { it.id != set.id })

    fun remove(id: String) = save(_sets.value.filter { it.id != id })
}
