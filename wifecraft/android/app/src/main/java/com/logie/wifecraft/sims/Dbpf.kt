package com.logie.wifecraft.sims

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Thrown for anything the Sims reader cannot read; the message says what was wrong and what to do. */
class SimsFormatException(message: String) : Exception(message)

/**
 * A Sims 4 package (DBPF 2.x) held in memory. The index is read once; [read] returns one resource,
 * inflated (zlib, 0x5A42) or unpacked (RefPack, 0xFFFF) as its entry says.
 *
 * Layout (DBPF 2.1, as The Sims 4 writes it): a 96-byte header ("DBPF", major 2, minor 1; the index's
 * entry count at 36, its size at 44, its position at 64, or at 40 when that is 0). The index starts
 * with a u32 of flags: bit 0 type, bit 1 group, bit 2 instance high, bit 3 instance low is the same
 * for every entry and written once, right after the flags. Each entry then holds the remaining
 * fields of type, group, instance high, instance low, then offset, size (bit 31: a compression u16
 * and a u16 follow), memory size.
 */
class Dbpf(private val bytes: ByteArray) {
    data class Entry(
        val type: Int,
        val group: Int,
        val instance: Long,
        val offset: Int,
        val size: Int,
        val memSize: Int,
        val compressed: Boolean,
    ) {
        /** The entry's compression code: 0 none, 0x5A42 zlib, 0xFFFF RefPack, 0xFFFE streamable, 0xFFE0 deleted. */
        var compression: Int = if (compressed) ZLIB else NONE
            internal set

        /** "TTTTTTTT:GGGGGGGG:IIIIIIIIIIIIIIII", how the resource is named in errors and in [SimsClip.sourceKey]. */
        val key: String get() = "%08X:%08X:%016X".format(type, group, instance)
    }

    val entries: List<Entry> = readIndex(Source.of(bytes), bytes.size.toLong())

    fun read(e: Entry): ByteArray {
        val start = e.offset.toLong() and 0xFFFFFFFFL
        val size = e.size.toLong() and 0xFFFFFFFFL
        if (start + size > bytes.size) {
            throw SimsFormatException("Resource ${e.key} runs past the end of the package (it is cut short: copy the file again).")
        }
        return unpack(e, bytes.copyOfRange(start.toInt(), (start + size).toInt()))
    }

