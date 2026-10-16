// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalLayoutApi::class)

package com.logie.warcraft.turtle.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.logie.warcraft.AppIcons
import com.logie.warcraft.BuildConfig
import com.logie.warcraft.R
import com.logie.warcraft.Server
import com.logie.warcraft.WowFiles
import com.logie.warcraft.WowUpdateActivity
import com.logie.warcraft.game.GameSettings
import com.logie.warcraft.turtle.TurtleAccounts
import com.logie.warcraft.turtle.TurtleFiles
import com.logie.warcraft.turtle.TurtleJobs
import com.logie.warcraft.turtle.TurtleLogin
import com.logie.warcraft.turtle.TurtlePackage
import com.logie.warcraft.turtle.TurtleServer
import com.logie.warcraft.turtle.TurtleService
import com.logie.warcraft.turtle.TurtleSettings
import com.logie.warcraft.turtle.TurtleTexts
import com.logie.warcraft.turtle.core.InstallState
import com.logie.warcraft.turtle.core.TurtleAccountRules
import com.logie.warcraft.turtle.core.TurtleRealm
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TurtleDialogs(dialog: TurtleDialogKind?, facts: TurtleFacts, onDialog: (TurtleDialogKind) -> Unit, onDismiss: () -> Unit) {
    when (dialog) {
        TurtleDialogKind.SERVER -> ServerDialog(facts, onDialog, onDismiss)
        TurtleDialogKind.FILES -> FilesDialog(onDismiss)
        TurtleDialogKind.ACCOUNTS -> AccountsDialog(onDismiss)
        TurtleDialogKind.GRAPHICS -> GraphicsDialog(onDismiss)
        TurtleDialogKind.OPTIONS -> OptionsDialog(onDismiss)
        TurtleDialogKind.ICON -> IconDialog(onDismiss)
        TurtleDialogKind.REPAIR -> RepairDialog(onDismiss)
        TurtleDialogKind.UNINSTALL -> UninstallDialog(facts, onDismiss)
        // Over the whole screen (TurtleHomeRoute).
        TurtleDialogKind.ADDONS, null -> {}
    }
}

/** A job of this screen's: under way, done or failed (the 3.3.5a Game files screen's top). */
@Composable
private fun JobState(job: TurtleJobs.State, screen: TurtleDialogKind) {
    val context = LocalContext.current
    when (job) {
        is TurtleJobs.State.Working -> if (dialogFor(job.kind) == screen) TurtleWindow(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TurtleSpinner(32.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(TurtleTexts.step(context, job.step), style = MaterialTheme.typography.titleMedium)
                }
                job.progress?.let { TurtleProgressLine(it) }
                job.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                Text(stringResource(R.string.wow_import_background_note), style = MaterialTheme.typography.bodySmall)
            }
        }
        is TurtleJobs.State.Done -> if (dialogFor(job.kind) == screen) Text(job.message, style = MaterialTheme.typography.bodyMedium, color = TurtleColors.Leaf)
        is TurtleJobs.State.Failed -> if (dialogFor(job.kind) == screen) {
            Text(job.message, style = MaterialTheme.typography.bodyMedium, color = TurtleColors.Blood)
            job.log?.takeIf { it.isNotBlank() }?.let { TurtleLog(it) }
        }
        TurtleJobs.State.Idle -> {}
    }
}

// The server -------------------------------------------------------------------------------------

/**
 * The server (the 3.3.5a launcher's Server screen): how Turtle WoW's realm is, start and stop, its
 * own parts each put in with one tap, playing from a PC, its logs, the battery, backups of the
 * accounts and characters, starting over (repair, uninstall), and what it is.
 */
