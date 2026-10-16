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
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SmartToy
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
import com.logie.warcraft.AppIcons
import com.logie.warcraft.BuildConfig
import com.logie.warcraft.R
import com.logie.warcraft.Server
import com.logie.warcraft.TitleMusic
import com.logie.warcraft.WowUpdateActivity
import com.logie.warcraft.game.GameSettings
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
 * The launcher when Turtle WoW is the chosen game. It works the way the 3.3.5a launcher does
 * (WowHome): the picture and the logo, under them what the realm is doing with the one thing to do
 * next (Press start, or what's under way, or what's missing with the button that fixes it), then
 * the same tiles in the same order, each opening its screen. Only the look is Turtle WoW's: the old
 * map of Kalimdor and the Eastern Kingdoms with warm motes, its logo, turtlecraft.gg's colours and
 * its own music. Nothing here reads or changes the 3.3.5a game's files or realm, except
 * [Server.stop] when the user asks to stop ChromieCraft's realm (they share the login port).
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

    /** Press start: what's missing first (the realm, in one tap), then the game, or the realm alone without a client or data. */
    fun play() {
        when {
            facts.blocker != null || facts.pin == null && !facts.programsInstalled -> {}
            !facts.programsCurrent || !facts.databases ->
                TurtleService.job(context, TurtleJobs.Kind.INSTALL, options = TurtleJobs.Options(withMmaps = TurtleSettings.mmaps(context)))
            facts.client && facts.data -> TurtleGame.play(context)
            else -> TurtleServer.start(context)
        }
    }

    // How a long job went, as soon as it's done: on the screen it belongs to, as in the 3.3.5a launcher.
    LaunchedEffect(job) {
        val kind = (job as? TurtleJobs.State.Done)?.kind ?: (job as? TurtleJobs.State.Failed)?.kind
        if (kind != null && dialog == null) dialog = dialogFor(kind)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { refresh++ }

    TurtleTheme {
        Box(Modifier.fillMaxSize().background(TurtleColors.Night)) {
            TurtleHomeScreen(
                facts = facts,
                server = server,
                job = job,
                game = game,
                onPlay = { play() },
                onStart = { TurtleServer.start(context) },
                onStop = { TurtleServer.stop(context) },
                onDialog = { dialog = it },
                onSwitchGame = onSwitchGame,
            )
            if (dialog == TurtleDialogKind.ADDONS) TurtleAddonManager(onDismiss = { dialog = null; refresh++ })
        }
        TurtleDialogs(dialog, facts, onDialog = { dialog = it }, onDismiss = {
            if (TurtleJobs.state.value.let { it is TurtleJobs.State.Done || it is TurtleJobs.State.Failed }) TurtleJobs.clearResult()
            dialog = null
            refresh++
        })
    }
}

/** The screens: the tiles' (as the 3.3.5a launcher's), and the ones the server's opens. */
enum class TurtleDialogKind { SERVER, FILES, ACCOUNTS, GRAPHICS, ADDONS, OPTIONS, ICON, REPAIR, UNINSTALL }

