// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.logie.warcraft.BuildConfig
import com.logie.warcraft.R
import com.logie.warcraft.WowUpdateActivity
import com.logie.warcraft.WowUpdater
import com.logie.warcraft.felsong.ui.*
import com.logie.warcraft.game.GameSettings
import com.logie.warcraft.local.LocalInstallJobs
import com.logie.warcraft.local.WindowsRealmServer
import com.logie.warcraft.spp.*
import com.logie.warcraft.spp.core.SppRealm
import com.logie.warcraft.tww.core.TwwInstall
import com.logie.warcraft.tww.ui.TwwReportEffect
import com.logie.warcraft.ui.AppIconDialog
import com.logie.warcraft.ui.WowChoiceRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SppHomeRoute(onSwitchGame: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by SppJobs.state.collectAsState()
    val server by SppServer.status.collectAsState()
    val busy = job is LocalInstallJobs.State.Working
    var report by remember { mutableStateOf<TwwInstall.Report?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var choosingImport by rememberSaveable { mutableStateOf(LocalInstallJobs.Kind.IMPORT_CLIENT.name) }
    var playAfterStart by rememberSaveable { mutableStateOf(false) }
    var choosingAccountForPlay by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    val updateOffer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { checkingUpdate = false }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) SppService.start(context, LocalInstallJobs.Kind.valueOf(choosingImport), uri)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) SppService.start(context, LocalInstallJobs.Kind.valueOf(choosingImport), uri, true)
    }
    fun run(kind: LocalInstallJobs.Kind) { dialog = null; SppService.start(context, kind) }
    fun update() {
        if (checkingUpdate) return
        checkingUpdate = true
        scope.launch {
            try {
                val available = withContext(Dispatchers.IO) { if (WowUpdater.isConfigured) WowUpdater.findUpdate() else null }
                if (available != null) updateOffer.launch(WowUpdateActivity.intent(context, available))
                else message = "You have the latest available app update."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "The update check failed. Try again." }
            finally { checkingUpdate = false }
        }
    }
    TwwReportEffect(busy, if (busy) null else job, refresh, server.phase,
        read = { withContext(Dispatchers.IO) { SppFiles.report(context) } },
        onReport = { report = it }, onError = { message = it.message ?: "The installed files could not be read." })
    LaunchedEffect(server.phase) {
        if (playAfterStart && server.phase == WindowsRealmServer.Phase.RUNNING) {
            playAfterStart = false
            if (SppLogin.selectedName(context) == null) { choosingAccountForPlay = true; dialog = "accounts" }
            else SppGame.play(context)
        }
        if (server.phase == WindowsRealmServer.Phase.FAILED) playAfterStart = false
    }
    val editable = !busy && !SppServer.isBusy
    val ready = report?.canStartServer == true
    val count = report?.let { listOf(it.client, it.core, it.database, it.maps).count { component -> component.status == TwwInstall.Status.INSTALLED } } ?: 0
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        FelsongBackdrop()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            FelsongHeader(width, height, onSwitchGame)
            Column(Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FelsongWindow(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FelsongTitle(SppRealm.DISPLAY_NAME)
                        Text(SppRealm.DISTRIBUTION, color = FelsongColors.Sage)
                        if (job is LocalInstallJobs.State.Working) {
                            val current = job as LocalInstallJobs.State.Working
                            Text(current.detail)
                            val progress = current.progress
                            if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            FelsongButton("Pause installation", primary = false) { SppService.cancel(context) }
                        } else {
                            (job as? LocalInstallJobs.State.Failed)?.let { failure ->
                                Text(failure.message, color = FelsongColors.Blood, maxLines = 3)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    FelsongButton("Resume / Retry") { SppService.retry(context) }
                                    FelsongButton("Details", primary = false) { message = failure.message }
                                }
                            }
                            (job as? LocalInstallJobs.State.Done)?.let { Text(it.message, maxLines = 3, style = MaterialTheme.typography.bodySmall) }
                            Text(when {
                                server.phase == WindowsRealmServer.Phase.RUNNING -> "Your local Legion realm is running."
                                SppServer.isBusy -> server.detail ?: "Starting the local realm…"
                                ready -> "Your files are ready. Start the realm and play."
                                report == null -> "Checking installed files…"
                                job is LocalInstallJobs.State.Failed -> "Completed installation steps are kept. Retry continues with the missing components."
                                else -> "Download the game client, SPP server, database and maps directly to the app."
                            })
                            server.error?.let { Text(it, color = FelsongColors.Blood, maxLines = 3) }
                            if (job !is LocalInstallJobs.State.Failed || ready || server.phase == WindowsRealmServer.Phase.RUNNING) FelsongButton(when {
                                server.phase == WindowsRealmServer.Phase.RUNNING -> "Launch Game"
                                SppServer.isBusy -> "Server status"
                                ready -> "Press Start"
                                else -> "Download & Install"
                            }, Modifier.fillMaxWidth(), enabled = report != null) {
                                when {
                                    server.phase == WindowsRealmServer.Phase.RUNNING -> {
                                        if (SppLogin.selectedName(context) == null) { choosingAccountForPlay = true; dialog = "accounts" }
                                        else SppGame.play(context)
                                    }
                                    SppServer.isBusy -> dialog = "server"
                                    ready -> { playAfterStart = true; SppServer.start(context) }
                                    else -> run(LocalInstallJobs.Kind.INSTALL)
                                }
                            }
                            if (!ready && !SppServer.isBusy) Text(
                                "Setup uses SPP’s supplied local-play executable beside your original client. The full client is a 42.3 GB download; allow about 120 GB free during installation.",
                                style = MaterialTheme.typography.bodySmall)
                            if (!SppServer.isBusy) Text("Legion client support on Android is experimental; the runtime may fail before the login screen.",
                                style = MaterialTheme.typography.bodySmall, color = FelsongColors.Ember)
                        }
                    }
                }
                val tiles = listOf(
                    MenuTile(Icons.Default.Dns, "Server", server.phase.name.lowercase().replaceFirstChar { it.uppercase() }) { dialog = "server" },
                    MenuTile(Icons.Default.FolderOpen, "Game Files", "$count of 4 components installed") { dialog = "files" },
                    MenuTile(Icons.Default.AccountCircle, "Accounts", SppLogin.selectedName(context) ?: "Create or choose your local account") { dialog = "accounts" },
                    MenuTile(Icons.Default.Settings, "Realm & Graphics", "Dungeon scaling, experience, graphics driver") { dialog = "settings" },
                    MenuTile(Icons.Default.AspectRatio, "Graphics", stringResource(GameSettings.resolution(context).label)) { dialog = "graphics" },
                    MenuTile(Icons.Default.Palette, "App Icon", "Choose the app’s icon") { dialog = "icon" },
                    MenuTile(Icons.Default.SystemUpdateAlt, "Updates", if (checkingUpdate) "Checking…" else "Version ${BuildConfig.VERSION_NAME}") { update() },
                    MenuTile(Icons.Default.SwapHoriz, "Games", "Choose another expansion", onSwitchGame),
                )
                val columns = if (width >= 760.dp) 3 else if (width >= 480.dp) 2 else 1
                tiles.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { tile ->
                            FelsongWindow(Modifier.weight(1f).heightIn(min = 130.dp), onClick = tile.click) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(tile.icon, null, tint = FelsongColors.Fel)
                                    Text(tile.title.uppercase(), style = MaterialTheme.typography.titleLarge)
                                    Text(tile.subtitle, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
    message?.let { text -> FelsongDialog("Legion", { message = null }, buttons = { FelsongButton("Close") { message = null } }) { Text(text) } }
    when (dialog) {
        "server" -> FelsongDialog("Local Server", { dialog = null }, buttons = { FelsongButton("Close") { dialog = null } }) {
            Text("${SppRealm.DISTRIBUTION} · 127.0.0.1")
            StatusRow("Database", server.database.name.lowercase(), server.database == WindowsRealmServer.ComponentStatus.RUNNING)
            StatusRow("Authentication", server.authentication.name.lowercase(), server.authentication == WindowsRealmServer.ComponentStatus.RUNNING)
            StatusRow("World", server.world.name.lowercase(), server.world == WindowsRealmServer.ComponentStatus.RUNNING)
            server.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            server.error?.let { Text(it, color = FelsongColors.Blood) }
            FelsongButton("Start Server", enabled = editable && ready) { SppServer.start(context) }
            FelsongButton("Stop Server", primary = false, enabled = !busy && SppServer.isBusy) { playAfterStart = false; SppServer.stop(context) }
            FelsongButton("Launch Game", enabled = !busy && server.phase == WindowsRealmServer.Phase.RUNNING) {
                dialog = null
                if (SppLogin.selectedName(context) == null) { choosingAccountForPlay = true; dialog = "accounts" }
                else SppGame.play(context)
            }
            FelsongButton("Logs", primary = false) {
                scope.launch {
                    try {
                        message = withContext(Dispatchers.IO) {
                            listOf("database", "authentication", "world", "client").joinToString("\n\n") { name ->
                                val file = File(SppFiles.layout(context).root, "runtime/$name.log")
                                name.replaceFirstChar { it.uppercase() } + "\n" + if (file.isFile) file.readLines().takeLast(20).joinToString("\n").takeLast(2500) else "No log has been produced."
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { message = error.message ?: "The server log could not be read." }
                }
            }
            if (server.phase == WindowsRealmServer.Phase.FAILED && SppServer.isBusy)
                FelsongButton("Force Stop", primary = false) { dialog = "force" }
            FelsongButton("Background activity", primary = false) { dialog = "background" }
        }
        "files" -> FelsongDialog("Game Files", { dialog = null }, buttons = { FelsongButton("Close") { dialog = null } }) {
            report?.let { value ->
                ComponentRow("Game Client", value.client)
                ComponentRow("Server Core", value.core)
                ComponentRow("Server Database", value.database)
                ComponentRow("Map Files", value.maps)
                if (!value.localConnectionConfigured || !value.localServerConfigured) Text("Finish local setup before starting the game.", color = FelsongColors.Ember)
            } ?: Text("Checking installed files…")
            FelsongButton("Download & Install", Modifier.fillMaxWidth(), enabled = editable) { run(LocalInstallJobs.Kind.INSTALL) }
            FelsongButton("Verify Installation", primary = false, enabled = editable) { run(LocalInstallJobs.Kind.VERIFY) }
            FelsongButton("Installation Options", primary = false, enabled = editable) { dialog = "advanced" }
        }
        "advanced" -> FelsongDialog("Installation Options", { dialog = null }) {
            Text("Client: 42.3 GB full download. SPP repack: 5.4 GB, including its database and server maps. Files stay separate from the other realms.")
            StatusRow("Storage", if (SppFiles.external(context)) "External app storage" else "Internal app storage", true)
            FelsongButton("Use ${if (SppFiles.external(context)) "internal" else "external"} storage", primary = false, enabled = editable) {
                if (!SppFiles.setExternal(context, !SppFiles.external(context))) message = "Choose storage before installing. Existing files were kept in their current directory."
                else refresh++
            }
            FelsongButton("Download client", enabled = editable) { run(LocalInstallJobs.Kind.DOWNLOAD_CLIENT) }
            FelsongButton("Reinstall server", primary = false, enabled = editable) { dialog = "replace" }
            FelsongButton("Import existing files (optional)", primary = false, enabled = editable) { dialog = "import" }
            FelsongButton("Clear temporary downloads", primary = false, enabled = editable) { run(LocalInstallJobs.Kind.CLEAN_DOWNLOADS) }
            FelsongButton("Download sources", primary = false) { dialog = "sources" }
        }
        "replace" -> FelsongDialog("Replace SPP Server?", { dialog = null }, buttons = {
            FelsongButton("Cancel", primary = false) { dialog = null }
            FelsongButton("Replace Server") { run(LocalInstallJobs.Kind.DOWNLOAD_SERVER) }
        }) { Text("This replaces the SPP server and its database, including local characters and accounts. Back up that database first. Normal Download & Install preserves an existing database.") }
        "import" -> FelsongDialog("Import Existing Files", { dialog = null }) {
            Text("Optional: normal setup downloads everything in the app. Imports accept a matching 26972 client or the original SPP AshamaneCore repack.")
            listOf(LocalInstallJobs.Kind.IMPORT_CLIENT to "client", LocalInstallJobs.Kind.IMPORT_SERVER to "server").forEach { (kind, label) ->
                FelsongButton("Import $label folder", primary = false, enabled = editable) { choosingImport = kind.name; dialog = null; folderPicker.launch(null) }
                FelsongButton("Import $label archive", primary = false, enabled = editable) { choosingImport = kind.name; dialog = null; archivePicker.launch(arrayOf("*/*")) }
            }
        }
        "sources" -> FelsongDialog("Download Sources", { dialog = null }) {
            Text("Original SPP AshamaneCore · Legion 7.3.5 build 26972. The app validates the inspected archives before installation.")
            FelsongButton("Client archive", primary = false) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SppRealm.CLIENT_URL))) }
            FelsongButton("Server archive", primary = false) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SppRealm.SERVER_URL))) }
        }
        "accounts" -> SppAccountsDialog(server.phase == WindowsRealmServer.Phase.RUNNING, ready, { dialog = null; choosingAccountForPlay = false }) {
            dialog = null; refresh++
            if (choosingAccountForPlay) { choosingAccountForPlay = false; SppGame.play(context) }
        }
        "settings" -> SppSettingsDialog(editable, { dialog = null }, { refresh++; dialog = null })
        "graphics" -> {
            var resolution by remember { mutableStateOf(GameSettings.resolution(context)) }
            FelsongDialog("Graphics", { dialog = null }) {
                Text("Restart the local realm to apply a new game resolution.")
                GameSettings.Resolution.entries.forEach { value ->
                    val size = GameSettings.gameSize(context, value)
                    WowChoiceRow(stringResource(value.label), "${size.first} × ${size.second}", resolution == value) { GameSettings.setResolution(context, value); resolution = value; refresh++ }
                }
            }
        }
        "icon" -> AppIconDialog { dialog = null }
        "force" -> FelsongDialog("Force Stop?", { dialog = null }, buttons = {
            FelsongButton("Cancel", primary = false) { dialog = "server" }
            FelsongButton("Force Stop") { playAfterStart = false; SppServer.forceStop(context); dialog = "server" }
        }) { Text("Forced termination can lose unsaved progress or leave the database needing recovery. Use it only after normal Stop Server fails.") }
        "background" -> SppBackgroundDialog { dialog = null }
    }
}

