package de.mischpult.audio

import de.mischpult.audio.NativeEngine as N

enum class PadMode(val label: String) {
    /** Tap plays the whole sample; tapping again restarts it. */
    ONE_SHOT("ONE-SHOT"),
    /** Plays while the pad is held down. */
    HOLD("HOLD"),
    /** Tap starts looping, tap again stops. */
    LOOP("LOOP"),
}

/**
 * One sampler pad, as a control surface over the native engine's pad voice.
 * The UI only counts presses and releases; the audio thread turns them into
 * starts and stops, so a tap never races with rendering.
 */
class Pad(val index: Int) {
    /** The pad's sample (Kotlin side); the engine plays its own view of it. */
    @Volatile var sample: PcmTrack? = null
        set(t) {
            val handle = t?.attachNative() ?: 0L
            N.padLoad(index, handle)
            field?.detachNative()
            field = t
        }
    var mode: PadMode
        get() = PadMode.entries[N.padGetF(index, N.P_MODE).toInt().coerceIn(0, PadMode.entries.size - 1)]
        set(v) = N.padSetF(index, N.P_MODE, v.ordinal.toFloat())
    var volume: Float
        get() = N.padGetF(index, N.P_VOLUME)
        set(v) = N.padSetF(index, N.P_VOLUME, v)
    /** The sample's own tempo; 0 = unknown. */
    var bpm: Float
        get() = N.padGetF(index, N.P_BPM)
        set(v) = N.padSetF(index, N.P_BPM, v)
    /** Play at the leading deck's tempo instead of the sample's own. */
    var followTempo: Boolean
        get() = N.padGetF(index, N.P_FOLLOW) != 0f
        set(v) = N.padSetF(index, N.P_FOLLOW, if (v) 1f else 0f)
    /** While following the tempo, keep the pitch (time-stretch). */
    var keyLock: Boolean
        get() = N.padGetF(index, N.P_KEY_LOCK) != 0f
        set(v) = N.padSetF(index, N.P_KEY_LOCK, if (v) 1f else 0f)

    /** Whether the voice is sounding, and how far through the sample (0..1). */
    val playing: Boolean get() = N.padGetF(index, N.P_PLAYING) != 0f
    val progress: Float get() = N.padGetF(index, N.P_PROGRESS)

    fun press() = N.padPress(index)
    fun release() = N.padRelease(index)
    fun stop() = N.padStop(index)
}

/** 4 banks of 16 pads mixed into one stereo bus with its own volume. */
class Sampler {
    val pads = Array(PAD_COUNT) { Pad(it) }
    var volume: Float
        get() = N.samplerGetF(N.S_VOLUME)
        set(v) = N.samplerSetF(N.S_VOLUME, v)
    /** Send the sampler to the headphone cue (split output). */
    var pfl: Boolean
        get() = N.samplerGetF(N.S_PFL) != 0f
        set(v) = N.samplerSetF(N.S_PFL, if (v) 1f else 0f)

    val active: Boolean get() = N.samplerGetF(N.S_ACTIVE) != 0f

    /**
     * Quantised starts: a pad waits for the next grid line of the leading deck
     * (playing, with a tempo, louder in the mix). In beats; 0 = off.
     */
    var quantizeBeats: Float
        get() = N.samplerGetF(N.S_QUANTIZE)
        set(v) = N.samplerSetF(N.S_QUANTIZE, v)

    fun stopAll() = N.samplerStopAll()

    companion object {
        const val BANK_SIZE = 16
        const val BANKS = 4
        const val PAD_COUNT = BANK_SIZE * BANKS
    }
}
