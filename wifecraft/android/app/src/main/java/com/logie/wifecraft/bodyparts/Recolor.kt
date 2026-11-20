package com.logie.wifecraft.bodyparts

import kotlin.math.pow

/**
 * Recolours a part's diffuse to an NPC's skin, keeping its shading and detail. Pure; colours are 0xRRGGBB.
 *
 * The method: in linear light, every pixel is multiplied per channel by target / reference, where reference is the
 * part's own average colour (over the pixels its mesh uses, [uvMask], opaque ones only). A pixel at the average becomes
 * exactly the target; one half as bright stays half as bright, so wrinkles, creases and shading survive and a neutral
 * grey detail map (what CAS body parts like this carry: the game multiplies them over the sim's skin) takes the skin's
 * hue. Alpha is kept. [strength] blends from the original (0) to the full recolour (1).
 */
object Recolor {
    private val toLinear = FloatArray(256) { i -> srgbToLinear(i / 255f) }

    private fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    private fun linearToSrgb(c: Float): Float = if (c <= 0.0031308f) c * 12.92f else (1.055f * c.toDouble().pow(1 / 2.4).toFloat() - 0.055f)
    private fun byte(c: Float): Int = (c.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /**
     * The average colour of [img]'s pixels with alpha >= [minAlpha] (and inside [mask] when given), averaged in linear
     * light; null when no pixel qualifies. Also the way to sample an NPC's skin colour from a region of its skin texture.
     */
    fun averageColor(img: RgbaImage, mask: BooleanArray? = null, minAlpha: Int = 128, region: IntArray? = null): Int? {
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        val x0 = region?.get(0) ?: 0; val y0 = region?.get(1) ?: 0
        val x1 = region?.get(2) ?: img.width; val y1 = region?.get(3) ?: img.height
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(img.height)) for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(img.width)) {
            val i = y * img.width + x
            if (mask != null && !mask[i]) continue
            if ((img.rgba[i * 4 + 3].toInt() and 0xFF) < minAlpha) continue
            r += toLinear[img.rgba[i * 4].toInt() and 0xFF]; g += toLinear[img.rgba[i * 4 + 1].toInt() and 0xFF]; b += toLinear[img.rgba[i * 4 + 2].toInt() and 0xFF]
            n++
        }
        if (n == 0) return null
        return (byte(linearToSrgb((r / n).toFloat())) shl 16) or (byte(linearToSrgb((g / n).toFloat())) shl 8) or byte(linearToSrgb((b / n).toFloat()))
    }

    /**
     * Which texels of a [w] x [h] texture the triangles ([indices] into [uvs], u,v with v = 0 at the top row) cover:
     * the part's own pixels, so its reference colour is not taken from the rest of a shared body atlas.
     */
    fun uvMask(uvs: FloatArray, indices: IntArray, w: Int, h: Int): BooleanArray {
        val m = BooleanArray(w * h)
        for (t in 0 until indices.size / 3) {
            val a = indices[t * 3]; val b = indices[t * 3 + 1]; val c = indices[t * 3 + 2]
            val ax = uvs[a * 2] * w; val ay = uvs[a * 2 + 1] * h
            val bx = uvs[b * 2] * w; val by = uvs[b * 2 + 1] * h
            val cx = uvs[c * 2] * w; val cy = uvs[c * 2 + 1] * h
            val minX = maxOf(0, minOf(ax, bx, cx).toInt()); val maxX = minOf(w - 1, maxOf(ax, bx, cx).toInt())
            val minY = maxOf(0, minOf(ay, by, cy).toInt()); val maxY = minOf(h - 1, maxOf(ay, by, cy).toInt())
            val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (area == 0f) {
                // degenerate in UV space: mark its corners
                for ((x, y) in listOf(ax to ay, bx to by, cx to cy)) {
                    val xi = x.toInt(); val yi = y.toInt()
                    if (xi in 0 until w && yi in 0 until h) m[yi * w + xi] = true
                }
                continue
            }
            for (y in minY..maxY) for (x in minX..maxX) {
                val px = x + 0.5f; val py = y + 0.5f
                val w0 = (bx - px) * (cy - py) - (by - py) * (cx - px)
                val w1 = (cx - px) * (ay - py) - (cy - py) * (ax - px)
                val w2 = (ax - px) * (by - py) - (ay - py) * (bx - px)
                if ((w0 >= 0 && w1 >= 0 && w2 >= 0) || (w0 <= 0 && w1 <= 0 && w2 <= 0)) m[y * w + x] = true
            }
        }
        return m
    }

    /**
     * [img] recoloured so its [reference] colour (by default its own average over [mask]) becomes [target]; see the
     * object's comment. Returns a new image; pixels outside [mask] get the same gain (they can still be sampled at seams).
     */
    fun recolor(img: RgbaImage, target: Int, mask: BooleanArray? = null, reference: Int? = null, strength: Float = 1f): RgbaImage {
        val ref = reference ?: averageColor(img, mask) ?: averageColor(img) ?: return RgbaImage(img.width, img.height, img.rgba.copyOf())
        val s = strength.coerceIn(0f, 1f)
        val gain = FloatArray(3) { k ->
            val t = toLinear[(target shr (16 - 8 * k)) and 0xFF]
            val r = toLinear[(ref shr (16 - 8 * k)) and 0xFF]
            (t / maxOf(r, 1e-4f)).coerceIn(0f, 64f)
        }
        // one table per channel: 256 entries, so a 2048x2048 texture costs lookups only
        val lut = Array(3) { k -> ByteArray(256) { v ->
            val orig = v / 255f
            val rec = linearToSrgb(toLinear[v] * gain[k])
            byte(orig + (rec - orig) * s).toByte()
        } }
        val out = img.rgba.copyOf()
        for (i in 0 until img.width * img.height) for (k in 0..2) out[i * 4 + k] = lut[k][out[i * 4 + k].toInt() and 0xFF]
        return RgbaImage(img.width, img.height, out)
    }
}
