// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalLayoutApi::class)

package com.logie.warcraft.felsong.ui

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
import com.logie.warcraft.AppIcons
import com.logie.warcraft.R
import com.logie.warcraft.Server
import com.logie.warcraft.WowFiles
import com.logie.warcraft.felsong.FelsongAccounts
import com.logie.warcraft.felsong.FelsongFiles
import com.logie.warcraft.felsong.FelsongJobs
import com.logie.warcraft.felsong.FelsongLogin
import com.logie.warcraft.felsong.FelsongPackage
import com.logie.warcraft.felsong.FelsongServer
import com.logie.warcraft.felsong.FelsongService
import com.logie.warcraft.felsong.FelsongSettings
import com.logie.warcraft.felsong.FelsongTexts
import com.logie.warcraft.felsong.core.FelsongAccountRules
import com.logie.warcraft.felsong.core.FelsongInstallPlan
import com.logie.warcraft.felsong.core.FelsongRealm
import com.logie.warcraft.game.GameSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FelsongDialogs(dialog: FelsongDialogKind?, facts: FelsongFacts, onDialog: (FelsongDialogKind) -> Unit, onDismiss: () -> Unit) {
    when (dialog) {
        FelsongDialogKind.STEPS -> StepsDialog(facts, onDialog, onDismiss)
        FelsongDialogKind.SERVER -> ServerDialog(facts, onDialog, onDismiss)
        FelsongDialogKind.FILES -> FilesDialog(onDismiss)
        FelsongDialogKind.ACCOUNTS -> AccountsDialog(onDismiss)
        FelsongDialogKind.GRAPHICS -> GraphicsDialog(onDismiss)
        FelsongDialogKind.OPTIONS -> OptionsDialog(onDismiss)
        FelsongDialogKind.ICON -> IconDialog(onDismiss)
        FelsongDialogKind.REPAIR -> RepairDialog(onDismiss)
        FelsongDialogKind.UNINSTALL -> UninstallDialog(facts, onDismiss)
        null -> {}
    }
}

/** A job of this screen's: under way, done or failed (the other launchers' Game files screen's top). */
@Composable
private fun JobState(job: FelsongJobs.State, screen: FelsongDialogKind) {
    val context = LocalContext.current
    when (job) {
        is FelsongJobs.State.Working -> if (dialogFor(job.kind) == screen) FelsongWindow(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FelsongSpinner(32.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(FelsongTexts.step(context, job.step), style = MaterialTheme.typography.titleMedium)
                }
                job.progress?.let { FelsongProgressLine(it) }
                job.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                Text(stringResource(R.string.wow_import_background_note), style = MaterialTheme.typography.bodySmall)
            }
        }
        is FelsongJobs.State.Done -> if (dialogFor(job.kind) == screen) Text(job.message, style = MaterialTheme.typography.bodyMedium, color = FelsongColors.Lime)
        is FelsongJobs.State.Failed -> if (dialogFor(job.kind) == screen) {
            Text(job.message, style = MaterialTheme.typography.bodyMedium, color = FelsongColors.Blood)
            job.log?.takeIf { it.isNotBlank() }?.let { FelsongLog(it) }
        }
        FelsongJobs.State.Idle -> {}
    }
}

// The steps ----------------------------------------------------------------------------------------

