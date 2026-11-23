package com.logie.wifecraft.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.logie.wifecraft.data.DialogueHost
import com.logie.wifecraft.data.DialogueUiState
import com.logie.wifecraft.dialogue.SpeedResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Options -> Dialogue -> Speed (below the Dialogue model section): how many threads the model uses (Auto picks the phone's performance
 * cores), what the last answers cost ("first word after X s, Y tok/s"), a Speed test (a 64-token answer) and "Copy timings" (the
 * model's load time, the CPU's features, the kernels in use and the last 100 requests, as text for the developer). Compiled by CI only;
 * not run on a phone.
 */
@Composable
fun DialogueSpeedSection() {
    val ctx = LocalContext.current
    val cfg = DialogueHost.config
    val loaded = DialogueHost.state.value.phase == DialogueUiState.Phase.LOADED
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (isActive) { delay(1500); tick++ } }
    @Suppress("UNUSED_VARIABLE") val seen = tick
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SpeedResult?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    WifeSectionHeader("Speed")
    val choices = listOf("Auto", "3", "4", "5", "6")
    val selected = when (cfg.threads) { 0 -> 0; in 3..6 -> cfg.threads - 2; else -> 0 }
    WifeTabs(choices, selected, { DialogueHost.setSpeed(if (it == 0) 0 else it + 2) })
    WifeNote("Threads the model uses. Auto keeps them on the phone's fast cores (a thread on a slow core makes the others wait); try 4 or 5 by hand if the answers drag. It applies to the next answer, no reload.")
    if (loaded) WifeNote(DialogueHost.speedNote() + ". " + DialogueHost.cpuNote())

    val s = DialogueHost.summary()
    WifeNote(s.text())
    if (s.detail().isNotEmpty()) WifeNote(s.detail())
    s.hint()?.let { WifeNote(it) }

    WifeButton(if (running) "Testing..." else "Speed test (a 64-token answer)", {
        if (!running && loaded) {
            running = true; failed = false; result = null
            scope.launch {
                val r = runCatching { DialogueHost.speedTest() }.getOrNull()
                result = r; failed = r == null; running = false; tick++
            }
        }
    }, enabled = loaded && !running)
    result?.let { r -> WifeNote(r.text()); r.hint?.let { WifeNote(it) } }
    if (failed) WifeNote("The test did not finish: the model is busy or not loaded.")
    if (!loaded) WifeNote("The speed test needs the model loaded.")

    WifeButton("Copy timings", {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Wifecraft dialogue timings", DialogueHost.timingsReport()))
    })
    WifeNote("Copies the load time, this phone's CPU, which kernels run and the last ${DialogueHost.timings.capacity} requests (waiting, reading, writing, first word), so the numbers can be read off this phone.")
}
