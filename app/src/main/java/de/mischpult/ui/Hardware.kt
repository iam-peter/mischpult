package de.mischpult.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/*
 * The console look: graphite panels, rubber pads with a small status LED
 * instead of buttons that fill with colour, recessed "LCD" boxes for
 * readouts and waveforms, LED rings on knobs. Everything here is drawing
 * only; the controls keep their own gestures.
 */

val PadShape = RoundedCornerShape(4.dp)
private val PanelShape = RoundedCornerShape(6.dp)
private val LcdShape = RoundedCornerShape(3.dp)

/**
 * Light or shadow along the inside of a shape's edge, fading out over [reach]
 * from the top (or from the bottom). It follows the rounded corners, so every
 * corner keeps the same radius; a straight band would square off the shaded side.
 */
private class Shade(val color: Color, val width: Dp, val reach: Dp, val fromTop: Boolean)

/**
 * Fills [shape] with [fill] and shades its edges. Outline, gradients and
 * strokes are built once per size (drawWithCache), not on every draw: pads
 * redraw often, and there are dozens on screen.
 */
private fun Modifier.shadedSurface(shape: Shape, fill: Color, shades: List<Shade>): Modifier = this
    .clip(shape)
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val brushes = shades.map { sh ->
            val r = sh.reach.toPx()
            if (sh.fromTop) Brush.verticalGradient(listOf(sh.color, Color.Transparent), startY = 0f, endY = r)
            else Brush.verticalGradient(listOf(Color.Transparent, sh.color), startY = size.height - r, endY = size.height)
        }
        // A stroke is centred on the edge; the half outside is clipped away
        val strokes = shades.map { Stroke(it.width.toPx() * 2) }
        onDrawWithContent {
            drawOutline(outline, fill)
            drawContent()
            for (i in shades.indices) drawOutline(outline, brushes[i], style = strokes[i])
        }
    }

private val PadShades = listOf(
    Shade(Color.White.copy(alpha = 0.10f), 1.dp, 6.dp, fromTop = true),
    Shade(Color.Black.copy(alpha = 0.5f), 2.dp, 7.dp, fromTop = false),
)
private val PanelShades = listOf(Shade(Color.White.copy(alpha = 0.08f), 1.dp, 10.dp, fromTop = true))
private val LcdShades = listOf(Shade(Color.Black.copy(alpha = 0.7f), 2.dp, 8.dp, fromTop = true))

/** A raised rubber pad: lit from above, a darker lip at the bottom. */
fun Modifier.padSurface(shape: Shape = PadShape): Modifier = shadedSurface(shape, DjColors.surfaceHigh, PadShades)

/** Status LED in a pad's top-left corner: glowing [color] when [on], a dark dot otherwise. */
fun Modifier.led(color: Color, on: Boolean, size: Dp = 4.5.dp): Modifier = drawWithContent {
    drawContent()
    val r = size.toPx() / 2
    val c = Offset(5.dp.toPx() + r, 5.dp.toPx() + r)
    if (on) {
        drawCircle(color.copy(alpha = 0.30f), r * 2.6f, c)
        drawCircle(color, r, c)
    } else {
        drawCircle(DjColors.ledOff, r, c)
    }
}

/** A section of the console: graphite plate with a highlight along its top edge. */
fun Modifier.panel(): Modifier = shadedSurface(PanelShape, DjColors.surface, PanelShades)

/** A recessed display window for readouts and waveforms; the shadow says it sits below the panel. */
fun Modifier.lcd(shape: Shape = LcdShape): Modifier = shadedSurface(shape, DjColors.lcd, LcdShades)

/** In a dialog's confirm slot: that pad's LED is lit. */
private val LocalDefaultPad = staticCompositionLocalOf { false }

@Composable
fun ProvideDefaultPad(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalDefaultPad provides true, content = content)

/** Text button as a pad. Takes over from Material's TextButton / FilledTonalButton. */
@Composable
fun PadButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val lit = LocalDefaultPad.current && enabled
    Row(
        modifier
            .heightIn(min = 34.dp)
            .padSurface()
            .led(DjColors.cue, lit)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) DjColors.text else DjColors.textDim.copy(alpha = 0.5f)) {
            ProvideTextStyle(TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)) { content() }
        }
    }
}

