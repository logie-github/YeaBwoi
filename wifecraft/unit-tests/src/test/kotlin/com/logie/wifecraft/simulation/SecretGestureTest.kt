package com.logie.wifecraft.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretGestureTest {
    private val up = SwipeDirection.UP
    private fun touch(start: Long, ms: Long, dx: Float, dy: Float, travel: Float = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat(), multi: Boolean = false) =
        SecretTouch(start, start + ms, dx, dy, travel, multi)

    private fun swipe(d: SwipeDirection, start: Long, ms: Long = 300): SecretTouch = when (d) {
        SwipeDirection.UP -> touch(start, ms, 5f, -120f)
        SwipeDirection.DOWN -> touch(start, ms, -4f, 120f)
        SwipeDirection.LEFT -> touch(start, ms, -120f, 6f)
        SwipeDirection.RIGHT -> touch(start, ms, 120f, -6f)
    }
    private fun tap(start: Long) = touch(start, 90, 1f, 2f, 3f)

    /** Feeds the whole key with [gap] ms between touches; returns the last outcome. */
    private fun play(g: SecretGesture, gap: Long = 400, from: Long = 1000): Pair<SecretOutcome, Long> {
        var t = from
        var out = SecretOutcome.NONE
        for (d in SecretSpec.SEQUENCE) { out = g.feed(swipe(d, t)); t += 300 + gap }
        for (i in 0 until SecretSpec.TAPS) { out = g.feed(tap(t)); t += 90 + gap }
        return out to t
    }

    @Test fun theSequenceIsUpUpDownDownLeftRightLeftRightThenThreeTaps() {
        assertEquals(listOf(up, up, SwipeDirection.DOWN, SwipeDirection.DOWN, SwipeDirection.LEFT, SwipeDirection.RIGHT, SwipeDirection.LEFT, SwipeDirection.RIGHT), SecretSpec.SEQUENCE)
        assertEquals(3, SecretSpec.TAPS)
    }

    @Test fun theFullKeyOpensOnTheLastTapOnly() {
        val g = SecretGesture()
        var t = 0L
        for (d in SecretSpec.SEQUENCE) { assertEquals(SecretOutcome.STEP, g.feed(swipe(d, t))); t += 500 }
        assertEquals(SecretOutcome.STEP, g.feed(tap(t))); t += 300
        assertEquals(SecretOutcome.STEP, g.feed(tap(t))); t += 300
        assertEquals(SecretOutcome.OPEN, g.feed(tap(t)))
        assertEquals(0, g.progress)   // ready again
    }

    @Test fun itCanBeRepeated() {
        val g = SecretGesture()
        var (o, t) = play(g)
        assertEquals(SecretOutcome.OPEN, o)
        val again = play(g, from = t + 5000)
        assertEquals(SecretOutcome.OPEN, again.first)
    }

    @Test fun aWrongSwipeResetsAndThatSwipeMayStartTheNextTry() {
        val g = SecretGesture()
        g.feed(swipe(up, 0)); g.feed(swipe(up, 400))
        assertEquals(SecretOutcome.RESET, g.feed(swipe(up, 800)))   // a third up: the key starts over, this up is the first of a new try
        assertEquals(1, g.progress)
        assertEquals(SecretOutcome.RESET, g.feed(swipe(SwipeDirection.RIGHT, 1200)))
        assertEquals(0, g.progress)
    }

    @Test fun aWrongSwipeInTheMiddleMeansTheRestOfTheKeyDoesNotOpen() {
        val g = SecretGesture()
        var t = 0L
        val wrong = SecretSpec.SEQUENCE.toMutableList().also { it[4] = SwipeDirection.RIGHT }
        for (d in wrong) { g.feed(swipe(d, t)); t += 400 }
        var last = SecretOutcome.NONE
        repeat(3) { last = g.feed(tap(t)); t += 300 }
        assertTrue(last != SecretOutcome.OPEN)
    }

    @Test fun aSwipeMustBeLongEnoughQuickEnoughAndAlongOneAxis() {
        val spec = SecretSpec()
        assertEquals(up, SecretGesture.direction(touch(0, 300, 0f, -72f)))
        assertNull(SecretGesture.direction(touch(0, 300, 0f, -71f)))           // too short
        assertNull(SecretGesture.direction(touch(0, 901, 0f, -150f)))          // too slow
        assertEquals(up, SecretGesture.direction(touch(0, 900, 0f, -150f)))     // the limit itself
        assertNull(SecretGesture.direction(touch(0, 300, 80f, -150f)))          // diagonal: under 2:1
        assertEquals(up, SecretGesture.direction(touch(0, 300, 70f, -150f)))    // 2.14:1 is fine
        assertEquals(SwipeDirection.RIGHT, SecretGesture.direction(touch(0, 300, 150f, 60f)))
        assertEquals(SwipeDirection.LEFT, SecretGesture.direction(touch(0, 300, -150f, 60f)))
        assertEquals(SwipeDirection.DOWN, SecretGesture.direction(touch(0, 300, 0f, 90f)))
        assertNull(SecretGesture.direction(touch(0, 300, Float.NaN, 90f)))
        assertEquals(72f, spec.minSwipeDp)
    }

    @Test fun aSlowSwipeResetsTheKey() {
        val g = SecretGesture()
        g.feed(swipe(up, 0))
        assertEquals(SecretOutcome.RESET, g.feed(swipe(up, 400, ms = 1000)))
        assertEquals(0, g.progress)
    }

    @Test fun aPauseOverTwoAndAHalfSecondsResetsAndTheTouchStartsAfresh() {
        val g = SecretGesture()
        g.feed(swipe(up, 0))          // ends at 300
        assertEquals(SecretOutcome.STEP, g.feed(swipe(up, 300 + 2500)))   // exactly at the limit: still fine
        assertEquals(2, g.progress)
        val h = SecretGesture()
        h.feed(swipe(up, 0))
        h.feed(swipe(up, 300 + 2501))  // too late: this is a new try's first step
        assertEquals(1, h.progress)
        h.feed(swipe(SwipeDirection.DOWN, 6000))
        assertEquals(0, h.progress)    // (and a down is not the second step of a new try)
    }

    @Test fun twoFingersResetTheKey() {
        val g = SecretGesture()
        g.feed(swipe(up, 0)); g.feed(swipe(up, 400))
        assertEquals(SecretOutcome.RESET, g.feed(touch(800, 300, 0f, 120f, 120f, multi = true)))
        assertEquals(0, g.progress)
        assertNull(SecretGesture.direction(touch(0, 300, 0f, -150f, 150f, multi = true)))
    }

    @Test fun aCancelledTouchResetsTheKey() {
        val g = SecretGesture()
        g.feed(swipe(up, 0))
        g.feed(SecretTouch(400, 500, 0f, -100f, 100f, cancelled = true))
        assertEquals(0, g.progress)
    }

    @Test fun partialSequencesNeverOpen() {
        for (n in 0 until SecretSpec.SEQUENCE.size) {
            val g = SecretGesture()
            var t = 0L
            var out = SecretOutcome.NONE
            for (i in 0 until n) { out = g.feed(swipe(SecretSpec.SEQUENCE[i], t)); t += 400 }
            repeat(3) { out = g.feed(tap(t)); t += 300 }
            assertTrue(out != SecretOutcome.OPEN, "after $n swipes")
        }
    }

    @Test fun tapsNeedTheEightSwipesBefore() {
        val g = SecretGesture()
        var out = SecretOutcome.NONE
        repeat(3) { out = g.feed(tap(it * 300L)) }
        assertTrue(out != SecretOutcome.OPEN)
    }

    @Test fun tapsMustBeShortStillAndQuick() {
        assertTrue(SecretGesture.isTap(touch(0, 249, 0f, 0f, 11f)))
        assertFalse(SecretGesture.isTap(touch(0, 250, 0f, 0f, 2f)))     // held too long
        assertFalse(SecretGesture.isTap(touch(0, 100, 0f, 0f, 12f)))    // moved too far
        assertFalse(SecretGesture.isTap(touch(0, 100, 0f, 0f, 2f, multi = true)))
        // the gap: 700 ms from lifting to touching again is the limit
        fun run(gap: Long): SecretOutcome {
            val g = SecretGesture()
            var t = 0L
            for (d in SecretSpec.SEQUENCE) { g.feed(swipe(d, t)); t += 400 }
            g.feed(tap(t)); t += 90 + gap
            g.feed(tap(t)); t += 90 + gap
            return g.feed(tap(t))
        }
        assertEquals(SecretOutcome.OPEN, run(700))
        assertTrue(run(701) != SecretOutcome.OPEN)
    }

    @Test fun aSwipeBetweenTheTapsBreaksTheKey() {
        val g = SecretGesture()
        var t = 0L
        for (d in SecretSpec.SEQUENCE) { g.feed(swipe(d, t)); t += 400 }
        g.feed(tap(t)); t += 300
        g.feed(swipe(SwipeDirection.LEFT, t)); t += 400
        assertTrue(g.feed(tap(t)) != SecretOutcome.OPEN)
        assertTrue(g.progress < 8)
    }

    @Test fun theLongPauseBeforeTheTapsStillLetsThemCountUpToTheLimit() {
        val g = SecretGesture()
        var t = 0L
        for (d in SecretSpec.SEQUENCE) { g.feed(swipe(d, t)); t += 400 }
        val lastEnd = t - 400 + 300
        assertTrue(g.expectingTap(lastEnd + 2500))
        assertFalse(g.expectingTap(lastEnd + 2501))
    }

    // ---- raw pointer events

    private val density = 2f   // 1 dp = 2 px
    private fun detector() = SecretGestureDetector(density)

    private fun swipeEvents(d: SecretGestureDetector, id: Long, x: Float, y: Float, dxDp: Float, dyDp: Float, t: Long, ms: Long = 300): SecretOutcome {
        d.down(id, x, y, t)
        d.move(id, x + dxDp * density / 2, y + dyDp * density / 2)
        d.move(id, x + dxDp * density, y + dyDp * density)
        return d.up(id, x + dxDp * density, y + dyDp * density, t + ms)
    }

    @Test fun pixelsAreTurnedToDp() {
        val d = detector()
        assertEquals(SecretOutcome.STEP, swipeEvents(d, 1, 500f, 900f, 0f, -80f, 0))   // 160 px = 80 dp
        assertEquals(1, d.progress)
        val e = detector()
        swipeEvents(e, 1, 500f, 900f, 0f, -60f, 0)   // 120 px = 60 dp: too short
        assertEquals(0, e.progress)
    }

    @Test fun theWholeKeyThroughRawEvents() {
        val d = detector()
        var t = 0L
        var out = SecretOutcome.NONE
        val dirs = mapOf(SwipeDirection.UP to (0f to -100f), SwipeDirection.DOWN to (0f to 100f), SwipeDirection.LEFT to (-100f to 0f), SwipeDirection.RIGHT to (100f to 0f))
        for (s in SecretSpec.SEQUENCE) { val (dx, dy) = dirs.getValue(s); out = swipeEvents(d, 7, 300f, 600f, dx, dy, t); t += 600 }
        assertEquals(8, d.progress)
        repeat(3) {
            val consume = d.down(9, 100f, 100f, t)
            assertTrue(consume)   // the taps are the key's: kept from a button underneath
            out = d.up(9, 101f, 101f, t + 80); t += 250
        }
        assertEquals(SecretOutcome.OPEN, out)
    }

    @Test fun nothingIsConsumedBeforeTheTapStage() {
        val d = detector()
        assertFalse(d.down(1, 0f, 0f, 0))
        d.up(1, 0f, 0f, 50)
        var t = 1000L
        for (i in 0 until 3) { assertFalse(d.down(1, 0f, 0f, t)); d.move(1, 0f, -200f); d.up(1, 0f, -200f, t + 200); t += 500 }
    }

    @Test fun aSecondFingerResetsAndTheTouchCountsForNothing() {
        val d = detector()
        swipeEvents(d, 1, 300f, 600f, 0f, -100f, 0)
        assertEquals(1, d.progress)
        d.down(1, 300f, 600f, 500)
        d.down(2, 400f, 600f, 520)
        assertEquals(0, d.progress)
        d.move(1, 300f, 300f)
        assertEquals(SecretOutcome.NONE, d.up(1, 300f, 300f, 700))
        assertEquals(SecretOutcome.NONE, d.up(2, 400f, 300f, 710))
        assertEquals(0, d.progress)
        // the next touch is read afresh
        assertEquals(SecretOutcome.STEP, swipeEvents(d, 3, 300f, 600f, 0f, -100f, 2000))
    }

    @Test fun aCancelLosesTheKeyButNotTheDetector() {
        val d = detector()
        swipeEvents(d, 1, 300f, 600f, 0f, -100f, 0)
        d.down(1, 0f, 0f, 400)
        d.cancel()
        assertEquals(0, d.progress)
        assertEquals(SecretOutcome.STEP, swipeEvents(d, 2, 300f, 600f, 0f, -100f, 800))
    }

    @Test fun aSwipeWanderingThenReturningIsATapOnlyIfItStayedPut() {
        val d = detector()
        for (s in SecretSpec.SEQUENCE) {
            val (dx, dy) = when (s) { SwipeDirection.UP -> 0f to -100f; SwipeDirection.DOWN -> 0f to 100f; SwipeDirection.LEFT -> -100f to 0f; else -> 100f to 0f }
            swipeEvents(d, 1, 300f, 600f, dx, dy, d.progress * 500L)
        }
        d.down(1, 300f, 600f, 6000)
        d.move(1, 300f, 700f)          // 50 dp away
        d.move(1, 300f, 600f)          // and back
        d.up(1, 300f, 600f, 6100)
        assertEquals(0, d.progress)    // not a tap
    }
}
