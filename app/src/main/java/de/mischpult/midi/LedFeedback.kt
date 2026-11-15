package de.mischpult.midi

/**
 * Turns app state into LED messages for the controller's buttons: each mapped
 * button is lit (velocity / value [onValue]) or dark (0) on the same note or
 * CC it sends. Only changes go out, so a full refresh several times a second
 * costs nothing on the MIDI cable. Pure logic, unit-tested on the JVM.
 */
class LedFeedback(var onValue: Int = 127) {
    private val sent = HashMap<MidiBinding, Boolean>()

    /** Forget what the controller shows (new connection): the next update resends everything. */
    fun reset() = sent.clear()

    /**
     * Messages needed to bring the controller's LEDs to [state]. [state]
     * returns null for actions that have no LED (faders, jog wheels).
     */
    fun update(bindings: Map<MidiAction, MidiBinding>, state: (MidiAction) -> Boolean?): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val seen = HashSet<MidiBinding>()
        for ((action, binding) in bindings) {
            if (action.kind != ControlKind.BUTTON) continue
            val lit = state(action) ?: continue
            seen += binding
            if (sent[binding] == lit) continue
            sent[binding] = lit
            out += message(binding, lit)
        }
        // A button that lost its mapping goes dark rather than staying lit forever
        val gone = sent.keys - seen
        for (binding in gone) {
            if (sent[binding] == true) out += message(binding, false)
            sent.remove(binding)
        }
        return out
    }

    /** Everything that is lit, switched off (disconnect, app closing). */
    fun allOff(): List<ByteArray> = sent.filterValues { it }.keys.map { message(it, false) }.also { sent.clear() }

    private fun message(b: MidiBinding, lit: Boolean): ByteArray {
        val status = (if (b.note) 0x90 else 0xB0) or (b.channel and 0x0F)
        return byteArrayOf(status.toByte(), (b.number and 0x7F).toByte(), (if (lit) onValue and 0x7F else 0).toByte())
    }
}
