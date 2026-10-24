// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.logie.warcraft.BuildConfig
import com.logie.warcraft.R
import com.logie.warcraft.WowUpdateActivity
import com.logie.warcraft.felsong.FelsongFiles
import com.logie.warcraft.felsong.FelsongGame
import com.logie.warcraft.felsong.FelsongJobs
import com.logie.warcraft.felsong.FelsongLogin
import com.logie.warcraft.felsong.FelsongPackage
import com.logie.warcraft.felsong.FelsongServer
import com.logie.warcraft.felsong.FelsongService
import com.logie.warcraft.felsong.FelsongSettings
import com.logie.warcraft.felsong.FelsongTexts
import com.logie.warcraft.felsong.core.FelsongInstallPlan
import com.logie.warcraft.felsong.core.FelsongInstallState
import com.logie.warcraft.felsong.core.FelsongRealm
import com.logie.warcraft.game.GameSettings

/**
 * The launcher when Legion is the chosen game: one panel with where the setup stands (the numbered
 * steps from nothing to Play, [FelsongInstallPlan]) and the one thing to do next, then the tiles that
 * open the realm, the game's files, the accounts, the settings, the graphics and the upkeep
 * ([LegionDialogs]). Everything it starts is Legion's own (com.logie.warcraft.felsong): nothing here
 * reads or changes the other games' files or realms.
 */
