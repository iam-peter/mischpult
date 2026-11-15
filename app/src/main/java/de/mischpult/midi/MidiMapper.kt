package de.mischpult.midi

import kotlinx.serialization.Serializable

/** How an action responds to a control. */
enum class ControlKind { BUTTON, ABSOLUTE, RELATIVE }

/**
 * Everything a MIDI controller can drive. Deck actions exist for A and B;
 * [deck] is null for mixer/sampler actions.
 */
enum class MidiAction(val label: String, val kind: ControlKind, val deck: Int?, val slot: Int = 0) {
    A_PLAY("Play", ControlKind.BUTTON, 0), A_CUE("Cue", ControlKind.BUTTON, 0),
    A_SYNC("Sync", ControlKind.BUTTON, 0), A_KEYLOCK("Key lock", ControlKind.BUTTON, 0),
    A_TEMPO("Tempo", ControlKind.ABSOLUTE, 0), A_VOLUME("Channel fader", ControlKind.ABSOLUTE, 0),
    A_TRIM("Gain", ControlKind.ABSOLUTE, 0), A_EQ_HI("EQ high", ControlKind.ABSOLUTE, 0),
    A_EQ_MID("EQ mid", ControlKind.ABSOLUTE, 0), A_EQ_LOW("EQ low", ControlKind.ABSOLUTE, 0),
    A_FILTER("Filter", ControlKind.ABSOLUTE, 0), A_FX_ON("FX on", ControlKind.BUTTON, 0),
    A_FX_AMOUNT("FX amount", ControlKind.ABSOLUTE, 0), A_PFL("Headphone cue", ControlKind.BUTTON, 0),
    A_JOG("Jog wheel", ControlKind.RELATIVE, 0), A_JOG_TOUCH("Jog touch (scratch)", ControlKind.BUTTON, 0),
    A_LOOP_IN("Loop in", ControlKind.BUTTON, 0), A_LOOP_OUT("Loop out", ControlKind.BUTTON, 0),
    A_LOOP("Loop on/off", ControlKind.BUTTON, 0), A_AUTOLOOP("Auto-loop 4 beats", ControlKind.BUTTON, 0),
    A_JUMP_BACK("Beat jump back", ControlKind.BUTTON, 0), A_JUMP_FWD("Beat jump forward", ControlKind.BUTTON, 0),
    A_HOT1("Hot cue 1", ControlKind.BUTTON, 0, 0), A_HOT2("Hot cue 2", ControlKind.BUTTON, 0, 1),
    A_HOT3("Hot cue 3", ControlKind.BUTTON, 0, 2), A_HOT4("Hot cue 4", ControlKind.BUTTON, 0, 3),
    A_HOT5("Hot cue 5", ControlKind.BUTTON, 0, 4), A_HOT6("Hot cue 6", ControlKind.BUTTON, 0, 5),
    A_HOT7("Hot cue 7", ControlKind.BUTTON, 0, 6), A_HOT8("Hot cue 8", ControlKind.BUTTON, 0, 7),

    B_PLAY("Play", ControlKind.BUTTON, 1), B_CUE("Cue", ControlKind.BUTTON, 1),
    B_SYNC("Sync", ControlKind.BUTTON, 1), B_KEYLOCK("Key lock", ControlKind.BUTTON, 1),
    B_TEMPO("Tempo", ControlKind.ABSOLUTE, 1), B_VOLUME("Channel fader", ControlKind.ABSOLUTE, 1),
    B_TRIM("Gain", ControlKind.ABSOLUTE, 1), B_EQ_HI("EQ high", ControlKind.ABSOLUTE, 1),
    B_EQ_MID("EQ mid", ControlKind.ABSOLUTE, 1), B_EQ_LOW("EQ low", ControlKind.ABSOLUTE, 1),
    B_FILTER("Filter", ControlKind.ABSOLUTE, 1), B_FX_ON("FX on", ControlKind.BUTTON, 1),
    B_FX_AMOUNT("FX amount", ControlKind.ABSOLUTE, 1), B_PFL("Headphone cue", ControlKind.BUTTON, 1),
    B_JOG("Jog wheel", ControlKind.RELATIVE, 1), B_JOG_TOUCH("Jog touch (scratch)", ControlKind.BUTTON, 1),
    B_LOOP_IN("Loop in", ControlKind.BUTTON, 1), B_LOOP_OUT("Loop out", ControlKind.BUTTON, 1),
    B_LOOP("Loop on/off", ControlKind.BUTTON, 1), B_AUTOLOOP("Auto-loop 4 beats", ControlKind.BUTTON, 1),
    B_JUMP_BACK("Beat jump back", ControlKind.BUTTON, 1), B_JUMP_FWD("Beat jump forward", ControlKind.BUTTON, 1),
    B_HOT1("Hot cue 1", ControlKind.BUTTON, 1, 0), B_HOT2("Hot cue 2", ControlKind.BUTTON, 1, 1),
    B_HOT3("Hot cue 3", ControlKind.BUTTON, 1, 2), B_HOT4("Hot cue 4", ControlKind.BUTTON, 1, 3),
    B_HOT5("Hot cue 5", ControlKind.BUTTON, 1, 4), B_HOT6("Hot cue 6", ControlKind.BUTTON, 1, 5),
    B_HOT7("Hot cue 7", ControlKind.BUTTON, 1, 6), B_HOT8("Hot cue 8", ControlKind.BUTTON, 1, 7),

