// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.logie.warcraft.R
import com.logie.warcraft.WowFiles
import com.logie.warcraft.felsong.FelsongAccounts
import com.logie.warcraft.felsong.FelsongFiles
import com.logie.warcraft.felsong.FelsongJobs
import com.logie.warcraft.felsong.FelsongLogin
import com.logie.warcraft.felsong.FelsongServer
import com.logie.warcraft.felsong.FelsongService
import com.logie.warcraft.felsong.FelsongSettings
import com.logie.warcraft.felsong.core.FelsongAccountRules
import com.logie.warcraft.felsong.core.FelsongGraphics
import com.logie.warcraft.felsong.core.FelsongInstallPlan
import com.logie.warcraft.felsong.core.FelsongRealm
import com.logie.warcraft.game.GameSettings
import com.logie.warcraft.ui.AppIconDialog
import com.logie.warcraft.ui.WowChoiceRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Legion's steps and their states in words. */
object LegionTexts {
    @Composable
    fun step(step: FelsongInstallPlan.Step): String = stringResource(
        when (step) {
            FelsongInstallPlan.Step.CHECK -> R.string.legion_step_check
            FelsongInstallPlan.Step.SERVER -> R.string.legion_step_server
            FelsongInstallPlan.Step.DATABASE -> R.string.legion_step_database
            FelsongInstallPlan.Step.CLIENT -> R.string.legion_step_client
            FelsongInstallPlan.Step.DATA -> R.string.legion_step_data
            FelsongInstallPlan.Step.PATCH -> R.string.legion_step_patch
            FelsongInstallPlan.Step.ACCOUNT -> R.string.legion_step_account
            FelsongInstallPlan.Step.READY -> R.string.legion_step_ready
        }
    )

    @Composable
    fun status(status: FelsongInstallPlan.Status): String = stringResource(
        when (status) {
            FelsongInstallPlan.Status.DONE -> R.string.legion_status_done
            FelsongInstallPlan.Status.TODO -> R.string.legion_status_todo
            FelsongInstallPlan.Status.NEEDS_USER -> R.string.legion_status_needs_you
            FelsongInstallPlan.Status.BLOCKED -> R.string.legion_status_blocked
            FelsongInstallPlan.Status.WAITING -> R.string.legion_status_waiting
        }
    )
}

