// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.ui

import android.content.Context
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
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
import com.logie.warcraft.BuildConfig
import com.logie.warcraft.R
import com.logie.warcraft.Server
import com.logie.warcraft.TitleMusic
import com.logie.warcraft.turtle.TurtleFiles
import com.logie.warcraft.turtle.TurtleGame
import com.logie.warcraft.turtle.TurtleJobs
import com.logie.warcraft.turtle.TurtleLogin
import com.logie.warcraft.turtle.TurtlePackage
import com.logie.warcraft.turtle.TurtleServer
import com.logie.warcraft.turtle.TurtleService
import com.logie.warcraft.turtle.TurtleSettings
import com.logie.warcraft.turtle.TurtleTexts
import com.logie.warcraft.turtle.core.InstallPlan
import com.logie.warcraft.turtle.core.InstallState
import kotlinx.coroutines.delay

/**
 * The launcher when Turtle WoW is the chosen game: the old map of Kalimdor and the Eastern
 * Kingdoms with warm motes drifting up, Turtle WoW's logo, and under them Turtle's own world: its
 * state and the one thing to do next, each of its parts with its own button, and its settings.
 * In turtlecraft.gg's colours. Nothing here reads or changes the 3.3.5a game's files or realm,
 * except [Server.stop] when the user asks to stop ChromieCraft's realm (they share the login port).
 */
@Composable
fun TurtleHomeRoute(onSwitchGame: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val server by TurtleServer.status.collectAsState()
    val job by TurtleJobs.state.collectAsState()
    val game by TurtleGame.state.collectAsState()
    var dialog by remember { mutableStateOf<TurtleDialogKind?>(null) }
    val facts = remember(refresh, job, server.phase) { TurtleFacts.of(context) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) TurtleService.job(context, TurtleJobs.Kind.CLIENT, uri, folder = true, TurtleJobs.Options(withMmaps = TurtleSettings.mmaps(context)))
    }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) TurtleService.job(context, TurtleJobs.Kind.CLIENT, uri, folder = false, TurtleJobs.Options(withMmaps = TurtleSettings.mmaps(context)))
    }
    val actions = TurtleActions(
        install = { dialog = TurtleDialogKind.INSTALL },
        pickFolder = { pickFolder.launch(null) },
        pickZip = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
        job = { kind -> TurtleService.job(context, kind, options = TurtleJobs.Options(withMmaps = TurtleSettings.mmaps(context))) },
        makeData = { withMmaps -> TurtleService.job(context, TurtleJobs.Kind.DATA, options = TurtleJobs.Options(withMmaps = withMmaps)) },
        play = { TurtleGame.play(context) },
        start = { TurtleServer.start(context) },
        stop = { TurtleServer.stop(context) },
        stopChromieCraft = { Server.stop(context) },
        open = { dialog = it },
        switchGame = onSwitchGame,
    )

    LifecycleEventEffect(Lifecycle.Event.ON_START) { refresh++ }

    TurtleTheme {
        Box(Modifier.fillMaxSize().background(TurtleColors.Night)) {
            TurtleHomeScreen(facts, server, job, game, actions)
        }
        TurtleDialogs(dialog, facts, actions, onDismiss = { dialog = null; refresh++ })
    }
}

/** What the home screen's buttons do. */
class TurtleActions(
    val install: () -> Unit,
    val pickFolder: () -> Unit,
    val pickZip: () -> Unit,
    val job: (TurtleJobs.Kind) -> Unit,
    val makeData: (Boolean) -> Unit,
    val play: () -> Unit,
    val start: () -> Unit,
    val stop: () -> Unit,
    val stopChromieCraft: () -> Unit,
    val open: (TurtleDialogKind) -> Unit,
    val switchGame: () -> Unit,
)

enum class TurtleDialogKind { INSTALL, REALM, ACCOUNTS, SETTINGS, UPDATES, REPAIR, UNINSTALL, ABOUT }

