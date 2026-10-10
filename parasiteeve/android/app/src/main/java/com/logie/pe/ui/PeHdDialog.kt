// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.core.content.ContextCompat
import com.logie.pe.PeConfig
import com.logie.pe.R
import com.logie.pe.hd.HdTextures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/**
 * The HD texture pack: adding one (all the parts of its download, picked together), how the
 * unpacking goes, switching it on and off, and deleting it. After installing, offers to delete
 * the downloaded parts, which aren't needed anymore.
 */
@Composable
fun HdTexturesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by HdTextures.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val pack = remember(refresh, state) { HdTextures.installed(context) }
    var enabled by remember { mutableStateOf(PeConfig(context).hdTextures) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // The notification that shows the unpacking's progress needs the permission on Android 13+.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val pickParts = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        message = null
        confirmDelete = false
        HdTextures.start(context, uris)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val finished = state is HdTextures.State.Done || state is HdTextures.State.Failed
    fun close() {
        HdTextures.acknowledge()
        onDismiss()
    }

    PeDialog(
        title = stringResource(R.string.pe_hd_title),
        onDismiss = ::close,
        buttons = {
            when (val current = state) {
                HdTextures.State.Checking, is HdTextures.State.Unpacking -> {
                    PeButton(stringResource(R.string.pe_hd_stop), primary = false) { HdTextures.cancel() }
                    PeButton(stringResource(R.string.pe_hd_hide), onClick = onDismiss)
                }
                is HdTextures.State.Done -> {
                    PeButton(stringResource(R.string.pe_hd_keep_parts), primary = false, onClick = ::close)
                    PeButton(stringResource(R.string.pe_hd_delete_parts)) {
                        scope.launch {
                            val failed = withContext(Dispatchers.IO) { HdTextures.deleteParts(context, current.parts) }
                            HdTextures.acknowledge()
                            message = if (failed == 0) context.getString(R.string.pe_hd_parts_deleted)
                            else context.getString(R.string.pe_hd_parts_not_deleted, failed)
                        }
                    }
                }
                else -> {
                    if (pack != null && !finished) {
                        PeButton(
                            stringResource(if (confirmDelete) R.string.pe_hd_delete_confirm else R.string.pe_hd_delete),
                            primary = false,
                        ) {
                            if (confirmDelete) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { HdTextures.delete(context) }
                                    confirmDelete = false
                                    message = context.getString(R.string.pe_hd_deleted)
                                    refresh++
                                }
                            } else {
                                confirmDelete = true
                            }
                        }
                    }
                    PeButton(
                        stringResource(if (pack != null) R.string.pe_hd_replace else R.string.pe_hd_add),
                        primary = pack == null,
                    ) {
                        HdTextures.acknowledge()
                        pickParts.launch(arrayOf("*/*"))
                    }
                    PeButton(stringResource(android.R.string.ok), primary = pack != null, onClick = ::close)
                }
            }
        },
    ) {
        when (val current = state) {
            HdTextures.State.Checking -> Working(stringResource(R.string.pe_hd_checking), null, null)

            is HdTextures.State.Unpacking -> {
                val progress = if (current.total > 0) current.done.toFloat() / current.total else 0f
                Working(
                    stringResource(R.string.pe_hd_unpacking, (progress * 100).toInt()),
                    progress,
                    stringResource(R.string.pe_hd_unpacking_detail, sizeText(context, current.done), sizeText(context, current.total)),
                )
                Text(stringResource(R.string.pe_hd_unpacking_note), style = MaterialTheme.typography.bodySmall)
            }

            is HdTextures.State.Done -> {
                Text(
                    stringResource(R.string.pe_hd_done, count(current.pack.files), sizeText(context, current.pack.bytes)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.pe_hd_delete_parts_question, sizeText(context, current.partsBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is HdTextures.State.Failed -> {
                Text(stringResource(R.string.pe_hd_failed), style = MaterialTheme.typography.bodyLarge)
                Text(failureText(context, current), style = MaterialTheme.typography.bodyMedium, color = PeColors.Glow)
            }

            HdTextures.State.Idle -> {
                if (pack != null) {
                    Text(
                        stringResource(R.string.pe_hd_installed, count(pack.files), sizeText(context, pack.bytes)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    PeSwitchRow(stringResource(R.string.pe_hd_switch), stringResource(R.string.pe_hd_switch_summary), enabled) {
                        enabled = it
                        PeConfig(context).hdTextures = it
                    }
                } else {
                    Text(stringResource(R.string.pe_hd_none), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.pe_hd_body), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.pe_hd_space), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PeColors.Glow) }
    }
}

/** What's going on, with a spinner, and how far along it is once that's known. */
@Composable
private fun Working(title: String, progress: Float?, detail: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PeSpinner(36.dp)
        Spacer(Modifier.width(12.dp))
        Text(title.lowercase(), style = MaterialTheme.typography.headlineSmall)
    }
    if (progress != null) PeProgressLine(progress)
    if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall)
}

/** The HD textures' state in a few words, for the title screen's tile. */
@Composable
fun hdTileSummary(): String {
    val context = LocalContext.current
    val state by HdTextures.state.collectAsState()
    val pack = remember(state) { HdTextures.installed(context) }
    return when (val current = state) {
        HdTextures.State.Checking -> stringResource(R.string.pe_hd_checking)
        is HdTextures.State.Unpacking ->
            stringResource(R.string.pe_hd_unpacking, if (current.total > 0) (current.done * 100 / current.total).toInt() else 0)
        is HdTextures.State.Done -> stringResource(R.string.pe_tile_hd_done)
        is HdTextures.State.Failed -> stringResource(R.string.pe_hd_failed)
        HdTextures.State.Idle -> when {
            pack == null -> stringResource(R.string.pe_tile_hd_none)
            PeConfig(context).hdTextures -> stringResource(R.string.pe_tile_hd_on, sizeText(context, pack.bytes))
            else -> stringResource(R.string.pe_tile_hd_off)
        }
    }
}

private fun count(value: Long): String = NumberFormat.getIntegerInstance().format(value)

private fun failureText(context: Context, failure: HdTextures.State.Failed): String = when (failure.reason) {
    HdTextures.Reason.NOT_AN_ARCHIVE -> context.getString(R.string.pe_hd_error_not_archive, failure.detail)
    HdTextures.Reason.MIXED_FILES -> context.getString(R.string.pe_hd_error_mixed)
    HdTextures.Reason.PART_MISSING ->
        if (failure.detail.isEmpty()) context.getString(R.string.pe_hd_error_parts_missing)
        else context.getString(R.string.pe_hd_error_part_missing, failure.detail)
    HdTextures.Reason.CANT_READ -> context.getString(R.string.pe_hd_error_cant_read, failure.detail)
    HdTextures.Reason.NOT_SEEKABLE -> context.getString(R.string.pe_hd_error_not_seekable, failure.detail)
    HdTextures.Reason.NO_IMAGES -> context.getString(R.string.pe_hd_error_no_images)
    HdTextures.Reason.NO_SPACE -> {
        val (needed, free) = failure.detail.split('/').map { it.toLongOrNull() ?: 0L }.let { it.getOrElse(0) { 0L } to it.getOrElse(1) { 0L } }
        context.getString(R.string.pe_hd_error_no_space, sizeText(context, needed), sizeText(context, free))
    }
    HdTextures.Reason.DAMAGED -> context.getString(R.string.pe_hd_error_damaged)
    HdTextures.Reason.UNSUPPORTED -> context.getString(R.string.pe_hd_error_unsupported)
    HdTextures.Reason.WRITE_FAILED -> context.getString(R.string.pe_hd_error_write)
    HdTextures.Reason.OUT_OF_MEMORY -> context.getString(R.string.pe_hd_error_memory)
    HdTextures.Reason.CANCELLED -> context.getString(R.string.pe_hd_error_cancelled)
}
