// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.ui

import android.app.Activity
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
// Turtle WoW's game switch (out of order: kept apart from the Wrath Remix line's import above).
import androidx.compose.material.icons.filled.SwapHoriz
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
import com.logie.warcraft.AddonStore
import com.logie.warcraft.AppIcons
import com.logie.warcraft.BuildConfig
import com.logie.warcraft.Environments
import com.logie.warcraft.ImportService
import com.logie.warcraft.Imports
import com.logie.warcraft.LegacyRaces
import com.logie.warcraft.ModuleStore
import com.logie.warcraft.R
import com.logie.warcraft.Realms
import com.logie.warcraft.Server
import com.logie.warcraft.ServerPackage
import com.logie.warcraft.ServerService
import com.logie.warcraft.TitleMusic
import com.logie.warcraft.WowFiles
import com.logie.warcraft.WowUpdateActivity
import com.logie.warcraft.WowUpdater
import com.logie.warcraft.ZonesPackage
import com.logie.warcraft.game.Game
import com.logie.warcraft.game.GameSettings
import com.logie.warcraft.turtle.ui.GameSwitch
import com.logie.warcraft.turtle.ui.SwitchToTurtleDialog
import com.logie.warcraft.turtle.ui.TurtleLauncher
import com.logie.warcraft.turtle.ui.WrathGameChooser
import com.logie.warcraft.felsong.ui.FelsongLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The launcher, the first screen of the app: the Lich King over Icecrown, the logo, and under them
 * the realm. Opening the app offers any update and starts nothing: [PressStart] starts the game
 * ([Game]: the server, then the client) once it has the server's data and the client, or the server
 * alone without the client.
 */
@Composable
fun WowHomeRoute(activity: Activity, checkUpdateOnOpen: Boolean) {
    // The game chosen (Environments): Turtle WoW and Felsong have launchers of their own, in their
    // own looks.
    val environment by Environments.selected(LocalContext.current).collectAsState()
    when (environment) {
        Environments.Environment.TURTLE -> TurtleLauncher(checkUpdateOnOpen)
        Environments.Environment.FELSONG -> FelsongLauncher(checkUpdateOnOpen)
        Environments.Environment.TWW -> com.logie.warcraft.tww.ui.TwwLauncher(checkUpdateOnOpen)
        else -> WrathHomeRoute(activity, checkUpdateOnOpen)
    }
}

