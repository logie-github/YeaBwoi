// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.ui

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
import androidx.compose.foundation.layout.padding
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
import com.logie.warcraft.Accounts
import com.logie.warcraft.AppIcons
import com.logie.warcraft.BuildConfig
import com.logie.warcraft.Database
import com.logie.warcraft.ImportService
import com.logie.warcraft.Imports
import com.logie.warcraft.R
import com.logie.warcraft.Server
import com.logie.warcraft.ServerPackage
import com.logie.warcraft.ServerService
import com.logie.warcraft.WowFiles
import com.logie.warcraft.client.DataLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// The server -------------------------------------------------------------------------------------

/** The server: how it is, start and stop, its logs, connecting from a PC, starting over. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Server.status.collectAsState()
    var log by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val power = context.getSystemService(PowerManager::class.java)
    val unrestricted = remember(tick) { power.isIgnoringBatteryOptimizations(context.packageName) }
    val stopped = status.phase == Server.Phase.STOPPED || status.phase == Server.Phase.FAILED

    WowDialog(
        title = stringResource(R.string.wow_server_title),
        onDismiss = onDismiss,
        buttons = {
            if (stopped) {
                WowButton(stringResource(R.string.wow_server_start)) { Server.start(context) }
            } else if (status.phase != Server.Phase.STOPPING) {
                WowButton(stringResource(R.string.wow_server_stop), primary = false) { Server.stop(context) }
            }
            WowButton(stringResource(R.string.wow_close), primary = false, onClick = onDismiss)
        },
    ) {
        WowStatusLine(stringResource(R.string.wow_server_state), ServerService.statusText(context, status),
            good = when (status.phase) {
                Server.Phase.RUNNING -> status.world == Server.World.UP
                Server.Phase.FAILED -> false
                else -> null
            })
        status.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
        status.progress?.let { WowProgressLine(it) }
        status.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = WowColors.Blood) }
        status.log?.let { WowLog(it) }

        WowTitle(stringResource(R.string.wow_server_pc_title), style = MaterialTheme.typography.titleLarge)
        val lan = status.lanAddress ?: Server.lanAddress()
        Text(
            if (lan != null) stringResource(R.string.wow_server_pc_body, lan) else stringResource(R.string.wow_server_pc_no_wifi),
            style = MaterialTheme.typography.bodyMedium,
        )

        WowTitle(stringResource(R.string.wow_server_logs), style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, file) in listOf(
                R.string.wow_log_world to "world.out",
                R.string.wow_log_auth to "auth.out",
                R.string.wow_log_database to "mariadb.log",
                R.string.wow_log_import to "dbimport.log",
            )) {
                WowButton(stringResource(label), primary = false) {
                    scope.launch {
                        log = withContext(Dispatchers.IO) { WowFiles.tail(File(WowFiles.logs(context), file), 60) }
                            .ifBlank { context.getString(R.string.wow_log_empty) }
                    }
                }
            }
        }
        log?.let { WowLog(it) }

        if (!unrestricted) {
            WowTitle(stringResource(R.string.wow_server_battery_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.wow_server_battery_body), style = MaterialTheme.typography.bodySmall)
            WowButton(stringResource(R.string.wow_server_battery_action), primary = false) {
                @SuppressLint("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }
                scope.launch {
                    delay(4000)
                    tick++
                }
            }
        }

        WowTitle(stringResource(R.string.wow_server_reset_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.wow_server_reset_body), style = MaterialTheme.typography.bodySmall)
        WowButton(
            stringResource(if (confirmReset) R.string.wow_server_reset_confirm else R.string.wow_server_reset),
            primary = false,
            enabled = stopped,
        ) {
            if (!confirmReset) {
                confirmReset = true
            } else {
                confirmReset = false
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { Database.deleteAll(context) } }
                    Toast.makeText(
                        context,
                        if (result.isSuccess) context.getString(R.string.wow_server_reset_done) else result.exceptionOrNull()?.message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        ServerPackage.version(context)?.let {
            Text(stringResource(R.string.wow_server_version, it.trim()), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// Game files -------------------------------------------------------------------------------------

/** The client and the server's data: import them, make the data, see how a job went. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameFilesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by Imports.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val hasClient = remember(refresh, state) { WowFiles.hasClient(context) }
    val folders = remember(refresh, state) { WowFiles.dataFolders(context) }
    val downloaded = remember(refresh, state) { WowFiles.downloadedData(context) }
    val partial = remember(refresh, state) { Imports.downloadedSoFar(context) }
    var clientSize by remember { mutableStateOf<Long?>(null) }
    var withMmaps by remember { mutableStateOf(true) }
    val working = state is Imports.State.Working

    LaunchedEffect(hasClient) {
        clientSize = if (hasClient) withContext(Dispatchers.IO) { WowFiles.sizeOf(WowFiles.client(context)) } else null
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ImportService.importClient(context, uri, isFolder = true)
    }
    val pickClientZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ImportService.importClient(context, uri, isFolder = false)
    }
    val pickDataZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ImportService.importServerData(context, uri)
    }

    WowDialog(
        title = stringResource(R.string.wow_files_title),
        onDismiss = onDismiss,
        buttons = {
            if (working) WowButton(stringResource(R.string.wow_import_stop), primary = false) { ImportService.cancel(context) }
            WowButton(stringResource(R.string.wow_close), primary = false, onClick = onDismiss)
        },
    ) {
        when (val s = state) {
            is Imports.State.Working -> WowWindow(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WowSpinner(32.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(importTitle(s), style = MaterialTheme.typography.titleMedium)
                    }
                    s.progress?.let { WowProgressLine(it) }
                    s.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                    Text(stringResource(R.string.wow_import_background_note), style = MaterialTheme.typography.bodySmall)
                }
            }
            is Imports.State.Done -> Text(
                stringResource(
                    when (s.kind) {
                        Imports.Kind.CLIENT -> R.string.wow_import_client_done
                        Imports.Kind.DOWNLOAD -> R.string.wow_download_done
                        Imports.Kind.EXTRACT -> R.string.wow_extract_done
                        Imports.Kind.SERVER_DATA -> R.string.wow_data_import_done
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = WowColors.Good,
            )
            is Imports.State.Failed -> {
                Text(s.message, style = MaterialTheme.typography.bodyMedium, color = WowColors.Blood)
                s.log?.let { WowLog(it) }
            }
            Imports.State.Idle -> {}
        }

        WowTitle(stringResource(R.string.wow_files_client), style = MaterialTheme.typography.titleLarge)
        WowStatusLine(
            stringResource(R.string.wow_files_client_state),
            if (hasClient) stringResource(R.string.wow_files_client_yes, clientSize?.let { Formatter.formatShortFileSize(context, it) } ?: "…")
            else stringResource(R.string.wow_files_client_no),
            good = hasClient,
        )
        Text(stringResource(R.string.wow_files_client_body), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WowButton(stringResource(R.string.wow_files_client_folder), primary = !hasClient, enabled = !working) { pickFolder.launch(null) }
            WowButton(stringResource(R.string.wow_files_client_zip), primary = false, enabled = !working) {
                pickClientZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
        }

        WowTitle(stringResource(R.string.wow_files_data), style = MaterialTheme.typography.titleLarge)
        for (folder in DataLayout.FOLDERS) {
            WowStatusLine(folder, stringResource(if (folder in folders) R.string.wow_files_data_yes else R.string.wow_files_data_no), good = folder in folders)
        }
        Text(stringResource(R.string.wow_files_data_body), style = MaterialTheme.typography.bodySmall)

        // AzerothCore's own data, the version this server is built for (tested with it in CI).
        val current = downloaded == BuildConfig.WOW_DATA_VERSION
        WowTitle(stringResource(R.string.wow_files_data_download), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(
                R.string.wow_files_data_download_body,
                Formatter.formatShortFileSize(context, BuildConfig.WOW_DATA_SIZE),
                Formatter.formatShortFileSize(context, (BuildConfig.WOW_DATA_SIZE * 2.7).toLong()),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (downloaded != null) {
            WowStatusLine(
                stringResource(R.string.wow_files_data_downloaded),
                if (current) downloaded else stringResource(R.string.wow_files_data_downloaded_old, downloaded, BuildConfig.WOW_DATA_VERSION),
                good = current,
            )
        }
        WowButton(
            when {
                partial > 0 -> stringResource(
                    R.string.wow_files_data_download_resume,
                    Formatter.formatShortFileSize(context, partial),
                    Formatter.formatShortFileSize(context, BuildConfig.WOW_DATA_SIZE),
                )
                downloaded != null && !current -> stringResource(R.string.wow_files_data_download_update)
                else -> stringResource(R.string.wow_files_data_download_button)
            },
            primary = folders.isEmpty() || partial > 0 || (downloaded != null && !current),
            enabled = !working,
        ) {
            ImportService.downloadServerData(context)
        }

        WowTitle(stringResource(R.string.wow_files_data_phone), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.wow_files_data_phone_body), style = MaterialTheme.typography.bodySmall)
        WowSwitchRow(stringResource(R.string.wow_files_mmaps), stringResource(R.string.wow_files_mmaps_summary), withMmaps, enabled = !working) { withMmaps = it }
        WowButton(stringResource(R.string.wow_files_extract), primary = false, enabled = hasClient && !working) {
            ImportService.extract(context, withMmaps)
        }

        WowTitle(stringResource(R.string.wow_files_data_pc), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.wow_files_data_pc_body), style = MaterialTheme.typography.bodySmall)
        WowButton(stringResource(R.string.wow_files_data_import), primary = false, enabled = !working) {
            pickDataZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        Text(stringResource(R.string.wow_files_space, Formatter.formatShortFileSize(context, WowFiles.freeSpace(context))), style = MaterialTheme.typography.bodySmall)
    }
}

// Accounts ---------------------------------------------------------------------------------------

/** The game accounts: create, change password, game master or not, delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Server.status.collectAsState()
    var accounts by remember { mutableStateOf<List<Accounts.Account>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var gm by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<Accounts.Account?>(null) }
    var newPassword by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val dbUp = Server.databaseUp && (status.phase == Server.Phase.RUNNING || (status.phase == Server.Phase.STARTING && (status.step ?: Server.Step.DATABASE) > Server.Step.IMPORT))

    fun run(action: () -> Unit, done: () -> Unit = {}) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(action) }
            result.fold(
                onSuccess = { error = null; done(); reload++ },
                onFailure = { error = accountError(context, it) },
            )
        }
    }

    LaunchedEffect(reload, dbUp) {
        if (!dbUp) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { runCatching { Accounts.list(context) } }
        result.fold(onSuccess = { accounts = it }, onFailure = { error = accountError(context, it) })
        // The first account is for the user: a game master, so the GM commands work.
        if (accounts?.isEmpty() == true) gm = true
    }

    WowDialog(
        title = stringResource(R.string.wow_accounts_title),
        onDismiss = onDismiss,
        buttons = { WowButton(stringResource(R.string.wow_close), primary = false, onClick = onDismiss) },
    ) {
        if (!dbUp) {
            Text(stringResource(R.string.wow_accounts_need_server), style = MaterialTheme.typography.bodyMedium)
            if (status.phase == Server.Phase.STOPPED || status.phase == Server.Phase.FAILED) {
                WowButton(stringResource(R.string.wow_server_start)) { Server.start(context) }
            } else {
                WowSpinner(32.dp)
            }
            return@WowDialog
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = WowColors.Blood) }

        val list = accounts
        if (list == null) {
            WowSpinner(32.dp)
        } else if (list.isEmpty()) {
            Text(stringResource(R.string.wow_accounts_none), style = MaterialTheme.typography.bodyMedium)
        } else {
            for (account in list) {
                val selected = editing?.id == account.id
                WowChoiceRow(
                    title = account.name,
                    summary = stringResource(if (account.gmLevel >= Accounts.GM_ADMIN) R.string.wow_accounts_gm else R.string.wow_accounts_player),
                    selected = selected,
                ) {
                    editing = if (selected) null else account
                    newPassword = ""
                    confirmDelete = false
                }
                if (selected) {
                    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WowTextField(newPassword, { newPassword = it }, stringResource(R.string.wow_accounts_new_password), password = true)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            WowButton(stringResource(R.string.wow_accounts_set_password), enabled = newPassword.isNotEmpty()) {
                                run({ Accounts.setPassword(context, account.name, newPassword) }) {
                                    newPassword = ""
                                    Toast.makeText(context, R.string.wow_accounts_password_changed, Toast.LENGTH_SHORT).show()
                                }
                            }
                            val isGm = account.gmLevel >= Accounts.GM_ADMIN
                            WowButton(stringResource(if (isGm) R.string.wow_accounts_make_player else R.string.wow_accounts_make_gm), primary = false) {
                                run({ Accounts.setGmLevel(context, account.name, if (isGm) Accounts.GM_PLAYER else Accounts.GM_ADMIN) }) { editing = null }
                            }
                            WowButton(stringResource(if (confirmDelete) R.string.wow_accounts_delete_confirm else R.string.wow_accounts_delete), primary = false) {
                                if (!confirmDelete) confirmDelete = true
                                else run({ Accounts.delete(context, account.name) }) { editing = null; confirmDelete = false }
                            }
                        }
                    }
                }
            }
        }

        WowTitle(stringResource(R.string.wow_accounts_new), style = MaterialTheme.typography.titleLarge)
        WowTextField(name, { name = it.take(16) }, stringResource(R.string.wow_accounts_name))
        WowTextField(password, { password = it.take(16) }, stringResource(R.string.wow_accounts_password), password = true)
        WowSwitchRow(stringResource(R.string.wow_accounts_gm_switch), stringResource(R.string.wow_accounts_gm_switch_summary), gm) { gm = it }
        Text(stringResource(R.string.wow_accounts_rules), style = MaterialTheme.typography.bodySmall)
        WowButton(stringResource(R.string.wow_accounts_create), enabled = name.isNotBlank() && password.isNotEmpty()) {
            run({ Accounts.create(context, name, password, if (gm) Accounts.GM_ADMIN else Accounts.GM_PLAYER) }) {
                Toast.makeText(context, context.getString(R.string.wow_accounts_created, name.uppercase()), Toast.LENGTH_SHORT).show()
                name = ""
                password = ""
            }
        }
    }
}

private fun accountError(context: android.content.Context, error: Throwable): String = when (error) {
    is Accounts.InvalidException -> context.getString(
        when (error.reason) {
            Accounts.InvalidException.Reason.NAME -> R.string.wow_accounts_error_name
            Accounts.InvalidException.Reason.PASSWORD -> R.string.wow_accounts_error_password
            Accounts.InvalidException.Reason.TAKEN -> R.string.wow_accounts_error_taken
        }
    )
    is Database.NotRunningException -> context.getString(R.string.wow_accounts_need_server)
    else -> context.getString(R.string.wow_accounts_error, error.message ?: error.javaClass.simpleName)
}

// App icon ---------------------------------------------------------------------------------------

/** The launcher icon: Wrath of the Lich King's frost, or Cataclysm's fire. */
@Composable
fun AppIconDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppIcons.current(context)) }
    WowDialog(title = stringResource(R.string.wow_icon_title), onDismiss = onDismiss) {
        Text(stringResource(R.string.wow_icon_body), style = MaterialTheme.typography.bodySmall)
        for (icon in AppIcons.Icon.entries) {
            WowChoiceRow(
                title = stringResource(icon.label),
                summary = null,
                selected = icon == current,
                leading = {
                    Box(Modifier.size(56.dp).clip(CircleShape)) {
                        Image(painterResource(icon.background), null, Modifier.fillMaxSize())
                        // The adaptive icon's layers are 108dp with the picture in the middle 72.
                        Image(painterResource(icon.foreground), null, Modifier.fillMaxSize().padding(0.dp))
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
