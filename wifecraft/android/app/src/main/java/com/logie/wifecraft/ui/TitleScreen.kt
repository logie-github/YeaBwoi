package com.logie.wifecraft.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.BuildConfig
import com.logie.wifecraft.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The title: the backdrop breathing slowly with motes rising over it ([WifecraftMenuBackdrop]), the crest and the name, and
 * the menu on a slate panel: the main action in the game's red (Choose Scene, or Gather the Files until the files are here),
 * Options and Updates. Down the left on a wide screen, centred on a narrow one ([FrontLayout.title]); it scrolls rather than
 * clip on a short one.
 */
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
        val l = FrontLayout.title(maxWidth.value, maxHeight.value)
        WifecraftMenuBackdrop(Modifier.fillMaxSize(), BiasAlignment(0.6f, -0.4f))
        Box(
            Modifier.fillMaxSize().background(
                if (l.centered) Brush.verticalGradient(0f to Color(0x55060A10), 0.45f to Color(0x99060A10), 1f to Color(0xE6060A10))
                else Brush.horizontalGradient(0f to Color(0xE0060A10), 0.42f to Color(0x8C060A10), 0.68f to Color.Transparent),
            ),
        )
        // the menu comes in with the screen (a short fade and rise), once
        val enter = rememberEnter(MenuMotion.WINDOW_IN_MS + 80)
        val minHeight = maxHeight
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = minHeight)
                    .padding(horizontal = l.padH.dp).padding(top = l.padTop.dp, bottom = 48.dp)
                    .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 12.dp.toPx() },
                verticalArrangement = Arrangement.spacedBy(WifeTheme.gapM, Alignment.CenterVertically),
                horizontalAlignment = if (l.centered) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapM)) {
                    MenuPicture(R.drawable.wc_emblem, Modifier.size(l.crestDp.dp).clip(CircleShape), ContentScale.Fit)
                    Column {
                        Text("Wifecraft", style = WifeTheme.displayStyle(l.titleSp.sp), maxLines = 1)
                        Text("A Warcraft dating sim", style = WifeTheme.captionStyle(WifeTheme.text), maxLines = 1)
                    }
                }
                WifePanel(Modifier.width((l.buttonW + 2 * 16f).dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                        if (filesReady) WifeButton("Choose Scene", onChooseScene, Modifier.fillMaxWidth(), kind = WifeButtonKind.PRIMARY, sound = "ok")
                        else WifeButton("Gather the Files", onGather, Modifier.fillMaxWidth(), kind = WifeButtonKind.PRIMARY, sound = "ok")
                        // the windows play the game's open sound themselves
                        WifeButton("Options", onOptions, Modifier.fillMaxWidth(), sound = "")
                        WifeButton(if (updateTitle != null) "Updates  •  New" else "Updates", onUpdates, Modifier.fillMaxWidth(), sound = "")
                    }
                }
                if (!filesReady) WifeNote(
                    "Download the game's files to create and play with NPCs. Allow about $downloadSize.",
                    Modifier.widthIn(max = l.buttonW.dp + 32.dp),
                    WifeTheme.text,
                )
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(WifeTheme.gapM), horizontalAlignment = Alignment.End) {
            if (updateTitle != null) Text("$updateTitle is out: Updates installs it.", style = WifeTheme.captionStyle(WifeTheme.text), textAlign = TextAlign.End)
            Text("Version ${BuildConfig.VERSION_NAME}", style = WifeTheme.captionStyle())
        }
    }
}

/**
 * The menus' backdrop: the header picture breathing slowly (a gentle scale and pan, FrontMotion.drift) with pale motes rising
 * over it at their own pace (the parallax: two layers moving differently), and a vignette. Still under reduced motion. The
 * animation is read only where it is drawn (a layer's transform, one small canvas), so it redraws, never recomposes.
 */
