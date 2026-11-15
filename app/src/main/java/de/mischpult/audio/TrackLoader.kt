package de.mischpult.audio

import android.content.Context
import android.net.Uri
import de.mischpult.library.Track
import de.mischpult.library.TrackSource
import de.mischpult.source.plugin.PluginException
import de.mischpult.source.plugin.SourcePlugins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Track → (resolve → download → decode, or cache hit) → PCM + waveform.
 *
 * [load] calls `onReady` as early as possible — before decoding has finished —
 * with a track that keeps growing, so the deck can start playing right away.
 * It returns once the track is fully decoded and analysed, with the analyzer
 * so callers can run beat detection on the result.
 */
class TrackLoader(
    private val context: Context,
    private val cache: DecodeCache,
    /** Where online tracks' audio comes from. */
    private val plugins: SourcePlugins,
    /** Tracks whose decoded audio must survive cache trimming (offline crates). */
    private val protectedIds: () -> Set<String> = { emptySet() },
) {
    private val dlDir = File(context.cacheDir, "download").apply { mkdirs() }

    // One decode per track at a time: offline prep and a deck may ask for the same one
    private val locks = HashMap<String, Mutex>()
    private fun lockFor(id: String) = synchronized(locks) { locks.getOrPut(id) { Mutex() } }

    enum class Stage { RESOLVING, DOWNLOADING, DECODING, ANALYZING }

    suspend fun load(
        track: Track,
        onReady: (PcmTrack, WaveformAnalyzer) -> Unit,
        onStage: (Stage, Float) -> Unit,
        onAnalyzed: (WaveformAnalyzer) -> Unit = {},
    ): WaveformAnalyzer {
        val pcmFile = cache.fileFor(track.id)
        val lock = lockFor(track.id)
        // Busy means offline prep is decoding this very track; it will be cached in a moment
        if (lock.isLocked) onStage(Stage.DECODING, 0f)
        val analyzer = lock.withLock { loadLocked(track, pcmFile, onReady, onStage, onAnalyzed) }
        withContext(Dispatchers.IO) { cache.trim(MAX_CACHE_BYTES, keep = track.id, protected = protectedIds()) }
        return analyzer
    }

    /**
     * Makes sure [track] is decoded into the cache without loading it anywhere:
     * offline gig prep. Returns true if work was done, false if it was cached.
     */
    suspend fun prepare(track: Track, onStage: (Stage, Float) -> Unit): Boolean {
        val pcmFile = cache.fileFor(track.id)
        return lockFor(track.id).withLock {
            if (PcmTrack.open(pcmFile) != null) return@withLock false
            fetchAndDecode(track, pcmFile, onReady = { _, _ -> }, onStage = onStage, onAnalyzed = {})
            true
        }.also { withContext(Dispatchers.IO) { cache.trim(MAX_CACHE_BYTES, keep = track.id, protected = protectedIds()) } }
    }

    private suspend fun loadLocked(
        track: Track,
        pcmFile: File,
        onReady: (PcmTrack, WaveformAnalyzer) -> Unit,
        onStage: (Stage, Float) -> Unit,
        onAnalyzed: (WaveformAnalyzer) -> Unit,
    ): WaveformAnalyzer {
        val cached = PcmTrack.open(pcmFile)
        return if (cached != null) {
            cache.touch(track.id)
            val analyzer = WaveformAnalyzer(cached, Waveform(cached.expectedFrames))
            onReady(cached, analyzer)
            onStage(Stage.ANALYZING, 0f)
            // Progressive too: the waveform fills in while the deck is usable
            withContext(Dispatchers.Default) {
                val step = cached.sampleRate * 30
                var at = 0
                while (at < cached.available) {
                    ensureActive()
                    at = minOf(at + step, cached.available)
                    analyzer.advance(at, last = at == cached.available)
                    onAnalyzed(analyzer)
                    onStage(Stage.ANALYZING, at.toFloat() / cached.available)
                }
            }
            analyzer
        } else {
            fetchAndDecode(track, pcmFile, onReady, onStage, onAnalyzed)
        }
    }

    private suspend fun fetchAndDecode(
        track: Track,
        pcmFile: File,
        onReady: (PcmTrack, WaveformAnalyzer) -> Unit,
        onStage: (Stage, Float) -> Unit,
        onAnalyzed: (WaveformAnalyzer) -> Unit,
    ): WaveformAnalyzer {
        val source = when (track.source) {
            TrackSource.ONLINE -> null
            TrackSource.LOCAL_FILE -> PcmDecoder.uriSource(context, Uri.parse(track.uri))
        }
        return if (source != null) {
            decode(source, pcmFile, onReady, onStage, onAnalyzed)
        } else {
            val compressed = downloadOnline(track, onStage)
            try {
                decode(PcmDecoder.fileSource(compressed), pcmFile, onReady, onStage, onAnalyzed)
            } finally {
                compressed.delete()
            }
        }
    }

    private suspend fun decode(
        source: PcmDecoder.Source,
        pcmFile: File,
        onReady: (PcmTrack, WaveformAnalyzer) -> Unit,
        onStage: (Stage, Float) -> Unit,
        onAnalyzed: (WaveformAnalyzer) -> Unit,
    ): WaveformAnalyzer {
        onStage(Stage.DECODING, 0f)
        var analyzer: WaveformAnalyzer? = null
        var lastPercent = -1
        PcmDecoder.decode(
            source, pcmFile,
            onStart = { pcm ->
                val a = WaveformAnalyzer(pcm, Waveform(pcm.expectedFrames))
                analyzer = a
                onReady(pcm, a)
            },
            onProgress = { frames, fraction, done ->
                // Runs on the decoder thread; analysing ~1k frames is cheap next to the codec
                analyzer?.let {
                    it.advance(frames, last = done)
                    onAnalyzed(it)
                }
                val percent = (fraction * 100).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    onStage(Stage.DECODING, fraction)
                }
            },
        )
        return analyzer!!
    }

    /** Fetches an online track's audio from its source plugin into a file, with progress. */
    private suspend fun downloadOnline(track: Track, onStage: (Stage, Float) -> Unit): File = withContext(Dispatchers.IO) {
        val plugin = plugins.forTrack(track) ?: throw PluginException(
            "This track comes from the source plugin “${track.plugin}”, which is not installed",
            "Plugin missing", refused = false,
        )
        val dest = File(dlDir, "${track.id}.audio")
        val part = File(dlDir, "${track.id}.audio.part")
        // Links to the audio expire and are sometimes refused: one more try with a fresh one
        var attempt = 0
        while (true) {
            onStage(Stage.RESOLVING, 0f)
            val size = plugins.prepare(plugin, track.uri).size
            onStage(Stage.DOWNLOADING, 0f)
            try {
                var written = 0L
                plugins.openAudio(plugin, track.uri).use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var lastShown = -1
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (size > 0) {
                                val percent = (written * 100 / size).toInt()
                                if (percent != lastShown) { lastShown = percent; onStage(Stage.DOWNLOADING, written.toFloat() / size) }
                            }
                        }
                    }
                }
                if (size > 0 && written < size) throw java.io.IOException("Download stopped early ($written of $size bytes)")
                if (!part.renameTo(dest)) throw java.io.IOException("Could not move the download into place")
                return@withContext dest
            } catch (e: java.io.IOException) {
                part.delete()
                // A refusal stays a refusal; anything else may have been an expired link
                if ((e is PluginException && e.refused) || ++attempt >= 2) throw e
            }
        }
        @Suppress("UNREACHABLE_CODE")
        dest
    }

    companion object {
        // ~2 GB ≈ 40 four-minute tracks
        private const val MAX_CACHE_BYTES = 2L shl 30
    }
}
