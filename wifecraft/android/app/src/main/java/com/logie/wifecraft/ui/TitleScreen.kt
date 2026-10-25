package com.logie.wifecraft.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.BuildConfig
import com.logie.wifecraft.R

/** The title: the user's art, the emblem, and the game's own buttons. */
@Composable
fun TitleScreen(
    filesReady: Boolean,
    downloadSize: String,
    hasSave: Boolean,
    updateTitle: String?,
    onContinue: () -> Unit,
    onNew: () -> Unit,
    onGather: () -> Unit,
    onLoad: () -> Unit,
    onEndings: () -> Unit,
    onOptions: () -> Unit,
    onUpdates: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(
            painterResource(R.drawable.wc_header), null,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = BiasAlignment(0.6f, -0.4f),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color(0xD0080418), 0.42f to Color(0x80080418), 0.62f to Color.Transparent),
            ),
        )
        Column(Modifier.padding(start = 40.dp, top = 28.dp, bottom = 16.dp).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.wc_emblem), null, Modifier.size(74.dp).clip(CircleShape))
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Wifecraft", style = morpheusStyle(50.sp))
                    Text("A  DALARAN  ROMANCE", style = frizStyle(13.sp, Color.White))
                }
            }
            Spacer(Modifier.height(22.dp))
            if (filesReady) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WowButton("Continue", onContinue, enabled = hasSave, width = 230.dp, height = 46.dp)
                    WowButton("New Story", onNew, width = 230.dp, height = 46.dp)
                }
            } else {
                Text(
                    "The demo draws Dalaran, its people and WoW's interface from the game's own files.\n" +
                        "They're fetched once from wago.tools ($downloadSize) and kept on the phone.",
                    style = frizStyle(13.sp, Color.White), modifier = Modifier.width(420.dp),
                )
                Spacer(Modifier.height(10.dp))
                WowButton("Gather Dalaran", onGather, width = 230.dp, height = 46.dp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WowButton("Load", onLoad, enabled = filesReady && hasSave, width = 112.dp, height = 40.dp)
                WowButton("Endings", onEndings, width = 112.dp, height = 40.dp)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WowButton("Options", onOptions, width = 112.dp, height = 40.dp)
                WowButton("Updates", onUpdates, width = 112.dp, height = 40.dp)
            }
            Spacer(Modifier.weight(1f))
            Text("Version ${BuildConfig.VERSION_NAME}", style = frizStyle(11.sp))
            if (updateTitle != null) Text("$updateTitle is out: Updates installs it.", style = frizStyle(11.sp, Color.White))
        }
    }
}

/** The first launch's loading screen: what's fetched, the bar, and the choices about it. */
@Composable
fun GatherScreen(
    progress: com.logie.wifecraft.data.GameFiles.Progress,
    packLabels: List<Pair<String, String>>,
    wifiOnly: Boolean,
    onWifiOnly: (Boolean) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onBack: () -> Unit,
) {
    val art = LocalArt.current
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(painterResource(R.drawable.wc_splash), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xE0000000))))
        Column(Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (progress.running) WowButton("Pause", onPause, width = 140.dp, height = 40.dp)
            else WowButton(if (progress.done > 0) "Resume" else "Start", onStart, width = 140.dp, height = 40.dp)
            WowButton("Back", onBack, width = 140.dp, height = 40.dp)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                androidx.compose.material3.Checkbox(wifiOnly, onWifiOnly, colors = goldCheckbox())
                Text("Wi-Fi only", style = frizStyle(13.sp))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val mb = { b: Long -> "%.0f MB".format(b / 1e6) }
            val status = when {
                progress.error != null -> "Stopped: ${progress.error}. Resume tries again."
                progress.waitingForWifi -> "Waiting for Wi-Fi (or turn Wi-Fi only off)."
                progress.finished -> "Dalaran is ready."
                progress.running -> "Gathering ${packLabels.firstOrNull { it.first == progress.current }?.second ?: "Dalaran"}… ${mb(progress.done)} of ${mb(progress.total)}"
                else -> "${mb(progress.done)} of ${mb(progress.total)} on the phone"
            }
            Text(status, style = frizStyle(15.sp))
            Spacer(Modifier.height(6.dp))
            LoadingBar(if (progress.total > 0) progress.done.toFloat() / progress.total else 0f, Modifier.width(560.dp).height(52.dp))
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for ((key, label) in packLabels) {
                    val done = key in progress.complete
                    Text((if (done) "✓ " else "") + label, style = frizStyle(11.sp, if (done) Color(0xFF7CFC7C) else Color.White))
                }
            }
            if (art == null || !art.ready) Spacer(Modifier.height(0.dp))
        }
    }
}
