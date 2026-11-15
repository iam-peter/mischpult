package de.mischpult.audio

import de.mischpult.audio.NativeEngine as N

enum class FxType(val label: String) { ECHO("ECHO"), REVERB("REVERB"), FLANGER("FLANGER") }

/**
 * One turntable plus its channel processing, as seen from Kotlin. The sound is
 * made by the native engine (src/main/cpp/Engine.cpp); this class is its
 * control surface: properties read and write the engine's atomics directly,
 * and decisions that need no audio-rate precision (cue, loop markers, quantising)
 * are made here.
 */
class Deck(val index: Int) {

    /** The loaded track (Kotlin side: waveform, length). */
    @Volatile var track: PcmTrack? = null
        private set

    var playing: Boolean
        get() = N.deckGetF(index, N.D_PLAYING) != 0f
        set(v) = N.deckSetF(index, N.D_PLAYING, if (v) 1f else 0f)
    /** Tempo offset, e.g. +0.04 = +4 %. Changes pitch too (like a turntable). Sync writes it. */
    var tempo: Float
        get() = N.deckGetF(index, N.D_TEMPO)
        set(v) = N.deckSetF(index, N.D_TEMPO, v)
    /** Channel fader 0..1. */
    var volume by floatParam(N.D_VOLUME)
    /** Cue point in source frames. */
    @Volatile var cuePoint = 0.0

    // Channel processing
    var trim by floatParam(N.D_TRIM)
    var eqLow by floatParam(N.D_EQ_LOW)
    var eqMid by floatParam(N.D_EQ_MID)
    var eqHigh by floatParam(N.D_EQ_HIGH)
    /** -1 low-pass … 0 off … +1 high-pass. */
    var filter by floatParam(N.D_FILTER)
    var filterResonance by floatParam(N.D_FILTER_RES)
    var fxType: FxType
        get() = FxType.entries[N.deckGetF(index, N.D_FX_TYPE).toInt().coerceIn(0, FxType.entries.size - 1)]
        set(v) = N.deckSetF(index, N.D_FX_TYPE, v.ordinal.toFloat())
    var fxOn by boolParam(N.D_FX_ON)
    var fxAmount by floatParam(N.D_FX_AMOUNT)
    /** Echo time in beats. */
    var echoBeats by floatParam(N.D_ECHO_BEATS)
    var echoFeedback by floatParam(N.D_ECHO_FB)
    /** 0 = dark repeats … 1 = bright. */
    var echoTone by floatParam(N.D_ECHO_TONE)
    var reverbSize by floatParam(N.D_REV_SIZE)
    var reverbDamp by floatParam(N.D_REV_DAMP)
    /** Flanger sweep length in beats. */
    var flangerBeats by floatParam(N.D_FL_BEATS)
    var flangerDepth by floatParam(N.D_FL_DEPTH)
    var flangerFeedback by floatParam(N.D_FL_FB)

    /** Headphone pre-listening (PFL) for this channel. */
    var pfl by boolParam(N.D_PFL)
    /** Tempo follows the other (playing) deck while true. */
    var sync by boolParam(N.D_SYNC)
    /** Key lock: tempo changes keep the pitch while the deck plays normally. */
    var keyLock by boolParam(N.D_KEY_LOCK)

    /** Detected tempo/phase of the loaded track, null until known. */
    @Volatile var beatGrid: BeatGrid? = null
        set(g) {
            field = g
            N.deckSetD(index, N.DD_GRID_FIRST, g?.firstBeat ?: 0.0)
            N.deckSetD(index, N.DD_GRID_BPM, g?.bpm ?: 0.0)
            N.deckSetD(index, N.DD_GRID_FPB, g?.framesPerBeat ?: 0.0)
        }

    /** Loop start/end in source frames; negative = not set. */
    var loopIn: Double
        get() = N.deckGetD(index, N.DD_LOOP_IN)
        private set(v) = N.deckSetD(index, N.DD_LOOP_IN, v)
    var loopOut: Double
        get() = N.deckGetD(index, N.DD_LOOP_OUT)
        private set(v) = N.deckSetD(index, N.DD_LOOP_OUT, v)
    /** While true the playhead wraps from [loopOut] back to [loopIn]. */
    var loopActive: Boolean
        get() = N.deckGetF(index, N.D_LOOP_ACTIVE) != 0f
        private set(v) = N.deckSetF(index, N.D_LOOP_ACTIVE, if (v) 1f else 0f)

    /** Playback position in source frames. */
    val position: Double get() = N.deckGetD(index, N.DD_POSITION)
    /** Pre-fader peak level of the last block, 0..1, for meters. */
    val level: Float get() = N.deckGetF(index, N.D_LEVEL)
    /** True while the playhead waits at the decoded edge of a still-loading track. */
    val buffering: Boolean get() = N.deckGetF(index, N.D_BUFFERING) != 0f
    /** Producing sound from the record: playing or being scratched. */
    val active: Boolean get() = N.deckGetF(index, N.D_ACTIVE) != 0f

