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
 *
 * From the header: [actorName] is the clip's rig namespace, the actor it animates ("x", "y"... in the game's
 * own and in exported two-actor clips); [initialOffset] the header's initial offset; [events] the clip's
 * events that fall inside its duration (see [ClipEvent]); [tickLength] the codec's seconds per frame;
 * [explicitNamespaces] and [slots] the namespaces and IK slot assignments the clip names (another actor's
 * namespace there is a sign of a partner clip). Every header field has a default, so a clip built in code needs none.
 */
class SimsClip(
    val name: String,
    val duration: Float,
    val tracks: List<SimsTrack>,
    val sourceKey: String,
    val sourceAsset: String = "",
    val notes: List<String> = emptyList(),
    val actorName: String = "",
    val initialOffset: ClipOffset = ClipOffset.IDENTITY,
    val events: List<ClipEvent> = emptyList(),
    val tickLength: Float = 1f / 30f,
    val flags: Int = 0,
    val explicitNamespaces: List<String> = emptyList(),
    val slots: List<SlotAssignment> = emptyList(),
    /** How many of the header's events lay past the clip's end and were left out of [events]. */
    val eventsPastEnd: Int = 0,
)

/** The CLIP header's initial offset: a rotation (x,y,z,w) and a translation (x,y,z), in Sims axes (y up, metres). */
class ClipOffset(val rotation: FloatArray, val translation: FloatArray) {
    /** No offset, to float rounding (the sample's clips store w = 0.99999994). */
    val isIdentity: Boolean get() =
        translation.all { kotlin.math.abs(it) < 1e-6f } && (0 until 3).all { kotlin.math.abs(rotation[it]) < 1e-5f } && kotlin.math.abs(kotlin.math.abs(rotation[3]) - 1f) < 1e-5f

    override fun toString() = "ClipOffset(q=${rotation.toList()}, t=${translation.toList()})"

    companion object { val IDENTITY = ClipOffset(floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 0f)) }
}

/**
 * One clip event: its [type] (s4pi's numbering: 1 parent, 2 unparent, 3 sound, 5 effect, 12 snap,
 * 14 double-modifier sound...; the tools disagree from 17 up, so those are kept by number only),
 * [timeSec] from the clip's start, [name] the sound's name for sound events (3 and 14, a fixed
 * 128-byte string) and "" for the others, [actorHash] the actor-name hash a type-14 event carries (else 0).
 */
data class ClipEvent(val type: Int, val timeSec: Float, val name: String = "", val actorHash: Int = 0) {
    val isSound: Boolean get() = type == SOUND || type == DOUBLE_MODIFIER_SOUND

    companion object {
        const val SOUND = 3
        const val DOUBLE_MODIFIER_SOUND = 14
    }
}

/** An IK slot assignment of the clip header: chain and slot numbers, the target actor's namespace and joint name. */
data class SlotAssignment(val chain: Int, val slot: Int, val actor: String, val joint: String)

/**
 * The Sims 4's CLIP resource (type 0x6B20C4F3), versions 1 to 14.
 *
 * The resource (s4pi's ClipResource layout, checked on a sample pose pack): u32 version, u32 flags,
 * f32 duration (seconds), the initial offset (quaternion x,y,z,w and vector x,y,z), then by version:
 * (5+) a reference namespace hash, (10+) two surface hashes, (11+) a surface child hash, (7+) the clip
 * name, the rig namespace (the actor) as u32-length strings; (4+) the explicit namespaces (u32 count,
 * strings); the IK slot assignments (u32 count; each u16 chain, u16 slot, the actor's and the joint's
 * u32-length names); the events (u32 count; each u32 type, u32 size, then that many bytes: u32, u32,
 * f32 time, the type's data; types this reader does not know are skipped by their size); then a u32
 * length and the codec data. Events past the clip's duration are dropped (Sims 4 Studio's default sound
 * event sits at 1000 s in every clip it writes and never plays). A header whose middle part does not
 * read (damaged counts) falls back to finding the codec by its tag, with a note, and no events.
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
 * Constant types 9..16 (zero, one) and 19, 21 (10-bit quaternions) are skipped and named in [SimsClip.notes].
 */
object ClipDecoder {
    const val TYPE = 0x6B20C4F3
    private const val SIMS3_TAG = 0x6B20C4F3
    private val MAGIC = "_pilC3S_".toByteArray(Charsets.US_ASCII)
    private const val MAX_LIST = 4096
    private const val FIXED = 128

