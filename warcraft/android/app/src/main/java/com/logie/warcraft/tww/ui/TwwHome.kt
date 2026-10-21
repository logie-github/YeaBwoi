// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.tww.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.logie.warcraft.Environments
import com.logie.warcraft.R
import com.logie.warcraft.TitleMusic
import com.logie.warcraft.WowUpdateActivity
import com.logie.warcraft.WowUpdater
import com.logie.warcraft.tww.TwwFiles
import com.logie.warcraft.tww.TwwGame
import com.logie.warcraft.tww.TwwJobs
import com.logie.warcraft.tww.TwwServer
import com.logie.warcraft.tww.TwwService
import com.logie.warcraft.tww.core.TwwInstall
import com.logie.warcraft.tww.core.TwwRealm
import com.logie.warcraft.ui.WowButton
import com.logie.warcraft.ui.WowChoiceRow
import com.logie.warcraft.ui.WowColors
import com.logie.warcraft.ui.WowDialog
import com.logie.warcraft.ui.WowProgressLine
import com.logie.warcraft.ui.WowStatusLine
import com.logie.warcraft.ui.WowTheme
import com.logie.warcraft.ui.WowTitle
import com.logie.warcraft.ui.WowWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Another expansion within the existing launcher; all long work stays in its foreground job. */
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
    var pendingImport by rememberSaveable { mutableStateOf(TwwJobs.Kind.IMPORT_CLIENT.name) }
    var lastJob by rememberSaveable { mutableStateOf<String?>(null) }
    var lastUri by rememberSaveable { mutableStateOf<String?>(null) }
    var lastFolder by rememberSaveable { mutableStateOf(false) }
    var checkUpdate by rememberSaveable { mutableStateOf(checkUpdateOnOpen) }
    val updateOffer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { checkUpdate = false }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            lastJob = pendingImport; lastUri = uri.toString(); lastFolder = true
            TwwService.start(context, TwwJobs.Kind.valueOf(pendingImport), uri, true)
        }
    }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            lastJob = pendingImport; lastUri = uri.toString(); lastFolder = false
            TwwService.start(context, TwwJobs.Kind.valueOf(pendingImport), uri, false)
        }
    }
    fun run(kind: TwwJobs.Kind) {
        lastJob = kind.name; lastUri = null; lastFolder = false
        TwwService.start(context, kind)
    }
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { message = it.message ?: "No browser is available to open the download source." }
    }
    LaunchedEffect(checkUpdate) {
        if (checkUpdate) {
            val update = withContext(Dispatchers.IO) { if (WowUpdater.isConfigured) runCatching { WowUpdater.findUpdate() }.getOrNull() else null }
            if (update != null) updateOffer.launch(WowUpdateActivity.intent(context, update)) else checkUpdate = false
        }
    }
    LaunchedEffect(job, refresh, server.phase) {
        if (job !is TwwJobs.State.Working) {
            runCatching { withContext(Dispatchers.IO) { TwwFiles.report(context) } }
                .onSuccess { report = it }.onFailure { message = it.message }
        }
    }
    val busy = job is TwwJobs.State.Working
    val editable = !busy && !TwwServer.isBusy && !configuring
    WowTheme {
        Column(
            Modifier.fillMaxSize().background(WowColors.Abyss).verticalScroll(rememberScrollState())
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 20.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(TwwRealm.DISPLAY_NAME, style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.tww_metadata), style = MaterialTheme.typography.bodyMedium)
                WowButton(stringResource(R.string.games_tile), primary = false) { dialog = "games" }
                WowWindow(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        WowTitle(stringResource(R.string.tww_installation))
                        Text(stringResource(R.string.tww_replace_note), style = MaterialTheme.typography.bodySmall)
                        val checked = report
                        if (checked == null) Text(stringResource(R.string.tww_no_report)) else {
                            ComponentLine(R.string.tww_client, checked.client)
                            ComponentLine(R.string.tww_core, checked.core)
                            ComponentLine(R.string.tww_database, checked.database)
                            ComponentLine(R.string.tww_maps, checked.maps)
                            if (checked.firestormModified) Text(stringResource(R.string.tww_firestorm), style = MaterialTheme.typography.bodySmall)
                        }
                        WowButton(stringResource(R.string.tww_get_client), enabled = editable) { dialog = "client" }
                        WowButton(stringResource(R.string.tww_download_server), enabled = editable) { run(TwwJobs.Kind.DOWNLOAD_SERVER) }
                        WowButton(stringResource(R.string.tww_download_maps), enabled = editable) { run(TwwJobs.Kind.DOWNLOAD_MAPS) }
                        WowButton(stringResource(R.string.tww_import), primary = false, enabled = editable) { dialog = "import" }
                        WowButton(stringResource(R.string.tww_verify), primary = false, enabled = editable) { run(TwwJobs.Kind.VERIFY) }
                        Text(stringResource(R.string.tww_download_note), style = MaterialTheme.typography.bodySmall)
                    }
                }
                when (val state = job) {
                    is TwwJobs.State.Working -> WowWindow(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(state.detail)
                            state.progress?.let { WowProgressLine(it) }
                            WowButton(stringResource(R.string.tww_cancel), primary = false) { TwwService.cancel(context) }
                        }
                    }
                    is TwwJobs.State.Done -> ResultPanel(stringResource(R.string.tww_job_done), state.message) { TwwJobs.clearResult() }
                    is TwwJobs.State.Failed -> WowWindow(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            WowTitle(stringResource(R.string.tww_job_failed))
                            Text(state.message)
                            if (lastJob != null) WowButton(stringResource(R.string.tww_retry)) {
                                TwwService.start(context, TwwJobs.Kind.valueOf(lastJob!!), lastUri?.let(Uri::parse), lastFolder)
                            }
                            WowButton(stringResource(R.string.tww_close), primary = false) { TwwJobs.clearResult() }
                        }
                    }
                    else -> {}
                }
                WowWindow(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        WowTitle(stringResource(R.string.tww_controls))
                        WowStatusLine(stringResource(R.string.tww_database_process), server.database.name)
                        WowStatusLine(stringResource(R.string.tww_authentication), server.authentication.name)
                        WowStatusLine(stringResource(R.string.tww_world), server.world.name)
                        server.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        server.error?.let { Text(it, color = WowColors.Blood) }
                        report?.blockers?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        WowButton(stringResource(R.string.tww_connection), primary = false, enabled = editable) { dialog = "connection" }
                        WowButton(stringResource(R.string.tww_start), enabled = editable && report?.canStartServer == true) { TwwServer.start(context) }
                        WowButton(stringResource(R.string.tww_stop), primary = false, enabled = !busy && TwwServer.isBusy) { TwwServer.stop(context) }
                        if (server.phase == TwwServer.Phase.FAILED && TwwServer.isBusy) {
                            WowButton(stringResource(R.string.tww_force_stop), primary = false) { dialog = "force-stop" }
                        }
                        WowButton(stringResource(R.string.tww_launch), enabled = !busy && !configuring && report?.ready == true) { TwwGame.play(context) }
                        Text(stringResource(R.string.tww_runtime_note), style = MaterialTheme.typography.bodySmall)
                    }
                }
                WowWindow(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        WowTitle(stringResource(R.string.tww_storage))
                        Text(runCatching { TwwFiles.layout(context).root.path }.getOrDefault("Storage unavailable"), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.tww_storage_note), style = MaterialTheme.typography.bodySmall)
                        WowButton(stringResource(if (TwwFiles.storageExternal(context)) R.string.tww_internal else R.string.tww_external), primary = false, enabled = editable) {
                            if (!TwwFiles.setExternal(context, !TwwFiles.storageExternal(context))) message = context.getString(R.string.tww_storage_error)
                            refresh++
                        }
                        WowButton(stringResource(R.string.tww_clean), primary = false, enabled = editable) { run(TwwJobs.Kind.CLEAN_DOWNLOADS) }
                    }
                }
                WowButton(stringResource(R.string.tww_sources), primary = false) { dialog = "sources" }
            }
        }
        message?.let { text -> WowDialog(stringResource(R.string.tww_name), { message = null }) { Text(text) } }
        when (dialog) {
            "client" -> WowDialog(stringResource(R.string.tww_get_client), { dialog = null }, buttons = {
                WowButton(stringResource(R.string.tww_close), primary = false) { dialog = null }
                WowButton(stringResource(R.string.tww_open_firestorm)) { open(TwwRealm.CLIENT_URL) }
            }) { Text(stringResource(R.string.tww_client_workflow)) }
            "import" -> WowDialog(stringResource(R.string.tww_import), { dialog = null }) {
                Text(stringResource(R.string.tww_archive_support))
                listOf(TwwJobs.Kind.IMPORT_CLIENT to (R.string.tww_import_client_folder to R.string.tww_import_client_archive),
                    TwwJobs.Kind.IMPORT_SERVER to (R.string.tww_import_server_folder to R.string.tww_import_server_archive),
                    TwwJobs.Kind.IMPORT_MAPS to (R.string.tww_import_maps_folder to R.string.tww_import_maps_archive)).forEach { (kind, labels) ->
                    WowButton(stringResource(labels.first), primary = false) { pendingImport = kind.name; dialog = null; folderPicker.launch(null) }
                    WowButton(stringResource(labels.second), primary = false) { pendingImport = kind.name; dialog = null; archivePicker.launch(arrayOf("*/*")) }
                }
            }
            "connection" -> WowDialog(stringResource(R.string.tww_connection), { dialog = null }, buttons = {
                WowButton(stringResource(R.string.tww_close), primary = false) { dialog = null }
                WowButton(stringResource(R.string.tww_configure)) {
                    dialog = null
                    configuring = true
                    scope.launch {
                        message = withContext(Dispatchers.IO) {
                            runCatching {
                                TwwServer.configureLocal(context)
                                TwwGame.configureLocal(context, acknowledgeFirestorm = true)
                                context.getString(R.string.tww_configured)
                            }
                                .getOrElse { it.message ?: it.javaClass.simpleName }
                        }
                        configuring = false
                        refresh++
                    }
                }
            }) { Text(stringResource(R.string.tww_connection_note)) }
            "force-stop" -> WowDialog(stringResource(R.string.tww_force_stop), { dialog = null }, buttons = {
                WowButton(stringResource(R.string.tww_close), primary = false) { dialog = null }
                WowButton(stringResource(R.string.tww_force_stop)) { dialog = null; TwwServer.forceStop(context) }
            }) { Text(stringResource(R.string.tww_force_stop_warning)) }
            "sources" -> WowDialog(stringResource(R.string.tww_sources), { dialog = null }) {
                WowButton(stringResource(R.string.tww_open_firestorm)) { open(TwwRealm.CLIENT_URL) }
                WowButton(stringResource(R.string.tww_source_server)) { open(TwwRealm.SERVER_URL) }
                WowButton(stringResource(R.string.tww_source_maps)) { open(TwwRealm.MMAPS_URL) }
            }
            "games" -> WowDialog(stringResource(R.string.games_title), { dialog = null }) {
                listOf(Environments.Environment.WRATH to R.string.wrath_name, Environments.Environment.TURTLE to R.string.turtle_tile_name, Environments.Environment.FELSONG to R.string.felsong_name).forEach { (environment, label) ->
                    WowChoiceRow(stringResource(label), null, false) {
                        Environments.select(context, environment)
                        TitleMusic.onGameChanged(context)
                        dialog = null
                    }
                }
            }
        }
    }
}

@Composable
private fun ComponentLine(label: Int, component: TwwInstall.Component) {
    Column {
        WowStatusLine(stringResource(label), component.status.name.lowercase().replace('_', ' '), good = component.status == TwwInstall.Status.INSTALLED)
        if (component.missing.isNotEmpty()) Text(component.missing.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        if (component.details.isNotEmpty()) Text(component.details.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ResultPanel(title: String, message: String, onClose: () -> Unit) {
    WowWindow(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WowTitle(title)
            Text(message)
            WowButton(stringResource(R.string.tww_close), primary = false, onClick = onClose)
        }
    }
}
