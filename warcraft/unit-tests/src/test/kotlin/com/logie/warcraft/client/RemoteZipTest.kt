package com.logie.warcraft.client

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteZipTest {
    private class Counting(val bytes: ByteArray) : RemoteZip.Ranges {
        var reads = 0
        override fun read(from: Long, length: Int): ByteArray {
            reads++
            return bytes.copyOfRange(from.toInt(), from.toInt() + length)
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

    /** What a download of [entry] gets: its data from its local header on. */
    private fun read(zip: ByteArray, entry: RemoteZip.Entry): ByteArray {
        val start = RemoteZip.dataStart(entry, zip.copyOfRange(entry.localHeader.toInt(), entry.localHeader.toInt() + 30)).toInt()
        val raw = zip.copyOfRange(start, start + entry.compressedSize.toInt())
        if (entry.method == 0) return raw
        val inflater = Inflater(true).apply { setInput(raw) }
        val out = ByteArray(entry.size.toInt())
        inflater.inflate(out)
        return out
    }

    @Test
    fun storedAndDeflatedFilesComeOutWhole() {
        val big = ByteArray(200_000) { (it % 251).toByte() }
        val small = "hello".toByteArray()
        for (stored in listOf(true, false)) {
            val bytes = zip("folder/" to ByteArray(0), "folder/Patch-H.MPQ" to big, "folder/readme.txt" to small, stored = stored)
            val entries = RemoteZip.directory(bytes.size.toLong(), Counting(bytes))
            assertEquals(listOf("folder/", "folder/Patch-H.MPQ", "folder/readme.txt"), entries.map { it.name })
            val patch = entries[1]
            assertEquals(big.size.toLong(), patch.size)
            assertEquals(CRC32().apply { update(big) }.value, patch.crc)
            assertEquals(big.toList(), read(bytes, patch).toList())
            assertEquals("hello", String(read(bytes, entries[2])))
        }
    }

    @Test
    fun aBigDirectoryIsReadOnItsOwn() {
        val files = (1..3000).map { "data/file-$it.txt" to "x$it".toByteArray() }.toTypedArray()
        val bytes = zip(*files, stored = true)
        val ranges = Counting(bytes)
        val entries = RemoteZip.directory(bytes.size.toLong(), ranges)
        assertEquals(3000, entries.size)
        assertEquals("x2999", String(read(bytes, entries[2998])))
    }

    @Test
    fun zip64() {
        // One stored file with the zip64 extra field and the zip64 end records, as zips over 4 GB have.
        val data = "an mpq".toByteArray()
        val name = "world of warcraft 3.3.5a hd/data/patch-a.mpq".toByteArray()
        val crc = CRC32().apply { update(data) }.value
        val out = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x04034b50).putShort(45).putShort(0).putShort(0).putInt(0).putInt(crc.toInt())
            .putInt(data.size).putInt(data.size).putShort(name.size.toShort()).putShort(0).put(name).put(data)
        val directory = out.position()
        out.putInt(0x02014b50).putShort(45).putShort(45).putShort(0).putShort(0).putInt(0).putInt(crc.toInt())
            .putInt(-1).putInt(-1).putShort(name.size.toShort()).putShort(28).putShort(0).putShort(0).putShort(0)
            .putInt(0).putInt(-1).put(name)
            .putShort(1).putShort(24).putLong(data.size.toLong()).putLong(data.size.toLong()).putLong(0)
        val directorySize = out.position() - directory
        val zip64 = out.position()
        out.putInt(0x06064b50).putLong(44).putShort(45).putShort(45).putInt(0).putInt(0)
            .putLong(1).putLong(1).putLong(directorySize.toLong()).putLong(directory.toLong())
        out.putInt(0x07064b50).putInt(0).putLong(zip64.toLong()).putInt(1)
        out.putInt(0x06054b50).putShort(0).putShort(0).putShort(-1).putShort(-1).putInt(-1).putInt(-1).putShort(0)
        val bytes = out.array().copyOf(out.position())
        val entries = RemoteZip.directory(bytes.size.toLong(), Counting(bytes))
        assertEquals(1, entries.size)
        assertEquals(data.size.toLong(), entries[0].size)
        assertEquals(0L, entries[0].localHeader)
        assertEquals("an mpq", String(read(bytes, entries[0])))
    }
}
