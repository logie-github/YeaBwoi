package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.AnimTuning
import com.logie.wifecraft.sims.DbpfFile
import com.logie.wifecraft.sims.PairDefinition
import com.logie.wifecraft.sims.SimsFormatException
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsReader
import com.logie.wifecraft.sims.SimsRig
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
 *  The converted animations are returned unsaved: the review screen renames them and adds them to the [AnimLibrary].
 *  A converted clip or pair that would play broken ([ClipValidation]) is reported as [ImportEvent.Skipped], not returned. */
class ImportPipeline(
    private val retarget: Retarget,
    private val tmpDir: File,
    private val limits: ArchiveLimits = ArchiveLimits(),
    /** Reads a package's clips from its bytes (tests); null: the package is read from disk one resource at a time. */
    private val reader: ((ByteArray) -> List<SimsClip>)? = null,
    /** Makes the converter for a humanoid skeleton found inside a package (null: only [retarget]). */
    private val embeddedRetargeter: ((SimsRig) -> Retarget)? = null,
    /** A package's partner-animation tuning, with [reader]; any failure reads as none. */
    private val tuning: (ByteArray) -> List<PairDefinition> = { AnimTuning.pairs(it) },
    /**
     * Where each converted clip is written as it comes (anim/ClipStore): the results are then light animations whose tracks
     * stay on disk until played, so a pack of thousands of clips never sits in memory. Null: kept in memory (tests, the
     * pairing by hand). When the phone runs out of room the import stops there and keeps what it converted.
     */
    private val staging: ImportStaging? = null,
) {
    /** The free space ran out ([staging]): the clips after it are not converted, and counted. */
    private var full: StorageFullException? = null
    private var leftForSpace = 0

    /** Each converted clip's skeleton scale ([Retargeter.unitsPerMetre]) by its source, for its performances' offsets (one package's). */
    private val unitsBySource = HashMap<String, Float>()

    /** The humanoid skeletons found inside the packages, each with its RIG bytes and how many clips were converted with it. */
    private val embeddedUse = HashMap<SimsRig, Int>()
    private val embeddedBytes = HashMap<SimsRig, ByteArray>()

    /**
     * The RIG bytes of the embedded humanoid skeleton most clips of this import were converted with (null: none was used).
     * A pack that carries the Sims skeleton (WickedWhims' own) can leave it for the packs that do not carry one.
     */
    fun mostUsedEmbeddedRig(): ByteArray? = embeddedUse.maxByOrNull { it.value }?.key?.let { embeddedBytes[it] }

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
        full = null; leftForSpace = 0
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
        full?.let { e ->
            emit(ImportEvent.Skipped("Import", "Stopped early: ${e.message} ${out.size} animations were kept" +
                (if (leftForSpace > 0) "; $leftForSpace more clips were not converted" else "") + ". Free some space and import the pack again for the rest."))
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
            onEvent(ImportEvent.Failed(pname, "The package exceeds the ${MAX_PACKAGE shr 20} MB import limit")); return
        }
        val base = pname.substringAfterLast('/').substringBeforeLast('.')
        val count = if (reader != null) {
            val bytes = pfile.readBytes()
            val clips = try {
                reader(bytes)
            } catch (e: SimsFormatException) {
                onEvent(ImportEvent.Failed(pname, e.message ?: "not a readable Sims 4 package")); return
            }
            val defs = try { tuning(bytes) } catch (e: Exception) { emptyList() }
            pairThenConvert(pname, base, clips, defs, { retarget }, onEvent, out)
            clips.size
        } else {
            DbpfFile(pfile).use { pkg ->
                val found = if (embeddedRetargeter != null) SimsReader.humanoidRigResources(pkg) else emptyList()
                val rigs = found.map { it.first }
                for ((rig, bytes) in found) embeddedBytes[rig] = bytes
                val converters = HashMap<SimsRig, Retarget>()
                fun converter(clip: SimsClip): Retarget {
                    if (rigs.isEmpty() || embeddedRetargeter == null) return retarget
                    val hashes = clip.tracks.mapTo(HashSet()) { it.boneHash }
                    val rig = rigs.maxBy { it.coverage(hashes) }
                    embeddedUse.merge(rig, 1, Int::plus)
                    return converters.getOrPut(rig) { embeddedRetargeter.invoke(rig) }
                }
                // the furniture's or a prop's own clips (the tuning plays them on an object) and the parts of animations
                // for three or more people (a scene plays one or two): not converted, said why (ImportedPerformances.clipUse)
                val leftOut = runCatching { ImportedPerformances.clipUse(pkg).leftOut() }.getOrDefault(emptyMap())
                val first = out.size
                unitsBySource.clear()
                // A package of up to PAIRING_LIMIT clips is held whole so its partner animations can be found (Pairing);
                // a bigger one (a large mod archive's package) is converted one clip at a time, as read.
                val held = ArrayList<SimsClip>()
                var streaming = false
                val visited = SimsReader.visitClips(pkg,
                    onClip = { clip ->
                        val why = leftOut[clip.name.lowercase()]
                        if (why != null) {
                            val label = "$pname :: ${clip.name}"
                            onEvent(ImportEvent.Found(label))
                            onEvent(ImportEvent.Skipped(label, why))
                        } else if (!streaming && held.size < PAIRING_LIMIT) held += clip
                        else {
                            if (!streaming) {
                                streaming = true
                                for (c in held) convert(pname, base, c, ::converter, onEvent, out)
                                held.clear()
                            }
                            convert(pname, base, clip, ::converter, onEvent, out)
                        }
                    },
                    onFailure = { key, why ->
                        val label = "$pname :: $key"
                        onEvent(ImportEvent.Found(label))
                        onEvent(ImportEvent.Failed(label, why))
                    },
                )
                if (!streaming && held.isNotEmpty()) {
                    // the author's performances (ImportedPerformances) already pair their clips: those are left to them
                    val names = held.associate { it.name.lowercase() to it.sourceKey }
                    val authored = ImportedPerformances.read(pkg, names).flatMapTo(HashSet()) { p -> p.roles.map { it.source } }
                    val free = held.filter { it.sourceKey !in authored }
                    val authoredNames = held.filter { it.sourceKey in authored }.mapTo(HashSet()) { it.name.lowercase() }
                    val defs = if (free.size < 2) emptyList() else runCatching { AnimTuning.pairs(pkg) }.getOrDefault(emptyList())
                        .filter { def -> def.actors.none { it.clipName.lowercase() in authoredNames } }
                    pairThenConvert(pname, base, free, defs, ::converter, onEvent, out)
                    for (clip in held) if (clip.sourceKey in authored) convert(pname, base, clip, ::converter, onEvent, out)
                }
                val sources = mutableMapOf<String, String>()
                for (index in first until out.size) {
                    val anim = out[index]
                    sources.putIfAbsent(anim.sourceName.ifBlank { anim.name }.lowercase(), anim.source)
                }
                val groups = ImportedPerformances.read(pkg, sources).groupBy { it.roles.first { role -> role.source.isNotBlank() }.source }
                for (index in first until out.size) {
                    val clip = out[index]
                    // the tuning's offsets in Sims metres, scaled later as this clip's skeleton scales its root movement
                    val units = unitsBySource[clip.source] ?: 0f
                    val performances = groups[clip.source].orEmpty().map { it.copy(scale = units) }
                    if (performances.isNotEmpty()) out[index] = clip.copy(performances = performances)
                }
                visited
            }
        }
        if (count == 0) onEvent(ImportEvent.Skipped(pname, "No animation clips in this package (it may hold only objects, CAS parts or other mods)"))
    }

    /**
     * [clips] of one package: first its partner animations ([Pairing]: tuning [defs], names, geometry), converted together
     * ([Retarget.convertPair]) and reported as [ImportEvent.Paired]; then every other clip alone, in the package's order,
     * those that look like half of a pair also reported as [ImportEvent.Unpaired]; then the pairing's notes.
     */
    private fun pairThenConvert(
        pname: String, base: String, clips: List<SimsClip>, defs: List<PairDefinition>,
        retargetForClip: (SimsClip) -> Retarget, onEvent: (ImportEvent) -> Unit, out: MutableList<Anim>,
    ) {
        if (clips.isEmpty()) return
        if (full != null) { leftForSpace += clips.size; return }
        val found = if (clips.size < 2) Pairing.Result(emptyList(), clips, emptyList(), emptyList()) else Pairing.find(clips, defs)
        for (f in found.pairs) {
            val label = "$pname :: ${f.a.name} + ${f.b.name}"
            onEvent(ImportEvent.Found(label))
            try {
                val p = retargetForClip(f.a).convertPair(f.a, f.b, f.def)
                // a pair that would play broken is said and left out, never saved (ClipValidation)
                val broken = ClipValidation.problem(p)
                if (broken != null) onEvent(ImportEvent.Skipped(label, broken))
                else {
                    // what it needs (a bed, a seat...) read with the Sims clips' own names and slot targets, for the library
                    seed { RequirementStore.seed(RequirementStore.keyOf(p), RequirementClassifier.classify(p, RequirementStore.hints(p, f.a, f.b))) }
                    val kept = try { staging?.putPair(p) ?: p } catch (e: StorageFullException) {
                        full = e; onEvent(ImportEvent.Skipped(label, e.message ?: "The phone is full")); continue
                    }
                    onEvent(ImportEvent.Paired(kept.name, kept, f.mode))
                }
            } catch (e: Exception) {
                onEvent(ImportEvent.Failed(label, e.message ?: e.javaClass.simpleName))
                // a pair that does not convert is still two clips the person can try by hand
                for (c in listOf(f.a, f.b)) onEvent(ImportEvent.Unpaired("$pname :: ${c.name}", c, pname))
            }
        }
        val half = found.candidates.toHashSet()
        for (clip in clips) {
            if (clip !in half && clip !in found.singles) continue
            convert(pname, base, clip, retargetForClip, onEvent, out)
            if (clip in half) onEvent(ImportEvent.Unpaired("$pname :: ${clip.name.ifBlank { base }}", clip, pname))
        }
        // what the pairing could not use, after the results (a note never stands in for the package's row)
        for (n in found.notes) onEvent(ImportEvent.Skipped(pname, n))
    }

    private fun convert(
        packageName: String,
        base: String,
        clip: SimsClip,
        retargetForClip: (SimsClip) -> Retarget,
        onEvent: (ImportEvent) -> Unit,
        out: MutableList<Anim>,
    ) {
        if (full != null) { leftForSpace++; return }
        val name = clip.name.ifBlank { base }
        val label = "$packageName :: $name"
        onEvent(ImportEvent.Found(label))
        try {
            val converter = retargetForClip(clip)
            val anim = converter.convert(clip)
            val result = anim.copy(name = anim.name.ifBlank { name }, source = anim.source.ifBlank { clip.sourceKey })
            (converter as? Retargeter)?.unitsPerMetre?.takeIf { it > 0.0 && it.isFinite() }?.let { unitsBySource[result.source] = it.toFloat() }
            // a clip that would play broken is said and left out, never saved (ClipValidation)
            val broken = ClipValidation.problem(result)
            if (broken != null) { onEvent(ImportEvent.Skipped(label, broken)); return }
            seed { RequirementStore.seed(RequirementStore.keyOf(result), RequirementClassifier.classify(result, RequirementStore.hints(result, clip))) }
            // written to the staging folder now: from here on only its light form (no tracks in memory) is kept
            val kept = try { staging?.put(result) ?: result } catch (e: StorageFullException) {
                full = e; leftForSpace++; onEvent(ImportEvent.Skipped(label, e.message ?: "The phone is full")); return
            }
            out.add(kept)
            onEvent(ImportEvent.Done(kept))
        } catch (e: Exception) {
            onEvent(ImportEvent.Failed(label, e.message ?: e.javaClass.simpleName))
        }
    }

    /** A requirement's classification never fails an import: it is made again when the library first meets the animation. */
    private inline fun seed(block: () -> Unit) {
        try { block() } catch (e: Exception) { }
    }

    companion object {
        /** A package is read from disk one resource at a time, so its size only has to fit the archive limit (a 290 MB pack exists). */
        const val MAX_PACKAGE = 512L shl 20
        /** Packages with at most this many clips are searched for partner animations ([Pairing]). */
        const val PAIRING_LIMIT = 64
    }
}
