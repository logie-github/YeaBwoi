package com.logie.wifecraft.graphics

// Pure Kotlin (no Android): the adaptive quality governor and the frame pacing's arithmetic. Fed once a frame on the GL
// thread (graphics/Graphics.kt); the unit tests feed it synthetic frame-time traces.

/**
 * Drops effects before frames. Each frame it is given the frame's interval (vsync to vsync as the GL thread saw it) and,
 * when known, its work (the larger of the render's CPU time and its GPU time from the timer queries). Every [Config.evalS]
 * it takes the 95th percentile of the last [Config.window] frames (half a second at 60 fps), and acts only on what lasts:
 *
 *  - over budget (the interval's p95 past [Config.overInterval] x budget: frames missed; or the work's past
 *    [Config.overWork] x budget) for [Config.downAfterS] seconds: one step down ([level] + 1, QualityStep's order), then
 *    nothing for [Config.cooldownS] while the new level settles;
 *  - headroom (the work's p95 under [Config.underWork] x budget, no frame missed) for [upAfter] seconds: one step back up.
 *    A step up that has to be taken back within [Config.probeS] doubles [upAfter] (up to [Config.upMaxS]): hysteresis, so a
 *    level that doesn't hold isn't tried every few seconds. Without GPU times (no timer queries) a step up waits three times
 *    as long and needs the interval alone to be clean.
 *
 * [thermalFloor] (the phone's thermal status: 1 moderate, 2 severe) keeps that many steps down whatever the frames say.
 */
class Governor(private val config: Config = Config()) {
    data class Config(
        val window: Int = 30,
        val evalS: Double = 0.25,
        val overInterval: Float = 1.25f,
        val overWork: Float = 0.97f,
        val underWork: Float = 0.70f,
        val downAfterS: Double = 1.5,
        val cooldownS: Double = 2.0,
        val upAfterS: Double = 8.0,
        val upMaxS: Double = 64.0,
        val probeS: Double = 6.0,
        val minSamples: Int = 20,
    )

    /** Steps down now (0: everything as set). */
    var level = 0
        private set
    /** How many steps there are to take (the settings' applicable steps). */
    var maxLevel = 0
        set(v) { field = v.coerceAtLeast(0); if (level > field) level = field }
    /** The thermal status's steps (0..2), kept down whatever the frames say. */
    var thermalFloor = 0
    /** Seconds of headroom a step up waits for now (grows when steps up don't hold). */
    var upAfter = config.upAfterS
        private set
    /** The last evaluation's percentiles (ms; NaN before there are enough frames): for the overlay. */
    var p95Interval = Float.NaN
        private set
    var p95Work = Float.NaN
        private set

    private val intervals = FloatArray(config.window)
    private val works = FloatArray(config.window)
    private var count = 0
    private var head = 0
    private var workCount = 0
    private var lastEval = Double.NaN
    private var overSince = Double.NaN
    private var underSince = Double.NaN
    private var lastChange = Double.NEGATIVE_INFINITY
    private var lastUp = Double.NEGATIVE_INFINITY

    fun reset() {
        level = 0
        upAfter = config.upAfterS
        clearWindow()
        overSince = Double.NaN; underSince = Double.NaN
        lastChange = Double.NEGATIVE_INFINITY; lastUp = Double.NEGATIVE_INFINITY
        lastEval = Double.NaN
    }

    private fun clearWindow() { count = 0; head = 0; workCount = 0; p95Interval = Float.NaN; p95Work = Float.NaN }