/** Every file the game needs, numbered and in order, each with its state and the button that does it. */
@Composable
private fun StepsDialog(facts: FelsongFacts, onDialog: (FelsongDialogKind) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val job by FelsongJobs.state.collectAsState()
    val working = job is FelsongJobs.State.Working
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
    FelsongDialog(title = stringResource(R.string.felsong_steps_title), onDismiss = onDismiss, buttons = {
        FelsongButton(stringResource(R.string.felsong_close), onClick = onDismiss)
    }) {
        Text(stringResource(R.string.felsong_steps_body), style = MaterialTheme.typography.bodySmall)
        for (line in facts.plan) {
            FelsongWindow(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FelsongDot(
                            when (line.status) {
                                FelsongInstallPlan.Status.DONE -> FelsongColors.Lime
                                FelsongInstallPlan.Status.TODO, FelsongInstallPlan.Status.NEEDS_USER -> FelsongColors.Ember
                                FelsongInstallPlan.Status.BLOCKED -> FelsongColors.Blood
                                FelsongInstallPlan.Status.WAITING -> FelsongColors.Sage
                            },
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(stepTitle(line.step), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            stringResource(
                                when (line.status) {
                                    FelsongInstallPlan.Status.DONE -> R.string.felsong_step_status_done
                                    FelsongInstallPlan.Status.TODO -> R.string.felsong_step_status_todo
                                    FelsongInstallPlan.Status.NEEDS_USER -> R.string.felsong_step_status_needs_user
                                    FelsongInstallPlan.Status.BLOCKED -> R.string.felsong_step_status_blocked
                                    FelsongInstallPlan.Status.WAITING -> R.string.felsong_step_status_waiting
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (line.status == FelsongInstallPlan.Status.DONE) FelsongColors.Lime else FelsongColors.Sage,
                        )
                    }
                    Text(stepBody(line.step), style = MaterialTheme.typography.bodySmall)
                    // The step's own size and button.
                    when (line.step) {
                        FelsongInstallPlan.Step.SERVER -> {
                            facts.pin?.let { Text(stringResource(R.string.felsong_install_space, size(it.size), size(it.unpacked), size(facts.free)), style = MaterialTheme.typography.bodySmall) }
                            if (line.status != FelsongInstallPlan.Status.DONE) {
                                FelsongButton(stringResource(if (facts.downloadedSoFar > 0) R.string.felsong_resume else R.string.felsong_download_install), enabled = !working && facts.pin != null) {
                                    FelsongService.job(context, FelsongJobs.Kind.PROGRAMS)
                                }
                            }
                        }
                        FelsongInstallPlan.Step.DATABASE -> if (line.status == FelsongInstallPlan.Status.TODO) {
                            FelsongButton(stringResource(R.string.felsong_create), enabled = !working) { FelsongService.job(context, FelsongJobs.Kind.DATABASE) }
                        }
                        FelsongInstallPlan.Step.CLIENT, FelsongInstallPlan.Step.DATA -> if (line.status != FelsongInstallPlan.Status.DONE) {
                            if (facts.spp) FelsongButton(stringResource(R.string.felsong_spp_take), enabled = !working) { FelsongService.job(context, FelsongJobs.Kind.SPP) }
                            FelsongButton(stringResource(R.string.wow_tile_files), primary = line.status != FelsongInstallPlan.Status.WAITING) { onDialog(FelsongDialogKind.FILES) }
                        }
                        FelsongInstallPlan.Step.PATCH -> if (line.status == FelsongInstallPlan.Status.TODO) {
                            FelsongButton(stringResource(R.string.felsong_patch_now), enabled = !working) { FelsongService.job(context, FelsongJobs.Kind.PATCH) }
                        }
                        FelsongInstallPlan.Step.ACCOUNT -> if (line.status == FelsongInstallPlan.Status.NEEDS_USER) {
                            FelsongButton(stringResource(R.string.wow_tile_accounts)) { onDialog(FelsongDialogKind.ACCOUNTS) }
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun stepTitle(step: FelsongInstallPlan.Step): String = stringResource(
    when (step) {
        FelsongInstallPlan.Step.CHECK -> R.string.felsong_step_title_check
        FelsongInstallPlan.Step.SERVER -> R.string.felsong_step_title_server
        FelsongInstallPlan.Step.DATABASE -> R.string.felsong_step_title_database
        FelsongInstallPlan.Step.CLIENT -> R.string.felsong_step_title_client
        FelsongInstallPlan.Step.DATA -> R.string.felsong_step_title_data
        FelsongInstallPlan.Step.PATCH -> R.string.felsong_step_title_patch
        FelsongInstallPlan.Step.ACCOUNT -> R.string.felsong_step_title_account
        FelsongInstallPlan.Step.READY -> R.string.felsong_step_title_ready
    }
)

@Composable
private fun stepBody(step: FelsongInstallPlan.Step): String = stringResource(
    when (step) {
        FelsongInstallPlan.Step.CHECK -> R.string.felsong_step_body_check
        FelsongInstallPlan.Step.SERVER -> R.string.felsong_step_body_server
        FelsongInstallPlan.Step.DATABASE -> R.string.felsong_step_body_database
        FelsongInstallPlan.Step.CLIENT -> R.string.felsong_step_body_client
        FelsongInstallPlan.Step.DATA -> R.string.felsong_step_body_data
        FelsongInstallPlan.Step.PATCH -> R.string.felsong_step_body_patch
        FelsongInstallPlan.Step.ACCOUNT -> R.string.felsong_step_body_account
        FelsongInstallPlan.Step.READY -> R.string.felsong_step_body_ready
    }
)

// The server -------------------------------------------------------------------------------------

@Composable
private fun ServerDialog(facts: FelsongFacts, onDialog: (FelsongDialogKind) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by FelsongServer.status.collectAsState()
    val job by FelsongJobs.state.collectAsState()
    var log by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val power = context.getSystemService(PowerManager::class.java)
    val unrestricted = remember(tick) { power.isIgnoringBatteryOptimizations(context.packageName) }
    val stopped = status.phase == FelsongServer.Phase.STOPPED || status.phase == FelsongServer.Phase.FAILED
    val working = job is FelsongJobs.State.Working
    val busy = working || !stopped
    val size = { bytes: Long -> Formatter.formatShortFileSize(context, bytes) }
    val backups = remember(job) { FelsongJobs.backups(context) }

    FelsongDialog(
        title = stringResource(R.string.wow_server_title),
        onDismiss = onDismiss,
        buttons = {
            if (working && dialogFor((job as FelsongJobs.State.Working).kind) == FelsongDialogKind.SERVER) {
                FelsongButton(stringResource(R.string.wow_import_stop), primary = false) { FelsongJobs.cancel() }
            }
            if (stopped) {
                FelsongButton(stringResource(R.string.wow_server_start), enabled = facts.programsInstalled && facts.databases && !working) { FelsongServer.start(context) }
            } else if (status.phase != FelsongServer.Phase.STOPPING) {
                FelsongButton(stringResource(R.string.wow_server_stop), primary = false) { FelsongServer.stop(context) }
            }
            FelsongButton(stringResource(R.string.wow_close), primary = false, onClick = onDismiss)
        },
    ) {
        JobState(job, FelsongDialogKind.SERVER)
        FelsongStatusLine(
            stringResource(R.string.wow_server_state), FelsongTexts.server(context, status),
            good = when (status.phase) {
                FelsongServer.Phase.RUNNING -> status.worldUp
                FelsongServer.Phase.FAILED -> false
                else -> null
            },
        )
        status.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
        status.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = FelsongColors.Blood) }
        status.log?.takeIf { status.phase == FelsongServer.Phase.FAILED && it.isNotBlank() }?.let { FelsongLog(it) }

        FelsongTitle(stringResource(R.string.felsong_server_parts), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.felsong_server_parts_body), style = MaterialTheme.typography.bodySmall)
        FelsongStatusLine(
            stringResource(R.string.felsong_server_programs),
            when {
                facts.programsCurrent -> stringResource(R.string.felsong_part_installed, facts.version ?: "")
                facts.programsInstalled -> stringResource(R.string.felsong_part_update)
                facts.downloadedSoFar > 0 -> stringResource(R.string.felsong_part_partial, size(facts.downloadedSoFar))
                else -> stringResource(R.string.felsong_part_missing)
            },
            good = facts.programsCurrent,
        )
        FelsongButton(
            stringResource(
                when {
                    facts.programsInstalled && !facts.programsCurrent -> R.string.felsong_update_realm
                    facts.programsCurrent -> R.string.felsong_reinstall
                    facts.downloadedSoFar > 0 -> R.string.felsong_resume
                    else -> R.string.felsong_download_install
                }
            ),
            primary = !facts.programsCurrent,
            enabled = !busy && facts.pin != null && facts.blocker == null,
        ) { FelsongService.job(context, FelsongJobs.Kind.PROGRAMS) }
        facts.pin?.let {
            Text(stringResource(R.string.felsong_install_space, size(it.size), size(it.unpacked), size(facts.free)), style = MaterialTheme.typography.bodySmall)
        } ?: Text(stringResource(R.string.felsong_error_not_pinned), style = MaterialTheme.typography.bodySmall, color = FelsongColors.Blood)
        FelsongStatusLine(
            stringResource(R.string.felsong_server_databases),
            stringResource(if (facts.databases) R.string.felsong_part_created else R.string.felsong_part_missing),
            good = facts.databases,
        )
        if (!facts.databases) {
            FelsongButton(stringResource(R.string.felsong_create), enabled = !busy && facts.programsInstalled) { FelsongService.job(context, FelsongJobs.Kind.DATABASE) }
        }
        FelsongStatusLine(
            stringResource(R.string.felsong_server_runtime),
            stringResource(
                when {
                    facts.runtimeReady -> R.string.felsong_part_ready
                    facts.runtimeThere -> R.string.felsong_part_runtime_old
                    else -> R.string.felsong_part_missing
                }
            ),
            good = facts.runtimeThere,
        )
        if (!facts.runtimeThere) {
            FelsongButton(stringResource(R.string.felsong_install_short), enabled = !working) { FelsongService.job(context, FelsongJobs.Kind.RUNTIME) }
        }

        FelsongTitle(stringResource(R.string.wow_server_pc_title), style = MaterialTheme.typography.titleLarge)
        var lan by remember { mutableStateOf(FelsongSettings.lan(context)) }
        FelsongSwitchRow(stringResource(R.string.felsong_lan), stringResource(R.string.felsong_lan_summary), lan) {
            lan = it
            FelsongSettings.setLan(context, it)
        }
        val address = status.lanAddress ?: Server.lanAddress()
        Text(
            if (address != null) stringResource(R.string.felsong_server_pc_body, address) else stringResource(R.string.wow_server_pc_no_wifi),
            style = MaterialTheme.typography.bodyMedium,
        )

        FelsongTitle(stringResource(R.string.wow_server_logs), style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, file) in listOf(
                R.string.wow_log_world to "worldserver.out",
                R.string.wow_log_auth to "bnetserver.out",
                R.string.wow_log_database to "mariadb.log",
                R.string.felsong_log_data to "maps.log",
                R.string.felsong_log_patch to "patch-client.log",
            )) {
                FelsongButton(stringResource(label), primary = false) {
                    scope.launch {
                        log = withContext(Dispatchers.IO) { WowFiles.tail(File(FelsongFiles.layout(context).logs, file), 60) }
                            .ifBlank { context.getString(R.string.wow_log_empty) }
                    }
                }
            }
        }
        log?.let { FelsongLog(it) }

        if (!unrestricted) {
            FelsongTitle(stringResource(R.string.wow_server_battery_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.wow_server_battery_body), style = MaterialTheme.typography.bodySmall)
            FelsongButton(stringResource(R.string.wow_server_battery_action), primary = false) {
                @SuppressLint("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(intent) }
                scope.launch {
                    delay(4000)
                    tick++
                }
            }
        }

        FelsongTitle(stringResource(R.string.felsong_backups), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.felsong_backups_body), style = MaterialTheme.typography.bodySmall)
        FelsongButton(stringResource(R.string.felsong_backup_now), primary = false, enabled = !working && facts.databases) { FelsongService.job(context, FelsongJobs.Kind.BACKUP) }
        for (file in backups) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(file.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                FelsongButton(stringResource(R.string.felsong_restore), primary = false, enabled = !busy) {
                    FelsongService.job(context, FelsongJobs.Kind.RESTORE, options = FelsongJobs.Options(restoreFile = file.name))
                }
            }
        }

        FelsongTitle(stringResource(R.string.wow_server_reset_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.felsong_server_reset_body), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FelsongButton(stringResource(R.string.felsong_repair_open), primary = false, enabled = !working) { onDialog(FelsongDialogKind.REPAIR) }
            FelsongButton(stringResource(R.string.felsong_uninstall_open), primary = false, enabled = !working) { onDialog(FelsongDialogKind.UNINSTALL) }
        }

        FelsongTitle(stringResource(R.string.felsong_server_about), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.felsong_about_body), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.felsong_about_client), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.felsong_about_isolation), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.felsong_game_box64_note), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.felsong_about_legal), style = MaterialTheme.typography.bodySmall)
        FelsongPackage.version(context)?.let {
            Text(stringResource(R.string.wow_server_version, it.trim()), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// Game files -------------------------------------------------------------------------------------

/** Your client, the server's data and the patched program (the other launchers' Game files screen). */
@Composable
private fun FilesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val job by FelsongJobs.state.collectAsState()
    val hasClient = remember(job) { FelsongFiles.hasClient(context) }
    val parts = remember(job) { FelsongFiles.dataParts(context) }
    val state = remember(job) { FelsongFiles.state(context) }
    val installed = remember(job) { FelsongPackage.isInstalled(context) }
    val patched = remember(job) { FelsongFiles.isPatched(context) }
    var clientSize by remember { mutableStateOf<Long?>(null) }
    var withMmaps by remember { mutableStateOf(FelsongSettings.mmaps(context)) }
    val working = job is FelsongJobs.State.Working

    LaunchedEffect(hasClient) {
        clientSize = if (hasClient) withContext(Dispatchers.IO) { WowFiles.sizeOf(FelsongFiles.layout(context).client) } else null
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) FelsongService.job(context, FelsongJobs.Kind.CLIENT, uri, folder = true, FelsongJobs.Options(withMmaps = withMmaps))
    }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) FelsongService.job(context, FelsongJobs.Kind.CLIENT, uri, folder = false, FelsongJobs.Options(withMmaps = withMmaps))
    }
    val pickData = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) FelsongService.job(context, FelsongJobs.Kind.IMPORT_DATA, uri, folder = false)
    }

    FelsongDialog(
        title = stringResource(R.string.wow_files_title),
        onDismiss = onDismiss,
        buttons = {
            if (working && dialogFor((job as FelsongJobs.State.Working).kind) == FelsongDialogKind.FILES) {
                FelsongButton(stringResource(R.string.wow_import_stop), primary = false) { FelsongJobs.cancel() }
            }
            FelsongButton(stringResource(R.string.wow_close), primary = false, onClick = onDismiss)
        },
    ) {
        JobState(job, FelsongDialogKind.FILES)

        FelsongTitle(stringResource(R.string.wow_files_client), style = MaterialTheme.typography.titleLarge)
        val sizeText = clientSize?.let { Formatter.formatShortFileSize(context, it) } ?: "…"
        FelsongStatusLine(
            stringResource(R.string.felsong_files_client_state),
            if (!hasClient) stringResource(R.string.wow_files_client_no) else stringResource(R.string.felsong_files_client_in, sizeText, state.clientBuild),
            good = if (hasClient) state.clientBuild == FelsongRealm.CLIENT_BUILD else false,
        )
        Text(stringResource(R.string.felsong_files_client_body), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.felsong_files_client_why), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FelsongButton(stringResource(R.string.wow_files_client_folder), primary = !hasClient, enabled = !working) { pickFolder.launch(null) }
            FelsongButton(stringResource(R.string.wow_files_client_zip), primary = false, enabled = !working) {
                pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
            if (hasClient) FelsongButton(stringResource(R.string.felsong_check_files), primary = false, enabled = !working) { FelsongService.job(context, FelsongJobs.Kind.VERIFY) }
        }

        FelsongTitle(stringResource(R.string.wow_files_data), style = MaterialTheme.typography.titleLarge)
        for (part in listOf("dbc", "maps", "vmaps", "mmaps", "cameras", "gt")) {
            FelsongStatusLine(part, stringResource(if (part in parts) R.string.wow_files_data_yes else R.string.wow_files_data_no), good = part in parts)
        }
        Text(stringResource(R.string.felsong_files_data_body), style = MaterialTheme.typography.bodySmall)
        FelsongSwitchRow(stringResource(R.string.wow_files_mmaps), stringResource(R.string.felsong_install_mmaps_summary), withMmaps, enabled = !working) {
            withMmaps = it
            FelsongSettings.setMmaps(context, it)
        }
        FelsongButton(stringResource(R.string.wow_files_extract), primary = hasClient && parts.isEmpty(), enabled = hasClient && installed && !working) {
            FelsongService.job(context, FelsongJobs.Kind.DATA, options = FelsongJobs.Options(withMmaps = withMmaps))
        }
        FelsongTitle(stringResource(R.string.felsong_files_data_pc_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.felsong_files_data_pc_body), style = MaterialTheme.typography.bodySmall)
        FelsongButton(stringResource(R.string.felsong_files_data_import), primary = false, enabled = !working) {
            pickData.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }

        FelsongTitle(stringResource(R.string.felsong_files_patch_title), style = MaterialTheme.typography.titleLarge)
        FelsongStatusLine(
            stringResource(R.string.felsong_step_title_patch).substringAfter("· "),
            stringResource(if (patched) R.string.felsong_files_patch_yes else R.string.felsong_files_patch_no),
            good = patched,
        )
        Text(stringResource(R.string.felsong_files_patch_body), style = MaterialTheme.typography.bodySmall)
        FelsongButton(stringResource(if (patched) R.string.felsong_patch_again else R.string.felsong_patch_now), primary = hasClient && !patched, enabled = hasClient && installed && !working) {
            FelsongService.job(context, FelsongJobs.Kind.PATCH)
        }
        Text(stringResource(R.string.wow_files_space, Formatter.formatShortFileSize(context, FelsongFiles.freeSpace(context))), style = MaterialTheme.typography.bodySmall)
    }
}

