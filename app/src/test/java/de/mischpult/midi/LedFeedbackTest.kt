package de.mischpult.midi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LedFeedbackTest {
    private val play = MidiBinding(true, 0, 11)
    private val sync = MidiBinding(false, 2, 40)
    private val fader = MidiBinding(false, 0, 7)
    private val bindings = mapOf(MidiAction.A_PLAY to play, MidiAction.A_SYNC to sync, MidiAction.A_VOLUME to fader)

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun sendsStateOnceAndOnlyChanges() {
        val leds = LedFeedback()
        val state = mutableMapOf(MidiAction.A_PLAY to true, MidiAction.A_SYNC to false)
        val first = leds.update(bindings) { state[it] }
        // Faders have no LED; buttons get note-on / CC on their own channel and number
        assertEquals(2, first.size)
        assertArrayEquals(bytes(0x90, 11, 127), first[0])
        assertArrayEquals(bytes(0xB2, 40, 0), first[1])
        assertTrue(leds.update(bindings) { state[it] }.isEmpty())
        state[MidiAction.A_PLAY] = false
        val change = leds.update(bindings) { state[it] }
        assertEquals(1, change.size)
        assertArrayEquals(bytes(0x90, 11, 0), change[0])
    }

    @Test fun resetResendsAndUnmappedGoesDark() {
        val leds = LedFeedback()
        leds.update(bindings) { it == MidiAction.A_PLAY }
        leds.reset()
        assertEquals(2, leds.update(bindings) { it == MidiAction.A_PLAY }.size)
        // Play's mapping removed while lit: switch its LED off once
        val off = leds.update(bindings - MidiAction.A_PLAY) { it == MidiAction.A_PLAY }
        assertEquals(1, off.size)
        assertArrayEquals(bytes(0x90, 11, 0), off[0])
    }

    @Test fun allOffDarkensOnlyLitButtons() {
        val leds = LedFeedback(onValue = 64)
        val on = leds.update(bindings) { it == MidiAction.A_SYNC }
        assertArrayEquals(bytes(0xB2, 40, 64), on.first { it[1] == 40.toByte() })
        val off = leds.allOff()
        assertEquals(1, off.size)
        assertArrayEquals(bytes(0xB2, 40, 0), off[0])
    }
}
