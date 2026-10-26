// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.ui

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import com.logie.warcrafts.library.Wc3Keys
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.logie.warcrafts.ImportService
import com.logie.warcrafts.Imports
import com.logie.warcrafts.R
import com.logie.warcrafts.RtsFiles
import com.logie.warcrafts.Wc3Setup
import com.logie.warcrafts.game.GameSettings
import com.logie.warcrafts.game.GameStops
import com.logie.warcrafts.library.GameLayout
import com.logie.warcrafts.library.Install
import com.logie.warcrafts.library.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What to bring for Warcraft I or II: what the picker wants. */
private fun whatToImport(install: Install): Int = when (install) {
    Install.WC1 -> R.string.rts_files_what_wc1
    else -> R.string.rts_files_what_wc2
}

/**
 * Game files for the game the launcher shows. Warcraft I and II: what to bring and where it stands,
 * GOG's installer or the folder or zip, converting again, deleting. Warcraft III: its setup in the
 * app ([Warcraft3Dialog]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameFilesDialog(title: Title, onDismiss: () -> Unit) {
    if (title.install == Install.WC3) {
        Warcraft3Dialog(onDismiss)
        return
    }
    val context = LocalContext.current
    val look = LocalLook.current
    val install = title.install
    val imports by Imports.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var size by remember { mutableLongStateOf(-1L) }
    var confirmDelete by remember { mutableStateOf(false) }
    var filesIn by remember { mutableStateOf(false) }
    LaunchedEffect(refresh, imports) {
        withContext(Dispatchers.IO) {
            filesIn = com.logie.warcrafts.library.Needs.filesIn(RtsFiles.needs(context, install))
            size = if (filesIn) RtsFiles.size(context, install) else -1L
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) ImportService.start(context, install, uri, isFolder = true)
    }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) ImportService.start(context, install, uri, isFolder = false)
    }
    // GOG's installer: the setup program and the .bin files beside it, picked together.
    val pickInstaller = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) ImportService.startInstaller(context, install, uris)
    }
    val native = install.stratagusGame != null
    val working = imports as? Imports.State.Working

    RtsDialog(
        stringResource(R.string.rts_files_title, stringResource(ImportService.installName(install))),
        onDismiss = onDismiss,
    ) {
        Text(stringResource(whatToImport(install)), style = MaterialTheme.typography.bodyMedium)
        when {
            working != null -> {
                Text(
                    stringResource(ImportService.phaseTitle(working.phase), stringResource(ImportService.installName(working.install))),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (working.progress == null) RtsSpinner(28.dp) else RtsProgressLine(working.progress)
                working.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                RtsButton(stringResource(R.string.rts_import_stop), primary = false) { ImportService.cancel(context) }
            }
            else -> {
                (imports as? Imports.State.Failed)?.takeIf { it.install == install }?.let {
                    Text(stringResource(R.string.rts_import_failed, it.message), style = MaterialTheme.typography.bodyMedium, color = look.blood)
                }
                (imports as? Imports.State.Done)?.takeIf { it.install == install }?.let {
                    Text(
                        stringResource(if (install.stratagusGame != null) R.string.rts_convert_done else R.string.rts_import_done),
                        style = MaterialTheme.typography.bodyMedium,
                        color = look.good,
                    )
                }
                Text(
                    if (filesIn && size >= 0) {
                        stringResource(R.string.rts_files_installed, Formatter.formatShortFileSize(context, size))
                    } else {
                        stringResource(R.string.rts_files_missing)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.rts_files_move_first), style = MaterialTheme.typography.bodySmall)
                if (native) Text(stringResource(R.string.rts_files_installer_how), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (native) RtsButton(stringResource(R.string.rts_files_pick_installer)) { pickInstaller.launch(arrayOf("*/*")) }
                    RtsButton(stringResource(R.string.rts_files_pick_folder), primary = false) { pickFolder.launch(null) }
                    RtsButton(stringResource(R.string.rts_files_pick_zip), primary = false) {
                        pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                    }
                    if (native && filesIn) {
                        RtsButton(stringResource(R.string.rts_convert_again), primary = false) { ImportService.convert(context, install) }
                    }
                }
            }
        }

        if (filesIn && working == null) {
            if (confirmDelete) {
                Text(stringResource(R.string.rts_files_delete_confirm), style = MaterialTheme.typography.bodyMedium, color = look.blood)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RtsButton(stringResource(R.string.rts_files_delete_yes), primary = false) {
                        RtsFiles.delete(context, install)
                        confirmDelete = false
                        refresh++
                    }
                    RtsButton(stringResource(R.string.rts_files_delete_no)) { confirmDelete = false }
                }
            } else {
                RtsButton(stringResource(R.string.rts_files_delete), primary = false) { confirmDelete = true }
            }
        }
    }
}