    CROSSFADER("Crossfader", ControlKind.ABSOLUTE, null), MASTER("Master volume", ControlKind.ABSOLUTE, null),
    PAD1("Pad A1", ControlKind.BUTTON, null, 0), PAD2("Pad A2", ControlKind.BUTTON, null, 1),
    PAD3("Pad A3", ControlKind.BUTTON, null, 2), PAD4("Pad A4", ControlKind.BUTTON, null, 3),
    PAD5("Pad A5", ControlKind.BUTTON, null, 4), PAD6("Pad A6", ControlKind.BUTTON, null, 5),
    PAD7("Pad A7", ControlKind.BUTTON, null, 6), PAD8("Pad A8", ControlKind.BUTTON, null, 7),

    // Performance pads: what they do follows the Sampler tab's mode (hot cues / samples)
    A_PERF1("Performance pad 1", ControlKind.BUTTON, 0, 0), A_PERF2("Performance pad 2", ControlKind.BUTTON, 0, 1),
    A_PERF3("Performance pad 3", ControlKind.BUTTON, 0, 2), A_PERF4("Performance pad 4", ControlKind.BUTTON, 0, 3),
    A_PERF5("Performance pad 5", ControlKind.BUTTON, 0, 4), A_PERF6("Performance pad 6", ControlKind.BUTTON, 0, 5),
    A_PERF7("Performance pad 7", ControlKind.BUTTON, 0, 6), A_PERF8("Performance pad 8", ControlKind.BUTTON, 0, 7),
    B_PERF1("Performance pad 1", ControlKind.BUTTON, 1, 0), B_PERF2("Performance pad 2", ControlKind.BUTTON, 1, 1),
    B_PERF3("Performance pad 3", ControlKind.BUTTON, 1, 2), B_PERF4("Performance pad 4", ControlKind.BUTTON, 1, 3),
    B_PERF5("Performance pad 5", ControlKind.BUTTON, 1, 4), B_PERF6("Performance pad 6", ControlKind.BUTTON, 1, 5),
    B_PERF7("Performance pad 7", ControlKind.BUTTON, 1, 6), B_PERF8("Performance pad 8", ControlKind.BUTTON, 1, 7),
    PAD_MODE("Pad mode: hot cues / samples", ControlKind.BUTTON, null);

    val isHotCue: Boolean get() = deck != null && name.startsWith("HOT", startIndex = 2)
    val isPerformancePad: Boolean get() = deck != null && name.startsWith("PERF", startIndex = 2)
}

/** What a performance pad does right now. */
sealed interface PadTarget {
    data class HotCue(val deck: Int, val slot: Int) : PadTarget
    data class Sample(val padIndex: Int) : PadTarget

    companion object {
        /**
         * In hot cue mode a deck's pad n is that deck's hot cue n. In sample
         * mode the current bank is split: deck A's pads play its samples 1-8,
         * deck B's pads its samples 9-16.
         */
        fun of(hotCueMode: Boolean, bank: Int, deck: Int, slot: Int, bankSize: Int = 16): PadTarget =
            if (hotCueMode) HotCue(deck, slot) else Sample(bank * bankSize + deck * 8 + slot)
    }
}