    fun load(newTrack: PcmTrack?) {
        // The engine takes its own reference; then this side lets go of the old track
        val handle = newTrack?.attachNative() ?: 0L
        N.deckLoad(index, handle)
        track?.detachNative()
        track = newTrack
        beatGrid = null
        clearLoop()
        rollEnd()
        cuePoint = 0.0
        hotCues = List(HOT_CUES) { null }
    }

    fun seek(frame: Double) = N.deckSeek(index, frame)

    /** Jump to a fraction of the (expected) track length; clamped to what is decoded. */
    fun seekFraction(f: Float) {
        val t = track ?: return
        seek(f.coerceIn(0f, 1f) * (t.expectedFrames - 1).toDouble())
    }

    // --- Scratching -----------------------------------------------------------------

    private var scratchTarget = 0.0
    private var lastScratchNs = 0L
    private var fingerSpeed = 0.0

    /** Finger down on the platter: the record follows the finger from now on. */
    fun beginScratch() {
        scratchTarget = position
        lastScratchNs = System.nanoTime()
        fingerSpeed = 0.0
        N.deckSetD(index, N.DD_SCRATCH_VELOCITY, 0.0)
        N.deckSetD(index, N.DD_SCRATCH_TARGET, scratchTarget)
        N.deckSetF(index, N.D_SCRATCHING, 1f)
    }

    /**
     * Move the record by [frames] source frames (negative = backwards). Updates come in
     * steps (touch events, frames); the finger's speed goes along, worked out from the time
     * since the last one, so the engine can move the record on smoothly in between instead
     * of lurching from step to step, which sounds like a squeak.
     */
    fun scratchBy(frames: Double) {
        val t = track ?: return
        val now = System.nanoTime()
        val dt = (now - lastScratchNs) / 1e9
        lastScratchNs = now
        val instant = if (dt > 0.0005) frames / dt else fingerSpeed
        // After a pause the old speed means nothing; otherwise a little smoothing against jitter
        fingerSpeed = if (dt > 0.1) instant else fingerSpeed * 0.3 + instant * 0.7
        scratchTarget = (scratchTarget + frames).coerceIn(0.0, (t.available - 1).coerceAtLeast(0).toDouble())
        N.deckSetD(index, N.DD_SCRATCH_VELOCITY, fingerSpeed)
        N.deckSetD(index, N.DD_SCRATCH_TARGET, scratchTarget)
    }

    fun endScratch() {
        N.deckSetD(index, N.DD_SCRATCH_VELOCITY, 0.0)
        N.deckSetF(index, N.D_SCRATCHING, 0f)
    }

    /** CDJ-style cue: while playing jump back and stop, while stopped set the cue here. */
    fun cue() {
        if (playing) {
            playing = false
            seek(cuePoint)
        } else {
            cuePoint = position
        }
    }

    // --- Loops -------------------------------------------------------------------

    val hasLoop: Boolean get() = loopIn >= 0 && loopOut > loopIn

    /** Snaps to the nearest beat when the tempo is known, so loops stay on the grid. */
    private fun quantize(frame: Double): Double {
        val g = beatGrid ?: return frame
        val beats = Math.round((frame - g.firstBeat) / g.framesPerBeat)
        return (g.firstBeat + beats * g.framesPerBeat).coerceAtLeast(0.0)
    }

    /** Sets the loop start here. Moving it past the end drops the end. */
    fun setLoopIn() {
        val p = quantize(position)
        loopIn = p
        if (loopOut <= p) {
            loopActive = false
            loopOut = -1.0
        }
    }

    /**
     * Sets the loop end and starts looping. With a known tempo the length is
     * rounded to 1, 2, 4 or 8 beats (nearest on a log scale: 3 → 4, 5 → 4,
     * 6 → 8), so a loop pressed by hand still lands on a musical length.
     */
    fun setLoopOut() {
        if (loopIn < 0) return
        val g = beatGrid
        loopOut = if (g != null) {
            val beats = (position - loopIn) / g.framesPerBeat
            loopIn + roundLoopBeats(beats) * g.framesPerBeat
        } else {
            maxOf(position, loopIn + (track?.sampleRate?.toDouble() ?: 44100.0) * 0.1)
        }
        loopActive = true
    }

    /** Loop on/off. Turning it on outside the loop jumps back to the start (reloop). */
    fun toggleLoop() {
        if (!hasLoop) return
        if (loopActive) {
            loopActive = false
        } else {
            if (position < loopIn || position >= loopOut) seek(loopIn)
            loopActive = true
        }
    }

