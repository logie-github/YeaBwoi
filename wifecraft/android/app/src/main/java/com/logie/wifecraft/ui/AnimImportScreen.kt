package com.logie.wifecraft.ui

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.AnimLibrary
import com.logie.wifecraft.anim.ImportInput
import com.logie.wifecraft.anim.ImportPipeline
import com.logie.wifecraft.anim.Retarget
import com.logie.wifecraft.anim.WickedWhimsDownload
import com.logie.wifecraft.sims.DbpfFile
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsRig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// Import Animations: pick files (or a ZIP / 7z), watch each one convert, review the results, save them to the
// animation library. No conversion logic here: anim/ImportPipeline does it, ui/AnimState.kt decides the rest.

/** The retargeter used while no Sims skeleton is loaded: every clip fails with the plain reason. */
class NoRigRetarget : Retarget {
    override fun convert(clip: SimsClip): Anim = throw IllegalStateException(AnimText.NO_RIG)
}

/** [AnimLibrary] as the review's save sees it. */
fun AnimLibrary.asStore(): AnimStore {
    val lib = this
    return object : AnimStore {
        override fun add(a: Anim) = lib.addResult(a)
        override fun categories() = lib.categories()
    }
}

/** The Sims 4 skeleton from a game package the person picked: the best RIG resource's own bytes, stored once. */
object RigImport {
    private const val RIG_TYPE = 0x8EAF13DE.toInt()

    /**
     * Reads [pkg] from disk (index only: the game's packages are hundreds of megabytes), keeps its largest skeleton in
     * [store] and returns the bone count. Throws with a message to show when there is none.
     */
    fun fromFile(pkg: File, store: RigStore): Int {
        return DbpfFile(pkg).use { d ->
            var best: ByteArray? = null
            var bones = 0
            for (e in d.entries.filter { it.type == RIG_TYPE }) {
                val raw = runCatching { d.read(e) }.getOrNull() ?: continue
                val n = runCatching { SimsRig.parse(raw).bones.size }.getOrNull() ?: continue
                if (n > bones) { bones = n; best = raw }
            }
            if (best == null) throw IOException("That package has no skeleton in it. Try the Sims 4's ClientFullBuild0.package or SimulationFullBuild0.package.")
            store.save(best)
            bones
        }
    }

    fun load(store: RigStore): SimsRig? = store.bytes()?.let { runCatching { SimsRig.parse(it) }.getOrNull() }
}

private fun displayName(ctx: Context, uri: Uri): String {
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
}

private enum class Phase { Pick, Downloading, Running, Review, Saving, Saved }

/**
 * The import flow. [retargeter] makes the converter for the loaded skeleton (anim/Retargeter with the profile in
 * assets/sims_profile.json); [library] receives what the review saves.
 */