@Composable
private fun ServerDialog(facts: TurtleFacts, onDialog: (TurtleDialogKind) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by TurtleServer.status.collectAsState()
    val job by TurtleJobs.state.collectAsState()
    var log by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val power = context.getSystemService(PowerManager::class.java)
    val unrestricted = remember(tick) { power.isIgnoringBatteryOptimizations(context.packageName) }
    val stopped = status.phase == TurtleServer.Phase.STOPPED || status.phase == TurtleServer.Phase.FAILED
    val working = job is TurtleJobs.State.Working
    val busy = working || !stopped
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
    val backups = remember(job) { TurtleJobs.backups(context) }

    TurtleDialog(
        title = stringResource(R.string.wow_server_title),
        onDismiss = onDismiss,
        buttons = {
            if (working && dialogFor((job as TurtleJobs.State.Working).kind) == TurtleDialogKind.SERVER) {
                TurtleButton(stringResource(R.string.wow_import_stop), primary = false) { TurtleJobs.cancel() }
            }
            if (stopped) {
                TurtleButton(stringResource(R.string.wow_server_start), enabled = facts.programsInstalled && facts.databases && !working) { TurtleServer.start(context) }
            } else if (status.phase != TurtleServer.Phase.STOPPING) {
                TurtleButton(stringResource(R.string.wow_server_stop), primary = false) { TurtleServer.stop(context) }
            }
            TurtleButton(stringResource(R.string.wow_close), primary = false, onClick = onDismiss)
        },
    ) {
        JobState(job, TurtleDialogKind.SERVER)
        TurtleStatusLine(
            stringResource(R.string.wow_server_state), TurtleTexts.server(context, status),
            good = when (status.phase) {
                TurtleServer.Phase.RUNNING -> status.worldUp
                TurtleServer.Phase.FAILED -> false
                else -> null
            },
        )
        status.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
        status.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TurtleColors.Blood) }
        if (status.blockedByChromieCraft) {
            TurtleButton(stringResource(R.string.turtle_stop_chromiecraft)) { TurtleServer.dismiss(); Server.stop(context) }
        }

        // Each of the realm's parts, with its own button.
        TurtleTitle(stringResource(R.string.turtle_server_parts), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.turtle_server_parts_body), style = MaterialTheme.typography.bodySmall)
        TurtleStatusLine(
            stringResource(R.string.turtle_server_programs),
            when {
                facts.programsCurrent -> stringResource(R.string.turtle_part_installed, facts.version ?: "")
                facts.programsInstalled -> stringResource(R.string.turtle_part_update)
                facts.downloadedSoFar > 0 -> stringResource(R.string.turtle_part_partial, size(facts.downloadedSoFar))
                else -> stringResource(R.string.turtle_part_missing)
            },
            good = facts.programsCurrent,
        )
        TurtleButton(
            stringResource(
                when {
                    facts.programsInstalled && !facts.programsCurrent -> R.string.turtle_update_realm
                    facts.programsCurrent -> R.string.turtle_reinstall
                    facts.downloadedSoFar > 0 -> R.string.turtle_resume
                    else -> R.string.turtle_download_install
                }
            ),
            primary = !facts.programsCurrent,
            enabled = !busy && facts.pin != null && facts.blocker == null,
        ) { TurtleService.job(context, TurtleJobs.Kind.PROGRAMS) }
        facts.pin?.let {
            Text(stringResource(R.string.turtle_install_space, size(it.size), size(it.unpacked), size(facts.free)), style = MaterialTheme.typography.bodySmall)
        } ?: Text(stringResource(R.string.turtle_error_not_pinned), style = MaterialTheme.typography.bodySmall, color = TurtleColors.Blood)
        TurtleStatusLine(
            stringResource(R.string.turtle_server_databases),
            stringResource(if (facts.databases) R.string.turtle_part_created else R.string.turtle_part_missing),
            good = facts.databases,
        )
        if (!facts.databases) {
            TurtleButton(stringResource(R.string.turtle_create), enabled = !busy && facts.programsInstalled) { TurtleService.job(context, TurtleJobs.Kind.DATABASE) }
        }
        TurtleStatusLine(
            stringResource(R.string.turtle_server_runtime),
            stringResource(
                when {
                    facts.runtimeReady -> R.string.turtle_part_ready
                    facts.runtimeThere -> R.string.turtle_part_runtime_old
                    else -> R.string.turtle_part_missing
                }
            ),
            good = facts.runtimeThere,
        )
        if (!facts.runtimeThere) {
            TurtleButton(stringResource(R.string.turtle_install_short), enabled = !working) { TurtleService.job(context, TurtleJobs.Kind.RUNTIME) }
        }

        TurtleTitle(stringResource(R.string.wow_server_pc_title), style = MaterialTheme.typography.titleLarge)
        var lan by remember { mutableStateOf(TurtleSettings.lan(context)) }
        TurtleSwitchRow(stringResource(R.string.turtle_lan), stringResource(R.string.turtle_lan_summary), lan) {
            lan = it
            TurtleSettings.setLan(context, it)
        }
        val address = status.lanAddress ?: Server.lanAddress()
        Text(
            if (address != null) stringResource(R.string.turtle_server_pc_body, address) else stringResource(R.string.wow_server_pc_no_wifi),
            style = MaterialTheme.typography.bodyMedium,
        )

        TurtleTitle(stringResource(R.string.wow_server_logs), style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, file) in listOf(
                R.string.wow_log_world to "mangosd.out",
                R.string.wow_log_auth to "realmd.out",
                R.string.wow_log_database to "mariadb.log",
                R.string.turtle_log_data to "maps.log",
            )) {
                TurtleButton(stringResource(label), primary = false) {
                    scope.launch {
                        log = withContext(Dispatchers.IO) { WowFiles.tail(File(TurtleFiles.layout(context).logs, file), 60) }
                            .ifBlank { context.getString(R.string.wow_log_empty) }
                    }
                }
            }
        }
        log?.let { TurtleLog(it) }

        if (!unrestricted) {
            TurtleTitle(stringResource(R.string.wow_server_battery_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.wow_server_battery_body), style = MaterialTheme.typography.bodySmall)
            TurtleButton(stringResource(R.string.wow_server_battery_action), primary = false) {
                @SuppressLint("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }
                scope.launch {
                    delay(4000)
                    tick++
                }
            }
        }

        TurtleTitle(stringResource(R.string.turtle_backups), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.turtle_backups_body), style = MaterialTheme.typography.bodySmall)
        TurtleButton(stringResource(R.string.turtle_backup_now), primary = false, enabled = !working && facts.databases) { TurtleService.job(context, TurtleJobs.Kind.BACKUP) }
        for (file in backups) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(file.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TurtleButton(stringResource(R.string.turtle_restore), primary = false, enabled = !busy) {
                    TurtleService.job(context, TurtleJobs.Kind.RESTORE, options = TurtleJobs.Options(restoreFile = file.name))
                }
            }
        }

        TurtleTitle(stringResource(R.string.wow_server_reset_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.turtle_server_reset_body), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TurtleButton(stringResource(R.string.turtle_repair_open), primary = false, enabled = !working) { onDialog(TurtleDialogKind.REPAIR) }
            TurtleButton(stringResource(R.string.turtle_uninstall_open), primary = false, enabled = !working) { onDialog(TurtleDialogKind.UNINSTALL) }
        }

        TurtleTitle(stringResource(R.string.turtle_server_about), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.turtle_about_body), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.turtle_about_bots), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.turtle_about_client), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.turtle_about_isolation), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.turtle_about_legal), style = MaterialTheme.typography.bodySmall)
        TurtlePackage.version(context)?.let {
            Text(stringResource(R.string.wow_server_version, it.trim()), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// Game files -------------------------------------------------------------------------------------

/** Your client and the server's data (the 3.3.5a launcher's Game files screen): add, check, make. */
@Composable
private fun FilesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val job by TurtleJobs.state.collectAsState()
    val hasClient = remember(job) { TurtleFiles.hasClient(context) }
    val parts = remember(job) { TurtleFiles.dataParts(context) }
    val state = remember(job) { TurtleFiles.state(context) }
    val installed = remember(job) { TurtlePackage.isInstalled(context) }
    var clientSize by remember { mutableStateOf<Long?>(null) }
    var withMmaps by remember { mutableStateOf(TurtleSettings.mmaps(context)) }
    val working = job is TurtleJobs.State.Working

    LaunchedEffect(hasClient) {
        clientSize = if (hasClient) withContext(Dispatchers.IO) { WowFiles.sizeOf(TurtleFiles.layout(context).client) } else null
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) TurtleService.job(context, TurtleJobs.Kind.CLIENT, uri, folder = true, TurtleJobs.Options(withMmaps = withMmaps))
    }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) TurtleService.job(context, TurtleJobs.Kind.CLIENT, uri, folder = false, TurtleJobs.Options(withMmaps = withMmaps))
    }
    val pickData = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) TurtleService.job(context, TurtleJobs.Kind.IMPORT_DATA, uri, folder = false)
    }

    TurtleDialog(
        title = stringResource(R.string.wow_files_title),
        onDismiss = onDismiss,
        buttons = {
            if (working && dialogFor((job as TurtleJobs.State.Working).kind) == TurtleDialogKind.FILES) {
                TurtleButton(stringResource(R.string.wow_import_stop), primary = false) { TurtleJobs.cancel() }
            }
            TurtleButton(stringResource(R.string.wow_close), primary = false, onClick = onDismiss)
        },
    ) {
        JobState(job, TurtleDialogKind.FILES)

        TurtleTitle(stringResource(R.string.wow_files_client), style = MaterialTheme.typography.titleLarge)
        val sizeText = clientSize?.let { Formatter.formatShortFileSize(context, it) } ?: "…"
        TurtleStatusLine(
            stringResource(R.string.turtle_files_client_state),
            when {
                !hasClient -> stringResource(R.string.wow_files_client_no)
                state.clientCheck == InstallState.ClientCheck.EXACT -> stringResource(R.string.turtle_files_client_exact, sizeText)
                state.clientCheck == InstallState.ClientCheck.DIFFERENT -> stringResource(R.string.turtle_files_client_different, sizeText, state.clientDifferences)
                else -> stringResource(R.string.wow_files_client_yes, sizeText)
            },
            good = if (state.clientCheck == InstallState.ClientCheck.DIFFERENT) null else hasClient,
        )
        Text(stringResource(R.string.turtle_files_client_body), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TurtleButton(stringResource(R.string.wow_files_client_folder), primary = !hasClient, enabled = !working) { pickFolder.launch(null) }
            TurtleButton(stringResource(R.string.wow_files_client_zip), primary = false, enabled = !working) {
                pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
            if (hasClient) TurtleButton(stringResource(R.string.turtle_check_files), primary = false, enabled = !working) { TurtleService.job(context, TurtleJobs.Kind.VERIFY) }
        }

        TurtleTitle(stringResource(R.string.wow_files_data), style = MaterialTheme.typography.titleLarge)
        for (part in listOf("dbc", "maps", "vmaps", "mmaps")) {
            TurtleStatusLine(part, stringResource(if (part in parts) R.string.wow_files_data_yes else R.string.wow_files_data_no), good = part in parts)
        }
        Text(stringResource(R.string.turtle_files_data_body), style = MaterialTheme.typography.bodySmall)
        TurtleSwitchRow(stringResource(R.string.wow_files_mmaps), stringResource(R.string.turtle_install_mmaps_summary), withMmaps, enabled = !working) {
            withMmaps = it
            TurtleSettings.setMmaps(context, it)
        }
        TurtleButton(stringResource(R.string.wow_files_extract), primary = hasClient && parts.isEmpty(), enabled = hasClient && installed && !working) {
            TurtleService.job(context, TurtleJobs.Kind.DATA, options = TurtleJobs.Options(withMmaps = withMmaps))
        }
        TurtleTitle(stringResource(R.string.turtle_files_data_pc_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.turtle_files_data_pc_body), style = MaterialTheme.typography.bodySmall)
        TurtleButton(stringResource(R.string.turtle_files_data_import), primary = false, enabled = !working) {
            pickData.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        Text(stringResource(R.string.wow_files_space, Formatter.formatShortFileSize(context, TurtleFiles.freeSpace(context))), style = MaterialTheme.typography.bodySmall)
    }
}

// Graphics ---------------------------------------------------------------------------------------

/** The game's resolution, the same setting as the 3.3.5a game's (the phone's screen, or fewer lines for speed). */
@Composable
private fun GraphicsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(GameSettings.resolution(context)) }
    TurtleDialog(title = stringResource(R.string.wow_graphics_title), onDismiss = onDismiss, buttons = {
        TurtleButton(stringResource(R.string.wow_close), primary = false, onClick = onDismiss)
    }) {
        Text(stringResource(R.string.wow_graphics_body), style = MaterialTheme.typography.bodySmall)
        for (resolution in GameSettings.Resolution.entries) {
            val (width, height) = GameSettings.gameSize(context, resolution)
            TurtleChoiceRow(
                title = stringResource(resolution.label),
                summary = stringResource(R.string.wow_graphics_size, stringResource(resolution.summary), width, height),
                selected = resolution == current,
            ) {
                GameSettings.setResolution(context, resolution)
                current = resolution
            }
        }
    }
}

