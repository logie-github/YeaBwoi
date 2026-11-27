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
import androidx.compose.runtime.collectAsState
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

/**
 * Options' "Body parts" rows: the user's own Sims 4 body-part mod (a .package, a .zip of them, or a folder), picked on the
 * phone and never part of the app. Its male parts are read and written as part files (bodyparts/PartPackage.open and
 * export, on a background thread) into [root]/<set>/ (files/bodyparts), the set's parts.jsonl naming them; [current] is
 * the set the men wear ([onChanged] with the new one, or null after Remove). Errors are said as they are. NOT run on a
 * phone; the import itself is unit-tested (bodyparts/).
 */
@Composable
fun PartImportSection(root: File, current: String?, profileJson: () -> String, onChanged: (String?) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // written from the import's thread (a StateFlow may be), read here
    val progress = remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    val shownProgress by progress.collectAsState()
    var summary by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(current, busy) {
        summary = if (current == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val entries = com.logie.wifecraft.bodyparts.PartChoice.parse(File(File(root, current), "parts.jsonl").readText())
                "${entries.size} parts (${entries.count { it.state == "DEFAULT" }} default, ${entries.count { it.state == "ON" }} on)"
            }.getOrElse { "its parts list can't be read: import it again" }
        }
    }

    fun start(name: String, stage: (File) -> File) {
        busy = true; message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val work = File(context.cacheDir, "part-import").apply { deleteRecursively(); mkdirs() }
                    try {
                        progress.value = "Copying…"
                        val source = stage(work)
                        progress.value = "Reading the packages…"
                        val set = com.logie.wifecraft.bodyparts.PartPackage.safeName(name.substringBeforeLast('.'))
                        val imp = com.logie.wifecraft.bodyparts.PartPackage.open(source, set)
                        if (imp.parts.none { it.male && it.mesh != null }) throw com.logie.wifecraft.bodyparts.BodyPartFormatException(
                            "$name holds no male body part with a mesh" + (imp.problems.firstOrNull()?.let { " ($it)" } ?: "") + ".")
                        File(root, set).deleteRecursively()
                        val spec = com.logie.wifecraft.bodyparts.AttachSpec.fromProfile(profileJson())
                        val export = com.logie.wifecraft.bodyparts.PartPackage.export(imp, spec, root) { done, total ->
                            progress.value = "Writing parts ${done + 1} / $total"
                        }
                        set to (imp.problems + export.problems)
                    } finally {
                        work.deleteRecursively()
                    }
                }
            }
            progress.value = null
            result.onSuccess { (set, problems) ->
                onChanged(set)
                message = if (problems.isEmpty()) "Imported." else "Imported, with ${problems.size} problems: " + problems.take(3).joinToString("; ")
            }.onFailure { message = it.message ?: "The import failed." }
            busy = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val name = displayName(context, uri)
            start(name) { work ->
                val out = File(work, com.logie.wifecraft.bodyparts.PartPackage.safeName(name.substringBeforeLast('.')) + "." + name.substringAfterLast('.', "package").lowercase().take(8))
                val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("That file can't be opened.")
                input.use { i -> out.outputStream().use { i.copyTo(it) } }
                out
            }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree != null) {
            val name = android.provider.DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/').ifEmpty { "parts" }
            start(name) { work -> File(work, "folder").also { copyPackages(context, tree, it) } }
        }
    }

    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Body parts", style = frizStyle(16.sp))
        Text(
            when {
                busy -> shownProgress ?: "Importing…"
                current != null -> "Imported: $current" + (summary?.let { ", $it" } ?: "")
                else -> "Not imported"
            },
            style = frizStyle(12.sp, WowColors.white),
        )
        Text("Your own Sims 4 body-part mod: a .package, a .zip of them or a folder. Men's settings (size, cut, scrotum) are on each NPC's menu in the NPC picker.",
            style = frizStyle(11.sp, WowColors.grey))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
            WowButton("Choose file", { filePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")) },
                enabled = !busy, width = 140.dp, height = 36.dp)
            WowButton("Choose folder", { folderPicker.launch(null) }, enabled = !busy, width = 150.dp, height = 36.dp)
            WowButton("Remove", {
                val set = current
                if (set != null) {
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { File(root, set).deleteRecursively() } }
                        onChanged(null); message = "Removed."; busy = false
                    }
                }
            }, enabled = !busy && current != null, width = 110.dp, height = 36.dp)
        }
        message?.let { Text(it, style = frizStyle(12.sp, if (it.startsWith("Imported") || it == "Removed.") WowColors.white else androidx.compose.ui.graphics.Color(0xFFFF6A5A))) }
    }
}

/** Every .package under the picked folder [tree] (six folders deep at most) copied into [out], its folders kept. */
private fun copyPackages(context: android.content.Context, tree: Uri, out: File) {
    val resolver = context.contentResolver
    val columns = arrayOf(
        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    fun walk(docId: String, dir: File, depth: Int) {
        if (depth > 6) return
        dir.mkdirs()
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        resolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(id, File(dir, com.logie.wifecraft.bodyparts.PartPackage.safeName(name)), depth + 1)
                } else if (name.endsWith(".package", ignoreCase = true)) {
                    val target = File(dir, com.logie.wifecraft.bodyparts.PartPackage.safeName(name.dropLast(8)) + ".package")
                    resolver.openInputStream(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { i ->
                        target.outputStream().use { i.copyTo(it) }
                    }
                }
            }
        }
    }
    walk(android.provider.DocumentsContract.getTreeDocumentId(tree), out, 0)
}