@Composable
fun AnimImportScreen(
    library: AnimLibrary,
    rigs: RigStore,
    retargeter: (SimsRig) -> Retarget,
    onBack: () -> Unit,
    onLibrary: (() -> Unit)? = null,
    wickedWhims: Boolean = false,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    var phase by remember { mutableStateOf(Phase.Pick) }
    var rev by remember { mutableIntStateOf(0) }
    var run by remember { mutableStateOf(ImportRun()) }
    var draft by remember { mutableStateOf<ReviewDraft?>(null) }
    var saved by remember { mutableStateOf<List<SaveOutcome>>(emptyList()) }
    var download by remember { mutableStateOf(WickedWhimsDownload.Progress()) }
    var retrySeconds by remember { mutableStateOf<Long?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    val busy = phase == Phase.Downloading || phase == Phase.Running || phase == Phase.Saving
    var rigBones by remember { mutableStateOf<Int?>(null) }
    var hasRig by remember { mutableStateOf(rigs.has()) }
    var rigMessage by remember { mutableStateOf<String?>(null) }
    var rigBusy by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf<Int?>(null) }   // -1: for all, n: for item n
    @Suppress("UNUSED_EXPRESSION") rev

    LaunchedEffect(hasRig) {
        rigBones = if (hasRig) withContext(Dispatchers.IO) { RigImport.load(rigs)?.bones?.size } else null
    }
    BackHandler {
        when {
            newCategory != null -> newCategory = null
            busy -> Unit
            phase == Phase.Review -> phase = Phase.Pick
            else -> onBack()
        }
    }

    val pickRig = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        rigBusy = true; rigMessage = "Reading the package for the skeleton…"
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val tmp = File(ctx.cacheDir, "rig-pick.package")
                    try {
                        ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } } ?: throw IOException("The file couldn't be opened.")
                        val n = RigImport.fromFile(tmp, rigs)
                        "Skeleton loaded: $n bones."
                    } finally {
                        tmp.delete()
                    }
                } catch (e: Exception) {
                    e.message ?: e.toString()
                }
            }
            hasRig = rigs.has(); rigMessage = msg; rigBusy = false
        }
    }

    fun cancelWait() {
        if (retrySeconds == null) return
        importJob?.cancel()
        retrySeconds = null
        phase = Phase.Pick
    }

    fun start(inputs: List<ImportInput> = emptyList()) {
        if (phase != Phase.Pick || (!wickedWhims && inputs.isEmpty())) return
        val r = ImportRun()
        run = r
        download = WickedWhimsDownload.Progress()
        retrySeconds = null
        phase = if (wickedWhims) Phase.Downloading else Phase.Running
        importJob = scope.launch {
            val tmp = File(ctx.cacheDir, "anim-import")
            val results = withContext(Dispatchers.IO) {
                try {
                    val rig = RigImport.load(rigs)
                    val pipeline = ImportPipeline(
                        if (rig == null) NoRigRetarget() else retargeter(rig), tmp,
                        embeddedRetargeter = retargeter.takeIf { wickedWhims || rig == null },
                    )
                    fun convert(files: List<ImportInput>) = pipeline.run(files) { event ->
                        main.post { r.apply(event); rev++ }
                    }
                    if (wickedWhims) {
                        val archive = WickedWhimsDownload.openWithRetry(
                            wait = { waitMillis ->
                                val start = SystemClock.elapsedRealtime()
                                while (true) {
                                    val remaining = waitMillis - (SystemClock.elapsedRealtime() - start)
                                    if (remaining <= 0) break
                                    withContext(Dispatchers.Main) { retrySeconds = (remaining - 1) / 1000 + 1 }
                                    delay(minOf(remaining, 1000L))
                                }
                                withContext(Dispatchers.Main) { retrySeconds = null }
                            },
                            onProgress = { progress -> main.post { download = progress } },
                            onDownloaded = { main.post { if (phase == Phase.Downloading) phase = Phase.Running } },
                        )
                        archive.use { convert(listOf(ImportInput("WickedWhims.zip") { it })) }
                    } else convert(inputs)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    main.post { r.apply(com.logie.wifecraft.anim.ImportEvent.Failed("Import", e.message ?: e.toString())) }
                    emptyList()
                }
            }
            r.finish()
            val anims = results.ifEmpty { r.converted }
            rev++
            when {
                anims.isEmpty() -> phase = Phase.Pick
                wickedWhims -> {
                    phase = Phase.Saving
                    saved = withContext(Dispatchers.IO) { ReviewDraft(anims, "WickedWhims").save(library.asStore()) }
                    phase = Phase.Saved
                }
                else -> {
                    draft = ReviewDraft(anims)
                    phase = Phase.Review
                }
            }
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        start(uris.map { uri ->
            ImportInput(displayName(ctx, uri)) {
                ctx.contentResolver.openInputStream(uri) ?: throw IOException("The file couldn't be opened.")
            }
        })
    }

    EditorFrame(if (wickedWhims) "Import WickedWhims" else "Import Animations", { if (!busy) { if (phase == Phase.Review) phase = Phase.Pick else onBack() } },
        actions = { if (onLibrary != null && !busy) TextAction("Animations", onLibrary) }) {
        Crossfade(phase, animationSpec = tween(MenuMotion.duration(MenuMotion.PAGE_MS, reduceMotion())), label = "phase") { step ->
        when (step) {
            Phase.Pick -> if (wickedWhims) {
                WickedWhimsPickPage(run) { start() }
            } else PickPage(
                hasRig, rigBones, rigMessage, rigBusy, run,
                onRig = { pickRig.launch(arrayOf("*/*")) },
                onFiles = { pickFiles.launch(arrayOf("*/*")) },
            )
            Phase.Downloading -> WickedWhimsDownloadPage(download, retrySeconds, ::cancelWait)
            Phase.Running -> ProgressPage(run)
            Phase.Review -> draft?.let { d ->
                ReviewPage(
                    d, library.categories(), run.summary(),
                    changed = { rev++ },
                    onNewCategory = { newCategory = it },
                    onSave = {
                        if (phase == Phase.Review) {
                            phase = Phase.Saving
                            scope.launch {
                                saved = withContext(Dispatchers.IO) { d.save(library.asStore()) }
                                draft = null
                                phase = Phase.Saved
                            }
                        }
                    },
                    onCancel = { if (phase == Phase.Review) phase = Phase.Pick },
                )
            }
            Phase.Saving -> Text("Saving animations…", style = frizStyle(16.sp))
            Phase.Saved -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ReviewDraft.summary(saved) + ".", style = frizStyle(18.sp))
                if (wickedWhims) Text(run.summary(), style = frizStyle(13.sp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(saved, key = { i, _ -> i }, contentType = { _, o -> o::class.simpleName }) { _, o ->
                        val (txt, col) = when (o) {
                            is SaveOutcome.Added -> (o.renamedFrom?.let { "${o.name}: a name like \"$it\" was already taken" } ?: o.name) to Color.White
                            is SaveOutcome.Duplicate -> "${o.name}: already in the library as \"${o.existing}\", skipped" to WowColors.grey
                            is SaveOutcome.Failed -> "${o.name}: failed, ${o.why}" to Color(0xFFFF6A5A)
                        }
                        Text(txt, style = frizStyle(14.sp, col, false))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!wickedWhims || saved.any { it is SaveOutcome.Failed }) {
                        WowButton(if (wickedWhims) "Try again" else "Import more", { phase = Phase.Pick }, width = 190.dp, height = 40.dp)
                    }
                    WowButton("Done", onBack, width = 150.dp, height = 40.dp)
                }
            }
        }
        }
    }

    // fades out still showing itself; a new one never inherits the last one's typing (key)
    rememberHeld(newCategory)?.let { target -> key(target) {
        NameDialog("New category", "", "Use", { newCategory = null }, visible = newCategory != null) { name ->
            if (newCategory == null) return@NameDialog
            newCategory = null
            val c = AnimText.category(name)
            draft?.let { d -> if (target < 0) d.setAllCategories(c) else d.setCategory(target, c) }
            rev++
        }
    } }
}