/** Wrath of the Lich King's launcher, as it has always been, with a way to the other games'. */
@Composable
private fun WrathHomeRoute(activity: Activity, checkUpdateOnOpen: Boolean) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var askTurtle by remember { mutableStateOf(false) }
    var choosingGame by remember { mutableStateOf(false) }
    val server by Server.status.collectAsState()
    val imports by Imports.state.collectAsState()
    val game by Game.state.collectAsState()
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    var checkUpdate by rememberSaveable { mutableStateOf(checkUpdateOnOpen) }

    val facts = remember(refresh, imports, server.phase) { HomeFacts.of(context) }

    fun startServer() {
        if (facts.ready) Server.start(context)
    }

    /** The game when it can start (the client and the server's data are there), else the realm alone. */
    fun play() {
        if (!facts.ready) return
        if (facts.hasClient && facts.hasData) Game.play(context) else Server.start(context)
    }

    val updateOffer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkUpdate = false
    }

    // Opening the app: offer a newer version if there is one. The server and the game wait for
    // Press start.
    LaunchedEffect(Unit) {
        if (!checkUpdate) return@LaunchedEffect
        val update = if (WowUpdater.isConfigured) {
            // Offline, or GitHub unreachable: carry on, and check again next time.
            withContext(Dispatchers.IO) { runCatching { WowUpdater.findUpdate() }.getOrNull() }
        } else {
            null
        }
        if (update != null) updateOffer.launch(WowUpdateActivity.intent(context, update)) else checkUpdate = false
    }

    // The custom races of earlier versions: their part of the client goes (the realm's part at
    // its next start).
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { LegacyRaces.cleanClient(context) } }
    }

    val serverStopped = server.phase == Server.Phase.STOPPED || server.phase == Server.Phase.FAILED

    // The Worgen's and Goblins' starting areas, once the realm's set up and while it's stopped: this
    // build's package downloaded once (ZonesPackage), in at the realm's next start.
    var zonesTried by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(checkUpdate, serverStopped, imports is Imports.State.Idle, facts) {
        if (zonesTried || checkUpdate || !serverStopped || imports !is Imports.State.Idle) return@LaunchedEffect
        if (withContext(Dispatchers.IO) { ZonesPackage.needed(context) }) {
            zonesTried = true
            ImportService.downloadZones(context)
        }
    }

    // A realm build with the user's modules, asked for earlier: how it went, once at the app's
    // opening; a finished one goes in while the realm is stopped (Modules screen otherwise).
    LaunchedEffect(checkUpdate, serverStopped, imports is Imports.State.Idle) {
        if (checkUpdate || !serverStopped || imports !is Imports.State.Idle || modulesCheckTried) return@LaunchedEffect
        if (!ModuleStore.isAvailable) return@LaunchedEffect
        ModuleStore.load(context)
        if (ModuleStore.pending.value == null) return@LaunchedEffect
        modulesCheckTried = true
        val build = withContext(Dispatchers.IO) { runCatching { ModuleStore.refresh(context) }.getOrNull() }
            ?: ModuleStore.pending.value
        if (build != null && build.succeeded && build.versionCode == BuildConfig.VERSION_CODE &&
            Imports.state.value is Imports.State.Idle && Server.status.value.phase.let { it == Server.Phase.STOPPED || it == Server.Phase.FAILED }
        ) {
            ImportService.installModules(context)
        }
    }

    // How a long job went, as soon as it's done.
    LaunchedEffect(imports) {
        if ((imports is Imports.State.Done || imports is Imports.State.Failed) && dialog == null) {
            val kind = (imports as? Imports.State.Done)?.kind ?: (imports as? Imports.State.Failed)?.kind
            dialog = when (kind) {
                Imports.Kind.MODS -> modsDialog
                Imports.Kind.MODULES -> HomeDialog.MODULES
                else -> HomeDialog.FILES
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { refresh++ }

    WowTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HomeScreen(
                facts = facts,
                server = server,
                imports = imports,
                game = game,
                onStart = { startServer() },
                onPlay = { play() },
                onStop = { Server.stop(context) },
                onDialog = { dialog = it },
                onSwitchGame = { choosingGame = true },
            )
            if (dialog == HomeDialog.ADDONS) AddonManager(onDismiss = { dialog = null; refresh++ })
            if (dialog == HomeDialog.MODULES) ModuleManager(onDismiss = { dialog = null; Imports.clearResult(); refresh++ })
        }
        if (choosingGame) WrathGameChooser(onDismiss = { choosingGame = false }) { target ->
            choosingGame = false
            if (!GameSwitch.trySwitch(context, target)) askTurtle = true
        }
        if (askTurtle) SwitchToTurtleDialog(onDismiss = { askTurtle = false })
        when (dialog) {
            HomeDialog.SERVER -> ServerDialog(onDismiss = { dialog = null; refresh++ })
            HomeDialog.FILES -> GameFilesDialog(onDismiss = { dialog = null; Imports.clearResult(); refresh++ })
            HomeDialog.ACCOUNTS -> AccountsDialog(onDismiss = { dialog = null })
            HomeDialog.ICON -> AppIconDialog(onDismiss = { dialog = null })
            HomeDialog.REALM -> RealmDialog(onDismiss = { dialog = null; refresh++ })
            HomeDialog.GRAPHICS -> GraphicsDialog(onDismiss = { dialog = null; refresh++ })
            // Over the whole screen, in the box above.
            HomeDialog.ADDONS, HomeDialog.MODULES -> {}
            null -> {}
        }
    }
}

enum class HomeDialog { SERVER, FILES, ACCOUNTS, GRAPHICS, ADDONS, MODULES, ICON, REALM }

/** Where the client's changes (Imports.Kind.MODS) were last asked for: their outcome shows there. */
private var modsDialog = HomeDialog.GRAPHICS

/** Whether this run of the app has looked at a realm build in flight. */
private var modulesCheckTried = false

