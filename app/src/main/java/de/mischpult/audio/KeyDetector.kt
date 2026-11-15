package de.mischpult.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Musical key of a track, as 0..11 = C..B major and 12..23 = C..B minor.
 *
 * Method: a chromagram (energy per pitch class) from FFT frames of the
 * downsampled mono signal, correlated with Temperley's major/minor key
 * profiles rotated to all 24 keys; the best correlation wins.
 */
object KeyDetector {
    private const val DOWNSAMPLE = 4
    private const val FFT_SIZE = 8192
    private const val HOP = FFT_SIZE / 2
    private const val MAX_FRAMES = 600

    // Temperley (2005) key profiles, tonic first
    private val MAJOR = doubleArrayOf(5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0)
    private val MINOR = doubleArrayOf(5.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 4.5, 3.5, 2.0, 1.5, 4.0)

    fun detect(track: PcmTrack): Int? {
        val frames = track.available
        val rate = track.sampleRate.toDouble() / DOWNSAMPLE
        val monoLength = frames / DOWNSAMPLE
        if (monoLength < FFT_SIZE * 4) return null

        // Only the analysed windows are read, straight from the track: at most
        // MAX_FRAMES of them, so memory stays small even for an hour-long mix.
        // Mono, averaged in groups of DOWNSAMPLE (a crude but sufficient anti-alias).
        val chunk = ShortArray(DOWNSAMPLE * 2 * FFT_SIZE)
        fun readMono(start: Int, out: FloatArray) {
            track.read(start * DOWNSAMPLE, chunk, FFT_SIZE * DOWNSAMPLE)
            for (i in 0 until FFT_SIZE) {
                var sum = 0f
                for (j in 0 until DOWNSAMPLE * 2) sum += chunk[i * DOWNSAMPLE * 2 + j]
                out[i] = sum / (DOWNSAMPLE * 2 * 32768f)
            }
        }

        // Which pitch class each FFT bin belongs to (C2 .. C7), and its weight
        val binPitch = IntArray(FFT_SIZE / 2) { -1 }
        for (k in 1 until FFT_SIZE / 2) {
            val f = k * rate / FFT_SIZE
            if (f < 65.0 || f > 2100.0) continue
            val midi = 69 + 12 * log2(f / 440.0)
            binPitch[k] = ((midi.roundToInt() % 12) + 12) % 12
        }
        val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }

        val chroma = DoubleArray(12)
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val totalFrames = (monoLength - FFT_SIZE) / HOP
        val stride = maxOf(1, totalFrames / MAX_FRAMES)
        var frame = 0
        while (frame < totalFrames) {
            readMono(frame * HOP, re)
            for (i in 0 until FFT_SIZE) {
                re[i] *= window[i]
                im[i] = 0f
            }
            fft(re, im)
            val frameChroma = DoubleArray(12)
            for (k in 1 until FFT_SIZE / 2) {
                val pc = binPitch[k]
                if (pc < 0) continue
                frameChroma[pc] += sqrt((re[k] * re[k] + im[k] * im[k]).toDouble())
            }
            // Log-compress per frame so loud passages don't dominate the whole track
            for (pc in 0 until 12) chroma[pc] += ln(1.0 + frameChroma[pc])
            frame += stride
        }

        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (key in 0 until 24) {
            val profile = if (key < 12) MAJOR else MINOR
            val tonic = key % 12
            val score = correlation(chroma) { pc -> profile[(pc - tonic + 12) % 12] }
            if (score > bestScore) { bestScore = score; best = key }
        }
        return best.takeIf { it >= 0 }
    }

    private inline fun correlation(a: DoubleArray, b: (Int) -> Double): Double {
        val meanA = a.average()
        var meanB = 0.0
        for (i in 0 until 12) meanB += b(i)
        meanB /= 12
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until 12) {
            val x = a[i] - meanA
            val y = b(i) - meanB
            num += x * y; da += x * x; db += y * y
        }
        return if (da == 0.0 || db == 0.0) 0.0 else num / sqrt(da * db)
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** Camelot wheel notation and harmonic-mixing rules for detected keys. */
object Camelot {
    private val NAMES = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")

    /** Wheel number 1..12 and A (minor) / B (major). */
    fun of(key: Int): Pair<Int, Char> {
        val minor = key >= 12
        // Minor keys sit next to their relative major (3 semitones up)
        val tonic = if (minor) (key % 12 + 3) % 12 else key % 12
        val number = ((tonic * 7) % 12 + 7) % 12 + 1
        return number to if (minor) 'A' else 'B'
    }

    fun label(key: Int): String = of(key).let { (n, l) -> "$n$l" }

    fun musical(key: Int): String = NAMES[key % 12] + if (key >= 12) "m" else ""

    /** The key after shifting the pitch by [semitones] (tempo change without key lock). */
    fun transpose(key: Int, semitones: Int): Int {
        val pc = ((key % 12 + semitones) % 12 + 12) % 12
        return if (key >= 12) 12 + pc else pc
    }

    /** Same key, the relative major/minor, or one step around the wheel: mixes cleanly. */
    fun compatible(a: Int, b: Int): Boolean {
        val (na, la) = of(a)
        val (nb, lb) = of(b)
        if (na == nb) return true
        val d = (na - nb + 12) % 12
        return la == lb && (d == 1 || d == 11)
    }

    /** Pitch shift of a tempo change, in whole semitones. */
    fun semitonesFor(tempo: Float): Int = (12 * log2(1.0 + tempo)).roundToInt()
}
