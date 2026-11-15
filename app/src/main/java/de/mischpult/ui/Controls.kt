package de.mischpult.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Rotary knob, 0..1. Drag up/down to turn, double-tap to reset to [default].
 * With [bipolar] the value arc grows from 12 o'clock (EQ, filter).
 */
@Composable
fun Knob(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    default: Float = 0.5f,
    bipolar: Boolean = true,
    size: Dp = 34.dp,
) {
    val current by rememberUpdatedState(value)
    val onChange by rememberUpdatedState(onValueChange)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(size)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dy ->
                        change.consume()
                        // Full travel over ~180 dp
                        onChange((current - dy / (180.dp.toPx())).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(default) {
                    detectTapGestures(onDoubleTap = { onChange(default) })
                }
        ) {
            // A ring of 11 LEDs shows the value (from 12 o'clock when bipolar), around a rubber cap
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val ringR = this.size.minDimension / 2 - 2.5.dp.toPx()
            val dot = 1.6.dp.toPx()
            val steps = 10
            val lit = Math.round(value * steps)
            val centre = steps / 2
            for (i in 0..steps) {
                val on = if (bipolar) {
                    if (lit >= centre) i in centre..lit else i in lit..centre
                } else i <= lit && value > 0.001f
                val a = Math.toRadians((135.0 + 27.0 * i))
                val p = c + Offset((cos(a) * ringR).toFloat(), (sin(a) * ringR).toFloat())
                if (on) drawCircle(accent.copy(alpha = 0.3f), dot * 2.2f, p)
                drawCircle(if (on) accent else DjColors.ledOff, dot, p)
            }
            val capR = ringR - 4.5.dp.toPx()
            drawCircle(Color.Black.copy(alpha = 0.5f), capR, c + Offset(0f, 1.dp.toPx()))
            drawCircle(DjColors.surfaceHigh, capR, c)
            drawArc(Color.White.copy(alpha = 0.10f), 200f, 140f, false, Offset(c.x - capR, c.y - capR),
                Size(capR * 2, capR * 2), style = Stroke(1.dp.toPx()))
            val angle = Math.toRadians(135.0 + 270.0 * value)
            drawLine(
                DjColors.text,
                c + Offset((cos(angle) * capR * 0.25f).toFloat(), (sin(angle) * capR * 0.25f).toFloat()),
                c + Offset((cos(angle) * capR * 0.9f).toFloat(), (sin(angle) * capR * 0.9f).toFloat()),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            )
        }
        Text(label, fontSize = 9.sp, color = DjColors.textDim, maxLines = 1)
    }
}

/**
 * Small latching pad (SYNC, FX on, headphone cue): its LED lights in [color]
 * while on, the way a controller shows state, instead of the pad filling.
 */
@Composable
fun ToggleChip(
    text: String,
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(28.dp)
            .padSurface()
            .led(color, active && enabled)
            .pointerInput(onClick, enabled) { if (enabled) detectTapGestures(onPress = { onClick() }) }
            .padding(start = 12.dp, end = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        icon?.let {
            androidx.compose.material3.Icon(it, null, tint = DjColors.deckA,
                modifier = Modifier.size(13.dp).padding(end = 3.dp))
        }
        Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1,
            color = when {
                !enabled -> DjColors.textDim.copy(alpha = 0.4f)
                active -> DjColors.text
                else -> DjColors.textDim
            })
    }
}

/** Knob position ↔ channel gain. Knob 0.5 = unity, 0 = kill, 1 ≈ +6 dB. */
object EqCurve {
    fun gain(k: Float): Float = if (k <= 0.5f) (k / 0.5f).pow(2.5f) else 1f + (k - 0.5f) * 2f
    fun knob(g: Float): Float = if (g <= 1f) 0.5f * g.pow(1f / 2.5f) else 0.5f + (g - 1f) / 2f
}

/** Knob position ↔ trim gain, ±12 dB around 12 o'clock. */
object TrimCurve {
    fun gain(k: Float): Float = 10f.pow((k - 0.5f) * 24f / 20f)
    fun knob(g: Float): Float = (kotlin.math.log10(g) * 20f / 24f + 0.5f).coerceIn(0f, 1f)
}

/**
 * A control value that the UI changes but that can also change elsewhere (a
 * MIDI controller): re-reads [read] a few times per second and follows it.
 */
@Composable
fun rememberSynced(key: Any?, read: () -> Float): androidx.compose.runtime.MutableFloatState {
    val state = androidx.compose.runtime.remember(key) { androidx.compose.runtime.mutableFloatStateOf(read()) }
    val entry = androidx.compose.runtime.remember(key) { SyncedValue(read, state) }
    entry.read = read
    androidx.compose.runtime.DisposableEffect(entry) {
        SyncedValue.all += entry
        onDispose { SyncedValue.all -= entry }
    }
    return state
}

/** A knob or fader value that [SyncPoller] keeps in step with the engine. */
private class SyncedValue(var read: () -> Float, val state: androidx.compose.runtime.MutableFloatState) {
    companion object {
        /** Registered from composition, read by the poller: both on the main thread. */
        val all = mutableListOf<SyncedValue>()
    }
}

/**
 * One loop for all [rememberSynced] values on screen (fifteen on the decks),
 * rather than one loop each: every separate loop is its own main-thread wake-up.
 */
@Composable
fun SyncPoller() {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            for (e in SyncedValue.all) {
                val v = e.read()
                if (kotlin.math.abs(v - e.state.floatValue) > 1e-3f) e.state.floatValue = v
            }
            pollDelay(120)
        }
    }
}

/** Whether the app's screen is showing (between the activity's onStart and onStop). */
object AppVisibility {
    val visible = kotlinx.coroutines.flow.MutableStateFlow(true)

    /** Returns at once while visible; otherwise waits until the app is shown again. */
    suspend fun awaitVisible() {
        if (!visible.value) visible.first { it }
    }
}

/**
 * Waits [ms] for a UI polling loop, and while the app is in the background
 * until it is shown again: no waking the CPU several times a second during a
 * set with the screen off. (Waiting for a frame would do that too, but each
 * wait asks the display for a frame; dozens of loops made that costly.)
 */
suspend fun pollDelay(ms: Long) {
    kotlinx.coroutines.delay(ms)
    AppVisibility.awaitVisible()
}