@Composable
fun LegionHomeRoute(onSwitchGame: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val server by FelsongServer.status.collectAsState()
    val job by FelsongJobs.state.collectAsState()
    val game by FelsongGame.state.collectAsState()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val facts = remember(refresh, job, server.phase) { LegionFacts.of(context) }

    // How a long job went, as soon as it's done.
    LaunchedEffect(job) {
        if (job is FelsongJobs.State.Done || job is FelsongJobs.State.Failed) dialog = LegionDialog.RESULT
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { refresh++ }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        FelsongBackdrop()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FelsongHeader(width, height, onSwitchGame)
            Column(Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LegionPanel(facts, server, job, game, onDialog = { dialog = it })
                val tiles = listOf(
                    LegionTile(Icons.Default.Dns, stringResource(R.string.legion_tile_realm), FelsongTexts.server(context, server)) { dialog = LegionDialog.REALM },
                    LegionTile(Icons.Default.FolderOpen, stringResource(R.string.legion_tile_files), filesSummary(facts)) { dialog = LegionDialog.FILES },
                    LegionTile(Icons.Default.AccountCircle, stringResource(R.string.legion_tile_accounts), facts.savedAccount ?: stringResource(R.string.legion_tile_accounts_none)) { dialog = LegionDialog.ACCOUNTS },
                    LegionTile(Icons.Default.Settings, stringResource(R.string.legion_tile_settings), stringResource(R.string.legion_tile_settings_summary)) { dialog = LegionDialog.SETTINGS },
                    LegionTile(Icons.Default.AspectRatio, stringResource(R.string.legion_tile_graphics), graphicsSummary(context)) { dialog = LegionDialog.GRAPHICS },
                    LegionTile(Icons.Default.Build, stringResource(R.string.legion_tile_upkeep), stringResource(R.string.legion_tile_upkeep_summary)) { dialog = LegionDialog.UPKEEP },
                    LegionTile(Icons.Default.Palette, stringResource(R.string.wow_tile_icon), stringResource(R.string.legion_tile_icon_summary)) { dialog = LegionDialog.ICON },
                    LegionTile(Icons.Default.SystemUpdateAlt, stringResource(R.string.wow_tile_updates), stringResource(R.string.wow_tile_updates_summary, BuildConfig.VERSION_NAME)) { WowUpdateActivity.launch(context) },
                    LegionTile(Icons.Default.SwapHoriz, stringResource(R.string.games_tile), stringResource(R.string.legion_tile_games_summary), onSwitchGame),
                )
                val columns = if (width >= 760.dp) 3 else if (width >= 480.dp) 2 else 1
                tiles.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { tile ->
                            FelsongWindow(Modifier.weight(1f).heightIn(min = 110.dp), onClick = tile.click) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(tile.icon, null, tint = FelsongColors.Fel)
                                    Text(tile.title.uppercase(), style = MaterialTheme.typography.titleMedium)
                                    Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                                }
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text(
                    stringResource(R.string.legion_version, BuildConfig.VERSION_NAME, facts.version ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
    }
    LegionDialogs(dialog, facts, server, job, onDialog = { dialog = it }, onChanged = { refresh++ })
}

/** The screens the tiles and the panel open. */
object LegionDialog {
    const val REALM = "realm"
    const val FILES = "files"
    const val ACCOUNTS = "accounts"
    const val SETTINGS = "settings"
    const val GRAPHICS = "graphics"
    const val UPKEEP = "upkeep"
    const val ICON = "icon"
    const val RESULT = "result"
}

private class LegionTile(val icon: ImageVector, val title: String, val subtitle: String, val click: () -> Unit)

/** What's on the phone for Legion, read when the screen needs it. */
data class LegionFacts(
    val blocker: String?,
    val pin: FelsongPackage.Pin?,
    val serverInstalled: Boolean,
    val serverCurrent: Boolean,
    val databases: Boolean,
    val client: Boolean,
    val state: FelsongInstallState,
    val dataParts: Set<String>,
    val data: Boolean,
    val dataCurrent: Boolean,
    val patched: Boolean,
    val accounts: Int,
    val savedAccount: String?,
    val used: Long,
    val free: Long,
    val version: String?,
) {
    val plan: List<FelsongInstallPlan.Line> = FelsongInstallPlan.of(
        FelsongInstallPlan.Facts(
            blocker = blocker,
            serverInstalled = serverInstalled,
            serverCurrent = serverCurrent,
            databasesThere = databases,
            clientThere = client,
            dataThere = data,
            dataMatchesClient = dataCurrent,
            clientPatched = patched,
            hasAccount = when {
                savedAccount != null || accounts > 0 -> true
                accounts == 0 -> false
                else -> null
            },
        )
    )
    val ready: Boolean get() = FelsongInstallPlan.ready(plan)

    companion object {
        fun of(context: Context): LegionFacts {
            val state = FelsongFiles.state(context)
            val data = FelsongFiles.hasData(context)
            return LegionFacts(
                blocker = FelsongFiles.blocker(context),
                pin = FelsongPackage.pinned,
                serverInstalled = FelsongPackage.isInstalled(context),
                serverCurrent = FelsongPackage.isCurrent(context),
                databases = FelsongFiles.hasDatabases(context),
                client = FelsongFiles.hasClient(context),
                state = state,
                dataParts = FelsongFiles.dataParts(context),
                data = data,
                dataCurrent = data && (state.dataFrom == null || state.dataFrom == state.clientExeSha256),
                patched = FelsongFiles.isPatched(context),
                accounts = com.logie.warcraft.felsong.FelsongSettings.knownAccounts(context),
                savedAccount = FelsongLogin.get(context)?.name,
                used = runCatching { FelsongFiles.used(context) }.getOrDefault(0L),
                free = FelsongFiles.freeSpace(context),
                version = FelsongPackage.version(context)?.lines()?.firstOrNull { it.startsWith("legioncore ") }?.removePrefix("legioncore ")?.take(7),
            )
        }
    }
}

@Composable
private fun filesSummary(facts: LegionFacts): String = stringResource(
    when {
        facts.client && facts.data -> R.string.legion_tile_files_all
        facts.client -> R.string.legion_tile_files_client
        else -> R.string.legion_tile_files_none
    }
)

@Composable
private fun graphicsSummary(context: Context): String {
    val resolution = GameSettings.resolution(context)
    val (width, height) = GameSettings.gameSize(context, resolution)
    return stringResource(R.string.wow_tile_graphics_summary, stringResource(resolution.label), width, height)
}

/** Where the setup stands, and the one thing to do next (or what is under way). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegionPanel(
    facts: LegionFacts,
    server: FelsongServer.Status,
    job: FelsongJobs.State,
    game: FelsongGame.State,
    onDialog: (String) -> Unit,
) {
    val context = LocalContext.current
    FelsongWindow(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FelsongTitle(stringResource(R.string.felsong_name))
            Text(stringResource(R.string.felsong_name_summary), color = FelsongColors.Sage, style = MaterialTheme.typography.bodySmall)
            when {
                facts.blocker != null -> Text(
                    stringResource(if (facts.blocker == "arm64") R.string.felsong_error_not_arm64 else R.string.felsong_error_blocker),
                    color = FelsongColors.Ember,
                )
                job is FelsongJobs.State.Working -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FelsongSpinner(36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(FelsongTexts.step(context, job.step), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    val progress = job.progress
                    if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    job.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                    if (job.kind == FelsongJobs.Kind.DOWNLOAD_CLIENT) Text(stringResource(R.string.legion_download_keeps), style = MaterialTheme.typography.bodySmall)
                    FelsongButton(stringResource(R.string.felsong_cancel), primary = false) { FelsongJobs.cancel() }
                }
                game is FelsongGame.State.Preparing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FelsongSpinner(36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(
                                when (game.step) {
                                    FelsongGame.Step.RUNTIME -> R.string.legion_game_step_runtime
                                    FelsongGame.Step.WINDOWS -> R.string.legion_game_step_windows
                                    FelsongGame.Step.REALM -> R.string.legion_game_step_realm
                                    FelsongGame.Step.STARTING -> R.string.legion_game_step_starting
                                }
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    game.progress?.let { LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                    Text(FelsongTexts.server(context, server), style = MaterialTheme.typography.bodySmall)
                    server.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                    if (server.step == FelsongServer.Step.WORLD && !facts.state.databaseUpdated) {
                        Text(stringResource(R.string.legion_first_start), style = MaterialTheme.typography.bodySmall)
                    }
                }
                game is FelsongGame.State.Failed -> {
                    Text(stringResource(R.string.legion_game_failed), style = MaterialTheme.typography.titleMedium, color = FelsongColors.Ember)
                    Text(game.message, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FelsongButton(stringResource(R.string.turtle_close), primary = false) { FelsongGame.dismiss() }
                        if (game.needsAccount) FelsongButton(stringResource(R.string.legion_tile_accounts)) { FelsongGame.dismiss(); onDialog(LegionDialog.ACCOUNTS) }
                        else FelsongButton(stringResource(R.string.wow_server_retry)) { FelsongGame.dismiss(); FelsongGame.play(context) }
                    }
                }
                server.phase == FelsongServer.Phase.STARTING || server.phase == FelsongServer.Phase.STOPPING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FelsongSpinner(36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(FelsongTexts.server(context, server), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    server.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                    if (server.step == FelsongServer.Step.WORLD && !facts.state.databaseUpdated) {
                        Text(stringResource(R.string.legion_first_start), style = MaterialTheme.typography.bodySmall)
                    }
                }
                server.phase == FelsongServer.Phase.FAILED -> {
                    Text(stringResource(R.string.legion_realm_failed), style = MaterialTheme.typography.titleMedium, color = FelsongColors.Ember)
                    Text(server.error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FelsongButton(stringResource(R.string.legion_details), primary = false) { onDialog(LegionDialog.REALM) }
                        FelsongButton(stringResource(R.string.wow_server_retry)) { FelsongServer.dismiss(); FelsongServer.start(context) }
                    }
                }
                else -> Setup(facts, server, onDialog)
            }
        }
    }
}

/** The numbered steps and the button for the next one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setup(facts: LegionFacts, server: FelsongServer.Status, onDialog: (String) -> Unit) {
    val context = LocalContext.current
    for (line in facts.plan) {
        if (line.step == FelsongInstallPlan.Step.CHECK || line.step == FelsongInstallPlan.Step.READY) continue
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${FelsongInstallPlan.number(line.step)}.", color = FelsongColors.Sage, modifier = Modifier.width(26.dp))
            Text(LegionTexts.step(line.step), modifier = Modifier.weight(1f))
            Text(
                LegionTexts.status(line.status),
                color = when (line.status) {
                    FelsongInstallPlan.Status.DONE -> FelsongColors.Pale
                    FelsongInstallPlan.Status.NEEDS_USER -> FelsongColors.Ember
                    else -> FelsongColors.Sage
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    val next = FelsongInstallPlan.next(facts.plan)
    val free = Formatter.formatShortFileSize(context, facts.free)
    when {
        FelsongInstallPlan.ready(facts.plan) -> {
            if (server.phase == FelsongServer.Phase.RUNNING) {
                Text(stringResource(R.string.legion_realm_online, FelsongRealm.REALM_NAME), color = FelsongColors.Pale)
                if (server.lanAddress != null) Text(stringResource(R.string.legion_realm_lan, server.lanAddress), style = MaterialTheme.typography.bodySmall)
            }
            FelsongButton(stringResource(R.string.wow_home_press_start), Modifier.fillMaxWidth()) { FelsongGame.play(context) }
            Text(stringResource(R.string.legion_ready_note), style = MaterialTheme.typography.bodySmall)
            if (server.phase == FelsongServer.Phase.RUNNING) {
                FelsongButton(stringResource(R.string.wow_server_stop), primary = false) { FelsongServer.stop(context) }
            }
        }
        next == null -> {}
        next.step == FelsongInstallPlan.Step.SERVER || next.step == FelsongInstallPlan.Step.DATABASE -> {
            val pin = facts.pin
            if (pin == null) {
                Text(stringResource(R.string.felsong_error_not_pinned), color = FelsongColors.Ember)
            } else {
                Text(stringResource(R.string.legion_install_note, Formatter.formatShortFileSize(context, pin.size), free), style = MaterialTheme.typography.bodySmall)
                FelsongButton(stringResource(R.string.legion_install_realm), Modifier.fillMaxWidth()) {
                    FelsongService.job(context, FelsongJobs.Kind.INSTALL, options = FelsongJobs.Options(withMmaps = FelsongSettings.mmaps(context)))
                }
            }
        }
        next.step == FelsongInstallPlan.Step.CLIENT -> {
            Text(stringResource(R.string.legion_client_note, free), style = MaterialTheme.typography.bodySmall)
            FelsongButton(stringResource(R.string.legion_download_client), Modifier.fillMaxWidth()) {
                FelsongService.job(context, FelsongJobs.Kind.DOWNLOAD_CLIENT, options = FelsongJobs.Options(withMmaps = FelsongSettings.mmaps(context)))
            }
            FelsongButton(stringResource(R.string.legion_use_own_client), Modifier.fillMaxWidth(), primary = false) { onDialog(LegionDialog.FILES) }
        }
        next.step == FelsongInstallPlan.Step.DATA -> {
            Text(stringResource(R.string.legion_data_note), style = MaterialTheme.typography.bodySmall)
            FelsongButton(stringResource(R.string.legion_make_data), Modifier.fillMaxWidth()) {
                FelsongService.job(context, FelsongJobs.Kind.DATA, options = FelsongJobs.Options(withMmaps = FelsongSettings.mmaps(context)))
            }
        }
        next.step == FelsongInstallPlan.Step.PATCH -> {
            FelsongButton(stringResource(R.string.legion_patch_client), Modifier.fillMaxWidth()) {
                FelsongService.job(context, FelsongJobs.Kind.PATCH)
            }
        }
        next.step == FelsongInstallPlan.Step.ACCOUNT -> {
            Text(stringResource(R.string.legion_account_note), style = MaterialTheme.typography.bodySmall)
            FelsongButton(stringResource(R.string.legion_make_account), Modifier.fillMaxWidth()) { onDialog(LegionDialog.ACCOUNTS) }
        }
        else -> {}
    }
}
