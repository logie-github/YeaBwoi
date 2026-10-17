// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcrafts.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.logie.warcrafts.BuildConfig
import com.logie.warcrafts.ImportService
import com.logie.warcrafts.Imports
import com.logie.warcrafts.R
import com.logie.warcrafts.RtsFiles
import com.logie.warcrafts.RtsUpdateActivity
import com.logie.warcrafts.RtsUpdater
import com.logie.warcrafts.game.Game
import com.logie.warcrafts.game.GameSettings
import com.logie.warcrafts.library.Needs
import com.logie.warcrafts.library.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class HomeDialog { FILES, GRAPHICS, CONTROLS, GAMES }

/**
 * The launcher, the first screen of the app: the chosen game's picture and logo in its own look,
 * and under them Press start, which starts the game ([Game]) once its files are in. A chip in the
 * picture's corner (and the Games tile) switches to another of the four; each has its own colours,
 * picture, logo and weather ([RtsLook]). Opening the app offers any update and starts nothing.
 */
@Composable
fun RtsHomeRoute(activity: Activity, checkUpdateOnOpen: Boolean) {
    val context = LocalContext.current
    val title by RtsFiles.title(context).collectAsState()
    val current = title ?: Title.TFT
    var refresh by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    var checkUpdate by rememberSaveable { mutableStateOf(checkUpdateOnOpen) }
    val imports by Imports.state.collectAsState()
    val game by Game.state.collectAsState()
    val needs = remember(refresh, imports, current) { RtsFiles.needs(context, current.install) }
    val hasGame = Needs.ready(needs)

    val updateOffer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkUpdate = false
    }
    LaunchedEffect(Unit) {
        if (!checkUpdate) return@LaunchedEffect
        val update = if (RtsUpdater.isConfigured) {
            withContext(Dispatchers.IO) { runCatching { RtsUpdater.findUpdate() }.getOrNull() }
        } else {
            null
        }
        if (update != null) updateOffer.launch(RtsUpdateActivity.intent(context, update)) else checkUpdate = false
    }

    // How an import went, as soon as it's done.
    LaunchedEffect(imports) {
        if ((imports is Imports.State.Done || imports is Imports.State.Failed) && dialog == null) dialog = HomeDialog.FILES
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { refresh++ }

    Crossfade(current, animationSpec = tween(600), label = "look") { shown ->
        RtsTheme(RtsLook.of(shown)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                HomeScreen(
                    title = shown,
                    needs = needs,
                    hasGame = hasGame,
                    imports = imports,
                    game = game,
                    onPlay = { Game.play(activity, shown) },
                    onDialog = { dialog = it },
                )
            }
        }
    }
    RtsTheme(RtsLook.of(current)) {
        when (dialog) {
            HomeDialog.FILES -> GameFilesDialog(current, onDismiss = { dialog = null; Imports.clearResult(); refresh++ })
            HomeDialog.GRAPHICS -> GraphicsDialog(current, onDismiss = { dialog = null; refresh++ })
            HomeDialog.CONTROLS -> ControlsDialog(onDismiss = { dialog = null })
            HomeDialog.GAMES -> GameChooser(current, onDismiss = { dialog = null }) { chosen ->
                RtsFiles.selectTitle(context, chosen)
                dialog = null
            }
            null -> {}
        }
        (game as? Game.State.Failed)?.let { failed ->
            RtsDialog(stringResource(R.string.rts_game_failed_title), onDismiss = { Game.dismiss() }) {
                Text(failed.message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun HomeScreen(
    title: Title,
    needs: List<Pair<Needs.Need, Boolean>>,
    hasGame: Boolean,
    imports: Imports.State,
    game: Game.State,
    onPlay: () -> Unit,
    onDialog: (HomeDialog) -> Unit,
) {
    val context = LocalContext.current
    val look = LocalLook.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        RtsBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(screenWidth, screenHeight, onSwitch = { onDialog(HomeDialog.GAMES) })
            // The Fold's inside screen (about 830 dp wide either way up): the game and its checklist
            // on the left, the menu on the right, so everything fits without scrolling far.
            val wide = screenWidth >= 700.dp
            val play: @Composable () -> Unit = {
                val working = imports as? Imports.State.Working
                when {
                    working != null -> ImportPanel(working) { onDialog(HomeDialog.FILES) }
                    game is Game.State.Preparing -> GamePanel(game)
                    hasGame -> PressStart(onPlay)
                    else -> Notice(
                        stringResource(R.string.rts_home_no_game, stringResource(ImportService.installName(title.install))),
                        stringResource(R.string.rts_home_add_files),
                    ) { onDialog(HomeDialog.FILES) }
                }
                NeedsPanel(title, needs, hasGame) { onDialog(HomeDialog.FILES) }
            }
            val menu: @Composable (Int) -> Unit = { columns ->
                RtsTitle(stringResource(R.string.rts_home_menu), Modifier.padding(top = if (wide) 0.dp else 14.dp), style = MaterialTheme.typography.titleLarge)
                val resolution = GameSettings.resolution(context)
                val (width, height) = GameSettings.gameSize(context, resolution)
                val tiles = listOf(
                    HomeTile(Icons.Default.FolderOpen, stringResource(R.string.rts_tile_files),
                        stringResource(if (hasGame) R.string.rts_tile_files_ready else R.string.rts_tile_files_none)) { onDialog(HomeDialog.FILES) },
                    HomeTile(Icons.Default.SwapHoriz, stringResource(R.string.rts_tile_games), stringResource(look.name)) { onDialog(HomeDialog.GAMES) },
                    HomeTile(Icons.Default.Tune, stringResource(R.string.rts_tile_graphics),
                        stringResource(R.string.rts_tile_graphics_summary, stringResource(resolution.label), width, height)) { onDialog(HomeDialog.GRAPHICS) },
                    HomeTile(Icons.Default.Gesture, stringResource(R.string.rts_tile_controls), stringResource(R.string.rts_tile_controls_summary)) {
                        onDialog(HomeDialog.CONTROLS)
                    },
                    HomeTile(Icons.Default.SystemUpdate, stringResource(R.string.rts_tile_updates),
                        stringResource(R.string.rts_tile_updates_summary, BuildConfig.VERSION_NAME)) { RtsUpdateActivity.launch(context) },
                )
                TileGrid(tiles, columns)
            }
            if (wide) {
                Row(
                    Modifier
                        .widthIn(max = 1100.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(14.dp)) { play() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) { menu(2) }
                }
            } else {
                Column(
                    Modifier
                        .widthIn(max = 860.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    play()
                    menu((screenWidth / 260.dp).toInt().coerceIn(1, 3).let { if (screenWidth >= 360.dp) maxOf(it, 2) else it })
                }
            }
            Text(
                stringResource(R.string.rts_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp, bottom = 22.dp),
            )
        }
    }
}

/**
 * What the game needs, item by item, each ticked once it's in the app: the user is told exactly
 * what to bring and where it is on a PC, and what's still missing after an import.
 */
@Composable
private fun NeedsPanel(title: Title, needs: List<Pair<Needs.Need, Boolean>>, ready: Boolean, onFiles: () -> Unit) {
    val context = LocalContext.current
    val look = LocalLook.current
    val install = title.install
    RtsWindow(Modifier.fillMaxWidth(), onClick = onFiles) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RtsTitle(
                stringResource(if (ready) R.string.rts_needs_title_ready else R.string.rts_needs_title),
                style = MaterialTheme.typography.titleLarge,
            )
            if (!ready) Text(stringResource(needsWhere(install)), style = MaterialTheme.typography.bodyMedium)
            for ((need, met) in needs) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        when {
                            met -> Icons.Default.CheckCircle
                            need.required -> Icons.Default.Cancel
                            else -> Icons.Default.RadioButtonUnchecked
                        },
                        contentDescription = null,
                        tint = when {
                            met -> look.good
                            need.required -> look.blood
                            else -> look.mist
                        },
                        modifier = Modifier.size(20.dp).padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(needLabel(need.item)), style = MaterialTheme.typography.titleSmall)
                        Text(
                            need.paths.joinToString("  ·  ") { it.replace('/', '\\') },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            color = look.accent,
                        )
                        Text(stringResource(needNote(need.item)), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            val free = remember { android.os.StatFs(context.filesDir.path).availableBytes }
            Text(
                stringResource(R.string.rts_needs_space, android.text.format.Formatter.formatShortFileSize(context, free)),
                style = MaterialTheme.typography.bodySmall,
            )
            if (install != com.logie.warcrafts.library.Install.WC3) {
                Text(stringResource(R.string.rts_needs_battlenet), style = MaterialTheme.typography.bodySmall, color = look.gold)
            }
        }
    }
}

private fun needsWhere(install: com.logie.warcrafts.library.Install): Int = when (install) {
    com.logie.warcrafts.library.Install.WC1 -> R.string.rts_needs_where_wc1
    com.logie.warcrafts.library.Install.WC2 -> R.string.rts_needs_where_wc2
    com.logie.warcrafts.library.Install.WC3 -> R.string.rts_needs_where_wc3
}

private fun needLabel(item: Needs.Item): Int = when (item) {
    Needs.Item.PROGRAM -> R.string.rts_need_program
    Needs.Item.DATA -> R.string.rts_need_data
    Needs.Item.BUILD_INFO -> R.string.rts_need_build_info
    Needs.Item.MAPS -> R.string.rts_need_maps
    Needs.Item.CD_KEYS -> R.string.rts_need_keys
}

private fun needNote(item: Needs.Item): Int = when (item) {
    Needs.Item.PROGRAM -> R.string.rts_need_program_note
    Needs.Item.DATA -> R.string.rts_need_data_note
    Needs.Item.BUILD_INFO -> R.string.rts_need_build_info_note
    Needs.Item.MAPS -> R.string.rts_need_maps_note
    Needs.Item.CD_KEYS -> R.string.rts_need_keys_note
}

/** The game's picture, its weather over it, slowly closing in; the logo over its lower edge. */
@Composable
private fun Header(screenWidth: Dp, screenHeight: Dp, onSwitch: () -> Unit) {
    val look = LocalLook.current
    // On the Fold's inside screen the picture leaves room for the game's panel and checklist.
    val headerHeight = if (screenWidth >= 700.dp) {
        min(screenHeight * 0.4f, 380.dp)
    } else {
        min((screenWidth * 0.62f).coerceIn(240.dp, 460.dp), screenHeight * 0.6f)
    }
    val logoWidth = min(min(screenWidth * 0.8f, headerHeight / look.logoRatio * 0.62f), 460.dp)
    val logoHeight = logoWidth * look.logoRatio

    val motion = rememberInfiniteTransition(label = "header")
    val drift by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(30_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift")
    val breathe by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(3_600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(150)
        reveal.animateTo(1f, tween(1_400, easing = FastOutSlowInEasing))
    }

    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxWidth().padding(top = statusBar).height(headerHeight + logoHeight * 0.55f)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .clipToBounds()
        ) {
            Image(
                painter = painterResource(look.header),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = look.headerAlignment,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val zoom = 1.03f + 0.05f * drift
                        scaleX = zoom
                        scaleY = zoom
                        transformOrigin = TransformOrigin((look.headerAlignment.horizontalBias + 1f) / 2f, 0.3f)
                        alpha = reveal.value
                    },
            )
            RtsWeather(alpha = reveal.value)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to look.abyss))
            )
        }
        Image(
            painter = painterResource(look.logo),
            contentDescription = stringResource(look.name),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = headerHeight - logoHeight * 0.45f)
                .width(logoWidth)
                .height(logoHeight)
                .graphicsLayer {
                    val value = reveal.value
                    alpha = ((value - 0.2f) / 0.8f).coerceIn(0f, 1f)
                    val scale = (0.95f + 0.05f * value) * (1f + 0.008f * breathe)
                    scaleX = scale
                    scaleY = scale
                },
        )
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .graphicsLayer { alpha = reveal.value }
                .clip(CircleShape)
                .background(look.abyss.copy(alpha = 0.5f))
                .clickable(onClick = onSwitch)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = look.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.rts_games_chip), style = RtsDisplayStyle.copy(fontSize = 13.sp, color = look.text))
        }
    }
}

