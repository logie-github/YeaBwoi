// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.logie.warcraft.R
import com.logie.warcraft.WowFiles
import com.logie.warcraft.client.BaseModelFiles
import com.logie.warcraft.client.RaliaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ZIP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

/**
 * Game options: Ralia's mod, on or off ([RaliaFiles]; the realm's mod-ralia makes the game's patch as it
 * starts; turning it on the first time asks for the zip of Ralia's patches, whose textures the app keeps),
 * and Base Model ([BaseModelFiles]; the realm's mod-base-model draws every character without its gear,
 * within seconds while the realm runs).
 */
@Composable
fun GameOptionsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember { WowFiles.root(context) }
    var on by remember { mutableStateOf(RaliaFiles.isOn(root)) }
    var hasFiles by remember { mutableStateOf(RaliaFiles.hasFiles(root)) }
    var baseModel by remember { mutableStateOf(BaseModelFiles.isOn(root)) }
    var reading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        problem = null
        scope.launch {
            val result = runCatching { readZip(context, uri) }
            reading = false
            result.fold(
                onSuccess = {
                    hasFiles = true
                    on = runCatching { RaliaFiles.setOn(root, WowFiles.client(context), true) }.isSuccess
                },
                onFailure = {
                    problem = if (it is RaliaFiles.NoTexturesException) context.getString(R.string.wow_options_ralia_no_textures)
                    else context.getString(R.string.wow_options_ralia_failed, it.message ?: it.javaClass.simpleName)
                },
            )
        }
    }

    WowDialog(title = stringResource(R.string.wow_options_title), onDismiss = onDismiss) {
        Text(stringResource(R.string.wow_options_body), style = MaterialTheme.typography.bodySmall)
        WowSwitchRow(stringResource(R.string.wow_options_ralia), null, on, enabled = !reading) { want ->
            problem = null
            if (want && !hasFiles) {
                pick.launch(ZIP_TYPES)
                return@WowSwitchRow
            }
            on = runCatching { RaliaFiles.setOn(root, WowFiles.client(context), want) }.isSuccess && want
        }
        when {
            reading -> Row(verticalAlignment = Alignment.CenterVertically) {
                WowSpinner(24.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.wow_options_ralia_reading), style = MaterialTheme.typography.bodySmall)
            }
            !hasFiles -> Text(stringResource(R.string.wow_options_ralia_pick), style = MaterialTheme.typography.bodySmall)
            else -> WowButton(stringResource(R.string.wow_options_ralia_replace), primary = false) { pick.launch(ZIP_TYPES) }
        }
        problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = WowColors.Blood) }
        WowSwitchRow(stringResource(R.string.wow_options_base_model), null, baseModel) { want ->
            baseModel = runCatching { BaseModelFiles.setOn(root, want) }.isSuccess && want
        }
    }
}

private suspend fun readZip(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
    val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("the zip couldn't be opened")
    input.use { RaliaFiles.unpack(WowFiles.root(context), it) }
}

/** The Game options tile's line: the options that are on. */
fun gameOptionsSummary(context: Context): String {
    val root = WowFiles.root(context)
    val on = listOfNotNull(
        context.getString(R.string.wow_options_ralia).takeIf { RaliaFiles.isOn(root) },
        context.getString(R.string.wow_options_base_model).takeIf { BaseModelFiles.isOn(root) },
    )
    return if (on.isEmpty()) context.getString(R.string.wow_tile_game_options_off) else on.joinToString(", ")
}
