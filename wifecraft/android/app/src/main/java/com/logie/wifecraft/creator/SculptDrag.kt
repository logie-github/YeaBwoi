package com.logie.wifecraft.creator

import kotlin.math.abs

/**
 * The creator's drag handles: a long press on a part of her body picks it (the tap-to-edit's pick), and the finger dragged on
 * her then changes that part as the Sims' Create-a-Sim does. Up and down is its size, left and right its width (or a man's
 * shoulders...); in Lift mode (the panel's switch, or a second finger down) up and down lifts it and left and right spaces it.
 * Each axis is one [SculptSlider] ([axes]); the drag moves that slider from where it was by how far the finger went
 * ([valueFor]: half the stage's height sweeps the whole -1..1), so the slider on the panel moves with it. With Symmetry off and
 * a side touched, only that side of a [SculptSlider.sided] slider changes. Pure: the screen (ui/CasScreen.kt) feeds it the
 * finger and sends what it gives; JVM-tested.
 */
object SculptDrag {
    /** How far (dp) the finger goes before the drag picks its axis. */
    const val SLOP_DP = 12f

    /** The share of the stage's height that sweeps a slider from -1 to 1. */
    const val SWEEP = 0.5f

    /** How long (ms) a finger rests on her before the press picks the part under it. */
    const val LONG_PRESS_MS = 380L

    /** One part's drag: which slider each direction moves (null: that direction does nothing) and whether up lowers it. */
    data class Axes(val vertical: SculptSlider?, val horizontal: SculptSlider?, val invertVertical: Boolean = false)

    /**
     * The sliders a drag on [part] moves: its size and width, or (in [lift] mode) its lift and spacing, or (in [project] mode, the
     * Bust & Butt designer's Projection) the bust's or butt's projection and roundness, a man's pectorals.
     */
    fun axes(part: SculptPart, female: Boolean, lift: Boolean, project: Boolean = false): Axes = when {
        project && !lift && part == SculptPart.CHEST -> if (female) Axes(SculptSlider.BUST_PROJECTION, SculptSlider.BUST_ROUND) else Axes(SculptSlider.PECS, null)
        project && !lift && part == SculptPart.HIPS -> Axes(SculptSlider.BUTT_PROJECTION, SculptSlider.BUTT_ROUND)
        else -> sizeAxes(part, female, lift)
    }

    private fun sizeAxes(part: SculptPart, female: Boolean, lift: Boolean): Axes = when (part) {
        SculptPart.OVERALL -> if (lift) Axes(SculptSlider.HEIGHT, null) else Axes(SculptSlider.BODY_FAT, SculptSlider.MUSCLE)
        SculptPart.NECK -> if (lift) Axes(SculptSlider.SHOULDER_SLOPE, null, invertVertical = true) else Axes(SculptSlider.NECK, SculptSlider.SHOULDERS)
        SculptPart.SHOULDERS -> Axes(SculptSlider.SHOULDER_SLOPE, SculptSlider.SHOULDERS, invertVertical = true)
        SculptPart.CHEST -> when {
            female && lift -> Axes(SculptSlider.BUST_LIFT, SculptSlider.BUST_SPACING)
            female -> Axes(SculptSlider.BUST_SIZE, SculptSlider.BUST_WIDTH)
            lift -> Axes(SculptSlider.SHOULDER_SLOPE, null, invertVertical = true)
            else -> Axes(SculptSlider.CHEST, SculptSlider.SHOULDERS)
        }
        SculptPart.ARMS -> if (lift) Axes(SculptSlider.ARMS, null) else Axes(SculptSlider.UPPER_ARMS, SculptSlider.FOREARMS)
        SculptPart.WAIST -> Axes(SculptSlider.BELLY, SculptSlider.WAIST)
        SculptPart.HIPS -> if (lift) Axes(SculptSlider.BUTT_LIFT, null) else Axes(SculptSlider.BUTT, SculptSlider.HIPS)
        SculptPart.LEGS -> if (lift) Axes(SculptSlider.LEG_LENGTH, null) else Axes(SculptSlider.THIGHS, SculptSlider.CALVES)
    }

