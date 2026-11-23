package com.logie.wifecraft.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.logie.wifecraft.anim.AnimLibrary
import com.logie.wifecraft.anim.Retarget
import com.logie.wifecraft.pack.AnimationHandler
import com.logie.wifecraft.pack.EntryStatus
import com.logie.wifecraft.pack.ObjectHandler
import com.logie.wifecraft.pack.PackImporter
import com.logie.wifecraft.pack.PackKind
import com.logie.wifecraft.pack.PackOptions
import com.logie.wifecraft.pack.PackReport
import com.logie.wifecraft.pack.ScriptNoteHandler
import com.logie.wifecraft.props.PropStore
import com.logie.wifecraft.sims.SimsRig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Options → Mods → Import pack: one picker for the pack zip (`tools/build_pack.py` makes it from your own Sims 4 packages). It is
 * unpacked safely (pack/PackImporter: no path outside its folder, no script run, size limits, every file checked against the
 * manifest's sha-256), each package is told by what it holds and sent to its importer: animations to the animation library (they
 * need the Sims skeleton: import WickedWhims first), toys, dolls, handcuffs, the cum-mesh layer and the bed's models to the prop
 * store (files/pack/props). Progress shows as it goes and then one line per file: imported (what), already imported, skipped
 * (why) or failed (why). A stopped import goes on from the next file when the same zip is picked again; **Import again**
 * does everything again. The switches below only stop things being drawn. NOT run on a phone: the importer's logic is unit-tested
 * (pack/), this screen is compiled by CI only.
 */
@Composable
fun PackImportSection(library: AnimLibrary, rigs: RigStore, retargeter: (SimsRig) -> Retarget) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    val root = remember(context) { File(context.filesDir, "pack") }
    val options = remember(root) { PackOptions(File(root, "options.json")) }
    var props by remember { mutableStateOf(options.props) }
    var layer by remember { mutableStateOf(options.bodyLayer) }
    var furniture by remember { mutableStateOf(options.furniture) }
    var busy by remember { mutableStateOf(false) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var report by remember { mutableStateOf<PackReport?>(null) }
    var models by remember { mutableStateOf(0) }
    val progress = remember { MutableStateFlow("") }
    val shown by progress.collectAsState()

    LaunchedEffect(busy) { models = withContext(Dispatchers.IO) { runCatching { PropStore(File(root, "props")).index().size }.getOrDefault(0) } }

    fun run(uri: Uri, force: Boolean) {
        busy = true; report = null; lastUri = uri
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val store = PropStore(File(root, "props"))
                    val handlers = mapOf(
                        PackKind.ANIMATIONS to AnimationHandler(library, { RigImport.load(rigs) }, retargeter, File(context.cacheDir, "pack-anim"), store),
                        PackKind.PROPS to ObjectHandler(store, PackKind.PROPS),
                        PackKind.BODY_LAYER to ObjectHandler(store, PackKind.BODY_LAYER),
                        PackKind.FURNITURE to ObjectHandler(store, PackKind.FURNITURE),
                        PackKind.SCRIPT_INFO to ScriptNoteHandler(),
                    )
                    PackImporter(root, handlers).import({ context.contentResolver.openInputStream(uri) ?: throw IOException("The file couldn't be opened.") }, force) { p ->
                        progress.value = if (p.total > 0) "${p.phase} ${p.done + 1} / ${p.total}: ${p.current}" else p.phase + "…"
                    }
                }.getOrElse { PackReport(null, emptyList(), it.message ?: "The import failed.", emptyList()) }
            }
            progress.value = ""
            report = r
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null && !busy) run(uri, false) }

    WifeSectionHeader("Import pack")
    WifeNote(
        if (models > 0) "$models prop and furniture models are on this phone. Pick the pack zip again to add to it or finish an import that stopped."
        else "One zip with your own Sims 4 animation, toy, doll, handcuff and furniture packages (tools/build_pack.py makes it). Animations need the Sims skeleton first: import WickedWhims."
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WifeTheme.gapS), verticalAlignment = Alignment.CenterVertically) {
        WifeButton(if (busy) "Importing…" else "Import pack", { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")) },
            enabled = !busy, kind = WifeButtonKind.PRIMARY)
        lastUri?.let { uri -> WifeButton("Import again", { run(uri, true) }, enabled = !busy, kind = WifeButtonKind.QUIET) }
    }
    if (busy && shown.isNotEmpty()) WifeNote(shown)
    report?.let { r ->
        val bad = r.problem != null || r.results.any { it.status == EntryStatus.FAILED }
        WifeNote(r.summary(), color = if (bad) Color(0xFFFF6A5A) else WifeTheme.textDim)
        for (x in r.results) {
            Column(Modifier.fillMaxWidth().heightIn(min = WifeTheme.touch)) {
                Text("${x.status.label}: ${x.entry.file}", style = WifeTheme.labelStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(x.detail + if (x.note.isNotEmpty()) " (${x.note})" else "", style = WifeTheme.captionStyle(), maxLines = 4)
            }
        }
        if (r.ignored.isNotEmpty()) WifeNote("Left alone: " + r.ignored.take(4).joinToString("; ") { "${it.first.substringAfterLast('/')} (${it.second})" } + if (r.ignored.size > 4) "…" else "")
        r.manifest?.notes?.forEach { WifeNote(it) }
    }
    WifeSwitchRow("Draw the pack's props", props, { props = it; options.set(props = it) },
        detail = "Toys, dolls and handcuffs, drawn in the stages that name them")
    WifeSwitchRow("Draw the body layer", layer, { layer = it; options.set(bodyLayer = it) },
        detail = "The animation-linked layer (the cum mesh), drawn in the stages that name it")
    WifeSwitchRow("Pack furniture in Build mode", furniture, { furniture = it; options.set(furniture = it) },
        detail = "The pack's bed and other furniture models in the New tab")
}
