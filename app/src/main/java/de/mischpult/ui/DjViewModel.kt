package de.mischpult.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.mischpult.MischpultApp
import de.mischpult.audio.Deck
import de.mischpult.DjController
import de.mischpult.library.CrateStore
import de.mischpult.library.Track
import de.mischpult.source.describeLoadError
import de.mischpult.source.plugin.SourcePlugin
import de.mischpult.source.searchTermsFor
import de.mischpult.source.shortLoadError
import de.mischpult.source.songTermsFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUi(
    val query: String = "",
    /** The online source's search filter in use (its key); null: the source's first. */
    val filter: String? = null,
    val results: List<Track> = emptyList(),
    val searching: Boolean = false,
    val hasMore: Boolean = false,
    val message: String? = null,
    /** Set while the results are other uploads of a refused track: that track's title. */
    val alternativesFor: String? = null,
    /** Per result (track id): whether it plays, checked in the background for other uploads. */
    val checks: Map<String, PlayCheck> = emptyMap(),
)

/** Result of asking the source whether an item plays: [plays] null while still checking. */
data class PlayCheck(val plays: Boolean?, val reason: String? = null)

/** Screen state for the library; the DJ session itself lives in [DjController]. */
class DjViewModel(app: Application) : AndroidViewModel(app) {

    val controller: DjController = (app as MischpultApp).controller
    val engine get() = controller.engine
    val crates get() = controller.crates
    val decks get() = controller.decks
    val cache get() = controller.cache

    private val _library = MutableStateFlow(LibraryUi())
    val library: StateFlow<LibraryUi> = _library.asStateFlow()
    /** Where the next page of the current search comes from: the plugin and its token. */
    private var nextPage: Pair<SourcePlugin, String>? = null
    private var searchJob: Job? = null

    /** Set to show the Library's online search (from a deck); the library resets it. */
    val jumpToSearch = MutableStateFlow(false)

    /** Shared zoom of both scrolling waveforms, in seconds visible. */
    val zoomSeconds = MutableStateFlow(8f)

    fun deck(i: Int): Deck = controller.deck(i)

    fun loadToDeck(i: Int, track: Track) = controller.loadToDeck(i, track)

    fun importFiles(uris: List<Uri>) = runImport { controller.importFiles(uris) }

    fun importFolder(tree: Uri) = runImport { controller.importFolder(tree) }

    private fun runImport(block: suspend () -> Int) {
        _library.update { it.copy(message = "Importing…") }
        viewModelScope.launch {
            val msg = try {
                val n = block()
                "Added $n file${if (n == 1) "" else "s"} to “${CrateStore.LOCAL}”"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "Import failed: ${e.message ?: e.javaClass.simpleName}"
            }
            _library.update { it.copy(message = msg) }
        }
    }

    fun setQuery(q: String) = _library.update { it.copy(query = q) }

    /** The online source searched: the first installed plugin (one is the usual case). */
    fun onlineSource(): SourcePlugin? = controller.plugins.plugins.value.firstOrNull()

    /**
     * The source refused [track]: search for other uploads of the song, leave the
     * refused one out, and check in the background which of the first results
     * actually play, so nobody has to try them one by one at the decks.
     */
    fun findOtherUploads(track: Track) {
        val plugin = controller.plugins.forTrack(track) ?: return
        val terms = searchTermsFor(track.title, track.artist)
        // A source's filters run from narrow to broad: other uploads are looked for in the broadest
        _library.update { it.copy(query = terms, filter = plugin.filters.lastOrNull()?.first) }
        jumpToSearch.value = true
        // Also the song alone: a refused upload often comes from a channel that is refused as a
        // whole (kids' channels), while covers, remixes and re-uploads elsewhere play
        val broader = songTermsFor(track.title).takeIf { it != terms && it.isNotBlank() }
        search(alternativesOf = track, broaderQuery = broader, source = plugin)
    }

    fun exitAlternatives() = _library.update { it.copy(alternativesFor = null, checks = emptyMap()) }

    fun setFilter(filter: String) {
        _library.update { it.copy(filter = filter) }
        if (_library.value.query.isNotBlank()) search()
    }

