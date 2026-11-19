package com.logie.wifecraft.ui

import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.creator.ImportFinder
import com.logie.wifecraft.creator.ImportKind
import com.logie.wifecraft.creator.ImportStore
import com.logie.wifecraft.creator.ListfileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * The Options panel's "Imports" section, as Wrath++'s Settings has them: a row per set (Ralia's mod, Alternate Female Options,
 * the body options, the undead skins), each with its status, Choose zip (Android's document picker), Remove, and the zip it looks for by name.
 * Everything that is not Android is in creator/ImportStore (unit-tested); this file is only the picker, the file lookup and the
 * rows. NOT run on a phone. The user's zips were not available here.
 */

/** Android's side of looking for a zip by its name: the shared storage's root, where the app may read it. */
private object AndroidFinder {
    fun mayLook(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun find(kind: ImportKind): File? {
        if (!mayLook()) return null
        val root = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return null
        return ImportFinder.find(ImportFinder.folders(root), kind.zipNames)
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "a zip"

@Composable
fun ImportsSection(store: ImportStore, names: ListfileNames, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<ImportKind?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(ImportKind.RALIA) }

    fun run(kind: ImportKind, work: () -> Unit) {
        busy = kind; message = null
        scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { work() }.exceptionOrNull() }
            message = error?.let { it.message ?: "It didn't work." }
            busy = null; version++
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val kind = picking
            run(kind) {
                if (kind.textures && !names.available()) names.refresh()
                val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("That file can't be opened.")
                input.use { store.importZip(kind, it, displayName(context, uri)) }
            }
        }
    }

    // a zip found by its name is taken in by itself (not after Remove, not over a zip picked by hand)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // the body options first: they need no file list, so a texture set's 150 MB fetch never holds them up
            for (kind in store.supportedKinds.sortedBy { it.textures }) runCatching {
                val found = AndroidFinder.find(kind) ?: return@runCatching
                if (kind.textures && !names.available()) names.refresh()
                store.lookFor(kind, found)
            }
        }
        version++
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Imports", style = frizStyle(16.sp))
        val statuses = remember(version) { store.statuses() }
        for (s in statuses) {
            val kind = s.kind
            Column(Modifier.padding(vertical = 2.dp)) {
                Text(kind.label, style = frizStyle(14.sp))
                Text(
                    when {
                        busy == kind -> "Importing…"
                        s.installed && kind.textures -> "Imported: ${s.files} textures from ${s.source ?: "a zip"}" +
                            (if (s.unmatched > 0) " (${s.unmatched} not in the game's file list)" else "") + if (s.active) ", in use" else ", off (the other set is on)"
                        s.installed && kind == ImportKind.UNDEAD -> "Imported: ${s.files} files from ${s.source ?: "a zip"}" + (store.undeadSummary()?.let { u ->
                            "; ${u.skins} skins for " + u.models.joinToString(", ") { undeadName(it) } + "; the Undead switch is under Accessories"
                        } ?: "")
                        s.installed -> "Imported: ${s.files} files from ${s.source ?: "a zip"}" + (store.bodySummary()?.let { b ->
                            "; shapes: " + (if (b.shapes.isEmpty()) "none" else b.shapes.joinToString(", ") { raceName(it) }) +
                                "; Style and Density: " + (if (b.layers.isEmpty()) "none" else "${b.layers.size} races")
                        } ?: "")
                        s.removed -> "Removed"
                        else -> "Not imported"
                    },
                    style = frizStyle(12.sp, WowColors.white),
                )
                Text(
                    "Looks for " + kind.zipNames.joinToString(" or ") + " in Download, Documents or the top of the phone's storage" +
                        if (AndroidFinder.mayLook()) "" else " (the app may not look there: choose it by hand)",
                    style = frizStyle(11.sp, WowColors.grey),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                    WowButton("Choose zip", { picking = kind; picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                        enabled = busy == null, width = 150.dp, height = 36.dp)
                    if (s.installed && kind.textures && !s.active)
                        WowButton("Use", { run(kind) { store.setActive(kind) } }, enabled = busy == null, width = 90.dp, height = 36.dp)
                    WowButton("Remove", { run(kind) { store.remove(kind) } }, enabled = busy == null && s.installed, width = 120.dp, height = 36.dp)
                }
            }
        }
        Text("Textures are matched to the game's files by name with its file list (fetched once, about 150 MB); the body options and the undead skins need no list.", style = frizStyle(11.sp, WowColors.grey))
        message?.let { Text(it, style = frizStyle(12.sp, androidx.compose.ui.graphics.Color(0xFFFF6A5A))) }
    }
}

/** An undead skins' race and sex key ("10_1": race id, sex 0 man, 1 woman) as the row says it. */
private fun undeadName(key: String): String {
    val (race, sex) = key.split('_').let { (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1) ?: "") }
    return raceName(race) + if (sex == "1") " woman" else " man"
}

/** The ten races of 3.3.5 the body options are made for, by id (races.json). */
private fun raceName(id: Int): String = when (id) {
    1 -> "Human"; 2 -> "Orc"; 3 -> "Dwarf"; 4 -> "Night Elf"; 5 -> "Undead"; 6 -> "Tauren"; 7 -> "Gnome"; 8 -> "Troll"
    10 -> "Blood Elf"; 11 -> "Draenei"; else -> "race $id"
}
