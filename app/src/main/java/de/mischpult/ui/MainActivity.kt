package de.mischpult.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import de.mischpult.PlaybackService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: DjViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        AppVisibility.visible.value = true
        // A source plugin may have been installed or removed meanwhile
        lifecycleScope.launch { vm.controller.plugins.refresh() }
    }

    override fun onStop() {
        AppVisibility.visible.value = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The playback notification needs this on Android 13+ (playback works without it)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // A DJ set must not be interrupted by the screen timing out
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            DjTheme {
                SyncPoller()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                // A deck asked for other uploads of a refused track: show the search
                val jumpToSearch by vm.jumpToSearch.collectAsState()
                LaunchedEffect(jumpToSearch) { if (jumpToSearch) tab = 1 }
                var playing by remember { mutableStateOf(false) }
                var askOnExit by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    while (true) {
                        playing = vm.controller.anyPlaying
                        pollDelay(250)
                    }
                }
                // Back from the library returns to the decks; leaving while music plays asks first,
                // so a set is never cut off, nor left running unnoticed
                BackHandler(enabled = tab != 0 || playing) {
                    if (tab != 0) tab = 0 else askOnExit = true
                }
                if (askOnExit) {
                    DjDialog(
                        onDismissRequest = { askOnExit = false },
                        title = { Text("Music is playing") },
                        text = { Text("Keep it playing in the background, or stop it and close Mischpult?") },
                        confirmButton = {
                            PadButton(onClick = {
                                askOnExit = false
                                vm.controller.stopAll()
                                PlaybackService.stop(this@MainActivity)
                                finish()
                            }) { Text("Stop and close") }
                        },
                        dismissButton = {
                            PadButton(onClick = {
                                askOnExit = false
                                moveTaskToBack(true)
                            }) { Text("Keep playing") }
                        },
                    )
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(DjColors.background)
                        .safeDrawingPadding()
                ) {
                    PadTabs(listOf("Decks", "Library", "Sampler", "Scratch"), tab, height = 34.dp) { tab = it }
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            0 -> MixerScreen(vm)
                            1 -> LibraryScreen(vm, onOpenDecks = { tab = 0 })
                            2 -> SamplerScreen(vm)
                            else -> ScratchScreen(vm)
                        }
                    }
                }
            }
        }
    }
}