    fun search(alternativesOf: Track? = null, broaderQuery: String? = null, source: SourcePlugin? = null) {
        val q = _library.value.query.trim()
        if (q.isEmpty()) return
        val plugin = source ?: onlineSource() ?: run {
            _library.update { it.copy(message = "No online source is installed") }
            return
        }
        val filter = _library.value.filter?.takeIf { f -> plugin.filters.any { it.first == f } }
            ?: plugin.filters.firstOrNull()?.first
        searchJob?.cancel()
        _library.update {
            it.copy(searching = true, message = null, alternativesFor = alternativesOf?.let { t -> songTermsFor(t.title) }, checks = emptyMap())
        }
        searchJob = viewModelScope.launch {
            try {
                if (looksLikeLink(q)) {
                    val r = plugins.open(plugin, q)
                    nextPage = null
                    if (r.playlist == null) {
                        _library.update { it.copy(results = r.tracks, hasMore = false) }
                    } else {
                        crates.addTracks(r.playlist, r.tracks)
                        _library.update {
                            it.copy(
                                results = r.tracks, hasMore = false,
                                message = "Imported playlist “${r.playlist}” (${r.tracks.size} tracks) as a crate",
                            )
                        }
                    }
                } else {
                    val page = plugins.search(plugin, q, filter)
                    nextPage = page.next?.let { plugin to it }
                    var results = page.tracks.filter { it.id != alternativesOf?.id }
                    if (alternativesOf != null && broaderQuery != null) {
                        // Both searches, taken in turns: the artist's uploads and everyone else's
                        val wider = runCatching { plugins.search(plugin, broaderQuery, filter) }.getOrNull()
                        if (wider != null) results = interleave(results, wider.tracks).filter { it.id != alternativesOf.id }
                    }
                    _library.update { it.copy(results = results, hasMore = page.next != null) }
                    if (alternativesOf != null) checkPlayable(plugin, results.take(ALTERNATIVES_CHECKED))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _library.update { it.copy(message = describeLoadError(e)) }
            } finally {
                _library.update { it.copy(searching = false) }
            }
        }
    }

    private val plugins get() = controller.plugins

    /** Something to open rather than search for: an address, not words. */
    private fun looksLikeLink(text: String) =
        "://" in text || text.startsWith("www.") || (' ' !in text && '/' in text && '.' in text.substringBefore('/'))

    fun loadMore() {
        val (plugin, token) = nextPage ?: return
        if (_library.value.searching) return
        _library.update { it.copy(searching = true) }
        searchJob = viewModelScope.launch {
            try {
                val page = plugins.more(plugin, token)
                nextPage = page.next?.let { plugin to it }
                _library.update { it.copy(results = it.results + page.tracks, hasMore = page.next != null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _library.update { it.copy(message = e.message) }
            } finally {
                _library.update { it.copy(searching = false) }
            }
        }
    }

    /** Asks the source about each track, a few at a time; runs inside the search job, so a new search cancels it. */
    private suspend fun checkPlayable(plugin: SourcePlugin, tracks: List<Track>) = kotlinx.coroutines.coroutineScope {
        _library.update { ui -> ui.copy(checks = ui.checks + tracks.associate { it.id to PlayCheck(null) }) }
        val slots = kotlinx.coroutines.sync.Semaphore(3)
        for (t in tracks) launch {
            slots.acquire()
            try {
                val check = try {
                    val why = plugins.check(plugin, t.uri)
                    if (why == null) PlayCheck(true) else PlayCheck(false, why)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PlayCheck(false, shortLoadError(e))
                }
                _library.update { ui -> ui.copy(checks = ui.checks + (t.id to check)) }
            } finally {
                slots.release()
            }
        }
    }

    fun clearMessage() = _library.update { it.copy(message = null) }

    companion object {
        /** How many of the other uploads get checked; each check costs the source a few requests. */
        const val ALTERNATIVES_CHECKED = 12

        /** a1, b1, a2, b2, … without repeats. */
        fun interleave(a: List<Track>, b: List<Track>): List<Track> {
            val out = LinkedHashMap<String, Track>()
            for (i in 0 until maxOf(a.size, b.size)) {
                a.getOrNull(i)?.let { out.putIfAbsent(it.id, it) }
                b.getOrNull(i)?.let { out.putIfAbsent(it.id, it) }
            }
            return out.values.toList()
        }
    }
}