/** What's on the phone for Turtle WoW, read when the screen needs it. */
data class TurtleFacts(
    val blocker: String?,
    val pin: TurtlePackage.Pin?,
    val programsInstalled: Boolean,
    val programsCurrent: Boolean,
    val downloadedSoFar: Long,
    val databases: Boolean,
    val runtimeThere: Boolean,
    val runtimeReady: Boolean,
    val client: Boolean,
    val state: InstallState,
    val dataParts: Set<String>,
    val data: Boolean,
    val dataCurrent: Boolean,
    val accounts: Int,
    val savedAccount: String?,
    val used: Long,
    val free: Long,
    val version: String?,
) {
    val plan: List<InstallPlan.Line> = InstallPlan.of(
        InstallPlan.Facts(
            blocker = blocker,
            serverInstalled = programsInstalled,
            serverCurrent = programsCurrent,
            databasesThere = databases,
            clientThere = client,
            dataThere = data,
            dataMatchesClient = dataCurrent,
            hasAccount = when {
                savedAccount != null || accounts > 0 -> true
                accounts == 0 -> false
                else -> null
            },
        )
    )
    val ready: Boolean get() = InstallPlan.ready(plan)
    val next: InstallPlan.Line? get() = InstallPlan.next(plan)

    companion object {
        fun of(context: Context): TurtleFacts {
            val state = TurtleFiles.state(context)
            val client = TurtleFiles.hasClient(context)
            val data = TurtleFiles.hasData(context)
            return TurtleFacts(
                blocker = TurtleFiles.blocker(context),
                pin = TurtlePackage.pinned,
                programsInstalled = TurtlePackage.isInstalled(context),
                programsCurrent = TurtlePackage.isCurrent(context),
                downloadedSoFar = TurtlePackage.downloadedSoFar(context),
                databases = TurtleFiles.hasDatabases(context),
                runtimeThere = TurtleGame.runtimeThere(context),
                runtimeReady = TurtleGame.runtimeReady(context),
                client = client,
                state = state,
                dataParts = TurtleFiles.dataParts(context),
                data = data,
                // Data made before the app kept track of its client is taken as the client's.
                dataCurrent = data && (state.dataFrom == null || state.dataFrom == state.clientExeSha256),
                accounts = TurtleSettings.knownAccounts(context),
                savedAccount = TurtleLogin.get(context)?.name,
                used = runCatching { TurtleFiles.used(context) }.getOrDefault(0L),
                free = TurtleFiles.freeSpace(context),
                version = TurtlePackage.version(context)?.lines()?.firstOrNull { it.startsWith("tortoise ") }?.removePrefix("tortoise ")?.take(7),
            )
        }
    }
}

@Composable
private fun TurtleHomeScreen(facts: TurtleFacts, server: TurtleServer.Status, job: TurtleJobs.State, game: TurtleGame.State, actions: TurtleActions) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        TurtleBackdrop()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TurtleHeader(screenWidth, screenHeight, actions.switchGame)
            Column(
                Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MainPanel(facts, server, job, game, actions)

                TurtleTitle(stringResource(R.string.turtle_components), modifier = Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleLarge)
                Components(facts, job is TurtleJobs.State.Working || TurtleServer.isBusy, actions)

                TurtleTitle(stringResource(R.string.turtle_menu), modifier = Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleLarge)
                val tiles = listOf(
                    Tile(Icons.Default.Dns, stringResource(R.string.turtle_tile_realm), TurtleTexts.server(context, server)) { actions.open(TurtleDialogKind.REALM) },
                    Tile(Icons.Default.AccountCircle, stringResource(R.string.turtle_tile_accounts), facts.savedAccount ?: stringResource(R.string.turtle_tile_accounts_summary)) { actions.open(TurtleDialogKind.ACCOUNTS) },
                    Tile(Icons.Default.Tune, stringResource(R.string.turtle_tile_settings), stringResource(R.string.turtle_tile_settings_summary)) { actions.open(TurtleDialogKind.SETTINGS) },
                    Tile(Icons.Default.SystemUpdate, stringResource(R.string.turtle_tile_updates), stringResource(R.string.turtle_tile_updates_summary, BuildConfig.VERSION_NAME)) { actions.open(TurtleDialogKind.UPDATES) },
                    Tile(Icons.Default.Build, stringResource(R.string.turtle_tile_repair), stringResource(R.string.turtle_tile_repair_summary)) { actions.open(TurtleDialogKind.REPAIR) },
                    Tile(Icons.Default.Info, stringResource(R.string.turtle_tile_about), stringResource(R.string.turtle_tile_about_summary)) { actions.open(TurtleDialogKind.ABOUT) },
                    Tile(Icons.Default.DeleteForever, stringResource(R.string.turtle_tile_uninstall), Formatter.formatShortFileSize(context, facts.used)) { actions.open(TurtleDialogKind.UNINSTALL) },
                    Tile(Icons.Default.SwapHoriz, stringResource(R.string.turtle_tile_switch), stringResource(R.string.turtle_tile_switch_summary)) { actions.switchGame() },
                )
                val columns = (screenWidth / 260.dp).toInt().coerceIn(1, 3).let { if (screenWidth >= 360.dp) maxOf(it, 2) else it }
                TileGrid(tiles, columns)

                Text(
                    stringResource(R.string.turtle_version, BuildConfig.VERSION_NAME, facts.version ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 22.dp),
                )
            }
        }
    }
}

