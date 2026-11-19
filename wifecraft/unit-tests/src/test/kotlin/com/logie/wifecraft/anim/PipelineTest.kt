package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsFormatException
import com.logie.wifecraft.sims.SimsTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class PipelineTest {
    private fun tmp() = Files.createTempDirectory("pipe").toFile()

    /** A fake "package": DBPF magic plus a script line per clip ("name" or "!boom" or "!fmt"). */
    private fun pkg(vararg clips: String) = ("DBPF\n" + clips.joinToString("\n")).toByteArray()
    private fun zip(vararg e: Pair<String, ByteArray>): ByteArray {
        val b = ByteArrayOutputStream(); ZipOutputStream(b).use { z -> for ((n, d) in e) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }; return b.toByteArray()
    }

    private val reader: (ByteArray) -> List<SimsClip> = { bytes ->
        val lines = String(bytes).lines().drop(1).filter { it.isNotEmpty() }
        if (lines == listOf("!fmt")) throw SimsFormatException("Not a Sims 4 package: bad header")
        lines.map { SimsClip(if (it == "-") "" else it.removePrefix("!"), 1f,
            listOf(SimsTrack(1, Channel.POSITION, floatArrayOf(0f), floatArrayOf(0f, 0f, 0f))), "hash:$it") }
    }
    private val fake = object : Retarget {
        override fun convert(clip: SimsClip): Anim {
            if (clip.name == "boom") throw IllegalStateException("no bones matched")
            return Anim("", clip.name, "", clip.duration, false, listOf(BoneTrack("Root", Channel.POSITION, floatArrayOf(0f), floatArrayOf(0f, 0f, 0f))), "", 0)
        }
    }

    private fun run(tmpDir: File, vararg inputs: Pair<String, ByteArray>, limits: ArchiveLimits = ArchiveLimits()): Pair<List<Anim>, List<ImportEvent>> {
        val ev = ArrayList<ImportEvent>()
        val out = ImportPipeline(fake, tmpDir, limits, reader).run(inputs.map { (n, b) -> ImportInput(n) { b.inputStream() } }) { ev.add(it) }
        return out to ev
    }

    @Test fun singleFilesZipAndErrorsNeverStopTheRest() {
        val t = tmp()
        val (anims, ev) = run(t,
            "sit.package" to pkg("Sit", "Stand"),
            "pack.zip" to zip("Dir/a.package" to pkg("Wave", "boom", "Dance"), "Dir/empty.package" to pkg(), "bad.package" to pkg("!fmt"), "x.txt" to byteArrayOf(1)),
            "notes.txt" to "hello".toByteArray(),
            "broken.zip" to "PK\u0003\u0004 not really".toByteArray())
        assertEquals(listOf("Sit", "Stand", "Wave", "Dance"), anims.map { it.name })
        assertEquals("hash:Sit", anims[0].source)         // source defaults to the clip's key
        assertEquals(4, ev.count { it is ImportEvent.Done })
        val failed = ev.filterIsInstance<ImportEvent.Failed>()
        assertEquals(4, failed.size, failed.toString())
        assertTrue(failed.any { it.name.endsWith(":: boom") && it.why.contains("no bones") })
        assertTrue(failed.any { it.name == "pack.zip/bad.package" && it.why.contains("bad header") })
        assertTrue(failed.any { it.name == "notes.txt" })
        assertTrue(failed.any { it.name == "broken.zip" })
        assertTrue(ev.filterIsInstance<ImportEvent.Skipped>().any { it.name == "pack.zip/Dir/empty.package" && it.why.contains("No animation clips") })
        assertTrue(ev.contains(ImportEvent.Found("pack.zip/Dir/a.package")))
        assertTrue(ev.contains(ImportEvent.Found("pack.zip/Dir/a.package :: Wave")))
        assertEquals(0, t.listFiles()!!.size, "temp folder cleaned")
    }

    @Test fun nameDefaultsToFileNameWhenClipHasNone() {
        val t = tmp()
        val (anims, _) = run(t, "My pose.package" to pkg("-"))
        assertEquals(listOf("My pose"), anims.map { it.name })
    }

    @Test fun unreadableInputAndUnsafeArchiveEntriesAreReported() {
        val t = tmp(); val ev = ArrayList<ImportEvent>()
        val out = ImportPipeline(fake, t, ArchiveLimits(), reader).run(listOf(
            ImportInput("gone.package") { throw IOException("permission denied") },
            ImportInput("evil.zip") { zip("../x.package" to pkg("A"), "ok.package" to pkg("B")).inputStream() })) { ev.add(it) }
        assertEquals(listOf("B"), out.map { it.name })
        assertTrue(ev.filterIsInstance<ImportEvent.Failed>().any { it.name == "gone.package" })
        assertTrue(ev.filterIsInstance<ImportEvent.Skipped>().any { it.why.contains("unsafe") })
        assertEquals(0, t.listFiles()!!.size)
    }

    @Test fun bombArchiveFailsAloneAndCleansUp() {
        val t = tmp()
        val (anims, ev) = run(t, "bomb.zip" to zip("a.package" to ByteArray(3 shl 20)), "ok.package" to pkg("Fine"),
            limits = ArchiveLimits(maxTotalBytes = 1 shl 20))
        assertEquals(listOf("Fine"), anims.map { it.name })
        assertTrue(ev.filterIsInstance<ImportEvent.Failed>().single().why.contains("bomb"))
        assertEquals(0, t.listFiles()!!.size)
    }

    @Test fun importedAnimsGoIntoTheLibraryWithTheDuplicatePolicy() {
        val (anims, _) = run(tmp(), "a.package" to pkg("Sit"), "b.package" to pkg("Sit"))
        val lib = AnimLibrary(tmp())
        val results = anims.map { lib.addResult(it) }
        assertEquals(listOf(false, true), results.map { it.duplicate })   // same file hash+clip key
        assertEquals(1, lib.list().size)
    }

    @Test fun realReaderRefusesGarbageWithItsMessage() {
        val t = tmp(); val ev = ArrayList<ImportEvent>()
        ImportPipeline(fake, t).run(listOf(ImportInput("x.package") { "DBPF-but-not-really".toByteArray().inputStream() })) { ev.add(it) }
        assertTrue(ev.any { it is ImportEvent.Failed || it is ImportEvent.Skipped }, ev.toString())
    }
}
