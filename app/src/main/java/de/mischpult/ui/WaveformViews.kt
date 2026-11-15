package de.mischpult.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.MutableLongState
import de.mischpult.audio.Deck
import de.mischpult.audio.Waveform
import kotlin.math.max

/**
 * Read inside a draw lambda to redraw without recomposing. Ticks every display
 * frame while [active] (a deck playing or scratched, a pad sounding, a meter
 * falling); otherwise looks at [probe] every [idleMs] and ticks only when its
 * value changed (a seek, a new hot cue, more of the track decoded), so still
 * screens cost nothing. Frames stop with the app in the background, and so
 * does this.
 */
@Composable
fun rememberFrameTick(
    active: () -> Boolean = { true },
    idleMs: Long = 100,
    probe: () -> Any? = { null },
): MutableLongState {
    val tick = remember { mutableLongStateOf(0L) }
    val isActive by rememberUpdatedState(active)
    val currentProbe by rememberUpdatedState(probe)
    LaunchedEffect(Unit) {
        var last: Any? = currentProbe()
        while (true) {
            if (isActive()) {
                withFrameNanos { tick.longValue = it }
                last = null // look again once it stops
            } else {
                pollDelay(idleMs)
                val now = currentProbe()
                if (now != last) {
                    last = now
                    tick.longValue++
                }
            }
        }
    }
    return tick
}

private val BEAT_TICK = Color(0x99FFFFFF)
private val HIGH = Color(0xFF7FD8FF)
private val BASS = Color(0xFFFF5A2A)
private const val BUCKETS = 8

/** Batches vertical lines per colour bucket so a frame is 8 native draw calls, not 1000+. */
private class LineBatcher {
    val paints = Array(BUCKETS) { b ->
        Paint().apply {
            color = lerp(HIGH, BASS, b / (BUCKETS - 1f)).toArgb()
            strokeWidth = 1f
            isAntiAlias = false
        }
    }
    private var lines = Array(BUCKETS) { FloatArray(0) }
    private val counts = IntArray(BUCKETS)

    fun begin(columns: Int, strokeWidth: Float) {
        if (lines[0].size < columns * 4) lines = Array(BUCKETS) { FloatArray(columns * 4) }
        counts.fill(0)
        paints.forEach { it.strokeWidth = strokeWidth }
    }

    fun add(x: Float, y0: Float, y1: Float, bass: Float) {
        val b = (bass * (BUCKETS - 1) + 0.5f).toInt().coerceIn(0, BUCKETS - 1)
        val arr = lines[b]
        val c = counts[b]
        arr[c] = x; arr[c + 1] = y0; arr[c + 2] = x; arr[c + 3] = y1
        counts[b] = c + 4
    }

    fun flush(scope: DrawScope, alpha: Int = 255) = scope.drawIntoCanvas { canvas ->
        for (b in 0 until BUCKETS) {
            if (counts[b] == 0) continue
            paints[b].alpha = alpha
            canvas.nativeCanvas.drawLines(lines[b], 0, counts[b], paints[b])
        }
    }
}

/**
 * Scrolling, zoomed waveform with the playhead fixed in the middle. Touching it
 * grabs the "record": hold to stop, drag to scratch/scroll, release to continue.
 */