    /** The value a drag of [deltaPx] (up or right positive) gives a slider that was at [start] on a stage [stagePx] tall. */
    fun valueFor(start: Float, deltaPx: Float, stagePx: Float): Float {
        if (!deltaPx.isFinite() || stagePx <= 1f) return BodySculpt.quantize(start)
        return BodySculpt.quantize(start + 2f * deltaPx / (SWEEP * stagePx))
    }

    /** The side a pick's code tells (bits 8-9 of Native.pickBodyRegion: 1 her left, 2 her right), null when it doesn't. */
    fun sideOf(code: Int): BodySide? = when ((code shr 8) and 3) {
        1 -> BodySide.LEFT
        2 -> BodySide.RIGHT
        else -> null
    }

    /** The label a drag shows by the finger: "Bust Size +35" (and "· left" with Symmetry off on one side). */
    fun label(slider: SculptSlider, value: Float, side: BodySide?): String {
        val shown = Math.round(value * 100f)
        val v = if (shown > 0) "+$shown" else "$shown"
        val where = when (side) { BodySide.LEFT -> " · left"; BodySide.RIGHT -> " · right"; null -> "" }
        return "${slider.label} $v$where"
    }
}

/**
 * One drag on her body, from the press to the lift of the finger: the sculpt it started from, the part, the axis it took
 * (after [SculptDrag.SLOP_DP]), the sculpt it makes now ([sculpt]) and the moments a phone would tick under the finger
 * ([tick]: the value crossing 0 or reaching an end). [side] is the side touched when Symmetry is off (null: both sides).
 */
class SculptDragSession(
    val start: BodySculpt, val part: SculptPart, val female: Boolean, lift: Boolean, val side: BodySide?, private val stagePx: Float,
    private val slopPx: Float, private val project: Boolean = false,
) {
    var lift: Boolean = lift
        private set
    var slider: SculptSlider? = null
        private set
    private var vertical = true
    var sculpt: BodySculpt = start
        private set
    var value: Float = 0f
        private set
    private var lastValue = 0f
    /** The value crossed 0 or reached -1 or 1 on the last [move] (a haptic tick). */
    var tick = false
        private set
    private var originX = 0f
    private var originY = 0f

    /** A second finger down (or the panel's Lift): the drag goes on as a lift from where the finger is now. */
    fun liftMode(on: Boolean, dxPx: Float, dyPx: Float) {
        if (on == lift) return
        lift = on
        slider = null
        originX = dxPx; originY = dyPx
    }

    /**
     * The finger [dxPx] right and [dyPx] down from where it pressed. Returns whether the sculpt changed. Before it has gone
     * [slopPx] nothing changes; then the larger direction's slider is the drag's for the rest of it.
     */
    fun move(dxPx: Float, dyPx: Float): Boolean {
        tick = false
        val dx = dxPx - originX
        val dy = dyPx - originY
        if (slider == null) {
            if (abs(dx) < slopPx && abs(dy) < slopPx) return false
            val axes = SculptDrag.axes(part, female, lift, project)
            vertical = abs(dy) >= abs(dx)
            // the direction the finger took; a part with no slider that way takes its other one (the finger's other direction)
            if ((if (vertical) axes.vertical else axes.horizontal) == null) vertical = !vertical
            slider = if (vertical) axes.vertical else axes.horizontal
            val s = slider ?: return false
            lastValue = startOf(s)
        }
        val s = slider ?: return false
        val axes = SculptDrag.axes(part, female, lift, project)
        val along = if (vertical) (if (axes.invertVertical && s == axes.vertical) dy else -dy) else dx
        val v = SculptDrag.valueFor(startOf(s), along, stagePx)
        val next = if (side != null && s.sided) sculpt.withSide(s, side, v) else sculpt.with(s, v)
        value = v
        tick = (lastValue < 0f && v >= 0f) || (lastValue > 0f && v <= 0f) || ((v == 1f || v == -1f) && lastValue != v)
        lastValue = v
        if (next == sculpt) return false
        sculpt = next
        return true
    }

    /** Where slider [s] was as this drag (or its lift part) began: one side's own value with Symmetry off. */
    private fun startOf(s: SculptSlider): Float = if (side != null && s.sided) start.on(s, side) else start[s]

    /** What the panel shows by the finger now (null before an axis is taken). */
    val label: String? get() = slider?.let { SculptDrag.label(it, value, if (it.sided) side else null) }
}