/** The old map, slowly closing in, motes rising over it; the logo over its lower edge. */
@Composable
private fun TurtleHeader(screenWidth: Dp, screenHeight: Dp, onSwitch: () -> Unit) {
    val headerHeight = min((screenWidth * 0.5f).coerceIn(220.dp, 420.dp), screenHeight * 0.55f)
    val logoWidth = min(min(screenWidth * 0.82f, headerHeight * 1.6f), 480.dp)
    val logoHeight = logoWidth * (238f / 748f)
    val motion = rememberInfiniteTransition(label = "header")
    val drift by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(32_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift")
    val breathe by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(3_800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(120)
        reveal.animateTo(1f, tween(1_400, easing = FastOutSlowInEasing))
    }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxWidth().padding(top = statusBar).height(headerHeight + logoHeight * 0.6f)) {
        Box(Modifier.fillMaxWidth().height(headerHeight).clipToBounds()) {
            Image(
                painter = painterResource(R.drawable.turtle_header),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // The Maelstrom between the two continents stays in sight on a narrow screen.
                alignment = BiasAlignment(0f, -0.2f),
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val zoom = 1.02f + 0.05f * drift
                    scaleX = zoom
                    scaleY = zoom
                    transformOrigin = TransformOrigin(0.5f, 0.45f)
                    alpha = reveal.value
                },
            )
            TurtleMotes(alpha = reveal.value)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to TurtleColors.Night)))
        }
        Image(
            painter = painterResource(R.drawable.turtle_logo),
            contentDescription = stringResource(R.string.turtle_name),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = headerHeight - logoHeight * 0.5f)
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
        Chip(Icons.Default.SwapHoriz, stringResource(R.string.turtle_switch_to_wrath), Modifier.align(Alignment.TopStart).padding(10.dp).graphicsLayer { alpha = reveal.value }, onSwitch)
        MusicChip(Modifier.align(Alignment.TopEnd).padding(10.dp).graphicsLayer { alpha = reveal.value })
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(CircleShape).background(TurtleColors.Night.copy(alpha = 0.55f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = TurtleColors.Gold, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = TurtleDisplayStyle.copy(fontSize = 13.sp, color = TurtleColors.Parchment))
    }
}