@Composable
fun ScrollingWaveform(
    deck: Deck,
    waveform: Waveform?,
    zoomSeconds: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    /** Pinch: multiply the visible time by this factor (< 1 zooms in). */
    onZoom: (Float) -> Unit = {},
) {
    // Every frame while the record moves; otherwise only when something on it changed
    val tick = rememberFrameTick(active = { deck.active }) {
        listOf(deck.position, waveform?.ready, deck.hotCues, deck.loopIn, deck.loopOut, deck.loopActive, deck.buffering)
    }
    val batcher = remember { LineBatcher() }
    val zoom by rememberUpdatedState(zoomSeconds)
    val zoomBy by rememberUpdatedState(onZoom)
    val scope = rememberCoroutineScope()
    val fling = remember(deck) { RecordFling(deck, scope) }

    Canvas(
        modifier.graphicsLayer().pointerInput(deck) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                fling.catch() // a coasting record stops under the finger
                // A second finger within 60 ms means pinch, not "grab the record":
                // waiting that long keeps a pinch from briefly stopping the music
                var lastX = down.position.x
                // Followed from the first touch: a quick flick can be over within the pinch wait
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var endX = down.position.x
                val pinchStart = withTimeoutOrNull<PointerEvent?>(60) {
                    while (true) {
                        val e = awaitPointerEvent()
                        e.changes.forEach { it.consume() }
                        e.changes.firstOrNull { it.id == down.id }?.let {
                            tracker.addPosition(it.uptimeMillis, it.position)
                            endX = it.position.x
                        }
                        if (e.changes.count { it.pressed } >= 2) return@withTimeoutOrNull e
                        if (e.changes.none { it.pressed }) return@withTimeoutOrNull null
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
                if (pinchStart != null) {
                    // Pinch zoom until all fingers lift
                    var lastDist = -1f
                    while (true) {
                        val e = awaitPointerEvent()
                        val pressed = e.changes.filter { it.pressed }
                        e.changes.forEach { it.consume() }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            val d = (pressed[0].position - pressed[1].position).getDistance()
                            if (lastDist > 0f && d > 0f) zoomBy(lastDist / d)
                            lastDist = d
                        } else {
                            lastDist = -1f
                        }
                    }
                    return@awaitEachGesture
                }
                if (currentEvent.changes.none { it.pressed }) {
                    val rate = deck.track?.sampleRate ?: 0
                    val framesPerPx = if (rate > 0) zoom * rate / size.width else 0f
                    deck.beginScratch()
                    if (kotlin.math.abs(endX - down.position.x) > viewConfiguration.touchSlop && framesPerPx > 0f) {
                        // A flick over within the wait: move by it and let the record coast on
                        deck.scratchBy(-(endX - down.position.x) * framesPerPx.toDouble())
                        fling.release(-tracker.calculateVelocity().x * framesPerPx.toDouble())
                    } else {
                        // A tap: grab and let go, like touching the record
                        deck.endScratch()
                    }
                    return@awaitEachGesture
                }
                deck.beginScratch()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val dx = change.position.x - lastX
                    lastX = change.position.x
                    val rate = deck.track?.sampleRate ?: 0
                    if (dx != 0f && rate > 0) {
                        val framesPerPx = zoom * rate / size.width
                        deck.scratchBy(-dx * framesPerPx.toDouble())
                    }
                    change.consume()
                    if (!change.pressed) break
                }
                // Let go mid-flick: the record coasts on and slows down (dragging right = backwards)
                val rate = deck.track?.sampleRate ?: 0
                val framesPerPx = if (rate > 0) zoom * rate / size.width else 0f
                fling.release(-tracker.calculateVelocity().x * framesPerPx.toDouble())
            }
        }
    ) {
        tick.value // redraw every frame
        val w = size.width
        val h = size.height
        val cy = h / 2
        drawRect(DjColors.lcd)
        drawLine(DjColors.outline, Offset(0f, cy), Offset(w, cy), 1f)
        val wf = waveform ?: return@Canvas
        val rate = deck.track?.sampleRate ?: return@Canvas
        val bins = wf.ready // only analysed bins; grows while loading

        val step = 2 // px per column
        val columns = (w / step).toInt() + 1
        val framesPerPx = zoom * rate / w
        val center = w / 2
        val pos = deck.position
        batcher.begin(columns, step.toFloat())
        for (c in 0 until columns) {
            val x = c * step.toFloat()
            val f0 = pos + (x - center) * framesPerPx
            if (f0 < 0) continue
            val b0 = (f0 / wf.binFrames).toInt()
            if (b0 >= bins) break
            val b1 = max(b0 + 1, ((f0 + framesPerPx * step) / wf.binFrames).toInt()).coerceAtMost(bins)
            var peak = 0f
            var bass = 0f
            for (b in b0 until b1) {
                val p = wf.peak(b)
                if (p > peak) { peak = p; bass = wf.bass(b) }
            }
            val amp = peak * (cy - 2)
            batcher.add(x, cy - amp, cy + amp, bass)
        }
        batcher.flush(this)

        // Beat grid: ticks at top and bottom so they never hide the waveform
        deck.beatGrid?.let { g ->
            val spacing = (g.framesPerBeat / framesPerPx).toFloat()
            if (spacing > 6f) {
                val startFrame = pos - center * framesPerPx
                var k = kotlin.math.ceil((startFrame - g.firstBeat) / g.framesPerBeat)
                val tick = h * 0.12f
                while (true) {
                    val x = center + ((g.firstBeat + k * g.framesPerBeat - pos) / framesPerPx).toFloat()
                    if (x > w) break
                    drawLine(BEAT_TICK, Offset(x, 0f), Offset(x, tick), 2f)
                    drawLine(BEAT_TICK, Offset(x, h - tick), Offset(x, h), 2f)
                    k += 1.0
                }
            }
        }

        // Loop: shaded while active, outlined while armed
        drawLoop(deck, w, h) { f -> center + ((f - pos) / framesPerPx).toFloat() }
        drawHotCues(deck, w, h, labels = true) { f -> center + ((f - pos) / framesPerPx).toFloat() }

        // Cue marker
        val cueX = center + ((deck.cuePoint - pos) / framesPerPx).toFloat()
        if (cueX in 0f..w) drawLine(DjColors.cue, Offset(cueX, 0f), Offset(cueX, h), 3f)
        // Playhead
        drawLine(DjColors.cue, Offset(center, 0f), Offset(center, h), 2.dp.toPx())
        drawLine(accent, Offset(center, 0f), Offset(center, 6f), 6f)
    }
}

