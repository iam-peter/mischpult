package de.mischpult.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import de.mischpult.library.RecordedSet
import de.mischpult.library.SetEntry
import de.mischpult.DeckUi
import de.mischpult.library.TrackMeta
import de.mischpult.audio.Camelot
import de.mischpult.audio.TrackLoader
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.OfflinePin
import de.mischpult.library.Crate
import de.mischpult.library.CrateStore
import de.mischpult.library.Track
import de.mischpult.library.TrackSource

/** Per-track BPM/key and the keys of what is playing, shared by all track rows. */
private val LocalTrackMeta = compositionLocalOf { emptyMap<String, TrackMeta>() }
private val LocalReferenceKeys = compositionLocalOf { emptySet<Int>() }

@Composable
fun LibraryScreen(vm: DjViewModel, onOpenDecks: () -> Unit) {
    val meta by vm.controller.trackMeta.meta.collectAsState()
    CompositionLocalProvider(
        LocalTrackMeta provides meta,
        LocalReferenceKeys provides rememberReferenceKeys(vm),
    ) {
        LibraryContent(vm, onOpenDecks)
    }
}

@Composable
private fun LibraryContent(vm: DjViewModel, onOpenDecks: () -> Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    val ui by vm.library.collectAsState()
    // Sent here from a deck to look for other uploads: that happens in the online search
    val jumpToSearch by vm.jumpToSearch.collectAsState()
    LaunchedEffect(jumpToSearch) {
        if (jumpToSearch) {
            section = 0
            vm.jumpToSearch.value = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        DeckStatusBar(vm, onOpenDecks)
        // The online tab is named after the installed source plugin
        val plugins by vm.controller.plugins.plugins.collectAsState()
        val online = plugins.firstOrNull()?.name ?: "Online"
        PadTabs(listOf(online, "Crates", "Files", "Sets"), section, height = 30.dp) { section = it }
        ui.message?.let {
            Text(it, color = DjColors.textDim, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).clickable { vm.clearMessage() })
        }
        when (section) {
            0 -> SearchSection(vm)
            1 -> CratesSection(vm)
            2 -> FilesSection(vm)
            else -> SetsSection(vm)
        }
    }
}

