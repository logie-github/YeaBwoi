package com.logie.wifecraft.creator

import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * HD textures for creatures (Options -> Graphics -> HD creature textures): a creature's skin textures, 512 px at best in the
 * game, are enlarged on the phone as they are fetched ([HdTextureCache]): twice the size by a Lanczos-3 resample with a light
 * unsharp mask ([Upscale]), written as uncompressed BLP2 under a synthetic file id of its own and used in place of the game's
 * file for that creature. The game's files are never changed and nothing is shipped: the result is made from what the phone
 * fetched, once per texture (and per [VERSION] of this pass), and kept. Pure JVM. NOT run on a phone.
 *
 * A heavier pass (a small super-resolution network) is only designed: `tools/hd/sr_textures.py` runs one (Swin2SR, 8 MB ONNX
 * file) on the desktop for comparison; on a phone's CPU it took minutes per 512 px texture, so it isn't used here.
 */
object Blp {
    class Image(val width: Int, val height: Int, val rgba: ByteArray)

    private fun u32(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or
        ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)

    /** The first (largest) level of a BLP2 as RGBA, or null when it isn't one this can read (palette, DXT1/3/5 or BGRA). */
    fun decode(b: ByteArray): Image? {
        if (b.size < 148 || String(b, 0, 4, Charsets.ISO_8859_1) != "BLP2") return null
        val encoding = b[8].toInt() and 255; val alphaDepth = b[9].toInt() and 255; val alphaEnc = b[10].toInt() and 255
        val w = u32(b, 12); val h = u32(b, 16)
        if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return null
        val off = u32(b, 20); var len = u32(b, 84)
        if (off < 0 || off >= b.size) return null
        if (len <= 0 || off.toLong() + len > b.size) len = b.size - off
        val out = ByteArray(w * h * 4)
        when (encoding) {
            1 -> {
                val n = w * h
                for (i in 0 until n) {
                    if (i >= len) break
                    val c = u32(b, 148 + 4 * (b[off + i].toInt() and 255))
                    out[i * 4] = (c shr 16).toByte(); out[i * 4 + 1] = (c shr 8).toByte(); out[i * 4 + 2] = c.toByte()
                    var a = 255
                    if (alphaDepth == 8) a = if (n + i < len) b[off + n + i].toInt() and 255 else 255
                    else if (alphaDepth == 4) { val k = n + i / 2; a = if (k < len) ((b[off + k].toInt() shr ((i and 1) * 4)) and 15) * 17 else 255 }
                    else if (alphaDepth == 1) { val k = n + i / 8; a = if (k < len && ((b[off + k].toInt() shr (i and 7)) and 1) != 0) 255 else 0 }
                    out[i * 4 + 3] = a.toByte()
                }
            }
            2 -> dxt(b, off, len, w, h, if (alphaEnc == 7) 5 else if (alphaEnc == 1) 3 else 1, alphaDepth, out)
            3 -> {
                val n = min(w * h, len / 4)
                for (i in 0 until n) {
                    out[i * 4] = b[off + i * 4 + 2]; out[i * 4 + 1] = b[off + i * 4 + 1]
                    out[i * 4 + 2] = b[off + i * 4]; out[i * 4 + 3] = b[off + i * 4 + 3]
                }
            }
            else -> return null
        }
        return Image(w, h, out)
    }

    private fun rgb565(v: Int, out: IntArray, o: Int) {
        out[o] = ((v shr 11) and 31) * 255 / 31; out[o + 1] = ((v shr 5) and 63) * 255 / 63; out[o + 2] = (v and 31) * 255 / 31
    }