/** Per-column summary of the overview, extended as more bins get analysed. */
private class OverviewColumns {
    var cols = FloatArray(0)
    var columns = 0
    var totalBins = 0
    /** Columns whose bins are all analysed; they never change again. */
    var done = 0
    /** Columns holding data (the last one may still be partial). */
    var filled = 0

    fun update(wf: Waveform, columns: Int, totalBins: Int) {
        if (columns != this.columns || totalBins != this.totalBins) {
            cols = FloatArray(columns * 2)
            this.columns = columns
            this.totalBins = totalBins
            done = 0
        }
        val ready = wf.ready
        var c = done
        filled = done
        while (c < columns) {
            val b0 = (c.toLong() * totalBins / columns).toInt()
            if (b0 >= ready) break
            val end = max(b0 + 1, ((c + 1).toLong() * totalBins / columns).toInt())
            val b1 = minOf(end, ready)
            var peak = 0f
            var bassSum = 0f
            for (b in b0 until b1) {
                peak = max(peak, wf.peak(b)); bassSum += wf.bass(b)
            }
            cols[c * 2] = peak
            cols[c * 2 + 1] = bassSum / (b1 - b0)
            filled = c + 1
            if (end <= ready) done = c + 1 else break
            c++
        }
    }
}

/** Whole-track overview. Tap or drag to jump. Fills left to right while loading. */
@Composable
fun OverviewWaveform(
    deck: Deck,
    waveform: Waveform?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    // The playhead crawls about a pixel a second here: ten looks a second are plenty
    val tick = rememberFrameTick(active = { false }) {
        listOf(Math.round(deck.position / 2048.0), waveform?.ready, deck.hotCues, deck.loopIn, deck.loopOut)
    }
    val batcher = remember { LineBatcher() }
    val cache = remember(waveform) { OverviewColumns() }

    Canvas(
        modifier
            .graphicsLayer()
            .pointerInput(deck) {
                detectTapGestures { deck.seekFraction(it.x / size.width) }
            }
            .pointerInput(deck) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    deck.seekFraction(change.position.x / size.width)
                }
            }
    ) {
        tick.value
        val w = size.width
        val h = size.height
        drawRect(DjColors.lcd)
        val wf = waveform ?: return@Canvas
        // Scale to the expected length so the overview doesn't stretch while loading
        val totalFrames = deck.track?.expectedFrames ?: return@Canvas
        if (totalFrames <= 0) return@Canvas

        val columns = w.toInt().coerceAtLeast(1)
        cache.update(wf, columns, Waveform.binsFor(totalFrames))
        val cols = cache.cols
        val filled = cache.filled

        val playX = (deck.position / totalFrames * w).toFloat()
        val cy = h / 2
        // Already played part dimmed, rest at full strength
        batcher.begin(columns, 1f)
        for (c in 0 until minOf(filled, playX.toInt())) {
            val amp = cols[c * 2] * (cy - 1)
            batcher.add(c.toFloat(), cy - amp, cy + amp, cols[c * 2 + 1])
        }
        batcher.flush(this, alpha = 90)
        batcher.begin(columns, 1f)
        for (c in maxOf(0, playX.toInt()) until filled) {
            val amp = cols[c * 2] * (cy - 1)
            batcher.add(c.toFloat(), cy - amp, cy + amp, cols[c * 2 + 1])
        }
        batcher.flush(this)

        drawLoop(deck, w, h) { f -> (f / totalFrames * w).toFloat() }
        drawHotCues(deck, w, h, labels = false) { f -> (f / totalFrames * w).toFloat() }
        val cueX = (deck.cuePoint / totalFrames * w).toFloat()
        drawLine(DjColors.cue, Offset(cueX, 0f), Offset(cueX, h), 2f)
        drawLine(accent, Offset(playX, 0f), Offset(playX, h), 3f)
    }
}