/** Section selector: a row of pads, the selected one with its LED lit. */
@Composable
fun PadTabs(titles: List<String>, selected: Int, modifier: Modifier = Modifier, height: Dp = 34.dp, onSelect: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        titles.forEachIndexed { i, t ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(height).padSurface().led(DjColors.deckA, on)
                    .pointerInput(i) { detectTapGestures(onPress = { onSelect(i) }) },
                contentAlignment = Alignment.Center,
            ) {
                Text(t.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    color = if (on) DjColors.text else DjColors.textDim, maxLines = 1)
            }
        }
    }
}

/**
 * Horizontal hardware slider: a recessed groove and a silver cap. Drags move
 * the value from where it is, never jump to the finger, so touching the tempo
 * or crossfader mid-set changes nothing by itself. Double-tap: [default].
 */
@Composable
fun HwSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    default: Float? = null,
    fill: Color? = null,
    centerMark: Boolean = false,
) {
    val current by rememberUpdatedState(value)
    val onChange by rememberUpdatedState(onValueChange)
    val span = valueRange.endInclusive - valueRange.start
    Canvas(
        modifier
            .height(24.dp)
            .fillMaxWidth()
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                setProgress { v -> onChange(v.coerceIn(valueRange)); true }
            }
            .pointerInput(valueRange) {
                detectHorizontalDragGestures { change, dx ->
                    change.consume()
                    onChange((current + dx / size.width * span).coerceIn(valueRange))
                }
            }
            .pointerInput(default) {
                if (default != null) detectTapGestures(onDoubleTap = { onChange(default) })
            }
    ) {
        val capW = 10.dp.toPx()
        val usable = size.width - capW
        val f = ((value - valueRange.start) / span).coerceIn(0f, 1f)
        val x = capW / 2 + usable * f
        val cy = size.height / 2
        val groove = 5.dp.toPx()
        drawRoundRect(DjColors.lcd, Offset(0f, cy - groove / 2), Size(size.width, groove), CornerRadius(1.dp.toPx()))
        if (fill != null) drawRect(fill.copy(alpha = 0.8f), Offset(1.dp.toPx(), cy - 1.dp.toPx()), Size(x, 2.dp.toPx()))
        if (centerMark) {
            drawRect(DjColors.textDim, Offset(size.width / 2 - 0.5.dp.toPx(), cy - 8.dp.toPx()), Size(1.dp.toPx(), 16.dp.toPx()))
        }
        val capH = minOf(size.height, 20.dp.toPx())
        drawRoundRect(DjColors.cap, Offset(x - capW / 2, cy - capH / 2), Size(capW, capH), CornerRadius(2.dp.toPx()))
        drawRect(DjColors.capShade, Offset(x - capW / 2, cy + capH / 2 - 3.dp.toPx()), Size(capW, 3.dp.toPx()))
        drawRect(DjColors.lcd, Offset(x - 0.5.dp.toPx(), cy - capH / 2 + 3.dp.toPx()), Size(1.dp.toPx(), capH - 7.dp.toPx()))
    }
}

/** Progress as a strip of LED segments; without [progress] a lit block runs along (busy). */
@Composable
fun LedProgress(
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
    color: Color = DjColors.deckA,
    trackColor: Color = DjColors.ledOff,
) {
    val run = if (progress == null) {
        rememberInfiniteTransition(label = "busy")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "busy")
            .value
    } else 0f
    Canvas(modifier.height(4.dp).fillMaxWidth()) {
        val seg = 4.dp.toPx()
        val gap = 1.5.dp.toPx()
        val n = ((size.width + gap) / (seg + gap)).toInt().coerceAtLeast(1)
        val lit = progress?.let { (it().coerceIn(0f, 1f) * n).toInt() }
        val runAt = (run * (n + 6)).toInt() - 3
        for (i in 0 until n) {
            val on = if (lit != null) i < lit else i in runAt..runAt + 3
            drawRect(if (on) color else trackColor, Offset(i * (seg + gap), 0f), Size(seg, size.height))
        }
    }
}

/** Popup menu on a graphite plate. */
@Composable
fun HwMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = PadShape,
        containerColor = DjColors.surfaceHigh,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        content = content,
    )
}

/** Pressing a pad pushes it in: it darkens while held instead of drawing an ink ripple. */
object PadPress : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 11

    private class Node(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private var presses = 0

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect {
                    when (it) {
                        is PressInteraction.Press -> presses++
                        is PressInteraction.Release, is PressInteraction.Cancel -> presses = maxOf(0, presses - 1)
                        else -> return@collect
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (presses > 0) drawRect(Color.Black.copy(alpha = 0.22f))
        }
    }
}
