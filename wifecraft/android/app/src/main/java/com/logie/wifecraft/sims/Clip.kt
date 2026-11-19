package com.logie.wifecraft.sims

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

enum class Channel { ROTATION, POSITION }

/** One bone's curve: rotation values are x,y,z,w per time; position values are x,y,z per time (the bone's local space, as the clip stores it). */
class SimsTrack(val boneHash: Int, val channel: Channel, val times: FloatArray, val values: FloatArray)

/**
 * One Sims 4 animation clip. [name] is the clip's own name (e.g. "creator:PosePack_..._set_1");
 * [sourceAsset] the file it was made from, when the clip says (e.g. "pose 1.blend"); [sourceKey]
 * the resource it came from (type:group:instance). [notes] lists channels the reader skipped and why.
 */
class SimsClip(
    val name: String,
    val duration: Float,
    val tracks: List<SimsTrack>,
    val sourceKey: String,
    val sourceAsset: String = "",
    val notes: List<String> = emptyList(),
)

/**
 * The Sims 4's CLIP resource (type 0x6B20C4F3), versions 1 to 14 and 18.
 *
 * The resource: u32 version, u32 flags, f32 duration (seconds), the initial offset (quaternion x,y,z,w
 * and vector x,y,z), then by version: (5+) a namespace hash, (10+) two surface hashes, (11+) a surface
 * child hash, (7+) the clip name and the rig namespace as u32-length strings; then explicit namespaces,
 * the IK slot assignments and the clip events (not read here), then a u32 length and the codec data.
 *
 * The codec ("_pilC3S_", The Sims 3's S3Clip): u32 version, u32 flags, f32 tick length, u16 tick count,
 * u16 padding, u32 channel count, u32 F1 palette size, then offsets (from the codec's start) of the
 * channel table, the F1 palette, the name and the source-asset name (zero-terminated): 48 bytes, the
 * channel table following at 48 in every clip of the sample pose pack. Each channel (20 bytes):
 * u32 frame data offset, u32 target (the bone: FNV-32 of its lower-cased name), f32 offset, f32 scale,
 * u16 frame count, u8 channel type, u8 sub-target (1 translation, 2 orientation, 3 scale, then IK).
 * Each frame: u16 start tick, u16 flags (bits 0..3: component i is negative), then the values, each
 * `value * scale + offset` from a magnitude in 0..1:
 *  - types 1..4 (F1..F4): u16 palette indices per component, padded to 4 bytes; magnitude = palette[i].
 *  - types 5..8 (F1..F4 normalized): one byte per component, padded to 4; magnitude = byte / 255.
 *  - 18 (F3 high precision): one u32, three 10-bit fields from bit 0; magnitude = field / 1023.
 *  - 20 (F4 super high precision quaternion): four u16, the low 12 bits; magnitude = bits / 4095.
 *  - 17: identity quaternion, no data.
 * Types 18 and 20 are what the game's own tools and the sample pose pack write (confirmed by decoding:
 * every quaternion unit length); the others follow s4pi's layout and are not confirmed on a sample.
 * F3 zero (11) is a constant zero translation. Other constant layouts and 10-bit quaternions are reported in [SimsClip.notes].
 */
object ClipDecoder {
    const val TYPE = 0x6B20C4F3
    private const val SIMS3_TAG = 0x6B20C4F3
    private val MAGIC = "_pilC3S_".toByteArray(Charsets.US_ASCII)

    fun decode(data: ByteArray, sourceKey: String = "", normalize: Boolean = true): SimsClip {
        val r = Reader(data, "clip $sourceKey")
        if (data.size < 40) r.fail("is only ${data.size} bytes; a Sims 4 clip header alone is 40")
        val version = r.u32(0)
        if (version == SIMS3_TAG) r.fail("is a Sims 3 clip; only Sims 4 clips are read")
        if (version !in 1..14 && version != 18) r.fail("is CLIP version $version; supported versions are 1 to 14 and 18")
        val duration = r.f32(8)
        var p = 40
        if (version >= 5) p += 4
        if (version >= 10) p += 8
        if (version >= 11) p += 4
        var clipName = ""
        if (version >= 7) { val (s, n) = r.string32(p); clipName = s; p = n }
        p = r.string32(p).second // rig namespace
        val codec = findCodec(data, p) ?: r.fail("has no animation data (no _pilC3S_ codec block after its header)")
        val codecLen = r.u32(codec - 4).toLong() and 0xFFFFFFFFL
        if (codecLen < 56 || codec + codecLen > data.size) r.fail("is cut short: its animation data says $codecLen bytes, ${data.size - codec} are there")
        val c = Reader(data.copyOfRange(codec, codec + codecLen.toInt()), "clip $sourceKey")
        return decodeCodec(c, clipName, duration, sourceKey, normalize)
    }

