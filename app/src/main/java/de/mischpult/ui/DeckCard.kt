package de.mischpult.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import de.mischpult.DeckUi
import de.mischpult.audio.Camelot
import de.mischpult.audio.Deck
import de.mischpult.audio.FxType
import de.mischpult.audio.TrackLoader
import kotlinx.coroutines.delay

private val TEMPO_RANGES = floatArrayOf(0.08f, 0.16f, 0.5f)
private val ECHO_BEATS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 2f)
private val FLANGER_BEATS = floatArrayOf(1f, 2f, 4f, 8f, 16f)
val LOOP_COLOR = Color(0xFF4CD964)

/** Audio-thread state of a deck, polled at UI rate; changes recompose only what reads them. */
private class DeckLive {
    var playing by mutableStateOf(false)
    var elapsed by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var buffering by mutableStateOf(false)
    var tempo by mutableFloatStateOf(0f)
    var sync by mutableStateOf(false)
    var keyLock by mutableStateOf(false)
    /** The other deck's audible key, to show whether the two mix harmonically. */
    var otherKey by mutableStateOf<Int?>(null)
    var fxOn by mutableStateOf(false)
    var fxType by mutableStateOf(FxType.ECHO)
    var loopInSet by mutableStateOf(false)
    var hasLoop by mutableStateOf(false)
    var loopActive by mutableStateOf(false)
    var loopBeats by mutableStateOf<Double?>(null)
}

/**
 * One deck as a card with two pages: DECK (transport, tempo, EQ, filter, loop,
 * FX on/off) and FX (effect choice and all its settings). Swipe up/down on the
 * card, or tap the handle at its bottom, to switch.
 *
 * Both pages are always composed and stacked; the card is as tall as the DECK
 * page, so switching never moves the layout around.
 */
@Composable
fun DeckPanel(
    vm: DjViewModel,
    index: Int,
    ui: DeckUi,
    accent: Color,
    modifier: Modifier,
    /** Short screens: pages scroll, and switch only by their handles (vertical drags scroll). */
    scrollable: Boolean = false,
) {
    val deck = vm.deck(index)
    val live = remember(deck) { DeckLive() }
    LaunchedEffect(deck) {
        while (true) {
            live.playing = deck.playing
            live.buffering = deck.buffering
            live.tempo = deck.tempo
            live.sync = deck.sync
            live.keyLock = deck.keyLock
            val other = vm.deck(1 - index)
            live.otherKey = effectiveKey(vm.decks[1 - index].value.key, other.tempo, other.keyLock)
            live.fxOn = deck.fxOn
            live.fxType = deck.fxType
            live.loopInSet = deck.loopIn >= 0
            live.hasLoop = deck.hasLoop
            live.loopActive = deck.loopActive
            live.loopBeats = deck.loopBeats()
            val t = deck.track
            val rate = t?.sampleRate ?: 1
            live.elapsed = (deck.position / rate).toInt()
            live.total = (t?.expectedFrames ?: 0) / rate
            // Quick while the record moves (time, buffering); a stopped deck changes only by taps or MIDI
            pollDelay(if (deck.active) 50 else 150)
        }
    }

    var page by rememberSaveable { mutableIntStateOf(0) }
    val shown by animateFloatAsState(page.toFloat(), spring(stiffness = 600f), label = "deckPage")
    val swipe = Modifier.pointerInput(Unit) {
        var travel = 0f
        val threshold = 40.dp.toPx()
        // Knobs and sliders consume their own drags first, so only free swipes switch pages
        detectVerticalDragGestures(
            onDragStart = { travel = 0f },
            onDragEnd = {
                if (travel < -threshold) page = (page + 1).coerceAtMost(PAGES - 1)
                if (travel > threshold) page = (page - 1).coerceAtLeast(0)
            },
        ) { change, dy ->
            change.consume()
            travel += dy
        }
    }
    val scrolls = List(PAGES) { rememberScrollState() }
    /** Pages are stacked; the shown one is on top, neighbours fade and slide away. */
    fun Modifier.page(i: Int) = this
        .zIndex(if (page == i) 1f else 0f)
        .graphicsLayer {
            val d = i - shown
            alpha = (1f - kotlin.math.abs(d)).coerceIn(0f, 1f)
            translationY = d * size.height * 0.15f
        }
        .then(if (scrollable) Modifier.verticalScroll(scrolls[i]) else swipe)
        .padding(horizontal = 7.dp, vertical = 6.dp)

    Box(
        modifier
            .panel()
    ) {
        // The DECK page sets the card's height; the others fit into it
        Column(
            (if (scrollable) Modifier.matchParentSize() else Modifier).page(0),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DeckPage(deck, ui, live, accent, index, vm)
            PageHandle("CUES", up = true) { page = 1 }
        }
        Column(Modifier.matchParentSize().page(1), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PageHandle("DECK", up = false) { page = 0 }
            CuesPage(deck, ui, live, accent, index)
            if (!scrollable) Spacer(Modifier.weight(1f))
            PageHandle("FX", up = true) { page = 2 }
        }
        Column(Modifier.matchParentSize().page(2), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PageHandle("CUES", up = false) { page = 1 }
            FxPage(deck, ui, live, accent, index)
        }
    }
}

