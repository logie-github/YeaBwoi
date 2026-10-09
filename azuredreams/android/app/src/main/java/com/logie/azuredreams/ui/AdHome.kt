// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams.ui

import android.app.Activity
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.logie.azuredreams.AdFiles
import com.logie.azuredreams.AdUpdateActivity
import com.logie.azuredreams.AdUpdater
import com.logie.azuredreams.BuildConfig
import com.logie.azuredreams.GameActivity
import com.logie.azuredreams.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The title screen, the first screen of the app. Opening the app goes straight back into the game
 * once it's installed (after offering any update); leaving the game comes back here.
 */
@Composable
fun AdHomeRoute(activity: Activity, autoplayOnOpen: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val disc = remember(refresh) { AdFiles.launchFile(context) }
    // The game's own saves need a real BIOS (from the next time the game starts).
    val hasBios = remember(refresh) { AdFiles.bios(context) != null }

    val crt = remember { Animatable(0f) }
    var launching by remember { mutableStateOf(false) }
    var autoplay by rememberSaveable { mutableStateOf(autoplayOnOpen) }
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    var importing by remember { mutableStateOf<Float?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var biosError by remember { mutableStateOf<String?>(null) }

    /** Boots the game, to its own title screen. */
    fun play() {
        if (launching || AdFiles.launchFile(context) == null) return
        launching = true
        scope.launch {
            crt.snapTo(0f)
            crt.animateTo(1f, tween(460, easing = LinearEasing))
            activity.startActivity(GameActivity.intent(context))
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
    }

    val updateOffer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        autoplay = false
        if (result.resultCode != AdUpdateActivity.RESULT_INSTALLING) play()
    }

    // Opening the app: offer a newer version if there is one, then start the game.
    LaunchedEffect(Unit) {
        if (!autoplay) return@LaunchedEffect
        if (disc == null) {
            autoplay = false
            return@LaunchedEffect
        }
        val update = if (AdUpdater.isConfigured) {
            // Offline or GitHub unreachable: just play, and check again next time.
            withContext(Dispatchers.IO) { runCatching { AdUpdater.findUpdate() }.getOrNull() }
        } else {
            null
        }
        if (update != null) {
            updateOffer.launch(AdUpdateActivity.intent(context, update))
        } else {
            autoplay = false
            play()
        }
    }

    // Back from the game.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        refresh++
        if (launching) {
            launching = false
            scope.launch { crt.snapTo(0f) }
        }
    }

    val addBios = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AdFiles.importBios(context, uri) } }
            refresh++
            result.fold(
                onSuccess = { Toast.makeText(context, R.string.ad_bios_added, Toast.LENGTH_SHORT).show() },
                onFailure = {
                    biosError = if (it is AdFiles.ImportException) context.getString(R.string.ad_bios_not_a_bios)
                    else context.getString(R.string.ad_card_failed, it.message ?: "")
                },
            )
        }
    }

    val pickGame = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = 0f
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AdFiles.importGame(context, uris) { done, total ->
                        importing = if (total > 0) done.toFloat() / total else 0f
                    }
                }
            }
            importing = null
            refresh++
            result.exceptionOrNull()?.let { importError = importErrorText(context, it) }
        }
    }

    AdTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // The CRT switching off as the game starts: the picture squeezes into a line, then a dot.
            val off = crt.value
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleY = 1f - 0.995f * (off / 0.6f).coerceIn(0f, 1f)
                        scaleX = 1f - ((off - 0.6f) / 0.4f).coerceIn(0f, 1f)
                    }
            ) {
                HomeScreen(
                    installed = disc != null,
                    onAddBios = if (hasBios) null else ({ addBios.launch(arrayOf("*/*")) }),
                    importing = importing,
                    onPlay = { play() },
                    onInstall = { pickGame.launch(arrayOf("*/*")) },
                    onDialog = { dialog = it },
                )
                if (off > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = (off * 1.6f).coerceAtMost(1f)))
                    )
                }
            }
            LoadingOverlay(visible = autoplay)
        }

        when (dialog) {
            HomeDialog.CONTROLS -> ControlsDialog(onDismiss = { dialog = null })
            HomeDialog.PICTURE -> PictureDialog(onDismiss = { dialog = null })
            HomeDialog.MEMORY_CARD -> MemoryCardDialog(onDismiss = { dialog = null; refresh++ })
            HomeDialog.BIOS -> BiosDialog(onDismiss = { dialog = null; refresh++ })
            HomeDialog.DISC -> DiscDialog(
                onReplace = { dialog = null; pickGame.launch(arrayOf("*/*")) },
                onDismiss = { dialog = null; refresh++ },
            )
            null -> {}
        }
        importError?.let { message ->
            AdDialog(title = stringResource(R.string.ad_install_failed), onDismiss = { importError = null }) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
        biosError?.let { message ->
            AdDialog(title = stringResource(R.string.ad_bios_title), onDismiss = { biosError = null }) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

enum class HomeDialog { CONTROLS, PICTURE, MEMORY_CARD, BIOS, DISC }

@Composable
private fun HomeScreen(
    installed: Boolean,
    /** Without a BIOS yet: adds one. */
    onAddBios: (() -> Unit)?,
    importing: Float?,
    onPlay: () -> Unit,
    onInstall: () -> Unit,
    onDialog: (HomeDialog) -> Unit,
) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        AdBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AdHeader(screenWidth)
            Column(
                Modifier
                    .widthIn(max = 860.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    importing != null -> ImportingWindow(importing)
                    installed -> {
                        PressStart(onClick = onPlay)
                        Text(
                            stringResource(R.string.ad_home_boot_line),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AdColors.TextDim,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        if (onAddBios != null) {
                            Text(
                                stringResource(R.string.ad_home_no_bios),
                                style = MaterialTheme.typography.bodySmall,
                                color = AdColors.Gold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .widthIn(max = 520.dp),
                            )
                        }
                        if (onAddBios != null) {
                            AdButton(
                                stringResource(R.string.ad_bios_add),
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                                onClick = onAddBios,
                            )
                        }
                    }
                    else -> InsertDisc(onInstall)
                }

                SectionTitle(stringResource(R.string.ad_home_menu))
                val tiles = listOf(
                    HomeTile(Icons.Default.TouchApp, stringResource(R.string.ad_tile_controls),
                        stringResource(R.string.ad_tile_controls_summary)) { onDialog(HomeDialog.CONTROLS) },
                    HomeTile(Icons.Default.Tv, stringResource(R.string.ad_tile_picture),
                        stringResource(R.string.ad_tile_picture_summary)) { onDialog(HomeDialog.PICTURE) },
                    HomeTile(Icons.Default.SdCard, stringResource(R.string.ad_tile_memory_card),
                        stringResource(R.string.ad_tile_memory_card_summary)) { onDialog(HomeDialog.MEMORY_CARD) },
                    HomeTile(Icons.Default.Memory, stringResource(R.string.ad_tile_bios),
                        stringResource(if (AdFiles.bios(context) != null) R.string.ad_tile_bios_yours else R.string.ad_tile_bios_builtin)) {
                        onDialog(HomeDialog.BIOS)
                    },
                    HomeTile(Icons.Default.Album, stringResource(R.string.ad_tile_disc),
                        stringResource(if (installed) R.string.ad_tile_disc_installed else R.string.ad_tile_disc_missing)) {
                        if (installed) onDialog(HomeDialog.DISC) else onInstall()
                    },
                    HomeTile(Icons.Default.SystemUpdate, stringResource(R.string.ad_tile_updates),
                        stringResource(R.string.ad_tile_updates_summary, BuildConfig.VERSION_NAME)) {
                        AdUpdateActivity.launch(context)
                    },
                )
                val columns = (screenWidth / 260.dp).toInt().coerceIn(1, 3).let { if (screenWidth >= 360.dp) maxOf(it, 2) else it }
                TileGrid(tiles, columns)

                Text(
                    stringResource(R.string.ad_version, stringResource(R.string.app_name), BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp, bottom = 22.dp),
                )
            }
        }
    }
}