    private fun findCodec(d: ByteArray, from: Int): Int? {
        var i = maxOf(from, 4)
        outer@ while (i + MAGIC.size <= d.size) {
            for (j in MAGIC.indices) if (d[i + j] != MAGIC[j]) { i++; continue@outer }
            return i
        }
        return null
    }

    private class Ch(val off: Int, val target: Int, val offset: Float, val scale: Float, val frames: Int, val type: Int, val sub: Int)

    private fun decodeCodec(c: Reader, clipName: String, outerDuration: Float, key: String, normalize: Boolean): SimsClip {
        val tick = c.f32(16)
        val ticks = c.u16(20)
        val count = c.u32(24).toLong() and 0xFFFFFFFFL
        val palSize = c.u32(28).toLong() and 0xFFFFFFFFL
        val chOff = c.u32(32).toLong() and 0xFFFFFFFFL
        val palOff = c.u32(36).toLong() and 0xFFFFFFFFL
        val nameOff = c.u32(40)
        val srcOff = c.u32(44)
        val n = c.size.toLong()
        if (chOff + count * 20 > n) c.fail("is cut short: $count channels at $chOff do not fit in ${c.size} bytes")
        if (palSize > 0 && palOff + palSize * 4 > n) c.fail("is cut short: its palette of $palSize values at $palOff does not fit in ${c.size} bytes")
        if (!(tick > 0f && tick < 10f)) c.fail("has a tick length of $tick seconds; the animation data is damaged")
        val palette = FloatArray(palSize.toInt()) { c.f32(palOff.toInt() + it * 4) }
        val codecName = c.zstring(nameOff)
        val source = c.zstring(srcOff)
        val notes = ArrayList<String>()
        val tracks = ArrayList<SimsTrack>()
        val skipped = HashMap<String, Int>()
        for (i in 0 until count.toInt()) {
            val b = chOff.toInt() + i * 20
            val ch = Ch(c.u32(b), c.u32(b + 4), c.f32(b + 8), c.f32(b + 12), c.u16(b + 16), c.u8(b + 18), c.u8(b + 19))
            val channel = when (ch.sub) { 1 -> Channel.POSITION; 2 -> Channel.ROTATION; else -> null }
            if (channel == null) { skipped.merge(subName(ch.sub), 1, Int::plus); continue }
            val want = if (channel == Channel.ROTATION) 4 else 3
            val t = decodeChannel(c, ch, want, palette, tick, normalize && channel == Channel.ROTATION)
            if (t == null) { skipped.merge("type ${ch.type} (${typeName(ch.type)}) ${channel.name.lowercase()}", 1, Int::plus); continue }
            tracks += SimsTrack(ch.target, channel, t.first, t.second)
        }
        for ((what, k) in skipped) notes += "skipped $k channel(s) of $what"
        if (tracks.isEmpty()) c.fail("has no bone rotations or positions this reader can decode" + if (notes.isEmpty()) "" else " (${notes.joinToString("; ")})")
        val duration = if (outerDuration > 0f && outerDuration.isFinite()) outerDuration else tick * ticks
        return SimsClip(clipName.ifEmpty { codecName }.ifEmpty { source.ifEmpty { key } }, duration, tracks, key, source, notes)
    }

