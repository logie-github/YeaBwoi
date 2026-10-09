// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.logie.azuredreams.AdConfig
import com.logie.azuredreams.AdFiles
import com.logie.azuredreams.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ControlsDialog(onDismiss: () -> Unit) {
    AdDialog(title = stringResource(R.string.ad_controls_title), onDismiss = onDismiss) {
        ControlsSettings()
        AdGestureGuide()
    }
}

/** The vibration switch, in the controls dialog and the in-game menu. */
@Composable
fun ControlsSettings() {
    val context = LocalContext.current
    var haptics by remember { mutableStateOf(AdConfig(context).haptics) }
    AdSwitchRow(stringResource(R.string.ad_haptics), stringResource(R.string.ad_haptics_summary), haptics) {
        haptics = it
        AdConfig(context).haptics = it
    }
}

@Composable
fun PictureDialog(onDismiss: () -> Unit) {
    AdDialog(title = stringResource(R.string.ad_picture_title), onDismiss = onDismiss) {
        PictureSettings()
    }
}

/** How the picture is drawn, in the picture dialog and the in-game menu. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PictureSettings() {
    val context = LocalContext.current
    var picture by remember { mutableIntStateOf(AdConfig(context).picture) }
    var sharper by remember { mutableStateOf(AdConfig(context).sharper3d) }
    var depth by remember { mutableIntStateOf(AdConfig(context).hd2dDepth) }
    var bloom by remember { mutableStateOf(AdConfig(context).hd2dBloom) }
    var motes by remember { mutableStateOf(AdConfig(context).hd2dMotes) }
    val choices = listOf(
        Triple(AdConfig.PICTURE_HD2D, R.string.ad_picture_hd2d, R.string.ad_picture_hd2d_summary),
        Triple(AdConfig.PICTURE_SHARP, R.string.ad_picture_sharp, R.string.ad_picture_sharp_summary),
        Triple(AdConfig.PICTURE_SMOOTH, R.string.ad_picture_smooth, R.string.ad_picture_smooth_summary),
        Triple(AdConfig.PICTURE_CRT, R.string.ad_picture_crt, R.string.ad_picture_crt_summary),
        Triple(AdConfig.PICTURE_UPSCALED, R.string.ad_picture_upscaled, R.string.ad_picture_upscaled_summary),
    )
    Column {
        for ((value, title, summary) in choices) {
            AdChoiceRow(stringResource(title), stringResource(summary), picture == value) {
                picture = value
                AdConfig(context).picture = value
            }
            if (value == AdConfig.PICTURE_HD2D && picture == AdConfig.PICTURE_HD2D) {
                Column(
                    Modifier.padding(start = 30.dp, top = 4.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.ad_hd2d_depth), style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for ((level, label) in listOf(
                            AdConfig.DEPTH_OFF to R.string.ad_hd2d_depth_off,
                            AdConfig.DEPTH_LIGHT to R.string.ad_hd2d_depth_light,
                            AdConfig.DEPTH_STRONG to R.string.ad_hd2d_depth_strong,
                        )) {
                            AdButton(stringResource(label), primary = depth == level) {
                                depth = level
                                AdConfig(context).hd2dDepth = level
                            }
                        }
                    }
                    Text(stringResource(R.string.ad_hd2d_depth_summary), style = MaterialTheme.typography.bodySmall)
                    AdSwitchRow(stringResource(R.string.ad_hd2d_bloom), stringResource(R.string.ad_hd2d_bloom_summary), bloom) {
                        bloom = it
                        AdConfig(context).hd2dBloom = it
                    }
                    AdSwitchRow(stringResource(R.string.ad_hd2d_motes), stringResource(R.string.ad_hd2d_motes_summary), motes) {
                        motes = it
                        AdConfig(context).hd2dMotes = it
                    }
                }
            }
        }
    }
    AdSwitchRow(stringResource(R.string.ad_sharper_3d), stringResource(R.string.ad_sharper_3d_summary), sharper) {
        sharper = it
        AdConfig(context).sharper3d = it
    }
}

@Composable
fun MemoryCardDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val card = remember(refresh) { AdFiles.memoryCard(context).takeIf { it.isFile } }

    val importCard = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AdFiles.importMemoryCard(context, uri) } }
            message = result.fold(
                onSuccess = { context.getString(R.string.ad_card_imported) },
                onFailure = {
                    if (it is AdFiles.ImportException) context.getString(R.string.ad_card_not_a_card)
                    else context.getString(R.string.ad_card_failed, it.message ?: "")
                },
            )
            refresh++
        }
    }
    val exportCard = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AdFiles.exportMemoryCard(context, uri) } }
            message = result.fold(
                onSuccess = { context.getString(R.string.ad_card_exported) },
                onFailure = { context.getString(R.string.ad_card_failed, it.message ?: "") },
            )
        }
    }

    AdDialog(
        title = stringResource(R.string.ad_card_title),
        onDismiss = onDismiss,
        buttons = {
            AdButton(stringResource(R.string.ad_card_import), primary = false) { importCard.launch(arrayOf("*/*")) }
            AdButton(stringResource(R.string.ad_card_export), primary = false, enabled = card != null) {
                exportCard.launch("Azure Dreams.mcd")
            }
            AdButton(stringResource(android.R.string.ok), onClick = onDismiss)
        },
    ) {
        Text(
            if (card != null) {
                stringResource(R.string.ad_card_status, relativeTime(card.lastModified()))
            } else {
                stringResource(R.string.ad_card_empty)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(stringResource(R.string.ad_card_body), style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AdColors.Gold) }
    }
}

