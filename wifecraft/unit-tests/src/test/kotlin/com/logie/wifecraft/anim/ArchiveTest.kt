package com.logie.wifecraft.anim

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ArchiveTest {
    private fun tmp() = Files.createTempDirectory("arch").toFile()
    private val pkg = "DBPF".toByteArray() + ByteArray(60) { it.toByte() }

    private fun zip(vararg e: Pair<String, ByteArray>): ByteArray {
        val b = ByteArrayOutputStream()
        ZipOutputStream(b).use { z -> for ((n, d) in e) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return b.toByteArray()
    }

    private fun sevenZ(vararg e: Pair<String, ByteArray>): ByteArray {
        val f = File.createTempFile("seven", ".7z"); f.delete()
        SevenZOutputFile(f).use { s ->
            for ((n, d) in e) { val en = s.createArchiveEntry(File(n), n); en.size = d.size.toLong(); s.putArchiveEntry(en); s.write(d); s.closeArchiveEntry() }
        }
        return f.readBytes().also { f.delete() }
    }

    private fun extract(bytes: ByteArray, kind: ArchiveImport.Kind, limits: ArchiveLimits = ArchiveLimits()): Pair<ExtractResult, File> {
        val d = tmp(); val f = File(d, "a.bin"); f.writeBytes(bytes)
        return ArchiveImport.extract(f, kind, File(d, "out"), limits) to d
    }

    @Test fun sniffsKinds() {
        val d = tmp()
        fun k(b: ByteArray) = File(d, "f").also { it.writeBytes(b) }.let { ArchiveImport.sniff(it) }
        assertEquals(ArchiveImport.Kind.ZIP, k(zip("a" to byteArrayOf(1))))
        assertEquals(ArchiveImport.Kind.SEVENZ, k(sevenZ("a" to byteArrayOf(1))))
        assertEquals(ArchiveImport.Kind.PACKAGE, k(pkg))
        assertEquals(ArchiveImport.Kind.OTHER, k("hello".toByteArray()))
        assertEquals(ArchiveImport.Kind.OTHER, k(ByteArray(0)))
    }

    @Test fun zipWithNestedFoldersKeepsOnlyPackages() {
        val (r, d) = extract(zip("Pack/Sit/a.package" to pkg, "Pack/readme.txt" to "x".toByteArray(), "b.PACKAGE" to pkg, "Pack/Empty/" to ByteArray(0)), ArchiveImport.Kind.ZIP)
        assertEquals(setOf("Pack/Sit/a.package", "b.PACKAGE"), r.files.map { it.first }.toSet())
        assertTrue(r.files.all { it.second.readBytes().contentEquals(pkg) })
        assertTrue(r.problems.isEmpty())
        ArchiveImport.deleteTree(d); assertFalse(d.exists())
    }

    @Test fun sevenZWorksToo() {
        val (r, _) = extract(sevenZ("x/a.package" to pkg, "x/notes.txt" to byteArrayOf(1)), ArchiveImport.Kind.SEVENZ)
        assertEquals(listOf("x/a.package"), r.files.map { it.first })
        assertTrue(r.files[0].second.readBytes().contentEquals(pkg))
    }

    @Test fun unsafePathsRefusedAndNothingEscapes() {
        val bad = listOf("../evil.package", "..\\evil2.package", "/abs/evil3.package", "C:/evil4.package", "C:\\evil5.package", "a/../../evil6.package")
        val (r, d) = extract(zip(*(bad.map { it to pkg } + ("good.package" to pkg)).toTypedArray()), ArchiveImport.Kind.ZIP)
        assertEquals(listOf("good.package"), r.files.map { it.first })
        assertEquals(bad.size, r.problems.size)
        assertTrue(r.problems.all { it.startsWith("Refused unsafe path") })
        // nothing was written outside the extraction folder, in or beside it
        assertEquals(setOf("a.bin", "out"), d.list()!!.toSet())
        assertFalse(File(d.parentFile, "evil.package").exists())
    }

    @Test fun safeNameRules() {
        assertEquals("a/b.package", ArchiveImport.safeName("./a//b.package"))
        assertEquals("a/b.package", ArchiveImport.safeName("a\\b.package"))
        assertNull(ArchiveImport.safeName("")); assertNull(ArchiveImport.safeName("a/../b")); assertNull(ArchiveImport.safeName("a\u0000b"))
    }

    @Test fun symlinkEntriesRefused() {
        val b = ByteArrayOutputStream()
        ZipArchiveOutputStream(b).use { z ->
            val l = ZipArchiveEntry("link.package"); l.unixMode = 0b1010_000_111_111_111 or 0 ; l.unixMode = 0xA1FF
            z.putArchiveEntry(l); z.write("/etc/passwd".toByteArray()); z.closeArchiveEntry()
            val g = ZipArchiveEntry("ok.package"); g.unixMode = 0x81A4; z.putArchiveEntry(g); z.write(pkg); z.closeArchiveEntry()
        }
        val (r, _) = extract(b.toByteArray(), ArchiveImport.Kind.ZIP)
        assertEquals(listOf("ok.package"), r.files.map { it.first })
        assertTrue(r.problems.single().contains("Refused link"))
    }

    @Test fun nestedArchiveOneLevel() {
        val inner = zip("deep/c.package" to pkg)
        val innerInner = zip("z.package" to pkg)
        val tooDeep = zip("m.zip" to innerInner)
        val (r, _) = extract(zip("one.package" to pkg, "More/inner.zip" to inner, "Deep/two.zip" to tooDeep), ArchiveImport.Kind.ZIP)
        assertEquals(setOf("one.package", "More/inner.zip/deep/c.package"), r.files.map { it.first }.toSet())
        assertTrue(r.problems.any { it.contains("one level") }, r.problems.toString())
    }

    @Test fun corruptArchivesAreReported() {
        assertFailsWith<ArchiveException> { extract(zip("a.package" to pkg).copyOf(30), ArchiveImport.Kind.ZIP) }
        assertFailsWith<ArchiveException> { extract("PK\u0003\u0004 garbage garbage garbage".toByteArray(), ArchiveImport.Kind.ZIP) }
        assertFailsWith<ArchiveException> { extract(sevenZ("a.package" to pkg).copyOf(40), ArchiveImport.Kind.SEVENZ) }
    }

    @Test fun bombsAreRefused() {
        val zeros = ByteArray(4 shl 20)
        // total size cap
        assertFailsWith<ArchiveException> { extract(zip("a.package" to zeros), ArchiveImport.Kind.ZIP, ArchiveLimits(maxTotalBytes = 1 shl 20)) }
        // per-entry cap
        assertFailsWith<ArchiveException> { extract(zip("a.package" to zeros), ArchiveImport.Kind.ZIP, ArchiveLimits(maxEntryBytes = 1 shl 20)) }
        // ratio: 4 MB of zeros packs to a few KB
        val e = assertFailsWith<ArchiveException> { extract(zip("a.package" to zeros), ArchiveImport.Kind.ZIP, ArchiveLimits(ratioMinBytes = 1 shl 20)) }
        assertTrue(e.message!!.contains("bomb"))
        // entry count
        val many = (1..20).map { "f$it.package" to pkg }.toTypedArray()
        assertFailsWith<ArchiveException> { extract(zip(*many), ArchiveImport.Kind.ZIP, ArchiveLimits(maxEntries = 10)) }
        // 7z ratio
        assertFailsWith<ArchiveException> { extract(sevenZ("a.package" to zeros), ArchiveImport.Kind.SEVENZ, ArchiveLimits(ratioMinBytes = 1 shl 20)) }
    }

    @Test fun spoolRefusesTooBig() {
        val d = tmp()
        assertFailsWith<ArchiveException> { ArchiveImport.spool(ByteArray(100).inputStream(), File(d, "x"), 50) }
        ArchiveImport.spool(ByteArray(100).inputStream(), File(d, "y"), 100)
    }
}
