package de.mischpult.source.plugin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.core.os.BundleCompat
import de.mischpult.library.Track
import de.mischpult.library.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import de.mischpult.source.plugin.PluginProtocol as P

/** A plugin's answer that something failed: what to show, in a sentence and in a few words. */
class PluginException(message: String, val short: String, val refused: Boolean) : IOException(message)

/** An installed source plugin, as it describes itself. */
data class SourcePlugin(
    val authority: String,
    val id: String,
    val name: String,
    /** Search filters, narrowest first: key to label. */
    val filters: List<Pair<String, String>>,
)

/**
 * Online sources come from separate plugin apps (docs/PLUGINS.md): this finds
 * the installed ones and talks to them. Each answers ContentResolver.call()
 * with bundles and hands audio over as a pipe; nothing of a source's own code
 * is part of Mischpult.
 */
class SourcePlugins(private val context: Context) {
    private val _plugins = MutableStateFlow<List<SourcePlugin>>(emptyList())
    val plugins: StateFlow<List<SourcePlugin>> = _plugins.asStateFlow()

    /** Looks for plugins again (call when the app comes to the front: one may have been installed). */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val found = context.packageManager.queryIntentContentProviders(Intent(P.ACTION), 0)
            .mapNotNull { it.providerInfo?.authority?.substringBefore(';') }
            .mapNotNull { authority -> runCatching { describe(authority) }.getOrNull() }
        _plugins.value = found
    }

    /** The plugin a saved track came from, if it is installed. */
    fun forTrack(track: Track): SourcePlugin? =
        if (track.source != TrackSource.ONLINE) null else _plugins.value.firstOrNull { it.id == track.plugin }

    fun forId(id: String): SourcePlugin? = _plugins.value.firstOrNull { it.id == id }

    class Page(val tracks: List<Track>, val next: String?)
    class Opened(val playlist: String?, val tracks: List<Track>)
    class Prepared(val size: Long, val mime: String?)

    suspend fun search(p: SourcePlugin, query: String, filter: String?): Page = withContext(Dispatchers.IO) {
        page(p, call(p, P.SEARCH, Bundle().apply { putString(P.KEY_QUERY, query); putString(P.KEY_FILTER, filter) }))
    }

    suspend fun more(p: SourcePlugin, next: String): Page = withContext(Dispatchers.IO) {
        page(p, call(p, P.MORE, Bundle().apply { putString(P.KEY_NEXT, next) }))
    }

    /** A link to an item or a list of them. */
    suspend fun open(p: SourcePlugin, link: String): Opened = withContext(Dispatchers.IO) {
        val b = call(p, P.OPEN, Bundle().apply { putString(P.KEY_LINK, link) })
        Opened(b.getString(P.KEY_PLAYLIST), tracks(p, b))
    }

    /** Null if the item plays, otherwise a few words why not. */
    suspend fun check(p: SourcePlugin, uri: String): String? = withContext(Dispatchers.IO) {
        val b = call(p, P.CHECK, Bundle().apply { putString(P.KEY_URI, uri) })
        if (b.getBoolean(P.KEY_PLAYS)) null else b.getString(P.KEY_SHORT) ?: "Won't play"
    }

    /** Gets the item's audio ready to read; its size in bytes if known (-1 otherwise). */
    suspend fun prepare(p: SourcePlugin, uri: String): Prepared = withContext(Dispatchers.IO) {
        val b = call(p, P.PREPARE, Bundle().apply { putString(P.KEY_URI, uri) })
        Prepared(b.getLong(P.KEY_SIZE, -1), b.getString(P.KEY_MIME))
    }

    /**
     * The item's audio bytes, in order. Read to the end, then [AudioStream.close]:
     * that reports a failure the plugin had on its side as a [PluginException].
     */
    fun openAudio(p: SourcePlugin, uri: String): AudioStream {
        val audio = Uri.Builder().scheme("content").authority(p.authority)
            .appendPath(P.AUDIO_PATH).appendPath(uri).build()
        val pfd = try {
            context.contentResolver.openFileDescriptor(audio, "r")
        } catch (e: Exception) {
            throw missing(p, e)
        } ?: throw PluginException("${p.name} did not hand over the audio", "No audio", false)
        return AudioStream(pfd)
    }

    class AudioStream(private val pfd: ParcelFileDescriptor) : InputStream() {
        private val input = FileInputStream(pfd.fileDescriptor)
        override fun read(): Int = input.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)
        override fun close() {
            try {
                // The plugin closes its end with a message when it failed
                pfd.checkError()
            } catch (e: IOException) {
                throw PluginException(e.message.orEmpty().removePrefix("Remote error: ").ifEmpty { "Download failed" },
                    "Download failed", false)
            } finally {
                runCatching { input.close() }
                runCatching { pfd.close() }
            }
        }
    }

    private fun describe(authority: String): SourcePlugin {
        val b = context.contentResolver.call(Uri.parse("content://$authority"), P.DESCRIBE, null, null)
            ?: throw IOException("No answer")
        require(b.getInt(P.KEY_PROTOCOL) == P.VERSION) { "Protocol ${b.getInt(P.KEY_PROTOCOL)}, not ${P.VERSION}" }
        val keys = b.getStringArrayList(P.KEY_FILTERS).orEmpty()
        val labels = b.getStringArrayList(P.KEY_FILTER_LABELS).orEmpty()
        return SourcePlugin(
            authority = authority,
            id = b.getString(P.KEY_ID) ?: authority,
            name = b.getString(P.KEY_NAME) ?: authority,
            filters = keys.mapIndexed { i, k -> k to (labels.getOrNull(i) ?: k) },
        )
    }

    private fun call(p: SourcePlugin, method: String, extras: Bundle): Bundle {
        val b = try {
            context.contentResolver.call(Uri.parse("content://${p.authority}"), method, null, extras)
        } catch (e: Exception) {
            throw missing(p, e)
        } ?: throw PluginException("${p.name} did not answer", "No answer", false)
        b.getString(P.KEY_ERROR)?.let { throw PluginException(it, b.getString(P.KEY_SHORT) ?: "Failed", b.getBoolean(P.KEY_REFUSED)) }
        return b
    }

    private fun missing(p: SourcePlugin, e: Exception): Exception =
        if (e is IllegalArgumentException || e is SecurityException) {
            PluginException("The ${p.name} plugin is not installed any more", "Plugin missing", false)
        } else e

    private fun page(p: SourcePlugin, b: Bundle) = Page(tracks(p, b), b.getString(P.KEY_NEXT))

    private fun tracks(p: SourcePlugin, b: Bundle): List<Track> =
        BundleCompat.getParcelableArrayList(b, P.KEY_TRACKS, Bundle::class.java).orEmpty().mapNotNull { t ->
            Track(
                id = t.getString(P.TRACK_ID) ?: return@mapNotNull null,
                source = TrackSource.ONLINE,
                plugin = p.id,
                uri = t.getString(P.TRACK_URI) ?: return@mapNotNull null,
                title = t.getString(P.TRACK_TITLE).orEmpty(),
                artist = t.getString(P.TRACK_ARTIST).orEmpty(),
                durationSec = t.getLong(P.TRACK_DURATION, -1),
                thumbnailUrl = t.getString(P.TRACK_THUMBNAIL),
            )
        }
}