/** Graphics: the games' resolution, Warcraft III's renderer, vibration, Warcraft III's touchpad fallback. */
@Composable
fun GraphicsDialog(title: Title, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var resolution by remember { mutableStateOf(GameSettings.resolution(context)) }
    var renderer by remember { mutableStateOf(GameSettings.renderer(context)) }
    var vibration by remember { mutableStateOf(GameSettings.vibration(context)) }
    var touchpad by remember { mutableStateOf(GameSettings.touchpad(context)) }
    RtsDialog(stringResource(R.string.rts_tile_graphics), onDismiss = onDismiss) {
        Text(stringResource(R.string.rts_graphics_note), style = MaterialTheme.typography.bodySmall)
        for (choice in GameSettings.Resolution.entries) {
            val (width, height) = GameSettings.gameSize(context, choice)
            RtsChoiceRow(
                stringResource(choice.label) + "  ·  ${width}×$height",
                stringResource(choice.summary),
                selected = choice == resolution,
            ) {
                resolution = choice
                GameSettings.setResolution(context, choice)
            }
        }
        RtsTitle(stringResource(R.string.rts_renderer_title), style = MaterialTheme.typography.titleLarge)
        for (choice in GameSettings.Renderer.entries) {
            RtsChoiceRow(stringResource(choice.label), stringResource(choice.summary), selected = choice == renderer) {
                renderer = choice
                GameSettings.setRenderer(context, choice)
            }
        }
        RtsTitle(stringResource(R.string.rts_tile_controls), style = MaterialTheme.typography.titleLarge)
        RtsSwitchRow(stringResource(R.string.rts_vibration), stringResource(R.string.rts_vibration_summary), vibration) {
            vibration = it
            GameSettings.setVibration(context, it)
        }
        if (title.install == com.logie.warcrafts.library.Install.WC3) {
            RtsSwitchRow(stringResource(R.string.rts_touchpad), stringResource(R.string.rts_touchpad_summary), touchpad) {
                touchpad = it
                GameSettings.setTouchpad(context, it)
            }
        }
    }
}

