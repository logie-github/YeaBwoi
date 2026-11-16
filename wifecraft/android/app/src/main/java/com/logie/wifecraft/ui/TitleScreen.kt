package com.logie.wifecraft.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
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

/**
 * The title: the user's art, the emblem, and the game's own red buttons stacked as WoW's Game Menu stacks them ([Menus.title]:
 * Continue, New Project, Projects, Options, Import Animations, Updates; Gather the Files in Continue's place until the game's
 * files are here). One column; two side by side when the screen is too short (the cover screen on its side). Continue opens
 * the story last worked on ([lastProject], named under the stack).
 */
@Composable
fun TitleScreen(
    filesReady: Boolean,
    downloadSize: String,
    /** The music and sounds' size when they aren't fetched yet (the stories play silent without). */
    soundMissing: String?,
    lastProject: String?,
    updateTitle: String?,
    onContinue: () -> Unit,
    onNewProject: () -> Unit,
    onProjects: () -> Unit,
    onGather: () -> Unit,
    onOptions: () -> Unit,
    onUpdates: () -> Unit,
    /** Import Sims 4 animations (needs none of the game's files); the library is reached from there and from the Game Menu. */
    onImportAnims: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onAnimations: () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val layout = ChromeLayout.of(maxWidth.value, maxHeight.value)
        val short = maxHeight < 520.dp
        Image(
            painterResource(R.drawable.wc_header), null,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = BiasAlignment(0.6f, -0.4f),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color(0xD0080418), 0.42f to Color(0x80080418), 0.62f to Color.Transparent),
            ),
        )
        Column(Modifier.padding(start = 40.dp, top = if (short) 20.dp else 32.dp, bottom = 16.dp, end = 16.dp).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.wc_emblem), null, Modifier.size(if (short) 56.dp else 74.dp).clip(CircleShape))
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Wifecraft", style = morpheusStyle(if (short) 40.sp else 50.sp))
                    Text("A  STORY  EDITOR", style = frizStyle(12.sp, Color.White))
                }
            }
            Spacer(Modifier.height(if (short) 14.dp else 26.dp))
            val buttons = Menus.title(filesReady, lastProject != null)
            RedStack(buttons, { it.label }, layout.titleCols, 260.dp, { item ->
                when (item) {
                    TitleItem.CONTINUE -> onContinue()
                    TitleItem.GATHER -> onGather()
                    TitleItem.NEW_PROJECT -> onNewProject()
                    TitleItem.PROJECTS -> onProjects()
                    TitleItem.OPTIONS -> onOptions()
                    TitleItem.IMPORT_ANIMATIONS -> onImportAnims()
                    TitleItem.UPDATES -> onUpdates()
                }
            })
            Spacer(Modifier.height(8.dp))
            when {
                !filesReady -> Text(
                    "Wifecraft draws its people, its stages and WoW's interface from the game's own files, fetched once from wago.tools " +
                        "(about $downloadSize) and kept on the phone: best on Wi-Fi, with room to spare.",
                    style = frizStyle(12.sp, Color.White), modifier = Modifier.widthIn(max = 540.dp),
                )
                lastProject != null -> Text("Continue: $lastProject", style = frizStyle(12.sp, Color.White), maxLines = 1)
            }
            if (filesReady && soundMissing != null) Text("WoW's music and sounds aren't here yet ($soundMissing): Options, Advanced, The game's files.",
                style = frizStyle(11.sp, WowColors.grey), modifier = Modifier.widthIn(max = 540.dp))
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), horizontalAlignment = Alignment.End) {
            if (updateTitle != null) Text("$updateTitle is out: Updates installs it.", style = frizStyle(11.sp, Color.White))
            Text("Version ${BuildConfig.VERSION_NAME}", style = frizStyle(11.sp))
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
            val mb = { b: Long -> if (b >= 1_000_000_000L) "%.1f GB".format(b / 1e9) else "%.0f MB".format(b / 1e6) }
            // the total is an estimate until the creator's files are all here (wago.tools gives no sizes ahead)
            val of = (if (progress.estimated) "about " else "") + mb(progress.total)
            val count = "${progress.files} of ${progress.filesTotal}${if (progress.filesExact) "" else "+"} files"
            val status = when {
                progress.error != null -> "Stopped: ${progress.error}. Resume tries again."
                progress.waitingForWifi -> "Waiting for Wi-Fi (or turn Wi-Fi only off)."
                progress.finished -> "Everything is here."
                progress.running -> "Gathering ${packLabels.firstOrNull { it.first == progress.current }?.second ?: "the game's files"}… $count, ${mb(progress.done)} of $of"
                else -> "$count, ${mb(progress.done)} of $of on the phone"
            }
            Text(status, style = frizStyle(15.sp))
            if (progress.running) Text(
                if (progress.parallel < progress.parallelCeiling) "slowed to ${progress.parallel}: wago.tools asked for less" else "${progress.parallel} at once",
                style = frizStyle(12.sp, if (progress.parallel < progress.parallelCeiling) Color(0xFFFFD060) else Color.White),
            )
            if (progress.failed.isNotEmpty() && !progress.running) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${progress.failed.size} ${if (progress.failed.size == 1) "file" else "files"} could not be fetched.", style = frizStyle(13.sp, Color(0xFFFF6060)))
                    WowButton("Retry", onStart, width = 112.dp, height = 36.dp)
                }
            }
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
