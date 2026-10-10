// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe.ui

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
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Hd
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.logie.pe.BuildConfig
import com.logie.pe.GameActivity
import com.logie.pe.PeFiles
import com.logie.pe.PeUpdateActivity
import com.logie.pe.PeUpdater
import com.logie.pe.R
import com.logie.pe.hd.HdTextures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The title screen, the first screen of the app. Opening the app goes straight into the game once
 * a disc is installed (after offering any update); leaving the game comes back here.
 */
@Composable
fun PeHomeRoute(activity: Activity, autoplayOnOpen: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val launch = remember(refresh) { PeFiles.launchFile(context) }
    val hasDisc1 = remember(refresh) { PeFiles.hasDisc(context, 1) }
    val hasDisc2 = remember(refresh) { PeFiles.hasDisc(context, 2) }
    // The game's own saves need a real BIOS (from the next time the game starts).
    val hasBios = remember(refresh) { PeFiles.bios(context) != null }

    val fade = remember { Animatable(0f) }
    var launching by remember { mutableStateOf(false) }
    var autoplay by rememberSaveable { mutableStateOf(autoplayOnOpen) }
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    var importing by remember { mutableStateOf<Float?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var biosError by remember { mutableStateOf<String?>(null) }
    /** The disc the files being picked are for, or null to tell from their names. */
    var importTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    val hdState by HdTextures.state.collectAsState()

    /**
     * Boots the game, to its own title screen, after the screen fades to black; straight from black
     * when the app is opening into the game ([fromLoading]).
     */
    fun play(fromLoading: Boolean = false) {
        if (launching || PeFiles.launchFile(context) == null) return
        launching = true
        scope.launch {
            if (fromLoading) {
                fade.snapTo(1f)
            } else {
                fade.snapTo(0f)
                fade.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
            }
            activity.startActivity(GameActivity.intent(context))
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
    }

    val updateOffer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        autoplay = false
        if (result.resultCode != PeUpdateActivity.RESULT_INSTALLING) play(fromLoading = true)
    }

    // Opening the app: offer a newer version if there is one, then start the game.
    LaunchedEffect(Unit) {
        if (!autoplay) return@LaunchedEffect
        // An HD texture pack unpacking, or just done: stay here to see how it goes.
        if (launch == null || hdState != HdTextures.State.Idle) {
            autoplay = false
            return@LaunchedEffect
        }
        val update = if (PeUpdater.isConfigured) {
            // Offline or GitHub unreachable: just play, and check again next time.
            withContext(Dispatchers.IO) { runCatching { PeUpdater.findUpdate() }.getOrNull() }
        } else {
            null
        }
        if (update != null) {
            updateOffer.launch(PeUpdateActivity.intent(context, update))
        } else {
            play(fromLoading = true)
            autoplay = false
        }
    }

    // How installing an HD texture pack went, as soon as it's done.
    LaunchedEffect(hdState) {
        if ((hdState is HdTextures.State.Done || hdState is HdTextures.State.Failed) && dialog == null) dialog = HomeDialog.HD
    }

    // Back from the game.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        refresh++
        if (launching) {
            launching = false
            scope.launch { fade.animateTo(0f, tween(400)) }
        }
    }

    val addBios = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { PeFiles.importBios(context, uri) } }
            refresh++
            result.fold(
                onSuccess = { Toast.makeText(context, R.string.pe_bios_added, Toast.LENGTH_SHORT).show() },
                onFailure = {
                    biosError = if (it is PeFiles.ImportException) context.getString(R.string.pe_bios_not_a_bios)
                    else context.getString(R.string.pe_card_failed, it.message ?: "")
                },
            )
        }
    }

    val pickDiscs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val target = importTarget
        importing = 0f
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PeFiles.importDiscs(context, uris, target) { done, total ->
                        importing = if (total > 0) done.toFloat() / total else 0f
                    }
                }
            }
            importing = null
            refresh++
            result.exceptionOrNull()?.let { importError = importErrorText(context, it) }
        }
    }

    /** Picks disc image files: for [disc], or with null, for whichever discs they are. */
    fun pick(disc: Int?) {
        importTarget = disc
        pickDiscs.launch(arrayOf("*/*"))
    }

    PeTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HomeScreen(
                installed = launch != null,
                hasDisc1 = hasDisc1,
                hasDisc2 = hasDisc2,
                onAddBios = if (hasBios) null else ({ addBios.launch(arrayOf("*/*")) }),
                importing = importing,
                onPlay = { play() },
                onPick = { pick(it) },
                onDialog = { dialog = it },
            )
            // Into the game: everything fades to black.
            if (fade.value > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = fade.value }
                        .background(Color.Black)
                        .pointerInput(Unit) {}
                )
            }
            LoadingOverlay(visible = autoplay)
        }

        when (dialog) {
            HomeDialog.CONTROLS -> ControlsDialog(onDismiss = { dialog = null })
            HomeDialog.PICTURE -> PictureDialog(onDismiss = { dialog = null })
            HomeDialog.MEMORY_CARD -> MemoryCardDialog(onDismiss = { dialog = null; refresh++ })
            HomeDialog.BIOS -> BiosDialog(onDismiss = { dialog = null; refresh++ })
            HomeDialog.HD -> HdTexturesDialog(onDismiss = { dialog = null; refresh++ })
            HomeDialog.DISCS -> DiscsDialog(
                onPick = { disc -> dialog = null; pick(disc) },
                onDismiss = { dialog = null; refresh++ },
            )
            null -> {}
        }
        importError?.let { message ->
            PeDialog(title = stringResource(R.string.pe_install_failed), onDismiss = { importError = null }) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
        biosError?.let { message ->
            PeDialog(title = stringResource(R.string.pe_bios_title), onDismiss = { biosError = null }) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

enum class HomeDialog { CONTROLS, PICTURE, MEMORY_CARD, BIOS, DISCS, HD }

@Composable
private fun HomeScreen(
    installed: Boolean,
    hasDisc1: Boolean,
    hasDisc2: Boolean,
    /** Without a BIOS yet: adds one. */
    onAddBios: (() -> Unit)?,
    importing: Float?,
    onPlay: () -> Unit,
    onPick: (disc: Int?) -> Unit,
    onDialog: (HomeDialog) -> Unit,
) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        PeBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PeHeader(screenWidth, screenHeight)
            Column(
                Modifier
                    .widthIn(max = 860.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when {
                    importing != null -> ImportingWindow(importing)
                    installed -> {
                        PressStart(onClick = onPlay)
                        Text(
                            stringResource(R.string.pe_home_boot_line),
                            style = MaterialTheme.typography.bodyMedium,
                            color = PeColors.Mist,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        if (!hasDisc1 || !hasDisc2) {
                            Notice(
                                text = stringResource(if (hasDisc1) R.string.pe_home_no_disc2 else R.string.pe_home_no_disc1),
                                action = stringResource(if (hasDisc1) R.string.pe_disc_add_2 else R.string.pe_disc_add_1),
                                onAction = { onPick(if (hasDisc1) 2 else 1) },
                            )
                        }
                        if (onAddBios != null) {
                            Notice(
                                text = stringResource(R.string.pe_home_no_bios),
                                action = stringResource(R.string.pe_bios_add),
                                onAction = onAddBios,
                            )
                        }
                    }
                    else -> InsertDiscs(onPick = { onPick(null) })
                }

                PeTitle(
                    stringResource(R.string.pe_home_menu),
                    modifier = Modifier.padding(top = 14.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                val tiles = listOf(
                    HomeTile(Icons.Default.TouchApp, stringResource(R.string.pe_tile_controls),
                        stringResource(R.string.pe_tile_controls_summary)) { onDialog(HomeDialog.CONTROLS) },
                    HomeTile(Icons.Default.Tv, stringResource(R.string.pe_tile_picture),
                        stringResource(R.string.pe_tile_picture_summary)) { onDialog(HomeDialog.PICTURE) },
                    HomeTile(Icons.Default.Hd, stringResource(R.string.pe_tile_hd), hdTileSummary()) {
                        onDialog(HomeDialog.HD)
                    },
                    HomeTile(Icons.Default.SdCard, stringResource(R.string.pe_tile_memory_card),
                        stringResource(R.string.pe_tile_memory_card_summary)) { onDialog(HomeDialog.MEMORY_CARD) },
                    HomeTile(Icons.Default.Memory, stringResource(R.string.pe_tile_bios),
                        stringResource(if (PeFiles.bios(context) != null) R.string.pe_tile_bios_yours else R.string.pe_tile_bios_builtin)) {
                        onDialog(HomeDialog.BIOS)
                    },
                    HomeTile(Icons.Default.Album, stringResource(R.string.pe_tile_discs),
                        stringResource(
                            when {
                                hasDisc1 && hasDisc2 -> R.string.pe_tile_discs_both
                                hasDisc1 || hasDisc2 -> R.string.pe_tile_discs_one
                                else -> R.string.pe_tile_discs_none
                            }
                        )) {
                        if (installed) onDialog(HomeDialog.DISCS) else onPick(null)
                    },
                    HomeTile(Icons.Default.SystemUpdate, stringResource(R.string.pe_tile_updates),
                        stringResource(R.string.pe_tile_updates_summary, BuildConfig.VERSION_NAME)) {
                        PeUpdateActivity.launch(context)
                    },
                )
                val columns = (screenWidth / 260.dp).toInt().coerceIn(1, 3).let { if (screenWidth >= 360.dp) maxOf(it, 2) else it }
                TileGrid(tiles, columns)

                Text(
                    stringResource(R.string.pe_version, stringResource(R.string.app_name), BuildConfig.VERSION_NAME),
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
 * Aya on the ruined shore with the creature in the fog behind her, drifting slowly closer, and the
 * logo over the dark sky beside her, its glow breathing.
 */
@Composable
private fun PeHeader(screenWidth: Dp, screenHeight: Dp) {
    // The picture's own shape, but leaving room under it for PRESS START on a phone held sideways.
    val headerHeight = min((screenWidth * (9f / 16f)).coerceIn(220.dp, 430.dp), screenHeight * 0.6f)
    val logoWidth = min(min(screenWidth * 0.5f, headerHeight * 1.3f), 400.dp)

    val motion = rememberInfiniteTransition(label = "header")
    val drift by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(26_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val breathe by motion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(200)
        reveal.animateTo(1f, tween(1_600, easing = FastOutSlowInEasing))
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .clipToBounds()
    ) {
        Image(
            painter = painterResource(R.drawable.pe_header),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Aya stands at the right: keep her in when the screen is narrower than the picture.
            alignment = BiasAlignment(0.55f, -0.2f),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val zoom = 1.04f + 0.06f * drift
                    scaleX = zoom
                    scaleY = zoom
                    translationX = -drift * size.width * 0.02f
                    transformOrigin = TransformOrigin(0.75f, 0.35f)
                    alpha = reveal.value
                },
        )
        // The picture sinks into the night below it and to its left, under the logo.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(0f to PeColors.Abyss.copy(alpha = 0.55f), 0.55f to Color.Transparent))
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to PeColors.Abyss))
        )
        PeGrain(alpha = 0.08f)
        Image(
            painter = painterResource(R.drawable.pe_logo),
            contentDescription = stringResource(R.string.pe_game_name),
            modifier = Modifier
                .align(BiasAlignment(-0.92f, 0.25f))
                .padding(start = 12.dp)
                .width(logoWidth)
                .height(logoWidth * (1127f / 2136f))
                .graphicsLayer {
                    val value = reveal.value
                    alpha = ((value - 0.25f) / 0.75f).coerceIn(0f, 1f) * (0.88f + 0.12f * breathe)
                    val scale = 0.94f + 0.06f * value
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

/** `[ press start ]`, glowing like the lamp on the bridge, breathing slowly. */
@Composable
private fun PressStart(onClick: () -> Unit) {
    val motion = rememberInfiniteTransition(label = "start")
    val breathe by motion.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .peGlowBehind(breathe)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "[ ${stringResource(R.string.pe_home_press_start).lowercase()} ]",
            style = PeDisplayStyle.copy(fontSize = 26.sp, color = PeColors.Glow, shadow = PeGlow),
            modifier = Modifier.graphicsLayer { alpha = breathe },
        )
    }
}

/** A line about something missing, with the button that adds it. */
@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = PeColors.Text,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 540.dp),
        )
        PeButton(action, primary = false, onClick = onAction)
    }
}

@Composable
private fun InsertDiscs(onPick: () -> Unit) {
    PeWindow(Modifier.fillMaxWidth(), padding = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PeSpinner(40.dp)
                Spacer(Modifier.width(12.dp))
                PeTitle(stringResource(R.string.pe_setup_title), modifier = Modifier.weight(1f))
            }
            Text(stringResource(R.string.pe_setup_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.pe_setup_bios_note), style = MaterialTheme.typography.bodySmall)
            PeButton(stringResource(R.string.pe_setup_action), modifier = Modifier.align(Alignment.End), onClick = onPick)
        }
    }
}