// App icon ---------------------------------------------------------------------------------------

/** The app's icon on the home screen, the same choice as in the 3.3.5a launcher. */
@Composable
private fun IconDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppIcons.current(context)) }
    TurtleDialog(title = stringResource(R.string.wow_icon_title), onDismiss = onDismiss, buttons = {
        TurtleButton(stringResource(R.string.wow_close), primary = false, onClick = onDismiss)
    }) {
        Text(stringResource(R.string.wow_icon_body), style = MaterialTheme.typography.bodySmall)
        for (icon in AppIcons.Icon.entries) {
            TurtleChoiceRow(
                title = stringResource(icon.label),
                summary = null,
                selected = icon == current,
                leading = {
                    Box(Modifier.size(56.dp).clip(CircleShape)) {
                        Image(painterResource(icon.background), null, Modifier.fillMaxSize())
                        Image(painterResource(icon.foreground), null, Modifier.fillMaxSize())
                    }
                },
            ) {
                if (icon != current) {
                    AppIcons.set(context, icon)
                    current = icon
                    Toast.makeText(context, R.string.wow_icon_changed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/** Turtle's own accounts (tw_logon): its database is started for this if the realm isn't running. */
@Composable
private fun AccountsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<TurtleAccounts.Account>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf(TurtleLogin.get(context)?.name) }

    fun act(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { TurtleServer.withDatabase(context) { block() } }.exceptionOrNull()
            }
            busy = false
            error = failure?.let { accountError(context, it) }
            refresh++
        }
    }
    LaunchedEffect(refresh) {
        saved = TurtleLogin.get(context)?.name
        if (!TurtlePackage.isInstalled(context) || !TurtleFiles.hasDatabases(context)) {
            error = context.getString(R.string.turtle_error_no_databases)
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) { runCatching { TurtleServer.withDatabase(context) { TurtleAccounts.list(context) } } }
        accounts = result.getOrNull()
        result.exceptionOrNull()?.let { error = accountError(context, it) }
    }
    TurtleDialog(title = stringResource(R.string.turtle_tile_accounts), onDismiss = onDismiss, buttons = { TurtleButton(stringResource(R.string.turtle_close), onClick = onDismiss) }) {
        Text(stringResource(R.string.turtle_accounts_body), style = MaterialTheme.typography.bodySmall)
        if (accounts == null && error == null) Row(verticalAlignment = Alignment.CenterVertically) { TurtleSpinner(28.dp) }
        for (account in accounts.orEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TurtleStatusLine(account.name, rankName(context, account.rank), good = if (account.name.equals(saved, ignoreCase = true)) true else null)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val nextRank = TurtleAccountRules.RANKS.let { ranks -> ranks[(ranks.indexOf(account.rank).coerceAtLeast(0) + 1) % ranks.size] }
                    TurtleButton(rankName(context, nextRank), primary = false, enabled = !busy) { act { TurtleAccounts.setRank(context, account.name, nextRank) } }
                    if (account.name.equals(saved, ignoreCase = true)) {
                        TurtleButton(stringResource(R.string.turtle_auto_login_off), primary = false, enabled = !busy) { TurtleLogin.forget(context); saved = null }
                    }
                    TurtleButton(stringResource(R.string.turtle_delete), primary = false, enabled = !busy) { act { TurtleAccounts.delete(context, account.name) } }
                }
            }
        }
        TurtleTitle(stringResource(R.string.turtle_accounts_new), style = MaterialTheme.typography.titleLarge)
        TurtleTextField(name, { name = it.take(16) }, stringResource(R.string.turtle_account_name))
        TurtleTextField(password, { password = it.take(16) }, stringResource(R.string.turtle_account_password), password = true)
        Text(stringResource(R.string.turtle_account_rules), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TurtleButton(stringResource(R.string.turtle_password_change), primary = false, enabled = !busy && name.isNotEmpty() && password.isNotEmpty()) {
                val user = name
                val pass = password
                act { TurtleAccounts.setPassword(context, user, pass) }
            }
            TurtleButton(stringResource(R.string.turtle_account_make), enabled = !busy && name.isNotEmpty() && password.isNotEmpty()) {
                val user = name
                val pass = password
                val first = accounts.isNullOrEmpty()
                act {
                    TurtleAccounts.create(context, user, pass, if (first) TurtleAccountRules.RANK_ADMINISTRATOR else TurtleAccountRules.RANK_PLAYER)
                    // The first account logs in by itself, as in the ChromieCraft realm.
                    if (first || TurtleLogin.get(context) == null) TurtleLogin.save(context, user, pass)
                }
                name = ""
                password = ""
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TurtleColors.Blood) }
    }
}

