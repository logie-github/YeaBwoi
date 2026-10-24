// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * Takes one of the game's own programs out of the client's CASC storage (`Data/data`): the stock
 * `Wow-64.exe`, exactly as the build says it is. A Legion client folder has the 32-bit program
 * loose and the 64-bit one only inside the storage (Blizzard's launcher unpacked it at install),
 * and a copy of the client that was handed around may carry programs somebody changed. The build
 * lists every program it installs with its content hash (the *install manifest*); this finds the
 * program there, follows its hash through the *encoding file* to where the storage keeps it (the
 * local `.idx` index), decodes it (BLTE) and checks the result against the hash the build gave.
 *
 * What it reads: `.build.info` (the build's key), `Data/config/xx/yy/<key>` (the build
 * configuration: where the install manifest and encoding file are), the `.idx` files in `Data/data` and
 * `Data/data/data.NNN`. Nothing is written but the output file. Only what the 7.3.5 client's
 * storage uses is understood (BLTE blocks stored plain or deflated); anything else is an error.
 */
class FelsongCasc(client: File) {
    class CascException(message: String) : IOException(message)

    private val dataDir = FelsongClient.find(client, "Data/data") ?: throw CascException("The client has no Data/data folder")
    private val configDir = FelsongClient.find(client, "Data/config")
    private val buildInfo = FelsongClient.find(client, FelsongClient.BUILD_INFO)

    /** A file's place in the storage: which `data.NNN`, where, and its size there (header included). */
    data class Location(val archive: Int, val offset: Long, val size: Int)

    /** The build's own record of a program: its content hash and size. */
    data class Manifest(val name: String, val contentKey: ByteArray, val size: Long, val tags: Set<String>)

    private val index: Map<Int, ByteArray> by lazy { loadIndex() }

    private val config: Map<String, List<String>> by lazy {
        val info = buildInfo?.takeIf { it.isFile } ?: throw CascException("The client has no .build.info")
        val key = buildKey(info.readText()) ?: throw CascException(".build.info has no build key")
        val file = configFile(key) ?: throw CascException("The build configuration $key isn't in the client's Data/config")
        parseConfig(file.readText())
    }

    /** The programs the build installs under [name] (one for each architecture it has it for). */
    fun manifest(name: String): List<Manifest> {
        val install = config["install"]?.getOrNull(1) ?: throw CascException("The build configuration has no install manifest")
        val bytes = readEncoded(hex(install))
        return parseInstall(bytes).filter { it.name.equals(name, ignoreCase = true) }
    }

    /** The content hash the build gives [name], or null if it doesn't install it. [tag] picks an architecture. */
    fun expectedHash(name: String, tag: String? = null): String? =
        manifest(name).firstOrNull { tag == null || tag in it.tags }?.contentKey?.let(::toHex)

    /**
     * Writes the stock [name] into [target] (through `<target>.part`) and returns its MD5, which
     * is the build's hash for it. [tag] (`x86_64`) picks the architecture's entry.
     */
    fun extract(name: String, target: File, tag: String? = null, cancelled: () -> Boolean = { false }): String {
        val entry = manifest(name).firstOrNull { tag == null || tag in it.tags }
            ?: throw CascException("The build doesn't install $name")
        val encoding = config["encoding"]?.getOrNull(1) ?: throw CascException("The build configuration has no encoding file")
        val ekey = lookupEncoding(hex(encoding), entry.contentKey) ?: throw CascException("$name isn't in the encoding file")
        val part = File(target.path + ".part")
        part.parentFile?.mkdirs()
        val md5 = MessageDigest.getInstance("MD5")
        var written = 0L
        try {
            part.outputStream().buffered(1 shl 16).use { out ->
                withRecord(ekey) { read, length ->
                    Blte(read, length).stream { chunk ->
                        if (cancelled()) throw IOException("cancelled")
                        out.write(chunk)
                        md5.update(chunk)
                        written += chunk.size
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        val got = md5.digest()
        if (!got.contentEquals(entry.contentKey) || written != entry.size) {
            part.delete()
            throw CascException("$name from the storage isn't the build's (${toHex(got)}, ${written} bytes; the build says ${toHex(entry.contentKey)}, ${entry.size})")
        }
        target.delete()
        if (!part.renameTo(target)) throw CascException("Couldn't keep $name")
        return toHex(got)
    }

    // The storage ----------------------------------------------------------------------------

    /** Where the storage keeps [ekey] (the 9 bytes the local index uses), or null. */
    fun locate(ekey: ByteArray): Location? {
        val bucket = Companion.bucketOf(ekey)
        val table = index[bucket] ?: return null
        var low = 0
        var high = table.size / ENTRY - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val at = mid * ENTRY
            val order = compare(table, at, ekey)
            when {
                order < 0 -> low = mid + 1
                order > 0 -> high = mid - 1
                else -> {
                    var offset = 0L
                    for (i in 0 until 5) offset = (offset shl 8) or (table[at + 9 + i].toLong() and 0xFF)
                    var size = 0
                    for (i in 3 downTo 0) size = (size shl 8) or (table[at + 14 + i].toInt() and 0xFF)
                    return Location((offset shr 30).toInt(), offset and ((1L shl 30) - 1), size)
                }
            }
        }
        return null
    }

    /** Runs [block] with a reader over the BLTE data of [ekey]'s record (its 0x1E-byte header skipped). */
    private fun <T> withRecord(ekey: ByteArray, block: (read: (Long, Int) -> ByteArray, length: Long) -> T): T {
        val where = locate(ekey) ?: throw CascException("${toHex(ekey)} isn't in the storage's index")
        val file = File(dataDir, "data.%03d".format(where.archive))
        if (!file.isFile) throw CascException("${file.name} is missing from the client")
        if (where.size < HEADER) throw CascException("Damaged index entry for ${toHex(ekey)}")
        RandomAccessFile(file, "r").use { raf ->
            if (where.offset + where.size > raf.length()) throw CascException("${file.name} is shorter than the index says (incomplete download?)")
            val header = ByteArray(HEADER)
            raf.seek(where.offset)
            raf.readFully(header)
            // The record's own header: its key, reversed, then its size (with the header).
            if (ekey.size == 16 && (0 until 16).any { header[it] != ekey[15 - it] }) {
                throw CascException("${file.name}: the record at ${where.offset} isn't ${toHex(ekey)}")
            }
            val recorded = (header[16].toInt() and 0xFF) or ((header[17].toInt() and 0xFF) shl 8) or
                ((header[18].toInt() and 0xFF) shl 16) or ((header[19].toInt() and 0xFF) shl 24)
            if (recorded != where.size) throw CascException("${file.name}: the record at ${where.offset} isn't the one the index names")
            val read = { at: Long, length: Int ->
                val bytes = ByteArray(length)
                raf.seek(where.offset + HEADER + at)
                raf.readFully(bytes)
                bytes
            }
            return block(read, (where.size - HEADER).toLong())
        }
    }

    /** All of an (encoded) file in the storage, decoded; for the small ones (the install manifest). */
    private fun readEncoded(ekey: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        withRecord(ekey) { read, length -> Blte(read, length).stream { out.write(it) } }
        return out.toByteArray()
    }

    private fun loadIndex(): Map<Int, ByteArray> {
        val newest = HashMap<Int, Pair<Int, File>>()
        for (file in dataDir.listFiles().orEmpty()) {
            val match = IDX_NAME.matchEntire(file.name) ?: continue
            val bucket = match.groupValues[1].toInt(16)
            val version = match.groupValues[2].toInt(16)
            if ((newest[bucket]?.first ?: -1) < version) newest[bucket] = version to file
        }
        if (newest.isEmpty()) throw CascException("The client has no .idx files in Data/data")
        return newest.mapValues { (_, pair) -> parseIdx(pair.second.readBytes(), pair.second.name) }
    }

    // The encoding file ----------------------------------------------------------------------

    /**
     * [contentKey]'s encoded key (the first of them) from the encoding file, found without
     * decoding all of it: its page index (first key of each page) names the one page to read.
     */
    private fun lookupEncoding(encodingEkey: ByteArray, contentKey: ByteArray): ByteArray? =
        withRecord(encodingEkey) { read, length ->
            val blte = Blte(read, length)
            val header = blte.range(0, 22)
            if (header[0] != 'E'.code.toByte() || header[1] != 'N'.code.toByte()) throw CascException("Not an encoding file")
            val ckeySize = header[3].toInt() and 0xFF
            val ekeySize = header[4].toInt() and 0xFF
            val pageSize = (u16(header, 5)) * 1024
            val pageCount = u32(header, 9).toInt()
            val especSize = u32(header, 18).toInt()
            if (ckeySize != 16 || ekeySize != 16) throw CascException("Unexpected key sizes in the encoding file")
            val indexAt = 22L + especSize
            val pageIndex = blte.range(indexAt, pageCount * 32)
            // The last page whose first key is not above the wanted one.
            var low = 0
            var high = pageCount - 1
            var page = -1
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (compare(pageIndex, mid * 32, 16, contentKey) <= 0) { page = mid; low = mid + 1 } else high = mid - 1
            }
            if (page < 0) return@withRecord null
            val bytes = blte.range(indexAt + pageCount * 32L + page.toLong() * pageSize, pageSize)
            var p = 0
            while (p + 22 <= bytes.size) {
                val keys = bytes[p].toInt() and 0xFF
                if (keys == 0) break
                val ckeyAt = p + 6
                if (bytes.copyOfRange(ckeyAt, ckeyAt + 16).contentEquals(contentKey)) return@withRecord bytes.copyOfRange(ckeyAt + 16, ckeyAt + 32)
                p += 22 + 16 * keys
            }
            null
        }

    companion object {
        private const val ENTRY = 18
        private const val HEADER = 0x1E
        private val IDX_NAME = Regex("""([0-9a-fA-F]{2})([0-9a-fA-F]{8})\.idx""")

        fun hex(text: String): ByteArray {
            require(text.length % 2 == 0) { "odd hex" }
            return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        /** The `Build Key` of the active row of a `.build.info`. */
        fun buildKey(text: String): String? {
            val lines = text.lines().filter { it.isNotBlank() }
            if (lines.size < 2) return null
            val header = lines[0].split('|').map { it.substringBefore('!').trim() }
            val key = header.indexOfFirst { it.equals("Build Key", ignoreCase = true) }
            val active = header.indexOfFirst { it.equals("Active", ignoreCase = true) }
            if (key < 0) return null
            val rows = lines.drop(1).map { it.split('|') }
            val row = rows.firstOrNull { active < 0 || it.getOrNull(active)?.trim() == "1" } ?: rows.first()
            return row.getOrNull(key)?.trim()?.lowercase()?.takeIf { Regex("[0-9a-f]{32}").matches(it) }
        }

        /** A build configuration: `key = value value`, `#` comments. */
        fun parseConfig(text: String): Map<String, List<String>> = buildMap {
            for (line in text.lines()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val at = line.indexOf('=')
                if (at < 0) continue
                put(line.substring(0, at).trim(), line.substring(at + 1).trim().split(' ').filter { it.isNotEmpty() })
            }
        }

        /** The install manifest ("IN"): tags with a bit for each entry, then the entries. */
        fun parseInstall(bytes: ByteArray): List<Manifest> {
            if (bytes.size < 10 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'N'.code.toByte()) throw CascException("Not an install manifest")
            val hashSize = bytes[3].toInt() and 0xFF
            val tagCount = u16(bytes, 4)
            val entryCount = u32(bytes, 6).toInt()
            val maskSize = (entryCount + 7) / 8
            var p = 10
            val tags = ArrayList<Pair<String, ByteArray>>()
            repeat(tagCount) {
                val end = indexOfZero(bytes, p)
                val name = String(bytes, p, end - p, Charsets.ISO_8859_1)
                p = end + 1 + 2
                tags += name to bytes.copyOfRange(p, p + maskSize)
                p += maskSize
            }
            val out = ArrayList<Manifest>(entryCount)
            for (i in 0 until entryCount) {
                val end = indexOfZero(bytes, p)
                val name = String(bytes, p, end - p, Charsets.ISO_8859_1)
                p = end + 1
                val key = bytes.copyOfRange(p, p + hashSize)
                p += hashSize
                val size = u32(bytes, p)
                p += 4
                val own = tags.filter { (_, mask) -> (mask[i / 8].toInt() and (0x80 shr (i % 8))) != 0 }.map { it.first }.toSet()
                out += Manifest(name, key, size, own)
            }
            return out
        }

        /** One `.idx` (version 7): the entries, 9-byte key, 5-byte place, 4-byte size, sorted by key. */
        fun parseIdx(bytes: ByteArray, name: String = "an index"): ByteArray {
            if (bytes.size < 40) throw CascException("$name is too short")
            val headerSize = u32le(bytes, 0).toInt()
            val version = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
            val sizeBytes = bytes[12].toInt() and 0xFF
            val offsetBytes = bytes[13].toInt() and 0xFF
            val keyBytes = bytes[14].toInt() and 0xFF
            val offsetBits = bytes[15].toInt() and 0xFF
            if (version != 7 || sizeBytes != 4 || offsetBytes != 5 || keyBytes != 9 || offsetBits != 30) {
                throw CascException("$name isn't a version 7 index this reader knows (version $version)")
            }
            val start = (8 + headerSize + 0x0F) and 0x0F.inv()
            val entriesSize = u32le(bytes, start).toInt()
            val from = start + 8
            if (entriesSize < 0 || entriesSize % ENTRY != 0 || from + entriesSize > bytes.size) throw CascException("$name is damaged")
            return bytes.copyOfRange(from, from + entriesSize)
        }

        /** Which of the 16 indexes a key belongs to (xor of its 9 bytes, folded). */
        fun bucketOf(ekey: ByteArray): Int {
            var x = 0
            for (i in 0 until 9) x = x xor (ekey[i].toInt() and 0xFF)
            return (x and 0x0F) xor (x shr 4)
        }

        private fun compare(table: ByteArray, at: Int, key: ByteArray): Int {
            for (i in 0 until 9) {
                val a = table[at + i].toInt() and 0xFF
                val b = key[i].toInt() and 0xFF
                if (a != b) return a - b
            }
            return 0
        }

        private fun compare(table: ByteArray, at: Int, length: Int, key: ByteArray): Int {
            for (i in 0 until length) {
                val a = table[at + i].toInt() and 0xFF
                val b = key[i].toInt() and 0xFF
                if (a != b) return a - b
            }
            return 0
        }

        private fun indexOfZero(bytes: ByteArray, from: Int): Int {
            var i = from
            while (i < bytes.size && bytes[i].toInt() != 0) i++
            if (i >= bytes.size) throw CascException("Damaged manifest")
            return i
        }

        private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

        private fun u32(b: ByteArray, at: Int): Long =
            ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

        private fun u32le(b: ByteArray, at: Int): Long =
            ((b[at + 3].toLong() and 0xFF) shl 24) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 1].toLong() and 0xFF) shl 8) or (b[at].toLong() and 0xFF)
    }

    private fun configFile(key: String): File? {
        val dir = configDir ?: return null
        return File(dir, "${key.substring(0, 2)}/${key.substring(2, 4)}/$key").takeIf { it.isFile }
    }

    /**
     * BLTE, the storage's container: a table of blocks (compressed size, decoded size, MD5 of the
     * block as stored), each starting with how it is encoded: `N` plain, `Z` deflated. A file with
     * no table is one block. Blocks are read, checked and decoded one at a time, so a few bytes out
     * of an 80 MB file cost a block or two.
     */
    private class Blte(private val read: (Long, Int) -> ByteArray, private val length: Long) {
        private class Block(val stored: Long, val storedSize: Int, val decodedSize: Int, val md5: ByteArray?, val decodedStart: Long)

        private val blocks: List<Block> by lazy {
            val head = read(0, 8)
            if (String(head, 0, 4, Charsets.ISO_8859_1) != "BLTE") throw CascException("Not BLTE data")
            val headerSize = u32(head, 4).toInt()
            if (headerSize == 0) {
                // One block, no table; its decoded size is only known once it is decoded.
                listOf(Block(8, (length - 8).toInt(), -1, null, 0))
            } else {
                val table = read(8, headerSize - 8)
                val count = ((table[1].toInt() and 0xFF) shl 16) or ((table[2].toInt() and 0xFF) shl 8) or (table[3].toInt() and 0xFF)
                if (headerSize != 12 + count * 24) throw CascException("Damaged BLTE table")
                var stored = headerSize.toLong()
                var decoded = 0L
                (0 until count).map { i ->
                    val at = 4 + i * 24
                    val storedSize = u32(table, at).toInt()
                    val decodedSize = u32(table, at + 4).toInt()
                    Block(stored, storedSize, decodedSize, table.copyOfRange(at + 8, at + 24), decoded).also {
                        stored += storedSize
                        decoded += decodedSize
                    }
                }
            }
        }

        private fun decode(block: Block): ByteArray {
            val raw = read(block.stored, block.storedSize)
            if (block.md5 != null && !MessageDigest.getInstance("MD5").digest(raw).contentEquals(block.md5)) {
                throw CascException("A block of the file is damaged (its checksum differs)")
            }
            if (raw.isEmpty()) throw CascException("Empty BLTE block")
            return when (raw[0].toInt().toChar()) {
                'N' -> raw.copyOfRange(1, raw.size)
                'Z' -> inflate(raw, 1, if (block.decodedSize >= 0) block.decodedSize else null)
                else -> throw CascException("BLTE block encoded as '${raw[0].toInt().toChar()}' isn't supported")
            }.also { if (block.decodedSize >= 0 && it.size != block.decodedSize) throw CascException("A block decoded to ${it.size} bytes, not ${block.decodedSize}") }
        }

        /** Every block, decoded in order. */
        fun stream(sink: (ByteArray) -> Unit) {
            for (block in blocks) sink(decode(block))
        }

        /** [count] decoded bytes from [start], reading only the blocks that hold them. */
        fun range(start: Long, count: Int): ByteArray {
            val out = ByteArray(count)
            var filled = 0
            for (block in blocks) {
                if (block.decodedSize < 0) {
                    // No table: decode the one block.
                    val all = decode(block)
                    if (start + count > all.size) throw CascException("The file is shorter than asked")
                    return all.copyOfRange(start.toInt(), start.toInt() + count)
                }
                val end = block.decodedStart + block.decodedSize
                if (end <= start + filled) continue
                if (block.decodedStart >= start + count) break
                val data = decode(block)
                val from = (start + filled - block.decodedStart).toInt()
                val take = minOf(data.size - from, count - filled)
                System.arraycopy(data, from, out, filled, take)
                filled += take
                if (filled == count) return out
            }
            throw CascException("The file is shorter than asked")
        }

        private fun inflate(raw: ByteArray, from: Int, expected: Int?): ByteArray {
            val inflater = Inflater()
            try {
                inflater.setInput(raw, from, raw.size - from)
                val out = java.io.ByteArrayOutputStream(expected ?: (raw.size * 4))
                val buffer = ByteArray(1 shl 16)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buffer, 0, n)
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }

    }
}
