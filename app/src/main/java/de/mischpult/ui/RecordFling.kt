package de.mischpult.ui

import androidx.compose.runtime.withFrameNanos
import de.mischpult.audio.Deck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp

/**
 * A record let go mid-flick keeps turning: it starts at the hand's speed and
 * eases towards the motor's, the deck's play speed when it is playing, a
 * standstill when it is not, the way a platter coasts under friction. The
 * deck stays in scratch mode meanwhile and hands back to the motor at the end.
 *
 * A new touch [catch]es the record: the coasting stops without ending the
 * scratch, as the touch begins its own.
 */
class RecordFling(private val deck: Deck, private val scope: CoroutineScope) {
    private var job: Job? = null
    /** Bumped by every start and catch: only the newest coast may end the scratch. */
    private var generation = 0

    /** Finger lifted with [velocity] in source frames per second (negative = backwards). */
    fun release(velocity: Double) {
        val gen = ++generation
        job?.cancel()
        val rate = deck.track?.sampleRate?.toDouble() ?: run { deck.endScratch(); return }
        val motor = if (deck.playing) rate * (1.0 + deck.tempo) else 0.0
        // A plain release (no flick) hands straight back to the motor
        if (abs(velocity - motor) < rate * MIN_FLICK) {
            deck.endScratch()
            return
        }
        job = scope.launch {
            try {
                // A thrown record spins only so fast; a hard flick on a zoomed-out waveform would
                // otherwise send it half a track away
                var v = velocity.coerceIn(-MAX_SPEED * rate, MAX_SPEED * rate)
                var last = 0L
                while (abs(v - motor) > rate * SETTLED) {
                    withFrameNanos { now ->
                        if (last != 0L) {
                            val dt = (now - last) / 1e9
                            // Exponential approach to the motor's speed: friction on the platter
                            v = motor + (v - motor) * exp(-dt / FRICTION_S)
                            deck.scratchBy(v * dt)
                        }
                        last = now
                    }
                }
            } finally {
                if (gen == generation) deck.endScratch()
            }
        }
    }

    /** A finger touches the record again: stop coasting, the touch takes over. */
    fun catch() {
        ++generation
        job?.cancel()
        job = null
    }

    private companion object {
        /** Seconds for the difference to the motor's speed to fall to 1/e. */
        const val FRICTION_S = 0.35
        /** Less than this (in play speeds) away from the motor at release: no coasting. */
        const val MIN_FLICK = 0.25
        /** Coasting ends this close (in play speeds) to the motor's speed. */
        const val SETTLED = 0.03
        /** Fastest coasting, in play speeds. */
        const val MAX_SPEED = 6.0
    }
}
