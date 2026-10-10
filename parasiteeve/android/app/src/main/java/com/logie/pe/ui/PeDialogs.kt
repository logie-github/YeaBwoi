// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.logie.pe.PeConfig
import com.logie.pe.PeFiles
import com.logie.pe.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ControlsDialog(onDismiss: () -> Unit) {
    PeDialog(title = stringResource(R.string.pe_controls_title), onDismiss = onDismiss) {
        ControlsSettings()
        PeGestureGuide()
    }
}

/** The vibration switch, in the controls dialog and the in-game menu. */
@Composable
fun ControlsSettings() {
    val context = LocalContext.current
    var haptics by remember { mutableStateOf(PeConfig(context).haptics) }
    PeSwitchRow(stringResource(R.string.pe_haptics), stringResource(R.string.pe_haptics_summary), haptics) {
        haptics = it
        PeConfig(context).haptics = it
    }
}

@Composable
fun PictureDialog(onDismiss: () -> Unit) {
    PeDialog(title = stringResource(R.string.pe_picture_title), onDismiss = onDismiss) {
        PictureSettings()
    }
}

/** How the picture is drawn, in the picture dialog and the in-game menu. */
@Composable
fun PictureSettings() {
    val context = LocalContext.current
    var picture by remember { mutableIntStateOf(PeConfig(context).picture) }
    var resolution by remember { mutableIntStateOf(PeConfig(context).resolution) }
    var steady by remember { mutableStateOf(PeConfig(context).steady3d) }

    Text(stringResource(R.string.pe_resolution), style = MaterialTheme.typography.titleMedium)
    PeSegments(
        options = PeConfig.RESOLUTIONS.map { it to stringResource(R.string.pe_resolution_option, it) },
        selected = resolution,
    ) { scale ->
        resolution = scale
        PeConfig(context).resolution = scale
    }
    Text(
        stringResource(R.string.pe_resolution_summary, resolution, 320 * resolution, 240 * resolution),
        style = MaterialTheme.typography.bodySmall,
    )
    PeSwitchRow(stringResource(R.string.pe_steady_3d), stringResource(R.string.pe_steady_3d_summary), steady) {
        steady = it
        PeConfig(context).steady3d = it
    }

    val choices = listOf(
        Triple(PeConfig.PICTURE_SHARP, R.string.pe_picture_sharp, R.string.pe_picture_sharp_summary),
        Triple(PeConfig.PICTURE_SMOOTH, R.string.pe_picture_smooth, R.string.pe_picture_smooth_summary),
        Triple(PeConfig.PICTURE_CRT, R.string.pe_picture_crt, R.string.pe_picture_crt_summary),
        Triple(PeConfig.PICTURE_UPSCALED, R.string.pe_picture_upscaled, R.string.pe_picture_upscaled_summary),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((value, title, summary) in choices) {
            PeChoiceRow(stringResource(title), stringResource(summary), picture == value) {
                picture = value
                PeConfig(context).picture = value
            }
        }
    }
}

@Composable
fun MemoryCardDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val card = remember(refresh) { PeFiles.memoryCard(context).takeIf { it.isFile } }

    val importCard = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { PeFiles.importMemoryCard(context, uri) } }
            message = result.fold(
                onSuccess = { context.getString(R.string.pe_card_imported) },
                onFailure = {
                    if (it is PeFiles.ImportException) context.getString(R.string.pe_card_not_a_card)
                    else context.getString(R.string.pe_card_failed, it.message ?: "")
                },
            )
            refresh++
        }
    }
    val exportCard = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { PeFiles.exportMemoryCard(context, uri) } }
            message = result.fold(
                onSuccess = { context.getString(R.string.pe_card_exported) },
                onFailure = { context.getString(R.string.pe_card_failed, it.message ?: "") },
            )
        }
    }

    PeDialog(
        title = stringResource(R.string.pe_card_title),
        onDismiss = onDismiss,
        buttons = {
            PeButton(stringResource(R.string.pe_card_import), primary = false) { importCard.launch(arrayOf("*/*")) }
            PeButton(stringResource(R.string.pe_card_export), primary = false, enabled = card != null) {
                exportCard.launch("Parasite Eve.mcd")
            }
            PeButton(stringResource(android.R.string.ok), onClick = onDismiss)
        },
    ) {
        Text(
            if (card != null) {
                stringResource(R.string.pe_card_status, relativeTime(card.lastModified()))
            } else {
                stringResource(R.string.pe_card_empty)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(stringResource(R.string.pe_card_body), style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PeColors.Glow) }
    }
}

@Composable
fun BiosDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val bios = remember(refresh) { PeFiles.bios(context) }

    val addBios = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { PeFiles.importBios(context, uri) } }
            message = result.fold(
                onSuccess = { context.getString(R.string.pe_bios_added) },
                onFailure = {
                    if (it is PeFiles.ImportException) context.getString(R.string.pe_bios_not_a_bios)
                    else context.getString(R.string.pe_card_failed, it.message ?: "")
                },
            )
            refresh++
        }
    }

    PeDialog(
        title = stringResource(R.string.pe_bios_title),
        onDismiss = onDismiss,
        buttons = {
            if (bios != null) {
                PeButton(stringResource(R.string.pe_bios_remove), primary = false) {
                    PeFiles.deleteBios(context)
                    message = null
                    refresh++
                }
            }
            PeButton(stringResource(R.string.pe_bios_add), primary = false) { addBios.launch(arrayOf("*/*")) }
            PeButton(stringResource(android.R.string.ok), onClick = onDismiss)
        },
    ) {
        Text(
            if (bios != null) stringResource(R.string.pe_bios_yours, bios.name) else stringResource(R.string.pe_bios_builtin),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(stringResource(R.string.pe_bios_body), style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PeColors.Glow) }
    }
}

/** Both discs: what's installed, and adding, replacing or deleting each. */
@Composable
fun DiscsDialog(onPick: (disc: Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val bothInOne = remember(refresh) { PeFiles.hasBothDiscsInOne(context) }
    var confirmDelete by remember { mutableStateOf<Int?>(null) }

    PeDialog(title = stringResource(R.string.pe_discs_title), onDismiss = onDismiss) {
        for (disc in 1..PeFiles.DISCS) {
            val file = remember(refresh) { PeFiles.discFile(context, disc) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pe_disc_label, disc).lowercase(), style = MaterialTheme.typography.headlineSmall)
                Text(
                    when {
                        file != null -> stringResource(R.string.pe_disc_status, file.name, sizeText(context, PeFiles.discSize(context, disc)))
                        bothInOne -> stringResource(R.string.pe_disc_in_pbp)
                        else -> stringResource(R.string.pe_disc_missing)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (file != null || bothInOne) PeColors.Text else PeColors.Mist,
                )
                if (!(bothInOne && disc == 2)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    ) {
                        if (file != null) {
                            PeButton(
                                stringResource(if (confirmDelete == disc) R.string.pe_disc_delete_confirm else R.string.pe_disc_delete),
                                primary = false,
                            ) {
                                if (confirmDelete == disc) {
                                    PeFiles.deleteDisc(context, disc)
                                    confirmDelete = null
                                    refresh++
                                } else {
                                    confirmDelete = disc
                                }
                            }
                        }
                        PeButton(
                            stringResource(if (file != null) R.string.pe_disc_replace else R.string.pe_disc_add),
                            primary = file == null,
                        ) { onPick(disc) }
                    }
                }
            }
        }
        Text(stringResource(R.string.pe_discs_body), style = MaterialTheme.typography.bodySmall)
    }
}
