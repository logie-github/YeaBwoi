// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.v3gold.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.logie.v3gold.GoldConfig
import com.logie.v3gold.GoldGame
import com.logie.v3gold.GoldLaunch
import com.logie.v3gold.GoldUpdateActivity
import com.logie.v3gold.GoldUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vita3k.emulator.BuildConfig
import org.vita3k.emulator.R
import org.vita3k.emulator.data.AppInfo
import org.vita3k.emulator.ui.viewmodel.AppsListViewModel

/**
 * V3 Gold's main menu, the first screen of the app (in place of Vita3K's app list). Opening the
 * app goes straight into Persona 4 Golden once it's set up, after offering any update; leaving the
 * game comes back here.
 */
@Composable
fun GoldHomeRoute(
    appsListViewModel: AppsListViewModel,
    onPlay: (AppInfo) -> Unit,
    onOpenFirmwareSetup: () -> Unit,
    onInstallGame: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGameSettings: (AppInfo) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val game = GoldGame.find(appsListViewModel.apps)
    val firmware = appsListViewModel.firmwareInstallState
    val ready = game != null && firmware.hasRequiredComponents
    val bars = remember { Animatable(0f) }
    var launching by remember { mutableStateOf(false) }
    var autoplay by rememberSaveable { mutableStateOf(GoldLaunch.consumeAutoplay()) }
    /** Keeps the loading screen up from opening the app until the game covers it. */
    var holdLoading by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }

    fun play(app: AppInfo) {
        if (launching) return
        launching = true
        scope.launch {
            bars.snapTo(0f)
            bars.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
            delay(120)
            onPlay(app)
        }
    }

    val updateOffer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val app = GoldGame.find(appsListViewModel.apps)
        if (result.resultCode != GoldUpdateActivity.RESULT_INSTALLING && app != null) {
            holdLoading = true
            play(app)
        }
        autoplay = false
    }

    // Opening the app: offer a newer version if there is one, then start the game.
    LaunchedEffect(appsListViewModel.initialized, appsListViewModel.loading) {
        if (!autoplay || !appsListViewModel.initialized || appsListViewModel.loading) return@LaunchedEffect
        val app = GoldGame.find(appsListViewModel.apps)
        if (app == null || !appsListViewModel.firmwareInstallState.hasRequiredComponents) {
            autoplay = false
            return@LaunchedEffect
        }
        val update = if (GoldUpdater.isConfigured) {
            // Offline or GitHub unreachable: just play, and check again next time.
            withContext(Dispatchers.IO) { runCatching { GoldUpdater.findUpdate() }.getOrNull() }
        } else {
            null
        }
        if (update != null) {
            updateOffer.launch(GoldUpdateActivity.intent(context, update))
        } else {
            holdLoading = true
            autoplay = false
            play(app)
        }
    }

    // Back from the game.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (launching) {
            launching = false
            holdLoading = false
            scope.launch { bars.snapTo(0f) }
        }
    }

    // Dark status bar icons over the yellow header.
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = true
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = (view.context as? Activity)?.window ?: return@onDispose
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = false
        }
    }

    GoldTheme {
        Box(Modifier.fillMaxSize()) {
            GoldHomeScreen(
                game = game,
                firmwareReady = firmware.hasRequiredComponents,
                loading = !appsListViewModel.initialized || appsListViewModel.loading,
                ready = ready,
                vita3kVersion = appsListViewModel.appVersion,
                onPlay = { game?.let { play(it) } },
                onControls = { showControls = true },
                onFirmware = onOpenFirmwareSetup,
                onInstallGame = onInstallGame,
                onSettings = onOpenSettings,
                onGameSettings = { game?.let(onOpenGameSettings) },
                onLibrary = onOpenLibrary,
                onUpdates = { GoldUpdateActivity.launch(context) },
            )
            LoadingOverlay(visible = autoplay || holdLoading)
            GoldTvBars(progress = bars.value)
        }
        if (showControls) {
            GoldControlsDialog(onDismiss = { showControls = false })
        }
    }
}