/**
 * The art of Koh looking out at the Monster Tower, slowly panning toward the tower, with the logo
 * banner unrolling over its bottom edge and Kewne hovering beside it.
 */
@Composable
private fun AdHeader(screenWidth: Dp) {
    val headerHeight = (screenWidth / 2.5f).coerceIn(190.dp, 400.dp)
    val logoWidth = min(screenWidth * 0.78f, 470.dp)
    val logoHeight = logoWidth * (222f / 600f)
    val overhang = logoHeight * 0.62f

    val motion = rememberInfiniteTransition(label = "header")
    val pan by motion.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pan",
    )
    val bob by motion.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    val drift by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing)),
        label = "drift",
    )
    // Kewne's little hop and spin, every few seconds.
    val hop by motion.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 5_200
                0f at 0
                0f at 4_300
                1f at 4_600 using FastOutSlowInEasing
                0f at 5_000 using FastOutSlowInEasing
            }
        ),
        label = "hop",
    )
    val unroll = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(250)
        unroll.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 140f))
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(headerHeight + overhang)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .clipToBounds()
        ) {
            Image(
                painter = painterResource(R.drawable.ad_header),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(0.15f, -0.25f),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val zoom = 1.12f
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan * size.width * 0.045f
                        transformOrigin = TransformOrigin(0.5f, 0.4f)
                    },
            )
            // The picture melts into the night below it.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to AdColors.Night,
                        )
                    )
            )
            AdScanlines(alpha = 0.14f)
            // Kewne, hovering in the sky by the tower.
            Image(
                bitmap = ImageBitmap.imageResource(R.drawable.ad_kewne),
                contentDescription = null,
                // Pixel art: kept crisp, not smoothed.
                filterQuality = FilterQuality.None,
                modifier = Modifier
                    .align(BiasAlignment(0.86f, -0.55f))
                    .size(width = headerHeight * 0.26f, height = headerHeight * 0.33f)
                    .graphicsLayer {
                        val circle = drift * 2f * Math.PI.toFloat()
                        translationX = kotlin.math.sin(circle) * 10.dp.toPx()
                        translationY = bob * 6.dp.toPx() - hop * 18.dp.toPx() + kotlin.math.cos(circle) * 4.dp.toPx()
                        rotationY = hop * 360f
                        rotationZ = bob * 4f
                    },
            )
        }
        // The logo banner unrolls from the middle, then floats.
        Image(
            painter = painterResource(R.drawable.ad_logo),
            contentDescription = stringResource(R.string.ad_game_name),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .width(logoWidth)
                .height(logoHeight)
                .graphicsLayer {
                    val value = unroll.value
                    scaleX = (0.08f + 0.92f * value)
                    scaleY = (0.7f + 0.3f * value.coerceAtMost(1f))
                    alpha = (value * 3f).coerceIn(0f, 1f)
                    translationY = bob * 3.dp.toPx() - 10.dp.toPx()
                    rotationZ = -1.5f + bob * 0.8f
                },
        )
    }
}

