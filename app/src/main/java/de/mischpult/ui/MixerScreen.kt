package de.mischpult.ui

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.mischpult.DeckUi
import de.mischpult.audio.Deck
import de.mischpult.audio.FxType
import de.mischpult.audio.MixerEngine
import de.mischpult.audio.OutputMode
import de.mischpult.audio.TrackLoader
import kotlinx.coroutines.delay

@Composable
fun MixerScreen(vm: DjViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
        // Wide screens (landscape phones, tablets): decks left and right at full height
        if (maxWidth > maxHeight && maxWidth >= 600.dp) {
            WideMixer(vm, deckWidth = (maxWidth * 0.28f).coerceIn(240.dp, 380.dp),
                // Short screens (landscape phones) scroll the deck cards; tablets fit them whole
                scrollDecks = maxHeight < 560.dp) { showSettings = true }
        } else {
            TallMixer(vm) { showSettings = true }
        }
    }
    var showMidi by remember { mutableStateOf(false) }
    if (showSettings) OutputSettingsDialog(vm.engine, onMidi = { showSettings = false; showMidi = true }) {
        showSettings = false
    }
    if (showMidi) MidiDialog(vm.controller.midi) { showMidi = false }
}

/** Both scrolling waveforms stacked (the classic layout for beatmatching by eye), with zoom. */
@Composable
internal fun Waveforms(vm: DjViewModel, modifier: Modifier) {
    val zoom by vm.zoomSeconds.collectAsState()
    val deckA by vm.decks[0].collectAsState()
    val deckB by vm.decks[1].collectAsState()
    val zoomBy: (Float) -> Unit = { f -> vm.zoomSeconds.value = (vm.zoomSeconds.value * f).coerceIn(1f, 60f) }
    Box(modifier.panel().padding(5.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ScrollingWaveform(vm.deck(0), deckA.waveform, zoom, DjColors.deckA,
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(3.dp)), onZoom = zoomBy)
            ScrollingWaveform(vm.deck(1), deckB.waveform, zoom, DjColors.deckB,
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(3.dp)), onZoom = zoomBy)
        }
        Column(Modifier.align(Alignment.CenterEnd)) {
            IconButton(onClick = { zoomBy(1 / 1.5f) }) {
                Icon(Icons.Filled.ZoomIn, "Zoom in", tint = DjColors.textDim)
            }
            IconButton(onClick = { zoomBy(1.5f) }) {
                Icon(Icons.Filled.ZoomOut, "Zoom out", tint = DjColors.textDim)
            }
        }
    }
}

/** Portrait phones: waveforms on top, decks and mixer below, sized to their controls. */
@Composable
private fun TallMixer(vm: DjViewModel, onSettings: () -> Unit) {
    val deckA by vm.decks[0].collectAsState()
    val deckB by vm.decks[1].collectAsState()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Waveforms(vm, Modifier.fillMaxWidth().weight(1f).heightIn(min = 120.dp))
        // Decks size to their controls; the channel strip stretches to match them
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DeckPanel(vm, 0, deckA, DjColors.deckA, Modifier.weight(1f).fillMaxHeight())
            ChannelStrip(vm, onSettings, Modifier.width(92.dp).fillMaxHeight())
            DeckPanel(vm, 1, deckB, DjColors.deckB, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** Landscape and tablets: deck A | waveforms over the mixer | deck B. */
@Composable
private fun WideMixer(vm: DjViewModel, deckWidth: Dp, scrollDecks: Boolean, onSettings: () -> Unit) {
    val deckA by vm.decks[0].collectAsState()
    val deckB by vm.decks[1].collectAsState()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DeckPanel(vm, 0, deckA, DjColors.deckA, Modifier.width(deckWidth).fillMaxHeight(), scrollable = scrollDecks)
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Waveforms(vm, Modifier.fillMaxWidth().weight(1f))
            ChannelStrip(vm, onSettings, Modifier.fillMaxWidth().height(if (scrollDecks) 190.dp else 300.dp))
        }
        DeckPanel(vm, 1, deckB, DjColors.deckB, Modifier.width(deckWidth).fillMaxHeight(), scrollable = scrollDecks)
    }
}

