package com.logie.wifecraft.creator

import kotlin.math.abs
import kotlin.random.Random

/**
 * The Body tab's Bust & Butt designer: the bust's and the butt's shape sliders on one compact panel (ui/CasPanels.kt), drawn live
 * as they move (the engine's live sculpt: cpp/engine/sculpt.h SculptMorph, every change on the GPU at once, the CPU sculpt once
 * the sliders rest). Its groups ([DesignerGroup]: a woman's Bust and Butt, a man's Chest and Glutes), its camera presets
 * ([DesignerView]), its quick shapes ([ShapePreset], animated to: [tween]), Randomize and Reset for a group. Pure: the screen feeds
 * it and sends what it gives; JVM-tested.
 */
enum class DesignerGroup(val label: String, val part: SculptPart, val sliders: List<SculptSlider>) {
    BUST("Bust", SculptPart.CHEST, listOf(
        SculptSlider.BUST_SIZE, SculptSlider.BUST_WIDTH, SculptSlider.BUST_SPACING, SculptSlider.BUST_PROJECTION, SculptSlider.BUST_LIFT,
        SculptSlider.BUST_DROOP, SculptSlider.BUST_UPPER, SculptSlider.BUST_ROUND, SculptSlider.BUST_SIDE, SculptSlider.UNDERBUST)),
    BUTT("Butt", SculptPart.HIPS, listOf(
        SculptSlider.BUTT, SculptSlider.BUTT_WIDTH, SculptSlider.BUTT_LIFT, SculptSlider.BUTT_PROJECTION, SculptSlider.BUTT_ROUND,
        SculptSlider.CHEEK_GAP, SculptSlider.HIP_DIP, SculptSlider.UNDERBUTT, SculptSlider.HIPS, SculptSlider.WAIST, SculptSlider.THIGHS)),
    CHEST("Chest", SculptPart.CHEST, listOf(SculptSlider.CHEST, SculptSlider.PECS, SculptSlider.ABS, SculptSlider.SHOULDERS)),
    GLUTES("Glutes", SculptPart.HIPS, listOf(
        SculptSlider.BUTT, SculptSlider.BUTT_WIDTH, SculptSlider.BUTT_LIFT, SculptSlider.BUTT_PROJECTION, SculptSlider.BUTT_ROUND,
        SculptSlider.HIPS, SculptSlider.WAIST, SculptSlider.THIGHS));

    /** The designer's short name for [s] in this group ("Size" for the bust's size...). */
    fun labelOf(s: SculptSlider, female: Boolean): String = SHORT[s] ?: s.labelFor(female)

    companion object {
        /** The groups a body of [female]'s sex has. */
        fun of(female: Boolean): List<DesignerGroup> = if (female) listOf(BUST, BUTT) else listOf(CHEST, GLUTES)

        private val SHORT = mapOf(
            SculptSlider.BUST_SIZE to "Size", SculptSlider.BUST_WIDTH to "Width", SculptSlider.BUST_SPACING to "Spacing",
            SculptSlider.BUST_PROJECTION to "Projection", SculptSlider.BUST_LIFT to "Lift", SculptSlider.BUST_DROOP to "Droop",
            SculptSlider.BUST_ROUND to "Roundness", SculptSlider.BUTT_WIDTH to "Width", SculptSlider.BUTT_LIFT to "Lift",
            SculptSlider.BUTT_PROJECTION to "Projection", SculptSlider.BUTT_ROUND to "Roundness",
        )
    }
}

/**
 * The designer's camera presets: the creator's turn ([facing], degrees, as ui/CreatorLayout.kt ViewState has it: 0 facing the
 * camera, 180 her back) and its zoom and focus for a group (the bust's height or the hips').
 */
enum class DesignerView(val label: String, val facing: Float) {
    FRONT("Front", 0f), THREE_QUARTER("3/4", 40f), SIDE("Side", 90f), BACK("Back", 180f);

