package de.mischpult.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.mischpult.audio.Deck
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A record on a turntable turns once every 1.8 s (33⅓ rpm). */
private const val SECONDS_PER_TURN = 60.0 / (100.0 / 3.0)

/**
 * For scratching: the left side of each turntable, as both stand on a real
 * battle setup, scratched up and down like a real record (the scratch follows the angle the
 * finger moves around the spindle), with a big crossfader for cuts below.
 */
@Composable
fun ScratchScreen(vm: DjViewModel) {
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Both waveforms, small: where in the track the hand is
        Waveforms(vm, Modifier.fillMaxWidth().height(132.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Turntable(vm, 0, Modifier.weight(1f))
            Turntable(vm, 1, Modifier.weight(1f))
        }
        CrossfaderPanel(vm)
    }
}

@Composable
private fun Turntable(vm: DjViewModel, index: Int, modifier: Modifier) {
    val deck = vm.deck(index)
    val ui by vm.decks[index].collectAsState()
    val accent = DjColors.deck(index)
    var playing by remember(deck) { mutableStateOf(deck.playing) }
    LaunchedEffect(deck) {
        while (true) {
            playing = deck.playing
            pollDelay(120)
        }
    }
    Column(modifier.panel().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().lcd().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("AB"[index].toString(), color = accent, fontFamily = DjFonts.mono, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                ui.track?.title ?: "Empty — load from Library",
                fontSize = 12.sp, color = if (ui.track != null) DjColors.text else DjColors.textDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp),
            )
        }
        Platter(deck, accent, Modifier.weight(1f).fillMaxWidth())
        // Start/stop at the bottom left, where a turntable has it
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip("PLAY", playing, accent, Modifier.weight(1f).height(36.dp), enabled = ui.waveform != null) {
                deck.playing = !deck.playing
                playing = deck.playing
            }
            ToggleChip("CUE", false, DjColors.cue, Modifier.weight(1f).height(36.dp), enabled = ui.waveform != null) { deck.cue() }
        }
    }
}

/**
 * The left side of a turntable: the platter comes in from the right with its
 * left edge in reach, the same on both decks, as two real turntables stand
 * side by side. The record turns clockwise, so on this side forward is up.
 * Moving a finger along the record turns it by exactly that angle.
 */
