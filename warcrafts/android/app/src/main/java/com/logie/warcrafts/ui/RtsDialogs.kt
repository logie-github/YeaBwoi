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
import com.logie.warcrafts.game.GameSettings
import com.logie.warcrafts.game.GameStops
import com.logie.warcrafts.library.GameLayout
import com.logie.warcrafts.library.Install
import com.logie.warcrafts.library.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What to bring for each game: what the picker wants. */
private fun whatToImport(install: Install): Int = when (install) {
    Install.WC1 -> R.string.rts_files_what_wc1
    Install.WC2 -> R.string.rts_files_what_wc2
    Install.WC3 -> R.string.rts_files_what_wc3
}

/**
 * Game files for the game the launcher shows: what to bring and where it stands, the folder or zip
 * picker, the program that starts it and what it's started with, Private (Blizzard's servers out of
 * reach), and deleting the files.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameFilesDialog(title: Title, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val look = LocalLook.current
    val install = title.install
    val imports by Imports.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var size by remember { mutableLongStateOf(-1L) }
    var programs by remember { mutableStateOf<List<GameLayout.Program>>(emptyList()) }
    var program by remember { mutableStateOf<String?>(null) }
    var arguments by remember(refresh) { mutableStateOf(RtsFiles.arguments(context, install)) }
    var private by remember { mutableStateOf(RtsFiles.isPrivate(context)) }
    var confirmDelete by remember { mutableStateOf(false) }
    var filesIn by remember { mutableStateOf(false) }
    LaunchedEffect(refresh, imports) {
        withContext(Dispatchers.IO) {
            filesIn = com.logie.warcrafts.library.Needs.filesIn(RtsFiles.needs(context, install))
            programs = GameLayout.candidates(RtsFiles.programs(context, install))
            program = RtsFiles.program(context, install)
            size = if (program != null || filesIn) RtsFiles.size(context, install) else -1L
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
        onDismiss = {
            RtsFiles.setArguments(context, install, arguments)
            onDismiss()
        },
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
                    if ((program != null || filesIn) && size >= 0) {
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
                    RtsButton(stringResource(R.string.rts_files_pick_folder), primary = !native) { pickFolder.launch(null) }
                    RtsButton(stringResource(R.string.rts_files_pick_zip), primary = false) {
                        pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                    }
                    if (native && filesIn) {
                        RtsButton(stringResource(R.string.rts_convert_again), primary = false) { ImportService.convert(context, install) }
                    }
                }
            }
        }

        // Warcraft III's program and arguments (Warcraft I and II are played by Stratagus).
        if (install.engine == com.logie.warcrafts.library.Engine.WINE && programs.isNotEmpty()) {
            RtsTitle(stringResource(R.string.rts_files_program), style = MaterialTheme.typography.titleLarge)
            for (candidate in programs.take(6)) {
                RtsChoiceRow(
                    candidate.path,
                    Formatter.formatShortFileSize(context, candidate.size),
                    selected = candidate.path == program,
                ) {
                    RtsFiles.setProgram(context, install, candidate.path)
                    refresh++
                }
            }
            OutlinedTextField(
                value = arguments,
                onValueChange = { arguments = it.replace("\n", "") },
                label = { Text(stringResource(R.string.rts_files_arguments)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.rts_files_arguments_note, install.defaultArguments.ifEmpty { "—" }), style = MaterialTheme.typography.bodySmall)
        }

        RtsTitle(stringResource(R.string.rts_files_private_title), style = MaterialTheme.typography.titleLarge)
        RtsSwitchRow(stringResource(R.string.rts_files_private), stringResource(R.string.rts_files_private_summary), private) {
            private = it
            RtsFiles.setPrivate(context, it)
        }

        if ((program != null || filesIn) && working == null) {
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

/** Graphics: the games' resolution, Warcraft III's renderer, vibration. */
@Composable
fun GraphicsDialog(title: Title, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var resolution by remember { mutableStateOf(GameSettings.resolution(context)) }
    var renderer by remember { mutableStateOf(GameSettings.renderer(context)) }
    var vibration by remember { mutableStateOf(GameSettings.vibration(context)) }
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
    }
}

/** The touch controls, as the README's table has them. */
@Composable
fun ControlsDialog(onDismiss: () -> Unit) {
    val look = LocalLook.current
    val rows = listOf(
        R.string.rts_controls_swipe to R.string.rts_controls_swipe_does,
        R.string.rts_controls_tap to R.string.rts_controls_tap_does,
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