    fun decode(data: ByteArray, sourceKey: String = "", normalize: Boolean = true): SimsClip {
        val r = Reader(data, "clip $sourceKey")
        if (data.size < 40) r.fail("is only ${data.size} bytes; a Sims 4 clip header alone is 40")
        val version = r.u32(0)
        if (version == SIMS3_TAG) r.fail("is a Sims 3 clip; only Sims 4 clips are read")
        if (version < 1 || version > 14) r.fail("is CLIP version $version; this reader knows versions 1 to 14 (The Sims 4 writes 14)")
        val flags = r.u32(4)
        val duration = r.f32(8)
        val q = FloatArray(4) { r.f32(12 + it * 4) }
        val t = FloatArray(3) { r.f32(28 + it * 4) }
        val offset = if ((q + t).all { it.isFinite() }) ClipOffset(q, t) else ClipOffset.IDENTITY
        var p = 40
        if (version >= 5) p += 4
        if (version >= 10) p += 8
        if (version >= 11) p += 4
        var clipName = ""
        if (version >= 7) { val (s, n) = r.string32(p); clipName = s; p = n }
        val (actor, afterActor) = r.string32(p)
        p = afterActor
        val notes = ArrayList<String>()
        var tail: Tail? = try { readTail(r, version, p) } catch (e: SimsFormatException) {
            notes += "header after the actor name not read (${e.message}); found the animation data by its tag, without events"
            null
        }
        val codec: Int
        if (tail != null && tail.codecAt + 8 <= data.size && matches(data, tail.codecAt)) {
            codec = tail.codecAt
        } else {
            if (tail != null && tail.codecLen > 0) {
                notes += "the header's animation data is not where it says; found by its tag, without events"
                tail = null
            }
            codec = findCodec(data, p) ?: r.fail("has no animation data (no _pilC3S_ codec block after its header)")
        }
        val codecLen = r.u32(codec - 4).toLong() and 0xFFFFFFFFL
        if (codecLen < 56 || codec + codecLen > data.size) r.fail("is cut short: its animation data says $codecLen bytes, ${data.size - codec} are there")
        val c = Reader(data.copyOfRange(codec, codec + codecLen.toInt()), "clip $sourceKey")
        val decoded = decodeCodec(c, clipName, duration, sourceKey, normalize)
        val end = decoded.duration
        // an event at the very end still plays; one past it (by more than a frame's rounding) never does
        val events = tail?.events.orEmpty().filter { it.timeSec >= 0f && it.timeSec <= end + 1e-4f }
        val dropped = (tail?.events?.size ?: 0) - events.size
        return SimsClip(
            decoded.name, decoded.duration, decoded.tracks, decoded.sourceKey, decoded.sourceAsset, decoded.notes + notes,
            actorName = actor, initialOffset = offset, events = events, tickLength = decoded.tickLength, flags = flags,
            explicitNamespaces = tail?.namespaces.orEmpty(), slots = tail?.slots.orEmpty(), eventsPastEnd = dropped,
        )
    }

    private class Tail(val namespaces: List<String>, val slots: List<SlotAssignment>, val events: List<ClipEvent>, val codecLen: Long, val codecAt: Int)

    /** The header from the explicit namespaces to the codec length; [p] is just past the rig namespace. */
    private fun readTail(r: Reader, version: Int, start: Int): Tail {
        var p = start
        val namespaces = ArrayList<String>()
        if (version >= 4) {
            val n = r.u32(p); p += 4
            if (n < 0 || n > MAX_LIST) r.fail("lists $n explicit namespaces; the header is damaged")
            repeat(n) { val (s, q) = r.string32(p); namespaces += s; p = q }
        }
        val slots = ArrayList<SlotAssignment>()
        val ns = r.u32(p); p += 4
        if (ns < 0 || ns > MAX_LIST) r.fail("lists $ns slot assignments; the header is damaged")
        repeat(ns) {
            val chain = r.u16(p); val slot = r.u16(p + 2); p += 4
            val (actor, a) = r.string32(p); p = a
            val (joint, j) = r.string32(p); p = j
            slots += SlotAssignment(chain, slot, actor, joint)
        }
        val events = ArrayList<ClipEvent>()
        val ne = r.u32(p); p += 4
        if (ne < 0 || ne > MAX_LIST) r.fail("lists $ne events; the header is damaged")
        repeat(ne) {
            val type = r.u32(p)
            val size = r.u32(p + 4)
            p += 8
            if (size < 12 || p.toLong() + size > r.size) r.fail("has an event of $size bytes at $p; the header is damaged")
            val time = r.f32(p + 8)
            val data = p + 12
            val dataLen = size - 12
            val ev = when (type) {
                ClipEvent.SOUND -> if (dataLen >= FIXED) ClipEvent(type, time, fixed(r, data)) else null
                ClipEvent.DOUBLE_MODIFIER_SOUND -> if (dataLen >= FIXED + 4) ClipEvent(type, time, fixed(r, data), r.u32(data + FIXED)) else null
                else -> ClipEvent(type, time)
            }
            if (ev != null && time.isFinite()) events += ev
            p += size
        }
        val len = r.u32(p).toLong() and 0xFFFFFFFFL
        return Tail(namespaces, slots, events, len, p + 4)
    }

    /** A fixed-length (128-byte) name: the bytes up to the first zero, as ASCII. */
    private fun fixed(r: Reader, p: Int): String {
        val sb = StringBuilder()
        for (i in 0 until FIXED) { val b = r.u8(p + i); if (b == 0) break; sb.append(b.toChar()) }
        return sb.toString()
    }

    private fun matches(d: ByteArray, at: Int): Boolean {
        if (at < 4 || at + MAGIC.size > d.size) return false
        for (j in MAGIC.indices) if (d[at + j] != MAGIC[j]) return false
        return true
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
        return SimsClip(clipName.ifEmpty { codecName }.ifEmpty { source.ifEmpty { key } }, duration, tracks, key, source, notes, tickLength = tick)
    }

    /** Returns the channel's times and values, or null when its type cannot be decoded into [want] components. */
    private fun decodeChannel(c: Reader, ch: Ch, want: Int, palette: FloatArray, tick: Float, normalize: Boolean): Pair<FloatArray, FloatArray>? {
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