/** A physical control: a note (button) or a controller number, on a MIDI channel 0..15. */
@Serializable
data class MidiBinding(val note: Boolean, val channel: Int, val number: Int) {
    override fun toString() = (if (note) "Note " else "CC ") + "$number · ch ${channel + 1}"
}

/** One decoded incoming message. [value] is 0..127 (note velocity or CC value). */
data class MidiEvent(val binding: MidiBinding, val value: Int, val pressed: Boolean)

/** What a bound control asks the app to do. */
sealed interface MidiCommand {
    val action: MidiAction
    data class Press(override val action: MidiAction, val down: Boolean) : MidiCommand
    /** Absolute position 0..1 (faders, knobs). */
    data class Set(override val action: MidiAction, val value: Float) : MidiCommand
    /** Relative steps (jog wheels, endless encoders); positive = clockwise. */
    data class Turn(override val action: MidiAction, val steps: Int) : MidiCommand
}

/**
 * Pure MIDI logic, no Android: parses raw bytes, learns bindings and turns
 * events into [MidiCommand]s. Unit-tested on the JVM.
 */
class MidiMapper(initial: Map<MidiAction, MidiBinding> = emptyMap()) {
    var bindings: Map<MidiAction, MidiBinding> = initial
        private set

    /** While set, the next note-on or CC is bound to this action instead of being executed. */
    @Volatile var learning: MidiAction? = null

    /** Called when a binding changed (to persist it) — the new map. */
    var onChanged: ((Map<MidiAction, MidiBinding>) -> Unit)? = null

    fun clear(action: MidiAction) {
        bindings = bindings - action
        onChanged?.invoke(bindings)
    }

    fun clearAll() {
        bindings = emptyMap()
        onChanged?.invoke(bindings)
    }

    /** Raw bytes as delivered by Android (may hold several messages, with running status). */
    fun parse(data: ByteArray, offset: Int, count: Int): List<MidiEvent> {
        val out = ArrayList<MidiEvent>(2)
        var i = offset
        val end = offset + count
        var status = 0
        while (i < end) {
            val b = data[i].toInt() and 0xFF
            if (b >= 0x80) {
                if (b >= 0xF0) { i++; continue } // system / realtime: not used
                status = b
                i++
                continue
            }
            if (status == 0) { i++; continue }
            val type = status and 0xF0
            val channel = status and 0x0F
            val needs = if (type == 0xC0 || type == 0xD0) 1 else 2
            if (i + needs > end) break
            val d1 = data[i].toInt() and 0x7F
            val d2 = if (needs == 2) data[i + 1].toInt() and 0x7F else 0
            i += needs
            when (type) {
                0x90 -> out += MidiEvent(MidiBinding(true, channel, d1), d2, d2 > 0) // velocity 0 = off
                0x80 -> out += MidiEvent(MidiBinding(true, channel, d1), 0, false)
                0xB0 -> out += MidiEvent(MidiBinding(false, channel, d1), d2, d2 > 0)
            }
        }
        return out
    }

    /** Learns or translates one event. Returns null when nothing should happen. */
    fun handle(event: MidiEvent): MidiCommand? {
        learning?.let { action ->
            // Learn on a press or a moving control, not on the release that follows
            if (event.binding.note && !event.pressed) return null
            learning = null
            bindings = bindings.filterValues { it != event.binding } + (action to event.binding)
            onChanged?.invoke(bindings)
            return null
        }
        val action = bindings.entries.firstOrNull { it.value == event.binding }?.key ?: return null
        return when (action.kind) {
            ControlKind.BUTTON -> MidiCommand.Press(action, event.pressed)
            ControlKind.ABSOLUTE -> MidiCommand.Set(action, event.value / 127f)
            ControlKind.RELATIVE -> relative(event.value).takeIf { it != 0 }?.let { MidiCommand.Turn(action, it) }
        }
    }

    companion object {
        /**
         * Relative encoders come in two common flavours: two's complement
         * (1..63 forward, 127..65 back) and binary offset (65.. forward,
         * 63.. back, 64 = still). Small values decide which one it is.
         */
        fun relative(value: Int): Int = when (value) {
            in 1..31 -> value          // two's complement, forward
            in 97..127 -> value - 128  // two's complement, back
            in 33..63 -> value - 64    // binary offset, back
            in 65..95 -> value - 64    // binary offset, forward
            else -> 0
        }
    }
}