// Graphics ---------------------------------------------------------------------------------------

@Composable
private fun GraphicsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(GameSettings.resolution(context)) }
    FelsongDialog(title = stringResource(R.string.wow_graphics_title), onDismiss = onDismiss, buttons = {
        FelsongButton(stringResource(R.string.wow_close), primary = false, onClick = onDismiss)
    }) {
        Text(stringResource(R.string.wow_graphics_body), style = MaterialTheme.typography.bodySmall)
        for (resolution in GameSettings.Resolution.entries) {
            val (width, height) = GameSettings.gameSize(context, resolution)
            FelsongChoiceRow(
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

@Composable
private fun IconDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppIcons.current(context)) }
    FelsongDialog(title = stringResource(R.string.wow_icon_title), onDismiss = onDismiss, buttons = {
        FelsongButton(stringResource(R.string.wow_close), primary = false, onClick = onDismiss)
    }) {
        Text(stringResource(R.string.wow_icon_body), style = MaterialTheme.typography.bodySmall)
        for (icon in AppIcons.Icon.entries) {
            FelsongChoiceRow(
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

// Accounts ---------------------------------------------------------------------------------------

/** The realm's own accounts (auth): its database is started for this if the realm isn't running. */
@Composable
private fun AccountsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<FelsongAccounts.Account>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf(FelsongLogin.get(context)?.name) }

    fun act(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { FelsongServer.withDatabase(context) { block() } }.exceptionOrNull()
            }
            busy = false
            error = failure?.let { accountError(context, it) }
            refresh++
        }
    }
    LaunchedEffect(refresh) {
        saved = FelsongLogin.get(context)?.name
        if (!FelsongPackage.isInstalled(context) || !FelsongFiles.hasDatabases(context)) {
            error = context.getString(R.string.felsong_error_no_databases)
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) { runCatching { FelsongServer.withDatabase(context) { FelsongAccounts.list(context) } } }
        accounts = result.getOrNull()
        result.exceptionOrNull()?.let { error = accountError(context, it) }
    }
    FelsongDialog(title = stringResource(R.string.wow_tile_accounts), onDismiss = onDismiss, buttons = { FelsongButton(stringResource(R.string.felsong_close), onClick = onDismiss) }) {
        Text(stringResource(R.string.felsong_accounts_body), style = MaterialTheme.typography.bodySmall)
        if (accounts == null && error == null) Row(verticalAlignment = Alignment.CenterVertically) { FelsongSpinner(28.dp) }
        for (account in accounts.orEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FelsongStatusLine(account.name, levelName(context, account.level), good = if (account.name.equals(saved, ignoreCase = true)) true else null)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val next = FelsongAccountRules.LEVELS.let { levels -> levels[(levels.indexOf(account.level).coerceAtLeast(0) + 1) % levels.size] }
                    FelsongButton(levelName(context, next), primary = false, enabled = !busy) { act { FelsongAccounts.setLevel(context, account.name, next) } }
                    if (account.name.equals(saved, ignoreCase = true)) {
                        FelsongButton(stringResource(R.string.felsong_auto_login_off), primary = false, enabled = !busy) { FelsongLogin.forget(context); saved = null }
                    }
                    FelsongButton(stringResource(R.string.felsong_delete), primary = false, enabled = !busy) { act { FelsongAccounts.delete(context, account.name) } }
                }
            }
        }
        FelsongTitle(stringResource(R.string.felsong_accounts_new), style = MaterialTheme.typography.titleLarge)
        FelsongTextField(name, { name = it.take(64) }, stringResource(R.string.felsong_account_name), email = true)
        FelsongTextField(password, { password = it.take(16) }, stringResource(R.string.felsong_account_password), password = true)
        Text(stringResource(R.string.felsong_account_rules), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            FelsongButton(stringResource(R.string.felsong_password_change), primary = false, enabled = !busy && name.isNotEmpty() && password.isNotEmpty()) {
                val user = name
                val pass = password
                act { FelsongAccounts.setPassword(context, user, pass) }
            }
            FelsongButton(stringResource(R.string.felsong_account_make), enabled = !busy && name.isNotEmpty() && password.isNotEmpty()) {
                val user = name
                val pass = password
                val first = accounts.isNullOrEmpty()
                act {
                    FelsongAccounts.create(context, user, pass, if (first) FelsongAccountRules.LEVEL_ADMINISTRATOR else FelsongAccountRules.LEVEL_PLAYER)
                    // The first account logs in by itself, as in the other realms.
                    if (first || FelsongLogin.get(context) == null) FelsongLogin.save(context, user, pass)
                }
                name = ""
                password = ""
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = FelsongColors.Blood) }
    }
}

