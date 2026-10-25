// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalLayoutApi::class)

package com.logie.warcraft.felsong.ui

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
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.logie.warcraft.AppIcons
import com.logie.warcraft.BuildConfig
import com.logie.warcraft.Environments
import com.logie.warcraft.R
import com.logie.warcraft.TitleMusic
import com.logie.warcraft.WowUpdateActivity
import com.logie.warcraft.WowUpdater
import com.logie.warcraft.felsong.FelsongFiles
import com.logie.warcraft.felsong.FelsongGame
import com.logie.warcraft.felsong.FelsongJobs
import com.logie.warcraft.felsong.FelsongLogin
import com.logie.warcraft.felsong.FelsongPackage
import com.logie.warcraft.felsong.FelsongServer
import com.logie.warcraft.felsong.FelsongService
import com.logie.warcraft.felsong.FelsongSettings
import com.logie.warcraft.felsong.FelsongTexts
import com.logie.warcraft.felsong.core.FelsongInstallPlan
import com.logie.warcraft.felsong.core.FelsongInstallState
import com.logie.warcraft.felsong.core.FelsongRealm
import com.logie.warcraft.game.GameSettings
import com.logie.warcraft.turtle.ui.GameSwitch
import com.logie.warcraft.turtle.ui.SwitchToTurtleDialog
import com.logie.warcraft.turtle.ui.SwitchToWrathDialog
import com.logie.warcraft.turtle.ui.TurtleTheme
import com.logie.warcraft.ui.WowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The launcher when Legion 7.3.5 is the chosen game (com.logie.warcraft.felsong, the package name
 * from when it was planned as Felsong's realm). It works the way the other launchers do: the
 * picture and the logo, under them what the realm is doing with the one thing to do next (Press
 * start, or what's under way, or what's missing with the button that fixes it), then the same tiles
 * in the same order, each opening its screen, and the numbered steps from nothing to Play. The
 * look: Illidan over the Broken Isles, fel green on dark olive, Kingdoms Will Burn as its music.
 * Nothing here reads or changes the other games' files or realms.
 */
@Composable
fun FelsongLauncher(checkUpdateOnOpen: Boolean) {
    val context = LocalContext.current
    var checkUpdate by rememberSaveable { mutableStateOf(checkUpdateOnOpen) }
    val updateOffer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { checkUpdate = false }
    LaunchedEffect(Unit) {
        if (!checkUpdate) return@LaunchedEffect
        val update = if (WowUpdater.isConfigured) withContext(Dispatchers.IO) { runCatching { WowUpdater.findUpdate() }.getOrNull() } else null
        if (update != null) updateOffer.launch(WowUpdateActivity.intent(context, update)) else checkUpdate = false
    }
    var choosing by remember { mutableStateOf(false) }
    // A realm the chosen game's can't run beside is running: asked in that game's look.
    var asking by remember { mutableStateOf<Environments.Environment?>(null) }
    FelsongHomeRoute(onSwitchGame = { choosing = true })
    if (choosing) FelsongTheme {
        FelsongGameChooser(onDismiss = { choosing = false }) { target ->
            choosing = false
            if (!GameSwitch.trySwitch(context, target)) asking = target
        }
    }
    when (asking) {
        Environments.Environment.WRATH -> TurtleTheme { SwitchToWrathDialog(onDismiss = { asking = null }) }
        Environments.Environment.TURTLE -> WowTheme { SwitchToTurtleDialog(onDismiss = { asking = null }) }
        else -> {}
    }
}

/** The other games, in this game's look. */
@Composable
fun FelsongGameChooser(onDismiss: () -> Unit, onPick: (Environments.Environment) -> Unit) {
    FelsongDialog(
        title = stringResource(R.string.games_title),
        onDismiss = onDismiss,
        buttons = { FelsongButton(stringResource(R.string.felsong_cancel), primary = false, onClick = onDismiss) },
    ) {
        FelsongChoiceRow(stringResource(R.string.wrath_name), stringResource(R.string.wrath_name_summary)) { onPick(Environments.Environment.WRATH) }
        FelsongChoiceRow(stringResource(R.string.turtle_tile_name), stringResource(R.string.turtle_tile_name_summary)) { onPick(Environments.Environment.TURTLE) }
        FelsongChoiceRow(stringResource(R.string.tww_name), stringResource(R.string.tww_summary)) { onPick(Environments.Environment.TWW) }
    }
}

@Composable
fun FelsongHomeRoute(onSwitchGame: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val server by FelsongServer.status.collectAsState()
    val job by FelsongJobs.state.collectAsState()
    val game by FelsongGame.state.collectAsState()
    var dialog by remember { mutableStateOf<FelsongDialogKind?>(null) }
    val facts = remember(refresh, job, server.phase) { FelsongFacts.of(context) }

    /** Press start: what's missing first (the realm, in one tap), then the game, or the realm alone without a client or data. */
    fun play() {
        when {
            facts.blocker != null || facts.pin == null && !facts.programsInstalled -> {}
            !facts.programsCurrent || !facts.databases ->
                FelsongService.job(context, FelsongJobs.Kind.INSTALL, options = FelsongJobs.Options(withMmaps = FelsongSettings.mmaps(context)))
            facts.spp -> FelsongService.job(context, FelsongJobs.Kind.SPP)
            facts.client && facts.data && !facts.patched -> FelsongService.job(context, FelsongJobs.Kind.PATCH)
            facts.client && facts.data && facts.patched -> FelsongGame.play(context)
            else -> FelsongServer.start(context)
        }
    }

    LaunchedEffect(job) {
        val kind = (job as? FelsongJobs.State.Done)?.kind ?: (job as? FelsongJobs.State.Failed)?.kind
        if (kind != null && dialog == null) dialog = dialogFor(kind)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { refresh++ }

    FelsongTheme {
        Box(Modifier.fillMaxSize().background(FelsongColors.Olive)) {
            FelsongHomeScreen(
                facts = facts,
                server = server,
                job = job,
                game = game,
                onPlay = { play() },
                onStart = { FelsongServer.start(context) },
                onStop = { FelsongServer.stop(context) },
                onDialog = { dialog = it },
                onSwitchGame = onSwitchGame,
            )
        }
        FelsongDialogs(dialog, facts, onDialog = { dialog = it }, onDismiss = {
            if (FelsongJobs.state.value.let { it is FelsongJobs.State.Done || it is FelsongJobs.State.Failed }) FelsongJobs.clearResult()
            dialog = null
            refresh++
        })
    }
}

/** The screens: the tiles' (as the other launchers'), and the ones the server's opens. */
enum class FelsongDialogKind { STEPS, SERVER, FILES, ACCOUNTS, GRAPHICS, OPTIONS, GAME_OPTIONS, ADDONS, ICON, REPAIR, UNINSTALL }

/** Where a job's progress and outcome show: the client, the data and the patch under Game files, the rest under Server. */
fun dialogFor(kind: FelsongJobs.Kind): FelsongDialogKind = when (kind) {
    FelsongJobs.Kind.CLIENT, FelsongJobs.Kind.SPP, FelsongJobs.Kind.VERIFY, FelsongJobs.Kind.DATA, FelsongJobs.Kind.IMPORT_DATA, FelsongJobs.Kind.PATCH -> FelsongDialogKind.FILES
    else -> FelsongDialogKind.SERVER
}

/** What's on the phone for the Legion game, read when the screen needs it. */
data class FelsongFacts(
    val blocker: String?,
    val pin: FelsongPackage.Pin?,
    val programsInstalled: Boolean,
    val programsCurrent: Boolean,
    val downloadedSoFar: Long,
    val databases: Boolean,
    val runtimeThere: Boolean,
    val runtimeReady: Boolean,
    val client: Boolean,
    val state: FelsongInstallState,
    val dataParts: Set<String>,
    val data: Boolean,
    val dataCurrent: Boolean,
    val patched: Boolean,
    val accounts: Int,
    val savedAccount: String?,
    val used: Long,
    val free: Long,
    val version: String?,
    /** SPP's Legion files are on the phone and would give Legion its client or data ([FelsongJobs.sppOffer]). */
    val spp: Boolean = false,
) {
    val plan: List<FelsongInstallPlan.Line> = FelsongInstallPlan.of(
        FelsongInstallPlan.Facts(
            blocker = blocker,
            serverInstalled = programsInstalled,
            serverCurrent = programsCurrent,
            databasesThere = databases,
            clientThere = client,
            dataThere = data,
            dataMatchesClient = dataCurrent,
            clientPatched = patched,
            hasAccount = when {
                savedAccount != null || accounts > 0 -> true
                accounts == 0 -> false
                else -> null
            },
        )
    )
    val ready: Boolean get() = FelsongInstallPlan.ready(plan)

    /** The numbered steps done, of the numbered steps (CHECK and READY aren't counted). */
    val stepsDone: Int get() = plan.count { it.step != FelsongInstallPlan.Step.CHECK && it.step != FelsongInstallPlan.Step.READY && it.status == FelsongInstallPlan.Status.DONE }
    val stepsTotal: Int get() = FelsongInstallPlan.Step.entries.size - 2

    companion object {
        fun of(context: Context): FelsongFacts {
            val state = FelsongFiles.state(context)
            val client = FelsongFiles.hasClient(context)
            val data = FelsongFiles.hasData(context)
            return FelsongFacts(
                blocker = FelsongFiles.blocker(context),
                pin = FelsongPackage.pinned,
                programsInstalled = FelsongPackage.isInstalled(context),
                programsCurrent = FelsongPackage.isCurrent(context),
                downloadedSoFar = FelsongPackage.downloadedSoFar(context),
                databases = FelsongFiles.hasDatabases(context),
                runtimeThere = FelsongGame.runtimeThere(context),
                runtimeReady = FelsongGame.runtimeReady(context),
                client = client,
                state = state,
                dataParts = FelsongFiles.dataParts(context),
                data = data,
                dataCurrent = data && (state.dataFrom == null || state.dataFrom == state.clientExeSha256),
                patched = FelsongFiles.isPatched(context),
                accounts = FelsongSettings.knownAccounts(context),
                savedAccount = FelsongLogin.get(context)?.name,
                used = runCatching { FelsongFiles.used(context) }.getOrDefault(0L),
                free = FelsongFiles.freeSpace(context),
                spp = FelsongJobs.sppOffer(context),
                version = FelsongPackage.version(context)?.lines()?.firstOrNull { it.startsWith("legioncore ") }?.removePrefix("legioncore ")?.take(7),
            )
        }
    }
}

@Composable
private fun FelsongHomeScreen(
    facts: FelsongFacts,
    server: FelsongServer.Status,
    job: FelsongJobs.State,
    game: FelsongGame.State,
    onPlay: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDialog: (FelsongDialogKind) -> Unit,
    onSwitchGame: () -> Unit,
) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        FelsongBackdrop()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FelsongHeader(screenWidth, screenHeight, onSwitchGame)
            Column(
                Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RealmPanel(facts, server, job, game, onPlay, onStart, onStop, onDialog)

                FelsongTitle(stringResource(R.string.wow_home_menu), modifier = Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleLarge)
                val xp = FelsongSettings.value(context, FelsongRealm.SETTINGS.first { it.key == "Rate.XP.Kill" })
                val solo = FelsongSettings.value(context, FelsongRealm.SETTINGS.first { it.key == "Solocraft.Enable" }) == "1"
                val tiles = listOf(
                    Tile(Icons.Default.FormatListNumbered, stringResource(R.string.felsong_tile_steps), stringResource(R.string.felsong_tile_steps_summary, facts.stepsDone, facts.stepsTotal)) { onDialog(FelsongDialogKind.STEPS) },
                    Tile(Icons.Default.Dns, stringResource(R.string.wow_tile_server), FelsongTexts.server(context, server)) { onDialog(FelsongDialogKind.SERVER) },
                    Tile(Icons.Default.FolderOpen, stringResource(R.string.wow_tile_files), filesSummary(facts)) { onDialog(FelsongDialogKind.FILES) },
                    Tile(Icons.Default.People, stringResource(R.string.wow_tile_accounts), facts.savedAccount ?: stringResource(R.string.felsong_tile_accounts_summary)) { onDialog(FelsongDialogKind.ACCOUNTS) },
                    Tile(Icons.Default.Tune, stringResource(R.string.wow_tile_graphics), graphicsSummary(context)) { onDialog(FelsongDialogKind.GRAPHICS) },
                    Tile(Icons.Default.Tune, stringResource(R.string.felsong_tile_options), stringResource(R.string.felsong_tile_options_summary, xp, stringResource(if (solo) R.string.felsong_on else R.string.felsong_off))) { onDialog(FelsongDialogKind.OPTIONS) },
                    Tile(Icons.Default.Checklist, stringResource(R.string.felsong_tile_game_options),
                        FelsongSettings.options(context).size.let { on -> if (on == 0) stringResource(R.string.felsong_tile_game_options_none) else pluralStringResource(R.plurals.felsong_tile_game_options_on, on, on) }) { onDialog(FelsongDialogKind.GAME_OPTIONS) },
                    Tile(Icons.Default.Extension, stringResource(R.string.wow_tile_addons), felsongAddonsSummary(context, facts)) { onDialog(FelsongDialogKind.ADDONS) },
                    Tile(Icons.Default.Palette, stringResource(R.string.wow_tile_icon), stringResource(AppIcons.current(context).label)) { onDialog(FelsongDialogKind.ICON) },
                    Tile(Icons.Default.SystemUpdate, stringResource(R.string.wow_tile_updates), stringResource(R.string.wow_tile_updates_summary, BuildConfig.VERSION_NAME)) { WowUpdateActivity.launch(context) },
                    Tile(Icons.Default.SwapHoriz, stringResource(R.string.games_tile), stringResource(R.string.games_tile_felsong_summary)) { onSwitchGame() },
                )
                val columns = (screenWidth / 260.dp).toInt().coerceIn(1, 3).let { if (screenWidth >= 360.dp) maxOf(it, 2) else it }
                TileGrid(tiles, columns)

                Text(
                    stringResource(R.string.felsong_version, BuildConfig.VERSION_NAME, facts.version ?: "—"),
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
private fun filesSummary(facts: FelsongFacts): String = stringResource(
    when {
        facts.data && facts.client -> R.string.wow_tile_files_all
        facts.data -> R.string.wow_tile_files_data
        facts.client -> R.string.wow_tile_files_client
        else -> R.string.felsong_tile_files_none
    }
)

/** Illidan over the Broken Isles, drifting slowly, fel motes rising, the logo half over its foot. */
@Composable
internal fun FelsongHeader(screenWidth: Dp, screenHeight: Dp, onSwitch: () -> Unit) {
    // Use Wrath's hero proportions in every orientation, rather than compressing the
    // illustration and wordmark into a small banner above the installation panel.
    val headerHeight = min((screenWidth * 0.62f).coerceIn(240.dp, 460.dp), screenHeight * 0.62f)
    val logoWidth = min(min(screenWidth * 0.78f, headerHeight * 1.25f), 460.dp)
    val logoHeight = logoWidth * 0.5f
    val motion = rememberInfiniteTransition(label = "header")
    val drift by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(32_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift")
    val breathe by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(3_800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(120)
        reveal.animateTo(1f, tween(1_400, easing = FastOutSlowInEasing))
    }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxWidth().padding(top = statusBar).height(headerHeight + logoHeight * 0.55f + 24.dp)) {
        Box(Modifier.fillMaxWidth().height(headerHeight).clipToBounds()) {
            Image(
                painter = painterResource(R.drawable.felsong_header),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Illidan, left of the middle, stays in sight on a narrow screen.
                alignment = BiasAlignment(-0.35f, -0.3f),
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val zoom = 1.02f + 0.05f * drift
                    scaleX = zoom
                    scaleY = zoom
                    transformOrigin = TransformOrigin(0.4f, 0.4f)
                    alpha = reveal.value
                },
            )
            FelsongMotes(alpha = reveal.value)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to FelsongColors.Olive)))
        }
        Column(
            modifier = Modifier.align(Alignment.TopCenter)
                .offset(y = headerHeight - logoHeight * 0.45f)
                .width(logoWidth).height(logoHeight)
                .semantics { contentDescription = "World of Warcraft: Legion" }
                .graphicsLayer {
                    val value = reveal.value
                    alpha = ((value - 0.2f) / 0.8f).coerceIn(0f, 1f)
                    val scale = (0.95f + 0.05f * value) * (1f + 0.008f * breathe)
                    scaleX = scale
                    scaleY = scale
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("WORLD OF WARCRAFT", style = FelsongDisplayStyle.copy(fontSize = (logoWidth.value * 0.055f).sp), color = FelsongColors.Bone)
            Text("LEGION", style = FelsongGradientText.copy(fontSize = (logoWidth.value * 0.20f).sp, shadow = FelsongGlow))
            Text("7.3.5", style = FelsongDisplayStyle.copy(fontSize = (logoWidth.value * 0.04f).sp), color = FelsongColors.Pale)
        }
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .graphicsLayer { alpha = reveal.value }
                .clip(CircleShape)
                .background(FelsongColors.Soot.copy(alpha = 0.55f))
                .clickable(onClick = onSwitch)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = FelsongColors.Lime, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.games_chip), style = FelsongDisplayStyle.copy(fontSize = 13.sp))
        }
        MusicChip(Modifier.align(Alignment.TopEnd).padding(10.dp).graphicsLayer { alpha = reveal.value })
    }
}

