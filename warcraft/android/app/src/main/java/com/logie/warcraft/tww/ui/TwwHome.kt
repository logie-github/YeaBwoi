// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww.ui

import com.logie.warcraft.local.LocalInstallJobs

import com.logie.warcraft.local.WindowsRealmServer

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.warcraft.*
import com.logie.warcraft.R
import com.logie.warcraft.game.GameSettings
import com.logie.warcraft.tww.*
import com.logie.warcraft.tww.core.TwwInstall
import com.logie.warcraft.tww.core.TwwRealm
import com.logie.warcraft.ui.AppIconDialog
import com.logie.warcraft.ui.WowChoiceRow
import com.logie.warcraft.ui.WowDisplayStyle
import com.logie.warcraft.ui.WowProgressLine
import com.logie.warcraft.ui.WowTheme
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Hero, one next action and menu tiles, following the app's existing realm home screens. */
@Composable
fun TwwLauncher(checkUpdateOnOpen: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by TwwJobs.state.collectAsState()
    val server by TwwServer.status.collectAsState()
    var report by remember { mutableStateOf<TwwInstall.Report?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var configuring by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var pendingImport by rememberSaveable { mutableStateOf(LocalInstallJobs.Kind.IMPORT_CLIENT.name) }
    var checkUpdate by rememberSaveable { mutableStateOf(checkUpdateOnOpen) }
    var playAfterStart by rememberSaveable { mutableStateOf(false) }
    val updateOffer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { checkUpdate = false }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) TwwService.start(context, LocalInstallJobs.Kind.valueOf(pendingImport), uri, true)
    }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) TwwService.start(context, LocalInstallJobs.Kind.valueOf(pendingImport), uri)
    }
    fun run(kind: LocalInstallJobs.Kind) { TwwService.start(context, kind) }
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { message = it.message }
    }
    fun configure() {
        dialog = null; configuring = true
        scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching { TwwServer.configureLocal(context); TwwGame.configureLocal(context, true); context.getString(R.string.tww_configured) }
                    .getOrElse { it.message ?: it.javaClass.simpleName }
            }
            configuring = false; refresh++
        }
    }
    LaunchedEffect(checkUpdate) {
        if (checkUpdate) {
            val update = withContext(Dispatchers.IO) { if (WowUpdater.isConfigured) runCatching { WowUpdater.findUpdate() }.getOrNull() else null }
            if (update != null) updateOffer.launch(WowUpdateActivity.intent(context, update)) else checkUpdate = false
        }
    }
    LaunchedEffect(job) {
        val result = job as? LocalInstallJobs.State.Done
        if (result?.kind == LocalInstallJobs.Kind.VERIFY || result?.kind == LocalInstallJobs.Kind.CLEAN_DOWNLOADS) message = result?.message
    }
    TwwReportEffect(
        busy = job is LocalInstallJobs.State.Working,
        version = if (job is LocalInstallJobs.State.Working) null else job,
        refresh = refresh,
        phase = server.phase,
        read = { withContext(Dispatchers.IO) { TwwFiles.report(context) } },
        onReport = { report = it },
        onError = { message = it.message ?: it.javaClass.simpleName },
    )
    LaunchedEffect(server.phase) {
        if (playAfterStart && server.phase == WindowsRealmServer.Phase.RUNNING) { playAfterStart = false; TwwGame.play(context) }
        if (server.phase == WindowsRealmServer.Phase.FAILED) playAfterStart = false
    }
    val busy = job is LocalInstallJobs.State.Working
    val editable = !busy && !TwwServer.isBusy && !configuring
    val installed = report?.let { listOf(it.client, it.core, it.database, it.maps).count { c -> c.status == TwwInstall.Status.INSTALLED } } ?: 0
    WowTheme {
        BoxWithConstraints(Modifier.fillMaxSize().background(TwwColors.Night)) {
            val screenWidth = maxWidth
            val screenHeight = maxHeight
            val columns = if (screenWidth >= 600.dp) 3 else 2
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TwwHeader(screenWidth, screenHeight) { dialog = "games" }
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TwwWindow(Modifier.fillMaxWidth()) {
                        Text(TwwRealm.DISPLAY_NAME, style = MaterialTheme.typography.headlineSmall, color = TwwColors.Gold)
                        Text("Draconic WoW · Local realm", style = MaterialTheme.typography.bodySmall, color = TwwColors.Muted)
                        when (val state = job) {
                            is LocalInstallJobs.State.Working -> {
                                Text(state.detail, style = MaterialTheme.typography.bodyMedium)
                                state.progress?.let { WowProgressLine(it) }
                                TwwButton("Pause installation", primary = false) { TwwService.cancel(context) }
                            }
                            is LocalInstallJobs.State.Failed -> {
                                Text("Installation needs attention", color = TwwColors.Error)
                                Text(state.message, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    TwwButton("Resume / Retry", Modifier.weight(1f)) { TwwService.retry(context) }
                                    TwwButton("Details", primary = false) { message = state.message }
                                }
                            }
                            else -> {
                                val ready = report?.canStartServer == true
                                Text(when {
                                    server.phase == WindowsRealmServer.Phase.RUNNING -> "Your local realm is running."
                                    TwwServer.isBusy -> server.detail ?: "Starting local realm…"
                                    ready -> "Your files are ready. Start your local realm and play."
                                    report == null -> "Checking game files…"
                                    else -> "Download the game client, server and maps directly to the app."
                                }, style = MaterialTheme.typography.bodyMedium)
                                server.error?.let { Text(it, color = TwwColors.Error, maxLines = 3) }
                                server.clientError?.let { Text(it, color = TwwColors.Error, maxLines = 3) }
                                TwwButton(when {
                                    server.phase == WindowsRealmServer.Phase.RUNNING -> "Launch Game"
                                    TwwServer.isBusy -> "Starting…"
                                    configuring -> "Configuring…"
                                    ready -> "Press Start"
                                    else -> "Download & Install"
                                }, Modifier.fillMaxWidth(), enabled = report != null && !configuring && (!TwwServer.isBusy || server.phase == WindowsRealmServer.Phase.RUNNING)) {
                                    when {
                                        server.phase == WindowsRealmServer.Phase.RUNNING -> TwwGame.play(context)
                                        ready -> { playAfterStart = true; TwwServer.start(context) }
                                        else -> run(LocalInstallJobs.Kind.INSTALL)
                                    }
                                }
                                if (!ready && !TwwServer.isBusy) Text(stringResource(R.string.tww_one_tap_note), style = MaterialTheme.typography.bodySmall, color = TwwColors.Muted)
                            }
                        }
                    }
                    val tiles = listOf(
                        Tile(Icons.Default.Dns, "Server", server.phase.name.lowercase().replaceFirstChar { it.uppercase() }) { dialog = "server" },
                        Tile(Icons.Default.FolderOpen, "Game Files", "$installed of 4 components installed") { dialog = "files" },
                        Tile(Icons.Default.Tune, "Graphics", stringResource(GameSettings.resolution(context).label)) { dialog = "graphics" },
                        Tile(Icons.Default.Palette, "App Icon", stringResource(AppIcons.current(context).label)) { dialog = "icon" },
                        Tile(Icons.Default.SystemUpdate, "Updates", "Version ${BuildConfig.VERSION_NAME}") { WowUpdateActivity.launch(context) },
                        Tile(Icons.Default.SwapHoriz, "Games", "Choose another expansion") { dialog = "games" },
                    )
                    tiles.chunked(columns).forEach { row ->
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { tile -> TwwWindow(Modifier.weight(1f).fillMaxHeight(), onClick = tile.onClick) {
                                Icon(tile.icon, null, tint = TwwColors.Gold, modifier = Modifier.size(26.dp))
                                Text(tile.title, style = WowDisplayStyle.copy(fontSize = 14.sp, color = TwwColors.Text))
                                Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, color = TwwColors.Muted)
                            } }
                        }
                    }
                }
            }
        }
        message?.let { text -> TwwDialog("The War Within", { message = null }) { Text(text) } }
        when (dialog) {
            "server" -> TwwDialog("Local Server", { dialog = null }) {
                TwwStatus("Database", server.database.name.lowercase(), server.database == WindowsRealmServer.ComponentStatus.RUNNING)
                TwwStatus("Authentication", server.authentication.name.lowercase(), server.authentication == WindowsRealmServer.ComponentStatus.RUNNING)
                TwwStatus("World server", server.world.name.lowercase(), server.world == WindowsRealmServer.ComponentStatus.RUNNING)
                server.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                server.error?.let { Text(it, color = TwwColors.Error) }
                server.clientError?.let { Text(it, color = TwwColors.Error) }
                TwwButton("Start Server", enabled = editable && report?.canStartServer == true) { TwwServer.start(context) }
                TwwButton("Stop Server", primary = false, enabled = !busy && TwwServer.isBusy) { playAfterStart = false; TwwServer.stop(context) }
                TwwButton("Launch Game", enabled = !busy && server.phase == WindowsRealmServer.Phase.RUNNING) { TwwGame.play(context) }
                if (server.phase == WindowsRealmServer.Phase.FAILED && TwwServer.isBusy) TwwButton("Force Stop", primary = false) { dialog = "force-stop" }
                TwwButton("Local connection settings", primary = false, enabled = editable) { dialog = "connection" }
                TwwButton("Background activity", primary = false) { dialog = "background" }
            }
            "files" -> TwwDialog("Game Files", { dialog = null }) {
                report?.let { checked ->
                    ComponentLine("Game Client", checked.client)
                    ComponentLine("Server Core", checked.core)
                    ComponentLine("Server Database", checked.database)
                    ComponentLine("Map Files", checked.maps)
                    checked.blockers.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                TwwButton("Download & Install", Modifier.fillMaxWidth(), enabled = editable) { dialog = null; run(LocalInstallJobs.Kind.INSTALL) }
                TwwButton("Verify Installation", primary = false, enabled = editable) { dialog = null; run(LocalInstallJobs.Kind.VERIFY) }
                (job as? LocalInstallJobs.State.Done)?.let { Text(it.message, style = MaterialTheme.typography.bodySmall) }
                TwwButton("Advanced / Storage", primary = false) { dialog = "advanced" }
            }
            "advanced" -> TwwDialog("Installation Options", { dialog = null }) {
                Text(TwwFiles.layout(context).root.path, style = MaterialTheme.typography.bodySmall)
                TwwButton(stringResource(if (TwwFiles.storageExternal(context)) R.string.tww_internal else R.string.tww_external), primary = false, enabled = editable) {
                    if (!TwwFiles.setExternal(context, !TwwFiles.storageExternal(context))) message = context.getString(R.string.tww_storage_error)
                    refresh++
                }
                Text(stringResource(R.string.tww_replace_note), style = MaterialTheme.typography.bodySmall)
                listOf("Download client" to LocalInstallJobs.Kind.DOWNLOAD_CLIENT, "Reinstall server" to LocalInstallJobs.Kind.DOWNLOAD_SERVER, "Download map files" to LocalInstallJobs.Kind.DOWNLOAD_MAPS).forEach { (label, kind) ->
                    TwwButton(label, primary = false, enabled = editable) {
                        if (kind == LocalInstallJobs.Kind.DOWNLOAD_SERVER && report?.detected != null) dialog = "replace-server"
                        else { dialog = null; run(kind) }
                    }
                }
                TwwButton("Import existing files (optional)", primary = false, enabled = editable) { dialog = "import" }
                TwwButton("Clear temporary downloads", primary = false, enabled = editable) { dialog = null; run(LocalInstallJobs.Kind.CLEAN_DOWNLOADS) }
                TwwButton("Download sources", primary = false) { dialog = "sources" }
            }
            "replace-server" -> TwwDialog("Replace server?", { dialog = null }, buttons = {
                TwwButton("Cancel", primary = false) { dialog = null }
                TwwButton("Replace Server") { dialog = null; run(LocalInstallJobs.Kind.DOWNLOAD_SERVER) }
            }) { Text("This replaces the existing Draconic server and database. Back up your accounts and characters first.") }
            "import" -> TwwDialog("Import Existing Files", { dialog = null }) {
                Text(stringResource(R.string.tww_archive_support), style = MaterialTheme.typography.bodySmall)
                listOf(LocalInstallJobs.Kind.IMPORT_CLIENT to "client", LocalInstallJobs.Kind.IMPORT_SERVER to "server", LocalInstallJobs.Kind.IMPORT_MAPS to "map files").forEach { (kind, name) ->
                    TwwButton("Import $name folder", primary = false, enabled = editable) { pendingImport = kind.name; dialog = null; folderPicker.launch(null) }
                    TwwButton("Import $name archive", primary = false, enabled = editable) { pendingImport = kind.name; dialog = null; archivePicker.launch(arrayOf("*/*")) }
                }
            }
            "connection" -> TwwDialog("Local Connection", { dialog = null }, buttons = {
                TwwButton("Close", primary = false) { dialog = null }
                TwwButton("Configure", enabled = editable) { configure() }
            }) { Text(stringResource(R.string.tww_connection_note)) }
            "force-stop" -> TwwDialog("Force Stop", { dialog = null }, buttons = {
                TwwButton("Cancel", primary = false) { dialog = null }
                TwwButton("Force Stop") { dialog = null; playAfterStart = false; TwwServer.forceStop(context) }
            }) { Text(stringResource(R.string.tww_force_stop_warning)) }
            "background" -> TwwBackgroundDialog({ dialog = null }, { message = it })
            "graphics" -> {
                var current by remember { mutableStateOf(GameSettings.resolution(context)) }
                TwwDialog("Graphics", { dialog = null; refresh++ }) {
                    Text("Game resolution. Restart the local realm to apply changes.", style = MaterialTheme.typography.bodySmall)
                    GameSettings.Resolution.entries.forEach { resolution ->
                        val (width, height) = GameSettings.gameSize(context, resolution)
                        WowChoiceRow(stringResource(resolution.label), "$width × $height", resolution == current) { GameSettings.setResolution(context, resolution); current = resolution }
                    }
                }
            }
            "icon" -> AppIconDialog { dialog = null; refresh++ }
            "sources" -> TwwDialog("Download Sources", { dialog = null }) {
                Text("Streaming client: Draconic publisher’s Firestorm mini-client, 11.2.5.63704. Game assets stream during play.", style = MaterialTheme.typography.bodySmall)
                TwwButton("Client archive", primary = false) { open(TwwRealm.CLIENT_ARCHIVE_URL) }
                TwwButton("Firestorm", primary = false) { open(TwwRealm.CLIENT_URL) }
                TwwButton("Server repack", primary = false) { open(TwwRealm.SERVER_URL) }
                TwwButton("Map files", primary = false) { open(TwwRealm.MMAPS_URL) }
            }
            "games" -> TwwDialog(stringResource(R.string.games_title), { dialog = null }) {
                listOf(Environments.Environment.WRATH to R.string.wrath_name, Environments.Environment.TURTLE to R.string.turtle_tile_name, Environments.Environment.FELSONG to R.string.felsong_name).forEach { (environment, label) ->
                    WowChoiceRow(stringResource(label), null, false) { Environments.select(context, environment); TitleMusic.onGameChanged(context); dialog = null }
                }
            }
        }
    }
}