    fun zoom(group: DesignerGroup): Float = if (group.part == SculptPart.CHEST) 2.2f else 2.0f
    fun focus(group: DesignerGroup): Float = if (group.part == SculptPart.CHEST) 0.68f else 0.46f

    companion object {
        /** The view a group opens on: the bust from the side (its shape shows best there), the butt from behind at three-quarters. */
        fun first(group: DesignerGroup): DesignerView = if (group.part == SculptPart.CHEST) SIDE else BACK
    }
}

/** The designer's quick shapes, each a set of values for a group's sliders (the rest of the group back to 0). */
enum class ShapePreset(val label: String) {
    SLIM("Slim"), NATURAL("Natural"), ATHLETIC("Athletic"), CURVY("Curvy"), FULL("Full");

    /** Its values for [group] (sliders not named: 0). */
    fun values(group: DesignerGroup): Map<SculptSlider, Float> = when (group) {
        DesignerGroup.BUST -> when (this) {
            SLIM -> mapOf(SculptSlider.BUST_SIZE to -0.55f, SculptSlider.BUST_PROJECTION to -0.3f, SculptSlider.BUST_WIDTH to -0.2f, SculptSlider.BUST_LIFT to 0.2f)
            NATURAL -> mapOf(SculptSlider.BUST_DROOP to 0.2f, SculptSlider.BUST_ROUND to 0.1f, SculptSlider.UNDERBUST to 0.15f)
            ATHLETIC -> mapOf(SculptSlider.BUST_SIZE to -0.25f, SculptSlider.BUST_LIFT to 0.4f, SculptSlider.BUST_UPPER to 0.2f, SculptSlider.BUST_DROOP to -0.3f)
            CURVY -> mapOf(SculptSlider.BUST_SIZE to 0.45f, SculptSlider.BUST_ROUND to 0.35f, SculptSlider.BUST_SIDE to 0.25f, SculptSlider.UNDERBUST to 0.2f)
            FULL -> mapOf(SculptSlider.BUST_SIZE to 0.8f, SculptSlider.BUST_PROJECTION to 0.35f, SculptSlider.BUST_ROUND to 0.4f,
                SculptSlider.BUST_DROOP to 0.3f, SculptSlider.BUST_SIDE to 0.35f, SculptSlider.UNDERBUST to 0.3f)
        }
        DesignerGroup.BUTT, DesignerGroup.GLUTES -> when (this) {
            SLIM -> mapOf(SculptSlider.BUTT to -0.5f, SculptSlider.BUTT_WIDTH to -0.3f, SculptSlider.HIPS to -0.3f, SculptSlider.THIGHS to -0.25f)
            NATURAL -> mapOf(SculptSlider.BUTT_ROUND to 0.1f, SculptSlider.UNDERBUTT to 0.1f)
            ATHLETIC -> mapOf(SculptSlider.BUTT to 0.3f, SculptSlider.BUTT_LIFT to 0.5f, SculptSlider.BUTT_PROJECTION to 0.3f, SculptSlider.HIP_DIP to 0.2f,
                SculptSlider.THIGHS to 0.15f).filterKeys { it in group.sliders }
            CURVY -> mapOf(SculptSlider.BUTT to 0.5f, SculptSlider.BUTT_ROUND to 0.4f, SculptSlider.BUTT_WIDTH to 0.3f, SculptSlider.HIPS to 0.45f,
                SculptSlider.WAIST to -0.3f, SculptSlider.HIP_DIP to -0.3f).filterKeys { it in group.sliders }
            FULL -> mapOf(SculptSlider.BUTT to 0.8f, SculptSlider.BUTT_PROJECTION to 0.5f, SculptSlider.BUTT_ROUND to 0.5f, SculptSlider.BUTT_WIDTH to 0.4f,
                SculptSlider.HIPS to 0.4f, SculptSlider.THIGHS to 0.35f, SculptSlider.UNDERBUTT to 0.3f)
        }
        DesignerGroup.CHEST -> when (this) {
            SLIM -> mapOf(SculptSlider.CHEST to -0.5f, SculptSlider.PECS to -0.3f, SculptSlider.SHOULDERS to -0.2f)
            NATURAL -> emptyMap()
            ATHLETIC -> mapOf(SculptSlider.CHEST to 0.35f, SculptSlider.PECS to 0.5f, SculptSlider.ABS to 0.6f, SculptSlider.SHOULDERS to 0.25f)
            CURVY -> mapOf(SculptSlider.CHEST to 0.3f, SculptSlider.PECS to 0.2f, SculptSlider.ABS to -0.2f)
            FULL -> mapOf(SculptSlider.CHEST to 0.8f, SculptSlider.PECS to 0.7f, SculptSlider.ABS to 0.4f, SculptSlider.SHOULDERS to 0.5f)
        }
    }
}

