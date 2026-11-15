package de.mischpult.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sin

/** A constant-tempo beat grid in source frames. */
data class BeatGrid(val bpm: Double, val firstBeat: Double, val framesPerBeat: Double) {
    /** Position within the current beat, 0..1. */
    fun phase(frame: Double): Double {
        val p = (frame - firstBeat) / framesPerBeat
        return p - kotlin.math.floor(p)
    }
}

/**
 * Tempo and beat-phase estimation for (mostly) constant-tempo dance music.
 *
 * 1. Onset novelty: rise of log energy per waveform bin (~1.5 ms), bass-weighted
 *    so kicks dominate.
 * 2. Coarse tempo: autocorrelation of the novelty over 78–178 BPM, mildly
 *    weighted towards 125 BPM to settle half/double-time ambiguity.
 * 3. Fine tempo and phase: fold the whole track at candidate periods around the
 *    coarse estimate; the period whose beats line up sharpest wins, and the
 *    phase of that alignment is the beatgrid's first beat.
 */
object BeatDetector {
    private const val MIN_BPM = 78.0
    private const val MAX_BPM = 178.0

    fun detect(energy: FloatArray, binFrames: Int, sampleRate: Int): BeatGrid? {
        val binsPerSec = sampleRate.toDouble() / binFrames
        val n = energy.size
        if (n < binsPerSec * 15) return null // need some material

        // 1. novelty
        val nov = FloatArray(n)
        var prev = ln(1f + 1000f * energy[0])
        var mean = 0.0
        for (i in 1 until n) {
            val cur = ln(1f + 1000f * energy[i])
            val d = cur - prev
            nov[i] = if (d > 0) d else 0f
            prev = cur
            mean += nov[i]
        }
        mean /= n
        for (i in 0 until n) nov[i] -= mean.toFloat()

        // 2. coarse: autocorrelation over a ≤90 s window from the middle of the track
        val window = minOf(n, (binsPerSec * 90).toInt())
        val start = (n - window) / 2
        val minLag = (60.0 / MAX_BPM * binsPerSec).toInt()
        val maxLag = (60.0 / MIN_BPM * binsPerSec).toInt() + 1
        if (window <= maxLag * 4) return null
        val scores = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) {
            var acc = 0.0
            val end = start + window - lag
            var i = start
            while (i < end) {
                acc += nov[i] * nov[i + lag]
                i++
            }
            scores[lag] = acc
        }
        var bestLag = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            val bpm = 60.0 * binsPerSec / lag
            val w = exp(-0.5 * (log2(bpm / 125.0) / 1.0).let { it * it })
            val s = scores[lag] * w
            if (s > bestScore) { bestScore = s; bestLag = lag }
        }
        if (bestLag <= 0 || bestScore <= 0) return null
        // parabolic refinement of the peak
        val y0 = scores[bestLag - 1]; val y1 = scores[bestLag]; val y2 = scores[bestLag + 1]
        val denom = y0 - 2 * y1 + y2
        val lag = bestLag + if (denom != 0.0) (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5) else 0.0
        val coarseBpm = 60.0 * binsPerSec / lag

        // 3. fine tempo + phase by comb folding over the whole track
        var bestBpm = coarseBpm
        var bestSharp = Double.NEGATIVE_INFINITY
        var bestPhase = 0.0
        var bpm = coarseBpm - 0.8
        while (bpm <= coarseBpm + 0.8) {
            val (sharp, phase) = fold(nov, 60.0 / bpm * binsPerSec)
            if (sharp > bestSharp) { bestSharp = sharp; bestBpm = bpm; bestPhase = phase }
            bpm += 0.01
        }
        // Most produced dance music sits on whole BPMs; snap when that costs (almost) nothing
        val rounded = Math.round(bestBpm).toDouble()
        if (kotlin.math.abs(rounded - bestBpm) < 0.06) {
            val (sharp, phase) = fold(nov, 60.0 / rounded * binsPerSec)
            if (sharp >= bestSharp * 0.98) { bestBpm = rounded; bestPhase = phase }
        }

        val framesPerBeat = 60.0 / bestBpm * sampleRate
        // phase is in bins within one period; the novelty peak sits on the beat
        val first = (bestPhase * binFrames) % framesPerBeat
        return BeatGrid(bestBpm, first, framesPerBeat)
    }

    /**
     * Folds the novelty at [period] bins and measures how strongly it concentrates
     * at one phase (resultant length of the circular mean). Returns that strength
     * and the phase in bins.
     */
    private fun fold(nov: FloatArray, period: Double): Pair<Double, Double> {
        var c = 0.0
        var s = 0.0
        val k = 2 * PI / period
        // Rotate incrementally instead of calling cos/sin per sample
        val cs = cos(k); val sn = sin(k)
        var re = 1.0; var im = 0.0
        for (i in nov.indices) {
            val v = nov[i]
            if (v > 0) { c += v * re; s += v * im }
            val r = re * cs - im * sn
            im = re * sn + im * cs
            re = r
            if (i and 4095 == 0) { // renormalise against drift
                val m = kotlin.math.sqrt(re * re + im * im)
                re /= m; im /= m
            }
        }
        var angle = kotlin.math.atan2(s, c)
        if (angle < 0) angle += 2 * PI
        return Pair(kotlin.math.sqrt(c * c + s * s), angle / k)
    }
}