/** What's on the phone, read when the screen needs it. */
data class HomeFacts(
    /** This build has a server in it (local builds may not). */
    val bundled: Boolean,
    /** The app is where the server's programs expect it. */
    val standardPath: Boolean,
    val hasClient: Boolean,
    val dataFolders: Set<String>,
    val hasData: Boolean,
    val serverVersion: String?,
) {
    /** The server can start (the world server also needs [hasData]). */
    val ready: Boolean get() = bundled && standardPath

    companion object {
        fun of(context: android.content.Context) = HomeFacts(
            // A build without a server of its own runs the one already unpacked (ServerPackage).
            bundled = ServerPackage.isBundled(context) || ServerPackage.isInstalled(context),
            standardPath = WowFiles.isStandardPath(context),
            hasClient = WowFiles.hasClient(context),
            dataFolders = WowFiles.dataFolders(context),
            hasData = WowFiles.hasServerData(context),
            serverVersion = ServerPackage.version(context)?.lines()?.firstOrNull { it.startsWith("merged ") }?.removePrefix("merged ")?.take(7),
        )
    }
}

@Composable
internal fun HomeScreen(
    facts: HomeFacts,
    server: Server.Status,
    imports: Imports.State,
    game: Game.State,
    onStart: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDialog: (HomeDialog) -> Unit,
    onSwitchGame: () -> Unit = {},
) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight
        WowBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WowHeader(screenWidth, screenHeight, onSwitchGame)
            Column(
                Modifier
                    .widthIn(max = 860.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RealmPanel(facts, server, imports, game, onStart, onPlay, onStop, onDialog)

                WowTitle(
                    stringResource(R.string.wow_home_menu),
                    modifier = Modifier.padding(top = 14.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                val tiles = listOf(
                    HomeTile(Icons.Default.Public, stringResource(R.string.wow_tile_realm), stringResource(Realms.current(context).label)) {
                        onDialog(HomeDialog.REALM)
                    },
                    HomeTile(Icons.Default.Dns, stringResource(R.string.wow_tile_server), ServerService.statusText(context, server)) {
                        onDialog(HomeDialog.SERVER)
                    },
                    HomeTile(Icons.Default.FolderOpen, stringResource(R.string.wow_tile_files), filesSummary(facts)) {
                        onDialog(HomeDialog.FILES)
                    },
                    HomeTile(Icons.Default.People, stringResource(R.string.wow_tile_accounts), stringResource(R.string.wow_tile_accounts_summary)) {
                        onDialog(HomeDialog.ACCOUNTS)
                    },
                    HomeTile(Icons.Default.Tune, stringResource(R.string.wow_tile_graphics), graphicsSummary(context)) {
                        modsDialog = HomeDialog.GRAPHICS
                        onDialog(HomeDialog.GRAPHICS)
                    },
                    HomeTile(Icons.Default.Extension, stringResource(R.string.wow_tile_addons), addonsSummary(context, facts)) {
                        onDialog(HomeDialog.ADDONS)
                    },
                    HomeTile(Icons.Default.Build, stringResource(R.string.wow_tile_modules), modulesSummary(context)) {
                        onDialog(HomeDialog.MODULES)
                    },
                    HomeTile(Icons.Default.Palette, stringResource(R.string.wow_tile_icon), stringResource(AppIcons.current(context).label)) {
                        onDialog(HomeDialog.ICON)
                    },
                    HomeTile(Icons.Default.SystemUpdate, stringResource(R.string.wow_tile_updates),
                        stringResource(R.string.wow_tile_updates_summary, BuildConfig.VERSION_NAME)) {
                        WowUpdateActivity.launch(context)
                    },
                    // Turtle WoW and Felsong, games of their own: the launcher switches to them (GameSwitch).
                    HomeTile(Icons.Default.SwapHoriz, stringResource(R.string.games_tile), stringResource(R.string.games_tile_wrath_summary)) {
                        onSwitchGame()
                    },
                )
                val columns = (screenWidth / 260.dp).toInt().coerceIn(1, 3).let { if (screenWidth >= 360.dp) maxOf(it, 2) else it }
                TileGrid(tiles, columns)

                Text(
                    stringResource(R.string.wow_version, BuildConfig.VERSION_NAME, facts.serverVersion ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp, bottom = 22.dp),
                )
            }
        }
    }
}