@Composable
private fun TwwHeader(width: Dp, height: Dp, onSwitch: () -> Unit) {
    val headerHeight = minOf((width * .62f).coerceIn(240.dp, 440.dp), height * .55f)
    val logoWidth = minOf(width * .76f, headerHeight * 1.15f, 360.dp)
    val logoHeight = logoWidth * (721f / 900f)
    val motion = rememberInfiniteTransition(label = "tww-header")
    val drift by motion.animateFloat(1f, 1.06f, infiniteRepeatable(tween(32000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift")
    val music by TitleMusic.on.collectAsState()
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()).height(headerHeight + logoHeight * .44f)) {
        Box(Modifier.fillMaxWidth().height(headerHeight).clipToBounds()) {
            Image(painterResource(R.drawable.tww_header), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = drift; scaleY = drift })
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.35f to Color.Transparent, 1f to TwwColors.Night)))
        }
        Image(painterResource(R.drawable.tww_logo), null, modifier = Modifier.align(Alignment.TopCenter).offset(y = headerHeight - logoHeight * .58f).width(logoWidth).height(logoHeight))
        Row(Modifier.align(Alignment.TopStart).padding(10.dp).clip(CircleShape).background(TwwColors.Night.copy(alpha = .7f)).clickable(onClick = onSwitch).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SwapHoriz, null, tint = TwwColors.Gold, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.games_chip), style = WowDisplayStyle.copy(fontSize = 13.sp))
        }
        Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(44.dp).clip(CircleShape).background(TwwColors.Night.copy(alpha = .7f)).clickable { TitleMusic.setOn(context, !music) }, contentAlignment = Alignment.Center) {
            Icon(if (music) Icons.Default.MusicNote else Icons.Default.MusicOff, stringResource(if (music) R.string.wow_music_turn_off else R.string.wow_music_turn_on), tint = TwwColors.Gold)
        }
    }
}