    companion object {
        const val NONE = 0
        const val ZLIB = 0x5A42
        const val REFPACK = 0xFFFF
        const val STREAMABLE = 0xFFFE
        const val DELETED = 0xFFE0

        /** The largest resource the reader will unpack (rigs and clips are kilobytes; this stops a bad size from eating memory). */
        const val MAX_RESOURCE = 64 * 1024 * 1024

        fun isDbpf(b: ByteArray): Boolean =
            b.size >= 4 && b[0] == 'D'.code.toByte() && b[1] == 'B'.code.toByte() && b[2] == 'P'.code.toByte() && b[3] == 'F'.code.toByte()

        /** Random access to the package's bytes, so the same index reader serves memory and files. */
        internal fun interface Source {
            fun read(pos: Long, len: Int): ByteArray

            companion object {
                fun of(b: ByteArray) = Source { pos, len ->
                    if (pos < 0 || len < 0 || pos + len > b.size) {
                        throw SimsFormatException("The package is cut short (needed bytes $pos..${pos + len}, it has ${b.size}): copy the file again.")
                    }
                    b.copyOfRange(pos.toInt(), pos.toInt() + len)
                }
            }
        }

        internal fun readIndex(src: Source, length: Long): List<Entry> {
            if (length < 96) throw SimsFormatException("Not a Sims 4 package: the file is only $length bytes (a package starts with a 96-byte DBPF header).")
            val h = le(src.read(0, 96))
            if (h.getInt(0) != 0x46504244) throw SimsFormatException("Not a Sims 4 package (no DBPF header). Pick a .package file from The Sims 4 or a mod.")
            val major = h.getInt(4)
            val minor = h.getInt(8)
            if (major != 2) {
                throw SimsFormatException("This package is DBPF $major.$minor; The Sims 4 writes 2.1 (DBPF 1.x is The Sims 2, Sims 3 packages hold a different clip format).")
            }
            val count = h.getInt(36).toLong() and 0xFFFFFFFFL
            val indexSize = h.getInt(44).toLong() and 0xFFFFFFFFL
            var indexPos = h.getInt(64).toLong() and 0xFFFFFFFFL
            if (indexPos == 0L) indexPos = h.getInt(40).toLong() and 0xFFFFFFFFL
            if (count == 0L) return emptyList()
            if (count > 10_000_000L) throw SimsFormatException("The package's index claims $count entries; the file is damaged.")
            if (indexPos < 96 || indexPos + 4 > length) throw SimsFormatException("The package's index lies outside the file (at $indexPos of $length bytes): it is cut short or damaged.")
            val avail = length - indexPos
            val want = if (indexSize in 4..avail) indexSize else avail
            if (want > Int.MAX_VALUE) throw SimsFormatException("The package's index is too large to read.")
            val idx = le(src.read(indexPos, want.toInt()))
            val flags = idx.getInt(0)
            var p = 4
            fun need(n: Int) {
                if (p + n > idx.limit()) throw SimsFormatException("The package's index is cut short (entry data ends at byte ${idx.limit()}): copy the file again.")
            }
            val const = IntArray(4)
            for (bit in 0 until 4) if (flags and (1 shl bit) != 0) { need(4); const[bit] = idx.getInt(p); p += 4 }
            val out = ArrayList<Entry>(minOf(count, 65536L).toInt())
            for (i in 0 until count.toInt()) {
                val f = IntArray(4)
                for (bit in 0 until 4) {
                    f[bit] = if (flags and (1 shl bit) != 0) const[bit] else { need(4); idx.getInt(p).also { p += 4 } }
                }
                need(12)
                val off = idx.getInt(p)
                val rawSize = idx.getInt(p + 4)
                val mem = idx.getInt(p + 8)
                p += 12
                var comp = NONE
                if (rawSize and Int.MIN_VALUE != 0) {
                    need(4)
                    comp = idx.getShort(p).toInt() and 0xFFFF
                    p += 4
                }
                val size = rawSize and Int.MAX_VALUE
                val inst = (f[2].toLong() shl 32) or (f[3].toLong() and 0xFFFFFFFFL)
                val e = Entry(f[0], f[1], inst, off, size, mem, comp != NONE)
                e.compression = comp
                out += e
            }
            return out
        }

        internal fun unpack(e: Entry, raw: ByteArray): ByteArray {
            val mem = e.memSize.toLong() and 0xFFFFFFFFL
            return when (e.compression) {
                NONE -> raw
                ZLIB -> {
                    if (mem > MAX_RESOURCE) throw SimsFormatException("Resource ${e.key} says it unpacks to $mem bytes; that is too large to be a clip or rig.")
                    inflate(raw, mem.toInt(), e.key)
                }
                REFPACK -> RefPack.decompress(raw, e.key)
                DELETED -> throw SimsFormatException("Resource ${e.key} is marked deleted in the package.")
                STREAMABLE -> throw SimsFormatException("Resource ${e.key} uses The Sims 4's streamable compression (0xFFFE), which this reader does not support.")
                else -> throw SimsFormatException("Resource ${e.key} uses unknown compression 0x%04X.".format(e.compression))
            }
        }

        private fun inflate(raw: ByteArray, size: Int, key: String): ByteArray {
            val inf = Inflater()
            try {
                inf.setInput(raw)
                val out = ByteArray(size)
                var n = 0
                while (n < size) {
                    val got = inf.inflate(out, n, size - n)
                    if (got == 0) {
                        if (inf.finished() || inf.needsInput()) break
                        if (inf.needsDictionary()) throw SimsFormatException("Resource $key needs a zlib dictionary; it is not a Sims 4 resource.")
                    }
                    n += got
                }
                if (n != size) throw SimsFormatException("Resource $key unpacked to $n bytes, not the $size its entry says: the package is damaged.")
                return out
            } catch (x: DataFormatException) {
                throw SimsFormatException("Resource $key is not valid zlib data (${x.message}): the package is damaged.")
            } finally {
                inf.end()
            }
        }

        internal fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    }
}