@Composable
private fun WickedWhimsPickPage(last: ImportRun, onInstall: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Download WickedWhims from the author and install compatible animations in your library.",
            style = frizStyle(14.sp, Color.White, false))
        RedButton(if (last.rows.isEmpty()) "Install" else "Try again", onInstall)
        if (last.rows.isNotEmpty()) {
            Text("Last import: ${last.summary()}", style = frizStyle(14.sp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { rowsList(last.rows) }
        }
    }
}

@Composable
private fun WickedWhimsDownloadPage(progress: WickedWhimsDownload.Progress, retrySeconds: Long?, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (retrySeconds != null) {
            val minutes = retrySeconds / 60
            val seconds = retrySeconds % 60
            val wait = if (minutes == 0L) "$seconds s" else "$minutes min $seconds s"
            Text("The download server is busy. Retrying in $wait…", style = frizStyle(16.sp))
            RedButton("Cancel", onCancel)
            return@Column
        }
        val total = progress.total
        val downloaded = "%.1f MB".format(progress.bytes / 1_000_000.0)
        if (total != null) {
            val fraction = (progress.bytes.toFloat() / total).coerceIn(0f, 1f)
            Text("Downloading WickedWhims… $downloaded / ${"%.1f MB".format(total / 1_000_000.0)}", style = frizStyle(16.sp))
            LoadingBar(fraction, Modifier.fillMaxWidth().height(28.dp).semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            })
        } else {
            Text(if (progress.bytes == 0L) "Connecting to the download site…" else "Downloading WickedWhims… $downloaded",
                style = frizStyle(16.sp))
        }
    }
}

