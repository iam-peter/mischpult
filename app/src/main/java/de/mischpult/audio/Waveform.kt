package de.mischpult.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Waveform summary of a [PcmTrack]. One bin covers [binFrames] frames and
 * stores the peak amplitude plus how much of the energy is bass, which the
 * views turn into the usual DJ-style colouring (bass warm, highs cold).
 *
 * Filled incrementally by [WaveformAnalyzer] while the track decodes; readers
 * may only look at bins below [ready].
 */
class Waveform(expectedFrames: Int) {
    val binFrames = BIN_FRAMES

    @Volatile private var peaks = ByteArray(binsFor(expectedFrames))
    @Volatile private var bass = ByteArray(binsFor(expectedFrames))

    /** Number of analysed bins, safe to read. */
    @Volatile var ready = 0
        private set

    fun peak(bin: Int): Float = (peaks[bin].toInt() and 0xFF) / 255f
    fun bass(bin: Int): Float = (bass[bin].toInt() and 0xFF) / 255f

    internal fun put(bin: Int, peak: Float, bassRatio: Float) {
        if (bin >= peaks.size) {
            val size = maxOf(bin + 1, peaks.size + peaks.size / 2)
            // Copy first, swap after: readers below [ready] always see valid data
            val p = peaks.copyOf(size)
            val b = bass.copyOf(size)
            peaks = p
            bass = b
        }
        peaks[bin] = (peak.coerceAtMost(1f) * 255f).toInt().toByte()
        bass[bin] = (bassRatio.coerceIn(0f, 1f) * 255f).toInt().toByte()
    }

    internal fun publish(bins: Int) {
        ready = bins
    }

    companion object {
        const val BIN_FRAMES = 64
        fun binsFor(frames: Int) = (frames.coerceAtLeast(0) + BIN_FRAMES - 1) / BIN_FRAMES
    }
}

/** Feeds a [Waveform] from a (possibly still growing) [PcmTrack]. Single-threaded. */
class WaveformAnalyzer(private val track: PcmTrack, val waveform: Waveform) {
    val sampleRate get() = track.sampleRate
    /** Frames analysed so far. */
    val analyzedFrames get() = nextFrame

    // Per-bin energy (bass-weighted) for beat detection
    private var onsets = FloatArray(waveform.ready.coerceAtLeast(Waveform.binsFor(track.expectedFrames)))

    /** Copy of the onset energies analysed so far. Call from the analysing thread. */
    fun snapshotOnsets(): FloatArray = onsets.copyOf(bin)

    // One-pole low-pass at ~150 Hz splits bass from the rest
    private val a = 1.0f - exp(-2.0 * Math.PI * 150.0 / track.sampleRate).toFloat()
    private var lp = 0f
    private var nextFrame = 0
    private var bin = 0
    private val buf = ShortArray(CHUNK_FRAMES * 2)

    /**
     * Analyses whole bins up to [upToFrame]. The final partial bin is only
     * analysed once [last] is true, so a growing track never yields a short bin.
     */
    fun advance(upToFrame: Int, last: Boolean = false) {
        val bf = waveform.binFrames
        val end = if (last) upToFrame else upToFrame - upToFrame % bf
        while (nextFrame < end) {
            val n = minOf(CHUNK_FRAMES, end - nextFrame)
            track.read(nextFrame, buf, n)
            var i = 0
            while (i < n) {
                val stop = minOf(i + bf, n)
                var peak = 0f
                var lowE = 0f
                var allE = 0f
                for (j in i until stop) {
                    val m = (buf[j * 2] + buf[j * 2 + 1]) * (0.5f / 32768f)
                    lp += a * (m - lp)
                    peak = max(peak, abs(m))
                    lowE += lp * lp
                    allE += m * m
                }
                if (bin >= onsets.size) onsets = onsets.copyOf(maxOf(bin + 1, onsets.size + onsets.size / 2))
                onsets[bin] = (lowE + 0.2f * allE) / (stop - i)
                waveform.put(bin++, peak, if (allE > 1e-9f) lowE / allE else 0f)
                i = stop
            }
            nextFrame += n
            waveform.publish(bin)
        }
    }

    private companion object {
        // Multiple of BIN_FRAMES so chunks never split a bin
        const val CHUNK_FRAMES = Waveform.BIN_FRAMES * 1024
    }
}
