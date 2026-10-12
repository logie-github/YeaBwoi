// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.logie.warcraft.ClientMods
import com.logie.warcraft.CustomRaces
import com.logie.warcraft.ImportService
import com.logie.warcraft.Imports
import com.logie.warcraft.R
import com.logie.warcraft.WowFiles
import com.logie.warcraft.client.HdPatches
import com.logie.warcraft.game.GameSettings

// Graphics ---------------------------------------------------------------------------------------

/** The game's resolution: the phone's screen, or fewer lines for speed. And its HD look. */
@Composable
fun GraphicsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(GameSettings.resolution(context)) }
    WowDialog(title = stringResource(R.string.wow_graphics_title), onDismiss = onDismiss) {
        Text(stringResource(R.string.wow_graphics_body), style = MaterialTheme.typography.bodySmall)
        for (resolution in GameSettings.Resolution.entries) {
            val (width, height) = GameSettings.gameSize(context, resolution)
            WowChoiceRow(
                title = stringResource(resolution.label),
                summary = stringResource(R.string.wow_graphics_size, stringResource(resolution.summary), width, height),
                selected = resolution == current,
            ) {
                GameSettings.setResolution(context, resolution)
                current = resolution
            }
        }
        HdLook()
    }
}

/**
 * The HD look: off, ChromieCraft's HD patch, or the alternative one ([HdPatches]). Picking one
 * downloads its files (or takes them out) in the background ([ClientMods]).
 */
@Composable
private fun HdLook() {
    val context = LocalContext.current
    val imports by Imports.state.collectAsState()
    var chosen by remember { mutableStateOf(ClientMods.hd(context)) }
    val installed = remember(imports) { ClientMods.hdInstalled(context) }
    val races = remember(imports) { CustomRaces.isInstalled(context) || CustomRaces.isWanted(context) }
    val hasClient = remember { WowFiles.hasClient(context) }
    val working = imports is Imports.State.Working
    val modsWorking = (imports as? Imports.State.Working)?.kind == Imports.Kind.MODS

    WowTitle(stringResource(R.string.wow_hd_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.wow_hd_body), style = MaterialTheme.typography.bodySmall)
    if (races) Text(stringResource(R.string.wow_hd_races_note), style = MaterialTheme.typography.bodySmall, color = WowColors.Gold)
    if (!hasClient) {
        Text(stringResource(R.string.wow_mods_error_no_client), style = MaterialTheme.typography.bodyMedium)
        return
    }
    for (choice in HdPatches.Choice.entries) {
        val (title, summary) = when (choice) {
            HdPatches.Choice.OFF -> R.string.wow_hd_off to R.string.wow_hd_off_summary
            HdPatches.Choice.CHROMIECRAFT -> R.string.wow_hd_chromiecraft to
                if (races) R.string.wow_hd_chromiecraft_summary_races else R.string.wow_hd_chromiecraft_summary
            HdPatches.Choice.ALTERNATIVE -> R.string.wow_hd_alternative to
                if (races) R.string.wow_hd_alternative_summary_races else R.string.wow_hd_alternative_summary
        }
        WowChoiceRow(stringResource(title), stringResource(summary), selected = choice == chosen) {
            if (working || choice == chosen) return@WowChoiceRow
            chosen = choice
            ClientMods.setHd(context, choice)
            Imports.clearResult()
            ImportService.applyMods(context)
        }
    }
    when {
        modsWorking -> Row(verticalAlignment = Alignment.CenterVertically) {
            WowSpinner(24.dp)
            Spacer(Modifier.width(10.dp))
            val state = imports as Imports.State.Working
            Text(state.detail ?: importTitle(state), style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        imports is Imports.State.Failed && (imports as Imports.State.Failed).kind == Imports.Kind.MODS -> {
            Text((imports as Imports.State.Failed).message, style = MaterialTheme.typography.bodyMedium, color = WowColors.Blood)
            WowButton(stringResource(R.string.wow_hd_retry), primary = false) {
                Imports.clearResult()
                ImportService.applyMods(context)
            }
        }
        chosen != installed -> {
            Text(stringResource(R.string.wow_hd_not_applied), style = MaterialTheme.typography.bodySmall)
            if (!working) WowButton(stringResource(R.string.wow_hd_retry), primary = false) { ImportService.applyMods(context) }
        }
        installed != HdPatches.Choice.OFF ->
            Text(stringResource(R.string.wow_hd_in), style = MaterialTheme.typography.bodySmall, color = WowColors.Good)
    }
}
