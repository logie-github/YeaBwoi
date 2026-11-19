package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.AnimTuning
import com.logie.wifecraft.sims.PairDefinition
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsFormatException
import com.logie.wifecraft.sims.SimsReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/** One thing the player picked: a file name to show and a way to read it (called once). */
class ImportInput(val displayName: String, val open: () -> InputStream)

sealed interface ImportEvent {
    /** A package (name = its path) or one clip in it (name = "path :: clip") was found. */
    data class Found(val name: String) : ImportEvent
    data class Done(val anim: Anim) : ImportEvent
    data class Skipped(val name: String, val why: String) : ImportEvent
    data class Failed(val name: String, val why: String) : ImportEvent
    /** Two clips of a package converted as a partner animation ([pair]); [name] is the pair's. */
    data class Paired(val name: String, val pair: PairAnim? = null, val mode: Pairing.Mode = Pairing.Mode.TUNING) : ImportEvent
    /** A clip that looks like one actor of a pair whose partner was not found ([name] "path :: clip"); it is also
     *  converted alone (a [Done] of its own). The review screen can pair two of these by hand ([ImportPipeline.pairByHand]). */
    data class Unpaired(val name: String, val clip: SimsClip, val packageName: String) : ImportEvent
}

/** What an import made: single animations, partner animations, and the clips that may be half of a pair. */
class ImportResult(val anims: List<Anim>, val pairs: List<PairAnim>, val unpaired: List<ImportEvent.Unpaired>)

/** Sims files to converted animations. Pure: no UI. Picks are single .package files or ZIP/7z archives (nested
 *  folders, one level of nested archives); each package's clips go through [retarget]. One bad file, archive,
 *  package or clip is reported and the rest carries on. The temp folder is removed whatever happens.
 *  A package's clips are first paired ([Pairing]: its tuning, the clip names, the geometry); a pair is converted
 *  together ([Retarget.convertPair]) and reported as [ImportEvent.Paired]; every other clip is converted alone,
 *  those that look like half of a pair also reported as [ImportEvent.Unpaired]. A package of one clip is a single.
 *  The converted animations are returned unsaved: the review screen renames them and adds them to the [AnimLibrary]. */