@Composable
private fun ChannelStrip(vm: DjViewModel, onSettings: () -> Unit, modifier: Modifier) {
    val a = vm.deck(0)
    val b = vm.deck(1)
    // Synced: a MIDI controller may move these too
    var volA by rememberSynced(a) { a.volume }
    var volB by rememberSynced(b) { b.volume }
    var xfade by rememberSynced(Unit) { vm.engine.crossfader }
    var pflA by remember { mutableStateOf(a.pfl) }
    var pflB by remember { mutableStateOf(b.pfl) }

    Column(
        modifier
            .panel()
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RecordButton(vm)
        Spacer(Modifier.height(6.dp))
        // Headphone pre-listen per channel (needs split output, see settings)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            PflButton(pflA, DjColors.deckA) { pflA = !pflA; a.pfl = pflA }
            PflButton(pflB, DjColors.deckB) { pflB = !pflB; b.pfl = pflB }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            VerticalFader(volA, { volA = it; a.volume = it }, DjColors.deckA, a)
            VerticalFader(volB, { volB = it; b.volume = it }, DjColors.deckB, b)
        }
        Spacer(Modifier.height(6.dp))
        HwSlider(
            value = xfade,
            onValueChange = { xfade = it; vm.engine.crossfader = it },
            valueRange = -1f..1f,
            default = 0f,
            centerMark = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("A ◂X▸ B", fontSize = 10.sp, color = DjColors.textDim,
                modifier = Modifier.clickable { xfade = 0f; vm.engine.crossfader = 0f })
            IconButton(onClick = onSettings, modifier = Modifier.width(32.dp).height(32.dp)) {
                Icon(Icons.Filled.Settings, "Output settings", tint = DjColors.textDim)
            }
        }
    }
}

/** Records the master to Music/Mischpult, with a set list; shows the running time. */
@Composable
private fun RecordButton(vm: DjViewModel) {
    val state by vm.controller.recording.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val on = state != null
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padSurface()
            .clickable {
                if (on) {
                    scope.launch {
                        val set = vm.controller.stopRecording()
                        Toast.makeText(context,
                            if (set != null) "Saved “${set.name}” to Music/Mischpult · see Library → Sets"
                            else "Recording failed", Toast.LENGTH_LONG).show()
                    }
                } else {
                    vm.controller.startRecording()?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                }
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) DjColors.error else DjColors.ledOff))
        Spacer(Modifier.width(5.dp))
        Text(
            state?.let { formatTime((it.elapsedMs / 1000).toInt()) } ?: "REC",
            fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = DjFonts.mono,
            color = if (on) DjColors.error else DjColors.textDim,
        )
    }
}

@Composable
private fun PflButton(active: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .width(34.dp).height(28.dp)
            .padSurface()
            .led(color, active)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Headphones, "Pre-listen", tint = if (active) DjColors.text else DjColors.textDim,
            modifier = Modifier.height(16.dp))
    }
}

