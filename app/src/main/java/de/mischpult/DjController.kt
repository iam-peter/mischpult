package de.mischpult

import android.app.Application
import android.net.Uri
import android.util.Log
import de.mischpult.audio.BeatGrid
import de.mischpult.audio.BeatDetector
import de.mischpult.audio.DecodeCache
import de.mischpult.audio.Deck
import de.mischpult.audio.KeyDetector
import de.mischpult.audio.MixRecorder
import de.mischpult.audio.MixerEngine
import de.mischpult.audio.WaveformAnalyzer
import de.mischpult.audio.PadMode
import de.mischpult.audio.PcmDecoder
import de.mischpult.audio.PcmTrack
import de.mischpult.library.PAD_COLORS
import de.mischpult.library.PadConfig
import de.mischpult.library.RecordedSet
import de.mischpult.midi.MidiAction
import de.mischpult.midi.MidiCommand
import de.mischpult.midi.MidiController
import de.mischpult.midi.PadTarget
import de.mischpult.source.describeLoadError
import de.mischpult.source.plugin.SourcePlugins
import de.mischpult.source.isRefusal
import de.mischpult.ui.EqCurve
import de.mischpult.ui.TrimCurve
import de.mischpult.library.SamplerStore
import de.mischpult.library.SetEntry
import de.mischpult.library.SetStore
import de.mischpult.library.TrackMetaStore
import de.mischpult.audio.TrackLoader
import de.mischpult.audio.Waveform
import de.mischpult.library.CrateStore
import de.mischpult.library.Track
import de.mischpult.library.TrackSource
import de.mischpult.source.LocalFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DeckUi(
    val track: Track? = null,
    /** Grows while the track loads; length/rate come from the deck's PcmTrack. */
    val waveform: Waveform? = null,
    /** Non-null while loading. The deck is already playable once [waveform] is set. */
    val loading: TrackLoader.Stage? = null,
    val progress: Float = 0f,
    val error: String? = null,
    /** The source refused the track itself; another upload of the song may play. */
    val refused: Boolean = false,
    /** Detected tempo, null until known. */
    val beatGrid: BeatGrid? = null,
    /** Detected musical key (0..11 major, 12..23 minor), null until known. */
    val key: Int? = null,
)

/**
 * The DJ session: engine, decks, track loading and the library's storage.
 * Lives as long as the process (not the screen), so a set keeps playing while
 * [PlaybackService] holds the process in the foreground.
 */
class DjController(private val app: Application, val crates: CrateStore) {

