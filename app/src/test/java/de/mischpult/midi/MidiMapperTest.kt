package de.mischpult.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class MidiMapperTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun parsesNotesCcAndRunningStatus() {
        val m = MidiMapper()
        // Note on ch1 #60 vel 100, then CC ch2 #7 = 64 twice using running status
        val events = m.parse(bytes(0x90, 60, 100, 0xB1, 7, 64, 7, 65), 0, 8)
        assertEquals(3, events.size)
        assertEquals(MidiEvent(MidiBinding(true, 0, 60), 100, true), events[0])
        assertEquals(MidiBinding(false, 1, 7), events[1].binding)
        assertEquals(65, events[2].value)
        // Note on with velocity 0 is a release; timing clock bytes are ignored
        val off = m.parse(bytes(0xF8, 0x90, 60, 0), 0, 4).single()
        assertEquals(false, off.pressed)
    }

    @Test fun learnsThenTranslates() {
        val m = MidiMapper()
        var saved: Map<MidiAction, MidiBinding>? = null
        m.onChanged = { saved = it }
        m.learning = MidiAction.A_PLAY
        assertNull(m.handle(MidiEvent(MidiBinding(true, 0, 11), 127, true)))
        assertEquals(MidiBinding(true, 0, 11), saved!![MidiAction.A_PLAY])
        assertEquals(MidiCommand.Press(MidiAction.A_PLAY, true), m.handle(MidiEvent(MidiBinding(true, 0, 11), 127, true)))
        assertEquals(MidiCommand.Press(MidiAction.A_PLAY, false), m.handle(MidiEvent(MidiBinding(true, 0, 11), 0, false)))
        // Unbound controls do nothing
        assertNull(m.handle(MidiEvent(MidiBinding(true, 0, 12), 127, true)))
    }

    @Test fun learningIgnoresReleaseAndMovesBinding() {
        val m = MidiMapper(mapOf(MidiAction.A_CUE to MidiBinding(true, 0, 5)))
        m.learning = MidiAction.B_CUE
        // The release of an earlier press must not be learned
        assertNull(m.handle(MidiEvent(MidiBinding(true, 0, 9), 0, false)))
        assertEquals(MidiAction.B_CUE, m.learning)
        // Learning a control that was bound elsewhere moves it
        m.handle(MidiEvent(MidiBinding(true, 0, 5), 127, true))
        assertNull(m.bindings[MidiAction.A_CUE])
        assertEquals(MidiBinding(true, 0, 5), m.bindings[MidiAction.B_CUE])
    }

    @Test fun absoluteAndRelativeControls() {
        val m = MidiMapper(mapOf(
            MidiAction.CROSSFADER to MidiBinding(false, 0, 10),
            MidiAction.A_JOG to MidiBinding(false, 0, 20),
        ))
        assertEquals(MidiCommand.Set(MidiAction.CROSSFADER, 1f), m.handle(MidiEvent(MidiBinding(false, 0, 10), 127, true)))
        assertEquals(MidiCommand.Turn(MidiAction.A_JOG, 3), m.handle(MidiEvent(MidiBinding(false, 0, 20), 3, true)))
        assertEquals(MidiCommand.Turn(MidiAction.A_JOG, -2), m.handle(MidiEvent(MidiBinding(false, 0, 20), 126, true)))
        assertEquals(MidiCommand.Turn(MidiAction.A_JOG, 1), m.handle(MidiEvent(MidiBinding(false, 0, 20), 65, true)))
        assertEquals(MidiCommand.Turn(MidiAction.A_JOG, -1), m.handle(MidiEvent(MidiBinding(false, 0, 20), 63, true)))
        assertNull(m.handle(MidiEvent(MidiBinding(false, 0, 20), 64, false)))
    }

    @Test fun performancePadsFollowPadMode() {
        // Hot cue mode: each deck's pads are its own hot cues
        assertEquals(PadTarget.HotCue(0, 3), PadTarget.of(true, bank = 2, deck = 0, slot = 3))
        assertEquals(PadTarget.HotCue(1, 7), PadTarget.of(true, bank = 0, deck = 1, slot = 7))
        // Sample mode: the bank is split, deck A plays pads 1-8, deck B pads 9-16
        assertEquals(PadTarget.Sample(0), PadTarget.of(false, bank = 0, deck = 0, slot = 0))
        assertEquals(PadTarget.Sample(15), PadTarget.of(false, bank = 0, deck = 1, slot = 7))
        assertEquals(PadTarget.Sample(3 * 16 + 8 + 2), PadTarget.of(false, bank = 3, deck = 1, slot = 2))
    }

    @Test fun classifiesPadActions() {
        assertTrue(MidiAction.A_HOT1.isHotCue)
        assertTrue(MidiAction.B_HOT8.isHotCue)
        assertFalse(MidiAction.A_PERF1.isHotCue)
        assertTrue(MidiAction.B_PERF4.isPerformancePad)
        assertFalse(MidiAction.PAD_MODE.isPerformancePad)
        assertFalse(MidiAction.PAD1.isHotCue)
        assertEquals(0, MidiAction.A_PERF1.slot)
        assertEquals(7, MidiAction.B_PERF8.slot)
    }
}
