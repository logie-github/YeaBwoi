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
        out.write("e trailing program bytes".toByteArray())
        return out.toByteArray()
    }

    @Test
    fun `the installer's files, CDN address and pieces are read from the downloader`() {
        val pkg = assertNotNull(BlizzardDownload.parse(downloader()))
        assertEquals("War3-1.27-Installer-enUS-ROC", pkg.name)
        assertEquals("http://cdn.example/applications/Warcraft3/1.27a2/ROC/enUS", pkg.directDownload)
        assertEquals(listOf("Installer Tome.mpq", "Installer.app/Info.plist", "Installer.exe"), pkg.files.map { it.path })
        assertEquals(4, pkg.pieces.size)
        assertEquals(listOf("Installer Tome.mpq", "Installer.exe"), BlizzardDownload.wanted(pkg).map { it.path })
    }

    @Test
    fun `addresses are tried under the installer's folder first, spaces escaped`() {
        val pkg = BlizzardDownload.parse(downloader())!!
        assertEquals(
            listOf(
                "http://cdn.example/applications/Warcraft3/1.27a2/ROC/enUS/War3-1.27-Installer-enUS-ROC/Installer%20Tome.mpq",
                "http://cdn.example/applications/Warcraft3/1.27a2/ROC/enUS/Installer%20Tome.mpq",
            ),
            BlizzardDownload.urls(pkg, pkg.files[0]),
        )
    }

    @Test
    fun `pieces check the downloaded files and skip the ones not downloaded`() {
        val pkg = BlizzardDownload.parse(downloader())!!
        val good = BlizzardDownload.Pieces(pkg)
        good.feed(tome, 0, 25); good.feed(tome, 25, 15); good.skip(mac.size.toLong()); good.feed(exe)
        assertTrue(good.finish())
        assertEquals(2, good.checked) // pieces 3 and 4 reach into the skipped Mac file
        val bad = BlizzardDownload.Pieces(pkg)
        bad.feed(tome.copyOf().also { it[3] = 99 }); bad.skip(mac.size.toLong()); bad.feed(exe)
        assertFalse(bad.finish())
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
            assertEquals(size, BlizzardDownload.wanted(pkg).first { it.path == "Installer Tome.mpq" }.length)
            assertTrue(pkg.directDownload.startsWith("http://rogue.blizzard.com.edgesuite.net/applications/Warcraft3/1.27a2/"))
            assertEquals((pkg.totalLength + pkg.pieceLength - 1) / pkg.pieceLength, pkg.pieces.size.toLong())
        }
    }
}