object BustButtDesigner {
    /** How long (ms) a preset's sliders take to glide to it. */
    const val ANIM_MS = 320L

    /** How far Randomize goes on a group's sliders. */
    const val RANDOM_REACH = 0.7f

    /** [s] with [group]'s sliders at [preset]'s values (both sides alike). */
    fun applyPreset(s: BodySculpt, preset: ShapePreset, group: DesignerGroup): BodySculpt {
        val v = preset.values(group)
        var next = s
        for (k in group.sliders) next = next.even(k).with(k, v[k] ?: 0f)
        return next
    }

    /** The ease of a glide (0..1 to 0..1, slow at both ends). */
    fun ease(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }

    /** [from] gliding to [to] on [sliders] at [t] (0..1, eased; both values and side balances; 1: exactly [to] on them). */
    fun tween(from: BodySculpt, to: BodySculpt, t: Float, sliders: List<SculptSlider>): BodySculpt {
        if (t >= 1f) {
            var done = from
            for (k in sliders) done = done.with(k, to[k]).balanced(k, to.side(k))
            return done
        }
        val e = ease(t)
        var s = from
        for (k in sliders) {
            s = s.with(k, from[k] + (to[k] - from[k]) * e)
            if (k.sided) s = s.balanced(k, from.side(k) + (to.side(k) - from.side(k)) * e)
        }
        return s
    }

    /**
     * A plausible new shape for [group]: each slider within ±[reach], its size leading (the shapes leaning its way), both sides
     * alike; the other groups' sliders kept.
     */
    fun randomize(s: BodySculpt, random: Random, group: DesignerGroup, reach: Float = RANDOM_REACH): BodySculpt {
        fun r() = (random.nextFloat() * 2f - 1f) * reach
        val size = r()
        var next = s
        for (k in group.sliders) {
            val v = if (k == group.sliders.first()) size else (r() + 0.35f * size) / 1.35f
            next = next.even(k).with(k, v.coerceIn(-reach, reach))
        }
        return next
    }

    /** [group]'s sliders back to 0, both sides. */
    fun reset(s: BodySculpt, group: DesignerGroup): BodySculpt {
        var next = s
        for (k in group.sliders) next = next.even(k).with(k, 0f)
        return next
    }

    /** [group]'s sliders' sides made alike (the Mirror switch turned back on). */
    fun mirror(s: BodySculpt, group: DesignerGroup): BodySculpt {
        var next = s
        for (k in group.sliders) next = next.even(k)
        return next
    }

    /** Whether [s] has [group]'s sliders all at 0 (both sides). */
    fun isNeutral(s: BodySculpt, group: DesignerGroup): Boolean = group.sliders.all { abs(s[it]) < 1e-4f && abs(s.side(it)) < 1e-4f }

    /** The preset [s] is at on [group] (null: none). */
    fun presetOf(s: BodySculpt, group: DesignerGroup): ShapePreset? =
        ShapePreset.entries.firstOrNull { p -> val v = p.values(group); group.sliders.all { abs(s[it] - (v[it] ?: 0f)) < 0.006f && s.side(it) == 0f } }
}