private fun rankName(context: android.content.Context, rank: Int) = context.getString(
    when (rank) {
        TurtleAccountRules.RANK_PLAYER -> R.string.turtle_rank_player
        TurtleAccountRules.RANK_MODERATOR -> R.string.turtle_rank_moderator
        TurtleAccountRules.RANK_DEVELOPER -> R.string.turtle_rank_developer
        else -> R.string.turtle_rank_admin
    }
)

private fun accountError(context: android.content.Context, error: Throwable): String = when (error) {
    is TurtleAccounts.InvalidException -> context.getString(
        when (error.reason) {
            TurtleAccounts.InvalidException.Reason.NAME -> R.string.turtle_account_bad_name
            TurtleAccounts.InvalidException.Reason.PASSWORD -> R.string.turtle_account_bad_password
            TurtleAccounts.InvalidException.Reason.TAKEN -> R.string.turtle_account_taken
        }
    )
    else -> context.getString(R.string.turtle_job_error, error.message ?: error.javaClass.simpleName)
}

/** Rates and world settings Tortoise offers, written into Turtle's mangosd.conf at the next start. */
@Composable
private fun OptionsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    TurtleDialog(title = stringResource(R.string.turtle_tile_options), onDismiss = onDismiss, buttons = {
        TurtleButton(stringResource(R.string.turtle_defaults), primary = false) {
            TurtleSettings.reset(context)
            version++
        }
        TurtleButton(stringResource(R.string.turtle_close), onClick = onDismiss)
    }) {
        Text(stringResource(R.string.turtle_settings_body), style = MaterialTheme.typography.bodySmall)
        for (group in TurtleRealm.Group.entries) {
            val settings = TurtleRealm.SETTINGS.filter { it.group == group }
            if (settings.isEmpty()) continue
            TurtleTitle(stringResource(if (group == TurtleRealm.Group.RATES) R.string.turtle_settings_rates else R.string.turtle_settings_world), style = MaterialTheme.typography.titleLarge)
            for (setting in settings) {
                val current = remember(version) { mutableStateOf(TurtleSettings.value(context, setting)) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(settingLabel(context, setting.key), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (choice in setting.choices) {
                            val label = if (setting.key.startsWith("Rate.")) "×$choice" else choice
                            TurtleButton(label, primary = current.value == choice) {
                                TurtleSettings.setValue(context, setting, choice)
                                current.value = choice
                            }
                        }
                    }
                }
            }
        }
        var mmaps by remember(version) { mutableStateOf(TurtleSettings.mmaps(context)) }
        TurtleSwitchRow(stringResource(R.string.turtle_install_mmaps), stringResource(R.string.turtle_install_mmaps_summary), mmaps) {
            mmaps = it
            TurtleSettings.setMmaps(context, it)
        }
        TurtleTitle(stringResource(R.string.turtle_settings_bots), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.turtle_settings_bots_body), style = MaterialTheme.typography.bodySmall)
        var worldBots by remember(version) { mutableIntStateOf(TurtleSettings.worldBots(context)) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.turtle_world_bots), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (count in TurtleRealm.WORLD_BOTS_CHOICES) {
                    TurtleButton(if (count == 0) stringResource(R.string.turtle_world_bots_none) else "$count", primary = worldBots == count) {
                        TurtleSettings.setWorldBots(context, count)
                        worldBots = count
                    }
                }
            }
        }
    }
}

