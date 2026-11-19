package com.logie.wifecraft.simulation

/**
 * The live scene's clock: the simulation moves in fixed steps of [step] seconds whatever the screen's rate, so a 60 Hz and
 * a 120 Hz phone play it at the same speed and the same way. Each drawn frame [advance]s it by the time since the last
 * (never negative, at most [maxFrame] for one slow frame), runs the whole steps due, and says how far into the next step
 * the frame is ([alpha], 0..1: the drawing interpolates between the last two steps, so motion is smooth at any rate).
 * A gap longer than [pauseGap] (the app was in the background, the screen off, the GL thread paused) counts as one step:
 * nobody jumps ahead on resume. [pause] forgets the last time so the next frame starts afresh. Not thread-safe: one thread
 * (the GL thread) drives it.
 */
class FixedStep(
    val step: Float = 1f / 120f,
    private val maxFrame: Float = 0.1f,
    private val pauseGap: Float = 0.25f,
    /** Steps run in one frame at most (a very slow frame drops the rest rather than spiralling). */
    private val maxSteps: Int = 12,
) {
    private var lastNanos = Long.MIN_VALUE
    private var accumulator = 0f

    /** How far the frame is into the step after the last one run (0..1). */
    var alpha = 0f
        private set

    /** Steps run since this clock started (tests, and the screen's notion of "something moved"). */
    var steps = 0L
        private set

    /** The time since the last frame, as this clock counted it (0 on the first frame and after [pause]). */
    var frameSeconds = 0f
        private set

    /** The frame at [nowNanos]: runs [update] once per whole step due (with [step]); returns how many ran. */
    inline fun advance(nowNanos: Long, update: (Float) -> Unit): Int {
        val due = begin(nowNanos)
        for (i in 0 until due) update(step)
        return due
    }

    /** [advance]'s bookkeeping: the steps due now (the caller runs them). */
    fun begin(nowNanos: Long): Int {
        var seconds = if (lastNanos == Long.MIN_VALUE) 0f else (nowNanos - lastNanos) / 1e9f
        lastNanos = nowNanos
        if (!seconds.isFinite() || seconds < 0f) seconds = 0f       // a clock that went back: no time passed
        if (seconds > pauseGap) seconds = step                       // back from a pause: one step, not a jump
        seconds = seconds.coerceAtMost(maxFrame)
        frameSeconds = seconds
        accumulator += seconds
        var due = (accumulator / step).toInt()
        if (due > maxSteps) { due = maxSteps; accumulator = due * step }
        accumulator -= due * step
        if (accumulator < 0f) accumulator = 0f
        steps += due
        alpha = (accumulator / step).coerceIn(0f, 1f)
        return due
    }

    /** The next frame starts afresh (no time counted across the gap). */
    fun pause() {
        lastNanos = Long.MIN_VALUE
    }
}