/** The big button: "Press start", breathing. */
@Composable
private fun PressStart(onClick: () -> Unit) {
    val look = LocalLook.current
    val motion = rememberInfiniteTransition(label = "start")
    val glow by motion.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1_800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    RtsWindow(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 22.dp), onClick = onClick) {
        Text(
            stringResource(R.string.rts_press_start),
            style = RtsDisplayStyle.copy(fontSize = 22.sp, color = look.gold, shadow = RtsGoldShadow),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = glow },
        )
    }
}

@Composable
private fun GamePanel(game: Game.State.Preparing) {
    val look = LocalLook.current
    RtsWindow(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RtsSpinner(28.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(
                        when (game.step) {
                            Game.Step.RUNTIME -> R.string.rts_game_step_runtime
                            Game.Step.WINDOWS -> R.string.rts_game_step_windows
                            Game.Step.STARTING -> R.string.rts_game_step_starting
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            game.progress?.let { RtsProgressLine(it) }
            if (game.step == Game.Step.RUNTIME) {
                Text(stringResource(R.string.rts_game_step_runtime_note), style = MaterialTheme.typography.bodySmall, color = look.mist)
            }
        }
    }
}

@Composable
private fun ImportPanel(state: Imports.State.Working, onOpen: () -> Unit) {
    RtsWindow(Modifier.fillMaxWidth(), onClick = onOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.rts_import_title, stringResource(ImportService.installName(state.install))),
                style = MaterialTheme.typography.titleMedium,
            )
            RtsProgressLine(state.progress ?: 0f)
            state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        }
    }
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    RtsWindow(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            RtsButton(action, onClick = onAction)
        }
    }
}

private class HomeTile(val icon: ImageVector, val title: String, val subtitle: String, val onClick: () -> Unit)

@Composable
private fun TileGrid(tiles: List<HomeTile>, columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in tiles.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (tile in row) TileWindow(tile, Modifier.weight(1f))
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TileWindow(tile: HomeTile, modifier: Modifier) {
    val look = LocalLook.current
    RtsWindow(modifier.height(96.dp), padding = PaddingValues(14.dp), onClick = tile.onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(tile.icon, contentDescription = null, tint = look.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(tile.title, style = RtsDisplayStyle.copy(fontSize = 14.sp, color = look.text), maxLines = 1)
            }
            Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}
