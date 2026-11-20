package com.logie.wifecraft.bodyparts

import kotlin.math.pow

/**
 * How a race's body takes the parts (cpp engine/bodypart.h, Native.partFit): the engine finds where the legs part and the
 * body's front from the model's own lower body and sizes the part by the Pelvis pivot height; these are the corrections on
 * top, fitted on the desktop harness (engine-test, `part` commands). [dx], [dz]: forward and up, in pelvis pivot heights;
 * [scale]: a factor on the size.
 */
data class PartFit(val dx: Float = 0f, val dz: Float = 0f, val scale: Float = 1f)

/** The parts' states as Native.partState takes them (drawing; [PartState] is a part file's own state), and the rule for when they show. */
object PartDraw {
    const val NONE = 0
    const val DEFAULT = 1
    const val ON = 2

    /**
     * What to draw for an NPC who should show [requested]: nothing unless its look is bare ([bare]: the roster's
     * VariantKind.BARE, no lower-body clothing or underwear). A look's underwear is mostly a texture layer, not a mesh, so
     * a part over it would show through: the caller, which knows the look, decides; the engine draws what it is told.
     */
    fun visible(requested: Int, bare: Boolean): Int = if (!bare || requested !in DEFAULT..ON) NONE else requested
}

object PartFits {
    /** Fitted per race (playable race ids); the others take the engine's measurement as it is. */
    private val byRace = mapOf(
        1 to PartFit(scale = 1.1f),    // human: heavy thighs, the measured size reads small
        2 to PartFit(scale = 1.1f),    // orc: as the human
        4 to PartFit(),                // night elf
        6 to PartFit(),                // tauren
    )

    fun forRace(raceId: Int): PartFit = byRace[raceId] ?: PartFit()
}

/** The NPC's skin colour where the parts meet the body, and a part's texture tinted to it. Pure. */
object PartSkin {
    private val toLinear = FloatArray(256) { i -> val c = i / 255f; if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat() }
    private fun toSrgb(c: Float): Float = if (c <= 0.0031308f) c * 12.92f else (1.055f * c.toDouble().pow(1 / 2.4).toFloat() - 0.055f)
    private fun byte(c: Float): Int = (c.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /**
     * The mean colour (0xRRGGBB, in linear light) of [skin]'s opaque texels within [radius] of each (u, v) of [uvs] (v 0 at the
     * top row: Native.partSkinUvs, the body's vertices around the parts' root); null when none is opaque.
     */
    fun sampleAt(skin: RgbaImage, uvs: FloatArray, radius: Int = 2): Int? {
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        var i = 0
        while (i + 1 < uvs.size) {
            val cx = (uvs[i] * skin.width).toInt(); val cy = (uvs[i + 1] * skin.height).toInt()
            for (y in maxOf(0, cy - radius)..minOf(skin.height - 1, cy + radius)) for (x in maxOf(0, cx - radius)..minOf(skin.width - 1, cx + radius)) {
                val k = (y * skin.width + x) * 4
                if ((skin.rgba[k + 3].toInt() and 0xFF) < 128) continue
                r += toLinear[skin.rgba[k].toInt() and 0xFF]; g += toLinear[skin.rgba[k + 1].toInt() and 0xFF]; b += toLinear[skin.rgba[k + 2].toInt() and 0xFF]
                n++
            }
            i += 2
        }
        if (n == 0) return null
        return (byte(toSrgb((r / n).toFloat())) shl 16) or (byte(toSrgb((g / n).toFloat())) shl 8) or byte(toSrgb((b / n).toFloat()))
    }

    /**
     * [texture] (the part's <id>.png) tinted to [skin] sampled at [uvs] ([Recolor.recolor], its reference the part's own
     * average over the texels its mesh uses); as it is when nothing could be sampled. What SceneView.attachPart takes.
     */
    fun tinted(texture: RgbaImage, part: ConvertedPart, skin: RgbaImage, uvs: FloatArray): RgbaImage {
        val target = sampleAt(skin, uvs) ?: return texture
        val mask = part.uvs?.let { Recolor.uvMask(it, part.indices, texture.width, texture.height) }
        return Recolor.recolor(texture, target, mask)
    }
}
