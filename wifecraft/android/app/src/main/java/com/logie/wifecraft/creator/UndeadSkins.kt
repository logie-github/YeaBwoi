package com.logie.wifecraft.creator

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One of the imported undead skins of a race and sex, in the package's order: its name and swatch (0xRRGGBB) for the
 *  Skin Color row, and its two files (synthetic ids under files/game/<id>). */
data class UndeadSkin(val name: String, val swatch: Int, val skin: Int, val face: Int)

/**
 * Wrath++'s undead skins (the user's `wrathpp-undead-skins.zip`: Blizzard's `realm/warcraft/wrathpp/tools/undead_pack.py`,
 * read on the phone by `WrathppUndead.kt`) drawn on today's models.
 *
 * What the package holds per race and sex: a few skins, each a body texture and a face (manifest.json says which).
 *  - The body (`…SkinUndead00_NN.blp`, 512 x 512): today's body texture's left half (the body; today's models map it
 *    at u 0..0.5), resized. Here it is laid over that half of the composed skin ([BODY]).
 *  - The face (`…FaceLowerUndeadNN.blp`, 256 x 128): today's face texture (the right half, u 0.5..1) cut to the box the
 *    head's texture coordinates use (m2.head_layout), turned a quarter (numpy's rot90) when that box is taller than wide,
 *    and resized. Here it is turned back at import ([rotateBack]) and laid over the same box of the composed skin ([HEADS]).
 *
 * [HEADS] are m2.head_layout's boxes as undead_pack.py cut the faces: worked out from the models of the package's build
 * (9.2.7.45745, the manifest's), with Blizzard's own m2.py, for the ten races of 3.3.5. At today's pinned build
 * (12.1.7.70892) 15 of the 20 boxes are the same and the other five differ by at most 3/512 of a side.
 * Pure JVM; NOT run on a phone.
 */
object UndeadSkins {
    /** A head's box in today's texture coordinates (in 512ths of the whole texture) and whether the face was turned. */
    data class Head(val u0: Int, val v0: Int, val u1: Int, val v1: Int, val rotate: Boolean) {
        /** The box as fractions of the material: u0, v0, u1, v1. */
        val rect: List<Float> get() = listOf(u0 / 512f, v0 / 512f, u1 / 512f, v1 / 512f)
    }

    /** The body: the left half of the composed skin. */
    val BODY: List<Float> = listOf(0f, 0f, 0.5f, 1f)

    private fun h(race: Int, sex: Sex, u0: Int, v0: Int, u1: Int, v1: Int, rotate: Boolean) = (race to sex) to Head(u0, v0, u1, v1, rotate)

    val HEADS: Map<Pair<Int, Sex>, Head> = mapOf(
        h(1, Sex.MALE, 267, 9, 503, 493, true),
        h(1, Sex.FEMALE, 258, 1, 504, 483, false),
        h(2, Sex.MALE, 260, 8, 510, 512, true),
        h(2, Sex.FEMALE, 261, 2, 511, 511, true),
        h(3, Sex.MALE, 265, 16, 505, 507, true),
        h(3, Sex.FEMALE, 257, 2, 510, 504, false),
        h(4, Sex.MALE, 258, 6, 510, 504, false),
        h(4, Sex.FEMALE, 258, 11, 510, 503, false),
        h(5, Sex.MALE, 256, 0, 510, 511, true),
        h(5, Sex.FEMALE, 257, 4, 511, 510, false),
        h(6, Sex.MALE, 258, 6, 511, 508, false),
        h(6, Sex.FEMALE, 258, 2, 507, 506, true),
        h(7, Sex.MALE, 277, 6, 507, 505, true),
        h(7, Sex.FEMALE, 259, 4, 512, 505, false),
        h(8, Sex.MALE, 264, 0, 512, 511, true),
        h(8, Sex.FEMALE, 262, 4, 510, 510, true),
        h(10, Sex.MALE, 264, 12, 502, 508, true),
        h(10, Sex.FEMALE, 256, 1, 511, 510, false),
        h(11, Sex.MALE, 257, 7, 512, 510, false),
        h(11, Sex.FEMALE, 258, 2, 510, 507, true),
    )

    fun head(raceId: Int, sex: Sex): Head? = HEADS[raceId to sex]

    /** The creator's words for an undead row that can't be used yet. */
    const val NOT_IMPORTED = "Import the undead skins in Options"

    /** The races Wrath++'s undead skins can be made for: the living races of 3.3.5 (not the Forsaken: they are undead). */
    val RACES = setOf(1, 2, 3, 4, 6, 7, 8, 10, 11)

    /**
     * A palettized BLP2 (undead_pack.py's blp.write_palette; any alpha depth) turned back from numpy's rot90(k=1): the
     * picture [h] x [w] becomes [w] x [h], out[i][j] = in[h - 1 - j][i]. Lossless: the palette is kept, only the
     * indices (and alpha) move; one mip level (the renderer makes its own). Throws on anything but a palettized BLP2.
     */
    fun rotateBack(blp: ByteArray): ByteArray {
        if (blp.size < 1172 || String(blp, 0, 4, Charsets.ISO_8859_1) != "BLP2") throw IOException("Not a BLP2 texture.")
        val b = ByteBuffer.wrap(blp).order(ByteOrder.LITTLE_ENDIAN)
        val encoding = blp[8].toInt() and 0xFF
        val alphaDepth = blp[9].toInt() and 0xFF
        if (encoding != 1) throw IOException("Only a palettized face can be turned (encoding $encoding).")
        val w = b.getInt(12); val h = b.getInt(16)
        val off = b.getInt(20); val n = w * h
        if (w <= 0 || h <= 0 || off < 1172 || off.toLong() + n > blp.size) throw IOException("The face texture is damaged.")
        fun alpha(i: Int): Int = when (alphaDepth) {
            8 -> blp.getOrElse(off + n + i) { -1 }.toInt() and 0xFF
            4 -> ((blp.getOrElse(off + n + i / 2) { 0 }.toInt() shr ((i and 1) * 4)) and 15) * 17
            1 -> if ((blp.getOrElse(off + n + i / 8) { 0 }.toInt() shr (i and 7)) and 1 != 0) 255 else 0
            else -> 255
        }
        val nw = h; val nh = w
        val outAlpha = alphaDepth != 0
        val data = ByteArray(n * if (outAlpha) 2 else 1)
        for (i in 0 until nh) for (j in 0 until nw) {
            val src = (h - 1 - j) * w + i
            val dst = i * nw + j
            data[dst] = blp[off + src]
            if (outAlpha) data[n + dst] = alpha(src).toByte()
        }
        val out = ByteBuffer.allocate(1172 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(blp, 0, 8)
        out.put(1); out.put(if (outAlpha) 8 else 0); out.put(0); out.put(0)     // palettized, alpha, no mips
        out.putInt(nw); out.putInt(nh)
        out.putInt(1172); repeat(15) { out.putInt(0) }
        out.putInt(data.size); repeat(15) { out.putInt(0) }
        out.put(blp, 148, 1024)
        out.put(data)
        return out.array()
    }

    /** A BLP2's width and height (null when it isn't one). */
    fun size(blp: ByteArray): Pair<Int, Int>? {
        if (blp.size < 148 || String(blp, 0, 4, Charsets.ISO_8859_1) != "BLP2") return null
        val b = ByteBuffer.wrap(blp).order(ByteOrder.LITTLE_ENDIAN)
        return b.getInt(12) to b.getInt(16)
    }
}