/** Loop region between the IN and OUT markers; [toX] maps a source frame to x. */
private inline fun DrawScope.drawLoop(deck: Deck, w: Float, h: Float, toX: (Double) -> Float) {
    val loopIn = deck.loopIn
    if (loopIn < 0) return
    val inX = toX(loopIn)
    val loopOut = deck.loopOut
    if (loopOut > loopIn) {
        val outX = toX(loopOut)
        if (outX >= 0f && inX <= w) {
            val alpha = if (deck.loopActive) 0.22f else 0.08f
            drawRect(LOOP_COLOR.copy(alpha = alpha), Offset(inX, 0f), androidx.compose.ui.geometry.Size(outX - inX, h))
            drawLine(LOOP_COLOR, Offset(outX, 0f), Offset(outX, h), 3f)
        }
    }
    if (inX in 0f..w) drawLine(LOOP_COLOR, Offset(inX, 0f), Offset(inX, h), 3f)
}

private val cueLabelPaint = Paint().apply {
    isAntiAlias = true
    textSize = 26f
    isFakeBoldText = true
    color = android.graphics.Color.BLACK
    textAlign = Paint.Align.CENTER
}

/** Hot cue markers in their slot colour; with [labels] a numbered tag at the top. */
private inline fun DrawScope.drawHotCues(deck: Deck, w: Float, h: Float, labels: Boolean, toX: (Double) -> Float) {
    deck.hotCues.forEachIndexed { slot, cue ->
        if (cue == null) return@forEachIndexed
        val x = toX(cue)
        if (x < -20f || x > w + 20f) return@forEachIndexed
        val color = CUE_COLORS[slot]
        drawLine(color, Offset(x, 0f), Offset(x, h), if (labels) 3f else 2f)
        if (labels) {
            val tag = 30f
            drawRect(color, Offset(x, 0f), androidx.compose.ui.geometry.Size(tag, tag))
            drawIntoCanvas { it.nativeCanvas.drawText("${slot + 1}", x + tag / 2, tag - 7f, cueLabelPaint) }
        }
    }
}
