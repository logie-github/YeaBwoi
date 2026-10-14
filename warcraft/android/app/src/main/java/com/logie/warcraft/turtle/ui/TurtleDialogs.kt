// SPDX-License-Identifier: GPL-3.0-or-later

@file:OptIn(ExperimentalLayoutApi::class)

package com.logie.warcraft.turtle.ui

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.logie.warcraft.BuildConfig
import com.logie.warcraft.R
import com.logie.warcraft.WowFiles
import com.logie.warcraft.WowUpdateActivity
import com.logie.warcraft.turtle.TurtleAccounts
import com.logie.warcraft.turtle.TurtleFiles
import com.logie.warcraft.turtle.TurtleJobs
import com.logie.warcraft.turtle.TurtleLogin
import com.logie.warcraft.turtle.TurtlePackage
import com.logie.warcraft.turtle.TurtleServer
import com.logie.warcraft.turtle.TurtleService
import com.logie.warcraft.turtle.TurtleSettings
import com.logie.warcraft.turtle.TurtleTexts
import com.logie.warcraft.turtle.core.TurtleAccountRules
import com.logie.warcraft.turtle.core.TurtleRealm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun TurtleDialogs(dialog: TurtleDialogKind?, facts: TurtleFacts, actions: TurtleActions, onDismiss: () -> Unit) {
    when (dialog) {
        TurtleDialogKind.INSTALL -> InstallDialog(facts, onDismiss)
        TurtleDialogKind.REALM -> RealmDialog(onDismiss)
        TurtleDialogKind.ACCOUNTS -> AccountsDialog(onDismiss)
        TurtleDialogKind.SETTINGS -> SettingsDialog(onDismiss)
        TurtleDialogKind.UPDATES -> UpdatesDialog(facts, actions, onDismiss)
        TurtleDialogKind.REPAIR -> RepairDialog(onDismiss)
        TurtleDialogKind.UNINSTALL -> UninstallDialog(facts, onDismiss)
        TurtleDialogKind.ABOUT -> AboutDialog(onDismiss)
        null -> {}
    }
}

