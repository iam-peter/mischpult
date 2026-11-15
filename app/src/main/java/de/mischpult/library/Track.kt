package de.mischpult.library

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = TrackSourceSerializer::class)
enum class TrackSource {
    /** From an online source plugin ([Track.plugin]). */
    ONLINE,
    LOCAL_FILE,
}

/**
 * Stores [TrackSource] by name. Anything but a local file reads as online,
 * so values written by older versions still load.
 */
object TrackSourceSerializer : KSerializer<TrackSource> {
    override val descriptor = PrimitiveSerialDescriptor("de.mischpult.library.TrackSource", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: TrackSource) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): TrackSource =
        if (decoder.decodeString() == TrackSource.LOCAL_FILE.name) TrackSource.LOCAL_FILE else TrackSource.ONLINE
}

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
    val plugin: String = "",
)