    val engine = MixerEngine()
    val cache = DecodeCache(File(app.cacheDir, "pcm"))
    val trackMeta = TrackMetaStore(app.filesDir)
    /** Online sources: separate plugin apps, looked for at start and whenever the app comes back. */
    val plugins = SourcePlugins(app)
    private val loader = TrackLoader(app, cache, plugins) { crates.offlineTrackIds() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _decks = Array(2) { MutableStateFlow(DeckUi()) }
    val decks: List<StateFlow<DeckUi>> = _decks.map { it.asStateFlow() }
    private val loadJobs = arrayOfNulls<Job>(2)
    // Guarded by deckLock: decoder threads report in while the user may load again
    private val loadGeneration = IntArray(2)
    private val deckLock = Any()

    init {
        scope.launch { plugins.refresh() }
        // Hold the process in the foreground as soon as music plays, so it keeps
        // playing when the screen goes off or the user switches apps
        scope.launch {
            while (true) {
                if (anyPlaying && !PlaybackService.running) {
                    runCatching { PlaybackService.start(app) }
                        .onFailure { Log.w("DjController", "Could not start playback service", it) }
                }
                delay(300)
            }
        }
    }

    fun deck(i: Int): Deck = engine.decks[i]

    fun loadToDeck(i: Int, track: Track) {
        loadJobs[i]?.cancel()
        val generation = synchronized(deckLock) {
            engine.decks[i].onHotCuesChanged = null
            engine.decks[i].load(null)
            ++loadGeneration[i]
        }
        fun current() = loadGeneration[i] == generation
        _decks[i].value = DeckUi(track = track, loading = TrackLoader.Stage.RESOLVING)
        loadJobs[i] = scope.launch {
            try {
                var preliminaryDone = false
                val analyzer = loader.load(
                    track,
                    onReady = { pcm, analyzer ->
                        // A cancelled decoder may still report in; only the latest load wins
                        synchronized(deckLock) {
                            if (current()) {
                                val deck = engine.decks[i]
                                deck.load(pcm)
                                // Hot cues belong to the track: restore them and save every change
                                deck.restoreHotCues(trackMeta[track.id]?.hotCues.orEmpty())
                                deck.onHotCuesChanged = { cues -> trackMeta.update(track.id) { it.copy(hotCues = cues) } }
                                _decks[i].update { it.copy(waveform = analyzer.waveform) }
                            }
                        }
                    },
                    onStage = { stage, p ->
                        synchronized(deckLock) {
                            if (current()) _decks[i].update { it.copy(loading = stage, progress = p) }
                        }
                    },
                    onAnalyzed = { analyzer ->
                        // A first tempo estimate once a minute is in, so SYNC works early
                        // (runs on the analysing thread, so the snapshot is consistent)
                        if (!preliminaryDone && analyzer.analyzedFrames > analyzer.sampleRate * 60) {
                            preliminaryDone = true
                            val onsets = analyzer.snapshotOnsets()
                            scope.launch { detectBeats(i, generation, onsets, analyzer.sampleRate) }
                        }
                    },
                )
                cache.refresh()
                detectBeats(i, generation, analyzer.snapshotOnsets(), analyzer.sampleRate)
                _decks[i].update { it.copy(loading = null) }
                detectKey(i, generation, track)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _decks[i].update { it.copy(loading = null, error = describeLoadError(e), refused = isRefusal(e)) }
            }
        }
    }

    private suspend fun detectBeats(i: Int, generation: Int, onsets: FloatArray, sampleRate: Int) {
        val grid = withContext(Dispatchers.Default) {
            BeatDetector.detect(onsets, Waveform.BIN_FRAMES, sampleRate)
        } ?: return
        synchronized(deckLock) {
            if (loadGeneration[i] != generation) return
            engine.decks[i].beatGrid = grid
            _decks[i].update { it.copy(beatGrid = grid) }
            _decks[i].value.track?.let { t -> trackMeta.update(t.id) { it.copy(bpm = grid.bpm) } }
        }
    }

    /** Key of the fully loaded track on deck [i]; cached per track, so only computed once. */
    private suspend fun detectKey(i: Int, generation: Int, track: Track) {
        val key = trackMeta[track.id]?.key ?: run {
            val pcm = engine.decks[i].track ?: return
            withContext(Dispatchers.Default) { KeyDetector.detect(pcm) }?.also { k ->
                trackMeta.update(track.id) { it.copy(key = k) }
            }
        } ?: return
        synchronized(deckLock) {
            if (loadGeneration[i] == generation) _decks[i].update { it.copy(key = key) }
        }
    }

    /**
     * BPM and key of a decoded track without loading it on a deck (offline
     * prep), so the library can show them. Skipped when already known.
     */
    private suspend fun analyzeIfMissing(track: Track) {
        val meta = trackMeta[track.id]
        if (meta?.bpm != null && meta.key != null) return
        val pcm = PcmTrack.open(cache.fileFor(track.id)) ?: return
        withContext(Dispatchers.Default) {
            val analyzer = WaveformAnalyzer(pcm, Waveform(pcm.expectedFrames))
            analyzer.advance(pcm.available, last = true)
            val grid = BeatDetector.detect(analyzer.snapshotOnsets(), Waveform.BIN_FRAMES, pcm.sampleRate)
            val key = KeyDetector.detect(pcm)
            trackMeta.update(track.id) { it.copy(bpm = grid?.bpm ?: it.bpm, key = key ?: it.key) }
        }
    }

    // --- MIDI controllers -----------------------------------------------------------

    private val jogTouched = BooleanArray(2)

    /** Sampler tab mode, shared with MIDI performance pads: hot cues (true) or samples. */
    val padHotCueMode = MutableStateFlow(false)
    /** Sample bank shown on the Sampler tab and played by MIDI performance pads. */
    val sampleBank = MutableStateFlow(0)

    /** Hold a hot cue this long to clear it, like on screen. */
    private val holdToClear = HashMap<Pair<Int, Int>, Runnable>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun hotCueButton(deck: Int, slot: Int, down: Boolean) {
        val key = deck to slot
        holdToClear.remove(key)?.let { mainHandler.removeCallbacks(it) }
        if (!down) return
        val d = engine.decks[deck]
        // Pressing an empty slot sets the cue; only an existing one can be held away
        val existed = d.hotCues.getOrNull(slot) != null
        d.hotCue(slot)
        if (!existed) return
        val clear = Runnable { engine.decks[deck].clearHotCue(slot); holdToClear.remove(key) }
        holdToClear[key] = clear
        mainHandler.postDelayed(clear, HOLD_TO_CLEAR_MS)
    }

    val midi = MidiController(app, dispatch = { onMidi(it) }, ledState = { ledState(it) })

    /** What a mapped button's LED should show; null for controls without one. */
    private fun ledState(a: MidiAction): Boolean? {
        if (a in PADS) return engine.sampler.pads[a.slot].playing
        if (a == MidiAction.PAD_MODE) return padHotCueMode.value
        if (a.isPerformancePad) {
            return when (val t = PadTarget.of(padHotCueMode.value, sampleBank.value, a.deck!!, a.slot)) {
                is PadTarget.HotCue -> engine.decks[t.deck].hotCues.getOrNull(t.slot) != null
                is PadTarget.Sample -> engine.sampler.pads[t.padIndex].playing
            }
        }
        val d = a.deck?.let { engine.decks[it] } ?: return null
        return when (val name = a.name.substring(2)) {
            "PLAY" -> d.playing
            // Lit while the deck stands at its cue point, like on a CDJ
            "CUE" -> d.track != null && !d.playing &&
                kotlin.math.abs(d.position - d.cuePoint) < (d.track?.sampleRate ?: 44100) * 0.05
            "SYNC" -> d.sync
            "KEYLOCK" -> d.keyLock
            "FX_ON" -> d.fxOn
            "PFL" -> d.pfl
            "LOOP" -> d.loopActive
            "LOOP_IN" -> d.loopIn >= 0
            "LOOP_OUT" -> d.hasLoop
            "AUTOLOOP" -> d.loopActive && d.loopBeats()?.let { kotlin.math.abs(it - 4) < 0.05 } == true
            else -> if (name.startsWith("HOT")) d.hotCues.getOrNull(a.slot) != null else null
        }
    }

    /** Turns a mapped controller message into what the on-screen control would do. */
    private fun onMidi(cmd: MidiCommand) {
        val a = cmd.action
        val i = a.deck
        val deck = i?.let { engine.decks[it] }
        when (cmd) {
            is MidiCommand.Press -> {
                when (a) {
                    // Momentary controls react to both edges
                    MidiAction.A_JOG_TOUCH, MidiAction.B_JOG_TOUCH -> {
                        jogTouched[i!!] = cmd.down
                        if (cmd.down) deck!!.beginScratch() else deck!!.endScratch()
                        return
                    }
                    in PADS -> {
                        val pad = engine.sampler.pads[a.slot]
                        if (cmd.down) pad.press() else pad.release()
                        return
                    }
                    MidiAction.PAD_MODE -> {
                        if (cmd.down) padHotCueMode.value = !padHotCueMode.value
                        return
                    }
                    else -> {
                        if (a.isHotCue) { hotCueButton(i!!, a.slot, cmd.down); return }
                        if (a.isPerformancePad) {
                            when (val t = PadTarget.of(padHotCueMode.value, sampleBank.value, i!!, a.slot)) {
                                is PadTarget.HotCue -> hotCueButton(t.deck, t.slot, cmd.down)
                                is PadTarget.Sample -> engine.sampler.pads[t.padIndex].let {
                                    if (cmd.down) it.press() else it.release()
                                }
                            }
                            return
                        }
                        if (!cmd.down) return
                    }
                }
                when (a.name.substring(2)) {
                    "PLAY" -> deck!!.playing = !deck.playing
                    "CUE" -> deck!!.cue()
                    "SYNC" -> if (deck!!.sync) deck.sync = false else engine.engageSync(i!!)
                    "KEYLOCK" -> deck!!.keyLock = !deck.keyLock
                    "FX_ON" -> deck!!.fxOn = !deck.fxOn
                    "PFL" -> deck!!.pfl = !deck.pfl
                    "LOOP_IN" -> deck!!.setLoopIn()
                    "LOOP_OUT" -> deck!!.setLoopOut()
                    "LOOP" -> deck!!.toggleLoop()
                    "AUTOLOOP" -> deck!!.autoLoop(4.0)
                    "JUMP_BACK" -> deck!!.beatJump(-4)
                    "JUMP_FWD" -> deck!!.beatJump(4)
                }
            }
            is MidiCommand.Set -> {
                val v = cmd.value
                when (a) {
                    MidiAction.CROSSFADER -> engine.crossfader = v * 2 - 1
                    MidiAction.MASTER -> engine.masterVolume = v
                    else -> when (a.name.substring(2)) {
                        "TEMPO" -> { deck!!.sync = false; deck.tempo = (v * 2 - 1) * 0.08f }
                        "VOLUME" -> deck!!.volume = v
                        "TRIM" -> deck!!.trim = TrimCurve.gain(v)
                        "EQ_HI" -> deck!!.eqHigh = EqCurve.gain(v)
                        "EQ_MID" -> deck!!.eqMid = EqCurve.gain(v)
                        "EQ_LOW" -> deck!!.eqLow = EqCurve.gain(v)
                        // A small dead zone, so "centre" on a hardware knob really is off
                        "FILTER" -> deck!!.filter = (v * 2 - 1).let { if (kotlin.math.abs(it) < 0.03f) 0f else it }
                        "FX_AMOUNT" -> deck!!.fxAmount = v
                    }
                }
            }
            is MidiCommand.Turn -> {
                val d = deck ?: return
                val rate = (d.track?.sampleRate ?: return).toDouble()
                when {
                    // Touched platter: scratch, ~12 ms of audio per encoder step
                    jogTouched[i!!] -> d.scratchBy(cmd.steps * rate * 0.012)
                    // Playing: nudge to line up the beats; stopped: browse through the track
                    d.playing -> d.seek(d.position + cmd.steps * rate * 0.002)
                    else -> d.seek((d.position + cmd.steps * rate * 0.02).coerceAtLeast(0.0))
                }
            }
        }
    }

    // --- Recording the mix + set list -----------------------------------------------

    val sets = SetStore(app.filesDir)
    private var recorder: MixRecorder? = null
    private var recordingStartedAt = 0L
    private var recordJob: Job? = null

    /** Live state of a recording in progress; null while not recording. */
    data class RecordingState(val elapsedMs: Long, val entries: List<SetEntry>)

    private val _recording = MutableStateFlow<RecordingState?>(null)
    val recording: StateFlow<RecordingState?> = _recording.asStateFlow()

    /**
     * Starts recording the master to Music/Mischpult and logs a set list: a track
     * counts as played once its deck is playing and audible (fader × crossfader
     * at least 30 %), with the time into the recording.
     */
    fun startRecording(): String? {
        if (recorder != null) return null
        val rec = MixRecorder(app, engine.sampleRate)
        try {
            rec.start()
        } catch (e: Exception) {
            return e.message ?: "Could not start recording"
        }
        recorder = rec
        recordingStartedAt = System.currentTimeMillis()
        _recording.value = RecordingState(0, emptyList())
        PlaybackService.start(app) // keep the process alive while recording in the background
        recordJob = scope.launch {
            val onAir = arrayOfNulls<String>(2)
            while (true) {
                val x = (engine.crossfader + 1f) * 0.5f * (Math.PI.toFloat() / 2f)
                val entries = _recording.value?.entries.orEmpty().toMutableList()
                for (i in 0..1) {
                    val deck = engine.decks[i]
                    val gain = (if (i == 0) kotlin.math.cos(x) else kotlin.math.sin(x)) * deck.volume
                    val track = _decks[i].value.track
                    val audible = deck.playing && gain >= 0.3f && track != null
                    // Newly on air; the same track again right away (after a pause) is no new entry
                    if (audible && onAir[i] != track!!.id && entries.lastOrNull()?.trackId != track.id) {
                        entries += SetEntry(rec.elapsedMs, track.id, track.title, track.artist, i,
                            trackMeta[track.id]?.bpm, trackMeta[track.id]?.key)
                    }
                    onAir[i] = if (audible) track!!.id else null
                }
                _recording.value = RecordingState(rec.elapsedMs, entries)
                delay(500)
            }
        }
        return null
    }

    /** Stops the recording and stores the set with its set list. */
    suspend fun stopRecording(): RecordedSet? {
        val rec = recorder ?: return null
        recordJob?.cancel()
        val entries = _recording.value?.entries.orEmpty()
        recorder = null
        _recording.value = null
        val result = withContext(Dispatchers.IO) { rec.stop() } ?: return null
        val set = RecordedSet(
            id = recordingStartedAt.toString(),
            name = result.displayName.removeSuffix(".m4a"),
            uri = result.uri.toString(),
            startedAt = recordingStartedAt,
            durationMs = result.durationMs,
            entries = entries,
        )
        sets.add(set)
        return set
    }

    /** Deletes a recorded set and its audio file. */
    fun deleteSet(set: RecordedSet) {
        runCatching {
            val uri = Uri.parse(set.uri)
            if (uri.scheme == "file") File(uri.path!!).delete() else app.contentResolver.delete(uri, null, null)
        }
        sets.remove(set.id)
    }

    val isRecording: Boolean get() = recorder != null

    // --- Offline gig prep ----------------------------------------------------------

    /** Progress of preparing a crate for offline use; null before the first run. */
    data class PrepState(
        val crate: String,
        val total: Int,
        val done: Int = 0,
        val current: List<String> = emptyList(),
        val failed: List<Pair<Track, String>> = emptyList(),
        val running: Boolean = true,
    )

    private val _prep = MutableStateFlow<PrepState?>(null)
    val prep: StateFlow<PrepState?> = _prep.asStateFlow()
    private var prepJob: Job? = null

    /**
     * Downloads and decodes every track of [crateName] ahead of time, so a gig
     * never depends on the venue's network. Marks the crate offline, which also
     * protects its decodes from cache trimming. Runs two tracks at a time.
     */
    fun prepareCrate(crateName: String) {
        if (prepJob?.isActive == true) return
        val tracks = crates.crates.value.firstOrNull { it.name == crateName }?.tracks ?: return
        crates.setOffline(crateName, true)
        _prep.value = PrepState(crateName, tracks.size)
        PrepService.start(app)
        prepJob = scope.launch {
            val queue = Channel<Track>(Channel.UNLIMITED)
            tracks.forEach { queue.trySend(it) }
            queue.close()
            coroutineScope {
                repeat(PREP_PARALLEL) {
                    launch {
                        for (track in queue) {
                            _prep.update { it?.copy(current = it.current + track.title) }
                            try {
                                loader.prepare(track) { _, _ -> }
                                analyzeIfMissing(track)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                val msg = describeLoadError(e)
                                _prep.update { it?.copy(failed = it.failed + (track to msg)) }
                            } finally {
                                _prep.update { it?.copy(done = it.done + 1, current = it.current - track.title) }
                                cache.refresh()
                            }
                        }
                    }
                }
            }
        }.also { job ->
            job.invokeOnCompletion { _prep.update { it?.copy(running = false, current = emptyList()) } }
        }
    }

    fun cancelPrep() {
        prepJob?.cancel()
    }

    /** The crate stops being offline: its decodes stay but may be trimmed again. */
    fun setOffline(crateName: String, offline: Boolean) = crates.setOffline(crateName, offline)

    // --- Library storage -------------------------------------------------------

    /** Removes [trackId] from a crate; its decoded audio goes too once no crate holds it. */
    fun removeFromCrate(crateName: String, trackId: String) {
        val track = crates.crates.value.firstOrNull { it.name == crateName }?.tracks?.firstOrNull { it.id == trackId }
        crates.removeTrack(crateName, trackId)
        forgetIfOrphaned(listOfNotNull(track))
    }

    fun deleteCrate(crateName: String) {
        val tracks = crates.crates.value.firstOrNull { it.name == crateName }?.tracks.orEmpty()
        crates.deleteCrate(crateName)
        forgetIfOrphaned(tracks)
    }

    private fun forgetIfOrphaned(tracks: List<Track>) {
        val stillUsed = crates.crates.value.flatMapTo(HashSet()) { c -> c.tracks.map { it.id } }
        val orphans = tracks.filter { it.id !in stillUsed }
        if (orphans.isEmpty()) return
        cache.delete(orphans.map { it.id })
        orphans.filter { it.source == TrackSource.LOCAL_FILE }.forEach { LocalFiles.release(app, Uri.parse(it.uri)) }
    }

    fun clearDecoded(trackId: String) = cache.delete(trackId)

    fun clearAllDecoded() = cache.clearAll()

    // --- Local files -------------------------------------------------------------

    suspend fun importFiles(uris: List<Uri>): Int {
        val tracks = LocalFiles.importDocuments(app, uris)
        crates.addTracks(CrateStore.LOCAL, tracks)
        return tracks.size
    }

    suspend fun importFolder(tree: Uri): Int {
        val tracks = LocalFiles.importTree(app, tree)
        crates.addTracks(CrateStore.LOCAL, tracks)
        return tracks.size
    }

    private companion object {
        const val PREP_PARALLEL = 2
        const val HOLD_TO_CLEAR_MS = 600L
        val PADS = setOf(MidiAction.PAD1, MidiAction.PAD2, MidiAction.PAD3, MidiAction.PAD4,
            MidiAction.PAD5, MidiAction.PAD6, MidiAction.PAD7, MidiAction.PAD8)
    }

    val anyPlaying: Boolean get() = engine.decks.any { it.playing } || engine.sampler.active || isRecording

    // --- Sampler -------------------------------------------------------------------

    val samples = SamplerStore(File(app.filesDir, "samples"))
    private val _padsBusy = MutableStateFlow<Set<Int>>(emptySet())
    /** Pads currently getting a new sample (decoding/copying). */
    val padsBusy: StateFlow<Set<Int>> = _padsBusy.asStateFlow()

    init {
        // Declared here, not at the top: initializers run in order and need [samples]
        restorePads()
    }

    private fun restorePads() {
        for ((index, cfg) in samples.pads.value) {
            val pad = engine.sampler.pads[index]
            pad.sample = PcmTrack.open(samples.audioFile(index))
            applyPad(pad, cfg)
        }
    }

    private fun applyPad(pad: de.mischpult.audio.Pad, cfg: PadConfig) {
        pad.mode = cfg.mode
        pad.volume = cfg.volume
        pad.bpm = cfg.bpm?.toFloat() ?: 0f
        pad.followTempo = cfg.followTempo
        pad.keyLock = cfg.keyLock
    }

    /** Decodes an audio file into pad [index]. Returns an error message, or null on success. */
    suspend fun assignPadFromFile(index: Int, uri: Uri): String? = assignPad(index) {
        val tmp = File(samples.dir, "pad_$index.new.pcm")
        val pcm = PcmDecoder.decode(PcmDecoder.uriSource(app, uri), tmp, onStart = {}, onProgress = { _, _, _ -> })
        // Long enough for beat detection (~15 s)? Then the pad knows its tempo
        val analyzer = WaveformAnalyzer(pcm, Waveform(pcm.expectedFrames))
        analyzer.advance(pcm.available, last = true)
        val bpm = BeatDetector.detect(analyzer.snapshotOnsets(), Waveform.BIN_FRAMES, pcm.sampleRate)?.bpm
        PadSource(tmp, LocalFiles.displayName(app, uri).substringBeforeLast('.'), bpm)
    }

    /** Copies deck [deck]'s current loop into pad [index]. */
    suspend fun assignPadFromLoop(index: Int, deck: Int): String? = assignPad(index) {
        val d = engine.decks[deck]
        val t = d.track ?: throw IllegalStateException("Deck ${"AB"[deck]} is empty")
        if (!d.hasLoop) throw IllegalStateException("Set a loop on deck ${"AB"[deck]} first")
        val tmp = File(samples.dir, "pad_$index.new.pcm")
        t.copyRange(d.loopIn.toInt(), d.loopOut.toInt(), tmp)
        val title = decks[deck].value.track?.title ?: "Deck ${"AB"[deck]}"
        val beats = d.loopBeats()?.let { " · ${Math.round(it)} beats" }.orEmpty()
        // The copied audio is at the track's own speed, so its tempo is the track's BPM
        PadSource(tmp, "$title$beats", d.beatGrid?.bpm)
    }

    private class PadSource(val file: File, val name: String, val bpm: Double?)

    private suspend fun assignPad(index: Int, produce: suspend () -> PadSource): String? {
        _padsBusy.update { it + index }
        return try {
            val source = withContext(Dispatchers.IO) { produce() }
            val tmp = source.file
            val name = source.name
            val target = samples.audioFile(index)
            engine.sampler.pads[index].stop()
            withContext(Dispatchers.IO) {
                if (!tmp.renameTo(target)) throw java.io.IOException("Could not store sample")
            }
            // The previous sample stays mapped until the voice lets go of it
            engine.sampler.pads[index].sample = PcmTrack.open(target)
            val old = samples.pads.value[index]
            val cfg = PadConfig(index, name.take(40), old?.mode ?: PadMode.ONE_SHOT,
                old?.volume ?: 0.9f, old?.color ?: (index % PAD_COLORS),
                bpm = source.bpm, followTempo = old?.followTempo ?: false, keyLock = old?.keyLock ?: true)
            samples.put(cfg)
            applyPad(engine.sampler.pads[index], cfg)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e.message ?: e.javaClass.simpleName
        } finally {
            _padsBusy.update { it - index }
        }
    }

    fun updatePad(index: Int, transform: (PadConfig) -> PadConfig) {
        samples.update(index, transform)
        val cfg = samples.pads.value[index] ?: return
        applyPad(engine.sampler.pads[index], cfg)
    }

    fun clearPad(index: Int) {
        val pad = engine.sampler.pads[index]
        pad.stop()
        pad.sample = null
        samples.remove(index)
    }

    /** Stops both decks (positions and loaded tracks stay). */
    fun stopAll() {
        engine.decks.forEach {
            it.playing = false
            it.fxOn = false // no echo/reverb tail ringing on after "stop"
        }
        engine.sampler.stopAll()
        // A recording in progress is finished and kept, not lost
        if (recorder != null) scope.launch { stopRecording() }
    }
}