/** Channel fader with a pre-fader level meter next to it. */
@Composable
private fun VerticalFader(value: Float, onValueChange: (Float) -> Unit, accent: Color, deck: Deck) {
    val onChange by rememberUpdatedState(onValueChange)
    val meter = remember { floatArrayOf(0f) }
    // Animate only while there is a level to show or one still falling back
    val tick = rememberFrameTick(active = { deck.level > 0.001f || meter[0] > 0.002f })
    Canvas(
        Modifier
            .graphicsLayer()
            .width(32.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange(1f - (change.position.y / size.height).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { onChange(1f - (it.y / size.height).coerceIn(0f, 1f)) }
            }
    ) {
        tick.value
        val w = size.width
        val h = size.height
        val capH = 18.dp.toPx()
        val travel = h - capH
        // Meter (fast attack, slow release)
        val lvl = deck.level
        meter[0] = if (lvl > meter[0]) lvl else meter[0] * 0.92f
        // LED meter: green, then yellow, red at the top
        val segH = 4.dp.toPx()
        val gap = 2.dp.toPx()
        val segs = ((h + gap) / (segH + gap)).toInt()
        val lit = (meter[0].coerceIn(0f, 1f) * segs).toInt()
        for (i in 0 until segs) {
            val c = when {
                i >= lit -> DjColors.ledOff
                i >= segs * 0.86f -> DjColors.error
                i >= segs * 0.65f -> DjColors.cue
                else -> METER_GREEN
            }
            drawRect(c, Offset(w - 4.dp.toPx(), h - (i + 1) * (segH + gap) + gap), Size(4.dp.toPx(), segH))
        }
        // Recessed slot + silver cap with a line in the deck's colour
        val cx = (w - 8.dp.toPx()) / 2
        drawRoundRect(DjColors.lcd, Offset(cx - 2.5.dp.toPx(), capH / 2), Size(5.dp.toPx(), h - capH),
            CornerRadius(1.dp.toPx()))
        val y = (1f - value) * travel
        drawRoundRect(DjColors.cap, Offset(cx - 11.dp.toPx(), y), Size(22.dp.toPx(), capH), CornerRadius(2.dp.toPx()))
        drawRect(DjColors.capShade, Offset(cx - 11.dp.toPx(), y + capH - 4.dp.toPx()), Size(22.dp.toPx(), 4.dp.toPx()))
        drawLine(accent, Offset(cx - 9.dp.toPx(), y + capH / 2 - 1.dp.toPx()), Offset(cx + 9.dp.toPx(), y + capH / 2 - 1.dp.toPx()), 2.dp.toPx())
    }
}

@Composable
private fun OutputSettingsDialog(engine: MixerEngine, onMidi: () -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(engine.outputMode) }
    var master by remember { mutableFloatStateOf(engine.masterVolume) }
    var phones by remember { mutableFloatStateOf(engine.headphoneVolume) }
    var cueMix by remember { mutableFloatStateOf(engine.cueMix) }

    DjDialog(
        onDismissRequest = onDismiss,
        confirmButton = { PadButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Output") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleChip("STEREO", mode == OutputMode.STEREO, DjColors.deckA, Modifier.weight(1f).height(34.dp)) {
                        mode = OutputMode.STEREO; engine.outputMode = mode
                    }
                    ToggleChip("SPLIT · CUE", mode == OutputMode.SPLIT, DjColors.deckA, Modifier.weight(1f).height(34.dp)) {
                        mode = OutputMode.SPLIT; engine.outputMode = mode
                    }
                }
                Text(
                    if (mode == OutputMode.SPLIT)
                        "Use a splitter cable: the left channel carries the master (mono) for the speakers, " +
                            "the right channel your headphone mix. Pick channels with the headphone buttons."
                    else
                        "Normal stereo output. Switch to split to pre-listen on headphones.",
                    fontSize = 12.sp, color = DjColors.textDim,
                )
                LabeledSlider("Master", master) { master = it; engine.masterVolume = it }
                PadButton(onClick = onMidi) { Text("MIDI controllers…") }
                if (mode == OutputMode.SPLIT) {
                    LabeledSlider("Headphones", phones) { phones = it; engine.headphoneVolume = it }
                    LabeledSlider("Headphone mix  (cue ◂ ▸ master)", cueMix) { cueMix = it; engine.cueMix = it }
                }
            }
        },
    )
}

@Composable
private fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Text(label, fontSize = 12.sp, color = DjColors.text)
        HwSlider(value = value, onValueChange = onChange, fill = DjColors.deckA)
    }
}

fun formatTime(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

/** The green of a level meter's lower segments. */
private val METER_GREEN = Color(0xFF8BD17C)
