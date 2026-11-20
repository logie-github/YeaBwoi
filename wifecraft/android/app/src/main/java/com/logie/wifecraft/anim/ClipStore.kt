package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The library's clip file (`.wclip`): one animation's bone tracks, quantized and keyframe-reduced, with the animation's own
 * metadata in a small JSON header (used to rebuild a lost index; the library's index is what is edited later). Big-endian:
 *
 * ```
 *  0  4  "WCLP"
 *  4  1  file version (VERSION, 1); a newer one is refused, never half-read
 *  5  1  flags: bit 0, the track block is deflated (java.util.zip, raw deflate)
 *  6  2  encoder revision (REVISION: the quantization and reduction that wrote it)
 *  8  4  track count
 * 12  4  meta length M
 * 16  4  CRC32 of the meta
 * 20  M  meta: UTF-8 JSON {"anim": AnimJson without tracks, "pair": PairJson without its clips, "part": "a"|"b"} (pair: a partner animation's clip)
 *  .  4  raw track block length R
 *  .  4  stored track block length S (S = R when not deflated)
 *  .  4  CRC32 of the stored track block
 *  .  S  the track block
 * ```
 *
 * The track block: varint tick rate (key times are whole ticks of 1/rate s; 0: times are float32), then per track: varint
 * name length and UTF-8 bone name; one byte, bit 0 the channel (0 rotation, 1 position) and bit 1 quantized (else raw
 * float32 values); varint key count; the times (varint first tick, then zigzag varint deltas; or float32); the values:
 * rotation, 6 bytes per key, smallest-three (2 bits: the largest component, 3 x 15 bits: the others over +-1/sqrt 2);
 * position, one byte of bits per component (16 or 24), float32 minimum and step per axis, then each key's components as
 * zigzag varint deltas of the quantized integers. A track whose values cannot be quantized within the error bound (a
 * rotation that is not unit length, a value that is not finite, a position range too wide) is kept as raw float32.
 */
object ClipCodec {
    const val VERSION = 1
    /** The encoder's revision, stamped per file and per index entry: Compact library re-encodes an older one. */
    const val REVISION = 1
    const val EXTENSION = ".wclip"
    private const val MAGIC = 0x57434C50   // "WCLP"
    private const val FLAG_DEFLATE = 1
    private const val ROT_MAX = 0.70710678118654752
    private const val ROT_STEPS = 32767

    /**
     * Keyframe reduction's error bounds: a key goes when the interpolation of its neighbours reproduces it within these.
     * 0.25 degree for a bone at the end of a chain (hands, fingers, face, toes); [CHAIN_TOLERANCE_DEG] for the body's chain
     * from the root to the wrists and ankles, where errors add up along a limb.
     */
    const val ROTATION_TOLERANCE_DEG = 0.25
    const val CHAIN_TOLERANCE_DEG = 0.1
    /** 0.5 mm in model units (yards: the reference skeleton's, as the Root track's). */
    const val POSITION_TOLERANCE = 0.0005 / 0.9144

    private val TICK_RATES = intArrayOf(30, 60, 120, 240, 480, 960, 1000, 1920, 3840, 10000)

    class Header(val meta: JSONObject, val trackCount: Int, val revision: Int, val bytes: Long)

    /** What [encode] made: the file's bytes and the tracks after the keyframe reduction (before quantization). */
    class Encoded(val bytes: ByteArray, val tracks: List<BoneTrack>)

    // ------------------------------------------------------------------ writing

    fun encode(meta: JSONObject, tracks: List<BoneTrack>, reduce: Boolean = true): Encoded {
        val kept = if (reduce) tracks.map { KeyReduction.reduce(it) } else tracks
        val raw = trackBlock(kept)
        val deflated = deflate(raw)
        val useDeflate = deflated.size < raw.size
        val stored = if (useDeflate) deflated else raw
        return Encoded(file(meta, kept.size, if (useDeflate) FLAG_DEFLATE else 0, REVISION, raw.size, stored, crc(stored)), kept)
    }

    /** Writes [bytes] to [target] through a temp file and a rename. */
    fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** [src]'s track block (copied as stored, not decoded) under a new [meta], written to [target]. Returns its size. */
    fun rewrite(src: File, target: File, meta: JSONObject): Long {
        val r = Reader(src.readBytes())
        val head = readFixed(r)
        val rawLen = r.int(); val storedLen = r.int(); val storedCrc = r.int()
        val stored = r.bytes(storedLen)
        if (crc(stored) != storedCrc) throw IOException("${src.name}: the track block is damaged")
        val bytes = file(meta, head.trackCount, head.flags, head.revision, rawLen, stored, storedCrc)
        writeAtomic(target, bytes)
        return bytes.size.toLong()
    }

    private fun file(meta: JSONObject, count: Int, flags: Int, revision: Int, rawLen: Int, stored: ByteArray, storedCrc: Int): ByteArray {
        val m = meta.toString().toByteArray(Charsets.UTF_8)
        val bo = ByteArrayOutputStream(32 + m.size + stored.size)
        DataOutputStream(bo).apply {
            writeInt(MAGIC); writeByte(VERSION); writeByte(flags); writeShort(revision); writeInt(count)
            writeInt(m.size); writeInt(crc(m)); write(m)
            writeInt(rawLen); writeInt(stored.size); writeInt(storedCrc); write(stored)
            flush()
        }
        return bo.toByteArray()
    }

    private fun trackBlock(tracks: List<BoneTrack>): ByteArray {
        val rate = tickRate(tracks)
        val w = Writer(4096)
        w.varint(rate.toLong())
        for (t in tracks) {
            val name = t.bone.toByteArray(Charsets.UTF_8)
            w.varint(name.size.toLong()); w.bytes(name)
            val rotation = t.channel == Channel.ROTATION
            val per = if (rotation) 4 else 3
            require(t.values.size == t.times.size * per) { "Track ${t.bone} has ${t.values.size} values for ${t.times.size} keys" }
            val posBits = if (rotation) 0 else positionBits(t.values)
            val quantized = if (rotation) rotationsQuantizable(t.values) else posBits > 0
            w.byte((if (rotation) 0 else 1) or (if (quantized) 2 else 0))
            w.varint(t.times.size.toLong())
            if (rate > 0) {
                var prev = 0L
                for ((i, time) in t.times.withIndex()) {
                    val tick = (time.toDouble() * rate).roundToLong()
                    if (i == 0) w.varint(tick) else w.varint(zigzag(tick - prev))
                    prev = tick
                }
            } else for (time in t.times) w.float(time)
            when {
                !quantized -> for (v in t.values) w.float(v)
                rotation -> for (k in t.times.indices) w.rotation(t.values, k * 4)
                else -> positions(w, t.values, posBits)
            }
        }
        return w.toByteArray()
    }

    /** The coarsest tick rate every key time sits on (within 20 microseconds), 0 when none does (times kept as floats). */
    private fun tickRate(tracks: List<BoneTrack>): Int {
        var ok = true
        for (t in tracks) for (time in t.times) if (!time.isFinite() || time < 0f || time > 1e6f) ok = false
        if (!ok) return 0
        rates@ for (rate in TICK_RATES) {
            for (t in tracks) for (time in t.times) {
                val tick = (time.toDouble() * rate).roundToLong()
                if (abs(tick.toDouble() / rate - time) > 2e-5 + abs(time) * 1e-6) continue@rates
            }
            return rate
        }
        return 0
    }

    private fun rotationsQuantizable(values: FloatArray): Boolean {
        for (k in 0 until values.size / 4) {
            var n = 0.0
            for (i in 0 until 4) { val v = values[k * 4 + i].toDouble(); if (!v.isFinite()) return false; n += v * v }
            if (abs(sqrt(n) - 1.0) > 0.01) return false
        }
        return true
    }

    /** 16 or 24 bits per component when the track's range quantizes within an eighth of the tolerance, else 0 (raw). */
    private fun positionBits(values: FloatArray): Int {
        if (values.any { !it.isFinite() }) return 0
        var range = 0.0
        for (axis in 0 until 3) {
            var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (i in axis until values.size step 3) { lo = min(lo, values[i].toDouble()); hi = max(hi, values[i].toDouble()) }
            if (values.isNotEmpty()) range = max(range, hi - lo)
        }
        val fine = POSITION_TOLERANCE / 8
        return when {
            range / 65535.0 <= fine -> 16
            range / 16777215.0 <= fine -> 24
            else -> 0
        }
    }

    private fun positions(w: Writer, values: FloatArray, bits: Int) {
        val steps = (1L shl bits) - 1
        w.byte(bits)
        val mins = FloatArray(3); val step = FloatArray(3)
        for (axis in 0 until 3) {
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (i in axis until values.size step 3) { lo = min(lo, values[i]); hi = max(hi, values[i]) }
            if (values.isEmpty()) { lo = 0f; hi = 0f }
            mins[axis] = lo
            step[axis] = ((hi.toDouble() - lo) / steps).toFloat()
            w.float(mins[axis]); w.float(step[axis])
        }
        val prev = LongArray(3)
        for (k in 0 until values.size / 3) for (axis in 0 until 3) {
            val s = step[axis]
            val q = if (s > 0f) ((values[k * 3 + axis].toDouble() - mins[axis]) / s).roundToLong().coerceIn(0L, steps) else 0L
            w.varint(zigzag(q - prev[axis])); prev[axis] = q
        }
    }

    // ------------------------------------------------------------------ reading

    private class Fixed(val flags: Int, val revision: Int, val trackCount: Int, val meta: JSONObject)

    private fun readFixed(r: Reader): Fixed {
        if (r.int() != MAGIC) throw IOException("Not a Wifecraft clip file")
        val version = r.byte()
        if (version !in 1..VERSION) throw IOException("Clip file version $version is not supported (this app reads version $VERSION)")
        val flags = r.byte(); val revision = r.short(); val count = r.int()
        val metaLen = r.int(); val metaCrc = r.int()
        if (count < 0 || metaLen < 0) throw IOException("Damaged clip header")
        val m = r.bytes(metaLen)
        if (crc(m) != metaCrc) throw IOException("The clip's header is damaged")
        return Fixed(flags, revision, count, JSONObject(String(m, Charsets.UTF_8)))
    }

    /** The header alone (the meta and the counts): nothing of the tracks is read. */
    fun readHeader(file: File): Header {
        val head = java.io.DataInputStream(file.inputStream().buffered()).use { input ->
            val first = ByteArray(20)
            input.readFully(first)
            val len = Reader(first).let { it.skip(12); it.int() }
            if (len < 0 || len > 16 shl 20) throw IOException("${file.name}: damaged header")
            val m = ByteArray(len)
            input.readFully(m)
            first + m
        }
        val f = readFixed(Reader(head))
        return Header(f.meta, f.trackCount, f.revision, file.length())
    }

    fun readTracks(file: File): List<BoneTrack> = decode(file.readBytes())

    fun decode(bytes: ByteArray): List<BoneTrack> {
        val r = Reader(bytes)
        val head = readFixed(r)
        val rawLen = r.int(); val storedLen = r.int(); val storedCrc = r.int()
        if (rawLen < 0 || storedLen < 0 || rawLen > 256 shl 20) throw IOException("Damaged clip header")
        val stored = r.bytes(storedLen)
        if (crc(stored) != storedCrc) throw IOException("The clip's tracks are damaged")
        val raw = if (head.flags and FLAG_DEFLATE != 0) inflate(stored, rawLen) else stored
        val b = Reader(raw)
        val rate = b.varint().toInt()
        val out = ArrayList<BoneTrack>(head.trackCount)
        repeat(head.trackCount) {
            val bone = String(b.bytes(b.varint().toInt()), Charsets.UTF_8)
            val kind = b.byte()
            val rotation = kind and 1 == 0
            val quantized = kind and 2 != 0
            val n = b.varint().toInt()
            if (n < 0 || n > 10_000_000) throw IOException("Damaged track")
            val times = FloatArray(n)
            if (rate > 0) {
                var tick = 0L
                for (i in 0 until n) { tick = if (i == 0) b.varint() else tick + unzigzag(b.varint()); times[i] = (tick.toDouble() / rate).toFloat() }
            } else for (i in 0 until n) times[i] = b.float()
            val per = if (rotation) 4 else 3
            val values = FloatArray(n * per)
            when {
                !quantized -> for (i in values.indices) values[i] = b.float()
                rotation -> for (k in 0 until n) b.rotation(values, k * 4)
                else -> {
                    val bits = b.byte()
                    if (bits != 16 && bits != 24) throw IOException("Damaged track")
                    val mins = FloatArray(3); val step = FloatArray(3)
                    for (axis in 0 until 3) { mins[axis] = b.float(); step[axis] = b.float() }
                    val q = LongArray(3)
                    for (k in 0 until n) for (axis in 0 until 3) {
                        q[axis] += unzigzag(b.varint())
                        values[k * 3 + axis] = (mins[axis] + q[axis].toDouble() * step[axis]).toFloat()
                    }
                }
            }
            out += BoneTrack(bone, if (rotation) Channel.ROTATION else Channel.POSITION, times, values)
        }
        return out
    }

    // ------------------------------------------------------------------ bytes

    private fun crc(b: ByteArray): Int = CRC32().apply { update(b) }.value.toInt()

    private fun deflate(raw: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            d.setInput(raw); d.finish()
            val out = ByteArrayOutputStream(raw.size / 2 + 64)
            val buf = ByteArray(16384)
            while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
            return out.toByteArray()
        } finally { d.end() }
    }

    private fun inflate(stored: ByteArray, rawLen: Int): ByteArray {
        val i = Inflater(true)
        try {
            // raw deflate wants one byte past the input (zlib's nowrap rule)
            i.setInput(stored.copyOf(stored.size + 1))
            val out = ByteArray(rawLen)
            var off = 0
            while (off < rawLen) {
                val n = i.inflate(out, off, rawLen - off)
                if (n == 0 && (i.finished() || i.needsInput() || i.needsDictionary())) break
                off += n
            }
            if (off != rawLen) throw IOException("The clip's tracks are damaged (short)")
            return out
        } catch (e: java.util.zip.DataFormatException) {
            throw IOException("The clip's tracks are damaged", e)
        } finally { i.end() }
    }

    private fun zigzag(v: Long) = (v shl 1) xor (v shr 63)
    private fun unzigzag(v: Long) = (v ushr 1) xor -(v and 1)

    private class Writer(size: Int) {
        private var buf = ByteArray(size); private var n = 0
        private fun room(k: Int) { if (n + k > buf.size) buf = buf.copyOf(max(buf.size * 2, n + k)) }
        fun byte(v: Int) { room(1); buf[n++] = v.toByte() }
        fun bytes(b: ByteArray) { room(b.size); System.arraycopy(b, 0, buf, n, b.size); n += b.size }
        fun varint(v: Long) { var x = v; while (x and 0x7FL.inv() != 0L) { byte(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; byte(x.toInt()) }
        fun float(f: Float) { val b = java.lang.Float.floatToRawIntBits(f); byte(b ushr 24); byte(b ushr 16); byte(b ushr 8); byte(b) }

        /** Smallest-three: the largest component's index (2 bits) and the other three, 15 bits each, in 6 bytes. */
        fun rotation(v: FloatArray, at: Int) {
            var x = v[at].toDouble(); var y = v[at + 1].toDouble(); var z = v[at + 2].toDouble(); var w = v[at + 3].toDouble()
            val len = sqrt(x * x + y * y + z * z + w * w)
            x /= len; y /= len; z /= len; w /= len
            val c = doubleArrayOf(x, y, z, w)
            var big = 0
            for (i in 1 until 4) if (abs(c[i]) > abs(c[big])) big = i
            val sign = if (c[big] < 0) -1.0 else 1.0
            var packed = big.toLong()
            for (i in 0 until 4) if (i != big) {
                val q = (((c[i] * sign) + ROT_MAX) / (2 * ROT_MAX) * ROT_STEPS).roundToLong().coerceIn(0L, ROT_STEPS.toLong())
                packed = (packed shl 15) or q
            }
            for (s in 40 downTo 0 step 8) byte((packed ushr s).toInt())
        }

        fun toByteArray(): ByteArray = buf.copyOf(n)
    }

    private class Reader(private val b: ByteArray) {
        private var p = 0
        fun skip(k: Int) { p += k }
        fun byte(): Int { if (p >= b.size) throw IOException("Unexpected end of the clip file"); return b[p++].toInt() and 0xFF }
        fun short(): Int = (byte() shl 8) or byte()
        fun int(): Int = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
        fun float(): Float = java.lang.Float.intBitsToFloat(int())
        fun bytes(k: Int): ByteArray {
            if (k < 0 || p + k > b.size) throw IOException("Unexpected end of the clip file")
            return b.copyOfRange(p, p + k).also { p += k }
        }
        fun varint(): Long {
            var shift = 0; var out = 0L
            while (true) {
                val v = byte()
                out = out or ((v and 0x7F).toLong() shl shift)
                if (v and 0x80 == 0) return out
                shift += 7
                if (shift > 63) throw IOException("Damaged number in the clip file")
            }
        }

        fun rotation(out: FloatArray, at: Int) {
            var packed = 0L
            repeat(6) { packed = (packed shl 8) or byte().toLong() }
            val big = ((packed ushr 45) and 3).toInt()
            val c = DoubleArray(4)
            var sum = 0.0
            var shift = 30
            for (i in 0 until 4) if (i != big) {
                val q = (packed ushr shift) and 0x7FFF
                val v = q.toDouble() / ROT_STEPS * (2 * ROT_MAX) - ROT_MAX
                c[i] = v; sum += v * v; shift -= 15
            }
            c[big] = sqrt(max(0.0, 1.0 - sum))
            val len = sqrt(c.sumOf { it * it })
            for (i in 0 until 4) out[at + i] = (c[i] / len).toFloat()
        }
    }
}

/**
 * Keyframe reduction: a key is dropped when interpolating the keys kept around it (slerp for rotations, lerp for
 * positions, as the engine plays them: engine/boneanim.h, scene.cpp) reproduces it within [ClipCodec.ROTATION_TOLERANCE_DEG]
 * or [ClipCodec.POSITION_TOLERANCE]. The first and last keys always stay (a pose keeps its pose, a loop its seam).
 */
object KeyReduction {
    /** The longest run of keys one pair of kept keys may stand for: bounds the work on long, still tracks. */
    private const val MAX_SPAN = 512

    /** The bones whose turn moves a whole limb or the body (the chain from the root to the wrists and ankles). */
    private val CHAIN = BodyGeometry.JOINTS.toSet() - setOf("Head", "L_Hand", "R_Hand", "L_Toe", "R_Toe")

    /** The rotation tolerance for [bone]. */
    fun rotationTolerance(bone: String): Double = if (bone in CHAIN) ClipCodec.CHAIN_TOLERANCE_DEG else ClipCodec.ROTATION_TOLERANCE_DEG

    fun reduce(t: BoneTrack, rotTolDeg: Double = rotationTolerance(t.bone), posTol: Double = ClipCodec.POSITION_TOLERANCE): BoneTrack {
        val n = t.times.size
        val per = if (t.channel == Channel.ROTATION) 4 else 3
        if (n <= 2 || t.values.size != n * per) return t
        for (i in 1 until n) if (!(t.times[i] >= t.times[i - 1])) return t
        if (t.values.any { !it.isFinite() }) return t
        val rotation = t.channel == Channel.ROTATION
        val cosHalf = cos(Math.toRadians(rotTolDeg) / 2)
        val unit = if (rotation) DoubleArray(n * 4).also { u ->
            for (k in 0 until n) {
                var len = 0.0
                for (i in 0 until 4) len += t.values[k * 4 + i].toDouble() * t.values[k * 4 + i]
                len = sqrt(len)
                for (i in 0 until 4) u[k * 4 + i] = if (len > 0) t.values[k * 4 + i] / len else 0.0
            }
        } else DoubleArray(0)
        val q = DoubleArray(4)
        fun fits(a: Int, b: Int): Boolean {
            val ta = t.times[a].toDouble(); val span = t.times[b] - ta
            // the engine's slerp from a to b (wmath.h: the shorter way, nlerp when nearly equal), weights per key
            var d = 0.0
            if (rotation) for (i in 0 until 4) d += unit[a * 4 + i] * unit[b * 4 + i]
            val sign = if (d < 0) -1.0 else 1.0
            d = abs(d)
            val th = if (d > 0.9995) 0.0 else acos(min(1.0, d))
            val s = sin(th)
            for (k in a + 1 until b) {
                val f = if (span > 0) (t.times[k] - ta) / span else 0.0
                if (rotation) {
                    val wa: Double; val wb: Double
                    if (th == 0.0) { wa = 1 - f; wb = f } else { wa = sin((1 - f) * th) / s; wb = sin(f * th) / s }
                    var len = 0.0
                    for (i in 0 until 4) { q[i] = unit[a * 4 + i] * wa + unit[b * 4 + i] * wb * sign; len += q[i] * q[i] }
                    var dk = 0.0
                    for (i in 0 until 4) dk += q[i] * unit[k * 4 + i]
                    if (abs(dk) / sqrt(len) < cosHalf) return false
                } else {
                    var dd = 0.0
                    for (i in 0 until 3) {
                        val va = t.values[a * 3 + i].toDouble(); val vb = t.values[b * 3 + i].toDouble()
                        val e = va + (vb - va) * f - t.values[k * 3 + i]
                        dd += e * e
                    }
                    if (dd > posTol * posTol) return false
                }
            }
            return true
        }
        // from each kept key, the farthest key that still reproduces every key between: found by doubling the step, then
        // halving it (each candidate checked over all its keys, so every segment kept is within the bound)
        val keep = ArrayList<Int>()
        keep += 0
        var anchor = 0
        while (anchor < n - 1) {
            var good = anchor + 1
            var step = 1
            var bad = -1
            while (true) {
                val next = min(n - 1, min(anchor + MAX_SPAN, good + step))
                if (next == good) break
                if (fits(anchor, next)) { good = next; step *= 2 } else { bad = next; break }
            }
            if (bad > 0) {
                var lo = good; var hi = bad
                while (hi - lo > 1) { val mid = (lo + hi) / 2; if (fits(anchor, mid)) lo = mid else hi = mid }
                good = lo
            }
            keep += good
            anchor = good
        }
        if (keep.size == n) return t
        val times = FloatArray(keep.size) { t.times[keep[it]] }
        val values = FloatArray(keep.size * per)
        for ((j, k) in keep.withIndex()) System.arraycopy(t.values, k * per, values, j * per, per)
        return BoneTrack(t.bone, t.channel, times, values)
    }
}

/**
 * A library or staged animation's tracks, read from its file only when something reads them (played, previewed,
 * measured): [size] and the [inspection] (ClipInspection's verdict, kept in the index) need no read. The decoded tracks
 * are held by [ClipCache] (the most recent [ClipCache.SIZE]). Two are equal when they name the same stored clip ([key]);
 * a StoredTracks is never equal to an in-memory list. [legacy] marks a clip still in the old JSON file (null: a
 * `.wclip`; "anim": an AnimJson file; "a"/"b": one actor of a PairJson file): the library converts it the first time its
 * tracks are read ([onLegacyLoad]) or by Compact library.
 */
class StoredTracks internal constructor(
    file: File,
    count: Int,
    inspection: ClipInspection?,
    bytes: Long,
    revision: Int,
    legacy: String? = null,
    /** In an import's staging folder: the library moves the file when the animation is saved. */
    val staged: Boolean = false,
    internal val key: String = file.absolutePath,
) : AbstractList<BoneTrack>() {
    @Volatile var file: File = file; internal set
    /** ClipInspection of the stored tracks with a duration of 1 s ([ClipStore.forDuration] gives the clip's own). */
    @Volatile var inspection: ClipInspection? = inspection; internal set
    @Volatile var count: Int = count; internal set
    @Volatile var bytes: Long = bytes; internal set
    @Volatile var revision: Int = revision; internal set
    @Volatile var legacy: String? = legacy; internal set
    @Volatile internal var onLegacyLoad: ((StoredTracks, List<BoneTrack>) -> Unit)? = null

    override val size: Int get() = if (ClipCache.isBroken(this)) 0 else count
    override fun get(index: Int): BoneTrack = load()[index]
    override fun iterator(): Iterator<BoneTrack> = load().iterator()
    override fun equals(other: Any?): Boolean = other === this || (other is StoredTracks && other.key == key)
    override fun hashCode(): Int = key.hashCode()
    override fun toString(): String = "StoredTracks(${file.name}, $count tracks)"

    /** The tracks (from the cache, else read): empty when the file is gone or damaged. */
    fun load(): List<BoneTrack> {
        val tracks = ClipCache.tracks(this)
        if (legacy != null && tracks.isNotEmpty()) onLegacyLoad?.let { hook -> runCatching { hook(this, tracks) } }
        return tracks
    }
}

/**
 * The decoded clips most recently read ([SIZE], least recently used out first), and any clip still held elsewhere (a weak
 * reference: a clip a scene is playing is not read twice). Thread-safe.
 */
object ClipCache {
    const val SIZE = 8

    private class Entry(val stamp: Long, val tracks: List<BoneTrack>)
    private val lru = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > SIZE
    }
    private val weak = HashMap<String, Pair<Long, WeakReference<List<BoneTrack>>>>()
    private val broken = HashSet<String>()

    /** How many clips were read from disk (tests). */
    @Volatile var reads = 0
        private set

    private fun id(t: StoredTracks) = t.file.absolutePath + "#" + (t.legacy ?: "")
    private fun stamp(f: File) = f.lastModified() * 31 + f.length()

    fun isBroken(t: StoredTracks): Boolean = synchronized(this) { id(t) in broken }

    fun tracks(t: StoredTracks): List<BoneTrack> {
        repeat(2) {
            val file = t.file
            val id = id(t)
            val stamp = stamp(file)
            synchronized(this) {
                lru[id]?.takeIf { it.stamp == stamp }?.let { return it.tracks }
                weak[id]?.takeIf { it.first == stamp }?.second?.get()?.let { lru[id] = Entry(stamp, it); return it }
            }
            val read = try { read(file, t.legacy) } catch (e: Exception) { null }
            synchronized(this) {
                if (read != null) {
                    reads++
                    broken.remove(id)
                    lru[id] = Entry(stamp, read)
                    weak[id] = stamp to WeakReference(read)
                    if (weak.size > 256) weak.entries.removeAll { it.value.second.get() == null }
                    return read
                }
            }
            // the file moved under us (a legacy clip converted meanwhile): once more with its new place
            if (t.file == file) {
                synchronized(this) { broken += id }
                return emptyList()
            }
        }
        return emptyList()
    }

    private fun read(file: File, legacy: String?): List<BoneTrack> = when (legacy) {
        null -> ClipCodec.readTracks(file)
        "anim" -> AnimJson.parse(file.readText()).tracks
        "a" -> PairJson.parse(file.readText()).a.tracks
        "b" -> PairJson.parse(file.readText()).b.tracks
        else -> throw IOException("Unknown clip form $legacy")
    }

    fun forget(file: File) = synchronized(this) {
        val prefix = file.absolutePath + "#"
        lru.keys.removeAll { it.startsWith(prefix) }; weak.keys.removeAll { it.startsWith(prefix) }; broken.removeAll { it.startsWith(prefix) }
    }

    fun clear() = synchronized(this) { lru.clear(); weak.clear(); broken.clear() }

    /** How many decoded clips the LRU holds now (tests). */
    fun held(): Int = synchronized(this) { lru.size }
}

/** The stored clip's metadata, as the clip file's header and the library's index keep it (no tracks). */
object ClipStore {
    /** [a]'s metadata as JSON: AnimJson with no tracks (the index and the clip header). */
    fun meta(a: Anim): JSONObject = AnimJson.toJson(a.copy(tracks = emptyList()))

    /** [p]'s metadata: PairJson with its two clips' metadata only. */
    fun pairMeta(p: PairAnim): JSONObject = PairJson.toJson(p.copy(a = p.a.copy(tracks = emptyList()), b = p.b.copy(tracks = emptyList())))

    /** The verdict ClipInspection gives the tracks, kept with a 1 s duration: [forDuration] gives it for the clip's own. */
    fun inspect(tracks: List<BoneTrack>): ClipInspection =
        ClipInspection.inspect(Anim("", "", "", 1f, false, tracks, "", 0L))

    /** ClipInspection.inspect's answer for [duration] from the tracks' verdict [kept] (computed with 1 s). */
    fun forDuration(kept: ClipInspection, duration: Float): ClipInspection = when {
        !duration.isFinite() || duration < 0f -> ClipInspection(ClipType.INVALID, "The duration is invalid.")
        kept.type == ClipType.INVALID || kept.type == ClipType.POSE -> kept
        duration <= 0f -> ClipInspection(ClipType.INVALID, "The moving clip has no duration.")
        else -> kept
    }

    fun inspectionJson(i: ClipInspection?): JSONObject? = i?.let { JSONObject().put("type", it.type.name).put("reason", it.reason ?: "") }

    fun inspectionOf(o: JSONObject?): ClipInspection? = o?.let { j ->
        val type = ClipType.entries.firstOrNull { it.name == j.optString("type") } ?: return null
        ClipInspection(type, j.optString("reason").ifEmpty { null })
    }

    /** A clip encoded and not written yet: its file's bytes, track count and the reduced tracks' verdict. */
    class Prepared(val bytes: ByteArray, val count: Int, val inspection: ClipInspection)

    /**
     * [tracks] reduced ([KeyReduction]) and encoded ([ClipCodec]) under the header [meta] (given the reduced tracks'
     * verdict, which the header keeps as "inspect" for an index rebuilt from the files).
     */
    fun prepare(tracks: List<BoneTrack>, meta: (ClipInspection) -> JSONObject): Prepared {
        val reduced = tracks.map { KeyReduction.reduce(it) }
        val inspection = inspect(reduced)
        val m = meta(inspection)
        if (!m.has("inspect")) m.put("inspect", inspectionJson(inspection))
        return Prepared(ClipCodec.encode(m, reduced, reduce = false).bytes, reduced.size, inspection)
    }

    /** Writes [p] to [file] (a temp file, then a rename) and returns the light tracks that read it back. */
    fun store(file: File, p: Prepared, staged: Boolean = false): StoredTracks {
        ClipCodec.writeAtomic(file, p.bytes)
        ClipCache.forget(file)
        return StoredTracks(file, p.count, p.inspection, p.bytes.size.toLong(), ClipCodec.REVISION, staged = staged)
    }

    /** The clip header's meta for a single animation, or for actor [part] ("a"/"b") of [pair]. */
    fun header(a: Anim, inspection: ClipInspection?, pair: PairAnim? = null, part: String? = null): JSONObject {
        val o = JSONObject().put("anim", meta(a))
        inspectionJson(inspection)?.let { o.put("inspect", it) }
        if (pair != null) o.put("pair", pairMeta(pair)).put("part", part)
        return o
    }
}

/** The free space a big import needs, and what to say about it. */
object ImportSpace {
    /** Kept free for the phone: an import stops before the library would leave less than this. */
    const val RESERVE = 200L shl 20
    /** Below this much room beyond [RESERVE] an import is refused outright (nothing useful would fit). */
    const val MIN_ROOM = 16L shl 20

    /**
     * Library bytes per byte of a pack's download (its ZIP), measured on the five community packs (WickedWhims, Tibo131,
     * Azmodan22 Platinum and Gold, Anarcis: README, "Storage"): 0.03 to 0.29; above the largest, so the estimate errs high.
     */
    const val PER_ARCHIVE_BYTE = 0.35

    enum class Verdict { OK, SUBSET, REFUSE }

    fun estimate(archiveBytes: Long): Long = (archiveBytes * PER_ARCHIVE_BYTE).toLong()

    fun verdict(need: Long, free: Long): Verdict = when {
        free - RESERVE >= max(need, MIN_ROOM) -> Verdict.OK
        free - RESERVE >= MIN_ROOM -> Verdict.SUBSET
        else -> Verdict.REFUSE
    }

    fun mb(bytes: Long): String = when {
        bytes >= 10L shl 30 -> "%.0f GB".format(java.util.Locale.ROOT, bytes / 1073741824.0)
        bytes >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.ROOT, bytes / 1073741824.0)
        bytes >= 10L shl 20 -> "${(bytes shr 20) / 10 * 10} MB"
        else -> "${max(1L, bytes shr 20)} MB"
    }

    /** "About 400 MB on this phone (2.1 GB free)." and, when it won't all fit, what happens. */
    fun preview(archiveBytes: Long, free: Long): String {
        val need = estimate(archiveBytes)
        val head = "Installs about ${mb(need)} on this phone (${mb(max(0L, free))} free)."
        return when (verdict(need, free)) {
            Verdict.OK -> head
            Verdict.SUBSET -> "$head Not all of it fits: the install stops when ${mb(RESERVE)} is left, and keeps what came before."
            Verdict.REFUSE -> "$head There is not enough room: free some space first."
        }
    }

    /** Refused before anything is downloaded or converted: null when there is room to start. */
    fun refusal(free: Long): String? = if (verdict(0L, free) != Verdict.REFUSE) null else
        "There is not enough free space on this phone (${mb(max(0L, free))} free; animations keep ${mb(RESERVE)} free). Free some space and try again."

    /** "283.4 MB", "1.2 GB", "950 KB" (a pack page's file size) in bytes; null when it isn't one. */
    fun parseSize(label: String): Long? {
        val m = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*([KMG])i?B", RegexOption.IGNORE_CASE).find(label) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = when (m.groupValues[2].uppercase()) { "K" -> 1L shl 10; "M" -> 1L shl 20; else -> 1L shl 30 }
        return (n * unit).toLong()
    }
}

