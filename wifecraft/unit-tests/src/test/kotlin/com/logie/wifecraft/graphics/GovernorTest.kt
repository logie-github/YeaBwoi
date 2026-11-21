package com.logie.wifecraft.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The governor fed synthetic frame-time traces at 60 fps (budget 16.67 ms). */
class GovernorTest {
    private val budget = 1000f / 60f

    /** Runs [seconds] of frames at 60 a second from [start]: each frame's interval and work from [frame]. */
    private fun Governor.run(start: Double, seconds: Double, frame: (Int) -> Pair<Float, Float?>): Double {
        val n = (seconds * 60).toInt()
        for (i in 0 until n) {
            val (interval, work) = frame(i)
            frame(start + i / 60.0, interval, work, budget)
        }
        return start + n / 60.0
    }

    @Test fun steadyFramesWithHeadroomStayPut() {
        val g = Governor().apply { maxLevel = 5 }
        g.run(0.0, 30.0) { budget to 9f }
        assertEquals(0, g.level)
    }

    @Test fun missedFramesStepDownOneAtATimeWithACooldown() {
        val g = Governor().apply { maxLevel = 5 }
        // every other frame takes two vsyncs: the p95 interval is 33 ms, the work 20 ms
        var t = g.run(0.0, 1.0) { (if (it % 2 == 0) 33.3f else budget) to 20f }
        assertEquals(0, g.level, "not within the first 1.5 s")
        t = g.run(t, 1.5) { (if (it % 2 == 0) 33.3f else budget) to 20f }
        assertEquals(1, g.level)
        // still over: the next step only after the window refills, the 1.5 s over budget and the 2 s cooldown
        t = g.run(t, 1.0) { 33.3f to 20f }
        assertEquals(1, g.level)
        t = g.run(t, 2.5) { 33.3f to 20f }
        assertEquals(2, g.level)
        // never below the last step
        g.run(t, 60.0) { 33.3f to 30f }
        assertEquals(5, g.level)
    }

    @Test fun aShortSpikeDoesNotStepDown() {
        val g = Governor().apply { maxLevel = 5 }
        var t = g.run(0.0, 5.0) { budget to 9f }
        t = g.run(t, 0.5) { 40f to 35f }       // half a second of hitches (a skin composed, a file loaded)
        g.run(t, 5.0) { budget to 9f }
        assertEquals(0, g.level)
    }

    @Test fun headroomStepsBackUpSlowlyAndBacksOffWhenItDoesNotHold() {
        val g = Governor().apply { maxLevel = 5 }
        var t = g.run(0.0, 3.0) { 33.3f to 22f }
        assertEquals(1, g.level)
        // light work for 6 s: not yet (8 s of headroom wanted)
        t = g.run(t, 6.0) { budget to 8f }
        assertEquals(1, g.level)
        t = g.run(t, 4.0) { budget to 8f }
        assertEquals(0, g.level)
        val wait = g.upAfter
        // the level up doesn't hold: down again within the probe window, and the next try waits twice as long
        t = g.run(t, 2.0) { 33.3f to 22f }
        assertEquals(1, g.level)
        assertEquals(wait * 2, g.upAfter)
        t = g.run(t, 10.0) { budget to 8f }
        assertEquals(1, g.level, "16 s of headroom wanted now")
        g.run(t, 8.0) { budget to 8f }
        assertEquals(0, g.level)
    }

    @Test fun workNearTheBudgetHoldsTheLevel() {
        val g = Governor().apply { maxLevel = 5 }
        var t = g.run(0.0, 3.0) { 33.3f to 22f }
        assertEquals(1, g.level)
        // 14 ms of work in a 16.7 ms frame: no frame missed, but not 30% spare either: stays where it is
        g.run(t, 40.0) { budget to 14f }
        assertEquals(1, g.level)
    }

    @Test fun withoutGpuTimesTheIntervalAloneDecidesAndStepsUpTakeLonger() {
        val g = Governor().apply { maxLevel = 3 }
        var t = g.run(0.0, 3.0) { 33.3f to null }
        assertEquals(1, g.level)
        t = g.run(t, 20.0) { budget to null }
        assertEquals(1, g.level, "three times the wait without the work's time")
        g.run(t, 6.0) { budget to null }
        assertEquals(0, g.level)
    }

    @Test fun thermalStatusKeepsStepsDown() {
        val g = Governor().apply { maxLevel = 5; thermalFloor = 2 }
        g.frame(0.0, budget, 5f, budget)
        assertEquals(2, g.level)
        g.run(0.1, 60.0) { budget to 5f }
        assertEquals(2, g.level, "never back up past the floor")
        g.thermalFloor = 0
        g.run(60.2, 10.0) { budget to 5f }
        assertTrue(g.level < 2)
        assertEquals(1, FramePacing.thermalSteps(2, Float.NaN))
        assertEquals(2, FramePacing.thermalSteps(4, 0.5f))
        assertEquals(1, FramePacing.thermalSteps(0, 0.97f))
        assertEquals(0, FramePacing.thermalSteps(1, 0.6f))
    }

    @Test fun fewerStepsThanTheLevelClampIt() {
        val g = Governor().apply { maxLevel = 5 }
        g.run(0.0, 30.0) { 40f to 35f }
        assertTrue(g.level >= 4)
        g.maxLevel = 2
        assertEquals(2, g.level)
    }

    @Test fun percentileAndPacing() {
        val v = FloatArray(100) { it + 1f }
        assertEquals(95f, Governor.percentile95(v, 100))
        assertEquals(3f, Governor.percentile95(floatArrayOf(3f, 1f, 2f), 3))
        assertEquals(2, FramePacing.divisor(120f, 60))
        assertEquals(1, FramePacing.divisor(120f, 120))
        assertEquals(1, FramePacing.divisor(60f, 120))
        assertEquals(1, FramePacing.divisor(120f, 90))
        assertEquals(2, FramePacing.divisor(119.88f, 60))
        assertEquals(4, FramePacing.divisor(240f, 60))
        assertEquals(1000f / 60f, FramePacing.budgetMs(120f, 60), 0.01f)
        assertEquals(1000f / 60f, FramePacing.budgetMs(60f, 120), 0.01f)
        assertEquals(1000f / 120f, FramePacing.budgetMs(120f, 90), 0.01f)
    }
}