private fun levelName(context: android.content.Context, level: Int) = context.getString(
    when (level) {
        FelsongAccountRules.LEVEL_PLAYER -> R.string.felsong_level_player
        FelsongAccountRules.LEVEL_MODERATOR -> R.string.felsong_level_moderator
        FelsongAccountRules.LEVEL_GAMEMASTER -> R.string.felsong_level_gamemaster
        else -> R.string.felsong_level_admin
    }
)

private fun accountError(context: android.content.Context, error: Throwable): String = when (error) {
    is FelsongAccounts.InvalidException -> context.getString(
        when (error.reason) {
            FelsongAccounts.InvalidException.Reason.NAME -> R.string.felsong_account_bad_name
            FelsongAccounts.InvalidException.Reason.PASSWORD -> R.string.felsong_account_bad_password
            FelsongAccounts.InvalidException.Reason.TAKEN -> R.string.felsong_account_taken
        }
    )
    else -> context.getString(R.string.felsong_job_error, error.message ?: error.javaClass.simpleName)
}

// Rates and solo ---------------------------------------------------------------------------------

@Composable
private fun OptionsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    FelsongDialog(title = stringResource(R.string.felsong_tile_options), onDismiss = onDismiss, buttons = {
        FelsongButton(stringResource(R.string.felsong_defaults), primary = false) {
            FelsongSettings.reset(context)
            version++
        }
        FelsongButton(stringResource(R.string.felsong_close), onClick = onDismiss)
    }) {
        Text(stringResource(R.string.felsong_settings_body), style = MaterialTheme.typography.bodySmall)
        for (group in FelsongRealm.Group.entries) {
            val settings = FelsongRealm.SETTINGS.filter { it.group == group }
            if (settings.isEmpty()) continue
            FelsongTitle(
                stringResource(
                    when (group) {
                        FelsongRealm.Group.RATES -> R.string.felsong_settings_rates
                        FelsongRealm.Group.WORLD -> R.string.felsong_settings_world
                        FelsongRealm.Group.SOLO -> R.string.felsong_settings_solo
                    }
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            if (group == FelsongRealm.Group.SOLO) Text(stringResource(R.string.felsong_settings_solo_body), style = MaterialTheme.typography.bodySmall)
            for (setting in settings) {
                val current = remember(version) { mutableStateOf(FelsongSettings.value(context, setting)) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(settingLabel(context, setting.key), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (choice in setting.choices) {
                            val label = when {
                                setting.key.startsWith("Rate.") -> "×$choice"
                                setting.key == "Solocraft.Enable" -> stringResource(if (choice == "1") R.string.felsong_on else R.string.felsong_off)
                                else -> choice
                            }
                            FelsongButton(label, primary = current.value == choice) {
                                FelsongSettings.setValue(context, setting, choice)
                                current.value = choice
                            }
                        }
                    }
                }
            }
        }
        var mmaps by remember(version) { mutableStateOf(FelsongSettings.mmaps(context)) }
        FelsongSwitchRow(stringResource(R.string.felsong_install_mmaps), stringResource(R.string.felsong_install_mmaps_summary), mmaps) {
            mmaps = it
            FelsongSettings.setMmaps(context, it)
        }
    }
}

private fun settingLabel(context: android.content.Context, key: String): String = context.getString(
    when (key) {
        "Rate.XP.Kill" -> R.string.felsong_rate_xp_kill
        "Rate.XP.Quest" -> R.string.felsong_rate_xp_quest
        "Rate.XP.Explore" -> R.string.felsong_rate_xp_explore
        "Rate.Drop.Money" -> R.string.felsong_rate_money
        "Rate.Reputation.Gain" -> R.string.felsong_rate_reputation
        "Rate.Honor" -> R.string.felsong_rate_honor
        "StartPlayerLevel" -> R.string.felsong_start_level
        "StartPlayerMoney" -> R.string.felsong_start_money
        "Solocraft.Enable" -> R.string.felsong_solocraft
        else -> R.string.felsong_setting_other
    }
)

// Repair, uninstall ------------------------------------------------------------------------------

@Composable
private fun RepairDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf(setOf(FelsongJobs.Repair.PROGRAMS)) }
    var resetConfirm by remember { mutableStateOf(false) }
    FelsongDialog(title = stringResource(R.string.felsong_repair), onDismiss = onDismiss, buttons = {
        FelsongButton(stringResource(R.string.felsong_cancel), primary = false, onClick = onDismiss)
        FelsongButton(stringResource(R.string.felsong_repair), enabled = picked.isNotEmpty() && (FelsongJobs.Repair.DATABASES !in picked || resetConfirm)) {
            FelsongService.job(context, FelsongJobs.Kind.REPAIR, options = FelsongJobs.Options(withMmaps = FelsongSettings.mmaps(context), repairs = picked))
            onDismiss()
        }
    }) {
        Text(stringResource(R.string.felsong_repair_body), style = MaterialTheme.typography.bodySmall)
        for (repair in FelsongJobs.Repair.entries) {
            FelsongSwitchRow(repairTitle(context, repair), repairSummary(context, repair), repair in picked) { on ->
                picked = if (on) picked + repair else picked - repair
            }
        }
        if (FelsongJobs.Repair.DATABASES in picked) {
            FelsongSwitchRow(stringResource(R.string.felsong_repair_databases_confirm), null, resetConfirm) { resetConfirm = it }
        }
    }
}