    private fun dxt(b: ByteArray, off: Int, len: Int, w: Int, h: Int, kind: Int, alphaDepth: Int, dst: ByteArray) {
        val bytes = if (kind == 1) 8 else 16
        val bw = (w + 3) / 4; val bh = (h + 3) / 4
        val pal = IntArray(16)
        val px = IntArray(16 * 4)
        for (by in 0 until bh) for (bx in 0 until bw) {
            val o = off + (by * bw + bx) * bytes
            if (o - off + bytes > len) return
            val c = o + if (kind == 1) 0 else 8
            val c0 = (b[c].toInt() and 255) or ((b[c + 1].toInt() and 255) shl 8)
            val c1 = (b[c + 2].toInt() and 255) or ((b[c + 3].toInt() and 255) shl 8)
            rgb565(c0, pal, 0); rgb565(c1, pal, 4); pal[3] = 255; pal[7] = 255
            if (kind != 1 || c0 > c1) {
                for (i in 0 until 3) { pal[8 + i] = (2 * pal[i] + pal[4 + i]) / 3; pal[12 + i] = (pal[i] + 2 * pal[4 + i]) / 3 }
                pal[11] = 255; pal[15] = 255
            } else {
                for (i in 0 until 3) { pal[8 + i] = (pal[i] + pal[4 + i]) / 2; pal[12 + i] = 0 }
                pal[11] = 255; pal[15] = 0
            }
            val bits = u32(b, c + 4)
            for (i in 0 until 16) { val p = ((bits ushr (2 * i)) and 3) * 4; for (k in 0 until 4) px[i * 4 + k] = pal[p + k] }
            if (kind == 3) {
                for (i in 0 until 16) px[i * 4 + 3] = ((b[o + i / 2].toInt() shr ((i and 1) * 4)) and 15) * 17
            } else if (kind == 5) {
                val a0 = b[o].toInt() and 255; val a1 = b[o + 1].toInt() and 255
                val ap = IntArray(8); ap[0] = a0; ap[1] = a1
                if (a0 > a1) for (i in 1..6) ap[i + 1] = ((7 - i) * a0 + i * a1) / 7
                else { for (i in 1..4) ap[i + 1] = ((5 - i) * a0 + i * a1) / 5; ap[6] = 0; ap[7] = 255 }
                var ab = 0L
                for (i in 0 until 6) ab = ab or ((b[o + 2 + i].toLong() and 255) shl (8 * i))
                for (i in 0 until 16) px[i * 4 + 3] = ap[((ab ushr (3 * i)) and 7).toInt()]
            }
            for (i in 0 until 16) {
                val x = bx * 4 + (i and 3); val y = by * 4 + (i shr 2)
                if (x >= w || y >= h) continue
                for (k in 0 until 3) dst[(y * w + x) * 4 + k] = px[i * 4 + k].toByte()
                dst[(y * w + x) * 4 + 3] = (if (kind == 1 && alphaDepth == 0) 255 else px[i * 4 + 3]).toByte()
            }
        }
    }

    /** [image] as an uncompressed BLP2 (BGRA, one level: the renderer makes its own mips). */
    fun encode(image: Image): ByteArray {
        val w = image.width; val h = image.height
        val data = w * h * 4
        val out = ByteArray(148 + 1024 + data)
        for ((i, c) in "BLP2".withIndex()) out[i] = c.code.toByte()
        fun put(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte(); out[o + 2] = (v shr 16).toByte(); out[o + 3] = (v shr 24).toByte() }
        put(4, 1)
        out[8] = 3; out[9] = 8; out[10] = 8; out[11] = 0
        put(12, w); put(16, h)
        put(20, 148 + 1024); put(84, data)
        for (i in 0 until w * h) {
            val o = 148 + 1024 + i * 4
            out[o] = image.rgba[i * 4 + 2]; out[o + 1] = image.rgba[i * 4 + 1]; out[o + 2] = image.rgba[i * 4]; out[o + 3] = image.rgba[i * 4 + 3]
        }
        return out
    }
}

object Upscale {
    private fun lanczos(x: Double): Double {
        val a = abs(x)
        if (a < 1e-9) return 1.0
        if (a >= 3.0) return 0.0
        val p = PI * a
        return 3.0 * sin(p) * sin(p / 3.0) / (p * p)
    }

    /** One axis' taps: for each output index, the first source index and the weights (a 3-lobe window, edges clamped). */
    private class Taps(val first: IntArray, val weights: Array<DoubleArray>)

    private fun taps(src: Int, dst: Int): Taps {
        val scale = dst.toDouble() / src
        val first = IntArray(dst); val weights = Array(dst) { DoubleArray(6) }
        for (o in 0 until dst) {
            val centre = (o + 0.5) / scale - 0.5
            val f = floor(centre).toInt() - 2
            first[o] = f
            var sum = 0.0
            for (k in 0 until 6) { val w = lanczos(centre - (f + k)); weights[o][k] = w; sum += w }
            for (k in 0 until 6) weights[o][k] /= sum
        }
        return Taps(first, weights)
    }

    /** [image] enlarged [factor] times by Lanczos-3 (every channel, alpha too), then unsharp-masked by [sharpen] (0: none). */
    fun enlarge(image: Blp.Image, factor: Int, sharpen: Float): Blp.Image {
        val sw = image.width; val sh = image.height
        val dw = sw * factor; val dh = sh * factor
        val tx = taps(sw, dw); val ty = taps(sh, dh)
        val mid = FloatArray(dw * sh * 4)
        for (y in 0 until sh) for (x in 0 until dw) for (c in 0 until 4) {
            var v = 0.0
            for (k in 0 until 6) {
                val sx = (tx.first[x] + k).coerceIn(0, sw - 1)
                v += tx.weights[x][k] * (image.rgba[(y * sw + sx) * 4 + c].toInt() and 255)
            }
            mid[(y * dw + x) * 4 + c] = v.toFloat()
        }
        val out = FloatArray(dw * dh * 4)
        for (y in 0 until dh) for (x in 0 until dw) for (c in 0 until 4) {
            var v = 0.0
            for (k in 0 until 6) {
                val sy = (ty.first[y] + k).coerceIn(0, sh - 1)
                v += ty.weights[y][k] * mid[(sy * dw + x) * 4 + c]
            }
            out[(y * dw + x) * 4 + c] = v.toFloat()
        }
        if (sharpen > 0f) {
            // unsharp mask on the colour: the image less its 3x3 binomial blur, added back
            val src = out.copyOf()
            for (y in 0 until dh) for (x in 0 until dw) for (c in 0 until 3) {
                var blur = 0f
                for (dy in -1..1) for (dx in -1..1) {
                    val wgt = (2 - abs(dx)) * (2 - abs(dy)) / 16f
                    blur += wgt * src[((y + dy).coerceIn(0, dh - 1) * dw + (x + dx).coerceIn(0, dw - 1)) * 4 + c]
                }
                val o = (y * dw + x) * 4 + c
                out[o] = src[o] + sharpen * (src[o] - blur)
            }
        }
        val bytes = ByteArray(dw * dh * 4)
        for (i in bytes.indices) bytes[i] = max(0f, min(255f, out[i] + 0.5f)).toInt().toByte()
        return Blp.Image(dw, dh, bytes)
    }
}