/** Install Turtle WoW: an account to make on the way (optional), and whether to make pathfinding. */
@Composable
private fun InstallDialog(facts: TurtleFacts, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mmaps by remember { mutableStateOf(TurtleSettings.mmaps(context)) }
    val nameOk = name.isEmpty() || TurtleAccountRules.normalizeName(name) != null
    val passwordOk = name.isEmpty() || TurtleAccountRules.isPassword(password)
    TurtleDialog(
        title = stringResource(R.string.turtle_install_title),
        onDismiss = onDismiss,
        buttons = {
            TurtleButton(stringResource(R.string.turtle_cancel), primary = false, onClick = onDismiss)
            TurtleButton(stringResource(R.string.turtle_install), enabled = nameOk && passwordOk && facts.pin != null) {
                TurtleSettings.setMmaps(context, mmaps)
                TurtleService.job(
                    context, TurtleJobs.Kind.INSTALL,
                    options = TurtleJobs.Options(
                        withMmaps = mmaps,
                        accountName = name.takeIf { it.isNotEmpty() },
                        accountPassword = password.takeIf { name.isNotEmpty() },
                    ),
                )
                onDismiss()
            }
        },
    ) {
        Text(stringResource(R.string.turtle_install_steps), style = MaterialTheme.typography.bodyMedium)
        facts.pin?.let {
            Text(
                stringResource(R.string.turtle_install_space, Formatter.formatShortFileSize(context, it.size), Formatter.formatShortFileSize(context, it.unpacked), Formatter.formatShortFileSize(context, facts.free)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(stringResource(R.string.turtle_install_account), style = MaterialTheme.typography.titleMedium)
        TurtleTextField(name, { name = it.take(16) }, stringResource(R.string.turtle_account_name))
        TurtleTextField(password, { password = it.take(16) }, stringResource(R.string.turtle_account_password), password = true)
        if (!nameOk || !passwordOk) Text(stringResource(R.string.turtle_account_rules), style = MaterialTheme.typography.bodySmall, color = TurtleColors.Blood)
        TurtleSwitchRow(stringResource(R.string.turtle_install_mmaps), stringResource(R.string.turtle_install_mmaps_summary), mmaps) { mmaps = it }
        Text(stringResource(R.string.turtle_install_isolation), style = MaterialTheme.typography.bodySmall)
    }
}

/** The realm: how it is, start and stop, its logs, PCs on the Wi-Fi. */
@Composable
private fun RealmDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val server by TurtleServer.status.collectAsState()
    var log by remember { mutableStateOf("mangosd.out") }
    var refresh by remember { mutableIntStateOf(0) }
    val logs = listOf("mangosd.out", "realmd.out", "mariadb.log", "maps.log", "vmaps.log", "mmaps.log", "configure.log")
    val text = remember(log, refresh, server) { WowFiles.tail(File(TurtleFiles.layout(context).logs, log), 40) }
    TurtleDialog(
        title = stringResource(R.string.turtle_tile_realm),
        onDismiss = onDismiss,
        buttons = {
            TurtleButton(stringResource(R.string.turtle_close), primary = false, onClick = onDismiss)
            if (TurtleServer.isBusy) TurtleButton(stringResource(R.string.turtle_stop_realm)) { TurtleServer.stop(context) }
            else TurtleButton(stringResource(R.string.turtle_start_realm), enabled = TurtlePackage.isInstalled(context)) { TurtleServer.start(context) }
        },
    ) {
        TurtleStatusLine(stringResource(R.string.turtle_realm_state), TurtleTexts.server(context, server), good = if (server.phase == TurtleServer.Phase.FAILED) false else if (server.worldUp) true else null)
        server.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = TurtleColors.Blood) }
        TurtleStatusLine(stringResource(R.string.turtle_realm_ports), "${TurtleRealm.REALMD_PORT}, ${TurtleRealm.WORLD_PORT}, ${TurtleRealm.DB_PORT}")
        TurtleStatusLine(stringResource(R.string.turtle_realm_folder), TurtleFiles.layout(context).root.path)
        var lan by remember { mutableStateOf(TurtleSettings.lan(context)) }
        TurtleSwitchRow(stringResource(R.string.turtle_lan), stringResource(R.string.turtle_lan_summary), lan) {
            lan = it
            TurtleSettings.setLan(context, it)
        }
        Text(stringResource(R.string.turtle_realm_logs), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (name in logs) TurtleButton(name.substringBefore('.'), primary = name == log) { log = name; refresh++ }
        }
        TurtleLog(text.ifBlank { stringResource(R.string.turtle_log_empty) })
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
private fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    TurtleDialog(title = stringResource(R.string.turtle_tile_settings), onDismiss = onDismiss, buttons = {
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

/**
 * Updates: the realm package this version of the app is made for (Tortoise-WoW at a pinned
 * commit, for the 1.18.1 client only), and the app's own updates (which bring newer realm pins).
 * Turtle WoW's client has no updates any more: 1.18.1 with its 2026-04-12 hotfixes was the last.
 */
@Composable
private fun UpdatesDialog(facts: TurtleFacts, actions: TurtleActions, onDismiss: () -> Unit) {
    val context = LocalContext.current
    TurtleDialog(title = stringResource(R.string.turtle_tile_updates), onDismiss = onDismiss, buttons = {
        TurtleButton(stringResource(R.string.turtle_close), primary = false, onClick = onDismiss)
        TurtleButton(stringResource(R.string.turtle_check_app)) { WowUpdateActivity.launch(context) }
    }) {
        TurtleStatusLine(stringResource(R.string.turtle_updates_app), BuildConfig.VERSION_NAME)
        TurtleStatusLine(stringResource(R.string.turtle_updates_realm), facts.version ?: stringResource(R.string.turtle_part_missing), good = facts.programsCurrent)
        TurtleStatusLine(stringResource(R.string.turtle_updates_pinned), facts.pin?.tortoise?.take(7) ?: "—")
        TurtleStatusLine(stringResource(R.string.turtle_updates_client), stringResource(R.string.turtle_updates_client_value))
        Text(stringResource(R.string.turtle_updates_body), style = MaterialTheme.typography.bodySmall)
        if (facts.programsInstalled && !facts.programsCurrent) {
            Text(stringResource(R.string.turtle_updates_realm_ready), style = MaterialTheme.typography.bodyMedium)
            TurtleButton(stringResource(R.string.turtle_update_realm), modifier = Modifier.align(Alignment.End), enabled = facts.pin != null && !TurtleServer.isBusy) {
                actions.job(TurtleJobs.Kind.PROGRAMS)
                onDismiss()
            }
        } else if (facts.programsCurrent) {
            Text(stringResource(R.string.turtle_updates_up_to_date), style = MaterialTheme.typography.bodyMedium, color = TurtleColors.Leaf)
        }
    }
}

/** Repair: each part checked or put back by itself; backups of the accounts and characters. */
@Composable
private fun RepairDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf(setOf(TurtleJobs.Repair.PROGRAMS)) }
    var resetConfirm by remember { mutableStateOf(false) }
    val backups = remember { TurtleJobs.backups(context) }
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
        TurtleTitle(stringResource(R.string.turtle_backups), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.turtle_backups_body), style = MaterialTheme.typography.bodySmall)
        TurtleButton(stringResource(R.string.turtle_backup_now), primary = false, modifier = Modifier.align(Alignment.End)) {
            TurtleService.job(context, TurtleJobs.Kind.BACKUP)
            onDismiss()
        }
        for (file in backups) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(file.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TurtleButton(stringResource(R.string.turtle_restore), primary = false, enabled = !TurtleServer.isBusy) {
                    TurtleService.job(context, TurtleJobs.Kind.RESTORE, options = TurtleJobs.Options(restoreFile = file.name))
                    onDismiss()
                }
            }
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

/** What this is, where it comes from, and what it can't do. */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    TurtleDialog(title = stringResource(R.string.turtle_tile_about), onDismiss = onDismiss, buttons = { TurtleButton(stringResource(R.string.turtle_close), onClick = onDismiss) }) {
        Text(stringResource(R.string.turtle_about_body), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.turtle_about_client), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.turtle_about_bots), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.turtle_about_isolation), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.turtle_about_legal), style = MaterialTheme.typography.bodySmall)
    }
}