/** PRESS START, blinking, in its menu window with the pointer nudging at it. */
@Composable
private fun PressStart(onClick: () -> Unit) {
    val motion = rememberInfiniteTransition(label = "start")
    val blink by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1_100
                1f at 0
                1f at 700
                0.15f at 760
                0.15f at 1_040
                1f at 1_100
            }
        ),
        label = "blink",
    )
    AdWindow(Modifier.fillMaxWidth(), onClick = onClick, padding = androidx.compose.foundation.layout.PaddingValues(vertical = 22.dp, horizontal = 16.dp)) {
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            AdPointer(size = 22.dp)
            Spacer(Modifier.width(14.dp))
            Text(
                stringResource(R.string.ad_home_press_start),
                style = AdDisplayStyle.copy(fontSize = 24.sp, color = AdColors.Gold),
                modifier = Modifier.graphicsLayer { alpha = blink },
            )
        }
    }
}

@Composable
private fun InsertDisc(onInstall: () -> Unit) {
    AdWindow(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AdSpinningDisc(36.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.ad_setup_title), style = MaterialTheme.typography.headlineSmall, color = AdColors.Gold)
            }
            Text(stringResource(R.string.ad_setup_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.ad_setup_bios_note), style = MaterialTheme.typography.bodySmall)
            AdButton(stringResource(R.string.ad_setup_action), modifier = Modifier.align(Alignment.End), onClick = onInstall)
        }
    }
}