/** Where a job's progress and outcome show: the client and the data under Game files, the rest under Server. */
fun dialogFor(kind: TurtleJobs.Kind): TurtleDialogKind = when (kind) {
    TurtleJobs.Kind.CLIENT, TurtleJobs.Kind.VERIFY, TurtleJobs.Kind.DATA, TurtleJobs.Kind.IMPORT_DATA -> TurtleDialogKind.FILES
    else -> TurtleDialogKind.SERVER
}

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
private fun TurtleHomeScreen(
    facts: TurtleFacts,
    server: TurtleServer.Status,
    job: TurtleJobs.State,
    game: TurtleGame.State,
    onPlay: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDialog: (TurtleDialogKind) -> Unit,
    onSwitchGame: () -> Unit,
) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        TurtleBackdrop()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TurtleHeader(screenWidth, screenHeight, onSwitchGame)
            Column(
                Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RealmPanel(facts, server, job, game, onPlay, onStart, onStop, onDialog)

                TurtleTitle(stringResource(R.string.wow_home_menu), modifier = Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleLarge)
                // The 3.3.5a launcher's tiles, in its order.
                val tiles = listOf(
                    Tile(Icons.Default.Dns, stringResource(R.string.wow_tile_server), TurtleTexts.server(context, server)) { onDialog(TurtleDialogKind.SERVER) },
                    Tile(Icons.Default.FolderOpen, stringResource(R.string.wow_tile_files), filesSummary(facts)) { onDialog(TurtleDialogKind.FILES) },
                    Tile(Icons.Default.People, stringResource(R.string.wow_tile_accounts), facts.savedAccount ?: stringResource(R.string.turtle_tile_accounts_summary)) { onDialog(TurtleDialogKind.ACCOUNTS) },
                    Tile(Icons.Default.Tune, stringResource(R.string.wow_tile_graphics), graphicsSummary(context)) { onDialog(TurtleDialogKind.GRAPHICS) },
                    Tile(Icons.Default.Extension, stringResource(R.string.wow_tile_addons), turtleAddonsSummary(context, facts)) { onDialog(TurtleDialogKind.ADDONS) },
                    Tile(Icons.Default.SmartToy, stringResource(R.string.turtle_tile_options), stringResource(R.string.turtle_tile_options_summary, TurtleSettings.worldBots(context))) { onDialog(TurtleDialogKind.OPTIONS) },
                    Tile(Icons.Default.Palette, stringResource(R.string.wow_tile_icon), stringResource(AppIcons.current(context).label)) { onDialog(TurtleDialogKind.ICON) },
                    Tile(Icons.Default.SystemUpdate, stringResource(R.string.wow_tile_updates), stringResource(R.string.wow_tile_updates_summary, BuildConfig.VERSION_NAME)) { WowUpdateActivity.launch(context) },
                    // Back to the 3.3.5a game (GameSwitch), as its launcher's tile comes here.
                    Tile(Icons.Default.SwapHoriz, stringResource(R.string.games_tile), stringResource(R.string.games_tile_turtle_summary)) { onSwitchGame() },
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

@Composable
private fun graphicsSummary(context: Context): String {
    val resolution = GameSettings.resolution(context)
    val (width, height) = GameSettings.gameSize(context, resolution)
    return stringResource(R.string.wow_tile_graphics_summary, stringResource(resolution.label), width, height)
}

@Composable
private fun filesSummary(facts: TurtleFacts): String = stringResource(
    when {
        facts.data && facts.client -> R.string.wow_tile_files_all
        facts.data -> R.string.wow_tile_files_data
        facts.client -> R.string.wow_tile_files_client
        else -> R.string.turtle_tile_files_none
    }
)

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
        Chip(Icons.Default.SwapHoriz, stringResource(R.string.games_chip), Modifier.align(Alignment.TopStart).padding(10.dp).graphicsLayer { alpha = reveal.value }, onSwitch)
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

/** The launcher music's switch: a note, crossed out while it's off. */
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

/** What the realm is doing, and the one thing to do next (the 3.3.5a launcher's realm panel). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RealmPanel(
    facts: TurtleFacts,
    server: TurtleServer.Status,
    job: TurtleJobs.State,
    game: TurtleGame.State,
    onPlay: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDialog: (TurtleDialogKind) -> Unit,
) {
    val context = LocalContext.current
    val panel = Modifier.fillMaxWidth()
    when {
        facts.blocker != null -> Notice(stringResource(if (facts.blocker == "arm64") R.string.turtle_error_not_arm64 else R.string.turtle_error_blocker))
        job is TurtleJobs.State.Working -> JobPanel(job, onOpen = { onDialog(dialogFor(job.kind)) })
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
                if (game.step == TurtleGame.Step.RUNTIME || game.step == TurtleGame.Step.WINDOWS) {
                    game.progress?.let { TurtleProgressLine(it) }
                    Text(stringResource(R.string.wow_game_runtime_note), style = MaterialTheme.typography.bodySmall)
                }
                TurtleStatusLine(stringResource(R.string.wow_tile_server), TurtleTexts.server(context, server), good = server.phase == TurtleServer.Phase.RUNNING && server.worldUp)
                if (server.phase == TurtleServer.Phase.STARTING) server.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
        }
        game is TurtleGame.State.Failed -> TurtleWindow(panel, padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TurtleTitle(stringResource(R.string.turtle_game_failed))
                Text(game.message, style = MaterialTheme.typography.bodyMedium)
                FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TurtleButton(stringResource(R.string.turtle_close), primary = false) { TurtleGame.dismiss() }
                    when {
                        game.needsAccount -> TurtleButton(stringResource(R.string.wow_tile_accounts)) { TurtleGame.dismiss(); onDialog(TurtleDialogKind.ACCOUNTS) }
                        game.chromieCraft -> TurtleButton(stringResource(R.string.turtle_stop_chromiecraft)) { TurtleGame.dismiss(); TurtleServer.dismiss(); Server.stop(context) }
                        else -> TurtleButton(stringResource(R.string.turtle_retry)) { TurtleGame.dismiss(); onPlay() }
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
                TurtleTitle(stringResource(R.string.wow_realm_online))
                TurtleStatusLine(stringResource(R.string.wow_realm_name), com.logie.warcraft.turtle.core.TurtleClientSetup.REALM_NAME)
                TurtleStatusLine(
                    stringResource(R.string.wow_realm_world),
                    stringResource(if (server.worldUp) R.string.wow_realm_world_up else R.string.wow_realm_world_no_data),
                    good = server.worldUp,
                )
                TurtleStatusLine(stringResource(R.string.wow_realm_here), "127.0.0.1")
                TurtleStatusLine(stringResource(R.string.wow_realm_lan), server.lanAddress ?: stringResource(R.string.wow_realm_lan_none))
                if (!facts.client) Text(stringResource(R.string.turtle_realm_no_client), style = MaterialTheme.typography.bodySmall)
                FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TurtleButton(stringResource(R.string.wow_server_stop), primary = false, onClick = onStop)
                    if (!server.worldUp || !facts.client) {
                        TurtleButton(stringResource(R.string.wow_tile_files)) { onDialog(TurtleDialogKind.FILES) }
                    } else {
                        TurtleButton(stringResource(R.string.wow_game_play), onClick = onPlay)
                    }
                }
            }
        }
        server.phase == TurtleServer.Phase.FAILED -> TurtleWindow(panel, padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TurtleTitle(stringResource(R.string.wow_server_failed))
                Text(server.error ?: "", style = MaterialTheme.typography.bodyMedium)
                FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TurtleButton(stringResource(R.string.wow_server_details), primary = false) { onDialog(TurtleDialogKind.SERVER) }
                    if (server.blockedByChromieCraft) {
                        TurtleButton(stringResource(R.string.turtle_stop_chromiecraft)) { TurtleServer.dismiss(); Server.stop(context) }
                    } else {
                        TurtleButton(stringResource(R.string.wow_server_retry), onClick = onStart)
                    }
                }
            }
        }
        else -> {
            PressStart(onClick = onPlay)
            Text(
                stringResource(
                    when {
                        !facts.programsCurrent || !facts.databases -> if (facts.programsInstalled) R.string.turtle_home_start_line_update else R.string.turtle_home_start_line_install
                        !facts.data -> R.string.turtle_home_start_line_no_data
                        facts.client -> R.string.turtle_home_start_line_game
                        else -> R.string.turtle_home_start_line
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = TurtleColors.Stone,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // What's missing, with the button that fixes it (the 3.3.5a launcher's notices).
            when {
                facts.pin == null && !facts.programsInstalled -> Notice(stringResource(R.string.turtle_error_not_pinned))
                !facts.programsCurrent || !facts.databases -> facts.pin?.let { pin ->
                    Notice(stringResource(R.string.turtle_home_no_realm, Formatter.formatShortFileSize(context, pin.size), Formatter.formatShortFileSize(context, facts.free)))
                }
                !facts.client -> Notice(stringResource(R.string.turtle_home_no_client), stringResource(R.string.wow_tile_files)) { onDialog(TurtleDialogKind.FILES) }
                !facts.data || !facts.dataCurrent -> Notice(stringResource(R.string.turtle_home_no_data), stringResource(R.string.wow_tile_files)) { onDialog(TurtleDialogKind.FILES) }
                facts.accounts == 0 && facts.savedAccount == null -> Notice(stringResource(R.string.turtle_home_no_account), stringResource(R.string.wow_tile_accounts)) { onDialog(TurtleDialogKind.ACCOUNTS) }
            }
        }
    }
}

/** A job under way (installing, copying the client, making the data...); a tap opens its screen. */
@Composable
private fun JobPanel(job: TurtleJobs.State.Working, onOpen: () -> Unit) {
    val context = LocalContext.current
    TurtleWindow(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp), onClick = onOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TurtleSpinner(40.dp)
                Spacer(Modifier.width(12.dp))
                Text(TurtleTexts.step(context, job.step), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            }
            job.progress?.let { TurtleProgressLine(it) }
            job.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
        }
    }
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

/** A line about something missing, with the button that fixes it. */
@Composable
private fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = TurtleColors.Parchment, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 560.dp))
        if (action != null) TurtleButton(action, primary = false, onClick = onAction)
    }
}

private class Tile(val icon: ImageVector, val title: String, val subtitle: String, val onClick: () -> Unit)

@Composable
private fun TileGrid(tiles: List<Tile>, columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(columns).forEachIndexed { rowIndex, row ->
            // Tiles side by side are as tall as the tallest.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { columnIndex, tile ->
                    TileWindow(tile, rowIndex * columns + columnIndex, Modifier.weight(1f).fillMaxHeight())
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A panel that rises out of the dark, one after another, when the screen opens. */
@Composable
private fun TileWindow(tile: Tile, index: Int, modifier: Modifier) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(450L + index * 90L)
        appear.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    TurtleWindow(
        modifier = modifier.graphicsLayer {
            alpha = appear.value
            translationY = (1f - appear.value) * 14.dp.toPx()
        },
        onClick = tile.onClick,
        padding = PaddingValues(14.dp),
    ) {
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