    fun clearLoop() {
        loopActive = false
        loopIn = -1.0
        loopOut = -1.0
    }

    /** Loop length in beats, or null without a loop or tempo. */
    fun loopBeats(): Double? {
        val g = beatGrid ?: return null
        return if (hasLoop) (loopOut - loopIn) / g.framesPerBeat else null
    }

    // --- Quick loops, beat jump, roll ---------------------------------------------------

    /** Length of [beats] beats in source frames (half a second per beat without a tempo). */
    private fun beatFrames(beats: Double): Double =
        beats * (beatGrid?.framesPerBeat ?: ((track?.sampleRate ?: 44100) * 0.5))

    /** Start of the beat the playhead is in (or the playhead itself without a tempo). */
    private fun currentBeatStart(): Double {
        val g = beatGrid ?: return position
        val n = kotlin.math.floor((position - g.firstBeat) / g.framesPerBeat)
        return (g.firstBeat + n * g.framesPerBeat).coerceAtLeast(0.0)
    }

    /**
     * Auto-loop of [beats] beats from the beat the playhead is in. Pressing the
     * same length again while it loops turns the loop off.
     */
    fun autoLoop(beats: Double) {
        val length = beatFrames(beats)
        if (loopActive && kotlin.math.abs((loopOut - loopIn) - length) < 1.0) {
            loopActive = false
            return
        }
        val start = currentBeatStart()
        loopIn = start
        loopOut = start + length
        loopActive = true
    }

    /** Jumps [beats] beats (negative = back). An active loop moves along with it. */
    fun beatJump(beats: Int) {
        val delta = beatFrames(beats.toDouble())
        if (loopActive && hasLoop) {
            loopIn = (loopIn + delta).coerceAtLeast(0.0)
            loopOut = (loopOut + delta).coerceAtLeast(loopIn + 1.0)
        }
        seek((position + delta).coerceAtLeast(0.0))
    }

    /**
     * Loop roll: loops [beats] beats from the current beat while held; on
     * release the track continues where it would have been without the roll.
     */
    fun rollStart(beats: Double) {
        val start = currentBeatStart()
        N.deckSetD(index, N.DD_ROLL_IN, start)
        N.deckSetD(index, N.DD_ROLL_OUT, start + beatFrames(beats))
        N.deckSetF(index, N.D_ROLL_ACTIVE, 1f)
    }

    fun rollEnd() = N.deckSetF(index, N.D_ROLL_ACTIVE, 0f)

    val rolling: Boolean get() = N.deckGetF(index, N.D_ROLL_ACTIVE) != 0f

    // --- Hot cues ---------------------------------------------------------------------

    /** Up to [HOT_CUES] jump points in source frames; null = empty slot. Saved per track. */
    @Volatile var hotCues: List<Double?> = List(HOT_CUES) { null }
        private set
    /** Called when the cues change, to persist them with the track. */
    var onHotCuesChanged: ((List<Double?>) -> Unit)? = null

    fun restoreHotCues(cues: List<Double?>) {
        hotCues = List(HOT_CUES) { cues.getOrNull(it) }
    }

    /** Empty slot: store the playhead there. Set slot: jump to it (keeps playing or stays paused). */
    fun hotCue(slot: Int) {
        val cue = hotCues[slot]
        if (cue == null) {
            if (track == null) return
            hotCues = hotCues.toMutableList().also { it[slot] = position }
            onHotCuesChanged?.invoke(hotCues)
        } else {
            seek(cue)
        }
    }

    fun clearHotCue(slot: Int) {
        if (hotCues[slot] == null) return
        hotCues = hotCues.toMutableList().also { it[slot] = null }
        onHotCuesChanged?.invoke(hotCues)
    }

    /** Current tempo in BPM including the tempo fader, or null if unknown. */
    fun effectiveBpm(): Double? = beatGrid?.let { it.bpm * (1 + tempo) }

    private fun floatParam(id: Int) = object : kotlin.properties.ReadWriteProperty<Any?, Float> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = N.deckGetF(index, id)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Float) =
            N.deckSetF(index, id, value)
    }

    private fun boolParam(id: Int) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = N.deckGetF(index, id) != 0f
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) =
            N.deckSetF(index, id, if (value) 1f else 0f)
    }

    companion object {
        const val HOT_CUES = 8
        private val LOOP_LENGTHS = intArrayOf(1, 2, 4, 8)

        /** Nearest of 1, 2, 4, 8 beats, compared on a log scale. */
        fun roundLoopBeats(beats: Double): Int {
            if (beats <= 1.0) return 1
            val exp = Math.round(kotlin.math.log2(beats)).toInt().coerceIn(0, LOOP_LENGTHS.size - 1)
            return LOOP_LENGTHS[exp]
        }
    }
}