@Composable
private fun PickPage(hasRig: Boolean, bones: Int?, rigMessage: String?, rigBusy: Boolean, last: ImportRun, onRig: () -> Unit, onFiles: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Pick Sims 4 animation files (.package), several at once, or a .zip or .7z holding them in any folders. " +
                "Each animation is converted to Wifecraft's skeleton; you name and sort them before they are saved.",
            style = frizStyle(14.sp, Color.White, false),
        )
        EditorCard(onClick = onRig) {
            Column(Modifier.weight(1f)) {
                Text("Sims 4 skeleton", style = frizStyle(16.sp))
                Text(RigStore.status(hasRig, bones), style = frizStyle(13.sp, if (hasRig) Color(0xFF7CFC7C) else Color(0xFFFF6A5A), false))
                if (!hasRig) Text("Wifecraft can also use a humanoid skeleton included in an animation package.",
                    style = frizStyle(12.sp, WowColors.grey, false))
                rigMessage?.let { Text(it, style = frizStyle(12.sp, Color.White, false)) }
            }
            SmallButton(if (hasRig) "Replace" else "Choose package", onRig, enabled = !rigBusy, width = 150.dp)
        }
        WowButton("Choose animation files", onFiles, Modifier.align(Alignment.CenterHorizontally), width = 280.dp, height = 44.dp)
        if (last.rows.isNotEmpty()) {
            Text("Last import: ${last.summary()}", style = frizStyle(14.sp))
            LazyColumn(Modifier.weight(1f), rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(3.dp)) { rowsList(last.rows) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.rowsList(rows: List<ImportRow>) {
    // rows are only ever added at the end: the place is the key
    itemsIndexed(rows, key = { i, _ -> i }, contentType = { _, r -> if (r.note.isEmpty()) 0 else 1 }) { _, r ->
        val col = when (r.status) {
            ItemStatus.DONE -> Color(0xFF7CFC7C)
            ItemStatus.FAILED -> Color(0xFFFF6A5A)
            ItemStatus.SKIPPED -> WowColors.grey
            else -> Color.White
        }
        Column(Modifier.fillMaxWidth()) {
            Text("${r.status.label}: ${r.name}", style = frizStyle(14.sp, col, false), maxLines = 1)
            if (r.note.isNotEmpty()) Text(r.note, style = frizStyle(12.sp, WowColors.grey, false), modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun ProgressPage(run: ImportRun) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Converting… ${run.summary()}", style = frizStyle(16.sp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        // follows the newest file while it is at the bottom (scrolled up to read, it stays where it was put)
        val list = rememberLazyListState()
        val n = run.rows.size
        LaunchedEffect(n) {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (n > 0 && last >= n - 3) list.animateScrollToItem(n - 1)
        }
        LazyColumn(Modifier.weight(1f).warScrollbar(list), list, verticalArrangement = Arrangement.spacedBy(3.dp)) { rowsList(run.rows) }
    }
}

@Composable
private fun ReviewPage(
    d: ReviewDraft, existing: List<String>, summary: String,
    changed: () -> Unit, onNewCategory: (Int) -> Unit, onSave: () -> Unit, onCancel: () -> Unit,
) {
    val choices = d.categoryChoices(existing).map { it to it }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Review: $summary. Name and sort them, then save.", style = frizStyle(14.sp, Color.White, false))
        if (d.items.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box1(Modifier.weight(1f)) { PickRow("All to", choices, null, { d.setAllCategories(it); changed() }) }
                SmallButton("New", { onNewCategory(-1) }, width = 70.dp)
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // the items never move or go (Skip keeps them): the place is the key
            itemsIndexed(d.items, key = { i, _ -> i }, contentType = { _, _ -> "item" }) { i, it ->
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box1(Modifier.weight(1f)) { EditorField(it.name, { v -> d.setName(i, v.take(AnimText.MAX_NAME)); changed() }, "Name") }
                        SmallButton(if (it.include) "Keep" else "Skip", { d.toggle(i); changed() }, width = 80.dp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box1(Modifier.weight(1f)) { PickRow("Category", choices, AnimText.category(it.category), { c -> d.setCategory(i, c); changed() }) }
                        SmallButton("New", { onNewCategory(i) }, width = 70.dp)
                    }
                    Text(AnimBrowse.describe(it.anim) + " · from " + it.anim.source, style = frizStyle(12.sp, WowColors.grey, false), maxLines = 1)
                }
            }
        }
        Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WowButton("Discard", onCancel, width = 150.dp, height = 40.dp)
            WowButton("Save ${d.chosen}", onSave, enabled = d.chosen > 0, width = 190.dp, height = 40.dp)
        }
    }
}

/** A weighted slot for a field in a row. */
@Composable
private fun Box1(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(modifier) { content() }
}
