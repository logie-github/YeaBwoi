package com.logie.wifecraft.bodyparts

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Thrown for a body-part resource (GEOM, CASP, texture) that cannot be read; the message names the resource and what was wrong. */
class BodyPartFormatException(message: String) : Exception(message)

/** A Sims 4 resource key: type, group, instance ("TTTTTTTT:GGGGGGGG:IIIIIIIIIIIIIIII"). */
data class ResourceKey(val type: Int, val group: Int, val instance: Long) {
    override fun toString(): String = "%08X:%08X:%016X".format(type, group, instance)

    companion object {
        val NONE = ResourceKey(0, 0, 0L)
    }
}

/** Bounds-checked little-endian cursor over one resource; every miss is a [BodyPartFormatException] naming [what]. */
internal class Bin(val b: ByteArray, private val what: String, var p: Int = 0) {
    private val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    val size: Int get() = b.size
    val left: Int get() = b.size - p

    fun fail(why: String): Nothing = throw BodyPartFormatException("The $what $why.")

    fun need(n: Int) {
        if (n < 0 || p < 0 || p.toLong() + n > b.size) fail("is cut short (needed bytes $p..${p.toLong() + n}, it has ${b.size})")
    }

    fun u8(): Int { need(1); return (b[p++].toInt() and 0xFF) }
    fun u16(): Int { need(2); return (bb.getShort(p).toInt() and 0xFFFF).also { p += 2 } }
    fun i16(): Int { need(2); return bb.getShort(p).toInt().also { p += 2 } }
    fun i32(): Int { need(4); return bb.getInt(p).also { p += 4 } }
    fun u64(): Long { need(8); return bb.getLong(p).also { p += 8 } }
    fun f32(): Float { need(4); return bb.getFloat(p).also { p += 4 } }
    fun skip(n: Int) { need(n); p += n }
    fun bytes(n: Int): ByteArray { need(n); return b.copyOfRange(p, p + n).also { p += n } }

    /** A count read from the data, checked against what could still fit ([unit] bytes each) so a bad count fails cleanly. */
    fun count(unit: Int, label: String): Int {
        val n = i32()
        if (n < 0 || (unit > 0 && n.toLong() * unit > left)) fail("claims $n $label at byte ${p - 4}; it is damaged")
        return n
    }

    /** .NET BinaryReader string with a big-endian UTF-16 body: a 7-bit-encoded byte length, then the characters. */
    fun string7BigEndianUtf16(): String {
        var len = 0
        var shift = 0
        while (true) {
            val c = u8()
            len = len or ((c and 0x7F) shl shift)
            if (c and 0x80 == 0) break
            shift += 7
            if (shift > 28) fail("has a damaged string length at byte $p")
        }
        if (len > 65536) fail("has a string of $len bytes at byte $p; it is damaged")
        need(len)
        return String(b, p, len, Charsets.UTF_16BE).also { p += len }
    }

    fun absolute(pos: Int): Bin = Bin(b, what, pos)
}
