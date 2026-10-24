package com.logie.warcrafts.library

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlizzardDownloadTest {
    private val pieceLength = 16
    private val tome = ByteArray(40) { it.toByte() }
    private val mac = ByteArray(10) { 7 }
    private val exe = ByteArray(12) { (100 + it).toByte() }

    private fun str(s: String) = "${s.toByteArray().size}:$s".toByteArray()
    private fun bytes(b: ByteArray) = "${b.size}:".toByteArray() + b

    /** A downloader: some program bytes, then its torrent description. */
    private fun downloader(): ByteArray {
        val all = tome + mac + exe
        val pieces = all.toList().chunked(pieceLength).flatMap { MessageDigest.getInstance("SHA-1").digest(it.toByteArray()).toList() }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("MZ program bytes ".toByteArray())
        out.write("d8:announce".toByteArray()); out.write(str("http://tracker/announce"))
        out.write(str("direct download")); out.write(str("http://cdn.example/applications/Warcraft3/1.27a2/ROC/enUS"))
        out.write(str("info")); out.write("d".toByteArray())
        out.write(str("files")); out.write("l".toByteArray())
        for ((path, length) in listOf(listOf("Installer Tome.mpq") to 40, listOf("Installer.app", "Info.plist") to 10, listOf("Installer.exe") to 12)) {
            out.write("d".toByteArray()); out.write(str("length")); out.write("i${length}e".toByteArray())
            out.write(str("path")); out.write("l".toByteArray()); path.forEach { out.write(str(it)) }; out.write("ee".toByteArray())
        }
        out.write("e".toByteArray())
        out.write(str("name")); out.write(str("War3-1.27-Installer-enUS-ROC"))
        out.write(str("piece length")); out.write("i${pieceLength}e".toByteArray())
        out.write(str("pieces")); out.write(bytes(pieces))
        out.write("e".toByteArray())
        out.write(str("launch target")); out.write(str("War3-1.27-Installer-enUS-ROC/Installer.exe"))
        out.write("e".toByteArray())
        out.write("PSER^\u0006bcac=expires=1793402955~access=/applications/Warcraft3/1.27a2/ROC/enUS/*~md5=c0baa4d3d5095f5709bdd6f9298c31e2IKOC trailing".toByteArray())
        return out.toByteArray()
    }

    @Test
    fun `the installer's files, CDN address and pieces are read from the downloader`() {
        val pkg = assertNotNull(BlizzardDownload.parse(downloader()))
        assertEquals("War3-1.27-Installer-enUS-ROC", pkg.name)
        assertEquals("http://cdn.example/applications/Warcraft3/1.27a2/ROC/enUS", pkg.directDownload)
        assertEquals(listOf("Installer Tome.mpq", "Installer.app/Info.plist", "Installer.exe"), pkg.files.map { it.path })
        assertEquals(4, pkg.pieces.size)
    }

    @Test
    fun `the download's token is read from after the description`() {
        val pkg = BlizzardDownload.parse(downloader())!!
        assertEquals("bcac=expires=1793402955~access=/applications/Warcraft3/1.27a2/ROC/enUS/*~md5=c0baa4d3d5095f5709bdd6f9298c31e2", pkg.token)
        assertEquals("http://cdn.example/applications/Warcraft3/1.27a2/ROC/enUS/3", BlizzardDownload.pieceUrl(pkg, 3))
    }

    @Test
    fun `each piece's bytes go to the files they belong to`() {
        val pkg = BlizzardDownload.parse(downloader())!!
        // Files: tome 0..40, mac 40..50, exe 50..62; pieces of 16.
        assertEquals(listOf(BlizzardDownload.Segment(0, 0, 0, 16)), BlizzardDownload.segments(pkg, 0))
        assertEquals(
            listOf(BlizzardDownload.Segment(0, 32, 0, 8), BlizzardDownload.Segment(1, 0, 8, 8)),
            BlizzardDownload.segments(pkg, 2),
        )
        assertEquals(
            listOf(BlizzardDownload.Segment(1, 8, 0, 2), BlizzardDownload.Segment(2, 0, 2, 12)),
            BlizzardDownload.segments(pkg, 3),
        )
        assertEquals(14, BlizzardDownload.pieceSize(pkg, 3))
        // Rebuilt from its pieces, every file is as it was, and every piece's SHA-1 matches.
        val all = tome + mac + exe
        val files = pkg.files.map { ByteArray(it.length.toInt()) }
        for (i in pkg.pieces.indices) {
            val size = BlizzardDownload.pieceSize(pkg, i)
            val piece = all.copyOfRange(i * pieceLength, i * pieceLength + size)
            assertTrue(BlizzardDownload.sha1(piece, size).contentEquals(pkg.pieces[i]))
            for (s in BlizzardDownload.segments(pkg, i)) piece.copyInto(files[s.file], s.fileOffset.toInt(), s.pieceOffset, s.pieceOffset + s.length)
        }
        assertTrue(files[0].contentEquals(tome) && files[1].contentEquals(mac) && files[2].contentEquals(exe))
    }

    @Test
    fun `a program without a torrent description is no downloader`() {
        assertNull(BlizzardDownload.parse("MZ just a program".toByteArray()))
    }

    @Test
    fun `Blizzard's real downloaders, where present here`() {
        val dir = File(System.getenv("W3_DOWNLOADERS") ?: return)
        for ((file, size) in listOf("WAR3.exe" to 752118042L, "W3XP.exe" to 580710053L)) {
            val pkg = assertNotNull(BlizzardDownload.parse(File(dir, file).readBytes()), file)
            assertEquals(size, pkg.files.first { it.path == "Installer Tome.mpq" }.length)
            assertNotNull(pkg.token)
            assertTrue(pkg.directDownload.startsWith("http://rogue.blizzard.com.edgesuite.net/applications/Warcraft3/1.27a2/"))
            assertEquals((pkg.totalLength + pkg.pieceLength - 1) / pkg.pieceLength, pkg.pieces.size.toLong())
        }
    }
}