private fun settingLabel(context: android.content.Context, key: String): String = context.getString(
    when (key) {
        "Rate.XP.Kill" -> R.string.turtle_rate_xp_kill
        "Rate.XP.Quest" -> R.string.turtle_rate_xp_quest
        "Rate.XP.Explore" -> R.string.turtle_rate_xp_explore
        "Rate.Drop.Money" -> R.string.turtle_rate_money
        "Rate.Reputation.Gain" -> R.string.turtle_rate_reputation
        "Rate.Rest.InGame" -> R.string.turtle_rate_rest
        "Rate.Rest.Offline.InTavernOrCity" -> R.string.turtle_rate_rest_inn
        "Rate.Rest.Offline.InWilderness" -> R.string.turtle_rate_rest_wild
        "StartPlayerLevel" -> R.string.turtle_start_level
        else -> R.string.turtle_setting_other
    }
)

/** Repair: each part checked or put back by itself (from the Server screen's Start over). */
@Composable
private fun RepairDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf(setOf(TurtleJobs.Repair.PROGRAMS)) }
    var resetConfirm by remember { mutableStateOf(false) }
    TurtleDialog(title = stringResource(R.string.turtle_tile_repair), onDismiss = onDismiss, buttons = {
        TurtleButton(stringResource(R.string.turtle_cancel), primary = false, onClick = onDismiss)
        TurtleButton(stringResource(R.string.turtle_repair), enabled = picked.isNotEmpty() && (TurtleJobs.Repair.DATABASES !in picked || resetConfirm)) {
            TurtleService.job(context, TurtleJobs.Kind.REPAIR, options = TurtleJobs.Options(withMmaps = TurtleSettings.mmaps(context), repairs = picked))
            onDismiss()
        }
    }) {
        Text(stringResource(R.string.turtle_repair_body), style = MaterialTheme.typography.bodySmall)
        for (repair in TurtleJobs.Repair.entries) {
            TurtleSwitchRow(repairTitle(context, repair), repairSummary(context, repair), repair in picked) { on ->
                picked = if (on) picked + repair else picked - repair
            }
        }
        if (TurtleJobs.Repair.DATABASES in picked) {
            TurtleSwitchRow(stringResource(R.string.turtle_repair_databases_confirm), null, resetConfirm) { resetConfirm = it }
        }
    }
}

