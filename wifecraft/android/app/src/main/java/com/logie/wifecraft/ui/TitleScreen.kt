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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

@Composable
fun TitleScreen(
    filesReady: Boolean,
    downloadSize: String,
    updateTitle: String?,
    onChooseScene: () -> Unit,
    onGather: () -> Unit,
    onOptions: () -> Unit,
    onUpdates: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val layout = ChromeLayout.of(maxWidth.value, maxHeight.value)
        val short = maxHeight < 520.dp
        WifecraftMenuBackdrop(Modifier.fillMaxSize(), BiasAlignment(0.6f, -0.4f))
        // the menu comes in with the screen (a short fade and rise), once
        val enter = rememberEnter(MenuMotion.WINDOW_IN_MS + 40)
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color(0xD0080418), 0.42f to Color(0x80080418), 0.62f to Color.Transparent),
            ),
        )
        Column(
            Modifier.padding(start = 40.dp, top = if (short) 20.dp else 32.dp, bottom = 16.dp, end = 16.dp).fillMaxSize()
                .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 8.dp.toPx() },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MenuPicture(R.drawable.wc_emblem, Modifier.size(if (short) 56.dp else 74.dp).clip(CircleShape), ContentScale.Fit)
                Text("Wifecraft", style = morpheusStyle(if (short) 40.sp else 50.sp))
            }
            Spacer(Modifier.height(if (short) 14.dp else 26.dp))
            val buttons = listOf(
                (if (filesReady) "Choose Scene" else "Gather the Files") to (if (filesReady) onChooseScene else onGather),
                "Options" to onOptions,
                "Updates" to onUpdates,
            )
            RedStack(buttons.map { StackButton(it, true) }, { it.first }, layout.titleCols, 260.dp, { it.second() })
            if (!filesReady) Text(
                "Download the game files to create and play with NPCs. Allow about $downloadSize.",
                style = frizStyle(12.sp, Color.White), modifier = Modifier.padding(top = 8.dp).widthIn(max = 540.dp),
            )
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), horizontalAlignment = Alignment.End) {
            if (updateTitle != null) Text("$updateTitle is out: Updates installs it.", style = frizStyle(11.sp, Color.White))
            Text("Version ${BuildConfig.VERSION_NAME}", style = frizStyle(11.sp))
        }
    }
}

@Composable
internal fun WifecraftMenuBackdrop(modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center) {
    MenuPicture(R.drawable.wc_header, modifier, ContentScale.Crop, alignment)
}

/**
 * The menus' own pictures (the backdrop, the splash, the emblem: full HD JPEGs and a 512 px PNG), decoded once off the main
 * thread and kept: painterResource decodes them on the main thread every time a screen showing them is opened, a stalled
 * frame each time the title comes back. The place is the caller's ([modifier]) from the start; the picture fades into it.
 */
private object MenuPictures {
    private val kept = java.util.concurrent.ConcurrentHashMap<Int, ImageBitmap>()

    fun cached(id: Int): ImageBitmap? = kept[id]

    suspend fun load(res: android.content.res.Resources, id: Int): ImageBitmap? = kept[id] ?: withContext(Dispatchers.IO) {
        runCatching { android.graphics.BitmapFactory.decodeResource(res, id)?.asImageBitmap() }.getOrNull()
    }?.also { kept[id] = it }
}

@Composable
private fun MenuPicture(id: Int, modifier: Modifier, scale: ContentScale, alignment: Alignment = Alignment.Center) {
    val res = LocalContext.current.resources
    val pic by produceState(MenuPictures.cached(id), id) { if (value == null) value = MenuPictures.load(res, id) }
    val reduced = reduceMotion()
    // already kept: shown at once; decoded now: faded in
    val fade = remember(id) { Animatable(if (MenuPictures.cached(id) != null || reduced) 1f else 0f) }
    LaunchedEffect(pic != null) { if (pic != null && fade.value < 1f) fade.animateTo(1f, tween(MenuMotion.WINDOW_IN_MS)) }
    Box(modifier) {
        pic?.let { Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }, alignment = alignment, contentScale = scale) }
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
        MenuPicture(R.drawable.wc_splash, Modifier.fillMaxSize(), ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xE0000000))))
        Column(Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // one button that changes its words (two would each let a quick double tap through: Start, then Pause at once)
            WowButton(if (progress.running) "Pause" else if (progress.done > 0) "Resume" else "Start", if (progress.running) onPause else onStart, width = 140.dp, height = 40.dp)
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
