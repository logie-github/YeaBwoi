// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.ui

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.logie.warcraft.R
import com.logie.warcraft.export.BlizzardExport
import com.logie.warcraft.export.ExportService
import com.logie.warcraft.export.SafExportSink
import com.logie.warcraft.export.core.ExportPlan
import com.logie.warcraft.export.core.ExportPlanner
import com.logie.warcraft.export.core.ExportRules
import com.logie.warcraft.export.core.VerifyReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Export everything to Blizzard": what will be copied with its sizes per game, the folder to copy
 * to (Android's folder picker) with the free space it needs and has, the progress by bytes and
 * files, Stop, and how it went. Verify re-reads the copy against its checksums.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlizzardExportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by BlizzardExport.state.collectAsState()
    val working = state is BlizzardExport.State.Working

    var plan by remember { mutableStateOf<ExportPlan?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(emptyList<String>()) }
    var chosen by remember { mutableStateOf(ExportRules.Group.entries.toSet()) }
    var tree by remember { mutableStateOf(BlizzardExport.rememberedTree(context)) }
    var picked by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf<Triple<String, Long?, Long>?>(null) }
    val outside = remember { BlizzardExport.outsideFiles(context) }

    // What is there, counted again whenever no export is running (a finished one changes nothing, but the app may have).
    LaunchedEffect(working) {
        if (working) return@LaunchedEffect
        scanError = null
        val found = runCatching { withContext(Dispatchers.IO) { BlizzardExport.scan(context) } }
        plan = found.getOrNull()
        scanError = found.exceptionOrNull()?.message
        running = BlizzardExport.runningRealms(context).map { it.label }
    }

    // The destination: its name, its free space, and what still has to be written to it.
    LaunchedEffect(tree, plan, chosen, picked) {
        val folder = tree
        val current = plan
        destination = null
        if (folder == null || current == null || !picked) return@LaunchedEffect
        destination = runCatching {
            withContext(Dispatchers.IO) {
                val sink = SafExportSink(context, folder)
                Triple(sink.displayName(), sink.freeBytes(), ExportPlanner.remainingBytes(current.only(chosen), sink))
            }
        }.getOrNull()
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        tree = uri
        picked = true
    }
    val pickForVerify = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        tree = uri
        ExportService.verify(context, uri)
    }

    val blocker = remember(working, state) { BlizzardExport.blocker(context) }
    val selected = plan?.only(chosen)
    val canExport = !working && picked && tree != null && selected != null && selected.files.isNotEmpty() && blocker == null

    WowDialog(
        title = stringResource(R.string.export_title),
        onDismiss = onDismiss,
        buttons = {
            if (working) WowButton(stringResource(R.string.wow_import_stop), primary = false) { ExportService.cancel() }
            WowButton(stringResource(R.string.wow_close), primary = false, onClick = onDismiss)
        },
    ) {
        when (val s = state) {
            is BlizzardExport.State.Working -> ExportWorking(s)
            is BlizzardExport.State.Done -> Text(
                stringResource(R.string.export_done, s.files, Formatter.formatShortFileSize(context, s.bytes), s.destination),
                style = MaterialTheme.typography.bodyMedium,
                color = WowColors.Good,
            )
            is BlizzardExport.State.Verified -> VerifyResult(s.report)
            is BlizzardExport.State.Failed -> Text(s.message, style = MaterialTheme.typography.bodyMedium, color = WowColors.Blood)
            BlizzardExport.State.Idle -> {}
        }

        Text(stringResource(R.string.export_intro), style = MaterialTheme.typography.bodySmall)

        WowTitle(stringResource(R.string.export_sizes_title), style = MaterialTheme.typography.titleLarge)
        val current = plan
        when {
            current != null -> {
                val byGroup = current.byGroup()
                for (group in ExportRules.Group.entries) {
                    val (files, bytes) = byGroup[group] ?: continue
                    WowSwitchRow(
                        group.title,
                        stringResource(R.string.export_group_summary, files, Formatter.formatShortFileSize(context, bytes)),
                        checked = group in chosen,
                        enabled = !working,
                    ) { on -> chosen = if (on) chosen + group else chosen - group }
                }
                WowStatusLine(
                    stringResource(R.string.export_total, selected?.files?.size ?: 0, Formatter.formatShortFileSize(context, selected?.totalBytes ?: 0)),
                    "",
                )
                Text(stringResource(R.string.export_left_out_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.export_left_out_body), style = MaterialTheme.typography.bodySmall)
            }
            scanError != null -> Text(scanError ?: "", style = MaterialTheme.typography.bodySmall, color = WowColors.Blood)
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                WowSpinner(24.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.export_scanning), style = MaterialTheme.typography.bodySmall)
            }
        }
        plan?.renamed?.size?.takeIf { it > 0 }?.let { n ->
            Text(stringResource(R.string.export_renamed, n), style = MaterialTheme.typography.bodySmall, color = WowColors.Mist)
        }
        if (outside.isNotEmpty()) {
            Text(stringResource(R.string.export_outside, outside.joinToString()), style = MaterialTheme.typography.bodySmall, color = WowColors.Blood)
        }

        blocker?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = WowColors.Blood) }
        if (running.isNotEmpty() && blocker == null && !working) {
            Text(stringResource(R.string.export_realm_running, running.joinToString()), style = MaterialTheme.typography.bodyMedium, color = WowColors.Blood)
        }

        destination?.let { (name, free, needed) ->
            Text(stringResource(R.string.export_space_needed, Formatter.formatShortFileSize(context, needed)), style = MaterialTheme.typography.bodySmall)
            Text(
                if (free != null) stringResource(R.string.export_space_free, Formatter.formatShortFileSize(context, free)) else stringResource(R.string.export_space_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = if (free != null && !ExportPlanner.fits(needed, free)) WowColors.Blood else WowColors.Text,
            )
            Text(name, style = MaterialTheme.typography.bodySmall, color = WowColors.Mist)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (picked && tree != null) {
                val name = destination?.first ?: ""
                WowButton(
                    if (running.isNotEmpty()) stringResource(R.string.export_stop_and_export) else stringResource(R.string.export_start, name),
                    enabled = canExport,
                ) {
                    ExportService.export(context, tree!!, chosen, stopRealms = running.isNotEmpty())
                }
                WowButton(stringResource(R.string.export_other_folder), primary = false, enabled = !working) { pickFolder.launch(null) }
            } else {
                WowButton(stringResource(R.string.export_choose), enabled = !working && plan != null) { pickFolder.launch(null) }
            }
            WowButton(stringResource(R.string.export_verify), primary = false, enabled = !working) {
                val remembered = tree
                if (remembered != null) ExportService.verify(context, remembered) else pickForVerify.launch(null)
            }
        }
    }
}