    /**
     * One frame at [nowS] seconds: its [intervalMs], its [workMs] (null: not known), the frame [budgetMs] (1000 / target).
     * True when [level] changed.
     */
    fun frame(nowS: Double, intervalMs: Float, workMs: Float?, budgetMs: Float): Boolean {
        val before = level
        if (intervalMs.isFinite() && intervalMs > 0f && intervalMs < 1000f) {
            intervals[head] = intervalMs
            works[head] = if (workMs != null && workMs.isFinite() && workMs >= 0f) workMs else Float.NaN
            head = (head + 1) % config.window
            if (count < config.window) count++
        }
        val floor = thermalFloor.coerceIn(0, maxLevel)
        if (level < floor) { level = floor; lastChange = nowS; clearWindow() }
        if (lastEval.isNaN() || nowS - lastEval >= config.evalS) {
            lastEval = nowS
            evaluate(nowS, budgetMs, floor)
        }
        return level != before
    }

    private fun evaluate(now: Double, budget: Float, floor: Int) {
        if (count < config.minSamples) return
        p95Interval = percentile95(intervals, count)
        val w = FloatArray(count)
        var n = 0
        for (i in 0 until count) if (!works[i].isNaN()) w[n++] = works[i]
        p95Work = if (n >= config.minSamples) percentile95(w, n) else Float.NaN
        val over = p95Interval > budget * config.overInterval || (!p95Work.isNaN() && p95Work > budget * config.overWork)
        val under = if (!p95Work.isNaN()) p95Work < budget * config.underWork && p95Interval <= budget * 1.1f
            else p95Interval <= budget * 1.05f
        val upWait = if (p95Work.isNaN()) upAfter * 3 else upAfter
        if (over) {
            underSince = Double.NaN
            if (overSince.isNaN()) overSince = now
            if (now - overSince >= config.downAfterS && now - lastChange >= config.cooldownS && level < maxLevel) {
                if (now - lastUp < config.probeS) upAfter = minOf(upAfter * 2, config.upMaxS)
                level++
                lastChange = now
                overSince = Double.NaN
                clearWindow()
            }
        } else {
            overSince = Double.NaN
            if (under && level > floor) {
                if (underSince.isNaN()) underSince = now
                if (now - underSince >= upWait && now - lastChange >= config.cooldownS) {
                    level--
                    lastChange = now
                    lastUp = now
                    underSince = Double.NaN
                    clearWindow()
                }
            } else underSince = Double.NaN
        }
    }

    companion object {
        /** The 95th percentile of the first [n] values (nearest rank). */
        fun percentile95(values: FloatArray, n: Int): Float {
            if (n <= 0) return Float.NaN
            val sorted = values.copyOf(n).also { it.sort() }
            val rank = kotlin.math.ceil(0.95 * n).toInt().coerceIn(1, n)
            return sorted[rank - 1]
        }
    }
}

/** The frame pacing's arithmetic (graphics/Graphics.kt's Choreographer pacer). */
object FramePacing {
    /** Draw on every n-th vsync of a [refreshHz] screen to come closest to [targetFps] without going over (1: every one). */
    fun divisor(refreshHz: Float, targetFps: Int): Int {
        if (!(refreshHz > 0f) || targetFps <= 0) return 1
        var n = kotlin.math.round(refreshHz / targetFps).toInt().coerceAtLeast(1)
        // (a screen a little under the target's multiple, 119.9 Hz for 60, still draws every second vsync)
        while (n > 1 && refreshHz / n < targetFps * 0.9f) n--
        return n
    }

    /** The frame's budget in ms: the paced rate's (never faster than the screen). */
    fun budgetMs(refreshHz: Float, targetFps: Int): Float {
        val hz = if (refreshHz > 0f) refreshHz / divisor(refreshHz, targetFps) else targetFps.toFloat()
        return 1000f / hz.coerceAtLeast(1f)
    }

    /** The thermal status (PowerManager.THERMAL_STATUS_*: 0 none .. 6 shutdown) and headroom (NaN: unknown) as steps. */
    fun thermalSteps(status: Int, headroom: Float): Int = when {
        status >= 3 -> 2                                    // severe and worse
        status == 2 -> 1                                    // moderate
        headroom.isFinite() && headroom >= 0.95f -> 1       // about to throttle
        else -> 0
    }
}