    /** Returns the channel's times and values, or null when its type cannot be decoded into [want] components. */
    private fun decodeChannel(c: Reader, ch: Ch, want: Int, palette: FloatArray, tick: Float, normalize: Boolean): Pair<FloatArray, FloatArray>? {
        if (ch.type == 11 && want == 3 && ch.sub == 1) return floatArrayOf(0f) to FloatArray(3)
        if (ch.type == 17) {
            if (want != 4) return null
            return floatArrayOf(0f) to floatArrayOf(0f, 0f, 0f, 1f)
        }
        val comps: Int
        val bytes: Int
        when (ch.type) {
            in 1..4 -> { comps = ch.type; bytes = ((comps * 2 + 3) / 4) * 4 }
            in 5..8 -> { comps = ch.type - 4; bytes = 4 }
            18 -> { comps = 3; bytes = 4 }
            20 -> { comps = 4; bytes = 8 }
            else -> return null
        }
        if (comps != want) return null
        if (ch.frames == 0) return null
        val frameSize = 4 + bytes
        val off = ch.off.toLong() and 0xFFFFFFFFL
        if (off + ch.frames.toLong() * frameSize > c.size) c.fail("is cut short: a channel's ${ch.frames} frames at $off run past ${c.size} bytes")
        val times = FloatArray(ch.frames)
        val values = FloatArray(ch.frames * comps)
        var p = off.toInt()
        for (f in 0 until ch.frames) {
            times[f] = c.u16(p) * tick
            val flags = c.u16(p + 2)
            val d = p + 4
            for (k in 0 until comps) {
                val mag = when (ch.type) {
                    in 1..4 -> {
                        val ix = c.u16(d + k * 2)
                        if (ix >= palette.size) c.fail("points at palette entry $ix of ${palette.size}; the animation data is damaged")
                        palette[ix]
                    }
                    in 5..8 -> c.u8(d + k) / 255f
                    18 -> ((c.u32(d) ushr (10 * k)) and 0x3FF) / 1023f
                    else -> (c.u16(d + k * 2) and 0xFFF) / 4095f
                }
                val signed = if (flags and (1 shl k) != 0) -mag else mag
                val v = signed * ch.scale + ch.offset
                if (!v.isFinite()) c.fail("decodes to a non-number; the animation data is damaged")
                values[f * comps + k] = v
            }
            if (normalize) {
                val b = f * 4
                val len = sqrt(values[b] * values[b] + values[b + 1] * values[b + 1] + values[b + 2] * values[b + 2] + values[b + 3] * values[b + 3])
                if (len < 1e-6f) { values[b] = 0f; values[b + 1] = 0f; values[b + 2] = 0f; values[b + 3] = 1f }
                else if (abs(len - 1f) > 0f) for (k in 0 until 4) values[b + k] /= len
            }
            p += frameSize
        }
        return times to values
    }

    private fun subName(s: Int) = when (s) {
        0 -> "unknown sub-target"
        3 -> "scale"
        in 4..14 -> "single-axis sub-target $s"
        else -> "IK sub-target $s"
    }

    private fun typeName(t: Int) = when (t) {
        0 -> "unknown"
        in 1..4 -> "F$t palette"
        in 5..8 -> "F${t - 4} normalized"
        in 9..12 -> "F${t - 8} zero"
        in 13..16 -> "F${t - 12} one"
        17 -> "quaternion identity"
        18 -> "F3 high precision"
        19 -> "F4 high precision quaternion"
        20 -> "F4 super high precision quaternion"
        21 -> "F3 high precision quaternion"
        else -> "unknown"
    }
}

/** Bounds-checked little-endian reads; every miss is a [SimsFormatException] naming [what]. */
internal class Reader(private val b: ByteArray, private val what: String) {
    private val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    val size get() = b.size
    fun fail(why: String): Nothing = throw SimsFormatException("The $what $why.")
    private fun need(p: Int, n: Int) { if (p < 0 || n < 0 || p.toLong() + n > b.size) fail("is cut short (needed bytes $p..${p.toLong() + n}, it has ${b.size})") }
    fun u8(p: Int): Int { need(p, 1); return b[p].toInt() and 0xFF }
    fun u16(p: Int): Int { need(p, 2); return bb.getShort(p).toInt() and 0xFFFF }
    fun u32(p: Int): Int { need(p, 4); return bb.getInt(p) }
    fun f32(p: Int): Float { need(p, 4); return bb.getFloat(p) }
    /** A u32 length then that many bytes; returns the string and the position after it. */
    fun string32(p: Int): Pair<String, Int> {
        val len = u32(p)
        if (len < 0 || len > 65536) fail("has a string of length $len at $p; the header is damaged")
        need(p + 4, len)
        return String(b, p + 4, len, Charsets.UTF_8) to p + 4 + len
    }
    fun zstring(p: Int): String {
        if (p < 0 || p >= b.size) return ""
        var e = p
        while (e < b.size && b[e] != 0.toByte() && e - p < 4096) e++
        return String(b, p, e - p, Charsets.UTF_8)
    }
}