@Composable
private fun ExportWorking(s: BlizzardExport.State.Working) {
    val context = LocalContext.current
    WowWindow(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WowSpinner(32.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    when (s.phase) {
                        BlizzardExport.Phase.CHECKING -> stringResource(R.string.export_phase_checking)
                        BlizzardExport.Phase.STOPPING -> stringResource(R.string.export_phase_stopping, s.note ?: "")
                        BlizzardExport.Phase.SCANNING -> stringResource(R.string.export_phase_scanning)
                        BlizzardExport.Phase.COPYING -> stringResource(R.string.export_phase_copying)
                        BlizzardExport.Phase.VERIFYING -> stringResource(R.string.export_phase_verifying)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            s.progress?.let { p ->
                if (p.totalBytes > 0) WowProgressLine((p.bytes.toDouble() / p.totalBytes).toFloat())
                Text(stringResource(R.string.export_progress_files, p.files, p.totalFiles), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(
                        R.string.export_progress_bytes,
                        Formatter.formatShortFileSize(context, p.bytes), Formatter.formatShortFileSize(context, p.totalBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (p.skipped > 0) Text(stringResource(R.string.export_progress_skipped, p.skipped), style = MaterialTheme.typography.bodySmall)
                p.current?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = WowColors.Mist) }
            }
            Text(stringResource(R.string.wow_import_background_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VerifyResult(report: VerifyReport) {
    val context = LocalContext.current
    when {
        report.problem != null -> Text(stringResource(R.string.export_verify_problem, report.problem ?: ""), style = MaterialTheme.typography.bodyMedium, color = WowColors.Blood)
        report.ok -> Text(
            stringResource(R.string.export_verify_ok, report.checked, Formatter.formatShortFileSize(context, report.bytes)),
            style = MaterialTheme.typography.bodyMedium,
            color = WowColors.Good,
        )
        else -> {
            Text(
                stringResource(R.string.export_verify_bad, report.missing.size, report.wrongSize.size, report.wrongChecksum.size),
                style = MaterialTheme.typography.bodyMedium,
                color = WowColors.Blood,
            )
            val lines = (report.missing.map { "missing  $it" } + report.wrongSize.map { "size     $it" } + report.wrongChecksum.map { "checksum $it" })
            WowLog(lines.take(30).joinToString("\n") + if (lines.size > 30) "\n… and ${lines.size - 30} more" else "")
        }
    }
}
