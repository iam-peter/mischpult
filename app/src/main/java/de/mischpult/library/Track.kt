package de.mischpult.library

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TrackSource {
    /** From an online source plugin ([Track.plugin]). Stored as "YOUTUBE", the only one there was at first. */
    @SerialName("YOUTUBE") ONLINE,
    LOCAL_FILE,
}

/** Plugin id of online tracks saved before plugins: the YouTube source was built in then. */
const val FIRST_PLUGIN = "youtube"

/** A playable item, independent of where the audio comes from. */
@Serializable
data class Track(
    /** Stable key; also used as the cache file name. */
    val id: String,
    val source: TrackSource,
    /** The plugin's own address of the item for online tracks, a content URI for local files. */
    val uri: String,
    val title: String,
    val artist: String = "",
    val durationSec: Long = -1,
    val thumbnailUrl: String? = null,
    /** For online tracks: the id of the source plugin they come from. */
    val plugin: String = FIRST_PLUGIN,
)
