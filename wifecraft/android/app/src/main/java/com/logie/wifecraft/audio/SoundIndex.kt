package com.logie.wifecraft.audio

import com.logie.wifecraft.catalog.SoundCatalog
import com.logie.wifecraft.creator.Tables
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * A sound kit -> its files, for the kits the editor can ask for and no others: SoundKitEntry is 37 MB of CSV (1.33 million
 * rows), so it is read once, a line at a time ([SoundCatalog.streamKitIndex]), keeping the kits [SoundCatalog.usedKits]
 * names, and the result is kept as a small binary file (files/cache/sound-index.bin: varints, a key in front so another
 * build or other tables make a new one). Pure JVM.
 */
class SoundIndex(val kits: Map<Int, IntArray>) {
    fun files(kit: Int): List<Int> = kits[kit]?.toList().orEmpty()

    fun write(out: OutputStream, key: String) {
        val b = ByteArrayOutputStream()
        b.write(MAGIC)
        val k = key.toByteArray(Charsets.UTF_8)
        varint(b, k.size.toLong()); b.write(k)
        varint(b, kits.size.toLong())
        var prev = 0
        for (kit in kits.keys.sorted()) {
            val f = kits.getValue(kit)
            varint(b, (kit - prev).toLong()); prev = kit
            varint(b, f.size.toLong())
            for (id in f) varint(b, id.toLong())
        }
        out.write(b.toByteArray())
    }

    companion object {
        private val MAGIC = "WFSK1".toByteArray(Charsets.US_ASCII)

        /** The index kept in [bytes] for [key], or null (not this key, cut off, not an index). */
        fun read(bytes: ByteArray, key: String): SoundIndex? = try {
            val r = Reader(bytes)
            if (bytes.size < MAGIC.size || !bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) null
            else {
                r.pos = MAGIC.size
                val kl = r.varint().toInt()
                val k = String(bytes, r.pos, kl, Charsets.UTF_8); r.pos += kl
                if (k != key) null else {
                    val n = r.varint().toInt()
                    val m = HashMap<Int, IntArray>(n * 2)
                    var prev = 0
                    repeat(n) {
                        val kit = prev + r.varint().toInt(); prev = kit
                        val c = r.varint().toInt()
                        m[kit] = IntArray(c) { r.varint().toInt() }
                    }
                    if (r.pos != bytes.size) null else SoundIndex(m)
                }
            }
        } catch (e: Exception) { null }

        /** The index from SoundKitEntry's CSV ([csv]), keeping the kits [tables] can ask for. */
        fun build(tables: Tables, csv: InputStream): SoundIndex =
            SoundIndex(SoundCatalog.streamKitIndex(csv, SoundCatalog.usedKits(tables)))

        /**
         * The kept index for [key] in [cache], else one built from [csv] (opened only then) and kept (written whole as
         * `.part`, then renamed). The second value says whether it was built now.
         */
        fun load(cache: File, key: String, tables: Tables, csv: () -> InputStream): Pair<SoundIndex, Boolean> {
            runCatching { if (cache.isFile) read(cache.readBytes(), key) else null }.getOrNull()?.let { return it to false }
            val idx = csv().use { build(tables, it) }
            cache.parentFile?.mkdirs()
            val part = File(cache.path + ".part")
            part.outputStream().use { idx.write(it, key) }
            if (!part.renameTo(cache)) { cache.delete(); part.renameTo(cache) }
            return idx to true
        }

        private fun varint(o: ByteArrayOutputStream, v: Long) {
            var x = v
            while (x >= 0x80) { o.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
            o.write(x.toInt())
        }

        private class Reader(val b: ByteArray) {
            var pos = 0
            fun varint(): Long {
                var shift = 0; var v = 0L
                while (true) {
                    val c = b[pos++].toInt() and 0xFF
                    v = v or ((c and 0x7F).toLong() shl shift)
                    if (c < 0x80) return v
                    shift += 7
                    if (shift > 35) throw IllegalStateException("bad varint")
                }
            }
        }
    }
}