private data class MenuTile(val icon: ImageVector, val title: String, val subtitle: String, val click: () -> Unit)

@Composable private fun StatusRow(name: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, modifier = Modifier.weight(1f))
        Text(value, color = if (good) FelsongColors.Pale else FelsongColors.Sage)
    }
}

@Composable private fun ComponentRow(name: String, component: TwwInstall.Component) {
    Column(Modifier.padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusRow(name, component.status.name.lowercase().replaceFirstChar { it.uppercase() }, component.status == TwwInstall.Status.INSTALLED)
        (component.missing + component.details).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun SppAccountsDialog(running: Boolean, canStart: Boolean, onDismiss: () -> Unit, onSelected: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<SppAccounts.Account>>(emptyList()) }
    var email by remember { mutableStateOf(SppLogin.selectedName(context).orEmpty()) }
    var password by remember { mutableStateOf("") }
    var create by remember { mutableStateOf(false) }
    var administrator by remember { mutableStateOf(false) }
    var rememberPassword by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(running) {
        if (running) {
            try { accounts = withContext(Dispatchers.IO) { SppAccounts.list(context) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
        }
    }
    fun submit(changePassword: Boolean = false) {
        working = true; error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when {
                        changePassword -> SppAccounts.changePassword(context, email, password)
                        create -> SppAccounts.create(context, email, password, administrator)
                    }
                    if (!SppAccounts.isPassword(context, email, password)) throw java.io.IOException("The account name or password does not match SPP’s database.")
                    SppLogin.save(context, email, password, rememberPassword)
                    SppGame.prepareLaunch(context)
                }
                onSelected()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "The account operation failed." }
            finally { working = false }
        }
    }
    FelsongDialog("Local Accounts", onDismiss, buttons = { FelsongButton("Close", primary = false, onClick = onDismiss) }) {
        if (!running) {
            Text("Start the local SPP server, then create or choose your account here.")
            FelsongButton("Start Server", enabled = !SppServer.isBusy && !SppJobs.busy && canStart) { SppServer.start(context) }
        } else {
            accounts.forEach { account -> FelsongChoiceRow(account.email, if (email.equals(account.email, true)) "Selected" else "Use this Battle.net account") { email = account.email; create = false; password = "" } }
            OutlinedTextField(email, { email = it }, label = { Text("Email address") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !working)
            OutlinedTextField(password, { password = it }, label = { Text(if (create) "New password" else "Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !working)
            Text("Use 1–16 printable password characters. Accounts and characters belong only to this local realm.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(create, { create = it }, enabled = !working); Text("Create a new account") }
            if (create) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(administrator, { administrator = it }, enabled = !working); Text("Game master account") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(rememberPassword, { rememberPassword = it }, enabled = !working); Text("Remember password on this device") }
            error?.let { Text(it, color = FelsongColors.Blood) }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            FelsongButton(if (create) "Create & Use Account" else "Use Account", enabled = !working && email.isNotBlank() && password.isNotEmpty()) { submit() }
            if (!create) FelsongButton("Change Password", primary = false, enabled = !working && email.isNotBlank() && password.isNotEmpty()) { submit(true) }
        }
    }
}

@Composable private fun SppSettingsDialog(editable: Boolean, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var values by remember { mutableStateOf<SppSettings.Values?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { values = withContext(Dispatchers.IO) { SppSettings.load(context) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message }
    }
    FelsongDialog("Realm & Graphics", onDismiss, buttons = {
        FelsongButton("Close", primary = false, onClick = onDismiss)
        FelsongButton("Apply", enabled = editable && values != null && !saving) {
            saving = true
            scope.launch {
                try { withContext(Dispatchers.IO) { SppSettings.save(context, values!!) }; onSaved() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failure = error.message }
                finally { saving = false }
            }
        }
    }) {
        if (!editable) Text("Stop the local realm before changing these settings.")
        values?.let { current ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(current.solo, { values = current.copy(solo = it) }, enabled = editable && !saving)
                Text("Solo dungeon and raid scaling")
            }
            Text("Solocraft adjusts player stats for dungeon and raid content.", style = MaterialTheme.typography.bodySmall)
            Text("Experience rate: ×${current.xp}")
            SppSettings.RATES.forEach { rate -> WowChoiceRow("×$rate", null, current.xp.toDoubleOrNull() == rate.toDouble()) { if (editable) values = current.copy(xp = rate) } }
            Text("Every race and class from the start: the allied races (Nightborne, Highmountain Tauren, Void Elf, Lightforged Draenei) and Demon Hunters, without Battle for Azeroth or a level-70 character first.",
                style = MaterialTheme.typography.bodySmall)
            Text("Graphics driver")
            WowChoiceRow("Vortek", "The phone's own driver, as the other games use", current.graphics == SppSettings.VORTEK) {
                if (editable) values = current.copy(graphics = SppSettings.VORTEK)
            }
            if (SppSettings.turnipAvailable(context)) WowChoiceRow("Turnip", "Mesa's driver for Adreno: try it if the game shows nothing or stops", current.graphics == SppSettings.TURNIP) {
                if (editable) values = current.copy(graphics = SppSettings.TURNIP)
            }
        }
        failure?.let { Text(it, color = FelsongColors.Blood) }
        if (values == null && failure == null || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@SuppressLint("BatteryLife")
@Composable private fun SppBackgroundDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    val unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
    FelsongDialog("Background Activity", onDismiss) {
        Text("Downloads and the active local realm keep running when you switch apps or lock the screen.")
        StatusRow("Battery access", if (unrestricted) "Unrestricted" else "Optimized", unrestricted)
        if (!unrestricted) FelsongButton("Allow background activity") {
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            try { context.startActivity(request) }
            catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
        }
    }
}