private const val PAGES = 3

/** Eight hot-cue colours, also used for the markers on the waveforms. */
val CUE_COLORS = listOf(
    Color(0xFFFF4D4D), Color(0xFFFF9F1A), Color(0xFFFFE14D), Color(0xFF4CD964),
    Color(0xFF2ED3C6), Color(0xFF3E8BFF), Color(0xFFB47CFF), Color(0xFFFF5CC8),
)

/**
 * Performance page: hot cues, auto-loops, loop roll and beat jump.
 * Hot cues: tap an empty slot to set it, tap a set one to jump, long-press to clear.
 */
@Composable
private fun CuesPage(deck: Deck, ui: DeckUi, live: DeckLive, accent: Color, index: Int) {
    val haptics = LocalHapticFeedback.current
    val loaded = ui.waveform != null
    // Hot cues change from here only, but restore on load: poll the deck cheaply
    var cues by remember(deck) { mutableStateOf(deck.hotCues) }
    LaunchedEffect(deck) {
        while (true) {
            cues = deck.hotCues
            pollDelay(200)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        DeckBadge(index, accent)
        Spacer(Modifier.width(6.dp))
        Text("CUES & LOOPS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = DjColors.text,
            modifier = Modifier.weight(1f))
        // Time and tempo stay visible while performing on this page
        Text(formatTime(live.elapsed), fontFamily = DjFonts.mono, fontSize = 12.sp, color = DjColors.text)
    }

    Text("HOT CUES · hold to clear", fontSize = 9.sp, color = DjColors.textDim)
    for (row in 0 until 2) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (col in 0 until 4) {
                val slot = row * 4 + col
                val color = CUE_COLORS[slot]
                val set = cues.getOrNull(slot) != null
                Box(
                    Modifier
                        .weight(1f)
                        .height(34.dp)
                        .padSurface()
                        .led(color, set)
                        .pointerInput(deck, slot, loaded) {
                            if (!loaded) return@pointerInput
                            detectTapGestures(
                                onPress = {
                                    // Jump/set on touch-down; a long press clears instead
                                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                    deck.hotCue(slot)
                                    cues = deck.hotCues
                                },
                                onLongPress = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    deck.clearHotCue(slot)
                                    cues = deck.hotCues
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${slot + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = if (set) color else DjColors.textDim.copy(alpha = if (loaded) 1f else 0.4f))
                }
            }
        }
    }

    Text("AUTO LOOP (beats)", fontSize = 9.sp, color = DjColors.textDim)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (b in intArrayOf(1, 2, 4, 8)) {
            val on = live.loopActive && live.loopBeats?.let { kotlin.math.abs(it - b) < 0.05 } == true
            ToggleChip("$b", on, LOOP_COLOR, Modifier.weight(1f), enabled = loaded) { deck.autoLoop(b.toDouble()) }
        }
    }

    Text("ROLL (hold)", fontSize = 9.sp, color = DjColors.textDim)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (b in doubleArrayOf(0.25, 0.5, 1.0)) {
            HoldChip(formatBeats(b), LOOP_COLOR, Modifier.weight(1f), enabled = loaded,
                onDown = { deck.rollStart(b) }, onUp = { deck.rollEnd() })
        }
    }

    Text("BEAT JUMP", fontSize = 9.sp, color = DjColors.textDim)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (b in intArrayOf(-4, -1, 1, 4)) {
            ToggleChip(if (b < 0) "◀ ${-b}" else "$b ▶", false, accent, Modifier.weight(1f), enabled = loaded) {
                deck.beatJump(b)
            }
        }
    }
}