@Composable
private fun graphicsSummary(context: android.content.Context): String {
    val resolution = GameSettings.resolution(context)
    val (width, height) = GameSettings.gameSize(context, resolution)
    return stringResource(R.string.wow_tile_graphics_summary, stringResource(resolution.label), width, height)
}

@Composable
private fun addonsSummary(context: android.content.Context, facts: HomeFacts): String {
    if (!facts.hasClient) return stringResource(R.string.wow_tile_addons_no_client)
    LaunchedEffect(facts) { AddonStore.refresh(context) }
    val installed by AddonStore.installed.collectAsState()
    val count = installed.size
    return if (count == 0) stringResource(R.string.wow_tile_addons_none) else addonCountText(count)
}

@Composable
private fun filesSummary(facts: HomeFacts): String = stringResource(
    when {
        facts.hasData && facts.hasClient -> R.string.wow_tile_files_all
        facts.hasData -> R.string.wow_tile_files_data
        facts.hasClient -> R.string.wow_tile_files_client
        else -> R.string.wow_tile_files_none
    }
)

/**
 * The Lich King before Icecrown Citadel with the frost wyrm, snow drifting across, the picture
 * slowly closing in; the logo over its lower edge.
 */
@Composable
private fun WowHeader(screenWidth: Dp, screenHeight: Dp, onSwitchGame: () -> Unit) {
    // Tall enough for the Lich King on a phone held upright, leaving room for the realm panel
    // with the phone sideways.
    val headerHeight = min((screenWidth * 0.62f).coerceIn(240.dp, 460.dp), screenHeight * 0.62f)
    val logoWidth = min(min(screenWidth * 0.78f, headerHeight * 1.25f), 460.dp)
    val logoHeight = logoWidth * (610f / 1400f)

    val motion = rememberInfiniteTransition(label = "header")
    val drift by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(30_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift")
    val breathe by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(3_600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(150)
        reveal.animateTo(1f, tween(1_600, easing = FastOutSlowInEasing))
    }

    // The picture starts under the status bar, which would hide the Lich King's crown.
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxWidth().padding(top = statusBar).height(headerHeight + logoHeight * 0.55f)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .clipToBounds()
        ) {
            Image(
                painter = painterResource(R.drawable.wow_header),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // The Lich King stands a third of the way in: keep him in when the screen is
                // narrower than the picture.
                alignment = BiasAlignment(-0.42f, -0.4f),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val zoom = 1.03f + 0.05f * drift
                        scaleX = zoom
                        scaleY = zoom
                        transformOrigin = TransformOrigin(0.33f, 0f)
                        alpha = reveal.value
                    },
            )
            WowSnow(alpha = reveal.value)
            // The picture sinks into the night below it, where the logo sits.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to WowColors.Abyss))
            )
        }
        Image(
            painter = painterResource(R.drawable.wow_logo),
            contentDescription = stringResource(R.string.wow_game_name),
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
        MusicSwitch(
            Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .graphicsLayer { alpha = reveal.value }
        )
        GameSwitchChip(
            Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .graphicsLayer { alpha = reveal.value },
            onSwitchGame,
        )
    }
}

/** To Turtle WoW's launcher: a small chip in the picture's corner. */
@Composable
private fun GameSwitchChip(modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(CircleShape)
            .background(WowColors.Abyss.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = WowColors.Ice, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.games_chip), style = WowDisplayStyle.copy(fontSize = 13.sp))
    }
}

