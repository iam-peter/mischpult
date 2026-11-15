package de.mischpult.audio

import de.mischpult.audio.NativeEngine as N

/**
 * How the two signals (master, headphones) reach the one stereo output.
 *
 * Phones have a single stereo output, so pre-listening uses the classic mobile
 * DJ trick: a splitter cable, with the master in mono on the left channel (to
 * the speakers) and the headphone mix in mono on the right.
 */
enum class OutputMode { STEREO, SPLIT }

/**
 * The mixer as seen from Kotlin. Audio runs in the native engine
 * (src/main/cpp): an Oboe low-latency stream rendering two decks and the
 * sampler through the crossfader into master and headphone cue. It opens the
 * stream when something should sound, closes it after a few seconds of
 * silence, and reopens it when the output device changes.
 */
class MixerEngine {

    init {
        N.start()
    }

    /** Output sample rate of the device the engine plays on. */
    val sampleRate: Int get() = N.sampleRate()

    val decks = arrayOf(Deck(0), Deck(1))
    val sampler = Sampler()

    /** -1 = only deck A, +1 = only deck B. */
    var crossfader: Float
        get() = N.mixGetF(N.M_XFADE)
        set(v) = N.mixSetF(N.M_XFADE, v)
    /** Sharp cut curve for scratching instead of the smooth mixing curve. */
    var crossfaderCut: Boolean
        get() = N.mixGetF(N.M_XFADE_CUT) > 0.5f
        set(v) = N.mixSetF(N.M_XFADE_CUT, if (v) 1f else 0f)
    var masterVolume: Float
        get() = N.mixGetF(N.M_MASTER)
        set(v) = N.mixSetF(N.M_MASTER, v)
    var outputMode: OutputMode
        get() = if (N.mixGetF(N.M_OUTPUT_MODE) != 0f) OutputMode.SPLIT else OutputMode.STEREO
        set(v) = N.mixSetF(N.M_OUTPUT_MODE, v.ordinal.toFloat())
    var headphoneVolume: Float
        get() = N.mixGetF(N.M_PHONES)
        set(v) = N.mixSetF(N.M_PHONES, v)
    /** Headphone mix: 0 = cue channels only, 1 = master only. */
    var cueMix: Float
        get() = N.mixGetF(N.M_CUE_MIX)
        set(v) = N.mixSetF(N.M_CUE_MIX, v)

    /** Turns on sync for [deck] (the other deck becomes the tempo master) and lines up the beats. */
    fun engageSync(deck: Int) = N.engageSync(deck)
}