/** Momentary button: active only while held (loop roll). */
@Composable
private fun HoldChip(text: String, color: Color, modifier: Modifier, enabled: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    var held by remember { mutableStateOf(false) }
    val c = if (enabled) color else DjColors.textDim
    Box(
        modifier
            .height(28.dp)
            .padSurface()
            .led(c, held)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    held = true
                    onDown()
                    tryAwaitRelease()
                    held = false
                    onUp()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (held) DjColors.text else DjColors.textDim)
    }
}

@Composable
private fun PageHandle(label: String, up: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (up) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null,
            tint = DjColors.textDim, modifier = Modifier.height(16.dp))
        Text(label, fontSize = 9.sp, color = DjColors.textDim, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ColumnScope.DeckPage(deck: Deck, ui: DeckUi, live: DeckLive, accent: Color, index: Int, vm: DjViewModel) {
    var rangeIdx by rememberSaveable { mutableIntStateOf(0) }
    val range = maxOf(TEMPO_RANGES[rangeIdx], kotlin.math.abs(live.tempo))
    val grid = ui.beatGrid

    // The deck's display: what is loaded, where it is, its overview
    Column(Modifier.fillMaxWidth().lcd().padding(horizontal = 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
    TitleRow(ui, accent, index)

    when {
        // Nothing playable yet: resolving / downloading
        ui.loading != null && ui.waveform == null -> {
            Text(stageText(ui.loading), fontSize = 11.sp, color = DjColors.textDim)
            LedProgress(
                progress = { ui.progress },
                modifier = Modifier.fillMaxWidth(),
                color = accent,
            )
        }
        ui.error != null && ui.waveform == null -> {
            Text(ui.error, fontSize = 11.sp, lineHeight = 14.sp, color = DjColors.error, maxLines = 4)
            // The source refused this upload: offer the search for others, checked for playability
            if (ui.refused && ui.track != null) {
                ToggleChip("OTHER UPLOADS", false, accent, Modifier.fillMaxWidth()) {
                    vm.findOtherUploads(ui.track)
                }
            }
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(live.elapsed), fontFamily = DjFonts.mono, fontSize = 12.sp, color = accent)
            Spacer(Modifier.weight(1f))
            Text("-" + formatTime((live.total - live.elapsed).coerceAtLeast(0)),
                fontFamily = DjFonts.mono, fontSize = 12.sp, color = DjColors.textDim)
        }
    }

    OverviewWaveform(deck, ui.waveform, accent, Modifier.fillMaxWidth().height(24.dp))
    }

    // Playable but still loading: say so without hiding the controls
    if (ui.waveform != null) {
        val status = when {
            live.buffering -> "Buffering…"
            ui.loading == TrackLoader.Stage.DECODING -> "Decoding ${(ui.progress * 100).toInt()} % · playable"
            ui.loading == TrackLoader.Stage.ANALYZING -> "Building waveform…"
            ui.error != null -> "Load stopped: ${ui.error}"
            else -> null
        }
        if (status != null) {
            Text(status, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (ui.error != null && !live.buffering) DjColors.error else DjColors.textDim)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DeckButton("CUE", DjColors.cue, Modifier.weight(1f)) { deck.cue() }
        DeckButton(null, accent, Modifier.weight(1f), active = live.playing) { deck.playing = !deck.playing }
    }

    // Tempo: BPM, sync, range, fader
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    grid?.let { "%.1f".format(it.bpm * (1 + live.tempo)) } ?: if (ui.track != null) "…" else "--",
                    fontFamily = DjFonts.mono, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = if (live.sync) accent else DjColors.text,
                )
                // Camelot key; green when it mixes with the other deck
                val key = effectiveKey(ui.key, live.tempo, live.keyLock)
                if (key != null) {
                    Spacer(Modifier.width(6.dp))
                    val other = live.otherKey
                    Text(
                        Camelot.label(key), fontFamily = DjFonts.mono, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (other != null && Camelot.compatible(key, other)) LOOP_COLOR else DjColors.textDim,
                    )
                }
            }
            // Tap to reset the tempo
            Text(
                "%+.1f%%".format(live.tempo * 100),
                fontFamily = DjFonts.mono, fontSize = 10.sp, color = DjColors.textDim,
                modifier = Modifier.clickable { deck.sync = false; deck.tempo = 0f },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ToggleChip("SYNC", live.sync, accent, Modifier.width(52.dp), enabled = grid != null) {
                if (deck.sync) deck.sync = false else vm.engine.engageSync(index)
            }
            // Key lock: tempo without the chipmunk effect
            ToggleChip("KEY", live.keyLock, accent, Modifier.width(52.dp)) {
                deck.keyLock = !deck.keyLock
                live.keyLock = deck.keyLock
            val other = vm.deck(1 - index)
            live.otherKey = effectiveKey(vm.decks[1 - index].value.key, other.tempo, other.keyLock)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Tap to cycle the fader range
        Text("±${(TEMPO_RANGES[rangeIdx] * 100).toInt()}", fontSize = 9.sp, color = DjColors.textDim,
            fontFamily = DjFonts.mono,
            modifier = Modifier.clickable { rangeIdx = (rangeIdx + 1) % TEMPO_RANGES.size })
        HwSlider(
            value = live.tempo.coerceIn(-range, range),
            onValueChange = { deck.sync = false; live.tempo = it; deck.tempo = it },
            valueRange = -range..range,
            default = 0f,
            centerMark = true,
            modifier = Modifier.weight(1f),
        )
    }

    EqRow(deck, accent)

    // Filter + loop
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        var filter by rememberSynced(deck) { (deck.filter + 1f) / 2f }
        Knob(filter, { filter = it; deck.filter = it * 2f - 1f }, "FILTER", DjColors.cue)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToggleChip("IN", live.loopInSet, LOOP_COLOR, Modifier.weight(1f), enabled = ui.waveform != null) {
                    deck.setLoopIn()
                }
                ToggleChip("OUT", live.hasLoop, LOOP_COLOR, Modifier.weight(1f), enabled = live.loopInSet) {
                    deck.setLoopOut()
                }
            }
            val beats = live.loopBeats
            ToggleChip(
                if (beats != null) "LOOP ${formatBeats(beats)}" else "LOOP",
                live.loopActive, LOOP_COLOR, Modifier.fillMaxWidth(), enabled = live.hasLoop,
            ) { deck.toggleLoop() }
        }
    }

    // FX on/off stays on the first page; everything else is one swipe away
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(live.fxType.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DjColors.textDim,
            modifier = Modifier.weight(1f))
        ToggleChip("FX ON", live.fxOn, accent, Modifier.width(64.dp)) { deck.fxOn = !deck.fxOn; live.fxOn = deck.fxOn }
    }
}