/** The touch controls, as the README's table has them. */
@Composable
fun ControlsDialog(onDismiss: () -> Unit) {
    val look = LocalLook.current
    val rows = listOf(
        R.string.rts_controls_swipe to R.string.rts_controls_swipe_does,
        R.string.rts_controls_tap to R.string.rts_controls_tap_does,
        R.string.rts_controls_order to R.string.rts_controls_order_does,
        R.string.rts_controls_tap_drag to R.string.rts_controls_tap_drag_does,
        R.string.rts_controls_double_tap to R.string.rts_controls_double_tap_does,
        R.string.rts_controls_hold to R.string.rts_controls_hold_does,
        R.string.rts_controls_double to R.string.rts_controls_double_does,
        R.string.rts_controls_rest to R.string.rts_controls_rest_does,
        R.string.rts_controls_two_tap to R.string.rts_controls_two_tap_does,
        R.string.rts_controls_pinch to R.string.rts_controls_pinch_does,
        R.string.rts_controls_three to R.string.rts_controls_three_does,
        R.string.rts_controls_four to R.string.rts_controls_four_does,
        R.string.rts_controls_back to R.string.rts_controls_back_does,
    )
    RtsDialog(stringResource(R.string.rts_tile_controls), onDismiss = onDismiss) {
        for ((gesture, does) in rows) {
            Column {
                Text(stringResource(gesture), style = MaterialTheme.typography.titleSmall, color = look.accent)
                Text(stringResource(does), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(stringResource(R.string.rts_controls_mouse), style = MaterialTheme.typography.bodySmall)
    }
}

/** The four games, each by its logo, and whether its files are in. */
@Composable
fun GameChooser(current: Title, onDismiss: () -> Unit, onChoose: (Title) -> Unit) {
    val context = LocalContext.current
    RtsDialog(stringResource(R.string.rts_games_title), onDismiss = onDismiss, buttons = {
        RtsButton(stringResource(R.string.rts_close), primary = false, onClick = onDismiss)
    }) {
        for (title in Title.entries) {
            val look = RtsLook.of(title)
            val ready = remember(title) { RtsFiles.hasGame(context, title.install) }
            RtsTheme(look) {
                RtsWindow(Modifier.fillMaxWidth(), opaque = true, onClick = { onChoose(title) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painterResource(look.logo),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.width(112.dp).height(60.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(look.name),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (title == current) look.glow else look.text,
                            )
                            Text(
                                stringResource(if (ready) R.string.rts_games_ready else R.string.rts_games_missing),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ready) look.good else look.mist,
                            )
                        }
                    }
                }
            }
        }
        Text(stringResource(R.string.rts_games_wc3_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * A game (or the app) stopped by itself: what it said last ([GameStops]), to copy and send. The
 * games' output is in English, as the engine writes it.
 */
@Composable
fun GameStoppedDialog(report: GameStops.Report, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val name = report.title?.let { stringResource(ImportService.installName(it.install)) }
    RtsDialog(
        if (name != null) stringResource(R.string.rts_stopped_title, name) else stringResource(R.string.rts_stopped_title_app),
        onDismiss = onDismiss,
        buttons = {
            RtsButton(stringResource(R.string.rts_stopped_copy), primary = false) { clipboard.setText(AnnotatedString(report.text)) }
            RtsButton("OK", onClick = onDismiss)
        },
    ) {
        Text(stringResource(R.string.rts_stopped_text), style = MaterialTheme.typography.bodyMedium)
        SelectionContainer {
            Text(
                report.text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = LocalLook.current.mist,
            )
        }
    }
}

/**
 * The player's two CD keys (on the game's box or in their Blizzard account), typed here and written
 * into the installed game as Blizzard's installer would ([Wc3Setup.saveKeys]). Dashes and spaces
 * may be typed; each key must be 26 of the characters Warcraft III's keys are made of.
 */
@Composable
private fun Warcraft3Keys(onSaved: () -> Unit) {
    val context = LocalContext.current
    val look = LocalLook.current
    val scope = rememberCoroutineScope()
    var roc by remember { mutableStateOf("") }
    var tft by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Text(stringResource(R.string.rts_keys_how), style = MaterialTheme.typography.bodySmall)
    for ((label, value, set) in listOf(
        Triple(R.string.rts_keys_roc, roc) { v: String -> roc = v },
        Triple(R.string.rts_keys_tft, tft) { v: String -> tft = v },
    )) {
        OutlinedTextField(
            value = value,
            onValueChange = { set(it.replace("\n", "").take(40)) },
            label = { Text(stringResource(label)) },
            singleLine = true,
            isError = value.isNotBlank() && Wc3Keys.normalize(value) == null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = look.blood) }
    if (saving) {
        RtsSpinner(28.dp)
    } else {
        RtsButton(stringResource(R.string.rts_keys_save)) {
            val rocKey = Wc3Keys.normalize(roc)
            val tftKey = Wc3Keys.normalize(tft)
            if (rocKey == null || tftKey == null) {
                error = context.getString(R.string.rts_keys_bad)
                return@RtsButton
            }
            saving = true
            error = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { Wc3Setup.saveKeys(context, rocKey, tftKey) } }
                saving = false
                result.onSuccess { onSaved() }.onFailure { error = it.message ?: it.javaClass.simpleName }
            }
        }
    }
}

/** The colours of the dialogs' text fields. */
@Composable
private fun fieldColors() = LocalLook.current.let { look ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = look.accent,
        unfocusedBorderColor = look.steel,
        focusedLabelColor = look.accent,
        unfocusedLabelColor = look.mist,
        cursorColor = look.accent,
    )
}

/**
 * Classic+ ([com.logie.warcrafts.library.ClassicPlus]), new art for the units, buildings and trees:
 * downloaded from where its page links it, or from its archive picked on the phone, then installed
 * converted for 1.27; on or off (the game reads its files or not), or taken out.
 */
@Composable
private fun ClassicPlusSection(busy: Boolean, refresh: Int, onChange: () -> Unit) {
    val context = LocalContext.current
    val look = LocalLook.current
    val game = RtsFiles.folder(context, Install.WC3)
    var installed by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(refresh, busy) {
        installed = withContext(Dispatchers.IO) { com.logie.warcrafts.library.ClassicPlus.installed(game) }
        enabled = withContext(Dispatchers.IO) { com.logie.warcrafts.library.ClassicPlus.enabled(game) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) ImportService.installClassicPlus(context, uri)
    }
    RtsTitle(stringResource(R.string.rts_classicplus_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.rts_classicplus_intro), style = MaterialTheme.typography.bodyMedium)
    if (busy) return
    if (installed) {
        RtsSwitchRow(stringResource(R.string.rts_classicplus_on), stringResource(R.string.rts_classicplus_on_summary), enabled) {
            com.logie.warcrafts.library.ClassicPlus.setEnabled(game, it)
            enabled = it
        }
        if (confirmRemove) {
            Text(stringResource(R.string.rts_classicplus_remove_confirm), style = MaterialTheme.typography.bodyMedium, color = look.blood)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RtsButton(stringResource(R.string.rts_files_delete_yes), primary = false) {
                    com.logie.warcrafts.library.ClassicPlus.remove(game)
                    confirmRemove = false
                    onChange()
                }
                RtsButton(stringResource(R.string.rts_files_delete_no)) { confirmRemove = false }
            }
        } else {
            RtsButton(stringResource(R.string.rts_classicplus_remove), primary = false) { confirmRemove = true }
        }
    } else {
        RtsButton(stringResource(R.string.rts_classicplus_download)) { ImportService.installClassicPlus(context) }
        RtsButton(stringResource(R.string.rts_classicplus_pick), primary = false) { pick.launch(arrayOf("*/*")) }
        Text(stringResource(R.string.rts_classicplus_how), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Warcraft III, set up in the app ([Wc3Setup]): one button downloads both games from Blizzard and
 * installs them, silently (no installer window, licence page or CD key); then it's ready, and Press
 * start plays it. A stopped setup goes on from where it was with the same button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Warcraft3Dialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val look = LocalLook.current
    val imports by Imports.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var step by remember { mutableStateOf<Wc3Setup.Step?>(null) }
    var private by remember { mutableStateOf(RtsFiles.isPrivate(context)) }
    var confirmDelete by remember { mutableStateOf(false) }
    var changeKeys by remember { mutableStateOf(false) }
    LaunchedEffect(refresh, imports) {
        step = withContext(Dispatchers.IO) { Wc3Setup.step(context) }
    }
    val working = (imports as? Imports.State.Working)?.takeIf { it.install == Install.WC3 }
    val current = step

    RtsDialog(stringResource(R.string.rts_wc3_title), onDismiss = onDismiss) {
        Text(stringResource(R.string.rts_wc3_intro), style = MaterialTheme.typography.bodyMedium)
        val order = listOf(
            Wc3Setup.Step.DOWNLOAD to R.string.rts_wc3_step_download,
            Wc3Setup.Step.INSTALL to R.string.rts_wc3_step_install,
            Wc3Setup.Step.KEYS to R.string.rts_wc3_step_keys,
        )
        for ((index, entry) in order.withIndex()) {
            val (s, label) = entry
            val done = current != null && current.ordinal > s.ordinal
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (done) "✓" else "${index + 1}", style = MaterialTheme.typography.titleMedium, color = if (done) look.good else look.gold)
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = if (current == s) look.text else look.mist)
            }
        }

        when {
            working != null -> {
                Text(
                    stringResource(ImportService.phaseTitle(working.phase), stringResource(ImportService.installName(Install.WC3))),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (working.progress == null) RtsSpinner(28.dp) else RtsProgressLine(working.progress)
                working.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                RtsButton(stringResource(R.string.rts_import_stop), primary = false) { ImportService.cancel(context) }
            }
            else -> {
                (imports as? Imports.State.Failed)?.takeIf { it.install == Install.WC3 }?.let {
                    Text(stringResource(R.string.rts_import_failed, it.message), style = MaterialTheme.typography.bodyMedium, color = look.blood)
                }
                when (current) {
                    Wc3Setup.Step.DOWNLOAD -> RtsButton(stringResource(R.string.rts_wc3_download)) { ImportService.downloadWc3(context) }
                    Wc3Setup.Step.INSTALL -> RtsButton(stringResource(R.string.rts_wc3_install)) { ImportService.downloadWc3(context) }
                    Wc3Setup.Step.KEYS -> Warcraft3Keys { refresh++ }
                    Wc3Setup.Step.READY -> {
                        Text(stringResource(R.string.rts_wc3_ready), style = MaterialTheme.typography.titleMedium, color = look.good)
                        if (changeKeys) {
                            Warcraft3Keys { changeKeys = false; refresh++ }
                        } else {
                            RtsButton(stringResource(R.string.rts_keys_change), primary = false) { changeKeys = true }
                        }
                    }
                    null -> RtsSpinner(28.dp)
                }
            }
        }

        // The last run's report (settings, Wine's output, the picture, the taps), to send.
        val clipboard = LocalClipboardManager.current
        var copied by remember { mutableStateOf(false) }
        if (com.logie.warcrafts.game.Wc3Report.file(context).isFile) {
            RtsButton(stringResource(if (copied) R.string.rts_wc3_report_copied else R.string.rts_wc3_report_copy), primary = false) {
                com.logie.warcrafts.game.Wc3Report.read(context)?.let {
                    clipboard.setText(AnnotatedString(it))
                    copied = true
                }
            }
            Text(stringResource(R.string.rts_wc3_report_how), style = MaterialTheme.typography.bodySmall)
        }

        if (current == Wc3Setup.Step.READY) ClassicPlusSection(busy = working != null, refresh = refresh) { refresh++ }

        RtsTitle(stringResource(R.string.rts_files_private_title), style = MaterialTheme.typography.titleLarge)
        RtsSwitchRow(stringResource(R.string.rts_files_private), stringResource(R.string.rts_files_private_summary), private) {
            private = it
            RtsFiles.setPrivate(context, it)
        }

        if (current == Wc3Setup.Step.READY && working == null) {
            if (confirmDelete) {
                Text(stringResource(R.string.rts_files_delete_confirm), style = MaterialTheme.typography.bodyMedium, color = look.blood)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RtsButton(stringResource(R.string.rts_files_delete_yes), primary = false) {
                        RtsFiles.delete(context, Install.WC3)
                        confirmDelete = false
                        refresh++
                    }
                    RtsButton(stringResource(R.string.rts_files_delete_no)) { confirmDelete = false }
                }
            } else {
                RtsButton(stringResource(R.string.rts_files_delete), primary = false) { confirmDelete = true }
            }
        }
    }
}
