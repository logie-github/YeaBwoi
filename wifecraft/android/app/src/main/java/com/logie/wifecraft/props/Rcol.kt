package com.logie.wifecraft.props

import com.logie.wifecraft.sims.SimsFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A resource key: type, group, instance. */
data class Key(val type: Int, val group: Int, val instance: Long)

/** One internal chunk of a resource collection: its key (all zero for an unnamed chunk) and bytes. */
class Chunk(val type: Int, val group: Int, val instance: Long, val data: ByteArray) {
    val tag: String get() = if (data.size >= 4) String(data, 0, 4, Charsets.ISO_8859_1) else ""
}

/**
 * A Sims 4 resource collection (RCOL: model, model LOD, material, skin... resources): u32 version, public chunk count, an unused
 * u32, the external and internal counts, then every internal and external key (instance u64, type u32, group u32), then each
 * internal chunk's (position, size), then the chunks. References between chunks are u32s: the high nibble 1 and an index into
 * the internal chunks (`0x10000003` is chunk 3), 2 and an index into the external keys.
 */
class Rcol(val chunks: List<Chunk>, val external: List<Key>) {
    /** The internal chunk a reference [r] names, or null (a null reference, an external one, or out of range). */
    fun chunk(r: Int): Chunk? = if (r ushr 28 == 1 && (r and 0x0FFFFFFF) < chunks.size) chunks[r and 0x0FFFFFFF] else null

    companion object {
        private const val MAX_CHUNKS = 4096

        fun parse(d: ByteArray, what: String = "resource"): Rcol {
            fun fail(why: String): Nothing = throw SimsFormatException("The $what is not a readable model resource ($why).")
            if (d.size < 20) fail("too short")
            val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            val ext = b.getInt(12)
            val int = b.getInt(16)
            if (ext < 0 || int < 0 || ext > MAX_CHUNKS || int > MAX_CHUNKS) fail("bad counts")
            var p = 20
            if (p.toLong() + 16L * (int + ext) + 8L * int > d.size) fail("cut short")
            val keys = ArrayList<Key>(int + ext)
            repeat(int + ext) {
                keys += Key(b.getInt(p + 8), b.getInt(p + 12), b.getLong(p))
                p += 16
            }
            val chunks = ArrayList<Chunk>(int)
            for (i in 0 until int) {
                val pos = b.getInt(p).toLong() and 0xFFFFFFFFL
                val size = b.getInt(p + 4).toLong() and 0xFFFFFFFFL
                p += 8
                if (pos + size > d.size) fail("a chunk runs past the end")
                val k = keys[i]
                chunks += Chunk(k.type, k.group, k.instance, d.copyOfRange(pos.toInt(), (pos + size).toInt()))
            }
            return Rcol(chunks, keys.subList(int, int + ext))
        }
    }
}
