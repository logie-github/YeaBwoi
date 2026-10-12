// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.logie.warcraft.Addons
import com.logie.warcraft.R
import com.logie.warcraft.WowFiles
import com.logie.warcraft.game.GameSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

// Add-ons ----------------------------------------------------------------------------------------

/** The add-ons in the client: import zips, see what's there, delete. */
@Composable
fun AddonsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var addons by remember { mutableStateOf<List<Addons.Addon>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val hasClient = remember { WowFiles.hasClient(context) }

    LaunchedEffect(refresh) {
        addons = withContext(Dispatchers.IO) { runCatching { Addons.list(context) }.getOrDefault(emptyList()) }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Addons.install(context, uri) } }
            busy = false
            refresh++
            result.fold(
                onSuccess = { Toast.makeText(context, context.getString(R.string.wow_addons_installed, it.joinToString(", ")), Toast.LENGTH_LONG).show() },
                onFailure = {
                    val message = if (it is Addons.NoAddonException) context.getString(R.string.wow_addons_none_in_zip)
                    else context.getString(R.string.wow_addons_failed, it.message ?: it.javaClass.simpleName)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    WowDialog(
        title = stringResource(R.string.wow_addons_title),
        onDismiss = onDismiss,
        buttons = {
            if (hasClient) {
                WowButton(stringResource(R.string.wow_addons_import), enabled = !busy) {
                    pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                }
            }
            WowButton(stringResource(R.string.wow_close), primary = false, onClick = onDismiss)
        },
    ) {
        Text(stringResource(R.string.wow_addons_body), style = MaterialTheme.typography.bodySmall)
        if (!hasClient) {
            Text(stringResource(R.string.wow_addons_no_client), style = MaterialTheme.typography.bodyMedium)
            return@WowDialog
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WowSpinner(28.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.wow_addons_installing), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (addons.isEmpty() && !busy) {
            Text(stringResource(R.string.wow_addons_empty), style = MaterialTheme.typography.bodyMedium)
        }
        for (addon in addons) {
            val selected = editing == addon.folder
            WowChoiceRow(
                title = addon.title,
                summary = listOfNotNull(
                    addon.version,
                    if (addon.forThisGame) null else stringResource(R.string.wow_addons_other_version),
                ).joinToString(" · ").ifEmpty { null },
                selected = selected,
            ) {
                editing = if (selected) null else addon.folder
                confirmDelete = false
            }
            if (selected) {
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WowButton(
                        stringResource(if (confirmDelete) R.string.wow_addons_delete_confirm else R.string.wow_addons_delete),
                        primary = false,
                    ) {
                        if (!confirmDelete) {
                            confirmDelete = true
                        } else {
                            Addons.delete(context, addon.folder)
                            editing = null
                            confirmDelete = false
                            refresh++
                        }
                    }
                }
            }
        }
    }
}