/** The launcher music's switch: a note, crossed out while it's off. */
@Composable
private fun MusicChip(modifier: Modifier) {
    val context = LocalContext.current
    val on by TitleMusic.on.collectAsState()
    Box(
        modifier.size(44.dp).clip(CircleShape).background(FelsongColors.Soot.copy(alpha = 0.5f)).clickable { TitleMusic.setOn(context, !on) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (on) Icons.Default.MusicNote else Icons.Default.MusicOff,
            contentDescription = stringResource(if (on) R.string.wow_music_turn_off else R.string.wow_music_turn_on),
            tint = if (on) FelsongColors.Lime else FelsongColors.Sage,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** What the realm is doing, and the one thing to do next (the other launchers' realm panel). */
@Composable
private fun RealmPanel(
    facts: FelsongFacts,
    server: FelsongServer.Status,
    job: FelsongJobs.State,
    game: FelsongGame.State,
    onPlay: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDialog: (FelsongDialogKind) -> Unit,
) {
    val context = LocalContext.current
    val panel = Modifier.fillMaxWidth()
    when {
        facts.blocker != null -> Notice(stringResource(if (facts.blocker == "arm64") R.string.felsong_error_not_arm64 else R.string.felsong_error_blocker))
        job is FelsongJobs.State.Working -> JobPanel(job, onOpen = { onDialog(dialogFor(job.kind)) })
        game is FelsongGame.State.Preparing -> FelsongWindow(panel, padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FelsongSpinner(40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(
                            when (game.step) {
                                FelsongGame.Step.RUNTIME -> R.string.felsong_game_step_runtime
                                FelsongGame.Step.WINDOWS -> R.string.felsong_game_step_windows
                                FelsongGame.Step.REALM -> R.string.felsong_game_step_realm
                                FelsongGame.Step.STARTING -> R.string.felsong_game_step_starting
                            }
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (game.step == FelsongGame.Step.RUNTIME || game.step == FelsongGame.Step.WINDOWS) {
                    game.progress?.let { FelsongProgressLine(it) }
                    Text(stringResource(R.string.wow_game_runtime_note), style = MaterialTheme.typography.bodySmall)
                }
                FelsongStatusLine(stringResource(R.string.wow_tile_server), FelsongTexts.server(context, server), good = server.phase == FelsongServer.Phase.RUNNING && server.worldUp)
                if (server.phase == FelsongServer.Phase.STARTING) server.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                if (game.step == FelsongGame.Step.STARTING) Text(stringResource(R.string.felsong_game_box64_note), style = MaterialTheme.typography.bodySmall)
            }
        }
        game is FelsongGame.State.Failed -> FelsongWindow(panel, padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FelsongTitle(stringResource(R.string.felsong_game_failed))
                Text(game.message, style = MaterialTheme.typography.bodyMedium)
                FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FelsongButton(stringResource(R.string.felsong_close), primary = false) { FelsongGame.dismiss() }
                    if (game.needsAccount) FelsongButton(stringResource(R.string.wow_tile_accounts)) { FelsongGame.dismiss(); onDialog(FelsongDialogKind.ACCOUNTS) }
                    else FelsongButton(stringResource(R.string.felsong_retry)) { FelsongGame.dismiss(); onPlay() }
                }
            }
        }
        server.phase == FelsongServer.Phase.STARTING || server.phase == FelsongServer.Phase.STOPPING -> FelsongWindow(panel, padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FelsongSpinner(40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(FelsongTexts.server(context, server), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                }
                server.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                if (server.step == FelsongServer.Step.WORLD && !facts.state.databaseUpdated) Text(stringResource(R.string.felsong_realm_first_start), style = MaterialTheme.typography.bodySmall)
            }
        }
        server.phase == FelsongServer.Phase.RUNNING -> FelsongWindow(panel, padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FelsongTitle(stringResource(R.string.wow_realm_online))
                FelsongStatusLine(stringResource(R.string.wow_realm_name), FelsongRealm.REALM_NAME)
                FelsongStatusLine(
                    stringResource(R.string.wow_realm_world),
                    stringResource(if (server.worldUp) R.string.wow_realm_world_up else R.string.wow_realm_world_no_data),
                    good = server.worldUp,
                )
                FelsongStatusLine(stringResource(R.string.wow_realm_here), "127.0.0.1")
                FelsongStatusLine(stringResource(R.string.wow_realm_lan), server.lanAddress ?: stringResource(R.string.wow_realm_lan_none))
                if (!facts.client) Text(stringResource(R.string.felsong_realm_no_client), style = MaterialTheme.typography.bodySmall)
                FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FelsongButton(stringResource(R.string.wow_server_stop), primary = false, onClick = onStop)
                    if (!server.worldUp || !facts.client || !facts.patched) {
                        FelsongButton(stringResource(R.string.wow_tile_files)) { onDialog(FelsongDialogKind.FILES) }
                    } else {
                        FelsongButton(stringResource(R.string.wow_game_play), onClick = onPlay)
                    }
                }
            }
        }
        server.phase == FelsongServer.Phase.FAILED -> FelsongWindow(panel, padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FelsongTitle(stringResource(R.string.wow_server_failed))
                Text(server.error ?: "", style = MaterialTheme.typography.bodyMedium)
                FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FelsongButton(stringResource(R.string.wow_server_details), primary = false) { onDialog(FelsongDialogKind.SERVER) }
                    FelsongButton(stringResource(R.string.wow_server_retry), onClick = onStart)
                }
            }
        }
        else -> {
            PressStart(onClick = onPlay)
            Text(
                stringResource(
                    when {
                        !facts.programsCurrent || !facts.databases -> if (facts.programsInstalled) R.string.felsong_home_start_line_update else R.string.felsong_home_start_line_install
                        facts.spp -> R.string.felsong_home_start_line_spp
                        !facts.data -> R.string.felsong_home_start_line_no_data
                        facts.client && facts.patched -> R.string.felsong_home_start_line_game
                        else -> R.string.felsong_home_start_line
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = FelsongColors.Sage,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // What's missing, with the button that fixes it (the other launchers' notices).
            when {
                facts.pin == null && !facts.programsInstalled -> Notice(stringResource(R.string.felsong_error_not_pinned))
                !facts.programsCurrent || !facts.databases -> facts.pin?.let { pin ->
                    Notice(stringResource(R.string.felsong_home_no_realm, Formatter.formatShortFileSize(context, pin.size), Formatter.formatShortFileSize(context, facts.free)))
                }
                facts.spp -> Notice(stringResource(R.string.felsong_home_spp), stringResource(R.string.felsong_spp_take)) { FelsongService.job(context, FelsongJobs.Kind.SPP) }
                !facts.client -> Notice(stringResource(R.string.felsong_home_no_client), stringResource(R.string.wow_tile_files)) { onDialog(FelsongDialogKind.FILES) }
                !facts.data || !facts.dataCurrent -> Notice(stringResource(R.string.felsong_home_no_data), stringResource(R.string.wow_tile_files)) { onDialog(FelsongDialogKind.FILES) }
                !facts.patched -> Notice(stringResource(R.string.felsong_home_no_patch), stringResource(R.string.felsong_patch_now)) { FelsongService.job(context, FelsongJobs.Kind.PATCH) }
                facts.accounts == 0 && facts.savedAccount == null -> Notice(stringResource(R.string.felsong_home_no_account), stringResource(R.string.wow_tile_accounts)) { onDialog(FelsongDialogKind.ACCOUNTS) }
            }
        }
    }
}

/** A job under way (installing, copying the client, making the data...); a tap opens its screen. */
@Composable
private fun JobPanel(job: FelsongJobs.State.Working, onOpen: () -> Unit) {
    val context = LocalContext.current
    FelsongWindow(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp), onClick = onOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FelsongSpinner(40.dp)
                Spacer(Modifier.width(12.dp))
                Text(FelsongTexts.step(context, job.step), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            }
            job.progress?.let { FelsongProgressLine(it) }
            job.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
        }
    }
}