@Composable
private fun ImportingWindow(progress: Float) {
    AdWindow(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AdSpinningDisc(36.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.ad_installing, (progress * 100).toInt()),
                    style = MaterialTheme.typography.headlineSmall,
                    color = AdColors.Gold,
                )
            }
            // A chunky progress bar, the memory card manager kind.
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(18.dp)
            ) {
                drawRect(Color(0x66000000))
                val blocks = 20
                val gap = 2.dp.toPx()
                val width = (size.width - gap * (blocks + 1)) / blocks
                val lit = (progress * blocks).toInt()
                for (i in 0 until lit) {
                    drawRect(AdColors.Azure, Offset(gap + i * (width + gap), gap), androidx.compose.ui.geometry.Size(width, size.height - 2 * gap))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.titleLarge,
        color = AdColors.SkyLight,
        modifier = Modifier.padding(top = 12.dp, start = 4.dp),
    )
}

private class HomeTile(val icon: ImageVector, val title: String, val subtitle: String, val onClick: () -> Unit)

@Composable
private fun TileGrid(tiles: List<HomeTile>, columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { columnIndex, tile ->
                    TileWindow(tile, rowIndex * columns + columnIndex, Modifier.weight(1f))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A menu window that drops into place, one after another, when the screen opens. */
@Composable
private fun TileWindow(tile: HomeTile, index: Int, modifier: Modifier) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(300L + index * 70L)
        appear.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f))
    }
    AdWindow(
        modifier = modifier.graphicsLayer {
            alpha = appear.value.coerceIn(0f, 1f)
            translationY = (1f - appear.value) * -24.dp.toPx()
        },
        onClick = tile.onClick,
        padding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(tile.icon, contentDescription = null, tint = AdColors.Gold, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tile.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
}

/** Shown while the app goes straight back into the game, so the menu doesn't flash first. */
@Composable
private fun LoadingOverlay(visible: Boolean) {
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(0)), exit = fadeOut(tween(300))) {
        val motion = rememberInfiniteTransition(label = "loading")
        val blink by motion.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
            label = "blink",
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {},
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.ad_logo),
                    contentDescription = stringResource(R.string.ad_game_name),
                    modifier = Modifier.width(300.dp),
                )
                Spacer(Modifier.height(26.dp))
                AdSpinningDisc(44.dp)
                Spacer(Modifier.height(18.dp))
                Text(
                    stringResource(R.string.ad_loading),
                    style = AdDisplayStyle.copy(fontSize = 13.sp),
                    modifier = Modifier.graphicsLayer { alpha = blink },
                )
            }
            AdScanlines()
        }
    }
}

/** "just now", "5 minutes ago", "yesterday", ... */
@Composable
fun relativeTime(time: Long): String {
    val now = System.currentTimeMillis()
    return if (now - time < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.ad_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
}

fun sizeText(context: android.content.Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

private fun importErrorText(context: android.content.Context, error: Throwable): String = when {
    error is AdFiles.ImportException && error.reason == AdFiles.ImportException.Reason.NO_DISC ->
        context.getString(R.string.ad_install_no_disc)
    error is AdFiles.ImportException && error.reason == AdFiles.ImportException.Reason.MISSING_TRACKS ->
        context.getString(R.string.ad_install_missing_tracks, error.detail)
    else -> context.getString(R.string.ad_install_error, error.message ?: error.javaClass.simpleName)
}
