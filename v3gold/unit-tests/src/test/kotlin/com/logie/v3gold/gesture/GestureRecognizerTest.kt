package com.logie.v3gold.gesture

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GestureRecognizerTest {
    private class FakeScheduler : GestureScheduler {
        private class Task(val time: Long, val seq: Int, val action: () -> Unit) {
            var cancelled = false
        }

        var now = 0L
            private set
        private var seq = 0
        private val tasks = ArrayList<Task>()

        override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
            val task = Task(now + delayMs, seq++, action)
            tasks.add(task)
            return Cancellable { task.cancelled = true }
        }

        fun advanceTo(time: Long) {
            while (true) {
                val next = tasks.filter { !it.cancelled && it.time <= time }
                    .minWithOrNull(compareBy<Task>({ it.time }, { it.seq })) ?: break
                tasks.remove(next)
                now = next.time
                next.action()
            }
            tasks.removeAll { it.cancelled }
            now = time
        }
    }

    /** Records every change of controller state with the time it happened. */
    private class Recorder(private val scheduler: FakeScheduler) : GestureOutput {
        val events = ArrayList<String>()
        val pressed = HashSet<VitaButton>()
        val sticks = Array(Stick.entries.size) { floatArrayOf(0f, 0f) }
        /** Every stick that was ever pushed away from the center. */
        val sticksMoved = HashSet<Stick>()
        var menuOpened = 0

        override fun setButton(button: VitaButton, pressed: Boolean) {
            if (pressed) this.pressed.add(button) else this.pressed.remove(button)
            events.add("${scheduler.now} $button ${if (pressed) "down" else "up"}")
        }

        override fun setStick(stick: Stick, x: Float, y: Float) {
            sticks[stick.ordinal][0] = x
            sticks[stick.ordinal][1] = y
            if (x != 0f || y != 0f) sticksMoved.add(stick)
        }

        override fun openMenu() {
            menuOpened++
            events.add("${scheduler.now} menu")
        }

        fun stick(stick: Stick) = sticks[stick.ordinal]

        /** The buttons that were pressed at some point, in order. */
        fun presses() = events.filter { it.endsWith(" down") }.map { it.split(" ")[1] }
    }

    private val config = GestureConfig(
        touchSlopPx = 20f,
        stickRadiusPx = 100f,
        twoFingerSwipePx = 80f,
    )
    private lateinit var scheduler: FakeScheduler
    private lateinit var out: Recorder
    private lateinit var recognizer: GestureRecognizer

    @BeforeTest
    fun setUp() {
        scheduler = FakeScheduler()
        out = Recorder(scheduler)
        recognizer = GestureRecognizer(config, scheduler, out)
    }

    private fun down(id: Int, x: Float, y: Float, t: Long) {
        scheduler.advanceTo(t)
        recognizer.pointerDown(id, x, y, t)
    }

    private fun move(id: Int, x: Float, y: Float, t: Long) {
        scheduler.advanceTo(t)
        recognizer.pointerMove(id, x, y, t)
        recognizer.movesFinished()
    }

    /** Two fingers moving in the same touch event. */
    private fun moveBoth(x0: Float, y0: Float, x1: Float, y1: Float, t: Long) {
        scheduler.advanceTo(t)
        recognizer.pointerMove(0, x0, y0, t)
        recognizer.pointerMove(1, x1, y1, t)
        recognizer.movesFinished()
    }

    private fun up(id: Int, x: Float, y: Float, t: Long) {
        scheduler.advanceTo(t)
        recognizer.pointerUp(id, x, y, t)
    }

    private fun tap(id: Int, x: Float, y: Float, t: Long) {
        down(id, x, y, t)
        up(id, x, y, t + 50)
    }

    @Test
    fun tapIsCrossOnceTheDoubleTapWindowEnds() {
        tap(0, 200f, 300f, 0)
        scheduler.advanceTo(200)
        assertTrue(out.events.isEmpty(), "Cross waits for the double tap window")
        scheduler.advanceTo(1000)
        assertEquals(listOf("300 CROSS down", "400 CROSS up"), out.events)
    }

    @Test
    fun holdIsCircleUntilTheFingerLifts() {
        down(0, 200f, 300f, 0)
        scheduler.advanceTo(279)
        assertTrue(out.pressed.isEmpty())
        scheduler.advanceTo(280)
        assertEquals(setOf(VitaButton.CIRCLE), out.pressed)
        up(0, 200f, 300f, 2000)
        assertTrue(out.pressed.isEmpty())
        scheduler.advanceTo(5000)
        assertEquals(listOf("CIRCLE"), out.presses())
    }

    @Test
    fun doubleTapIsStartOnly() {
        tap(0, 200f, 300f, 0)
        tap(0, 205f, 305f, 150)
        scheduler.advanceTo(2000)
        assertEquals(listOf("START"), out.presses())
    }

    @Test
    fun tapThenHoldIsSquare() {
        tap(0, 600f, 300f, 0)
        down(0, 600f, 300f, 150)
        scheduler.advanceTo(430)
        assertEquals(setOf(VitaButton.SQUARE), out.pressed)
        up(0, 600f, 300f, 1000)
        scheduler.advanceTo(2000)
        assertEquals(listOf("SQUARE"), out.presses())
    }

    @Test
    fun swipeAnywhereMovesTheLeftStickAndHoldsTheDpad() {
        for ((id, x) in listOf(0 to 100f, 1 to 900f)) {
            val t = id * 2000L
            down(id, x, 300f, t)
            move(id, x, 220f, t + 30)
            assertEquals(0.8f, out.stick(Stick.LEFT)[1], 0.001f)
            assertTrue(VitaButton.DPAD_UP in out.pressed)
            scheduler.advanceTo(t + 1000)
            assertTrue(VitaButton.DPAD_UP in out.pressed, "The D-pad stays down while the swipe does")
            up(id, x, 220f, t + 1100)
            assertTrue(out.pressed.isEmpty())
            assertEquals(0f, out.stick(Stick.LEFT)[1])
        }
        assertFalse(Stick.RIGHT in out.sticksMoved)
    }

    @Test
    fun holdThenSwipeWithAnotherFingerTurnsTheCamera() {
        down(0, 200f, 300f, 0)
        scheduler.advanceTo(400)
        assertEquals(setOf(VitaButton.CIRCLE), out.pressed)
        down(1, 600f, 300f, 500)
        move(1, 660f, 300f, 540)
        assertEquals(0.6f, out.stick(Stick.RIGHT)[0], 0.001f)
        assertTrue(out.pressed.isEmpty(), "The held finger lets go of Circle once it anchors the camera")
        move(1, 560f, 300f, 600)
        assertEquals(-0.4f, out.stick(Stick.RIGHT)[0], 0.001f)
        up(1, 560f, 300f, 700)
        assertEquals(0f, out.stick(Stick.RIGHT)[0])
        // Swiping again while still holding turns the camera again.
        down(1, 600f, 300f, 800)
        move(1, 600f, 360f, 840)
        assertEquals(-0.6f, out.stick(Stick.RIGHT)[1], 0.001f)
        up(1, 600f, 360f, 900)
        up(0, 200f, 300f, 1000)
        scheduler.advanceTo(3000)
        assertFalse(Stick.LEFT in out.sticksMoved)
        assertTrue(out.pressed.isEmpty())
        assertEquals(listOf("CIRCLE"), out.presses(), "No Cross, Select or swipe buttons")
    }

    @Test
    fun quickHoldAndSwipeTurnsTheCameraToo() {
        // Both fingers land before the hold time: one stays still, the other swipes.
        down(0, 200f, 300f, 0)
        down(1, 600f, 300f, 60)
        move(1, 640f, 300f, 100)
        assertFalse(Stick.RIGHT in out.sticksMoved, "Too early to tell from a two-finger swipe")
        move(1, 680f, 302f, 140)
        assertEquals(0.8f, out.stick(Stick.RIGHT)[0], 0.001f)
        scheduler.advanceTo(1000)
        up(1, 680f, 302f, 1000)
        up(0, 200f, 300f, 1050)
        scheduler.advanceTo(3000)
        assertTrue(out.presses().isEmpty(), "No Select, Circle or L1")
        assertEquals(0f, out.stick(Stick.RIGHT)[0])
        assertFalse(Stick.LEFT in out.sticksMoved)
    }

    @Test
    fun walkAndTurnTheCameraAtOnce() {
        down(0, 200f, 300f, 0)
        move(0, 150f, 300f, 30)
        down(1, 700f, 300f, 100)
        move(1, 700f, 250f, 150)
        assertEquals(-0.5f, out.stick(Stick.LEFT)[0], 0.001f)
        assertEquals(0.5f, out.stick(Stick.RIGHT)[1], 0.001f)
        up(1, 700f, 250f, 500)
        assertEquals(0f, out.stick(Stick.RIGHT)[1])
        assertEquals(-0.5f, out.stick(Stick.LEFT)[0], 0.001f, "Still walking")
    }

    @Test
    fun secondFingerTapWhileWalkingIsCrossRightAway() {
        down(0, 200f, 300f, 0)
        move(0, 200f, 200f, 30)
        tap(1, 800f, 300f, 100)
        assertTrue(VitaButton.CROSS in out.pressed)
        scheduler.advanceTo(400)
        assertFalse(VitaButton.CROSS in out.pressed)
        assertFalse(Stick.RIGHT in out.sticksMoved)
    }

    @Test
    fun secondFingerHoldWhileWalkingIsCircle() {
        down(0, 200f, 300f, 0)
        move(0, 200f, 200f, 30)
        down(1, 800f, 300f, 100)
        scheduler.advanceTo(400)
        assertTrue(VitaButton.CIRCLE in out.pressed)
        up(1, 800f, 300f, 800)
        assertFalse(VitaButton.CIRCLE in out.pressed)
        assertTrue(VitaButton.DPAD_UP in out.pressed, "Still walking")
    }

    @Test
    fun holdThenDragKeepsCircleAndWalks() {
        down(0, 200f, 300f, 0)
        scheduler.advanceTo(300)
        move(0, 260f, 300f, 400)
        assertEquals(setOf(VitaButton.CIRCLE, VitaButton.DPAD_RIGHT), out.pressed)
        assertEquals(0.6f, out.stick(Stick.LEFT)[0], 0.001f)
        up(0, 260f, 300f, 600)
        assertTrue(out.pressed.isEmpty())
        assertEquals(0f, out.stick(Stick.LEFT)[0])
    }

    private fun twoFingerSwipe(dx: Float, dy: Float, t: Long) {
        down(0, 400f, 500f, t)
        down(1, 600f, 510f, t + 20)
        for (step in 1..4) {
            moveBoth(400f + dx * step / 4, 500f + dy * step / 4, 600f + dx * step / 4, 510f + dy * step / 4, t + 20 + step * 25L)
        }
        up(0, 400f + dx, 500f + dy, t + 140)
        up(1, 600f + dx, 510f + dy, t + 150)
        scheduler.advanceTo(t + 1000)
    }

    @Test
    fun twoFingerSwipeUpIsTriangle() {
        twoFingerSwipe(0f, -200f, 0)
        assertEquals(listOf("TRIANGLE"), out.presses(), "Once per swipe; no Select or Cross")
        assertTrue(out.pressed.isEmpty())
        assertTrue(out.sticksMoved.isEmpty())
    }

    @Test
    fun twoFingerSwipeRightIsL1AndLeftIsR1() {
        twoFingerSwipe(200f, 0f, 0)
        twoFingerSwipe(-200f, 10f, 2000)
        assertEquals(listOf("L1", "R1"), out.presses())
        assertTrue(out.sticksMoved.isEmpty())
    }

    @Test
    fun twoFingerSwipeDownIsNothing() {
        twoFingerSwipe(0f, 200f, 0)
        assertTrue(out.presses().isEmpty())
        assertTrue(out.sticksMoved.isEmpty())
    }

    @Test
    fun twoFingerHoldIsSelectUntilTheFingersLift() {
        down(0, 400f, 300f, 0)
        down(1, 600f, 300f, 30)
        scheduler.advanceTo(400)
        assertEquals(setOf(VitaButton.SELECT), out.pressed)
        up(0, 400f, 300f, 900)
        assertEquals(setOf(VitaButton.SELECT), out.pressed)
        up(1, 600f, 300f, 950)
        assertTrue(out.pressed.isEmpty())
        scheduler.advanceTo(2000)
        assertEquals(listOf("SELECT"), out.presses())
        assertEquals(0, out.menuOpened)
    }

    @Test
    fun twoFingerTapIsNothing() {
        down(0, 400f, 300f, 0)
        down(1, 600f, 300f, 20)
        up(0, 400f, 300f, 100)
        up(1, 600f, 300f, 110)
        scheduler.advanceTo(3000)
        assertTrue(out.events.isEmpty())
    }

    @Test
    fun twoFingerTapThenTwoFingerHoldOpensTheMenu() {
        down(0, 400f, 300f, 0)
        down(1, 600f, 300f, 20)
        up(0, 400f, 300f, 100)
        up(1, 600f, 300f, 110)
        down(0, 400f, 300f, 300)
        down(1, 600f, 300f, 320)
        scheduler.advanceTo(700)
        assertEquals(1, out.menuOpened)
        assertTrue(out.pressed.isEmpty())
        up(0, 400f, 300f, 900)
        up(1, 600f, 300f, 950)
        scheduler.advanceTo(3000)
        assertTrue(out.presses().isEmpty(), "No Select or Cross around the menu")
    }

    @Test
    fun liftingOneOfTwoFingersEarlyIsNotSelect() {
        down(0, 400f, 300f, 0)
        down(1, 600f, 300f, 20)
        up(0, 400f, 300f, 150)
        scheduler.advanceTo(1000)
        up(1, 600f, 300f, 1000)
        scheduler.advanceTo(3000)
        assertTrue(out.presses().isEmpty())
    }

    @Test
    fun tapThenTwoFingersStillCountsTheTap() {
        tap(0, 200f, 300f, 0)
        down(0, 400f, 300f, 150)
        down(1, 600f, 300f, 160)
        assertEquals(listOf("CROSS"), out.presses())
    }

    @Test
    fun cancelAllLetsGoOfEverything() {
        down(0, 200f, 300f, 0)
        move(0, 200f, 200f, 30)
        down(1, 800f, 300f, 100)
        scheduler.advanceTo(500)
        assertEquals(setOf(VitaButton.DPAD_UP, VitaButton.CIRCLE), out.pressed)
        recognizer.cancelAll()
        assertTrue(out.pressed.isEmpty())
        assertEquals(0f, out.stick(Stick.LEFT)[1])
        scheduler.advanceTo(5000)
        assertTrue(out.pressed.isEmpty())
    }

    @Test
    fun staleTouchesAreDropped() {
        down(0, 200f, 300f, 0)
        scheduler.advanceTo(400)
        assertEquals(setOf(VitaButton.CIRCLE), out.pressed)
        // The up event never arrived; the next gesture starts.
        recognizer.dropStaleTouches()
        assertTrue(out.pressed.isEmpty())
        tap(0, 200f, 300f, 1000)
        scheduler.advanceTo(2000)
        assertEquals(listOf("CIRCLE", "CROSS"), out.presses())
    }
}