/**
 * The HD textures made so far, per game texture file: `<dir>/ids.json` names each its synthetic id (above the imports' ids,
 * stable), `<dir>/<file>.v<VERSION>` marks it made by this pass's [VERSION], and the BLP is `<gameDir>/<id>`, where the renderer
 * reads files by id. Written whole and renamed into place. [enabled] is Options' switch (read each time: a switch off gives
 * the game's own files back at the next dress and deletes nothing). A texture already [MIN_SIDE] px or more on its long side
 * is left alone (nothing to gain), as is one this can't read.
 */
class HdTextureCache(private val dir: File, private val gameDir: File, private val enabled: () -> Boolean = { true }) {
    private val idsFile = File(dir, "ids.json")
    private val ids = LinkedHashMap<Int, Int>()

    init {
        runCatching {
            if (idsFile.isFile) { val o = JSONObject(idsFile.readText()); for (k in o.keys()) k.toIntOrNull()?.let { f -> ids[f] = o.getInt(k) } }
        }
    }

    /** The synthetic id of [fileId]'s HD texture (allocated at the first ask), stable across runs. */
    @Synchronized fun idOf(fileId: Int): Int {
        ids[fileId]?.let { return it }
        var id = FIRST_ID + Math.floorMod(fileId * -1640531535, SPAN)
        while (id in ids.values) id = FIRST_ID + (id - FIRST_ID + 1) % SPAN
        ids[fileId] = id
        dir.mkdirs()
        val o = JSONObject(); for ((k, v) in ids) o.put(k.toString(), v)
        val tmp = File(dir, "ids.json.tmp"); tmp.writeText(o.toString())
        if (!tmp.renameTo(idsFile)) { idsFile.delete(); tmp.renameTo(idsFile) }
        return id
    }

    /** Whether [fileId]'s HD texture is made (by this version) and on disk. */
    fun has(fileId: Int): Boolean {
        val id = ids[fileId] ?: return false
        return File(dir, "$fileId.v$VERSION").isFile && File(gameDir, id.toString()).let { it.isFile && it.length() > 0 }
    }

    /**
     * The id to draw [fileId] with: its HD texture's (made now from [source], the game's own file, when it isn't yet) while the
     * switch is on, else [fileId]. Never throws: a file that can't be read or written gives [fileId] back.
     */
    fun resolve(fileId: Int, source: File?): Int {
        if (!enabled() || fileId <= 0 || fileId >= MAX_GAME_ID) return fileId
        if (has(fileId)) return idOf(fileId)
        if (source == null || !source.isFile) return fileId
        return runCatching {
            val image = Blp.decode(source.readBytes()) ?: return fileId
            if (max(image.width, image.height) >= MIN_SIDE) return fileId
            val hd = Upscale.enlarge(image, FACTOR, SHARPEN)
            val id = idOf(fileId)
            gameDir.mkdirs()
            val part = File(gameDir, "$id.part"); val target = File(gameDir, id.toString())
            part.writeBytes(Blp.encode(hd))
            if (!part.renameTo(target)) { target.delete(); part.renameTo(target) }
            File(dir, "$fileId.v$VERSION").writeText("$FACTOR")
            id
        }.getOrDefault(fileId)
    }

    /** [textures] (a look's texture type to file) with each file its HD id; a switch off or nothing made: [textures] itself. */
    fun map(textures: Map<Int, Int>, source: (Int) -> File?): Map<Int, Int> {
        if (!enabled() || textures.isEmpty()) return textures
        val out = LinkedHashMap<Int, Int>()
        for ((type, file) in textures) out[type] = resolve(file, source(file))
        return out
    }

    companion object {
        /** The pass's version: a change of its method makes every texture again. */
        const val VERSION = 1
        const val FACTOR = 2
        const val SHARPEN = 0.5f
        /** A texture this big on its long side is left alone. */
        const val MIN_SIDE = 1024
        /** Above the imports' ids (ImportStore.FIRST_ID + its span of 147,000,000). */
        const val FIRST_ID = 2_147_000_000
        const val SPAN = 480_000
        /** File ids from this up are the app's own, never the game's. */
        const val MAX_GAME_ID = 2_000_000_000
    }
}