@Composable
private fun MusicChip(modifier: Modifier) {
    val context = LocalContext.current
    val on by TitleMusic.on.collectAsState()
    Box(
        modifier.size(44.dp).clip(CircleShape).background(TurtleColors.Night.copy(alpha = 0.5f)).clickable { TitleMusic.setOn(context, !on) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (on) Icons.Default.MusicNote else Icons.Default.MusicOff,
            contentDescription = stringResource(if (on) R.string.wow_music_turn_off else R.string.wow_music_turn_on),
            tint = if (on) TurtleColors.Gold else TurtleColors.Stone,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** What Turtle WoW is doing, and the one thing to do next. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainPanel(facts: TurtleFacts, server: TurtleServer.Status, job: TurtleJobs.State, game: TurtleGame.State, actions: TurtleActions) {
    val context = LocalContext.current
    val panel = Modifier.fillMaxWidth()
    when {
        facts.blocker != null -> Notice(stringResource(if (facts.blocker == "arm64") R.string.turtle_error_not_arm64 else R.string.turtle_error_blocker))
        job is TurtleJobs.State.Working -> TurtleWindow(panel, padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TurtleSpinner(40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(TurtleTexts.step(context, job.step), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                }
                job.progress?.let { TurtleProgressLine(it) }
                job.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                stepNote(job.step)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                TurtleButton(stringResource(R.string.turtle_cancel), primary = false, modifier = Modifier.align(Alignment.End)) { TurtleJobs.cancel() }
            }
        }
        job is TurtleJobs.State.Done || job is TurtleJobs.State.Failed -> TurtleWindow(panel, padding = PaddingValues(18.dp)) {
            val failed = job as? TurtleJobs.State.Failed
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TurtleTitle(stringResource(if (failed != null) R.string.turtle_job_failed else R.string.turtle_job_done))
                Text(failed?.message ?: (job as TurtleJobs.State.Done).message, style = MaterialTheme.typography.bodyMedium)
                failed?.log?.takeIf { it.isNotBlank() }?.let { TurtleLog(it) }
                FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TurtleButton(stringResource(R.string.turtle_ok), primary = failed == null) { TurtleJobs.clearResult() }
                    if (failed != null) {
                        TurtleButton(stringResource(R.string.turtle_repair), primary = false) { TurtleJobs.clearResult(); actions.open(TurtleDialogKind.REPAIR) }
                        TurtleButton(stringResource(R.string.turtle_retry)) {
                            TurtleJobs.clearResult()
                            if (failed.kind == TurtleJobs.Kind.CLIENT) actions.pickFolder() else actions.job(failed.kind)
                        }
                    }
                }
            }
        }
        game is TurtleGame.State.Preparing -> TurtleWindow(panel, padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TurtleSpinner(40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(
                            when (game.step) {
                                TurtleGame.Step.RUNTIME -> R.string.turtle_game_step_runtime
                                TurtleGame.Step.WINDOWS -> R.string.turtle_game_step_windows
                                TurtleGame.Step.REALM -> R.string.turtle_game_step_realm
                                TurtleGame.Step.STARTING -> R.string.turtle_game_step_starting
                            }
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                }
                game.progress?.let { TurtleProgressLine(it) }
                TurtleStatusLine(stringResource(R.string.turtle_tile_realm), TurtleTexts.server(context, server), good = server.worldUp)
                server.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
        }
        game is TurtleGame.State.Failed -> TurtleWindow(panel, padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TurtleTitle(stringResource(R.string.turtle_game_failed))
                Text(game.message, style = MaterialTheme.typography.bodyMedium)
                FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TurtleButton(stringResource(R.string.turtle_close), primary = false) { TurtleGame.dismiss() }
                    when {
                        game.needsAccount -> TurtleButton(stringResource(R.string.turtle_tile_accounts)) { TurtleGame.dismiss(); actions.open(TurtleDialogKind.ACCOUNTS) }
                        game.chromieCraft -> TurtleButton(stringResource(R.string.turtle_stop_chromiecraft)) { TurtleGame.dismiss(); TurtleServer.dismiss(); actions.stopChromieCraft() }
                        else -> TurtleButton(stringResource(R.string.turtle_retry)) { TurtleGame.dismiss(); actions.play() }
                    }
                }
            }
        }
        server.phase == TurtleServer.Phase.STARTING || server.phase == TurtleServer.Phase.STOPPING -> TurtleWindow(panel, padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TurtleSpinner(40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(TurtleTexts.server(context, server), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                }
                server.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                if (server.step == TurtleServer.Step.WORLD && !facts.state.databaseUpdated) Text(stringResource(R.string.turtle_realm_first_start), style = MaterialTheme.typography.bodySmall)
            }
        }
        server.phase == TurtleServer.Phase.RUNNING -> TurtleWindow(panel, padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TurtleTitle(stringResource(R.string.turtle_realm_online))
                TurtleStatusLine(stringResource(R.string.turtle_realm_name), com.logie.warcraft.turtle.core.TurtleClientSetup.REALM_NAME)
                TurtleStatusLine(stringResource(R.string.turtle_realm_world), stringResource(if (server.worldUp) R.string.turtle_realm_world_up else R.string.turtle_realm_world_no_data), good = server.worldUp)
                TurtleStatusLine(stringResource(R.string.turtle_realm_here), "127.0.0.1")
                if (TurtleSettings.lan(context)) TurtleStatusLine(stringResource(R.string.turtle_realm_lan), server.lanAddress ?: "—")
                FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TurtleButton(stringResource(R.string.turtle_stop_realm), primary = false, onClick = actions.stop)
                    if (server.worldUp && facts.client) TurtleButton(stringResource(R.string.turtle_play), onClick = actions.play)
                }
            }
        }
        server.phase == TurtleServer.Phase.FAILED -> TurtleWindow(panel, padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TurtleTitle(stringResource(R.string.turtle_realm_failed))
                Text(server.error ?: "", style = MaterialTheme.typography.bodyMedium)
                FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TurtleButton(stringResource(R.string.turtle_details), primary = false) { actions.open(TurtleDialogKind.REALM) }
                    if (server.blockedByChromieCraft) {
                        TurtleButton(stringResource(R.string.turtle_stop_chromiecraft)) { TurtleServer.dismiss(); actions.stopChromieCraft() }
                    } else {
                        TurtleButton(stringResource(R.string.turtle_retry), onClick = actions.start)
                    }
                }
            }
        }
        facts.ready -> {
            PressStart(onClick = actions.play)
            Text(
                stringResource(R.string.turtle_ready_line),
                style = MaterialTheme.typography.bodyMedium,
                color = TurtleColors.Stone,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        else -> SetupPanel(facts, actions)
    }
}