private fun repairTitle(context: android.content.Context, repair: FelsongJobs.Repair) = context.getString(
    when (repair) {
        FelsongJobs.Repair.PROGRAMS -> R.string.felsong_repair_programs
        FelsongJobs.Repair.CONFIGS -> R.string.felsong_repair_configs
        FelsongJobs.Repair.CLIENT -> R.string.felsong_repair_client
        FelsongJobs.Repair.DATA -> R.string.felsong_repair_data
        FelsongJobs.Repair.DATABASES -> R.string.felsong_repair_databases
    }
)

private fun repairSummary(context: android.content.Context, repair: FelsongJobs.Repair) = context.getString(
    when (repair) {
        FelsongJobs.Repair.PROGRAMS -> R.string.felsong_repair_programs_summary
        FelsongJobs.Repair.CONFIGS -> R.string.felsong_repair_configs_summary
        FelsongJobs.Repair.CLIENT -> R.string.felsong_repair_client_summary
        FelsongJobs.Repair.DATA -> R.string.felsong_repair_data_summary
        FelsongJobs.Repair.DATABASES -> R.string.felsong_repair_databases_summary
    }
)

/** Uninstall: all of the Legion game, nothing else; confirmed by typing LEGION. */
@Composable
private fun UninstallDialog(facts: FelsongFacts, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }
    FelsongDialog(title = stringResource(R.string.felsong_uninstall), onDismiss = onDismiss, buttons = {
        FelsongButton(stringResource(R.string.felsong_cancel), primary = false, onClick = onDismiss)
        FelsongButton(stringResource(R.string.felsong_uninstall), enabled = typed.trim().equals("LEGION", ignoreCase = true)) {
            FelsongService.job(context, FelsongJobs.Kind.UNINSTALL)
            Toast.makeText(context, R.string.felsong_uninstalling, Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }) {
        Text(stringResource(R.string.felsong_uninstall_body, Formatter.formatShortFileSize(context, facts.used)), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.felsong_uninstall_keeps), style = MaterialTheme.typography.bodySmall)
        FelsongTextField(typed, { typed = it }, stringResource(R.string.felsong_uninstall_type))
    }
}
