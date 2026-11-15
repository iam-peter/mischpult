package de.mischpult.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.mischpult.midi.MidiAction
import de.mischpult.midi.MidiController

/**
 * MIDI controllers: connect a USB or Bluetooth controller, then map it by
 * MIDI learn — tap Learn next to a function and touch the control.
 */
@Composable
fun MidiDialog(midi: MidiController, onDismiss: () -> Unit) {
    val devices by midi.devices.collectAsState()
    val bindings by midi.bindings.collectAsState()
    val learning by midi.learning.collectAsState()
    val lastEvent by midi.lastEvent.collectAsState()
    val scanning by midi.scanning.collectAsState()
    val ledValue by midi.ledValue.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        message = if (granted.values.all { it }) midi.scanBluetooth() else "Bluetooth permission denied"
    }

    Dialog(
        onDismissRequest = { midi.learn(null); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(DjColors.background).safeDrawingPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MIDI controllers", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DjColors.text,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = { midi.learn(null); onDismiss() }) {
                    Icon(Icons.Filled.Close, "Close", tint = DjColors.textDim)
                }
            }
            if (!midi.supported) {
                Text("This device has no MIDI support.", color = DjColors.error, fontSize = 13.sp)
                return@Column
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    Text("Devices", fontSize = 12.sp, color = DjColors.textDim, modifier = Modifier.padding(top = 4.dp))
                    if (devices.isEmpty()) {
                        Text("Plug in a USB controller, or scan for a Bluetooth one.", fontSize = 12.sp,
                            color = DjColors.textDim)
                    }
                }
                items(devices, key = { it.id }) { d ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(DjColors.surface)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontSize = 14.sp, color = DjColors.text)
                            Text(if (d.bluetooth) "Bluetooth" else "USB", fontSize = 11.sp, color = DjColors.textDim)
                        }
                        if (d.connected) {
                            PadButton(onClick = { midi.disconnect(d.id) }) { Text("Disconnect", color = DjColors.textDim) }
                        } else {
                            PadButton(onClick = { midi.connect(d.id) }) { Text("Connect") }
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PadButton(onClick = { permissions.launch(midi.bluetoothPermissions()) }, enabled = !scanning) {
                            Text(if (scanning) "Scanning…" else "Scan Bluetooth")
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(lastEvent?.let { "Last: $it" } ?: "No MIDI received yet", fontSize = 11.sp,
                            color = DjColors.textDim, fontFamily = DjFonts.mono)
                    }
                    message?.let { Text(it, fontSize = 12.sp, color = DjColors.error) }
                    // Buttons light up to show play, sync, loops, set hot cues, sounding pads…
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("LED on value", fontSize = 12.sp, color = DjColors.textDim, modifier = Modifier.weight(1f))
                        for (v in intArrayOf(127, 64, 1)) {
                            ToggleChip("$v", ledValue == v, DjColors.cue, Modifier.width(48.dp)) { midi.setLedValue(v) }
                        }
                    }
                    Text("Most controllers light buttons at 127; try 1 or 64 if yours stays dark or shows a different colour.",
                        fontSize = 10.sp, color = DjColors.textDim)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                        Text("Mapping · tap Learn, then touch the control", fontSize = 12.sp, color = DjColors.textDim,
                            modifier = Modifier.weight(1f))
                        PadButton(onClick = { midi.clearAll() }, enabled = bindings.isNotEmpty()) {
                            Text("Clear all", color = if (bindings.isNotEmpty()) DjColors.error else DjColors.textDim)
                        }
                    }
                }
                val groups = listOf(
                    "Deck A" to MidiAction.entries.filter { it.deck == 0 },
                    "Deck B" to MidiAction.entries.filter { it.deck == 1 },
                    "Mixer & sampler" to MidiAction.entries.filter { it.deck == null },
                )
                groups.forEach { (title, actions) ->
                    item(key = title) {
                        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = when (title) { "Deck A" -> DjColors.deckA; "Deck B" -> DjColors.deckB; else -> DjColors.text },
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    items(actions, key = { it.name }) { action ->
                        val binding = bindings[action]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(action.label, fontSize = 13.sp, color = DjColors.text, modifier = Modifier.weight(1f))
                            Text(
                                if (learning == action) "touch a control…" else binding?.toString() ?: "—",
                                fontSize = 11.sp, fontFamily = DjFonts.mono,
                                color = if (learning == action) DjColors.cue else DjColors.textDim,
                            )
                            Spacer(Modifier.width(6.dp))
                            ToggleChip(if (learning == action) "…" else "LEARN", learning == action, DjColors.cue,
                                Modifier.width(64.dp)) { midi.learn(if (learning == action) null else action) }
                            IconButton(onClick = { midi.clear(action) }, enabled = binding != null) {
                                Icon(Icons.Filled.Close, "Clear", tint = if (binding != null) DjColors.textDim else DjColors.outline)
                            }
                        }
                    }
                }
            }
        }
    }
}