/** Deck letter as a small filled badge: recognisable at a glance, little space. */
@Composable
private fun DeckBadge(index: Int, accent: Color) {
    Text("AB"[index].toString(), color = accent, fontWeight = FontWeight.Bold, fontFamily = DjFonts.mono,
        fontSize = 14.sp, lineHeight = 14.sp)
}

/** Badge + title + artist in two tight lines. */
@Composable
private fun TitleRow(ui: DeckUi, accent: Color, index: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DeckBadge(index, accent)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(ui.track?.title ?: "Empty — load from Library", maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontSize = 12.sp, lineHeight = 14.sp,
                fontWeight = FontWeight.Medium, color = DjColors.text)
            if (ui.track?.artist?.isNotEmpty() == true) {
                Text(ui.track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 10.sp, lineHeight = 12.sp, color = DjColors.textDim)
            }
        }
    }
}

@Composable
private fun EqRow(deck: Deck, accent: Color) {
    // Synced: a MIDI controller may turn these too
    var trim by rememberSynced(deck) { TrimCurve.knob(deck.trim) }
    var hi by rememberSynced(deck) { EqCurve.knob(deck.eqHigh) }
    var mid by rememberSynced(deck) { EqCurve.knob(deck.eqMid) }
    var low by rememberSynced(deck) { EqCurve.knob(deck.eqLow) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Knob(trim, { trim = it; deck.trim = TrimCurve.gain(it) }, "GAIN", DjColors.textDim)
        Knob(hi, { hi = it; deck.eqHigh = EqCurve.gain(it) }, "HI", accent)
        Knob(mid, { mid = it; deck.eqMid = EqCurve.gain(it) }, "MID", accent)
        Knob(low, { low = it; deck.eqLow = EqCurve.gain(it) }, "LOW", accent)
    }
}