/** The launcher music's switch: a note, crossed out while it's off. */
@Composable
private fun MusicSwitch(modifier: Modifier) {
    val context = LocalContext.current
    val on by TitleMusic.on.collectAsState()
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(WowColors.Abyss.copy(alpha = 0.45f))
            .clickable { TitleMusic.setOn(context, !on) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (on) Icons.Default.MusicNote else Icons.Default.MusicOff,
            contentDescription = stringResource(if (on) R.string.wow_music_turn_off else R.string.wow_music_turn_on),
            tint = if (on) WowColors.Ice else WowColors.Mist,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** What the realm is doing, and the one thing to do next. */
@Composable
private fun RealmPanel(
    facts: HomeFacts,
    server: Server.Status,
    imports: Imports.State,
    game: Game.State,
    onStart: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDialog: (HomeDialog) -> Unit,
) {
    val context = LocalContext.current
    when {
        !facts.bundled -> Notice(stringResource(R.string.wow_home_no_server_build))
        !facts.standardPath -> Notice(stringResource(R.string.wow_server_error_path, WowFiles.root(context).path))
        imports is Imports.State.Working -> ImportPanel(imports, onOpen = { onDialog(HomeDialog.FILES) })
        game is Game.State.Preparing -> GamePanel(game, server)
        game is Game.State.Failed -> WowWindow(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WowTitle(stringResource(R.string.wow_game_failed))
                Text(game.message, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WowButton(stringResource(R.string.wow_close), primary = false) { Game.dismiss() }
                    if (game.needsAccount) {
                        WowButton(stringResource(R.string.wow_tile_accounts)) { Game.dismiss(); onDialog(HomeDialog.ACCOUNTS) }
                    } else {
                        WowButton(stringResource(R.string.wow_server_retry), onClick = onPlay)
                    }
                }
            }
        }
        server.phase == Server.Phase.STARTING || server.phase == Server.Phase.STOPPING -> WowWindow(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WowSpinner(40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(ServerService.statusText(context, server), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                }
                server.progress?.let { WowProgressLine(it) }
                server.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                if (server.step == Server.Step.IMPORT) {
                    Text(stringResource(R.string.wow_step_import_note), style = MaterialTheme.typography.bodySmall)
                }
                if (server.step == Server.Step.RACES && server.detail?.startsWith("Custom races") == false) {
                    Text(stringResource(R.string.wow_step_races_note), style = MaterialTheme.typography.bodySmall)
                }
                if (server.step == Server.Step.MODULES) {
                    Text(stringResource(R.string.wow_step_modules_note), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        server.phase == Server.Phase.RUNNING -> WowWindow(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WowTitle(stringResource(R.string.wow_realm_online))
                WowStatusLine(stringResource(R.string.wow_realm_name), server.realm.displayName)
                WowStatusLine(
                    stringResource(R.string.wow_realm_world),
                    stringResource(if (server.world == Server.World.UP) R.string.wow_realm_world_up else R.string.wow_realm_world_no_data),
                    good = server.world == Server.World.UP,
                )
                WowStatusLine(stringResource(R.string.wow_realm_here), "127.0.0.1")
                WowStatusLine(stringResource(R.string.wow_realm_lan), server.lanAddress ?: stringResource(R.string.wow_realm_lan_none))
                if (!facts.hasClient) Text(stringResource(R.string.wow_realm_no_client), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WowButton(stringResource(R.string.wow_server_stop), primary = false, onClick = onStop)
                    if (server.world == Server.World.NO_DATA || !facts.hasClient) {
                        WowButton(stringResource(R.string.wow_tile_files), primary = true) { onDialog(HomeDialog.FILES) }
                    } else {
                        WowButton(stringResource(R.string.wow_game_play), primary = true, onClick = onPlay)
                    }
                }
            }
        }
        server.phase == Server.Phase.FAILED -> WowWindow(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WowTitle(stringResource(R.string.wow_server_failed))
                Text(server.error ?: "", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WowButton(stringResource(R.string.wow_server_details), primary = false) { onDialog(HomeDialog.SERVER) }
                    WowButton(stringResource(R.string.wow_server_retry), onClick = onStart)
                }
            }
        }
        else -> {
            PressStart(onClick = onPlay)
            Text(
                stringResource(
                    when {
                        !facts.hasData -> R.string.wow_home_start_line_no_data
                        facts.hasClient -> R.string.wow_home_start_line_game
                        else -> R.string.wow_home_start_line
                    },
                    Realms.current(context).displayName,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = WowColors.Mist,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!facts.hasData) {
                Notice(stringResource(R.string.wow_home_no_data), stringResource(R.string.wow_tile_files)) { onDialog(HomeDialog.FILES) }
            }
        }
    }
}

/** The game getting ready: Winlator's runtime, the game's Windows, then waiting for the realm. */
@Composable
private fun GamePanel(game: Game.State.Preparing, server: Server.Status) {
    val context = LocalContext.current
    WowWindow(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WowSpinner(40.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(
                        when (game.step) {
                            Game.Step.RUNTIME -> R.string.wow_game_step_runtime
                            Game.Step.WINDOWS -> R.string.wow_game_step_windows
                            Game.Step.REALM -> R.string.wow_game_step_realm
                            Game.Step.STARTING -> R.string.wow_game_step_starting
                        }
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
            }
            if (game.step == Game.Step.RUNTIME || game.step == Game.Step.WINDOWS) {
                game.progress?.let { WowProgressLine(it) }
                Text(stringResource(R.string.wow_game_runtime_note), style = MaterialTheme.typography.bodySmall)
            }
            WowStatusLine(
                stringResource(R.string.wow_tile_server),
                ServerService.statusText(context, server),
                good = server.phase == Server.Phase.RUNNING && server.world == Server.World.UP,
            )
            if (server.phase == Server.Phase.STARTING) {
                server.progress?.let { WowProgressLine(it) }
                server.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
        }
    }
}

@Composable
private fun ImportPanel(state: Imports.State.Working, onOpen: () -> Unit) {
    WowWindow(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp), onClick = onOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WowSpinner(40.dp)
                Spacer(Modifier.width(12.dp))
                Text(importTitle(state), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            }
            state.progress?.let { WowProgressLine(it) }
            state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
        }
    }
}

@Composable
fun importTitle(state: Imports.State.Working): String = when (state.kind) {
    Imports.Kind.CLIENT -> stringResource(R.string.wow_import_client_progress, ((state.progress ?: 0f) * 100).toInt())
    Imports.Kind.DOWNLOAD -> stringResource(
        if (state.part <= 1) R.string.wow_download_progress else R.string.wow_download_unpack_progress,
        ((state.progress ?: 0f) * 100).toInt(),
    )
    Imports.Kind.SERVER_DATA -> stringResource(R.string.wow_data_import_progress, ((state.progress ?: 0f) * 100).toInt())
    Imports.Kind.EXTRACT -> stringResource(R.string.wow_extract_progress, state.part, state.parts)
    Imports.Kind.MODS -> state.progress?.let { stringResource(R.string.wow_mods_progress, (it * 100).toInt()) }
        ?: stringResource(R.string.wow_mods_title)
    Imports.Kind.MODULES -> state.progress?.let { stringResource(R.string.wow_modules_job_progress, (it * 100).toInt()) }
        ?: stringResource(R.string.wow_modules_job_title)
    Imports.Kind.ZONES -> stringResource(
        if (state.part <= 1) R.string.wow_zones_progress else R.string.wow_zones_unpack_progress,
        ((state.progress ?: 0f) * 100).toInt(),
    )
}

/** `PRESS START`, gold, breathing with the frost light behind it. */
@Composable
private fun PressStart(onClick: () -> Unit) {
    val motion = rememberInfiniteTransition(label = "start")
    val breathe by motion.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1_500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 260.dp, height = 56.dp)
                .graphicsLayer { alpha = breathe }
                .background(Brush.radialGradient(listOf(WowColors.Ice.copy(alpha = 0.25f), Color.Transparent)))
        )
        Text(
            stringResource(R.string.wow_home_press_start).uppercase(),
            style = WowDisplayStyle.copy(fontSize = 25.sp, color = WowColors.Gold, shadow = WowGoldShadow, letterSpacing = WowDisplayStyle.letterSpacing * 1.5f),
            modifier = Modifier.graphicsLayer { alpha = 0.55f + 0.45f * breathe },
        )
    }
}

/** A line about something missing, with the button that fixes it. */
@Composable
private fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = WowColors.Text,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp),
        )
        if (action != null) WowButton(action, primary = false, onClick = onAction)
    }
}

private class HomeTile(val icon: ImageVector, val title: String, val subtitle: String, val onClick: () -> Unit)

@Composable
private fun TileGrid(tiles: List<HomeTile>, columns: Int) {
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
private fun TileWindow(tile: HomeTile, index: Int, modifier: Modifier) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(450L + index * 90L)
        appear.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    WowWindow(
        modifier = modifier.graphicsLayer {
            alpha = appear.value
            translationY = (1f - appear.value) * 14.dp.toPx()
        },
        onClick = tile.onClick,
        padding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(tile.icon, contentDescription = null, tint = WowColors.Ice, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tile.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
}
