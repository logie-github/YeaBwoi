package com.logie.warcraft.felsong.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random
import java.util.zip.Deflater
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [FelsongCasc] on a client storage made here (the same formats the 7.3.5.26972 client's `Data`
 * folder has: version 7 indexes, BLTE records in `data.NNN`, an encoding file with pages, an
 * install manifest), and, when LEGION_CASC_TESTBED names a folder with a real client's `.build.info`,
 * `Data/config`, the `.idx` files in `Data/data` and the start of `Data/data/data.000`, on that.
 */
class FelsongCascTest {
    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes)

    private fun be32(value: Long) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt()).array()

    private fun le32(value: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    /** BLTE with a table: blocks of [blockSize] decoded bytes, alternately plain (N) and deflated (Z). */
    private fun blte(content: ByteArray, blockSize: Int, tableless: Boolean = false): ByteArray {
        if (tableless) return "BLTE".toByteArray() + be32(0) + byteArrayOf('N'.code.toByte()) + content
        val blocks = content.toList().chunked(blockSize).mapIndexed { i, chunk ->
            val raw = chunk.toByteArray()
            if (i % 2 == 0) byteArrayOf('N'.code.toByte()) + raw else byteArrayOf('Z'.code.toByte()) + deflate(raw)
        }
        val table = ByteArrayOutputStream()
        table.write(byteArrayOf(0x0F, 0, (blocks.size shr 8).toByte(), blocks.size.toByte()))
        content.toList().chunked(blockSize).forEachIndexed { i, chunk ->
            table.write(be32(blocks[i].size.toLong()))
            table.write(be32(chunk.size.toLong()))
            table.write(md5(blocks[i]))
        }
        val tableBytes = table.toByteArray()
        return "BLTE".toByteArray() + be32(8L + tableBytes.size) + tableBytes + blocks.fold(ByteArray(0)) { a, b -> a + b }
    }

    private class Stored(val ekey: ByteArray, val record: ByteArray)

    /** A data.000 record: the 0x1E-byte header (the key reversed, its own size, then the rest) and the BLTE data. */
    private fun record(encoded: ByteArray): Stored {
        val ekey = md5(encoded)
        val size = 0x1E + encoded.size
        val header = ByteArray(0x1E)
        for (i in 0 until 16) header[i] = ekey[15 - i]
        le32(size.toLong()).copyInto(header, 16)
        return Stored(ekey, header + encoded)
    }

    private fun entry(ekey: ByteArray, archive: Int, offset: Long, size: Int): ByteArray {
        val place = (archive.toLong() shl 30) or offset
        return ekey.copyOf(9) + ByteArray(5) { place.shr(8 * (4 - it)).toByte() } + le32(size.toLong())
    }

    private fun idx(bucket: Int, entries: List<ByteArray>): ByteArray {
        val sorted = entries.sortedWith { a, b ->
            var order = 0
            for (i in 0 until 9) if (order == 0) order = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            order
        }
        val header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(16).putInt(0x1234)
        header.putShort(7).put(bucket.toByte()).put(0).put(4).put(5).put(9).put(30).putLong(0x40000000L)
        return header.array() + le32(sorted.size * 18L) + le32(0) + sorted.fold(ByteArray(0)) { a, b -> a + b }
    }

    private class Built(val client: File, val exe: ByteArray, val exeMd5: String)

    /** A client folder whose storage holds `Wow-64.exe` (and a 32-bit `Wow.exe`), as the build lists them. */
    private fun build(): Built {
        val root = createTempDirectory("casc").toFile()
        val random = Random(7)
        val exe = ByteArray(300_000).also { random.nextBytes(it) }
        val exe32 = ByteArray(5_000).also { random.nextBytes(it) }
        val tags = listOf("Windows", "x86_32", "x86_64")
        // Install manifest: both programs, with the architecture each is tagged for.
        val entries = listOf("Wow.exe" to (exe32 to setOf(0, 1)), "Wow-64.exe" to (exe to setOf(0, 2)))
        val install = ByteArrayOutputStream()
        install.write("IN".toByteArray()); install.write(1); install.write(16)
        install.write(byteArrayOf(0, tags.size.toByte())); install.write(be32(entries.size.toLong()))
        tags.forEachIndexed { t, tag ->
            install.write(tag.toByteArray()); install.write(0); install.write(byteArrayOf(0, 3))
            val mask = ByteArray((entries.size + 7) / 8)
            entries.forEachIndexed { i, (_, v) -> if (t in v.second) mask[i / 8] = (mask[i / 8].toInt() or (0x80 shr (i % 8))).toByte() }
            install.write(mask)
        }
        for ((name, v) in entries) {
            install.write(name.toByteArray()); install.write(0); install.write(md5(v.first)); install.write(be32(v.first.size.toLong()))
        }
        // Encoding file: pages of content keys (a few hundred, so the wanted ones are not on the first page).
        val keys = ArrayList<Pair<ByteArray, ByteArray>>() // content key to encoded key
        val exeStored = record(blte(exe, 70_000))
        val exe32Stored = record(blte(exe32, 1_000, tableless = true))
        keys += md5(exe) to exeStored.ekey
        keys += md5(exe32) to exe32Stored.ekey
        repeat(500) { keys += ByteArray(16).also { random.nextBytes(it) } to ByteArray(16).also { random.nextBytes(it) } }
        val sorted = keys.sortedWith { a, b ->
            var order = 0
            for (i in 0 until 16) if (order == 0) order = (a.first[i].toInt() and 0xFF) - (b.first[i].toInt() and 0xFF)
            order
        }
        val pageBytes = 1024
        val pages = ArrayList<ByteArray>()
        var current = ByteArrayOutputStream()
        val firsts = ArrayList<ByteArray>()
        for ((ck, ek) in sorted) {
            if (current.size() + 38 > pageBytes) {
                pages += current.toByteArray().copyOf(pageBytes); current = ByteArrayOutputStream()
            }
            if (current.size() == 0) firsts += ck
            current.write(1); current.write(byteArrayOf(0, 0, 0, 1, 0)); current.write(ck); current.write(ek)
        }
        pages += current.toByteArray().copyOf(pageBytes)
        val espec = "z".toByteArray()
        val encoding = ByteArrayOutputStream()
        encoding.write("EN".toByteArray()); encoding.write(1); encoding.write(16); encoding.write(16)
        encoding.write(byteArrayOf(0, (pageBytes / 1024).toByte(), 0, 1))
        encoding.write(be32(pages.size.toLong())); encoding.write(be32(0)); encoding.write(0); encoding.write(be32(espec.size.toLong()))
        encoding.write(espec)
        firsts.forEachIndexed { i, first -> encoding.write(first); encoding.write(md5(pages[i])) }
        pages.forEach { encoding.write(it) }
        assertTrue(pages.size > 2)

        val installStored = record(blte(install.toByteArray(), 0, tableless = true))
        val encodingStored = record(blte(encoding.toByteArray(), 4_000))

        val archive = ByteArrayOutputStream()
        val places = ArrayList<Pair<Stored, Long>>()
        for (stored in listOf(exe32Stored, installStored, encodingStored, exeStored)) {
            places += stored to archive.size().toLong()
            archive.write(stored.record)
        }
        val data = File(root, "Data/data").apply { mkdirs() }
        File(data, "data.000").writeBytes(archive.toByteArray())
        val buckets = places.groupBy { FelsongCasc.bucketOf(it.first.ekey) }
        for (bucket in 0 until 16) {
            val inBucket = buckets[bucket].orEmpty().map { (s, at) -> entry(s.ekey, 0, at, s.record.size) }
            File(data, "%02x00000002.idx".format(bucket)).writeBytes(idx(bucket, inBucket))
        }
        // An older index of the same bucket that points somewhere else: the newer one must win.
        val some = buckets.entries.first()
        File(data, "%02x00000001.idx".format(some.key)).writeBytes(idx(some.key, some.value.map { (s, _) -> entry(s.ekey, 0, 12_345, 99) }))

        val buildConfig = "# Build Configuration\n\nroot = ${"ab".repeat(16)}\n" +
            "install = ${FelsongCasc.toHex(md5(install.toByteArray()))} ${FelsongCasc.toHex(installStored.ekey)}\n" +
            "install-size = 1 2\nencoding = ${FelsongCasc.toHex(md5(encoding.toByteArray()))} ${FelsongCasc.toHex(encodingStored.ekey)}\n" +
            "encoding-size = 1 2\nbuild-name = WOW-26972patch7.3.5_Retail\n"
        val buildKey = FelsongCasc.toHex(md5(buildConfig.toByteArray()))
        File(root, "Data/config/${buildKey.substring(0, 2)}/${buildKey.substring(2, 4)}/$buildKey").apply { parentFile.mkdirs() }.writeText(buildConfig)
        File(root, ".build.info").writeText(
            "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Version!STRING:0\n" +
                "us|0|${"00".repeat(16)}|${"11".repeat(16)}|6.0.0.1\n" +
                "us|1|$buildKey|${"22".repeat(16)}|7.3.5.26972\n",
        )
        return Built(root, exe, FelsongCasc.toHex(md5(exe)))
    }

    @Test
    fun takesTheStockProgramOutOfTheStorage() {
        val built = build()
        val casc = FelsongCasc(built.client)
        assertEquals(built.exeMd5, casc.expectedHash("Wow-64.exe"))
        assertEquals(built.exeMd5, casc.expectedHash("wow-64.exe", "x86_64"))
        assertNull(casc.expectedHash("Wow-64.exe", "x86_32"))
        val out = File(built.client, "Wow-64.exe")
        assertEquals(built.exeMd5, casc.extract("Wow-64.exe", out, "x86_64"))
        assertContentEquals(built.exe, out.readBytes())
        assertTrue(!File(out.path + ".part").exists())
        // The 32-bit program is a block-less BLTE.
        val out32 = File(built.client, "Wow.exe")
        casc.extract("Wow.exe", out32)
        assertEquals(5_000, out32.length())
    }

    @Test
    fun refusesWhatTheBuildDoesNotInstall() {
        val built = build()
        assertFailsWith<FelsongCasc.CascException> { FelsongCasc(built.client).extract("Nothing.exe", File(built.client, "x")) }
    }

    @Test
    fun refusesDamagedStorage() {
        val built = build()
        val data = File(built.client, "Data/data/data.000")
        val bytes = data.readBytes()
        // A byte in the middle of the program's blocks.
        bytes[bytes.size - 100_000] = (bytes[bytes.size - 100_000].toInt() xor 0x55).toByte()
        data.writeBytes(bytes)
        val out = File(built.client, "Wow-64.exe")
        assertFailsWith<FelsongCasc.CascException> { FelsongCasc(built.client).extract("Wow-64.exe", out, "x86_64") }
        assertTrue(!out.exists() && !File(out.path + ".part").exists())
    }

    @Test
    fun saysWhenTheDownloadIsShort() {
        val built = build()
        val data = File(built.client, "Data/data/data.000")
        data.writeBytes(data.readBytes().copyOf(1_000))
        val failure = assertFailsWith<FelsongCasc.CascException> { FelsongCasc(built.client).extract("Wow-64.exe", File(built.client, "Wow-64.exe"), "x86_64") }
        assertTrue("shorter" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun readsTheBuildKeyOfTheActiveRow() {
        val key = "3b0517b51edbe0b96f6ac5ea7eaaed38"
        val info = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|Version!STRING:0\nus|1|$key|7.3.5.26972\n"
        assertEquals(key, FelsongCasc.buildKey(info))
        assertEquals(key, FelsongCasc.buildKey("Build Key!HEX:16|Active!DEC:1\n${key.uppercase()}|1\n"))
        assertNull(FelsongCasc.buildKey("Branch!STRING:0\nus\n"))
        assertNull(FelsongCasc.buildKey("Build Key!HEX:16\nnothex\n"))
        val config = FelsongCasc.parseConfig("# Build Configuration\n\nroot = aa\ninstall = bb cc\ninstall-size = 1 2\n")
        assertEquals(listOf("bb", "cc"), config["install"])
    }

    @Test
    fun refusesAnIndexOfAnotherVersion() {
        val bytes = idx(3, emptyList()).also { it[8] = 5 }
        assertFailsWith<FelsongCasc.CascException> { FelsongCasc.parseIdx(bytes) }
    }

    /**
     * The real thing: LEGION_CASC_TESTBED is a folder with a 7.3.5.26972 client's `.build.info`,
     * `Data/config`, the `.idx` files in `Data/data` and the first ~230 MB of `Data/data/data.000` (where the
     * build keeps its install manifest, encoding file and Wow-64.exe). Wow-64.exe must come out
     * with the content hash the build's install manifest gives it.
     */
    @Test
    fun takesWow64OutOfARealClient() {
        val bed = System.getenv("LEGION_CASC_TESTBED")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val casc = FelsongCasc(bed)
        val expected = assertNotNull(casc.expectedHash("Wow-64.exe", "x86_64"))
        assertEquals("6ff2a79bf3fa89b146dcce93fd0d8ac8", expected)
        val out = File(createTempDirectory("real").toFile(), "Wow-64.exe")
        assertEquals(expected, casc.extract("Wow-64.exe", out, "x86_64"))
        assertEquals(27_600_872L, out.length())
        assertEquals("7.3.5.26972", FelsongClient.versionFromExe(out.inputStream())?.text)
    }
}
