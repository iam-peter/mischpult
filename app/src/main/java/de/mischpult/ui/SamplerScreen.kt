package de.mischpult.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.mischpult.audio.PadMode
import de.mischpult.audio.Sampler
import de.mischpult.library.PAD_COLORS
import de.mischpult.library.PadConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PAD_PALETTE = listOf(
    Color(0xFF3EC1FF), Color(0xFFFF8A2B), Color(0xFF4CD964), Color(0xFFFF5C8A),
    Color(0xFFB47CFF), Color(0xFFFFD23F), Color(0xFF2ED3C6), Color(0xFFFF6A4D),
)

private const val COLUMNS = 4

/**
 * Sample pads for a gig: 16 pads, each holding a snippet from a file or from a
 * deck's loop. Normal mode is for playing only — a tap can never open a dialog
 * mid-set. EDIT mode turns taps into "configure this pad".
 */
@Composable
fun SamplerScreen(vm: DjViewModel) {
    val controller = vm.controller
    val sampler = controller.engine.sampler
    val pads by controller.samples.pads.collectAsState()
    val busy by controller.padsBusy.collectAsState()
    var editMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var volume by remember { mutableFloatStateOf(sampler.volume) }
    var pfl by remember { mutableStateOf(sampler.pfl) }
    // Shared with MIDI performance pads, so screen and controller always agree
    val bank by controller.sampleBank.collectAsState()
    var quantize by remember { mutableFloatStateOf(sampler.quantizeBeats) }
    // Which banks have something sounding, for the dots on the bank buttons
    var bankActive by remember { mutableStateOf(List(Sampler.BANKS) { false }) }
    LaunchedEffect(Unit) {
        while (true) {
            bankActive = List(Sampler.BANKS) { b ->
                (0 until Sampler.BANK_SIZE).any { sampler.pads[b * Sampler.BANK_SIZE + it].playing }
            }
            pollDelay(150)
        }
    }

    val hotCueMode by controller.padHotCueMode.collectAsState()

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Pad mode, like the performance pads on a controller
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip("SAMPLES", !hotCueMode, DjColors.text, Modifier.weight(1f)) { controller.padHotCueMode.value = false }
            ToggleChip("HOT CUES", hotCueMode, CUE_COLORS[0], Modifier.weight(1f)) {
                controller.padHotCueMode.value = true
                editMode = false
            }
        }
        if (hotCueMode) {
            HotCuePads(vm, Modifier.weight(1f))
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VOL", fontSize = 10.sp, color = DjColors.textDim)
            HwSlider(
                value = volume,
                onValueChange = { volume = it; sampler.volume = it },
                fill = DjColors.textDim,
                modifier = Modifier.weight(1f),
            )
            IconChip(Icons.Filled.Headphones, "Pre-listen sampler", pfl, DjColors.cue) { pfl = !pfl; sampler.pfl = pfl }
            IconChip(Icons.Filled.Stop, "Stop all pads", false, DjColors.error) { sampler.stopAll() }
            ToggleChip("EDIT", editMode, DjColors.text, Modifier.width(56.dp)) { editMode = !editMode }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (b in 0 until Sampler.BANKS) {
                Box(Modifier.weight(1f)) {
                    ToggleChip("BANK ${"ABCD"[b]}", bank == b, DjColors.text, Modifier.fillMaxWidth()) {
                        controller.sampleBank.value = b
                    }
                    if (bankActive[b] && bank != b) {
                        Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(6.dp).clip(CircleShape)
                            .background(DjColors.deckA))
                    }
                }
            }
            // Quantise: off -> 1/4 -> 1/2 -> 1 beat
            ToggleChip(
                if (quantize > 0) "Q ${formatBeats(quantize.toDouble())}" else "Q OFF",
                quantize > 0, DjColors.cue, Modifier.width(64.dp),
            ) {
                val steps = floatArrayOf(0f, 0.25f, 0.5f, 1f)
                quantize = steps[(steps.indexOfFirst { it == quantize } + 1) % steps.size]
                sampler.quantizeBeats = quantize
            }
        }
        if (editMode || pads.isEmpty()) {
            Text(
                if (pads.isEmpty() && !editMode) "Tap EDIT, then a pad, to put a sample on it."
                else "Tap a pad to set its sample, mode, volume or colour. Tap EDIT again to play.",
                fontSize = 11.sp, color = DjColors.textDim,
            )
        }

        // One ticker for the whole bank: every frame while any of its pads sounds,
        // otherwise a cheap look for a start (MIDI, a quantized press) every 40 ms
        val bankPads = remember(bank) {
            List(Sampler.BANK_SIZE) { vm.controller.engine.sampler.pads[bank * Sampler.BANK_SIZE + it] }
        }
        val padTick = rememberFrameTick(active = { bankPads.any { it.playing } }, idleMs = 40) {
            bankPads.count { it.playing }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in 0 until Sampler.BANK_SIZE / COLUMNS) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (col in 0 until COLUMNS) {
                        val index = bank * Sampler.BANK_SIZE + row * COLUMNS + col
                        SamplePad(
                            index = index,
                            config = pads[index],
                            busy = index in busy,
                            editMode = editMode,
                            vm = vm,
                            tick = padTick,
                            onEdit = { editing = index },
                            modifier = Modifier.weight(1f).fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    editing?.let { index -> PadEditor(vm, index, pads[index]) { editing = null } }
}

@Composable
private fun SamplePad(
    index: Int,
    config: PadConfig?,
    busy: Boolean,
    editMode: Boolean,
    vm: DjViewModel,
    tick: androidx.compose.runtime.MutableLongState,
    onEdit: () -> Unit,
    modifier: Modifier,
) {
    val pad = vm.controller.engine.sampler.pads[index]
    val color = config?.let { PAD_PALETTE[it.color % PAD_PALETTE.size] } ?: DjColors.outline
    val haptics = LocalHapticFeedback.current
    val empty = config == null

    Box(
        modifier
            .padSurface(RoundedCornerShape(5.dp))
            .pointerInput(editMode, empty) {
                if (editMode || empty) {
                    detectTapGestures { onEdit() }
                } else {
                    // Fire on touch-down: a pad has to be as fast as a drum pad
                    detectTapGestures(onPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        pad.press()
                        tick.longValue++ // light up on this frame, not the next look
                        tryAwaitRelease()
                        pad.release()
                    })
                }
            },
    ) {
        // Lit while sounding, with the playback position as a fill from the left
        Canvas(Modifier.matchParentSize().graphicsLayer()) {
            tick.value
            // The pad's colour as a light strip along its top edge, bright while it sounds
            if (!empty) drawRect(color.copy(alpha = if (pad.playing) 1f else 0.4f), size = Size(size.width, 3.dp.toPx()))
            if (pad.playing) {
                drawRect(color.copy(alpha = 0.18f))
                drawRect(color.copy(alpha = 0.35f), Offset.Zero, Size(size.width * pad.progress, size.height))
            }
        }
        Column(Modifier.fillMaxSize().padding(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${"ABCD"[index / Sampler.BANK_SIZE]}${index % Sampler.BANK_SIZE + 1}", fontSize = 10.sp,
                    color = DjColors.textDim, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (config != null && config.followTempo && config.bpm != null) {
                    Text(if (config.keyLock) "KEY " else "SYNC ", fontSize = 8.sp, color = DjColors.cue)
                }
                if (config != null && config.mode != PadMode.ONE_SHOT) {
                    Text(if (config.mode == PadMode.LOOP) "LOOP" else "HOLD", fontSize = 8.sp, color = color)
                }
                if (editMode) Icon(Icons.Filled.Edit, null, tint = DjColors.textDim, modifier = Modifier.size(12.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(22.dp), color = color, strokeWidth = 2.dp)
                    config == null -> Icon(Icons.Filled.Add, "Assign sample", tint = DjColors.textDim)
                    else -> Text(config.name, fontSize = 11.sp, lineHeight = 13.sp, color = DjColors.text,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun PadEditor(vm: DjViewModel, index: Int, config: PadConfig?, onDismiss: () -> Unit) {
    val controller = vm.controller
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember(config) { mutableStateOf(config?.name.orEmpty()) }
    // Which decks have a loop that could become this pad's sample
    var loops by remember { mutableStateOf(listOf(false, false)) }
    LaunchedEffect(Unit) {
        while (true) {
            loops = listOf(vm.deck(0).hasLoop, vm.deck(1).hasLoop)
            pollDelay(300)
        }
    }
    fun run(block: suspend () -> String?) {
        scope.launch {
            error = block()
            if (error == null) onDismiss()
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) run { controller.assignPadFromFile(index, uri) }
    }

    DjDialog(
        onDismissRequest = onDismiss,
        confirmButton = { PadButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = if (config != null) {
            { PadButton(onClick = { controller.clearPad(index); onDismiss() }) { Text("Clear pad", color = DjColors.error) } }
        } else null,
        title = { Text("Pad ${"ABCD"[index / Sampler.BANK_SIZE]}${index % Sampler.BANK_SIZE + 1}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sample", fontSize = 12.sp, color = DjColors.textDim)
                PadButton(onClick = { pickFile.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Audio file from this device…")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in 0..1) {
                        PadButton(
                            onClick = { run { controller.assignPadFromLoop(index, d) } },
                            enabled = loops[d],
                            modifier = Modifier.weight(1f),
                        ) { Text("Loop of ${"AB"[d]}", maxLines = 1) }
                    }
                }
                if (!loops[0] && !loops[1]) {
                    Text("Set IN/OUT on a deck to grab its loop as a sample.", fontSize = 11.sp, color = DjColors.textDim)
                }
                error?.let { Text(it, fontSize = 12.sp, color = DjColors.error) }

                if (config != null) {
                    CompactField(
                        value = name,
                        onValueChange = { name = it; controller.updatePad(index) { c -> c.copy(name = it.take(40)) } },
                        label = "Name",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Mode", fontSize = 12.sp, color = DjColors.textDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PadMode.entries.forEach { m ->
                            ToggleChip(m.label, config.mode == m, DjColors.text, Modifier.weight(1f)) {
                                controller.updatePad(index) { it.copy(mode = m) }
                            }
                        }
                    }
                    Text(
                        when (config.mode) {
                            PadMode.ONE_SHOT -> "Tap plays the whole sample; tapping again restarts it."
                            PadMode.HOLD -> "Plays while you hold the pad."
                            PadMode.LOOP -> "Tap starts looping, tap again stops."
                        },
                        fontSize = 11.sp, color = DjColors.textDim,
                    )
                    PadTempo(config) { transform -> controller.updatePad(index, transform) }
                    Text("Volume", fontSize = 12.sp, color = DjColors.textDim)
                    var vol by remember(config.index) { mutableFloatStateOf(config.volume) }
                    HwSlider(value = vol, fill = DjColors.deckA, onValueChange = { vol = it; controller.updatePad(index) { c -> c.copy(volume = it) } },
                        modifier = Modifier.height(28.dp))
                    Text("Colour", fontSize = 12.sp, color = DjColors.textDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (c in 0 until PAD_COLORS) {
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(PAD_PALETTE[c])
                                    .border(2.dp, if (config.color == c) DjColors.text else Color.Transparent, CircleShape)
                                    .clickable { controller.updatePad(index) { it.copy(color = c) } },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun IconChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    active: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 40.dp, height = 28.dp)
            .padSurface()
            .led(color, active)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (active) DjColors.text else DjColors.textDim, modifier = Modifier.size(16.dp))
    }
}

/**
 * Tempo of a pad: its own BPM (from the deck it came from, detected, or typed),
 * whether it follows the playing deck's tempo, and whether it keeps its pitch
 * while doing so (key lock) or speeds up like a record.
 */
@Composable
private fun PadTempo(config: PadConfig, update: ((PadConfig) -> PadConfig) -> Unit) {
    var bpmText by remember(config.index) { mutableStateOf(config.bpm?.let { "%.1f".format(it) }.orEmpty()) }
    val known = config.bpm != null
    Text("Tempo", fontSize = 12.sp, color = DjColors.textDim)
    CompactField(
        value = bpmText,
        onValueChange = { text ->
            bpmText = text
            val v = text.replace(',', '.').toDoubleOrNull()?.takeIf { it in 40.0..250.0 }
            update { it.copy(bpm = v) }
        },
        label = "Sample BPM",
        placeholder = "unknown",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ToggleChip("FOLLOW TEMPO", config.followTempo && known, DjColors.cue, Modifier.weight(1f), enabled = known) {
            update { it.copy(followTempo = !it.followTempo) }
        }
        ToggleChip("KEY LOCK", config.keyLock, DjColors.cue, Modifier.weight(1f),
            enabled = known && config.followTempo) {
            update { it.copy(keyLock = !it.keyLock) }
        }
    }
    Text(
        when {
            !known -> "Enter the sample's BPM to let it follow the decks."
            !config.followTempo -> "Plays at its own tempo."
            config.keyLock -> "Plays at the leading deck's tempo and keeps its pitch."
            else -> "Plays at the leading deck's tempo; the pitch follows, like a record."
        },
        fontSize = 11.sp, color = DjColors.textDim,
    )
}

/**
 * Hot cue pad mode: both decks' 8 hot cues as big pads in their cue colours.
 * Tap an empty pad to set a cue at the playhead, a set one to jump there
 * (on touch-down), hold to clear — the same as on the deck's CUES page.
 */
@Composable
private fun HotCuePads(vm: DjViewModel, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (d in 0..1) {
            val ui by vm.decks[d].collectAsState()
            val deck = vm.deck(d)
            val accent = if (d == 0) DjColors.deckA else DjColors.deckB
            // Cues change from here, the CUES page or a MIDI controller: follow the deck
            var cues by remember(deck) { mutableStateOf(deck.hotCues) }
            LaunchedEffect(deck) {
                while (true) {
                    cues = deck.hotCues
                    pollDelay(150)
                }
            }
            val loaded = ui.waveform != null
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ABCD"[d].toString(), color = accent, fontWeight = FontWeight.Black, fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text(ui.track?.title ?: "Empty — load a track to use its hot cues", fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = if (loaded) DjColors.text else DjColors.textDim)
            }
            for (row in 0 until 2) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (col in 0 until 4) {
                        val slot = row * 4 + col
                        HotCuePad(deck, slot, cues.getOrNull(slot), loaded, ui.track?.let { deck.track?.sampleRate },
                            Modifier.weight(1f).fillMaxSize()) { cues = deck.hotCues }
                    }
                }
            }
        }
    }
}

@Composable
private fun HotCuePad(
    deck: de.mischpult.audio.Deck,
    slot: Int,
    cue: Double?,
    enabled: Boolean,
    sampleRate: Int?,
    modifier: Modifier,
    onChanged: () -> Unit,
) {
    val color = CUE_COLORS[slot]
    val haptics = LocalHapticFeedback.current
    val set = cue != null
    Box(
        modifier
            .padSurface(RoundedCornerShape(5.dp))
            .background(if (set) color.copy(alpha = 0.14f) else Color.Transparent)
            .led(color, set, 6.dp)
            .pointerInput(deck, slot, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        deck.hotCue(slot)
                        onChanged()
                    },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        deck.clearHotCue(slot)
                        onChanged()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${slot + 1}", fontSize = 20.sp, fontWeight = FontWeight.Black,
                color = if (set) color else DjColors.textDim.copy(alpha = if (enabled) 1f else 0.4f))
            Text(
                when {
                    cue != null && sampleRate != null -> formatTime((cue / sampleRate).toInt())
                    enabled -> "tap to set"
                    else -> ""
                },
                fontSize = 10.sp, color = if (set) DjColors.text else DjColors.textDim,
            )
        }
    }
}
