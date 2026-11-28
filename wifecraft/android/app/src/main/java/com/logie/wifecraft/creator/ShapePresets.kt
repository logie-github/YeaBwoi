package com.logie.wifecraft.creator

/**
 * The Bust & Butt designer's whole-body shape presets: Natural, Curvy, Athletic and the ZZZ-style proportions (a small waist over a full bust,
 * hips, butt and thighs: the look the spring-bone jiggle's ZZZ style is made for), each a set of values for the sculpt's sliders, applied in one
 * tap (a glide, one Undo step: ui/CasPanels.kt). Sliders only: no new meshes. A preset sets every slider it names and puts the others of
 * [SLIDERS] back to 0 (both sides alike); Height and Leg Length (her scale and skeleton) and the men's-only sliders are left alone.
 */
enum class BodyShapePreset(val label: String) {
    NATURAL("Natural"), CURVY("Curvy"), ATHLETIC("Athletic"), ZZZ("ZZZ-style");

    /** Its values for a woman (sliders not named: 0). */
    val values: Map<SculptSlider, Float>
        get() = when (this) {
            NATURAL -> mapOf(
                SculptSlider.BUST_DROOP to 0.15f, SculptSlider.BUST_ROUND to 0.1f, SculptSlider.UNDERBUST to 0.1f,
                SculptSlider.BUTT_ROUND to 0.1f, SculptSlider.UNDERBUTT to 0.1f,
            )
            CURVY -> mapOf(
                SculptSlider.BUST_SIZE to 0.4f, SculptSlider.BUST_ROUND to 0.3f, SculptSlider.BUST_SIDE to 0.2f, SculptSlider.UNDERBUST to 0.2f,
                SculptSlider.WAIST to -0.3f, SculptSlider.HIPS to 0.45f, SculptSlider.BUTT to 0.5f, SculptSlider.BUTT_ROUND to 0.4f,
                SculptSlider.BUTT_WIDTH to 0.3f, SculptSlider.THIGHS to 0.3f, SculptSlider.HIP_DIP to -0.3f, SculptSlider.BODY_FAT to 0.15f,
            )
            ATHLETIC -> mapOf(
                SculptSlider.BUST_SIZE to -0.25f, SculptSlider.BUST_LIFT to 0.35f, SculptSlider.BUST_UPPER to 0.2f, SculptSlider.BUST_DROOP to -0.3f,
                SculptSlider.SHOULDERS to 0.2f, SculptSlider.ARMS to 0.15f, SculptSlider.WAIST to -0.15f, SculptSlider.ABS to 0.5f,
                SculptSlider.BUTT to 0.3f, SculptSlider.BUTT_LIFT to 0.5f, SculptSlider.BUTT_PROJECTION to 0.3f, SculptSlider.HIP_DIP to 0.2f,
                SculptSlider.THIGHS to 0.25f, SculptSlider.CALVES to 0.2f, SculptSlider.MUSCLE to 0.45f, SculptSlider.BODY_FAT to -0.2f,
            )
            ZZZ -> mapOf(
                SculptSlider.BUST_SIZE to 0.55f, SculptSlider.BUST_LIFT to 0.15f, SculptSlider.BUST_ROUND to 0.4f, SculptSlider.BUST_SIDE to 0.2f,
                SculptSlider.BUST_PROJECTION to 0.2f, SculptSlider.UNDERBUST to 0.25f,
                SculptSlider.WAIST to -0.45f, SculptSlider.BELLY to -0.2f, SculptSlider.ABS to 0.25f, SculptSlider.HIPS to 0.55f,
                SculptSlider.BUTT to 0.65f, SculptSlider.BUTT_ROUND to 0.55f, SculptSlider.BUTT_WIDTH to 0.35f, SculptSlider.BUTT_PROJECTION to 0.35f,
                SculptSlider.BUTT_LIFT to 0.2f, SculptSlider.UNDERBUTT to 0.4f, SculptSlider.HIP_DIP to -0.2f, SculptSlider.THIGHS to 0.5f,
                SculptSlider.BODY_FAT to 0.2f,
            )
        }

    companion object {
        /** The sliders a preset owns (every mesh slider but the skeleton's, a woman's). */
        val SLIDERS: List<SculptSlider> = SculptSlider.entries.filter { it.mesh && it.forWomen }

        /** [s] at [preset]: its sliders set (both sides alike), the others of [SLIDERS] at 0; Height and Leg Length kept. */
        fun apply(s: BodySculpt, preset: BodyShapePreset): BodySculpt {
            var next = s
            val v = preset.values
            for (k in SLIDERS) next = next.even(k).with(k, v[k] ?: 0f)
            return next
        }

        /** The preset [s] is at (null: none). */
        fun of(s: BodySculpt): BodyShapePreset? = entries.firstOrNull { p ->
            val v = p.values
            SLIDERS.all { k -> kotlin.math.abs(s[k] - (v[k] ?: 0f)) < 0.006f && kotlin.math.abs(s.side(k)) < 0.006f }
        }
    }
}
