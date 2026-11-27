package com.logie.wifecraft.creator

import org.json.JSONObject
import kotlin.random.Random

/**
 * The parts of the body the Body tab's Sculpt groups its sliders by (its part selector), each the place a tap or a drag on her
 * body lands on ([of]: the tap-to-edit's [BodyRegion]).
 */
enum class SculptPart(val label: String) {
    OVERALL("Overall"), NECK("Neck"), SHOULDERS("Shoulders"), CHEST("Chest"), ARMS("Arms"), WAIST("Waist"), HIPS("Hips"), LEGS("Legs");

    /** Its sliders in the engine's order (those for her sex: a man has no bust shapes); on a man the chest's own slider first. */
    fun sliders(female: Boolean = true): List<SculptSlider> {
        val all = SculptSlider.entries.filter { it.part == this && (if (female) it.forWomen else it.forMen) }
        return if (this == CHEST && !female) all.sortedBy { if (it == SculptSlider.CHEST) 0 else 1 } else all
    }

    companion object {
        /** The part a tapped region of her body is (null: a region the sculpt has no part for: the head, hair, hands, feet). */
        fun of(region: BodyRegion): SculptPart? = when (region) {
            BodyRegion.NECK -> NECK
            BodyRegion.CHEST -> CHEST
            BodyRegion.WAIST -> WAIST
            BodyRegion.HIPS -> HIPS
            BodyRegion.LEGS -> LEGS
            BodyRegion.ARMS -> ARMS
            else -> null
        }
    }
}

/**
 * The Body tab's sculpt sliders, each from -1 to 1 (0: the body as made), on every body: the game's own (every race's default
 * model, both sexes: cpp/engine/sculpt.h on its whole vertex list, bind pose, so it bends with its bones as before; the robe's
 * and sleeves' geosets on it follow) and the Liard HD figure alike. Height is the actor's scale and Leg Length the skeleton's
 * (cpp/engine/bodyrig.h, [legFactor]); the others move the mesh ([mesh]). [sided] sliders can differ left and right
 * ([BodySculpt.sides], the creator's Symmetry off). The engine takes them in this order ([BodySculpt.toArray]; sculpt.h's
 * SculptSlider): the first eleven as they always were, the rest added after them, so older saves read back unchanged.
 */
