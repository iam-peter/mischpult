package de.mischpult.source.plugin

/**
 * Mischpult's source-plugin protocol, version 1, described in docs/PLUGINS.md.
 * Plugins keep a copy of this file (only the package differs).
 *
 * A plugin is an app with an exported ContentProvider that declares an
 * intent filter for [ACTION]. Mischpult calls it with ContentResolver.call():
 *
 * - [DESCRIBE] → [KEY_PROTOCOL], [KEY_ID], [KEY_NAME], [KEY_FILTERS], [KEY_FILTER_LABELS]
 * - [SEARCH] ([KEY_QUERY], [KEY_FILTER]) → [KEY_TRACKS], [KEY_NEXT]
 * - [MORE] ([KEY_NEXT]) → [KEY_TRACKS], [KEY_NEXT]
 * - [OPEN] ([KEY_LINK]) → [KEY_TRACKS], and [KEY_PLAYLIST] (its name) for a list
 * - [CHECK] ([KEY_URI]) → [KEY_PLAYS], [KEY_SHORT]
 * - [PREPARE] ([KEY_URI]) → [KEY_SIZE] (-1 if unknown), [KEY_MIME]
 *
 * and reads the audio with openFile("content://<authority>/audio/<Uri.encode(uri)>", "r"):
 * the bytes of the audio file, in order. Any call may answer with [KEY_ERROR]
 * (a sentence for the person at the decks), [KEY_SHORT] (a few words) and
 * [KEY_REFUSED] (the source refused this item; another one of the same song may
 * work); a failed audio read closes the pipe with the error's message.
 */
object PluginProtocol {
    const val VERSION = 1
    const val ACTION = "de.mischpult.action.SOURCE_PLUGIN"

    const val DESCRIBE = "describe"
    const val SEARCH = "search"
    const val MORE = "more"
    const val OPEN = "open"
    const val CHECK = "check"
    const val PREPARE = "prepare"

    /** Path segment of the audio URI. */
    const val AUDIO_PATH = "audio"

    const val KEY_PROTOCOL = "protocol"
    const val KEY_ID = "id"
    const val KEY_NAME = "name"
    const val KEY_FILTERS = "filters"
    const val KEY_FILTER_LABELS = "filterLabels"
    const val KEY_QUERY = "query"
    const val KEY_FILTER = "filter"
    const val KEY_NEXT = "next"
    const val KEY_LINK = "link"
    const val KEY_TRACKS = "tracks"
    const val KEY_PLAYLIST = "playlist"
    const val KEY_URI = "uri"
    const val KEY_PLAYS = "plays"
    const val KEY_SIZE = "size"
    const val KEY_MIME = "mime"
    const val KEY_ERROR = "error"
    const val KEY_SHORT = "short"
    const val KEY_REFUSED = "refused"

    // A track: one Bundle each in KEY_TRACKS
    const val TRACK_ID = "id"
    const val TRACK_URI = "uri"
    const val TRACK_TITLE = "title"
    const val TRACK_ARTIST = "artist"
    const val TRACK_DURATION = "duration"
    const val TRACK_THUMBNAIL = "thumbnail"
}