@Composable
private fun ImportingWindow(progress: Float) {
    PeWindow(Modifier.fillMaxWidth(), padding = PaddingValues(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PeSpinner(40.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.pe_installing, (progress * 100).toInt()).lowercase(),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            PeProgressLine(progress)
        }
    }
}

private class HomeTile(val icon: ImageVector, val title: String, val subtitle: String, val onClick: () -> Unit)

@Composable
private fun TileGrid(tiles: List<HomeTile>, columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { columnIndex, tile ->
                    TileWindow(tile, rowIndex * columns + columnIndex, Modifier.weight(1f))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A panel that surfaces out of the dark, one after another, when the screen opens. */
@Composable
private fun TileWindow(tile: HomeTile, index: Int, modifier: Modifier) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(450L + index * 90L)
        appear.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    PeWindow(
        modifier = modifier.graphicsLayer {
            alpha = appear.value
            translationY = (1f - appear.value) * 14.dp.toPx()
        },
        onClick = tile.onClick,
        padding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(tile.icon, contentDescription = null, tint = PeColors.Teal, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
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
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(0)), exit = fadeOut(tween(400))) {
        val motion = rememberInfiniteTransition(label = "loading")
        val breathe by motion.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Reverse),
            label = "breathe",
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
                    painter = painterResource(R.drawable.pe_logo),
                    contentDescription = stringResource(R.string.pe_game_name),
                    modifier = Modifier.width(300.dp),
                )
                Spacer(Modifier.height(28.dp))
                PeSpinner(46.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.pe_loading).lowercase(),
                    style = PeDisplayStyle.copy(fontSize = 13.sp, color = PeColors.Mist),
                    modifier = Modifier.graphicsLayer { alpha = breathe },
                )
            }
            PeGrain()
        }
    }
}

/** "just now", "5 minutes ago", "yesterday", ... */
@Composable
fun relativeTime(time: Long): String {
    val now = System.currentTimeMillis()
    return if (now - time < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.pe_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
}

fun sizeText(context: android.content.Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

private fun importErrorText(context: android.content.Context, error: Throwable): String {
    val reason = (error as? PeFiles.ImportException)?.reason
    return when (reason) {
        PeFiles.ImportException.Reason.NO_DISC -> context.getString(R.string.pe_install_no_disc)
        PeFiles.ImportException.Reason.MISSING_TRACKS -> context.getString(R.string.pe_install_missing_tracks, (error as PeFiles.ImportException).detail)
        PeFiles.ImportException.Reason.TOO_MANY_DISCS -> context.getString(R.string.pe_install_too_many)
        PeFiles.ImportException.Reason.PBP_HAS_BOTH -> context.getString(R.string.pe_install_pbp_has_both)
        else -> context.getString(R.string.pe_install_error, error.message ?: error.javaClass.simpleName)
    }
}