enum class SculptSlider(val key: String, val label: String, val part: SculptPart, val mesh: Boolean = true, val sided: Boolean = true,
                        val drawn: Boolean = true, val forWomen: Boolean = true, val forMen: Boolean = true) {
    HEIGHT("height", "Height", SculptPart.OVERALL, mesh = false, sided = false),
    SHOULDERS("shoulders", "Shoulders", SculptPart.SHOULDERS),
    ARMS("arms", "Arms", SculptPart.ARMS),
    BUST_SIZE("bust", "Bust Size", SculptPart.CHEST),
    BUST_LIFT("bustLift", "Bust Lift", SculptPart.CHEST),
    WAIST("waist", "Waist", SculptPart.WAIST),
    BELLY("belly", "Belly", SculptPart.WAIST, sided = false),
    HIPS("hips", "Hips", SculptPart.HIPS),
    BUTT("butt", "Butt", SculptPart.HIPS),
    THIGHS("thighs", "Thighs", SculptPart.LEGS),
    /** The skeleton's legs (engine/bodyrig.h): on every body, the Liard figure's or the game's own. */
    LEG_LENGTH("legLength", "Leg Length", SculptPart.LEGS, mesh = false, sided = false),
    NECK("neck", "Neck", SculptPart.NECK, sided = false),
    CHEST("chest", "Chest", SculptPart.CHEST),
    BUST_WIDTH("bustWidth", "Bust Width", SculptPart.CHEST),
    BUST_SPACING("bustSpacing", "Bust Spacing", SculptPart.CHEST, sided = false),
    SHOULDER_SLOPE("shoulderSlope", "Shoulder Slope", SculptPart.SHOULDERS),
    UPPER_ARMS("upperArms", "Upper Arms", SculptPart.ARMS),
    FOREARMS("forearms", "Forearms", SculptPart.ARMS),
    CALVES("calves", "Calves", SculptPart.LEGS),
    BUTT_LIFT("buttLift", "Butt Lift", SculptPart.HIPS),
    /** Overall softness: shares of the waist, belly, hips, butt, thighs, arms, calves, bust and neck (sculpt.h). */
    BODY_FAT("bodyFat", "Body Fat", SculptPart.OVERALL, sided = false),
    /** Overall muscle: shares of the shoulders, arms, chest, thighs, calves and neck, the waist and belly a little less. */
    MUSCLE("muscle", "Muscle", SculptPart.OVERALL, sided = false),
    // the Bust & Butt designer's shapes (BustButtDesigner.kt; sculpt.h after the twenty-two: older saves and arrays end before them)
    /** The bust forward from the chest wall, its tip leading. */
    BUST_PROJECTION("bustProjection", "Bust Projection", SculptPart.CHEST, forMen = false),
    /** The tips down (and the underside rolled out over the fold); below 0 perkier. */
    BUST_DROOP("bustDroop", "Bust Droop", SculptPart.CHEST, forMen = false),
    /** The upper slope filled (or hollowed) between the collarbones and the tips. */
    BUST_UPPER("bustUpper", "Upper Fullness", SculptPart.CHEST, forMen = false),
    /** Fuller round each tip (rounder), or narrower and more conical. */
    BUST_ROUND("bustRound", "Bust Roundness", SculptPart.CHEST, forMen = false),
    /** Each breast's outer side out toward the arm. */
    BUST_SIDE("bustSide", "Side Fullness", SculptPart.CHEST, forMen = false),
    /** The underside fuller (down and forward). */
    UNDERBUST("underbust", "Underbust", SculptPart.CHEST, forMen = false),
    /** The butt's back half wider. */
    BUTT_WIDTH("buttWidth", "Butt Width", SculptPart.HIPS),
    /** Each cheek's crown back. */
    BUTT_PROJECTION("buttProjection", "Butt Projection", SculptPart.HIPS),
    /** Fuller round each cheek (mostly down and out), or flatter. */
    BUTT_ROUND("buttRound", "Butt Roundness", SculptPart.HIPS),
    /** The cleft between the cheeks deeper (or shallower). */
    CHEEK_GAP("cheekGap", "Cheek Gap", SculptPart.HIPS, sided = false),
    /** The side under the hip bone drawn in (below 0 filled out). */
    HIP_DIP("hipDip", "Hip Dip", SculptPart.HIPS),
    /** The fold where the cheek meets the thigh, fuller. */
    UNDERBUTT("underbutt", "Underbutt", SculptPart.HIPS),
    /** The pectorals' lower half forward (a narrower band than Chest). */
    PECS("pecs", "Pec Definition", SculptPart.CHEST),
    /** The belly flatter, its middle line drawn in. */
    ABS("abs", "Abs", SculptPart.WAIST, sided = false);

    /** Whether it is one of the designer's shapes (added after the twenty-two; [BodySculpt.random] leaves them to the designer). */
    val designer: Boolean get() = ordinal >= BUST_PROJECTION.ordinal

    /** Its name on a body of [female]'s sex: a man's Butt is his Glutes. */
    fun labelFor(female: Boolean): String = if (!female && this == BUTT) "Glutes" else label
}

/** A side of her body (her own left and right, as the engine's model space has them). */
enum class BodySide { LEFT, RIGHT }

/**
 * A body's sculpt: a value for each [SculptSlider] (missing: 0) and, for a [SculptSlider.sided] one, how far her left side
 * is above it and her right below ([sides]: left = value + side, right = value - side, each kept within -1..1). Clamped and
 * kept to two decimals so that a saved look reads back equal. Saved in [Appearance] as
 * `"sculpt":{"bust":0.35,"hips":0.4,"sides":{"hips":0.1}}`, the zeros left out; a body at all zeros is not saved at all
 * ([isNeutral]), so every look saved before reads back as it was.
 */
