package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Sex
import org.json.JSONObject
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One NPC's Eve body choice (what SceneView.setEveBody passes on to the engine's EveOptions, engine/evebody.h):
 * [on]; the lower body [bottom] (a variant of the converted set: "plain", "animated", "fairy", ...; a variant the set
 * lacks takes its first); [tone] -1 for the neutral skin tinted to the NPC's own skin, else a skin tone of the set's table
 * (its index, as it is); [overlaySlot] -1 for none, else an overlay slot of the set (its index) with [overlayShade] -1 for
 * the shade nearest her skin (by Lab distance) or a shade's index; [opacity] the overlay's, 0..1 (60% by default).
 */
data class EveOptions(
    val on: Boolean = false,
    val bottom: String = "plain",
    val tone: Int = -1,
    val overlaySlot: Int = -1,
    val overlayShade: Int = -1,
    val opacity: Float = DEFAULT_OPACITY,
) {
    fun toJson(): JSONObject = JSONObject().put("on", on).put("bottom", bottom).put("tone", tone).put("overlaySlot", overlaySlot)
        .put("overlayShade", overlayShade).put("opacity", opacity.toDouble())

    companion object {
        const val DEFAULT_OPACITY = 0.6f

        fun fromJson(o: JSONObject?): EveOptions = if (o == null) EveOptions() else EveOptions(
            o.optBoolean("on", false), o.optString("bottom", "plain").ifEmpty { "plain" }, o.optInt("tone", -1), o.optInt("overlaySlot", -1),
            o.optInt("overlayShade", -1), o.optDouble("opacity", DEFAULT_OPACITY.toDouble()).toFloat().coerceIn(0f, 1f))
    }
}

/**
 * When the Eve body is drawn: a woman whose look is unclothed, the upper and the lower body both (the engine only checks its
 * fit table: the race's model allowed). As the male parts' rule (PartChoice.lowerBodyBare): No Gear, or worn clothing
 * without a shirt (4), chest or robe (5), waist (6), legs (7), wrists (9), hands (10: Eve's hands would cover gloves) or
 * tabard (19); the recruit clothing not yet put on covers her. Feet and the rest stay as they are (Eve has no feet).
 * The engine keeps her own body for any other look; the swap follows each change of look (a re-dress).
 */
object EveRule {
    val COVERING_SLOTS = setOf(4, 5, 6, 7, 9, 10, 19)

    fun unclothed(a: Appearance): Boolean = when {
        !a.gear -> true
        a.starterClothing && a.clothing.isEmpty() -> false
        else -> a.clothing.keys.none { it in COVERING_SLOTS }
    }

    /** Whether Eve is drawn for [a] with [options] (the engine still says no for a race its fit table excludes). */
    fun applies(a: Appearance, options: EveOptions?): Boolean = options?.on == true && a.sex == Sex.FEMALE && unclothed(a)
}

/** The colour maths the engine uses (engine/evebody.cpp), for the menus: Lab and the nearest overlay shade. Pure. */
object EveColour {
    private fun lin(c: Int): Double { val f = c / 255.0; return if (f <= 0.04045) f / 12.92 else ((f + 0.055) / 1.055).pow(2.4) }

    /** CIE L*a*b* (D65) of 0xRRGGBB. */
    fun lab(rgb: Int): DoubleArray {
        val r = lin((rgb shr 16) and 255); val g = lin((rgb shr 8) and 255); val b = lin(rgb and 255)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.9505
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.089
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116.0
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    fun distance(a: Int, b: Int): Double { val p = lab(a); val q = lab(b); return sqrt((p[0] - q[0]).pow(2) + (p[1] - q[1]).pow(2) + (p[2] - q[2]).pow(2)) }

    /** The index of the swatch nearest [skin] by Lab distance, -1 for none. */
    fun nearest(skin: Int, swatches: List<Int>): Int = swatches.indices.minByOrNull { distance(skin, swatches[it]) } ?: -1
}