@Composable
private fun GoldHomeScreen(
    game: AppInfo?,
    firmwareReady: Boolean,
    loading: Boolean,
    ready: Boolean,
    vita3kVersion: String,
    onPlay: () -> Unit,
    onControls: () -> Unit,
    onFirmware: () -> Unit,
    onInstallGame: () -> Unit,
    onSettings: () -> Unit,
    onGameSettings: () -> Unit,
    onLibrary: () -> Unit,
    onUpdates: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        GoldTileBackground()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GoldHeader(screenWidth)
            Column(
                Modifier
                    .widthIn(max = 880.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when {
                    ready && game != null -> {
                        PlayButton(onClick = onPlay)
                        Text(
                            stringResource(R.string.gold_home_game_line, game.title, game.titleId),
                            style = MaterialTheme.typography.titleMedium,
                            color = GoldColors.Ink,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }

                    !loading -> SetupSteps(
                        firmwareReady = firmwareReady,
                        gameInstalled = game != null,
                        onFirmware = onFirmware,
                        onInstallGame = onInstallGame,
                    )

                    else -> Spacer(Modifier.height(76.dp))
                }

                SectionTitle(stringResource(R.string.gold_home_menu))
                val tiles = listOf(
                    HomeTile(Icons.Default.TouchApp, stringResource(R.string.gold_tile_controls),
                        stringResource(R.string.gold_tile_controls_summary), onClick = onControls),
                    HomeTile(Icons.Default.Tune, stringResource(R.string.gold_tile_game_settings),
                        stringResource(R.string.gold_tile_game_settings_summary), enabled = game != null,
                        onClick = onGameSettings),
                    HomeTile(Icons.Default.Settings, stringResource(R.string.gold_tile_settings),
                        stringResource(R.string.gold_tile_settings_summary), onClick = onSettings),
                    HomeTile(Icons.Default.Download, stringResource(R.string.gold_tile_install),
                        stringResource(R.string.gold_tile_install_summary), onClick = onInstallGame),
                    HomeTile(Icons.Default.Memory, stringResource(R.string.gold_tile_firmware),
                        stringResource(if (firmwareReady) R.string.gold_tile_firmware_ready else R.string.gold_tile_firmware_missing),
                        onClick = onFirmware),
                    HomeTile(Icons.Default.SystemUpdate, stringResource(R.string.gold_tile_updates),
                        stringResource(R.string.gold_tile_updates_summary, BuildConfig.VERSION_NAME), onClick = onUpdates),
                    HomeTile(Icons.Default.Apps, stringResource(R.string.gold_tile_library),
                        stringResource(R.string.gold_tile_library_summary), onClick = onLibrary),
                )
                val columns = (screenWidth / 200.dp).toInt().coerceIn(2, 4)
                TileGrid(tiles, columns)

                Text(
                    stringResource(R.string.gold_version, stringResource(R.string.app_name), BuildConfig.VERSION_NAME, vita3kVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = GoldColors.InkSoft,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp, bottom = 20.dp),
                )
            }
        }
    }
}

/** The artwork with the logo popping in over its bottom edge. */
@Composable
private fun GoldHeader(screenWidth: Dp) {
    val headerHeight = (screenWidth / 2.6f).coerceIn(170.dp, 360.dp)
    val logoWidth = min(screenWidth * 0.42f, 340.dp)
    val logoHeight = logoWidth * 0.72f
    val overhang = logoHeight * 0.42f

    val motion = rememberInfiniteTransition(label = "header")
    val zoom by motion.animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(tween(16_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "zoom",
    )
    val float by motion.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(2_600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "float",
    )
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(150)
        appear.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 180f))
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(headerHeight + overhang)
    ) {
        Image(
            painter = painterResource(R.drawable.gold_header),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = BiasAlignment(0.6f, -0.3f),
            modifier = Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .clip(SlantedBottom)
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    transformOrigin = TransformOrigin(0.75f, 0.35f)
                },
        )
        // The black stripe along the slanted edge.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(headerHeight + 10.dp)
        ) {
            val stripe = 9.dp.toPx()
            val bottom = headerHeight.toPx()
            val path = Path().apply {
                moveTo(0f, bottom - stripe / 2)
                lineTo(size.width, bottom * SLANT - stripe / 2)
                lineTo(size.width, bottom * SLANT + stripe / 2)
                lineTo(0f, bottom + stripe / 2)
                close()
            }
            drawPath(path, GoldColors.Ink)
        }
        Image(
            painter = painterResource(R.drawable.gold_logo),
            contentDescription = stringResource(R.string.gold_game_name),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp)
                .width(logoWidth)
                .height(logoHeight)
                .graphicsLayer {
                    val value = appear.value
                    val scale = 0.55f + 0.45f * value
                    scaleX = scale
                    scaleY = scale
                    rotationZ = -10f * (1f - value) - 2f
                    translationY = float.dp.toPx()
                    alpha = value.coerceIn(0f, 1f)
                },
        )
    }
}