@Composable
internal fun WifecraftMenuBackdrop(modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center, motes: Boolean = true) {
    val reduced = reduceMotion()
    val drift: State<Float>
    val rise: State<Float>
    if (reduced) {
        drift = remember { mutableFloatStateOf(0f) }
        rise = remember { mutableFloatStateOf(0.3f) }
    } else {
        val t = rememberInfiniteTransition(label = "backdrop")
        drift = t.animateFloat(0f, 1f, infiniteRepeatable(tween(FrontMotion.DRIFT_MS, easing = LinearEasing), RepeatMode.Restart), label = "drift")
        rise = t.animateFloat(0f, 1f, infiniteRepeatable(tween(FrontMotion.DRIFT_MS / 2, easing = LinearEasing), RepeatMode.Restart), label = "motes")
    }
    Box(modifier) {
        MenuPicture(
            R.drawable.wc_header,
            Modifier.fillMaxSize().graphicsLayer {
                val (s, px, py) = FrontMotion.drift(drift.value, reduced)
                scaleX = s; scaleY = s
                translationX = px * size.width; translationY = py * size.height
            },
            ContentScale.Crop, alignment,
        )
        if (motes) Canvas(Modifier.fillMaxSize()) {
            val p = rise.value
            val r = 1.6.dp.toPx()
            for (i in 0 until FrontMotion.MOTES) {
                val (x, y, a) = FrontMotion.mote(i, p)
                if (a <= 0.01f) continue
                val c = Offset(x * size.width, y * size.height)
                drawCircle(Color(0xFFFFE2A0).copy(alpha = a * 0.35f), r * 3f, c)
                drawCircle(Color(0xFFFFF1C8).copy(alpha = a), r, c)
            }
        }
        // the vignette: the edges darker, the eye kept on the menu
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(0.55f to Color.Transparent, 1f to Color(0x99000000))))
    }
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

/** The first launch's gathering screen: the splash, and on a slate panel what's fetched, the bar, and the choices about it. */
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
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val screenW = maxWidth
        val screenH = maxHeight
        MenuPicture(R.drawable.wc_splash, Modifier.fillMaxSize(), ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xE6000000))))
        val panelW = minOf(600.dp, screenW - 32.dp).coerceAtLeast(0.dp)
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.heightIn(min = (screenH - 32.dp).coerceAtLeast(0.dp)), contentAlignment = Alignment.BottomCenter) {
                WifePanel(Modifier.width(panelW)) {
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
                    Text("Gathering the Files", style = WifeTheme.titleStyle(), maxLines = 1)
                    WifeDivider(Modifier.padding(vertical = WifeTheme.gapXs))
                    // two lines kept for the status whatever it says: the bar and the buttons never move under the finger
                    Text(status, style = WifeTheme.bodyStyle(), minLines = 2, maxLines = 3)
                    Text(
                        if (!progress.running) "" else if (progress.parallel < progress.parallelCeiling) "Slowed to ${progress.parallel}: wago.tools asked for less" else "${progress.parallel} at once",
                        style = WifeTheme.captionStyle(if (progress.parallel < progress.parallelCeiling) Color(0xFFFFD060) else WifeTheme.textDim), maxLines = 1,
                    )
                    WifeGap()
                    WifeProgress(if (progress.total > 0) progress.done.toFloat() / progress.total else 0f)
                    WifeGap(WifeTheme.gapXs)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapM)) {
                        for ((key, label) in packLabels) {
                            val done = key in progress.complete
                            Text((if (done) "✓ " else "") + label, style = WifeTheme.captionStyle(if (done) WifeTheme.good else WifeTheme.text), maxLines = 1)
                        }
                    }
                    if (progress.failed.isNotEmpty() && !progress.running) {
                        Text("${progress.failed.size} ${if (progress.failed.size == 1) "file" else "files"} could not be fetched: Resume tries them again.",
                            style = WifeTheme.captionStyle(WifeTheme.danger), modifier = Modifier.padding(top = WifeTheme.gapXs))
                    }
                    WifeSwitchRow("Wi-Fi only", wifiOnly, onWifiOnly, detail = "A look's own files still come as it is shown")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS)) {
                        WifeButton("Back", onBack, Modifier.weight(1f), sound = "close")
                        // one button that changes its words (two would each let a quick double tap through: Start, then Pause at once)
                        WifeButton(if (progress.running) "Pause" else if (progress.done > 0) "Resume" else "Start",
                            if (progress.running) onPause else onStart, Modifier.weight(1f), kind = WifeButtonKind.PRIMARY)
                    }
                }
            }
        }
    }
}