/** Second page: the effect itself, with room for its settings. */
@Composable
private fun FxPage(deck: Deck, ui: DeckUi, live: DeckLive, accent: Color, index: Int) {
    var type by remember(deck) { mutableStateOf(deck.fxType) }
    var amount by rememberSynced(deck) { deck.fxAmount }

    Row(verticalAlignment = Alignment.CenterVertically) {
        DeckBadge(index, accent)
        Spacer(Modifier.width(6.dp))
        Text("FX", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DjColors.text, modifier = Modifier.weight(1f))
        ToggleChip("FX ON", live.fxOn, accent, Modifier.width(64.dp)) { deck.fxOn = !deck.fxOn; live.fxOn = deck.fxOn }
    }
    Text(ui.track?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp,
        color = DjColors.textDim)

    // Effect choice
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FxType.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { t ->
                    ToggleChip(t.label, type == t, accent, Modifier.weight(1f)) { type = t; deck.fxType = t }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    // Settings of the chosen effect
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Knob(amount, { amount = it; deck.fxAmount = it }, "AMOUNT", accent, bipolar = false)
        when (type) {
            FxType.ECHO -> {
                var fb by remember(deck) { mutableFloatStateOf(deck.echoFeedback) }
                var tone by remember(deck) { mutableFloatStateOf(deck.echoTone) }
                Knob(fb, { fb = it; deck.echoFeedback = it }, "FEEDBACK", accent, default = 0.55f, bipolar = false)
                Knob(tone, { tone = it; deck.echoTone = it }, "TONE", accent, default = 0.35f, bipolar = false)
            }
            FxType.REVERB -> {
                var size by remember(deck) { mutableFloatStateOf(deck.reverbSize) }
                var damp by remember(deck) { mutableFloatStateOf(deck.reverbDamp) }
                Knob(size, { size = it; deck.reverbSize = it }, "SIZE", accent, default = 0.5f, bipolar = false)
                Knob(damp, { damp = it; deck.reverbDamp = it }, "DAMP", accent, default = 0.3f, bipolar = false)
            }
            FxType.FLANGER -> {
                var depth by remember(deck) { mutableFloatStateOf(deck.flangerDepth) }
                var fb by remember(deck) { mutableFloatStateOf(deck.flangerFeedback) }
                Knob(depth, { depth = it; deck.flangerDepth = it }, "DEPTH", accent, default = 0.6f, bipolar = false)
                Knob(fb, { fb = it; deck.flangerFeedback = it }, "FEEDBACK", accent, default = 0.6f, bipolar = false)
            }
        }
    }

    // Beat-synced timing
    when (type) {
        FxType.ECHO -> BeatChoice("TIME (beats)", ECHO_BEATS, deck.echoBeats, accent) { deck.echoBeats = it }
        FxType.FLANGER -> BeatChoice("SWEEP (beats)", FLANGER_BEATS, deck.flangerBeats, accent) { deck.flangerBeats = it }
        FxType.REVERB -> {}
    }

    // Filter character lives here too; the filter knob itself is on the first page
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var res by remember(deck) { mutableFloatStateOf(deck.filterResonance) }
        Knob(res, { res = it; deck.filterResonance = it }, "FILTER RES", DjColors.cue, default = 0.3f, bipolar = false)
    }
}

@Composable
private fun BeatChoice(label: String, options: FloatArray, initial: Float, accent: Color, onPick: (Float) -> Unit) {
    var current by remember { mutableFloatStateOf(initial) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 9.sp, color = DjColors.textDim)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            options.forEach { b ->
                ToggleChip(formatBeats(b.toDouble()), current == b, accent, Modifier.weight(1f)) {
                    current = b; onPick(b)
                }
            }
        }
    }
}

fun formatBeats(b: Double): String = when {
    kotlin.math.abs(b - 0.25) < 0.01 -> "1/4"
    kotlin.math.abs(b - 0.5) < 0.01 -> "1/2"
    kotlin.math.abs(b - 0.75) < 0.01 -> "3/4"
    kotlin.math.abs(b - Math.round(b)) < 0.05 -> Math.round(b).toString()
    else -> "%.1f".format(b)
}

@Composable
private fun DeckButton(text: String?, color: Color, modifier: Modifier, active: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .height(38.dp)
            .padSurface()
            .led(color, active)
            .pointerInput(onClick) { detectTapGestures(onPress = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) {
            Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        } else {
            Icon(if (active) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (active) "Pause" else "Play", tint = color)
        }
    }
}

fun stageText(stage: TrackLoader.Stage) = when (stage) {
    TrackLoader.Stage.RESOLVING -> "Resolving stream…"
    TrackLoader.Stage.DOWNLOADING -> "Downloading audio…"
    TrackLoader.Stage.DECODING -> "Decoding…"
    TrackLoader.Stage.ANALYZING -> "Building waveform…"
}

/** Key as heard: without key lock a tempo change shifts the pitch (rounded to semitones). */
fun effectiveKey(key: Int?, tempo: Float, keyLock: Boolean): Int? =
    if (key == null || keyLock) key else Camelot.transpose(key, Camelot.semitonesFor(tempo))