/** The header's bottom edge rises toward the right. */
private const val SLANT = 0.86f

private val SlantedBottom = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height * SLANT)
    lineTo(0f, size.height)
    close()
}

private val Parallelogram = GenericShape { size, _ ->
    val skew = size.height * 0.3f
    moveTo(skew, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width - skew, size.height)
    lineTo(0f, size.height)
    close()
}

@Composable
private fun PlayButton(onClick: () -> Unit) {
    val motion = rememberInfiniteTransition(label = "play")
    val pulse by motion.animateFloat(
        initialValue = 1f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val shine by motion.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(2_400, delayMillis = 1_600, easing = LinearEasing)),
        label = "shine",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, spring(dampingRatio = 0.5f), label = "press")

    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, end = 6.dp)
            .height(78.dp)
            .graphicsLayer {
                scaleX = pulse * press
                scaleY = pulse * press
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = 6.dp.toPx()
                    translationY = 6.dp.toPx()
                }
                .background(GoldColors.Paper, Parallelogram)
        )
        Row(
            Modifier
                .fillMaxSize()
                .clip(Parallelogram)
                .background(GoldColors.Ink)
                .drawWithContent {
                    drawContent()
                    val x = shine * size.width
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.32f), Color.Transparent),
                            start = Offset(x - 90f, 0f),
                            end = Offset(x + 90f, size.height),
                        )
                    )
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = GoldColors.Yellow,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.gold_home_play),
                style = GoldDisplayStyle,
                fontSize = 36.sp,
                color = GoldColors.Yellow,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = GoldDisplayStyle,
        fontSize = 20.sp,
        color = GoldColors.Ink,
        modifier = Modifier.padding(top = 10.dp),
    )
}

/** A white card with a thick ink outline and a hard ink shadow that it presses into. */
@Composable
private fun InkCard(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.6f), label = "sink")
    val shape = RoundedCornerShape(14.dp)
    var clickModifier: Modifier = Modifier
    if (onClick != null) {
        clickModifier = Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
    }
    Box(
        modifier
            .padding(end = 5.dp, bottom = 5.dp)
            .graphicsLayer {
                translationX = 4.dp.toPx() * sink
                translationY = 4.dp.toPx() * sink
                alpha = if (enabled) 1f else 0.5f
            }
            .drawBehind {
                val shadow = 5.dp.toPx() * (1f - sink)
                drawRoundRect(
                    GoldColors.Ink,
                    topLeft = Offset(shadow, shadow),
                    size = size,
                    cornerRadius = CornerRadius(14.dp.toPx()),
                )
            }
            .background(GoldColors.Paper, shape)
            .border(2.5.dp, GoldColors.Ink, shape)
            .then(clickModifier)
    ) {
        content()
    }
}