/**
 * Options' "HD Models" rows: the user's own high-detail characters, converted on a computer (tools/hd/whd_convert.py) into a
 * zip of .whd sets, picked here and imported by [runner] (bodyparts/HdImportRunner, its own thread) into files/bodyparts/hd;
 * the host loads them into the 3D view when it is done. [on]: the switch (every imported race and sex drawn as its HD figure
 * instead of the game's model, in every scene, picture and the creator, Gear on and off). Each set's row says what it offers
 * and has its own Remove. NOT run on a phone; the store and the runner are unit-tested (HdModelsTest).
 */
@Composable
fun HdModelsSection(
    runner: com.logie.wifecraft.bodyparts.HdImportRunner,
    store: com.logie.wifecraft.bodyparts.HdStore,
    on: Boolean,
    onSwitch: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(runner.state) }
    androidx.compose.runtime.DisposableEffect(runner) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val stop = runner.listen { s -> main.post { state = s } }
        onDispose { stop() }
    }
    val busy = state is com.logie.wifecraft.bodyparts.HdImportRunner.State.Running
    var sets by remember { mutableStateOf<List<com.logie.wifecraft.bodyparts.HdSetInfo>>(emptyList()) }
    LaunchedEffect(state) { sets = withContext(Dispatchers.IO) { store.sets() } }
    val work = remember { File(context.cacheDir, "hd-import") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) runner.start(work) { dir ->
            uris.mapIndexed { i, uri ->
                val name = displayName(context, uri)
                val ext = name.substringAfterLast('.', "zip").lowercase().take(8)
                val out = File(dir, "%02d_".format(i) + com.logie.wifecraft.bodyparts.PartPackage.safeName(name.substringBeforeLast('.')) + "." + ext)
                val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("$name can't be opened.")
                input.use { s -> out.outputStream().use { s.copyTo(it) } }
                out
            }
        }
    }
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("HD Models", style = frizStyle(16.sp))
        val s = state
        if (s is com.logie.wifecraft.bodyparts.HdImportRunner.State.Running) Text(s.line, style = frizStyle(12.sp, WowColors.white))
        Text("Your own high-detail characters replace the game's models: a race and sex with an imported set is drawn as its HD " +
            "figure everywhere (its body, face, ears, eyes, hair and its own clothing for her class with Gear on). Convert the " +
            "character's FBX zip (or .blend) on a computer with the app's tools/hd converter, copy the zip it makes (Wifecraft-HD-*.zip) to the " +
            "phone and pick it here. The first picture of each figure takes a moment while its textures are read.",
            style = frizStyle(11.sp, WowColors.grey))
        WowCheckRow("Use HD Models", on, onSwitch, enabled = !busy,
            detail = "On by default: a race and sex with a set is drawn as it, everywhere. Off forces the game's own model for everyone (the sets stay imported). Applies to looks dressed from now on.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
            WowButton("Choose files", { filePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")) },
                enabled = !busy, width = 140.dp, height = 36.dp)
            WowButton("Remove all", { Thread { runner.remove() }.start() }, enabled = !busy && sets.isNotEmpty(), width = 130.dp, height = 36.dp)
        }
        // which races and sexes have a Liard set (every other one is the game's own model: bodyparts/Bodies)
        Text(com.logie.wifecraft.bodyparts.Bodies.coverageLine(sets), style = frizStyle(12.sp, WowColors.white))
        if (sets.isEmpty()) Text("Not imported", style = frizStyle(12.sp, WowColors.white))
        for (set in sets) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(set.label, style = frizStyle(13.sp, WowColors.white))
                    Text("${set.hair.size} hair styles, ${set.outfits.size} outfits, ${set.eyes.size} eyes, ${set.faceMarks.size} face marks, " +
                        "${set.tints.size} skin tints, ${set.jewelry.size} jewellery; up to ${set.triangles / 1000}k triangles, " +
                        "about ${set.gpuBytes / 1_000_000} MB of textures",
                        style = frizStyle(11.sp, WowColors.grey))
                    if (set.credit.isNotEmpty()) Text(set.credit, style = frizStyle(11.sp, WowColors.grey))
                }
                WowButton("Remove", { Thread { runner.remove(set.race, set.sex) }.start() }, enabled = !busy, width = 100.dp, height = 34.dp)
            }
        }
        Text("Credits: the HD models converted from Liard's Art's edited World of Warcraft models are Liard's Art's work " +
            "(liardart.com), used with credit as the site asks; they are not part of this app.", style = frizStyle(11.sp, WowColors.grey))
        when (s) {
            is com.logie.wifecraft.bodyparts.HdImportRunner.State.Failed -> Text(s.message, style = frizStyle(12.sp, androidx.compose.ui.graphics.Color(0xFFFF6A5A)))
            is com.logie.wifecraft.bodyparts.HdImportRunner.State.Done -> Text(s.message, style = frizStyle(12.sp, WowColors.white))
            else -> {}
        }
    }
}