data class BodySculpt(val values: Map<SculptSlider, Float> = emptyMap(), val sides: Map<SculptSlider, Float> = emptyMap()) {
    operator fun get(s: SculptSlider): Float = values[s] ?: 0f

    /** The side balance of [s] (0: both sides alike). */
    fun side(s: SculptSlider): Float = sides[s] ?: 0f

    /** [s] on one side of her body. */
    fun on(s: SculptSlider, side: BodySide): Float =
        (this[s] + if (side == BodySide.LEFT) side(s) else -side(s)).coerceIn(-1f, 1f)

    /** [s] set to [v] on both sides (a side's balance kept, as far as both sides stay within -1..1). */
    fun with(s: SculptSlider, v: Float): BodySculpt {
        val q = quantize(v)
        val next = copy(values = if (q == 0f) values - s else values + (s to q))
        return next.withBalance(s, next.side(s))
    }

    /** [s] set to [v] on one side alone (the other side kept); a slider that isn't [SculptSlider.sided]: on both. */
    fun withSide(s: SculptSlider, side: BodySide, v: Float): BodySculpt {
        if (!s.sided) return with(s, v)
        val x = quantize(v)
        val other = on(s, if (side == BodySide.LEFT) BodySide.RIGHT else BodySide.LEFT)
        val mean = quantize((x + other) / 2f)
        val half = (x - other) / 2f
        val b = if (side == BodySide.LEFT) half else -half
        val next = copy(values = if (mean == 0f) values - s else values + (s to mean))
        return next.withBalance(s, b)
    }

    /** Both sides of [s] made alike again. */
    fun even(s: SculptSlider): BodySculpt = copy(sides = sides - s)

    /** [s]'s side balance set to [b] (her left [s] + b, her right [s] - b), as far as both sides stay within -1..1. */
    fun balanced(s: SculptSlider, b: Float): BodySculpt = withBalance(s, b)

    private fun withBalance(s: SculptSlider, b: Float): BodySculpt {
        if (!s.sided) return copy(sides = sides - s)
        // as far as both sides stay within -1..1
        val v = this[s]
        val room = 1f - kotlin.math.abs(v)
        val q = quantize(b.coerceIn(-room, room))
        return copy(sides = if (q == 0f) sides - s else sides + (s to q))
    }

    /** Every slider of [part] back to 0 (both sides). */
    fun reset(part: SculptPart): BodySculpt =
        BodySculpt(values.filterKeys { it.part != part }, sides.filterKeys { it.part != part })

    val isNeutral: Boolean get() = values.values.all { it == 0f } && sides.values.all { it == 0f }

    /** Whether any slider's two sides differ. */
    val isSided: Boolean get() = sides.values.any { it != 0f }

    /** The actor's scale for [SculptSlider.HEIGHT]: 6% taller or shorter at the ends. */
    val heightScale: Float get() = 1f + HEIGHT_REACH * this[SculptSlider.HEIGHT]

    /** The values in [SculptSlider]'s order (the engine's), then each one's side balance (sculpt.h kSculptValues). */
    fun toArray(): FloatArray {
        val n = SculptSlider.entries.size
        return FloatArray(2 * n) { i -> if (i < n) this[SculptSlider.entries[i]] else side(SculptSlider.entries[i - n]) }
    }

    /** The legs' length over the body as made ([legFactor] of [SculptSlider.LEG_LENGTH]). */
    val legScale: Float get() = legFactor(this[SculptSlider.LEG_LENGTH])

    fun toJson(): JSONObject = JSONObject().also { j ->
        for (s in SculptSlider.entries) this[s].takeIf { it != 0f }?.let { j.put(s.key, it.toDouble()) }
        if (isSided) j.put(SIDES_KEY, JSONObject().also { o -> for (s in SculptSlider.entries) side(s).takeIf { it != 0f }?.let { o.put(s.key, it.toDouble()) } })
    }

