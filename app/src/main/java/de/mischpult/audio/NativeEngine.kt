package de.mischpult.audio

/**
 * JNI bridge to the C++ audio engine (Oboe stream, decks, DSP, sampler, mixer;
 * see src/main/cpp). Parameter ids must match the enums in jni.cpp.
 */
object NativeEngine {
    init {
        System.loadLibrary("mischpult_engine")
    }

    external fun start()
    external fun sampleRate(): Int

    // Tracks: a native view on a decoded-PCM file, owned by Kotlin's PcmTrack
    external fun trackCreate(fd: Int, sampleRate: Int, capacityFrames: Long, available: Int, complete: Boolean): Long
    external fun trackPublish(handle: Long, available: Int, complete: Boolean)
    external fun trackRemap(handle: Long, capacityFrames: Long): Boolean
    external fun trackRelease(handle: Long)

    external fun deckLoad(deck: Int, handle: Long)
    external fun deckSetF(deck: Int, param: Int, value: Float)
    external fun deckGetF(deck: Int, param: Int): Float
    external fun deckSetD(deck: Int, param: Int, value: Double)
    external fun deckGetD(deck: Int, param: Int): Double
    external fun deckSeek(deck: Int, frame: Double)
    external fun engageSync(deck: Int)

    external fun mixSetF(param: Int, value: Float)
    external fun mixGetF(param: Int): Float

    external fun padLoad(pad: Int, handle: Long)
    external fun padPress(pad: Int)
    external fun padRelease(pad: Int)
    external fun padStop(pad: Int)
    external fun padSetF(pad: Int, param: Int, value: Float)
    external fun padGetF(pad: Int, param: Int): Float
    external fun samplerSetF(param: Int, value: Float)
    external fun samplerGetF(param: Int): Float
    external fun samplerStopAll()

    // Recording: the engine copies the stereo master into a ring buffer
    external fun recordStart()
    external fun recordStop()
    /** Fills [dst] (interleaved stereo) with recorded frames; returns how many. */
    external fun recordRead(dst: FloatArray): Int
    external fun recordDropped(): Long

    // Deck float params
    const val D_PLAYING = 0
    const val D_TEMPO = 1
    const val D_VOLUME = 2
    const val D_TRIM = 3
    const val D_EQ_LOW = 4
    const val D_EQ_MID = 5
    const val D_EQ_HIGH = 6
    const val D_FILTER = 7
    const val D_FILTER_RES = 8
    const val D_FX_TYPE = 9
    const val D_FX_ON = 10
    const val D_FX_AMOUNT = 11
    const val D_ECHO_BEATS = 12
    const val D_ECHO_FB = 13
    const val D_ECHO_TONE = 14
    const val D_REV_SIZE = 15
    const val D_REV_DAMP = 16
    const val D_FL_BEATS = 17
    const val D_FL_DEPTH = 18
    const val D_FL_FB = 19
    const val D_PFL = 20
    const val D_SYNC = 21
    const val D_LOOP_ACTIVE = 22
    const val D_LEVEL = 23
    const val D_BUFFERING = 24
    const val D_ACTIVE = 25
    const val D_SCRATCHING = 26
    const val D_ROLL_ACTIVE = 27
    const val D_KEY_LOCK = 28

    // Deck double params
    const val DD_POSITION = 0
    const val DD_LOOP_IN = 1
    const val DD_LOOP_OUT = 2
    const val DD_GRID_BPM = 3
    const val DD_GRID_FIRST = 4
    const val DD_GRID_FPB = 5
    const val DD_SCRATCH_TARGET = 6
    const val DD_ROLL_IN = 7
    const val DD_ROLL_OUT = 8
    const val DD_SCRATCH_VELOCITY = 9

    // Mixer
    const val M_XFADE = 0
    const val M_MASTER = 1
    const val M_PHONES = 2
    const val M_CUE_MIX = 3
    const val M_OUTPUT_MODE = 4
    const val M_XFADE_CUT = 5

    // Pads / sampler
    const val P_MODE = 0
    const val P_VOLUME = 1
    const val P_PLAYING = 2
    const val P_PROGRESS = 3
    const val P_BPM = 4
    const val P_FOLLOW = 5
    const val P_KEY_LOCK = 6
    const val S_VOLUME = 0
    const val S_PFL = 1
    const val S_ACTIVE = 2
    const val S_QUANTIZE = 3
}
