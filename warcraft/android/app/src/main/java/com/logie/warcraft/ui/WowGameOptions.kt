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
import com.logie.warcraft.R
import com.logie.warcraft.WowFiles
import com.logie.warcraft.client.BaseModelFiles
import com.logie.warcraft.client.AlternateFemaleFiles
import com.logie.warcraft.client.RaliaFiles
import com.logie.warcraft.client.TextureSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ZIP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

/**
 * Game options: the character textures, Ralia's mod ([RaliaFiles]) or Alternate Female Options
 * ([AlternateFemaleFiles]), one on at a time (the realm's mod-ralia makes the game's patch as it starts;
 * turning one on the first time asks for its zip, whose textures the app keeps), and Base Model
 * ([BaseModelFiles]; the realm's mod-base-model draws every character without its gear, within seconds
 * while the realm runs).
 */
@Composable
fun GameOptionsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val root = remember { WowFiles.root(context) }
    var baseModel by remember { mutableStateOf(BaseModelFiles.isOn(root)) }
    // (turning one set on turns the other off: both read again)
    var changed by remember { mutableIntStateOf(0) }

    WowDialog(title = stringResource(R.string.wow_options_title), onDismiss = onDismiss) {
        Text(stringResource(R.string.wow_options_body), style = MaterialTheme.typography.bodySmall)
        TextureSetOption(RaliaFiles, stringResource(R.string.wow_options_ralia), changed) { changed++ }
        TextureSetOption(AlternateFemaleFiles, stringResource(R.string.wow_options_alternate_female), changed) { changed++ }
        WowSwitchRow(stringResource(R.string.wow_options_base_model), null, baseModel) { want ->
            baseModel = runCatching { BaseModelFiles.setOn(root, want) }.isSuccess && want
        }
    }
}

/** One set of character textures: its switch, its zip (asked for the first time it's turned on), and how reading it went. */
@Composable
private fun TextureSetOption(set: TextureSet, title: String, changed: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember { WowFiles.root(context) }
    val on = remember(changed) { set.isOn(root) }
    var hasFiles by remember { mutableStateOf(set.hasFiles(root)) }
    var reading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        problem = null
        scope.launch {
            val result = runCatching { readZip(context, set, uri) }
            reading = false
            result.fold(
                onSuccess = {
                    hasFiles = true
                    runCatching { set.setOn(root, WowFiles.client(context), true) }
                    onChanged()
                },
                onFailure = {
                    problem = if (it is TextureSet.NoTexturesException) context.getString(R.string.wow_options_ralia_no_textures)
                    else context.getString(R.string.wow_options_ralia_failed, it.message ?: it.javaClass.simpleName)
                },
            )
        }
    }

    WowSwitchRow(title, null, on, enabled = !reading) { want ->
        problem = null
        if (want && !hasFiles) {
            pick.launch(ZIP_TYPES)
            return@WowSwitchRow
        }
        runCatching { set.setOn(root, WowFiles.client(context), want) }
        onChanged()
    }
    when {
        reading -> Row(verticalAlignment = Alignment.CenterVertically) {
            WowSpinner(24.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.wow_options_ralia_reading), style = MaterialTheme.typography.bodySmall)
        }
        !hasFiles -> Text(stringResource(R.string.wow_options_set_pick), style = MaterialTheme.typography.bodySmall)
        else -> WowButton(stringResource(R.string.wow_options_ralia_replace), primary = false) { pick.launch(ZIP_TYPES) }
    }
    problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = WowColors.Blood) }
}

private suspend fun readZip(context: Context, set: TextureSet, uri: Uri): Int = withContext(Dispatchers.IO) {
    val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("the zip couldn't be opened")
    input.use { set.unpack(WowFiles.root(context), it) }
}

/** The Game options tile's line: the options that are on. */
fun gameOptionsSummary(context: Context): String {
    val root = WowFiles.root(context)
    val on = listOfNotNull(
        context.getString(R.string.wow_options_ralia).takeIf { RaliaFiles.isOn(root) },
        context.getString(R.string.wow_options_alternate_female).takeIf { AlternateFemaleFiles.isOn(root) },
        context.getString(R.string.wow_options_base_model).takeIf { BaseModelFiles.isOn(root) },
    )
    return if (on.isEmpty()) context.getString(R.string.wow_tile_game_options_off) else on.joinToString(", ")
}