    companion object {
        const val HEIGHT_REACH = 0.06f
        /** Leg Length's ends: the thighs and shins this many times as long at -1 and at +1 (engine/bodyrig.h kLegShortest, kLegLongest). */
        const val LEG_SHORTEST = 0.85f
        const val LEG_LONGEST = 1.2f
        /** The JSON key of the sides' balances (not a slider's key). */
        const val SIDES_KEY = "sides"

        /** The slider (-1..1) as the legs' factor, as the engine maps it (bodyrig.h legLengthFactor): linear on each side of 0. */
        fun legFactor(v: Float): Float {
            if (!v.isFinite()) return 1f
            val c = v.coerceIn(-1f, 1f)
            return if (c < 0f) 1f + (1f - LEG_SHORTEST) * c else 1f + (LEG_LONGEST - 1f) * c
        }

        fun quantize(v: Float): Float {
            if (!v.isFinite()) return 0f
            val c = v.coerceIn(-1f, 1f)
            return Math.round(c * 100f) / 100f
        }

        fun fromJson(o: JSONObject?): BodySculpt? {
            if (o == null) return null
            var s = BodySculpt()
            for (k in SculptSlider.entries) if (o.has(k.key)) s = s.with(k, o.optDouble(k.key, 0.0).toFloat())
            o.optJSONObject(SIDES_KEY)?.let { sides ->
                for (k in SculptSlider.entries) if (sides.has(k.key)) s = s.withBalance(k, sides.optDouble(k.key, 0.0).toFloat())
            }
            return s.takeIf { !it.isNeutral }
        }

        /** A plausible body: each mesh slider within ±[reach] (the designer's shapes left at 0: BustButtDesigner.randomize), the
         *  hips and the butt leaning together; both sides alike. */
        fun random(random: Random, reach: Float): BodySculpt {
            fun r() = (random.nextFloat() * 2f - 1f) * reach
            val curve = r()
            var s = BodySculpt()
            for (k in SculptSlider.entries) if (k.mesh && k.drawn && !k.designer) s = s.with(k, r())
            s = s.with(SculptSlider.HIPS, (curve + s[SculptSlider.HIPS]) / 2f).with(SculptSlider.BUTT, (curve + s[SculptSlider.BUTT]) / 2f)
            return s
        }
    }
}

/**
 * Who changes the body as the sliders say: the engine (engine/SceneView.setSculpt, cpp/engine/sculpt.h: the game's own body
 * or the HD figure), a recording fake in the tests. [supports] says which sliders show live: every one on every body now.
 */
interface BodySculptApplier {
    fun supports(slider: SculptSlider): Boolean
    fun apply(actorKey: String, sculpt: BodySculpt)
}

/** What each kind of body takes: every drawn slider, the game's own model and the Liard HD figure alike. */
object SculptSupport {
    @Suppress("UNUSED_PARAMETER")
    fun supports(slider: SculptSlider, body: com.logie.wifecraft.bodyparts.BodyKind): Boolean = slider.drawn

    /** Why a slider is greyed (null: it isn't). */
    fun why(slider: SculptSlider, body: com.logie.wifecraft.bodyparts.BodyKind): String? = if (!supports(slider, body)) NOT_DRAWN else null

    /** (No slider is undrawn now; kept for a slider added before the engine draws it.) */
    const val NOT_DRAWN = "Not drawn yet"
}

/** A stand-in applier (tests; the screen before the engine answers): keeps what it was given. */
class RecordingSculptApplier(private val body: com.logie.wifecraft.bodyparts.BodyKind) : BodySculptApplier {
    val applied = ArrayList<Pair<String, BodySculpt>>()
    override fun supports(slider: SculptSlider) = SculptSupport.supports(slider, body)
    override fun apply(actorKey: String, sculpt: BodySculpt) { applied += actorKey to sculpt }
}
