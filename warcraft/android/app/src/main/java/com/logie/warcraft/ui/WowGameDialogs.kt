// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.logie.warcraft.R
import com.logie.warcraft.game.GameSettings

// Graphics ---------------------------------------------------------------------------------------

/** The game's resolution: the phone's screen, or fewer lines for speed. */
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
    }
}
