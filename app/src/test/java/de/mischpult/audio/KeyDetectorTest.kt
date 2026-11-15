package de.mischpult.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class KeyDetectorTest {

    /** Writes [chords] (MIDI notes, 2 s each, repeated) as a finished PCM cache file. */
    private fun track(vararg chords: IntArray): PcmTrack {
        val rate = 44100
        val seconds = 40
        val frames = rate * seconds
        val buf = ByteBuffer.allocate(16 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(PcmTrack.MAGIC).putInt(rate).putLong(frames.toLong())
        for (i in 0 until frames) {
            val chord = chords[(i / (2 * rate)) % chords.size]
            var v = 0.0
            for (note in chord) {
                val f = 440.0 * 2.0.pow((note - 69) / 12.0)
                // A few harmonics, like a real instrument
                for (h in 1..4) v += sin(2 * PI * f * h * i / rate) / (h * h)
            }
            val s = (v / chord.size * 8000).toInt().coerceIn(-32768, 32767).toShort()
            buf.putShort(s).putShort(s)
        }
        val file = File.createTempFile("keytest", ".pcm").apply { deleteOnExit() }
        file.writeBytes(buf.array())
        return PcmTrack.open(file)!!
    }

    @Test fun cMajorProgression() {
        // C - F - G - C, with the bass note an octave down
        val key = KeyDetector.detect(track(intArrayOf(48, 60, 64, 67), intArrayOf(41, 65, 69, 72),
            intArrayOf(43, 67, 71, 74), intArrayOf(48, 60, 64, 67)))
        assertEquals("C major", 0, key)
        assertEquals("8B", Camelot.label(key!!))
    }

    @Test fun aMinorProgression() {
        // Am - Dm - E - Am
        val key = KeyDetector.detect(track(intArrayOf(45, 57, 60, 64), intArrayOf(50, 62, 65, 69),
            intArrayOf(40, 64, 68, 71), intArrayOf(45, 57, 60, 64)))
        assertEquals("A minor", 21, key)
        assertEquals("8A", Camelot.label(key!!))
    }

    @Test fun camelotWheel() {
        assertEquals("9B", Camelot.label(7))        // G major
        assertEquals("7B", Camelot.label(5))        // F major
        assertEquals("1B", Camelot.label(11))       // B major
        assertEquals("9A", Camelot.label(12 + 4))   // E minor
        assertEquals("5A", Camelot.label(12 + 0))   // C minor
        assertTrue(Camelot.compatible(0, 21))       // C major / A minor: relative keys
        assertTrue(Camelot.compatible(0, 7))        // 8B / 9B
        assertTrue(Camelot.compatible(11, 0 + 6))   // 1B / 2B (F# major)
        assertFalse(Camelot.compatible(0, 2))       // 8B / 10B
        assertEquals(2, Camelot.transpose(0, 2))    // C + 2 semitones = D
        assertEquals(1, Camelot.semitonesFor(0.06f))
        assertEquals(0, Camelot.semitonesFor(0.02f))
    }
}