private fun stepNote(step: TurtleJobs.Step): Int? = when (step) {
    TurtleJobs.Step.MAPS, TurtleJobs.Step.VMAPS -> R.string.turtle_step_data_note
    TurtleJobs.Step.MMAPS -> R.string.turtle_step_mmaps_note
    TurtleJobs.Step.DOWNLOADING -> R.string.turtle_step_download_note
    TurtleJobs.Step.COPYING_CLIENT -> R.string.turtle_step_copy_note
    else -> null
}

/** Not ready yet: the next thing to do, with the button that does it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupPanel(facts: TurtleFacts, actions: TurtleActions) {
    val context = LocalContext.current
    TurtleWindow(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val next = facts.next
            when (next?.step) {
                InstallPlan.Step.SERVER, InstallPlan.Step.DATABASE, null -> {
                    TurtleTitle(stringResource(if (facts.programsInstalled) R.string.turtle_setup_update_title else R.string.turtle_setup_install_title))
                    Text(stringResource(R.string.turtle_setup_install_body), style = MaterialTheme.typography.bodyMedium)
                    facts.pin?.let { pin ->
                        Text(
                            stringResource(R.string.turtle_setup_install_size, Formatter.formatShortFileSize(context, pin.size), Formatter.formatShortFileSize(context, facts.free)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } ?: Text(stringResource(R.string.turtle_error_not_pinned), style = MaterialTheme.typography.bodySmall, color = TurtleColors.Blood)
                    TurtleButton(stringResource(R.string.turtle_install), enabled = facts.pin != null, modifier = Modifier.align(Alignment.End), onClick = actions.install)
                }
                InstallPlan.Step.CLIENT -> {
                    TurtleTitle(stringResource(R.string.turtle_setup_client_title))
                    Text(stringResource(R.string.turtle_setup_client_body), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.turtle_setup_client_why), style = MaterialTheme.typography.bodySmall)
                    FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TurtleButton(stringResource(R.string.turtle_pick_zip), primary = false, onClick = actions.pickZip)
                        TurtleButton(stringResource(R.string.turtle_pick_folder), onClick = actions.pickFolder)
                    }
                }
                InstallPlan.Step.DATA -> {
                    TurtleTitle(stringResource(R.string.turtle_setup_data_title))
                    Text(stringResource(R.string.turtle_setup_data_body), style = MaterialTheme.typography.bodyMedium)
                    TurtleButton(stringResource(R.string.turtle_make), modifier = Modifier.align(Alignment.End)) { actions.makeData(TurtleSettings.mmaps(context)) }
                }
                InstallPlan.Step.ACCOUNT -> {
                    TurtleTitle(stringResource(R.string.turtle_setup_account_title))
                    Text(stringResource(R.string.turtle_setup_account_body), style = MaterialTheme.typography.bodyMedium)
                    TurtleButton(stringResource(R.string.turtle_tile_accounts), modifier = Modifier.align(Alignment.End)) { actions.open(TurtleDialogKind.ACCOUNTS) }
                }
                InstallPlan.Step.CHECK, InstallPlan.Step.READY -> Text(stringResource(R.string.turtle_error_blocker), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Each part of Turtle WoW on its own line: how it is, and its own one-tap button (install,
 * make, check), so any one of them can be put in or done again by itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Components(facts: TurtleFacts, busy: Boolean, actions: TurtleActions) {
    val context = LocalContext.current
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
    TurtleWindow(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        Column {
            Component(
                title = stringResource(R.string.turtle_part_programs),
                summary = stringResource(R.string.turtle_part_programs_summary),
                status = when {
                    facts.programsCurrent -> stringResource(R.string.turtle_part_installed, facts.version ?: "")
                    facts.programsInstalled -> stringResource(R.string.turtle_part_update)
                    facts.downloadedSoFar > 0 -> stringResource(R.string.turtle_part_partial, size(facts.downloadedSoFar))
                    else -> stringResource(R.string.turtle_part_missing)
                },
                state = if (facts.programsCurrent) PartState.DONE else PartState.TODO,
                button = stringResource(if (facts.programsCurrent) R.string.turtle_reinstall else if (facts.downloadedSoFar > 0) R.string.turtle_resume else R.string.turtle_download_install),
                enabled = !busy && facts.pin != null && facts.blocker == null,
            ) { actions.job(TurtleJobs.Kind.PROGRAMS) }
            Component(
                title = stringResource(R.string.turtle_part_database),
                summary = stringResource(R.string.turtle_part_database_summary),
                status = stringResource(if (facts.databases) R.string.turtle_part_created else R.string.turtle_part_missing),
                state = if (facts.databases) PartState.DONE else if (facts.programsInstalled) PartState.TODO else PartState.WAITING,
                button = if (facts.databases) null else stringResource(R.string.turtle_create),
                enabled = !busy && facts.programsInstalled,
            ) { actions.job(TurtleJobs.Kind.DATABASE) }
            Component(
                title = stringResource(R.string.turtle_part_runtime),
                summary = stringResource(R.string.turtle_part_runtime_summary),
                status = stringResource(
                    when {
                        facts.runtimeReady -> R.string.turtle_part_ready
                        facts.runtimeThere -> R.string.turtle_part_runtime_old
                        else -> R.string.turtle_part_missing
                    }
                ),
                state = if (facts.runtimeThere) PartState.DONE else PartState.TODO,
                button = if (facts.runtimeThere) null else stringResource(R.string.turtle_install_short),
                enabled = !busy,
            ) { actions.job(TurtleJobs.Kind.RUNTIME) }
            ClientComponent(facts, busy, actions)
            Component(
                title = stringResource(R.string.turtle_part_data),
                summary = stringResource(R.string.turtle_part_data_summary),
                status = when {
                    facts.data && !facts.dataCurrent -> stringResource(R.string.turtle_part_data_old)
                    facts.data && facts.state.dbcCheck == InstallState.ClientCheck.DIFFERENT -> stringResource(R.string.turtle_part_data_dbc_different)
                    facts.data -> stringResource(R.string.turtle_part_data_made, (facts.dataParts - "mmaps").sorted().joinToString())
                    else -> stringResource(R.string.turtle_part_missing)
                },
                state = if (facts.data && facts.dataCurrent) PartState.DONE else if (facts.client && facts.programsInstalled) PartState.TODO else PartState.WAITING,
                button = stringResource(if (facts.data) R.string.turtle_remake else R.string.turtle_make),
                enabled = !busy && facts.client && facts.programsInstalled,
            ) { actions.makeData(false) }
            Component(
                title = stringResource(R.string.turtle_part_mmaps),
                summary = stringResource(R.string.turtle_part_mmaps_summary),
                status = stringResource(if ("mmaps" in facts.dataParts) R.string.turtle_part_created else R.string.turtle_part_optional),
                state = if ("mmaps" in facts.dataParts) PartState.DONE else PartState.OPTIONAL,
                button = stringResource(if ("mmaps" in facts.dataParts) R.string.turtle_remake else R.string.turtle_make),
                enabled = !busy && facts.client && facts.programsInstalled,
            ) { actions.makeData(true) }
            Component(
                title = stringResource(R.string.turtle_part_account),
                summary = stringResource(R.string.turtle_part_account_summary),
                status = facts.savedAccount ?: stringResource(if (facts.accounts > 0) R.string.turtle_part_created else R.string.turtle_part_missing),
                state = if (facts.savedAccount != null || facts.accounts > 0) PartState.DONE else if (facts.databases) PartState.TODO else PartState.WAITING,
                button = stringResource(R.string.turtle_tile_accounts),
                enabled = facts.databases,
                last = true,
            ) { actions.open(TurtleDialogKind.ACCOUNTS) }
        }
    }
    TurtleButton(
        stringResource(R.string.turtle_install_everything),
        enabled = !busy && facts.pin != null && facts.blocker == null && !facts.ready,
        modifier = Modifier.fillMaxWidth(),
        onClick = actions.install,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClientComponent(facts: TurtleFacts, busy: Boolean, actions: TurtleActions) {
    val state = facts.state
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PartHeader(
            title = stringResource(R.string.turtle_part_client),
            summary = stringResource(R.string.turtle_part_client_summary),
            status = when {
                !facts.client -> stringResource(R.string.turtle_part_client_missing)
                state.clientCheck == InstallState.ClientCheck.EXACT -> stringResource(R.string.turtle_part_client_exact)
                state.clientCheck == InstallState.ClientCheck.DIFFERENT -> stringResource(R.string.turtle_part_client_different, state.clientDifferences)
                else -> stringResource(R.string.turtle_part_client_unchecked)
            },
            state = when {
                !facts.client -> PartState.TODO
                state.clientCheck == InstallState.ClientCheck.DIFFERENT -> PartState.WARN
                else -> PartState.DONE
            },
        )
        FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (facts.client) TurtleButton(stringResource(R.string.turtle_check_files), primary = false, enabled = !busy) { actions.job(TurtleJobs.Kind.VERIFY) }
            TurtleButton(stringResource(R.string.turtle_pick_zip), primary = false, enabled = !busy, onClick = actions.pickZip)
            TurtleButton(stringResource(R.string.turtle_pick_folder), primary = !facts.client, enabled = !busy, onClick = actions.pickFolder)
        }
    }
    Divider()
}

enum class PartState { DONE, TODO, WAITING, OPTIONAL, WARN }

@Composable
private fun Component(
    title: String,
    summary: String,
    status: String,
    state: PartState,
    button: String?,
    enabled: Boolean,
    last: Boolean = false,
    onClick: () -> Unit,
) {
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PartHeader(title, summary, status, state)
        if (button != null) TurtleButton(button, primary = state == PartState.TODO, enabled = enabled, modifier = Modifier.align(Alignment.End), onClick = onClick)
    }
    if (!last) Divider()
}

@Composable
private fun PartHeader(title: String, summary: String, status: String, state: PartState) {
    Row(verticalAlignment = Alignment.Top) {
        TurtleDot(
            when (state) {
                PartState.DONE -> TurtleColors.Leaf
                PartState.TODO -> TurtleColors.Gold
                PartState.WARN -> TurtleColors.Orange
                PartState.OPTIONAL, PartState.WAITING -> TurtleColors.Dust
            },
            Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodySmall)
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (state == PartState.DONE) TurtleColors.Leaf else if (state == PartState.WARN) TurtleColors.Orange else TurtleColors.Parchment)
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, TurtleColors.Brown, Color.Transparent))))
}

/** PRESS START in the site's gold-to-coral capitals, breathing. */
@Composable
private fun PressStart(onClick: () -> Unit) {
    val motion = rememberInfiniteTransition(label = "start")
    val breathe by motion.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1_500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    Box(
        Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick).padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 260.dp, height = 56.dp).graphicsLayer { alpha = breathe }
                .background(Brush.radialGradient(listOf(TurtleColors.Orange.copy(alpha = 0.25f), Color.Transparent)))
        )
        Text(
            stringResource(R.string.wow_home_press_start).uppercase(),
            style = TurtleGradientText.copy(fontSize = 27.sp, shadow = TurtleShadow, letterSpacing = TurtleDisplayStyle.letterSpacing * 2f),
            modifier = Modifier.graphicsLayer { alpha = 0.55f + 0.45f * breathe },
        )
    }
}

@Composable
private fun Notice(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = TurtleColors.Parchment, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(8.dp))
}

private class Tile(val icon: ImageVector, val title: String, val subtitle: String, val onClick: () -> Unit)

@Composable
private fun TileGrid(tiles: List<Tile>, columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile ->
                    TurtleWindow(Modifier.weight(1f).fillMaxHeight(), onClick = tile.onClick, padding = PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(tile.icon, contentDescription = null, tint = TurtleColors.Gold, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tile.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                                Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