/** The screens of the Legion launcher: [dialog] names the open one (null: none). */
@Composable
fun LegionDialogs(
    dialog: String?,
    facts: LegionFacts,
    server: FelsongServer.Status,
    job: FelsongJobs.State,
    onDialog: (String?) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val busy = job is FelsongJobs.State.Working
    val editable = !busy && !FelsongServer.isBusy

    // The pickers stay registered whatever dialog is open.
    val clientFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) FelsongService.job(context, FelsongJobs.Kind.CLIENT, uri, folder = true, options = FelsongJobs.Options(withMmaps = FelsongSettings.mmaps(context)))
    }
    val clientZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) FelsongService.job(context, FelsongJobs.Kind.CLIENT, uri, options = FelsongJobs.Options(withMmaps = FelsongSettings.mmaps(context)))
    }
    val dataZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) FelsongService.job(context, FelsongJobs.Kind.IMPORT_DATA, uri)
    }
    fun run(kind: FelsongJobs.Kind, options: FelsongJobs.Options = FelsongJobs.Options(withMmaps = FelsongSettings.mmaps(context))) {
        onDialog(null)
        FelsongService.job(context, kind, options = options)
    }

    message?.let { text ->
        FelsongDialog(stringResource(R.string.felsong_name), { message = null }, buttons = { FelsongButton(stringResource(R.string.turtle_close)) { message = null } }) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }

    when (dialog) {
        LegionDialog.RESULT -> {
            val done = job as? FelsongJobs.State.Done
            val failed = job as? FelsongJobs.State.Failed
            if (done != null || failed != null) {
                val close = { FelsongJobs.clearResult(); onDialog(null); onChanged() }
                FelsongDialog(
                    stringResource(if (failed != null) R.string.felsong_job_failed else R.string.felsong_job_done),
                    close,
                    buttons = { FelsongButton(stringResource(R.string.turtle_close), onClick = close) },
                ) {
                    Text(done?.message ?: failed?.message.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    failed?.log?.takeIf { it.isNotBlank() }?.let {
                        Text(stringResource(R.string.legion_log_end), style = MaterialTheme.typography.bodySmall, color = FelsongColors.Sage)
                        Text(it.takeLast(2500), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        LegionDialog.REALM -> {
            var lan by remember { mutableStateOf(FelsongSettings.lan(context)) }
            FelsongDialog(stringResource(R.string.legion_tile_realm), { onDialog(null) }, buttons = { FelsongButton(stringResource(R.string.turtle_close)) { onDialog(null) } }) {
                StatusRow(stringResource(R.string.legion_realm_row_realm), com.logie.warcraft.felsong.FelsongTexts.server(context, server), server.phase == FelsongServer.Phase.RUNNING && server.worldUp)
                StatusRow(stringResource(R.string.legion_realm_row_here), "127.0.0.1", true)
                StatusRow(stringResource(R.string.legion_realm_row_lan), server.lanAddress ?: stringResource(R.string.legion_realm_lan_none), server.lanAddress != null)
                StatusRow(stringResource(R.string.legion_realm_row_ports), FelsongRealm.PORTS.joinToString(), true)
                server.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                server.error?.let { Text(it, color = FelsongColors.Blood) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(lan, { lan = it; FelsongSettings.setLan(context, it) }, enabled = editable)
                    Text(stringResource(R.string.legion_lan))
                }
                Text(stringResource(R.string.legion_lan_note), style = MaterialTheme.typography.bodySmall)
                FelsongButton(stringResource(R.string.wow_server_start), Modifier.fillMaxWidth(), enabled = editable && facts.serverInstalled) { onDialog(null); FelsongServer.start(context) }
                FelsongButton(stringResource(R.string.wow_server_stop), Modifier.fillMaxWidth(), primary = false, enabled = !busy && FelsongServer.isBusy) { FelsongServer.stop(context) }
                FelsongButton(stringResource(R.string.legion_logs), Modifier.fillMaxWidth(), primary = false) {
                    scope.launch {
                        message = withContext(Dispatchers.IO) { realmLogs(context) }
                    }
                }
                FelsongButton(stringResource(R.string.legion_background), Modifier.fillMaxWidth(), primary = false) { onDialog("background") }
            }
        }

        "background" -> BackgroundDialog { onDialog(null) }

        LegionDialog.FILES -> FelsongDialog(stringResource(R.string.legion_tile_files), { onDialog(null) }, buttons = { FelsongButton(stringResource(R.string.turtle_close)) { onDialog(null) } }) {
            var mmaps by remember { mutableStateOf(FelsongSettings.mmaps(context)) }
            val state = facts.state
            StatusRow(
                stringResource(R.string.legion_files_client),
                if (facts.client) stringResource(R.string.legion_files_client_in, state.clientBuild) else stringResource(R.string.legion_files_missing),
                facts.client && state.clientBuild == FelsongRealm.CLIENT_BUILD,
            )
            StatusRow(
                stringResource(R.string.legion_files_data),
                if (facts.data) facts.dataParts.sorted().joinToString() else stringResource(R.string.legion_files_missing),
                facts.data && facts.dataCurrent,
            )
            StatusRow(stringResource(R.string.legion_files_patched), if (facts.patched) stringResource(R.string.legion_status_done) else stringResource(R.string.legion_files_missing), facts.patched)
            StatusRow(stringResource(R.string.legion_files_space), stringResource(R.string.legion_files_space_value, Formatter.formatShortFileSize(context, facts.used), Formatter.formatShortFileSize(context, facts.free)), true)
            Text(stringResource(R.string.legion_files_note), style = MaterialTheme.typography.bodySmall)
            FelsongButton(stringResource(R.string.legion_download_client), Modifier.fillMaxWidth(), enabled = editable) { run(FelsongJobs.Kind.DOWNLOAD_CLIENT) }
            Text(stringResource(R.string.legion_client_note, Formatter.formatShortFileSize(context, facts.free)), style = MaterialTheme.typography.bodySmall)
            FelsongButton(stringResource(R.string.legion_own_folder), Modifier.fillMaxWidth(), primary = false, enabled = editable) { onDialog(null); clientFolder.launch(null) }
            FelsongButton(stringResource(R.string.legion_own_zip), Modifier.fillMaxWidth(), primary = false, enabled = editable) { onDialog(null); clientZip.launch(arrayOf("*/*")) }
            FelsongButton(stringResource(R.string.legion_verify_client), Modifier.fillMaxWidth(), primary = false, enabled = editable && facts.client) { run(FelsongJobs.Kind.VERIFY) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(mmaps, { mmaps = it; FelsongSettings.setMmaps(context, it) }, enabled = editable)
                Text(stringResource(R.string.legion_mmaps))
            }
            Text(stringResource(R.string.legion_mmaps_note), style = MaterialTheme.typography.bodySmall)
            FelsongButton(stringResource(R.string.legion_make_data), Modifier.fillMaxWidth(), primary = false, enabled = editable && facts.client && facts.serverInstalled) { run(FelsongJobs.Kind.DATA) }
            FelsongButton(stringResource(R.string.legion_import_data), Modifier.fillMaxWidth(), primary = false, enabled = editable && facts.serverInstalled) { onDialog(null); dataZip.launch(arrayOf("*/*")) }
            FelsongButton(stringResource(R.string.legion_patch_client), Modifier.fillMaxWidth(), primary = false, enabled = editable && facts.client && facts.serverInstalled) { run(FelsongJobs.Kind.PATCH) }
        }

        LegionDialog.ACCOUNTS -> AccountsDialog(editable, facts, onDismiss = { onDialog(null); onChanged() })

        LegionDialog.SETTINGS -> {
            var refresh by remember { mutableIntStateOf(0) }
            FelsongDialog(stringResource(R.string.legion_tile_settings), { onDialog(null) }, buttons = {
                FelsongButton(stringResource(R.string.legion_defaults), primary = false, enabled = editable) {
                    FelsongRealm.SETTINGS.forEach { FelsongSettings.setValue(context, it, it.default) }
                    refresh++
                }
                FelsongButton(stringResource(R.string.turtle_close)) { onDialog(null) }
            }) {
                if (!editable) Text(stringResource(R.string.legion_settings_stop_first), color = FelsongColors.Ember)
                Text(stringResource(R.string.legion_settings_note), style = MaterialTheme.typography.bodySmall)
                key(refresh) {
                    FelsongRealm.SETTINGS.forEach { setting ->
                        var value by remember { mutableStateOf(FelsongSettings.value(context, setting)) }
                        val shown = when {
                            setting.key == "Solocraft.Enable" -> stringResource(if (value == "1") R.string.legion_on else R.string.legion_off)
                            setting.key == "StartPlayerMoney" -> stringResource(R.string.legion_copper, value)
                            setting.group == FelsongRealm.Group.RATES -> "× $value"
                            else -> value
                        }
                        FelsongChoiceRow(LegionSettingNames.title(setting.key), shown) {
                            if (editable) {
                                val next = setting.choices[(setting.choices.indexOf(value) + 1) % setting.choices.size]
                                FelsongSettings.setValue(context, setting, next)
                                value = next
                            }
                        }
                    }
                }
            }
        }

        LegionDialog.GRAPHICS -> {
            var resolution by remember { mutableStateOf(GameSettings.resolution(context)) }
            var api by remember { mutableStateOf(FelsongSettings.api(context)) }
            var driver by remember { mutableStateOf(FelsongSettings.graphics(context)) }
            FelsongDialog(stringResource(R.string.legion_tile_graphics), { onDialog(null) }, buttons = { FelsongButton(stringResource(R.string.turtle_close)) { onDialog(null) } }) {
                Text(stringResource(R.string.legion_graphics_note), style = MaterialTheme.typography.bodySmall)
                GameSettings.Resolution.entries.forEach { value ->
                    val size = GameSettings.gameSize(context, value)
                    WowChoiceRow(stringResource(value.label), "${size.first} × ${size.second}", resolution == value) { GameSettings.setResolution(context, value); resolution = value }
                }
                Text(stringResource(R.string.legion_graphics_api), style = MaterialTheme.typography.titleSmall)
                for ((id, summary) in listOf(
                    FelsongGraphics.D3D9 to R.string.legion_api_d3d9,
                    FelsongGraphics.D3D11 to R.string.legion_api_d3d11,
                    FelsongGraphics.OPENGL to R.string.legion_api_opengl,
                )) {
                    WowChoiceRow(FelsongGraphics.name(id), stringResource(summary), api == id) { FelsongSettings.setApi(context, id); api = id }
                }
                Text(stringResource(R.string.legion_graphics_api_note), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.legion_graphics_driver), style = MaterialTheme.typography.titleSmall)
                WowChoiceRow("Vortek", stringResource(R.string.legion_driver_vortek), driver == FelsongSettings.VORTEK) { FelsongSettings.setGraphics(context, FelsongSettings.VORTEK); driver = FelsongSettings.VORTEK }
                if (FelsongSettings.turnipAvailable(context)) {
                    WowChoiceRow("Turnip", stringResource(R.string.legion_driver_turnip), driver == FelsongSettings.TURNIP) { FelsongSettings.setGraphics(context, FelsongSettings.TURNIP); driver = FelsongSettings.TURNIP }
                }
            }
        }

        LegionDialog.UPKEEP -> UpkeepDialog(facts, editable, onDialog, onChanged, run = { kind, options -> run(kind, options) })

        "uninstall" -> FelsongDialog(stringResource(R.string.legion_uninstall_title), { onDialog(LegionDialog.UPKEEP) }, buttons = {
            FelsongButton(stringResource(R.string.felsong_cancel), primary = false) { onDialog(LegionDialog.UPKEEP) }
            FelsongButton(stringResource(R.string.legion_uninstall)) { run(FelsongJobs.Kind.UNINSTALL) }
        }) { Text(stringResource(R.string.legion_uninstall_body, Formatter.formatShortFileSize(context, facts.used))) }

        LegionDialog.ICON -> AppIconDialog { onDialog(null) }
    }
}

@Composable
private fun StatusRow(name: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, modifier = Modifier.weight(1f))
        Text(value, color = if (good) FelsongColors.Pale else FelsongColors.Sage, modifier = Modifier.weight(1.4f))
    }
}

/** What the realm's programs last said, for the details of a failure. */
private fun realmLogs(context: Context): String {
    val logs = FelsongFiles.layout(context).logs
    return listOf("configure.log", "bnetserver.out", "worldserver.out", "mysqld.out", "db-init.log", "maps.log", "vmaps.log", "patch-client.log", "game-session.log")
        .map { File(logs, it) }
        .filter { it.isFile }
        .joinToString("\n\n") { it.name + "\n" + WowFiles.tail(it, 12).takeLast(1500) }
        .ifBlank { context.getString(R.string.legion_no_logs) }
}

/** Legion's accounts: made, chosen for the game's login, changed, removed; in the realm's own database. */
@Composable
private fun AccountsDialog(editable: Boolean, facts: LegionFacts, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<FelsongAccounts.Account>>(emptyList()) }
    var name by remember { mutableStateOf(FelsongLogin.get(context)?.name.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var create by remember { mutableStateOf(facts.savedAccount == null) }
    var admin by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val usable = facts.databases && facts.serverInstalled && !facts.blockerPresent()

    LaunchedEffect(reload, usable) {
        if (!usable) return@LaunchedEffect
        try {
            accounts = withContext(Dispatchers.IO) { FelsongServer.withDatabase(context) { FelsongAccounts.list(context) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message
        }
    }

    fun explain(failure: Exception): String = when ((failure as? FelsongAccounts.InvalidException)?.reason) {
        FelsongAccounts.InvalidException.Reason.NAME -> context.getString(R.string.legion_account_bad_name)
        FelsongAccounts.InvalidException.Reason.PASSWORD -> context.getString(R.string.legion_account_bad_password)
        FelsongAccounts.InvalidException.Reason.TAKEN -> context.getString(R.string.legion_account_taken)
        null -> failure.message ?: context.getString(R.string.legion_account_failed)
    }

    fun submit(action: String) {
        working = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    FelsongServer.withDatabase(context) {
                        when (action) {
                            "create" -> FelsongAccounts.create(context, name, password, if (admin) FelsongAccountRules.LEVEL_ADMINISTRATOR else FelsongAccountRules.LEVEL_PLAYER)
                            "password" -> FelsongAccounts.setPassword(context, name, password)
                        }
                        if (!FelsongAccounts.isPassword(context, name, password)) throw java.io.IOException(context.getString(R.string.legion_account_mismatch))
                    }
                    FelsongLogin.save(context, name, password)
                }
                reload++
                create = false
                password = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = explain(failure)
            } finally {
                working = false
            }
        }
    }

    FelsongDialog(stringResource(R.string.legion_tile_accounts), onDismiss, buttons = { FelsongButton(stringResource(R.string.turtle_close), onClick = onDismiss) }) {
        if (!usable) {
            Text(stringResource(R.string.legion_accounts_install_first))
            return@FelsongDialog
        }
        if (!editable && !FelsongServer.isBusy) Text(stringResource(R.string.legion_accounts_busy), style = MaterialTheme.typography.bodySmall)
        accounts.forEach { account ->
            FelsongChoiceRow(
                account.name,
                stringResource(
                    if (name.equals(account.name, ignoreCase = true)) R.string.legion_account_selected else R.string.legion_account_use,
                ) + if (account.level > 0) " · GM ${account.level}" else "",
            ) { name = account.name; create = false; password = "" }
        }
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.legion_account_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !working)
        OutlinedTextField(
            password, { password = it },
            label = { Text(stringResource(if (create) R.string.legion_account_new_password else R.string.legion_account_password)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !working,
        )
        Text(stringResource(R.string.legion_account_rules), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(create, { create = it }, enabled = !working); Text(stringResource(R.string.legion_account_create)) }
        if (create) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(admin, { admin = it }, enabled = !working); Text(stringResource(R.string.legion_account_admin)) }
        error?.let { Text(it, color = FelsongColors.Blood) }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        FelsongButton(
            stringResource(if (create) R.string.legion_account_create_use else R.string.legion_account_use_button),
            Modifier.fillMaxWidth(), enabled = !working && name.isNotBlank() && password.isNotEmpty(),
        ) { submit(if (create) "create" else "use") }
        if (!create) {
            FelsongButton(stringResource(R.string.legion_account_change_password), Modifier.fillMaxWidth(), primary = false, enabled = !working && name.isNotBlank() && password.isNotEmpty()) { submit("password") }
        }
    }
}

private fun LegionFacts.blockerPresent() = blocker != null

/** Repair, backups and uninstall. */
@Composable
private fun UpkeepDialog(
    facts: LegionFacts,
    editable: Boolean,
    onDialog: (String?) -> Unit,
    onChanged: () -> Unit,
    run: (FelsongJobs.Kind, FelsongJobs.Options) -> Unit,
) {
    val context = LocalContext.current
    var repairs by remember { mutableStateOf(emptySet<FelsongJobs.Repair>()) }
    var backups by remember { mutableStateOf(FelsongJobs.backups(context)) }
    FelsongDialog(stringResource(R.string.legion_tile_upkeep), { onDialog(null); onChanged() }, buttons = { FelsongButton(stringResource(R.string.turtle_close)) { onDialog(null); onChanged() } }) {
        Text(stringResource(R.string.legion_repair_title), style = MaterialTheme.typography.titleSmall)
        for ((repair, label) in listOf(
            FelsongJobs.Repair.PROGRAMS to R.string.legion_repair_programs,
            FelsongJobs.Repair.CONFIGS to R.string.legion_repair_configs,
            FelsongJobs.Repair.CLIENT to R.string.legion_repair_client,
            FelsongJobs.Repair.DATA to R.string.legion_repair_data,
            FelsongJobs.Repair.DATABASES to R.string.legion_repair_databases,
        )) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(repair in repairs, { repairs = if (it) repairs + repair else repairs - repair }, enabled = editable)
                Text(stringResource(label))
            }
        }
        Text(stringResource(R.string.legion_repair_note), style = MaterialTheme.typography.bodySmall)
        FelsongButton(stringResource(R.string.legion_repair), Modifier.fillMaxWidth(), enabled = editable && repairs.isNotEmpty()) {
            run(FelsongJobs.Kind.REPAIR, FelsongJobs.Options(repairs = repairs, withMmaps = FelsongSettings.mmaps(context)))
        }
        Text(stringResource(R.string.legion_backups_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.legion_backups_note), style = MaterialTheme.typography.bodySmall)
        FelsongButton(stringResource(R.string.legion_backup_now), Modifier.fillMaxWidth(), primary = false, enabled = editable && facts.databases) {
            run(FelsongJobs.Kind.BACKUP, FelsongJobs.Options())
        }
        backups.take(5).forEach { file ->
            FelsongChoiceRow(file.name, stringResource(R.string.legion_restore_tap)) {
                if (editable) run(FelsongJobs.Kind.RESTORE, FelsongJobs.Options(restoreFile = file.name))
            }
        }
        if (backups.isEmpty()) Text(stringResource(R.string.legion_no_backups), style = MaterialTheme.typography.bodySmall)
        FelsongButton(stringResource(R.string.legion_uninstall), Modifier.fillMaxWidth(), primary = false, enabled = editable) { onDialog("uninstall") }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun BackgroundDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    val unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
    FelsongDialog(stringResource(R.string.legion_background), onDismiss) {
        Text(stringResource(R.string.legion_background_note))
        StatusRow(stringResource(R.string.legion_background_row), stringResource(if (unrestricted) R.string.legion_background_free else R.string.legion_background_optimized), unrestricted)
        if (!unrestricted) {
            FelsongButton(stringResource(R.string.legion_background_allow)) {
                val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                try {
                    context.startActivity(request)
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }
            }
        }
    }
}

/** The settings' names in the launcher. */
object LegionSettingNames {
    @Composable
    fun title(key: String): String = stringResource(
        when (key) {
            "Rate.XP.Kill" -> R.string.legion_setting_xp_kill
            "Rate.XP.Quest" -> R.string.legion_setting_xp_quest
            "Rate.XP.Explore" -> R.string.legion_setting_xp_explore
            "Rate.Drop.Money" -> R.string.legion_setting_money
            "Rate.Reputation.Gain" -> R.string.legion_setting_reputation
            "Rate.Honor" -> R.string.legion_setting_honor
            "StartPlayerLevel" -> R.string.legion_setting_level
            "StartPlayerMoney" -> R.string.legion_setting_start_money
            "Solocraft.Enable" -> R.string.legion_setting_solo
            else -> R.string.legion_setting_other
        }
    )
}
