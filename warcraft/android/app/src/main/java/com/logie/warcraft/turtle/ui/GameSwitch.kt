// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.logie.warcraft.Environments
import com.logie.warcraft.R
import com.logie.warcraft.Server
import com.logie.warcraft.TitleMusic
import com.logie.warcraft.WowUpdateActivity
import com.logie.warcraft.WowUpdater
import com.logie.warcraft.turtle.TurtleServer
import com.logie.warcraft.ui.WowButton
import com.logie.warcraft.ui.WowChoiceRow
import com.logie.warcraft.ui.WowDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Switching the launcher between the games. Nothing of any game changes; only the realm that's
 * running may be stopped, when the user says so: ChromieCraft's and Turtle WoW's login servers use
 * the same port (the game's 3724), so only one of them runs at a time. Felsong has no realm on the
 * phone yet, so switching to or from it stops nothing.
 */
object GameSwitch {
    /** A realm that [target]'s realm can't run beside is running. */
    fun otherRunning(target: Environments.Environment): Boolean = when (target) {
        Environments.Environment.TURTLE -> Server.status.value.phase.let { it == Server.Phase.STARTING || it == Server.Phase.RUNNING || it == Server.Phase.STOPPING }
        Environments.Environment.WRATH -> TurtleServer.isBusy
        Environments.Environment.FELSONG -> false
    }

    fun switch(context: Context, target: Environments.Environment, stopOther: Boolean) {
        if (stopOther) {
            when (target) {
                Environments.Environment.TURTLE -> Server.stop(context)
                Environments.Environment.WRATH -> TurtleServer.stop(context)
                Environments.Environment.FELSONG -> {}
            }
        }
        Environments.select(context, target)
        TitleMusic.onGameChanged(context)
    }

    /** Switches now if the other realm isn't running; else returns false (ask first). */
    fun trySwitch(context: Context, target: Environments.Environment): Boolean {
        if (otherRunning(target)) return false
        switch(context, target, stopOther = false)
        return true
    }
}

/** The question when ChromieCraft's realm runs and the user picks Turtle WoW (in Wrath's look). */
@Composable
fun SwitchToTurtleDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    WowDialog(
        title = stringResource(R.string.turtle_switch_title),
        onDismiss = onDismiss,
        buttons = {
            WowButton(stringResource(R.string.turtle_cancel), primary = false, onClick = onDismiss)
            WowButton(stringResource(R.string.turtle_switch_keep), primary = false) { GameSwitch.switch(context, Environments.Environment.TURTLE, stopOther = false); onDismiss() }
            WowButton(stringResource(R.string.turtle_switch_stop)) { GameSwitch.switch(context, Environments.Environment.TURTLE, stopOther = true); onDismiss() }
        },
    ) {
        Text(stringResource(R.string.turtle_switch_to_turtle_body), style = MaterialTheme.typography.bodyMedium)
    }
}

/** The question when Turtle's realm runs and the user goes back to Wrath (in Turtle's look). */
@Composable
fun SwitchToWrathDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    TurtleDialog(
        title = stringResource(R.string.turtle_switch_title),
        onDismiss = onDismiss,
        buttons = {
            TurtleButton(stringResource(R.string.turtle_cancel), primary = false, onClick = onDismiss)
            TurtleButton(stringResource(R.string.turtle_switch_keep), primary = false) { GameSwitch.switch(context, Environments.Environment.WRATH, stopOther = false); onDismiss() }
            TurtleButton(stringResource(R.string.turtle_switch_stop)) { GameSwitch.switch(context, Environments.Environment.WRATH, stopOther = true); onDismiss() }
        },
    ) {
        Text(stringResource(R.string.turtle_switch_to_wrath_body), style = MaterialTheme.typography.bodyMedium)
    }
}

/** The other games, in Wrath's look: [onPick] gets the one chosen. */
@Composable
fun WrathGameChooser(onDismiss: () -> Unit, onPick: (Environments.Environment) -> Unit) {
    WowDialog(
        title = stringResource(R.string.games_title),
        onDismiss = onDismiss,
        buttons = { WowButton(stringResource(R.string.turtle_cancel), primary = false, onClick = onDismiss) },
    ) {
        WowChoiceRow(stringResource(R.string.turtle_tile_name), stringResource(R.string.turtle_tile_name_summary), selected = false) { onPick(Environments.Environment.TURTLE) }
        WowChoiceRow(stringResource(R.string.felsong_name), stringResource(R.string.felsong_name_summary), selected = false) { onPick(Environments.Environment.FELSONG) }
    }
}

/** The other games, in Turtle WoW's look. */
@Composable
fun TurtleGameChooser(onDismiss: () -> Unit, onPick: (Environments.Environment) -> Unit) {
    TurtleDialog(
        title = stringResource(R.string.games_title),
        onDismiss = onDismiss,
        buttons = { TurtleButton(stringResource(R.string.turtle_cancel), primary = false, onClick = onDismiss) },
    ) {
        TurtleChoiceRow(stringResource(R.string.wrath_name), stringResource(R.string.wrath_name_summary), selected = false) { onPick(Environments.Environment.WRATH) }
        TurtleChoiceRow(stringResource(R.string.felsong_name), stringResource(R.string.felsong_name_summary), selected = false) { onPick(Environments.Environment.FELSONG) }
    }
}

/**
 * The launcher in Turtle WoW's look: its home, the app's update check on opening (as Wrath's home
 * does), and the way to the other games.
 */
@Composable
fun TurtleLauncher(checkUpdateOnOpen: Boolean) {
    val context = LocalContext.current
    var asking by remember { mutableStateOf(false) }
    var checkUpdate by rememberSaveable { mutableStateOf(checkUpdateOnOpen) }
    val updateOffer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { checkUpdate = false }
    LaunchedEffect(Unit) {
        if (!checkUpdate) return@LaunchedEffect
        val update = if (WowUpdater.isConfigured) withContext(Dispatchers.IO) { runCatching { WowUpdater.findUpdate() }.getOrNull() } else null
        if (update != null) updateOffer.launch(WowUpdateActivity.intent(context, update)) else checkUpdate = false
    }
    var choosing by remember { mutableStateOf(false) }
    TurtleHomeRoute(onSwitchGame = { choosing = true })
    if (choosing) TurtleTheme {
        TurtleGameChooser(onDismiss = { choosing = false }) { target ->
            choosing = false
            if (!GameSwitch.trySwitch(context, target)) asking = true
        }
    }
    if (asking) TurtleTheme { SwitchToWrathDialog(onDismiss = { asking = false }) }
}
