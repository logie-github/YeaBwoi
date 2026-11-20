package com.logie.wifecraft.sims

/**
 * A package's string tables (STBL, resource type 0x220557DA): the display names its tuning gives as hashes ("0x0CBDA31C").
 * Layout: "STBL", version u16 (5), a byte, the entry count u64, two reserved bytes, the strings' total length u32; then
 * per entry its key u32, a flags byte, its length u16 and that many UTF-8 bytes. The instance's top byte is the language
 * (0x00 English): English is preferred, else whichever table has the key. A damaged table is skipped (never throws).
 */
object Stbl {
    const val TYPE = 0x220557DA

    /** Every key to its string in one table; empty when [bytes] is not a readable table. */
    fun parse(bytes: ByteArray): Map<Int, String> {
        if (bytes.size < 21 || bytes[0] != 'S'.code.toByte() || bytes[1] != 'T'.code.toByte() ||
            bytes[2] != 'B'.code.toByte() || bytes[3] != 'L'.code.toByte()) return emptyMap()
        fun u16(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        val count = u32(7).toLong() and 0xFFFFFFFFL
        if (u32(11) != 0 || count > 200_000) return emptyMap()
        val out = LinkedHashMap<Int, String>()
        var o = 21
        for (i in 0 until count) {
            if (o + 7 > bytes.size) break
            val key = u32(o)
            val length = u16(o + 5)
            o += 7
            if (o + length > bytes.size) break
            out.putIfAbsent(key, String(bytes, o, length, Charsets.UTF_8))
            o += length
        }
        return out
    }

    /** [pkg]'s tables merged, English first (its instance's top byte 0). */
    fun read(pkg: DbpfFile): Map<Int, String> {
        val tables = pkg.entries.filter { it.type == TYPE && it.size in 1..4_000_000 }
            .sortedBy { if ((it.instance ushr 56) == 0L) 0 else 1 }
        val out = HashMap<Int, String>()
        for (e in tables) {
            val table = runCatching { parse(pkg.read(e)) }.getOrDefault(emptyMap())
            for ((k, v) in table) out.putIfAbsent(k, v)
        }
        return out
    }

    private val HASH = Regex("0x([0-9A-Fa-f]{1,8})")

    /** A tuning's "0x…" display name looked up in [strings]; null when it is not a hash or not there (or blank). */
    fun lookup(value: String?, strings: Map<Int, String>): String? {
        val m = HASH.matchEntire(value?.trim() ?: return null) ?: return null
        val key = m.groupValues[1].toLong(16).toInt()
        if (key == 0) return null
        return strings[key]?.trim()?.takeIf { it.isNotEmpty() }
    }
}
