package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.DbpfFile
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
}

/** Sims files to converted animations. Pure: no UI. Picks are single .package files or ZIP/7z archives (nested
 *  folders, one level of nested archives); each package's clips go through [retarget]. One bad file, archive,
 *  package or clip is reported and the rest carries on. The temp folder is removed whatever happens.
 *  The converted animations are returned unsaved: the review screen renames them and adds them to the [AnimLibrary]. */
class ImportPipeline(
    private val retarget: Retarget,
    private val tmpDir: File,
    private val limits: ArchiveLimits = ArchiveLimits(),
    private val reader: ((ByteArray) -> List<SimsClip>)? = null,
    private val embeddedRetargeter: ((SimsRig) -> Retarget)? = null,
) {
    fun run(inputs: List<ImportInput>, onEvent: (ImportEvent) -> Unit): List<Anim> {
        val out = ArrayList<Anim>()
        tmpDir.mkdirs()
        val work = Files.createTempDirectory(tmpDir.toPath(), "import").toFile()
        try {
            for ((i, input) in inputs.withIndex()) {
                try {
                    handle(input, File(work, "in$i"), onEvent, out)
                } catch (e: Exception) {
                    onEvent(ImportEvent.Failed(input.displayName, e.message ?: e.javaClass.simpleName))
                }
            }
        } finally {
            ArchiveImport.deleteTree(work)
        }
        return out
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
            val clips = reader(pfile.readBytes())
            for (clip in clips) convert(pname, base, clip, { retarget }, onEvent, out)
            clips.size
        } else {
            DbpfFile(pfile).use { pkg ->
                val rigs = if (embeddedRetargeter != null) SimsReader.humanoidRigs(pkg) else emptyList()
                val converters = HashMap<SimsRig, Retarget>()
                fun converter(clip: SimsClip): Retarget {
                    if (rigs.isEmpty() || embeddedRetargeter == null) return retarget
                    val hashes = clip.tracks.mapTo(HashSet()) { it.boneHash }
                    val rig = rigs.maxBy { it.coverage(hashes) }
                    return converters.getOrPut(rig) { embeddedRetargeter.invoke(rig) }
                }
                SimsReader.visitClips(pkg,
                    onClip = { clip -> convert(pname, base, clip, ::converter, onEvent, out) },
                    onFailure = { key, why ->
                        val label = "$pname :: $key"
                        onEvent(ImportEvent.Found(label))
                        onEvent(ImportEvent.Failed(label, why))
                    },
                )
            }
        }
        if (count == 0) onEvent(ImportEvent.Skipped(pname, "No animation clips in this package (it may hold only objects, CAS parts or other mods)"))
    }

    private fun convert(
        packageName: String,
        base: String,
        clip: SimsClip,
        retargetForClip: (SimsClip) -> Retarget,
        onEvent: (ImportEvent) -> Unit,
        out: MutableList<Anim>,
    ) {
        val name = clip.name.ifBlank { base }
        val label = "$packageName :: $name"
        onEvent(ImportEvent.Found(label))
        try {
            val anim = retargetForClip(clip).convert(clip)
            val result = anim.copy(name = anim.name.ifBlank { name }, source = anim.source.ifBlank { clip.sourceKey })
            out.add(result)
            onEvent(ImportEvent.Done(result))
        } catch (e: Exception) {
            onEvent(ImportEvent.Failed(label, e.message ?: e.javaClass.simpleName))
        }
    }

    companion object { const val MAX_PACKAGE = 256L shl 20 }
}
