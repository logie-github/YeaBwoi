package com.logie.warcraft.client

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZipDownloadTest {
    /** A zip in memory; [failAfter] bytes into the first [failures] downloads, the connection drops. */
    private class Memory(val bytes: ByteArray, var failures: Int = 0, val failAfter: Long = 0) : ZipDownload.Source {
        val opened = mutableListOf<Long>()
        var sent = 0L

        override fun size(): Long = bytes.size.toLong()

        override fun open(from: Long, to: Long?): InputStream {
            opened += from
            val end = (to ?: (bytes.size - 1L)).toInt() + 1
            val stream: InputStream = ByteArrayInputStream(bytes, from.toInt(), end - from.toInt())
            val drop = failures > 0 && to == null
            if (drop) failures--
            return object : FilterInputStream(stream) {
                var count = 0L
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (drop && count >= failAfter) throw IOException("connection dropped")
                    val n = super.read(b, off, if (drop) minOf(len.toLong(), failAfter - count).toInt().coerceAtLeast(1) else len)
                    if (n > 0) { count += n; sent += n }
                    return n
                }
            }
        }
    }

    private fun zip(vararg files: Pair<String, ByteArray>, stored: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in files) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val files = listOf(
        // Random, so compressing doesn't make them small.
        "Character/Worgen/Male/WorgenMale.m2" to Random(1).nextBytes(300_000),
        "Character/Worgen/" to ByteArray(0),
        "DBFilesClient/ChrRaces.dbc" to Random(2).nextBytes(50_000),
        "Interface/GlueXML/CharacterCreate.lua" to "-- races".toByteArray(),
    )

    private fun temp(): File = Files.createTempDirectory("zipdownload").toFile()

    @Test
    fun everyFileComesOutWhole() {
        for (stored in listOf(true, false)) {
            val bytes = zip(*files.toTypedArray(), stored = stored)
            val download = ZipDownload(Memory(bytes))
            val folder = temp()
            val entries = download.directory()
            var last = 0L
            download.extractAll(entries, folder) { last = it }
            assertEquals(download.totalOf(entries), last)
            for ((name, data) in files) {
                if (name.endsWith("/")) continue
                assertContentEquals(data, File(folder, name).readBytes(), name)
            }
            assertTrue(folder.walk().none { it.name.endsWith(".part") })
            folder.deleteRecursively()
        }
    }

    @Test
    fun aDroppedConnectionCarriesOnFromTheFileItWasOn() {
        // Compressed files, with the data descriptors Java's zips have after each.
        val bytes = zip(*files.toTypedArray(), stored = false)
        val source = Memory(bytes, failures = 1, failAfter = 320_000)
        val download = ZipDownload(source)
        val folder = temp()
        val entries = download.directory()
        assertFailsWith<IOException> { download.extractAll(entries, folder) {} }
        // The first file made it; the second didn't.
        val first = entries.first { it.name.endsWith(".m2") }
        assertEquals(first.size, File(folder, first.name).length())
        assertFalse(File(folder, "DBFilesClient/ChrRaces.dbc").exists())

        download.extractAll(entries, folder) {}
        // The second pass starts at the file that didn't come.
        val second = entries.first { it.name.endsWith(".dbc") }
        assertEquals(second.localHeader, source.opened.last())
        for ((name, data) in files) {
            if (name.endsWith("/")) continue
            assertContentEquals(data, File(folder, name).readBytes(), name)
        }
        // Nothing left to do: no download at all.
        val opens = source.opened.size
        download.extractAll(entries, folder) {}
        assertEquals(opens, source.opened.size)
        folder.deleteRecursively()
    }

    @Test
    fun aDamagedFileIsntKept() {
        val bytes = zip(*files.toTypedArray(), stored = true)
        val download = ZipDownload(Memory(bytes))
        val entries = download.directory()
        val dbc = entries.first { it.name.endsWith(".dbc") }
        // One byte of its data changed.
        val damaged = bytes.copyOf()
        val start = RemoteZip.dataStart(dbc, bytes.copyOfRange(dbc.localHeader.toInt(), dbc.localHeader.toInt() + 30)).toInt()
        damaged[start + 10] = (damaged[start + 10] + 1).toByte()
        val folder = temp()
        assertFailsWith<IOException> { ZipDownload(Memory(damaged)).extractAll(entries, folder) {} }
        assertFalse(File(folder, dbc.name).exists())
        folder.deleteRecursively()
    }

    @Test
    fun namesStayInTheFolder() {
        val folder = File("/tmp/races")
        assertEquals(File(folder, "Character/Goblin/a.m2"), ZipDownload.target(folder, "Character/Goblin/a.m2"))
        assertEquals(File(folder, "Interface/b.lua"), ZipDownload.target(folder, "Interface\\b.lua"))
        assertFailsWith<IOException> { ZipDownload.target(folder, "../outside.txt") }
        assertFailsWith<IOException> { ZipDownload.target(folder, "a/../../outside.txt") }
        assertFailsWith<IOException> { ZipDownload.target(folder, "/etc/passwd") }
    }
}
