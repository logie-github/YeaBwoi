package com.logie.wifecraft.creator

import org.json.JSONObject
import kotlin.random.Random

/**
 * The Body tab's sculpt sliders, each from -1 to 1 (0: the body as made). Height works on every body (the actor's scale);
 * the others change the Eve body's mesh (cpp/engine/sculpt.h: smooth regions from its bone weights, scaled about the joints'
 * axes and pushed along the normals), so on a Warcraft body they are shown greyed ([eveOnly]). The engine takes them in
 * this order ([BodySculpt.toArray]; sculpt.h's SculptSlider).
 */
enum class SculptSlider(val key: String, val label: String, val eveOnly: Boolean, val drawn: Boolean = true) {
    HEIGHT("height", "Height", false),
    SHOULDERS("shoulders", "Shoulders", true),
    ARMS("arms", "Arms", true),
    BUST_SIZE("bust", "Bust Size", true),
    BUST_LIFT("bustLift", "Bust Lift", true),
    WAIST("waist", "Waist", true),
    BELLY("belly", "Belly", true),
    HIPS("hips", "Hips", true),
    BUTT("butt", "Butt", true),
    THIGHS("thighs", "Thighs", true),
    /** Not drawn yet: longer legs need the skeleton's bones moved, not only the mesh (the neck and ankle seams hold it). */
    LEG_LENGTH("legLength", "Leg Length", true, drawn = false),
}

/**
 * A body's sculpt: a value for each [SculptSlider] (missing: 0), clamped to -1..1 and kept to two decimals so that a saved
 * look reads back equal. Saved in [Appearance] as `"sculpt":{"bust":0.35,"hips":0.4}`, the zeros left out; a body at all
 * zeros is not saved at all ([isNeutral]), so every look saved before reads back as it was.
 */
data class BodySculpt(val values: Map<SculptSlider, Float> = emptyMap()) {
    operator fun get(s: SculptSlider): Float = values[s] ?: 0f

    fun with(s: SculptSlider, v: Float): BodySculpt {
        val q = quantize(v)
        return BodySculpt(if (q == 0f) values - s else values + (s to q))
    }

    val isNeutral: Boolean get() = values.values.all { it == 0f }

    /** The actor's scale for [SculptSlider.HEIGHT]: 6% taller or shorter at the ends. */
    val heightScale: Float get() = 1f + HEIGHT_REACH * this[SculptSlider.HEIGHT]

    /** The values in [SculptSlider]'s order (the engine's). */
    fun toArray(): FloatArray = FloatArray(SculptSlider.entries.size) { this[SculptSlider.entries[it]] }

    /** What a body that isn't Eve's can show: the height alone. */
    fun forWarcraftBody(): BodySculpt = BodySculpt().with(SculptSlider.HEIGHT, this[SculptSlider.HEIGHT])

    fun toJson(): JSONObject = JSONObject().also { j -> for (s in SculptSlider.entries) this[s].takeIf { it != 0f }?.let { j.put(s.key, it.toDouble()) } }

    companion object {
        const val HEIGHT_REACH = 0.06f

        fun quantize(v: Float): Float {
            if (!v.isFinite()) return 0f
            val c = v.coerceIn(-1f, 1f)
            return Math.round(c * 100f) / 100f
        }

        fun fromJson(o: JSONObject?): BodySculpt? {
            if (o == null) return null
            var s = BodySculpt()
            for (k in SculptSlider.entries) if (o.has(k.key)) s = s.with(k, o.optDouble(k.key, 0.0).toFloat())
            return s.takeIf { !it.isNeutral }
        }

        /** A plausible body: each drawn Eve slider within ±[reach], the hips and the butt leaning together. */
        fun random(random: Random, reach: Float): BodySculpt {
            fun r() = (random.nextFloat() * 2f - 1f) * reach
            val curve = r()
            var s = BodySculpt()
            for (k in SculptSlider.entries) if (k.eveOnly && k.drawn) s = s.with(k, r())
            s = s.with(SculptSlider.HIPS, (curve + s[SculptSlider.HIPS]) / 2f).with(SculptSlider.BUTT, (curve + s[SculptSlider.BUTT]) / 2f)
            return s
        }
    }
}

/**
 * Who changes the body as the sliders say: the engine for the Eve body (engine/SceneView.setSculpt, cpp/engine/sculpt.h), a
 * recording fake in the tests. [supports] says which sliders show live; the others are greyed on the Body tab with
 * [NEEDS_EVE].
 */
interface BodySculptApplier {
    fun supports(slider: SculptSlider): Boolean
    fun apply(actorKey: String, sculpt: BodySculpt)

    companion object {
        const val NEEDS_EVE = "Needs the Eve body"
    }
}

/** What each kind of body takes: a Warcraft body the height alone, the Eve body every slider. */
object SculptSupport {
    fun supports(slider: SculptSlider, eveBody: Boolean): Boolean = slider.drawn && (eveBody || !slider.eveOnly)

    /** Why a slider is greyed (null: it isn't). */
    fun why(slider: SculptSlider, eveBody: Boolean): String? = when {
        !slider.drawn -> NOT_DRAWN
        !supports(slider, eveBody) -> BodySculptApplier.NEEDS_EVE
        else -> null
    }

    const val NOT_DRAWN = "Not drawn yet (needs the skeleton changed)"
}

/** A stand-in applier (tests; the screen before the engine answers): keeps what it was given. */
class RecordingSculptApplier(private val eveBody: Boolean) : BodySculptApplier {
    val applied = ArrayList<Pair<String, BodySculpt>>()
    override fun supports(slider: SculptSlider) = SculptSupport.supports(slider, eveBody)
    override fun apply(actorKey: String, sculpt: BodySculpt) { applied += actorKey to (if (eveBody) sculpt else sculpt.forWarcraftBody()) }
}