/** The library's free space ran out during an import (the clips converted so far are kept). */
class StorageFullException(message: String) : IOException(message)

/**
 * An import's staging folder: each converted clip is written here as it comes (light [Anim]s with [StoredTracks] are
 * what the review holds), saving moves them into the library (AnimLibrary.addResult), Discard deletes the folder. Before
 * each clip it checks the free space ([freeSpace]); below [ImportSpace.RESERVE] it throws [StorageFullException].
 */
class ImportStaging(val dir: File, private val freeSpace: () -> Long) {
    private var n = 0
    /** Bytes written so far. */
    @Volatile var bytes: Long = 0L
        private set
    @Volatile var count: Int = 0
        private set

    init { dir.mkdirs() }

    private fun room(size: Long) {
        val free = freeSpace()
        if (free - size < ImportSpace.RESERVE) throw StorageFullException(
            "The phone is nearly full (${ImportSpace.mb(max(0L, free))} free; animations keep ${ImportSpace.mb(ImportSpace.RESERVE)} free).")
    }

    @Synchronized fun put(a: Anim): Anim = put(a, null, null)

    private fun put(a: Anim, pair: PairAnim?, part: String?): Anim {
        if (a.tracks is StoredTracks) return a
        val prepared = ClipStore.prepare(a.tracks) { ClipStore.header(a, it, pair, part) }
        room(prepared.bytes.size.toLong())
        val stored = ClipStore.store(File(dir, "${n++}${ClipCodec.EXTENSION}"), prepared, staged = true)
        bytes += prepared.bytes.size; count++
        return a.copy(tracks = stored)
    }

    @Synchronized fun putPair(p: PairAnim): PairAnim = p.copy(a = put(p.a, p, "a"), b = put(p.b, p, "b"))

    /** Removes the folder and everything left in it (Discard, or after the save moved what it kept). */
    fun delete() { ArchiveImport.deleteTree(dir) }
}