@Composable
private fun SearchSection(vm: DjViewModel) {
    val ui by vm.library.collectAsState()
    val crates by vm.crates.crates.collectAsState()
    val decoded by vm.cache.decoded.collectAsState()
    val decks = deckStates(vm)
    val collectionIds = remember(crates) {
        crates.firstOrNull { it.name == CrateStore.COLLECTION }?.tracks?.mapTo(HashSet()) { it.id } ?: emptySet()
    }
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    val plugins by vm.controller.plugins.plugins.collectAsState()
    val source = plugins.firstOrNull()
    if (source == null) {
        NoOnlineSource()
        return
    }

    // Infinite scroll
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, ui.hasMore) { if (nearEnd && ui.hasMore) vm.loadMore() }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        CompactField(
            value = ui.query,
            onValueChange = vm::setQuery,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            placeholder = "Search ${source.name}, or paste a link",
            leadingIcon = Icons.Filled.Search,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }),
        )
        // The source's own search filters
        if (source.filters.size > 1) {
            val current = ui.filter?.takeIf { f -> source.filters.any { it.first == f } } ?: source.filters.first().first
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((key, label) in source.filters) {
                    ToggleChip(label.uppercase(), current == key, DjColors.deckA, Modifier.weight(1f)) { vm.setFilter(key) }
                }
            }
        } else {
            Spacer(Modifier.height(6.dp))
        }
        if (ui.searching) LedProgress(Modifier.fillMaxWidth())
        ui.alternativesFor?.let { title -> AlternativesHeader(title, ui.checks, ui.searching) { vm.exitAlternatives() } }
        // Looking for other uploads: the ones that play first, as the checks come in
        val shown = if (ui.alternativesFor == null) ui.results else remember(ui.results, ui.checks) {
            ui.results.sortedBy { t -> when (ui.checks[t.id]?.plays) { true -> 0; null -> 1; false -> 2 } }
        }
        // While results reorder, stay at the top, where the ones that play arrive, until the list is
        // scrolled by hand (a lazy list otherwise follows whichever row was on top as it moves down)
        var scrolledByHand by remember(ui.alternativesFor) { mutableStateOf(false) }
        LaunchedEffect(listState, ui.alternativesFor) {
            snapshotFlow { listState.isScrollInProgress }.collect { if (it) scrolledByHand = true }
        }
        val order = remember(shown) { shown.map { it.id } }
        LaunchedEffect(order) {
            if (ui.alternativesFor != null && !scrolledByHand) listState.scrollToItem(0)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    decodedBytes = decoded[track.id],
                    decks = decks,
                    status = ui.checks[track.id],
                    onLoad = { deck -> vm.loadToDeck(deck, track) },
                    trailing = {
                        val inCollection = track.id in collectionIds
                        IconButton(onClick = {
                            if (inCollection) vm.controller.removeFromCrate(CrateStore.COLLECTION, track.id)
                            else vm.crates.addTracks(CrateStore.COLLECTION, listOf(track))
                        }) {
                            Icon(
                                if (inCollection) Icons.Filled.Check else Icons.Filled.Add,
                                if (inCollection) "In collection" else "Add to collection",
                                tint = if (inCollection) DjColors.deckA else DjColors.textDim,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun CratesSection(vm: DjViewModel) {
    val crates by vm.crates.crates.collectAsState()
    val decoded by vm.cache.decoded.collectAsState()
    val decks = deckStates(vm)
    var selected by rememberSaveable { mutableStateOf(CrateStore.COLLECTION) }
    val crate = crates.firstOrNull { it.name == selected } ?: crates.first()
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        StorageBar(vm)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            items(crates, key = { it.name }) { c ->
                ToggleChip("${c.name} (${c.tracks.size})", c.name == crate.name, DjColors.deckA,
                    Modifier.padding(start = 0.dp), icon = if (c.offline) Icons.Filled.OfflinePin else null) {
                    selected = c.name
                }
            }
        }
        if (crate.tracks.isNotEmpty()) OfflineBar(vm, crate, decoded)
        if (crate.tracks.isEmpty()) {
            Text(
                if (crate.name == CrateStore.COLLECTION)
                    "Your record collection is empty. Import files in the Files tab, or, with an online source " +
                        "installed, add tracks with + in its tab or paste a playlist link there to import it as a crate."
                else "This crate is empty.",
                color = DjColors.textDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp),
            )
        }
        if (crate.name != CrateStore.COLLECTION) {
            Text("Delete crate", color = DjColors.error, fontSize = 12.sp,
                modifier = Modifier.clickable { confirmDelete = true }.padding(4.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(crate.tracks, key = { it.id }) { track ->
                TrackRow(track, decoded[track.id], decks, onLoad = { vm.loadToDeck(it, track) }, trailing = {
                    TrackMenu(vm, crate.name, track, decoded[track.id])
                })
            }
        }
    }

    if (confirmDelete) {
        DjDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${crate.name}”?") },
            text = { Text("The crate goes, and so does the decoded audio of tracks that aren't in any other crate.") },
            confirmButton = {
                PadButton(onClick = {
                    vm.controller.deleteCrate(crate.name)
                    selected = CrateStore.COLLECTION
                    confirmDelete = false
                }) { Text("Delete", color = DjColors.error) }
            },
            dismissButton = { PadButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FilesSection(vm: DjViewModel) {
    val crates by vm.crates.crates.collectAsState()
    val decoded by vm.cache.decoded.collectAsState()
    val decks = deckStates(vm)
    val local = crates.firstOrNull { it.name == CrateStore.LOCAL }?.tracks.orEmpty()

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importFiles(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importFolder(uri)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadButton(onClick = { pickFiles.launch(arrayOf("audio/*")) }) {
                Icon(Icons.Filled.AudioFile, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add files")
            }
            PadButton(onClick = { pickFolder.launch(null) }) {
                Icon(Icons.Filled.CreateNewFolder, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add folder")
            }
        }
        if (local.isEmpty()) {
            Text(
                "MP3, M4A, FLAC, WAV, OGG… from this device. Files stay where they are; Mischpult only keeps " +
                    "permission to read them.",
                color = DjColors.textDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(local, key = { it.id }) { track ->
                TrackRow(track, decoded[track.id], decks, onLoad = { vm.loadToDeck(it, track) }, trailing = {
                    TrackMenu(vm, CrateStore.LOCAL, track, decoded[track.id])
                })
            }
        }
    }
}

/**
 * Offline readiness of a crate: how many of its tracks are decoded, and the
 * button that prepares the rest (download + decode ahead of a gig).
 */
@Composable
private fun OfflineBar(vm: DjViewModel, crate: Crate, decoded: Map<String, Long>) {
    val prep by vm.controller.prep.collectAsState()
    val ready = crate.tracks.count { it.id in decoded }
    val total = crate.tracks.size
    val runningHere = prep?.running == true && prep?.crate == crate.name
    val busyElsewhere = prep?.running == true && prep?.crate != crate.name
    val lastRun = prep?.takeIf { it.crate == crate.name && !it.running }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DjColors.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (crate.offline && ready == total) Icons.Filled.OfflinePin else Icons.Filled.CloudDownload,
                null, tint = if (crate.offline && ready == total) DjColors.deckA else DjColors.textDim,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        runningHere -> "Preparing for offline… ${prep!!.done}/$total"
                        crate.offline && ready == total -> "Ready offline · $total tracks"
                        crate.offline -> "Offline: $ready of $total ready"
                        else -> "$ready of $total decoded · not kept offline"
                    },
                    fontSize = 12.sp, color = DjColors.text,
                )
                val detail = when {
                    runningHere -> prep!!.current.firstOrNull()?.let { "Now: $it" }
                    busyElsewhere -> "Busy preparing “${prep!!.crate}”"
                    !crate.offline -> "Download + decode everything now, so the gig needs no network"
                    else -> null
                }
                detail?.let {
                    Text(it, fontSize = 10.sp, color = DjColors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                runningHere -> PadButton(onClick = { vm.controller.cancelPrep() }) { Text("Cancel") }
                !crate.offline -> PadButton(onClick = { vm.controller.prepareCrate(crate.name) }, enabled = !busyElsewhere) {
                    Text("Make offline")
                }
                ready < total -> PadButton(onClick = { vm.controller.prepareCrate(crate.name) }, enabled = !busyElsewhere) {
                    Text("Prepare missing")
                }
                else -> PadButton(onClick = { vm.controller.setOffline(crate.name, false) }) {
                    Text("Stop keeping", color = DjColors.textDim)
                }
            }
        }
        if (runningHere) {
            LedProgress(
                progress = { prep!!.done.toFloat() / total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
                color = DjColors.deckA,
            )
        }
        val failed = (if (runningHere) prep?.failed else lastRun?.failed).orEmpty()
        if (failed.isNotEmpty()) {
            Text(
                "${failed.size} failed: " + failed.take(3).joinToString("; ") { (t, why) -> "${t.title} (${why.take(40)})" } +
                    if (failed.size > 3) " …" else "",
                fontSize = 10.sp, color = DjColors.error, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** How much space decoded audio takes, with a way to free all of it. */
@Composable
private fun StorageBar(vm: DjViewModel) {
    val usage by vm.cache.usage.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Decoded audio: ${formatBytes(usage.bytes)} · ${usage.tracks} track${if (usage.tracks == 1) "" else "s"}",
            fontSize = 12.sp, color = DjColors.textDim, modifier = Modifier.weight(1f),
        )
        PadButton(onClick = { confirm = true }, enabled = usage.tracks > 0) { Text("Clear all") }
    }
    if (confirm) {
        DjDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear all decoded audio?") },
            text = {
                Text("Frees ${formatBytes(usage.bytes)}. Your crates stay as they are; tracks get decoded " +
                    "(and online tracks downloaded) again the next time you load them. Loaded decks keep playing. " +
                    "Offline crates will need preparing again before a gig.")
            },
            confirmButton = {
                PadButton(onClick = { vm.controller.clearAllDecoded(); confirm = false }) {
                    Text("Clear", color = DjColors.error)
                }
            },
            dismissButton = { PadButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** Per-track actions inside a crate. */
@Composable
private fun TrackMenu(vm: DjViewModel, crateName: String, track: Track, decodedBytes: Long?) {
    var open by remember { mutableStateOf(false) }
    val crates by vm.crates.crates.collectAsState()
    val inCollection = crates.firstOrNull { it.name == CrateStore.COLLECTION }?.tracks?.any { it.id == track.id } == true
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More", tint = DjColors.textDim) }
        HwMenu(expanded = open, onDismissRequest = { open = false }) {
            if (crateName != CrateStore.COLLECTION && !inCollection) {
                DropdownMenuItem(
                    text = { Text("Add to ${CrateStore.COLLECTION}") },
                    onClick = { vm.crates.addTracks(CrateStore.COLLECTION, listOf(track)); open = false },
                )
            }
            if (decodedBytes != null) {
                DropdownMenuItem(
                    text = { Text("Clear decoded audio (${formatBytes(decodedBytes)})") },
                    onClick = { vm.controller.clearDecoded(track.id); open = false },
                )
            }
            DropdownMenuItem(
                text = { Text("Remove from “$crateName”", color = DjColors.error) },
                onClick = { vm.controller.removeFromCrate(crateName, track.id); open = false },
            )
        }
    }
}

/** Keys the library compares against: what the playing decks sound like right now. */
@Composable
private fun rememberReferenceKeys(vm: DjViewModel): Set<Int> {
    var keys by remember { mutableStateOf(emptySet<Int>()) }
    LaunchedEffect(Unit) {
        while (true) {
            keys = (0..1).filter { vm.deck(it).playing }.mapNotNull { i ->
                val d = vm.deck(i)
                effectiveKey(vm.decks[i].value.key, d.tempo, d.keyLock)
            }.toSet()
            pollDelay(400)
        }
    }
    return keys
}

@Composable
private fun deckStates(vm: DjViewModel): List<DeckUi> {
    val a by vm.decks[0].collectAsState()
    val b by vm.decks[1].collectAsState()
    // Same list instance until a deck changes, so track rows can skip recomposing
    return remember(a, b) { listOf(a, b) }
}

/**
 * What's on the decks, always visible in the library: answers "did that load?"
 * without switching tabs. Tap to go to the decks.
 */
@Composable
private fun DeckStatusBar(vm: DjViewModel, onOpenDecks: () -> Unit) {
    val decks = deckStates(vm)
    var playing by remember { mutableStateOf(listOf(false, false)) }
    LaunchedEffect(Unit) {
        while (true) {
            playing = listOf(vm.deck(0).playing, vm.deck(1).playing)
            pollDelay(250)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        decks.forEachIndexed { i, ui ->
            val color = if (i == 0) DjColors.deckA else DjColors.deckB
            Column(
                Modifier
                    .weight(1f)
                    .lcd()
                    .clickable(onClick = onOpenDecks)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AB"[i].toString(), color = color, fontSize = 14.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(6.dp))
                    Text(ui.track?.title ?: "empty", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp, color = if (ui.track != null) DjColors.text else DjColors.textDim,
                        modifier = Modifier.weight(1f))
                    if (playing[i]) Icon(Icons.Filled.PlayArrow, "Playing", tint = color, modifier = Modifier.size(16.dp))
                }
                val status = when {
                    ui.error != null && ui.waveform == null -> "Failed: ${ui.error}"
                    ui.loading != null && ui.waveform == null -> stageText(ui.loading)
                    ui.loading == TrackLoader.Stage.DECODING -> "Ready · decoding ${(ui.progress * 100).toInt()} %"
                    ui.track != null -> listOfNotNull(
                        "Ready",
                        ui.beatGrid?.let { "%.1f BPM".format(it.bpm) },
                        ui.key?.let { Camelot.label(it) },
                    ).joinToString(" · ")
                    else -> "Tap A or B on a track"
                }
                Text(status, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (ui.error != null && ui.waveform == null) DjColors.error else DjColors.textDim)
                if (ui.loading != null) {
                    LedProgress(
                        progress = { ui.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(2.dp),
                        color = color,
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    decodedBytes: Long?,
    decks: List<DeckUi>,
    onLoad: (Int) -> Unit,
    status: PlayCheck? = null,
    trailing: @Composable () -> Unit,
) {
    val info = LocalTrackMeta.current[track.id]
    val refKeys = LocalReferenceKeys.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (track.source == TrackSource.LOCAL_FILE) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)).background(DjColors.surfaceHigh),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.MusicNote, null, tint = DjColors.textDim)
            }
        } else {
            AsyncImage(
                model = track.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)).background(DjColors.surfaceHigh),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = DjColors.text)
            Text(
                buildAnnotatedString {
                    append(listOfNotNull(
                        track.artist.ifEmpty { null },
                        track.durationSec.takeIf { it > 0 }?.let { formatTime(it.toInt()) },
                        info?.bpm?.let { "%.0f BPM".format(it) },
                    ).joinToString(" · "))
                    // Key in Camelot notation, green when it mixes with what is playing
                    info?.key?.let { k ->
                        append(" · ")
                        val match = refKeys.any { Camelot.compatible(k, it) }
                        withStyle(SpanStyle(color = if (match) LOOP_COLOR else DjColors.textDim,
                            fontWeight = if (match) FontWeight.Bold else FontWeight.Normal)) {
                            append(Camelot.label(k))
                        }
                    }
                    if (decodedBytes != null) append(" · decoded")
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = DjColors.textDim,
            )
            // When looking for other uploads: whether the source lets this one play
            status?.let { s ->
                Text(
                    when (s.plays) {
                        null -> "Checking…"
                        true -> "✓ Plays"
                        false -> "✗ " + (s.reason ?: "Won't play")
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = when (s.plays) { null -> DjColors.textDim; true -> LOOP_COLOR; false -> DjColors.error },
                )
            }
        }
        LoadButton("A", DjColors.deckA, decks[0], track) { onLoad(0) }
        Spacer(Modifier.width(6.dp))
        LoadButton("B", DjColors.deckB, decks[1], track) { onLoad(1) }
        trailing()
    }
}

/**
 * Load-to-deck button. Filled while this track is on that deck, with a ring
 * that fills while it is still on its way (resolve/download/decode).
 */
@Composable
private fun LoadButton(label: String, color: Color, deck: DeckUi, track: Track, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val here = deck.track?.id == track.id
    val loading = here && deck.loading != null && deck.waveform == null
    val failed = here && deck.error != null && deck.waveform == null
    val pressed = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(36.dp)
            .graphicsLayer { scaleX = pressed.value; scaleY = pressed.value }
            .padSurface()
            .led(if (failed) DjColors.error else color, (here && !loading) || failed)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                scope.launch {
                    pressed.snapTo(0.8f)
                    pressed.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
                }
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                progress = { deck.progress.coerceAtLeast(0.05f) },
                modifier = Modifier.size(30.dp), color = color, strokeWidth = 2.dp, trackColor = DjColors.surfaceHigh,
            )
        }
        Text(label, color = color, fontSize = 14.sp,
            fontWeight = if (here) FontWeight.Black else FontWeight.Normal)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    else -> "%.0f MB".format(bytes / (1024.0 * 1024))
}

/** Recorded sets: the audio in Music/Mischpult plus which track played when. */
@Composable
private fun SetsSection(vm: DjViewModel) {
    val sets by vm.controller.sets.sets.collectAsState()
    val live by vm.controller.recording.collectAsState()
    var confirmDelete by remember { mutableStateOf<RecordedSet?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "Tap REC on the mixer to record the master to Music/Mischpult. Tracks are logged as they go on air.",
                color = DjColors.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
            )
        }
        live?.let { state ->
            item {
                SetCard(
                    title = "Recording · ${formatTime((state.elapsedMs / 1000).toInt())}",
                    subtitle = "${state.entries.size} track${if (state.entries.size == 1) "" else "s"} so far",
                    entries = state.entries, highlight = true, expandedInitially = true,
                ) {}
            }
        }
        items(sets, key = { it.id }) { set ->
            SetCard(
                title = set.name,
                subtitle = "${formatTime((set.durationMs / 1000).toInt())} · ${set.entries.size} tracks",
                entries = set.entries, highlight = false, expandedInitially = false,
            ) { SetActions(vm, set) { confirmDelete = set } }
        }
    }

    confirmDelete?.let { set ->
        DjDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${set.name}”?") },
            text = { Text("The recording is deleted from Music/Mischpult, together with its set list.") },
            confirmButton = {
                PadButton(onClick = { vm.controller.deleteSet(set); confirmDelete = null }) {
                    Text("Delete", color = DjColors.error)
                }
            },
            dismissButton = { PadButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SetCard(
    title: String,
    subtitle: String,
    entries: List<SetEntry>,
    highlight: Boolean,
    expandedInitially: Boolean,
    actions: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(expandedInitially) }
    Column(
        Modifier
            .fillMaxWidth()
            .panel()
            .led(DjColors.error, highlight)
            .clickable { expanded = !expanded }
            .padding(10.dp),
    ) {
        Text(title, fontSize = 14.sp, color = if (highlight) DjColors.error else DjColors.text, fontWeight = FontWeight.Bold)
        Text(subtitle, fontSize = 11.sp, color = DjColors.textDim)
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            entries.forEach { e ->
                Row {
                    Text(formatTime((e.atMs / 1000).toInt()), fontSize = 11.sp, color = DjColors.textDim,
                        fontFamily = DjFonts.mono, modifier = Modifier.width(44.dp))
                    Text("ABCD"[e.deck].toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = if (e.deck == 0) DjColors.deckA else DjColors.deckB, modifier = Modifier.width(14.dp))
                    Text(
                        listOfNotNull(
                            listOf(e.artist, e.title).filter { it.isNotEmpty() }.joinToString(" – "),
                            e.bpm?.let { "%.0f".format(it) },
                            e.key?.let { Camelot.label(it) },
                        ).joinToString(" · "),
                        fontSize = 11.sp, color = DjColors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (entries.isEmpty()) Text("No tracks logged.", fontSize = 11.sp, color = DjColors.textDim)
        }
        actions()
    }
}

@Composable
private fun SetActions(vm: DjViewModel, set: RecordedSet, onDelete: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uri = Uri.parse(set.uri)
    // file:// URIs (Android 9 and older) cannot be handed to other apps without a FileProvider
    val shareable = uri.scheme == "content"
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (shareable) {
            PadButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            }) { Text("Play") }
            PadButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("audio/mp4")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_TEXT, setListText(set))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(Intent.createChooser(send, set.name)) }
            }) { Text("Share") }
        }
        PadButton(onClick = { clipboard.setText(AnnotatedString(setListText(set))) }) { Text("Copy list") }
        Spacer(Modifier.weight(1f))
        PadButton(onClick = onDelete) { Text("Delete", color = DjColors.error) }
    }
}

/** Plain-text set list, e.g. for a post or the show notes. */
private fun setListText(set: RecordedSet): String = buildString {
    appendLine(set.name)
    set.entries.forEach { e ->
        append(formatTime((e.atMs / 1000).toInt())).append("  ")
        appendLine(listOf(e.artist, e.title).filter { it.isNotEmpty() }.joinToString(" – "))
    }
}

/** Above other uploads of a refused track: which song, how the checks are going, a way back to a normal search. */
@Composable
private fun AlternativesHeader(title: String, checks: Map<String, PlayCheck>, searching: Boolean, onClose: () -> Unit) {
    val done = checks.values.count { it.plays != null }
    val plays = checks.values.count { it.plays == true }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp).lcd().padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Other uploads of “$title”", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DjColors.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    checks.isEmpty() && searching -> "Searching…"
                    // Some searches find nothing at all (explicit titles, for one)
                    checks.isEmpty() -> "No other uploads found. Try your own search"
                    done < checks.size -> "Checking which ones play… $done of ${checks.size}"
                    else -> "$plays of ${checks.size} checked play"
                },
                fontSize = 11.sp, color = if (done == checks.size && plays > 0) LOOP_COLOR else DjColors.textDim,
                fontFamily = DjFonts.mono,
            )
        }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Back to normal search", tint = DjColors.textDim) }
    }
}

/** The online tab without a source plugin: what it is for and how one gets there. */
@Composable
private fun NoOnlineSource() {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("No online source installed", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DjColors.text)
        Text(
            "Mischpult plays the music files on this device (Files tab). Online sources, such as a streaming " +
                "service, come as separate plugin apps: once one is installed, its search appears here. " +
                "See docs/PLUGINS.md in Mischpult's source code for how plugins work.",
            fontSize = 13.sp, lineHeight = 18.sp, color = DjColors.textDim,
        )
    }
}