@Composable
private fun Platter(deck: Deck, accent: Color, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val fling = remember(deck) { RecordFling(deck, scope) }
    val tick = rememberFrameTick(active = { deck.active }) { deck.position }
    // Where the spindle sits, relative to this view: set while drawing, read by the gesture
    val centre = remember { floatArrayOf(0f, 0f) }
    val currentDeck by rememberUpdatedState(deck)

    Canvas(
        modifier
            .clipToBounds()
            .graphicsLayer()
            .pointerInput(deck) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    fling.catch()
                    val d = currentDeck
                    val rate = d.track?.sampleRate ?: return@awaitEachGesture
                    // Grabbing the record holds it, like a hand on vinyl
                    d.beginScratch()
                    val framesPerRadian = SECONDS_PER_TURN * rate / (2 * PI)
                    var lastAngle = atan2(down.position.y - centre[1], down.position.x - centre[0])
                    var travelled = 0.0
                    val tracker = VelocityTracker1D(isDataDifferential = false)
                    tracker.addDataPoint(down.uptimeMillis, 0f)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val angle = atan2(change.position.y - centre[1], change.position.x - centre[0])
                        var delta = angle - lastAngle
                        // Across the ±π seam
                        if (delta > PI) delta -= (2 * PI).toFloat() else if (delta < -PI) delta += (2 * PI).toFloat()
                        lastAngle = angle
                        // Clockwise (as drawn, y down) is forward, the way a record turns
                        val frames = delta * framesPerRadian
                        travelled += frames
                        d.scratchBy(frames)
                        tracker.addDataPoint(change.uptimeMillis, travelled.toFloat())
                        change.consume()
                        if (!change.pressed) break
                    }
                    fling.release(tracker.calculateVelocity().toDouble())
                }
            }
    ) {
        tick.longValue
        val w = size.width
        val h = size.height
        // A big record: most of it off screen, its edge in reach of the thumb
        val r = minOf(h * 0.56f, w * 1.7f)
        val cx = 10.dp.toPx() + r
        val cy = h / 2
        centre[0] = cx
        centre[1] = cy
        val c = Offset(cx, cy)
        val rate = deck.track?.sampleRate ?: 44100
        val turns = deck.position / rate / SECONDS_PER_TURN
        val degrees = ((turns - kotlin.math.floor(turns)) * 360.0).toFloat()

        // Platter with a strobe ring: its dots stream past while the record turns
        drawCircle(DjColors.surfaceHigh, r, c)
        val dotR = 1.6.dp.toPx()
        val ringR = r - 5.dp.toPx()
        for (i in 0 until 90) {
            val a = (i * 4f + degrees) * PI.toFloat() / 180f
            drawCircle(if (i % 2 == 0) DjColors.cap else DjColors.ledOff, dotR,
                c + Offset(cos(a) * ringR, sin(a) * ringR))
        }
        // The record: black vinyl, faint grooves, the deck's colour on the label
        val vinyl = r - 11.dp.toPx()
        drawCircle(DjColors.lcd, vinyl, c)
        var g = vinyl - 6.dp.toPx()
        val label = r * 0.32f
        while (g > label + 4.dp.toPx()) {
            drawCircle(Color.White.copy(alpha = 0.035f), g, c, style = Stroke(1f))
            g -= 5.dp.toPx()
        }
        drawCircle(accent, label, c)
        drawCircle(DjColors.background, 3.dp.toPx(), c)
        // A marker on the record, as DJs put a sticker on it: shows where it is in the turn.
        // It starts on the visible left side.
        rotate(degrees + 180f, c) {
            drawLine(Color.White, Offset(cx + label + 2.dp.toPx(), cy), Offset(cx + vinyl - 2.dp.toPx(), cy),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(DjColors.background, Offset(cx + label * 0.35f, cy), Offset(cx + label - 4.dp.toPx(), cy),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun CrossfaderPanel(vm: DjViewModel) {
    var xfade by rememberSynced(Unit) { vm.engine.crossfader }
    var cut by remember { mutableStateOf(vm.engine.crossfaderCut) }
    Column(Modifier.fillMaxWidth().panel().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("A", color = DjColors.deckA, fontFamily = DjFonts.mono, fontSize = 14.sp)
            Text("CROSSFADER", fontSize = 11.sp, color = DjColors.textDim, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            ToggleChip("SMOOTH", !cut, DjColors.text, Modifier.width(72.dp)) { cut = false; vm.engine.crossfaderCut = false }
            ToggleChip("CUT", cut, DjColors.cue, Modifier.width(56.dp)) { cut = true; vm.engine.crossfaderCut = true }
            Text("B", color = DjColors.deckB, fontFamily = DjFonts.mono, fontSize = 14.sp)
        }
        BigCrossfader(xfade, Modifier.fillMaxWidth().height(72.dp)) { xfade = it; vm.engine.crossfader = it }
    }
}

/**
 * A long crossfader with a big cap. Unlike the mixing faders it goes straight
 * to where the finger is: a cut has to happen the moment the thumb lands.
 */
@Composable
private fun BigCrossfader(value: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier.pointerInput(Unit) {
            val capHalf = 26.dp.toPx()
            fun toValue(x: Float) = (((x - capHalf) / (size.width - 2 * capHalf)) * 2f - 1f).coerceIn(-1f, 1f)
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                change(toValue(down.position.x))
                while (true) {
                    val event = awaitPointerEvent()
                    val c = event.changes.firstOrNull { it.id == down.id } ?: break
                    change(toValue(c.position.x))
                    c.consume()
                    if (!c.pressed) break
                }
            }
        }
    ) {
        val capW = 52.dp.toPx()
        val capH = size.height - 8.dp.toPx()
        val cy = size.height / 2
        val usable = size.width - capW
        // Slot, with marks at the ends and in the middle
        drawRoundRect(DjColors.lcd, Offset(capW / 2, cy - 4.dp.toPx()), Size(usable, 8.dp.toPx()), CornerRadius(2.dp.toPx()))
        for (f in floatArrayOf(0f, 0.5f, 1f)) {
            val x = capW / 2 + usable * f
            drawLine(DjColors.textDim, Offset(x, 6.dp.toPx()), Offset(x, 14.dp.toPx()), 1.dp.toPx())
            drawLine(DjColors.textDim, Offset(x, size.height - 14.dp.toPx()), Offset(x, size.height - 6.dp.toPx()), 1.dp.toPx())
        }
        val x = capW / 2 + usable * (value + 1f) / 2f
        val top = cy - capH / 2
        drawRoundRect(Color.Black.copy(alpha = 0.5f), Offset(x - capW / 2, top + 2.dp.toPx()), Size(capW, capH), CornerRadius(4.dp.toPx()))
        drawRoundRect(DjColors.cap, Offset(x - capW / 2, top), Size(capW, capH), CornerRadius(4.dp.toPx()))
        drawRect(DjColors.capShade, Offset(x - capW / 2, top + capH - 6.dp.toPx()), Size(capW, 6.dp.toPx()))
        // Grip ridges and the centre line
        for (i in -2..2) {
            val gx = x + i * 7.dp.toPx()
            drawLine(DjColors.capShade, Offset(gx, top + 10.dp.toPx()), Offset(gx, top + capH - 14.dp.toPx()), 1.5.dp.toPx())
        }
        drawLine(DjColors.lcd, Offset(x, top + 6.dp.toPx()), Offset(x, top + capH - 10.dp.toPx()), 2.dp.toPx())
    }
}