private fun repairTitle(context: android.content.Context, repair: TurtleJobs.Repair) = context.getString(
    when (repair) {
        TurtleJobs.Repair.PROGRAMS -> R.string.turtle_repair_programs
        TurtleJobs.Repair.CONFIGS -> R.string.turtle_repair_configs
        TurtleJobs.Repair.CLIENT -> R.string.turtle_repair_client
        TurtleJobs.Repair.DATA -> R.string.turtle_repair_data
        TurtleJobs.Repair.DATABASES -> R.string.turtle_repair_databases
    }
)

private fun repairSummary(context: android.content.Context, repair: TurtleJobs.Repair) = context.getString(
    when (repair) {
        TurtleJobs.Repair.PROGRAMS -> R.string.turtle_repair_programs_summary
        TurtleJobs.Repair.CONFIGS -> R.string.turtle_repair_configs_summary
        TurtleJobs.Repair.CLIENT -> R.string.turtle_repair_client_summary
        TurtleJobs.Repair.DATA -> R.string.turtle_repair_data_summary
        TurtleJobs.Repair.DATABASES -> R.string.turtle_repair_databases_summary
    }
)

/** Uninstall: all of Turtle WoW, nothing else; confirmed by typing TURTLE. */
@Composable
private fun UninstallDialog(facts: TurtleFacts, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }
    TurtleDialog(title = stringResource(R.string.turtle_tile_uninstall), onDismiss = onDismiss, buttons = {
        TurtleButton(stringResource(R.string.turtle_cancel), primary = false, onClick = onDismiss)
        TurtleButton(stringResource(R.string.turtle_uninstall), enabled = typed.trim().equals("TURTLE", ignoreCase = true)) {
            TurtleService.job(context, TurtleJobs.Kind.UNINSTALL)
            Toast.makeText(context, R.string.turtle_uninstalling, Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }) {
        Text(stringResource(R.string.turtle_uninstall_body, Formatter.formatShortFileSize(context, facts.used)), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.turtle_uninstall_keeps), style = MaterialTheme.typography.bodySmall)
        TurtleTextField(typed, { typed = it }, stringResource(R.string.turtle_uninstall_type))
    }
}