private class HomeTile(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun TileGrid(tiles: List<HomeTile>, columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { columnIndex, tile ->
                    TileCard(tile, rowIndex * columns + columnIndex, Modifier.weight(1f))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TileCard(tile: HomeTile, index: Int, modifier: Modifier) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(200L + index * 60L)
        appear.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    InkCard(
        modifier = modifier.graphicsLayer {
            alpha = appear.value
            translationY = (1f - appear.value) * 28.dp.toPx()
        },
        enabled = tile.enabled,
        onClick = tile.onClick,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(GoldColors.Ink, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tile.icon, contentDescription = null, tint = GoldColors.Yellow, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tile.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = GoldColors.Ink,
                    maxLines = 1,
                )
                Text(
                    tile.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = GoldColors.InkSoft,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun SetupSteps(
    firmwareReady: Boolean,
    gameInstalled: Boolean,
    onFirmware: () -> Unit,
    onInstallGame: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(R.string.gold_home_setup))
        SetupStep(
            number = 1,
            title = stringResource(R.string.gold_setup_firmware_title),
            body = stringResource(R.string.gold_setup_firmware_body),
            done = firmwareReady,
            action = stringResource(R.string.gold_setup_firmware_action),
            onAction = onFirmware,
        )
        SetupStep(
            number = 2,
            title = stringResource(R.string.gold_setup_game_title),
            body = stringResource(R.string.gold_setup_game_body),
            done = gameInstalled,
            action = stringResource(R.string.gold_setup_game_action),
            onAction = onInstallGame,
        )
    }
}

@Composable
private fun SetupStep(number: Int, title: String, body: String, done: Boolean, action: String, onAction: () -> Unit) {
    InkCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(if (done) GoldColors.Green else GoldColors.Yellow, CircleShape)
                        .border(2.dp, GoldColors.Ink, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = GoldColors.Paper)
                    } else {
                        Text(number.toString(), fontWeight = FontWeight.Black, color = GoldColors.Ink, fontSize = 18.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = GoldColors.Ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(if (done) R.string.gold_setup_done else R.string.gold_setup_needed),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (done) GoldColors.Green else GoldColors.Red,
                )
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = GoldColors.InkSoft)
            Box(
                Modifier
                    .align(Alignment.End)
                    .clip(Parallelogram)
                    .background(GoldColors.Ink)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 26.dp, vertical = 10.dp)
            ) {
                Text(action.uppercase(), style = GoldDisplayStyle, fontSize = 16.sp, color = GoldColors.Yellow)
            }
        }
    }
}

/** Shown while the app goes straight into the game, so the menu doesn't flash first. */
@Composable
private fun LoadingOverlay(visible: Boolean) {
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(0)), exit = fadeOut(tween(350))) {
        val motion = rememberInfiniteTransition(label = "loading")
        val beat by motion.animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "beat",
        )
        val blink by motion.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
            label = "blink",
        )
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {},
            contentAlignment = Alignment.Center,
        ) {
            GoldTileBackground()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.gold_logo),
                    contentDescription = stringResource(R.string.gold_game_name),
                    modifier = Modifier
                        .width(300.dp)
                        .graphicsLayer {
                            scaleX = beat
                            scaleY = beat
                            rotationZ = -2f
                        },
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    stringResource(R.string.gold_home_loading).uppercase(),
                    style = GoldDisplayStyle,
                    fontSize = 18.sp,
                    color = GoldColors.Ink.copy(alpha = blink),
                )
            }
        }
    }
}

/** The gesture guide from the main menu, with the switches that also live in the in-game menu. */
@Composable
private fun GoldControlsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var gestures by remember { mutableStateOf(GoldConfig(context).gesturesEnabled) }
    var haptics by remember { mutableStateOf(GoldConfig(context).haptics) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GoldColors.Paper,
        title = { Text(stringResource(R.string.gold_controls_title), style = GoldDisplayStyle, fontSize = 24.sp) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DialogSwitch(stringResource(R.string.gold_gestures_switch), gestures) {
                    gestures = it
                    GoldConfig(context).gesturesEnabled = it
                }
                DialogSwitch(stringResource(R.string.gold_haptics_switch), haptics) {
                    haptics = it
                    GoldConfig(context).haptics = it
                }
                GoldGestureGuide()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok), fontWeight = FontWeight.Bold) }
        },
    )
}

@Composable
private fun DialogSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