/** PRESS START in the site's white-to-fel capitals, breathing. */
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
                .background(Brush.radialGradient(listOf(FelsongColors.Fel.copy(alpha = 0.25f), Color.Transparent)))
        )
        Text(
            stringResource(R.string.wow_home_press_start).uppercase(),
            style = FelsongGradientText.copy(fontSize = 27.sp, shadow = FelsongShadow, letterSpacing = FelsongDisplayStyle.letterSpacing * 2f),
            modifier = Modifier.graphicsLayer { alpha = 0.55f + 0.45f * breathe },
        )
    }
}

/** A line about something missing, with the button that fixes it. */
@Composable
private fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = FelsongColors.Bone, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 560.dp))
        if (action != null) FelsongButton(action, primary = false, onClick = onAction)
    }
}

private class Tile(val icon: ImageVector, val title: String, val subtitle: String, val onClick: () -> Unit)

@Composable
private fun TileGrid(tiles: List<Tile>, columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(columns).forEachIndexed { rowIndex, row ->
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
    FelsongWindow(
        modifier = modifier.graphicsLayer {
            alpha = appear.value
            translationY = (1f - appear.value) * 14.dp.toPx()
        },
        onClick = tile.onClick,
        padding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(tile.icon, contentDescription = null, tint = FelsongColors.Lime, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tile.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
}
