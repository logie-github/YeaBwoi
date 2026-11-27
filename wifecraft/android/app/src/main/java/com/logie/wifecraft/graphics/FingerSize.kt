package com.logie.wifecraft.graphics

import com.logie.wifecraft.anim.CharacterSize

/**
 * Finger size (Options > Graphics, next to Flesh contact): how big the player's own fingertip is on the skin. Pure JVM.
 *
 * The finger is the player's, not the body's: its size in metres is fixed (a fingertip pad about 1.1 cm in radius at 100%, so a tip
 * 2.2 cm across) and does **not** change with the body it touches, so a gnome woman's dent is as wide as a human's and looks
 * finger-sized against a small body.
 *
 * **Units.** The scene's world unit is the game's yard (0.9144 m) at Character size 1. Character size c (CharacterSize: 0.8 to 1.8,
 * 1.25 by default) draws every person c times larger so they fit the rooms, which makes the world (as people see it) c times larger
 * than a real one: a real metre is therefore c / 0.9144 world units, and a finger of r metres is r * c / 0.9144 of them. The result
 * goes to the engine as the touch's pad radius ([padRadius]); the engine works in multiples of it (dent radius 1.5 pads, the deepest
 * dent 1.1 pads and never more than 1.4, a pinch ridge as wide as the dent), so this is the only place the mapping lives.
 *
 * **Touch size.** Where the phone says how big the finger's contact is (MotionEvent.getTouchMajor) the pad is modulated a little by
 * it ([touchScale]), clamped to 0.8x to 1.4x.
 */
object FingerSize {
    const val MIN = 50
    const val MAX = 200
    const val DEFAULT = 100
    const val STEP = 5

    /** The fingertip pad's radius in metres at 100%. */
    const val PAD_M = 0.011f
    /** Metres in the game's yard (the world unit at Character size 1). */
    const val YARD_M = 0.9144f
    const val TOUCH_SCALE_MIN = 0.8f
    const val TOUCH_SCALE_MAX = 1.4f
    /** A typical fingertip's touch major axis, in millimetres (the size at which the pad is not modulated). */
    const val TYPICAL_TOUCH_MM = 9f

    fun clamp(percent: Int): Int = percent.coerceIn(MIN, MAX)

    /** World units per real metre at Character size [characterSize]. */
    fun worldPerMetre(characterSize: Float = CharacterSize.active): Float =
        (if (characterSize.isFinite() && characterSize > 0f) characterSize else 1f) / YARD_M

    /** The pad's radius in world units for [percent], Character size [characterSize] and a [touchScale] (see [touchScale]). */
    fun padRadius(percent: Int, characterSize: Float = CharacterSize.active, touchScale: Float = 1f): Float =
        PAD_M * (clamp(percent) / 100f) * worldPerMetre(characterSize) * touchScale.coerceIn(TOUCH_SCALE_MIN, TOUCH_SCALE_MAX)

    /** The pad's size in metres (what the player's own fingertip is) for [percent] and [touchScale]. */
    fun padMetres(percent: Int, touchScale: Float = 1f): Float = PAD_M * (clamp(percent) / 100f) * touchScale.coerceIn(TOUCH_SCALE_MIN, TOUCH_SCALE_MAX)

    /**
     * The pad's modulation from the phone's reported contact size: [touchMajorPx] (MotionEvent.getTouchMajor, pixels; 0 or less: not
     * reported) against a typical fingertip ([TYPICAL_TOUCH_MM]) at [pxPerMm]. Within 0.8 to 1.4, and 1 when nothing is reported; only
     * a little of the difference is taken (square root), so a noisy sensor can't swing the dent.
     */
    fun touchScale(touchMajorPx: Float, pxPerMm: Float): Float {
        if (!(touchMajorPx > 0f) || !(pxPerMm > 0f) || !touchMajorPx.isFinite() || !pxPerMm.isFinite()) return 1f
        val mm = touchMajorPx / pxPerMm
        return kotlin.math.sqrt(mm / TYPICAL_TOUCH_MM).coerceIn(TOUCH_SCALE_MIN, TOUCH_SCALE_MAX)
    }
}