@Composable
fun BiosDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val bios = remember(refresh) { AdFiles.bios(context) }

    val addBios = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AdFiles.importBios(context, uri) } }
            message = result.fold(
                onSuccess = { context.getString(R.string.ad_bios_added) },
                onFailure = {
                    if (it is AdFiles.ImportException) context.getString(R.string.ad_bios_not_a_bios)
                    else context.getString(R.string.ad_card_failed, it.message ?: "")
                },
            )
            refresh++
        }
    }

    AdDialog(
        title = stringResource(R.string.ad_bios_title),
        onDismiss = onDismiss,
        buttons = {
            if (bios != null) {
                AdButton(stringResource(R.string.ad_bios_remove), primary = false) {
                    AdFiles.deleteBios(context)
                    message = null
                    refresh++
                }
            }
            AdButton(stringResource(R.string.ad_bios_add), primary = false) { addBios.launch(arrayOf("*/*")) }
            AdButton(stringResource(android.R.string.ok), onClick = onDismiss)
        },
    ) {
        Text(
            if (bios != null) stringResource(R.string.ad_bios_yours, bios.name) else stringResource(R.string.ad_bios_builtin),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(stringResource(R.string.ad_bios_body), style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AdColors.Gold) }
    }
}

@Composable
fun DiscDialog(onReplace: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val disc = remember { AdFiles.launchFile(context) }
    var confirmDelete by remember { mutableStateOf(false) }
    AdDialog(
        title = stringResource(R.string.ad_disc_title),
        onDismiss = onDismiss,
        buttons = {
            AdButton(stringResource(if (confirmDelete) R.string.ad_disc_delete_confirm else R.string.ad_disc_delete), primary = false) {
                if (confirmDelete) {
                    AdFiles.deleteGame(context)
                    AdFiles.deleteState(context, AdFiles.RESUME_SLOT)
                    onDismiss()
                } else {
                    confirmDelete = true
                }
            }
            AdButton(stringResource(R.string.ad_disc_replace), primary = false, onClick = onReplace)
            AdButton(stringResource(android.R.string.ok), onClick = onDismiss)
        },
    ) {
        Text(
            stringResource(R.string.ad_disc_status, disc?.name ?: "—", sizeText(context, AdFiles.gameSize(context))),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(stringResource(R.string.ad_disc_body), style = MaterialTheme.typography.bodySmall)
    }
}
