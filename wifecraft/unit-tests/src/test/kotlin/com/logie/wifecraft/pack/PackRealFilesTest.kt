package com.logie.wifecraft.pack

import com.logie.wifecraft.props.PropStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Reads the packages in the folder named by the environment variable WIFECRAFT_PACK_FILES (the player's own animation, toy and
 * furniture packs: never part of the repository) through the app's classifier and prop store, and prints what it found to
 * WIFECRAFT_PACK_OUT when that names a file; the prop store goes to WIFECRAFT_PACK_STORE when that names a folder (for the desktop harness). Does nothing without the variable.
 */
class PackRealFilesTest {
    @Test fun readsTheRealPackages() {
        val dir = System.getenv("WIFECRAFT_PACK_FILES")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".package") }.sortedBy { it.name }.toList()
        val contents = files.associateWith { PackClassifier.read(it) }
        val refs = contents.values.flatMapTo(HashSet()) { it.propRefs }
        val store = PropStore(System.getenv("WIFECRAFT_PACK_STORE")?.let(::File) ?: Files.createTempDirectory("realprops").toFile())
        val out = StringBuilder()
        for ((f, c) in contents) {
            val kind = PackClassifier.kindOf(c, refs)
            out.append("${f.name}: $kind, ${c.resources} resources, ${c.clips} clips, ${c.stages.size} stages, ${c.objects.size} objects, ${c.audio} SNR")
            if (kind == PackKind.PROPS || kind == PackKind.BODY_LAYER || kind == PackKind.FURNITURE || kind == PackKind.ANIMATIONS) {
                val r = store.importPackage(f, kind)
                out.append("; props: ${r.objects} objects, ${r.clips} clips, ${r.uses} links, problems ${r.problems}")
            }
            out.append('\n')
        }
        for (i in store.index()) {
            val m = store.model(i.guid)!!
            out.append("  ${i.guid} ${i.kind} ${m.vertexCount} v ${m.triangleCount} t ${m.textures.size} tex size ${m.size.joinToString("x") { "%.2f".format(it) }} bones ${m.bones.size}")
            if (i.kind == PackKind.FURNITURE) com.logie.wifecraft.props.PropFootprint.of(m).let { out.append(" footprint half ${"%.2f".format(it.halfX)} x ${"%.2f".format(it.halfY)} yd, surface ${"%.2f".format(it.surface)} yd") }
            out.append('\n')
        }
        System.getenv("WIFECRAFT_PACK_OUT")?.let { File(it).writeText(out.toString()) }
        assertTrue(files.isNotEmpty())
    }

    /** The real pack.zip (WIFECRAFT_PACK_ZIP) through the importer with the object handlers and a recording animation handler. */
    @Test fun importsTheRealPack() {
        val zip = System.getenv("WIFECRAFT_PACK_ZIP")?.let(::File)?.takeIf { it.isFile } ?: return
        val root = Files.createTempDirectory("realpack").toFile()
        val store = PropStore(File(root, "props"))
        val handlers = mapOf(
            PackKind.ANIMATIONS to PackHandler { e, _, c ->
                HandlerResult(EntryStatus.IMPORTED, "${c?.clips} clips, ${c?.stages?.size} stages (recorded only)")
            },
            PackKind.PROPS to ObjectHandler(store, PackKind.PROPS), PackKind.BODY_LAYER to ObjectHandler(store, PackKind.BODY_LAYER),
            PackKind.FURNITURE to ObjectHandler(store, PackKind.FURNITURE), PackKind.SCRIPT_INFO to ScriptNoteHandler(),
        )
        val imp = PackImporter(File(root, "pack"), handlers)
        val out = StringBuilder()
        val t0 = System.nanoTime()
        val r = imp.import({ zip.inputStream() }) { p -> if (p.phase == "Importing") out.append("") }
        out.append("first import (${(System.nanoTime() - t0) / 1_000_000} ms): ${r.summary()}\n")
        for (x in r.results) out.append("  ${x.status.label.padEnd(10)} ${x.routedAs.key.padEnd(11)} ${x.entry.file}: ${x.detail}${if (x.note.isNotEmpty()) " [" + x.note + "]" else ""}\n")
        for (i in r.ignored) out.append("  ignored ${i.first}: ${i.second}\n")
        val r2 = imp.import({ zip.inputStream() })
        out.append("second import: ${r2.summary()}\n")
        System.getenv("WIFECRAFT_PACK_OUT2")?.let { File(it).writeText(out.toString()) }
        assertTrue(r.ok, r.summary())
    }
}