class ImportPipeline(
    private val retarget: Retarget,
    private val tmpDir: File,
    private val limits: ArchiveLimits = ArchiveLimits(),
    private val reader: (ByteArray) -> List<SimsClip> = { SimsReader.clips(it) },
    /** A package's partner-animation tuning; any failure reads as none. */
    private val tuning: (ByteArray) -> List<PairDefinition> = { AnimTuning.pairs(it) },
) {
    /** The single animations only (partner animations are reported as [ImportEvent.Paired]); see [runAll]. */
    fun run(inputs: List<ImportInput>, onEvent: (ImportEvent) -> Unit): List<Anim> = runAll(inputs, onEvent).anims

    /** Two clips paired on the review screen, converted together (the same shared origin and scale as a found pair). */
    fun pairByHand(a: SimsClip, b: SimsClip): PairAnim = retarget.convertPair(a, b, null)

    fun runAll(inputs: List<ImportInput>, onEvent: (ImportEvent) -> Unit): ImportResult {
        val out = ArrayList<Anim>()
        val pairs = ArrayList<PairAnim>()
        val unpaired = ArrayList<ImportEvent.Unpaired>()
        val emit: (ImportEvent) -> Unit = { e ->
            when (e) {
                is ImportEvent.Paired -> e.pair?.let { pairs += it }
                is ImportEvent.Unpaired -> unpaired += e
                else -> Unit
            }
            onEvent(e)
        }
        tmpDir.mkdirs()
        val work = Files.createTempDirectory(tmpDir.toPath(), "import").toFile()
        try {
            for ((i, input) in inputs.withIndex()) {
                try {
                    handle(input, File(work, "in$i"), emit, out)
                } catch (e: Exception) {
                    emit(ImportEvent.Failed(input.displayName, e.message ?: e.javaClass.simpleName))
                }
            }
        } finally {
            ArchiveImport.deleteTree(work)
        }
        return ImportResult(out, pairs, unpaired)
    }

    private fun handle(input: ImportInput, dir: File, onEvent: (ImportEvent) -> Unit, out: MutableList<Anim>) {
        val name = input.displayName
        val file = try {
            input.open().use { ArchiveImport.spool(it, File(dir, "pick"), limits.maxEntryBytes) }
        } catch (e: ArchiveException) {
            onEvent(ImportEvent.Failed(name, e.message ?: "unreadable")); return
        } catch (e: IOException) {
            onEvent(ImportEvent.Failed(name, "Could not read the file (${e.message ?: e.javaClass.simpleName})")); return
        }
        val packages: List<Pair<String, File>> = when (val kind = ArchiveImport.sniff(file)) {
            ArchiveImport.Kind.PACKAGE -> listOf(name to file)
            ArchiveImport.Kind.ZIP, ArchiveImport.Kind.SEVENZ -> {
                val r = try {
                    ArchiveImport.extract(file, kind, File(dir, "x"), limits)
                } catch (e: ArchiveException) {
                    onEvent(ImportEvent.Failed(name, e.message ?: "invalid archive")); return
                }
                for (p in r.problems) onEvent(ImportEvent.Skipped(name, p))
                if (r.files.isEmpty()) {
                    onEvent(ImportEvent.Skipped(name, "No .package files found in this archive"))
                    return
                }
                r.files.map { (n, f) -> "$name/$n" to f }
            }
            else -> {
                onEvent(ImportEvent.Failed(name, "Not a Sims 4 .package file or a ZIP/7z archive"))
                return
            }
        }
        for ((pname, pfile) in packages) {
            try {
                onePackage(pname, pfile, onEvent, out)
            } catch (e: Exception) {
                onEvent(ImportEvent.Failed(pname, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun onePackage(pname: String, pfile: File, onEvent: (ImportEvent) -> Unit, out: MutableList<Anim>) {
        onEvent(ImportEvent.Found(pname))
        if (pfile.length() > MAX_PACKAGE) {
            onEvent(ImportEvent.Failed(pname, "The package is larger than ${MAX_PACKAGE shr 20} MB, too big to read on the phone")); return
        }
        val bytes = pfile.readBytes()
        val clips = try {
            reader(bytes)
        } catch (e: SimsFormatException) {
            onEvent(ImportEvent.Failed(pname, e.message ?: "not a readable Sims 4 package")); return
        }
        if (clips.isEmpty()) {
            onEvent(ImportEvent.Skipped(pname, "No animation clips in this package (it may hold only objects, CAS parts or other mods)")); return
        }
        val defs = try { tuning(bytes) } catch (e: Exception) { emptyList() }
        val found = if (clips.size < 2) Pairing.Result(emptyList(), clips, emptyList(), emptyList()) else Pairing.find(clips, defs)
        val base = pname.substringAfterLast('/').removeSuffix(".package").removeSuffix(".Package")
        // the pairs first, then every other clip alone, in the package's order
        for (f in found.pairs) {
            val label = "$pname :: ${f.a.name} + ${f.b.name}"
            onEvent(ImportEvent.Found(label))
            try {
                val p = retarget.convertPair(f.a, f.b, f.def)
                onEvent(ImportEvent.Paired(p.name, p, f.mode))
            } catch (e: Exception) {
                onEvent(ImportEvent.Failed(label, e.message ?: e.javaClass.simpleName))
                // a pair that does not convert is still two clips the person can try by hand
                for (c in listOf(f.a, f.b)) onEvent(ImportEvent.Unpaired("$pname :: ${c.name}", c, pname))
            }
        }
        val half = found.candidates.toHashSet()
        for (clip in clips) {
            if (clip !in half && clip !in found.singles) continue
            val cname = clip.name.ifBlank { base }
            val label = "$pname :: $cname"
            onEvent(ImportEvent.Found(label))
            try {
                var a = retarget.convert(clip)
                if (a.name.isBlank()) a = a.copy(name = cname)
                if (a.source.isBlank()) a = a.copy(source = clip.sourceKey)
                out.add(a)
                onEvent(ImportEvent.Done(a))
            } catch (e: Exception) {
                onEvent(ImportEvent.Failed(label, e.message ?: e.javaClass.simpleName))
            }
            if (clip in half) onEvent(ImportEvent.Unpaired(label, clip, pname))
        }
        // what the pairing could not use, after the results (a note never stands in for the package's row)
        for (n in found.notes) onEvent(ImportEvent.Skipped(pname, n))
    }

    companion object { const val MAX_PACKAGE = 256L shl 20 }
}