/**
 * A package read from disk, index only: for The Sims 4's own packages (hundreds of megabytes),
 * where only a few resources (the rigs) are wanted. Offsets are unsigned 32-bit, as DBPF stores them.
 */
class DbpfFile(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val src = Dbpf.Companion.Source { pos, len ->
        if (pos < 0 || len < 0 || pos + len > raf.length()) {
            throw SimsFormatException("The package is cut short (needed bytes $pos..${pos + len}, it has ${raf.length()}): copy the file again.")
        }
        ByteArray(len).also { raf.seek(pos); raf.readFully(it) }
    }
    val entries: List<Dbpf.Entry> = try { Dbpf.readIndex(src, raf.length()) } catch (x: Throwable) { raf.close(); throw x }

    fun read(e: Dbpf.Entry): ByteArray {
        val size = e.size.toLong() and 0xFFFFFFFFL
        if (size > Dbpf.MAX_RESOURCE) throw SimsFormatException("Resource ${e.key} is $size bytes; that is too large to be a clip or rig.")
        return Dbpf.unpack(e, src.read(e.offset.toLong() and 0xFFFFFFFFL, size.toInt()))
    }

    override fun close() = raf.close()
}

/**
 * RefPack (QFS), The Sims 4's "internal" compression (0xFFFF), used in the game's own packages.
 * Header: a flags byte, 0xFB, then the unpacked size, big-endian, 4 bytes when flags bit 7 is set,
 * otherwise 3 (flags bit 0: a compressed size of the same width comes first). Then commands until
 * a stop command (0xFC..0xFF): each copies literal bytes from the input, then a run from earlier output.
 */
object RefPack {
    fun decompress(src: ByteArray, key: String = "resource"): ByteArray {
        fun bad(why: String): Nothing = throw SimsFormatException("Resource $key is not valid RefPack data ($why): the package is damaged.")
        if (src.size < 5) bad("too short")
        val flags = src[0].toInt() and 0xFF
        if (src[1].toInt() and 0xFF != 0xFB) bad("no 0xFB marker")
        val w = if (flags and 0x80 != 0) 4 else 3
        var p = 2
        if (flags and 0x01 != 0) p += w
        if (p + w > src.size) bad("header cut short")
        var size = 0L
        for (i in 0 until w) size = (size shl 8) or (src[p + i].toLong() and 0xFF)
        p += w
        if (size > Dbpf.MAX_RESOURCE) bad("says it unpacks to $size bytes")
        val out = ByteArray(size.toInt())
        var o = 0
        fun b(i: Int): Int = if (i < src.size) src[i].toInt() and 0xFF else bad("command cut short")
        fun copy(plain: Int, len: Int, dist: Int) {
            if (p + plain > src.size || o + plain > out.size) bad("literal past the end")
            System.arraycopy(src, p, out, o, plain); p += plain; o += plain
            if (len > 0) {
                if (dist > o || o + len > out.size) bad("copy from outside the output")
                for (i in 0 until len) { out[o] = out[o - dist]; o++ }
            }
        }
        while (true) {
            if (p >= src.size) break
            val c0 = b(p)
            when {
                c0 < 0x80 -> { val c1 = b(p + 1); p += 2; copy(c0 and 3, ((c0 and 0x1C) shr 2) + 3, ((c0 and 0x60) shl 3) + c1 + 1) }
                c0 < 0xC0 -> { val c1 = b(p + 1); val c2 = b(p + 2); p += 3; copy((c1 shr 6) and 3, (c0 and 0x3F) + 4, ((c1 and 0x3F) shl 8) + c2 + 1) }
                c0 < 0xE0 -> { val c1 = b(p + 1); val c2 = b(p + 2); val c3 = b(p + 3); p += 4; copy(c0 and 3, ((c0 and 0x0C) shl 6) + c3 + 5, ((c0 and 0x10) shl 12) + (c1 shl 8) + c2 + 1) }
                c0 < 0xFC -> { p += 1; copy(((c0 and 0x1F) shl 2) + 4, 0, 0) }
                else -> { p += 1; copy(c0 and 3, 0, 0); break }
            }
        }
        if (o != out.size) bad("unpacked to $o bytes, not $size")
        return out
    }
}