private class Tile(val icon: ImageVector, val title: String, val subtitle: String, val onClick: () -> Unit)

@Composable
private fun ComponentLine(label: String, component: TwwInstall.Component) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TwwStatus(label, component.status.name.lowercase().replaceFirstChar { it.uppercase() }, component.status == TwwInstall.Status.INSTALLED)
        (component.missing + component.details).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = TwwColors.Muted) }
    }
}

/** A superseded or disposed screen cancels a scan normally; cancellation is never a file error. */
@Composable
internal fun TwwReportEffect(
    busy: Boolean, version: Any?, refresh: Int, phase: WindowsRealmServer.Phase,
    read: suspend () -> TwwInstall.Report,
    onReport: (TwwInstall.Report) -> Unit,
    onError: (Exception) -> Unit,
) {
    LaunchedEffect(busy, version, refresh, phase) {
        if (!busy) {
            try { onReport(read()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                onError(error)
            }
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun TwwBackgroundDialog(onDismiss: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { unrestricted = power.isIgnoringBatteryOptimizations(context.packageName) }
    TwwDialog("Background Activity", onDismiss) {
        Text("Active downloads and the local realm continue when you switch apps or lock the screen. Their notification lets you return to the app.")
        TwwStatus("Battery access", if (unrestricted) "Unrestricted" else "Optimized", unrestricted)
        if (!unrestricted) {
            Text("Allow unrestricted battery access if your phone stops background work.", style = MaterialTheme.typography.bodySmall)
            TwwButton("Allow background activity") {
                try { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
                catch (error: Exception) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                        .onFailure { onError(it.message ?: error.message ?: "Battery settings could not be opened.") }
                }
            }
        }
        Text("Pause, Stop Server and Exit still stop the selected work. Android Force Stop closes the app and its background work.", style = MaterialTheme.typography.bodySmall)
    }
}
