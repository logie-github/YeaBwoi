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
        flickMinPx = 60f,
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
        recognizer.setViewWidth(1000f)
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
    fun tapsOnEitherHalfAreCross() {
        tap(0, 900f, 300f, 0)
        scheduler.advanceTo(1000)
        assertEquals(listOf("CROSS"), out.presses())
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
    fun tapThenHoldIsSquareOnTheLeftHalfToo() {
        tap(0, 100f, 300f, 0)
        down(0, 100f, 300f, 150)
        scheduler.advanceTo(500)
        assertEquals(setOf(VitaButton.SQUARE), out.pressed)
    }

    @Test
    fun leftSwipeMovesTheLeftStickAndHoldsTheDpad() {
        down(0, 200f, 300f, 0)
        move(0, 200f, 220f, 30)
        assertEquals(0f, out.stick(Stick.LEFT)[0], 0.001f)
        assertEquals(0.8f, out.stick(Stick.LEFT)[1], 0.001f)
        assertTrue(VitaButton.DPAD_UP in out.pressed)
        scheduler.advanceTo(1000)
        assertTrue(VitaButton.DPAD_UP in out.pressed, "The D-pad stays down while the swipe does")
        up(0, 200f, 220f, 1100)
        assertTrue(out.pressed.isEmpty())
        assertEquals(0f, out.stick(Stick.LEFT)[1])
        assertFalse(Stick.RIGHT in out.sticksMoved)
    }

    @Test
    fun leftFlickNeverPressesL1OrR1() {
        down(0, 200f, 300f, 0)
        move(0, 300f, 300f, 40)
        up(0, 300f, 300f, 60)
        scheduler.advanceTo(1000)
        assertFalse("L1" in out.presses())
        assertFalse("R1" in out.presses())
        assertTrue(Stick.LEFT in out.sticksMoved)
    }

    @Test
    fun rightFlickToTheRightIsL1() {
        down(0, 700f, 300f, 0)
        move(0, 740f, 302f, 40)
        move(0, 800f, 305f, 80)
        up(0, 800f, 305f, 100)
        scheduler.advanceTo(1000)
        assertEquals(listOf("100 L1 down", "200 L1 up"), out.events)
        assertFalse(Stick.RIGHT in out.sticksMoved, "A flick never moves the right stick")
    }

    @Test
    fun rightFlickToTheLeftIsR1() {
        down(0, 800f, 300f, 0)
        move(0, 700f, 290f, 60)
        up(0, 690f, 290f, 90)
        scheduler.advanceTo(1000)
        assertEquals(listOf("R1"), out.presses())
        assertFalse(Stick.RIGHT in out.sticksMoved)
    }

    @Test
    fun shortOrVerticalRightFlicksPressNothing() {
        down(0, 800f, 300f, 0)
        move(0, 830f, 300f, 40)
        up(0, 830f, 300f, 60)
        down(1, 800f, 300f, 1000)
        move(1, 810f, 200f, 1040)
        up(1, 810f, 200f, 1060)
        scheduler.advanceTo(3000)
        assertTrue(out.presses().isEmpty())
        assertFalse(Stick.RIGHT in out.sticksMoved)
    }

    @Test
    fun rightSwipeKeptDownMovesTheRightStick() {
        down(0, 700f, 300f, 0)
        move(0, 780f, 300f, 60)
        assertFalse(Stick.RIGHT in out.sticksMoved, "Could still be a flick")
        scheduler.advanceTo(250)
        assertEquals(0.8f, out.stick(Stick.RIGHT)[0], 0.001f)
        move(0, 700f, 360f, 400)
        assertEquals(0f, out.stick(Stick.RIGHT)[0], 0.001f)
        assertEquals(-0.6f, out.stick(Stick.RIGHT)[1], 0.001f)
        up(0, 900f, 300f, 600)
        assertEquals(0f, out.stick(Stick.RIGHT)[0])
        scheduler.advanceTo(2000)
        assertTrue(out.presses().isEmpty(), "No L1 after the stick, and no D-pad on the right")
        assertFalse(Stick.LEFT in out.sticksMoved)
    }

    @Test
    fun slowRightSwipeMovesTheStickRightAway() {
        down(0, 700f, 300f, 0)
        move(0, 710f, 300f, 200)
        move(0, 750f, 300f, 300)
        assertEquals(0.5f, out.stick(Stick.RIGHT)[0], 0.001f)
    }

    @Test
    fun bothSticksAtOnce() {
        down(0, 200f, 300f, 0)
        move(0, 150f, 300f, 30)
        down(1, 700f, 300f, 100)
        move(1, 700f, 250f, 150)
        scheduler.advanceTo(400)
        assertEquals(-0.5f, out.stick(Stick.LEFT)[0], 0.001f)
        assertEquals(0.5f, out.stick(Stick.RIGHT)[1], 0.001f)
        up(1, 700f, 250f, 500)
        assertEquals(0f, out.stick(Stick.RIGHT)[1])
        assertEquals(-0.5f, out.stick(Stick.LEFT)[0], 0.001f)
    }

    @Test
    fun secondFingerTapWhileWalkingIsCrossRightAway() {
        down(0, 200f, 300f, 0)
        move(0, 200f, 200f, 30)
        tap(1, 800f, 300f, 100)
        assertTrue(VitaButton.CROSS in out.pressed)
        scheduler.advanceTo(400)
        assertFalse(VitaButton.CROSS in out.pressed)
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
    fun holdThenDragKeepsCircleAndMovesTheStick() {
        down(0, 200f, 300f, 0)
        scheduler.advanceTo(300)
        move(0, 260f, 300f, 400)
        assertEquals(setOf(VitaButton.CIRCLE, VitaButton.DPAD_RIGHT), out.pressed)
        assertEquals(0.6f, out.stick(Stick.LEFT)[0], 0.001f)
        up(0, 260f, 300f, 600)
        assertTrue(out.pressed.isEmpty())
        assertEquals(0f, out.stick(Stick.LEFT)[0])
    }

    @Test
    fun twoFingerSwipeUpIsTriangle() {
        down(0, 400f, 500f, 0)
        down(1, 600f, 510f, 20)
        moveBoth(400f, 450f, 600f, 460f, 60)
        assertTrue(out.presses().isEmpty())
        moveBoth(405f, 400f, 598f, 410f, 100)
        assertEquals(listOf("TRIANGLE"), out.presses())
        moveBoth(405f, 300f, 598f, 310f, 140)
        up(0, 405f, 300f, 160)
        up(1, 598f, 310f, 170)
        scheduler.advanceTo(2000)
        assertEquals(listOf("TRIANGLE"), out.presses(), "Once per swipe; no Select or Cross")
        assertTrue(out.pressed.isEmpty())
        assertTrue(out.sticksMoved.isEmpty())
    }

    @Test
    fun twoFingerSwipeDownOrSidewaysIsNothing() {
        down(0, 400f, 300f, 0)
        down(1, 600f, 300f, 20)
        moveBoth(400f, 450f, 600f, 450f, 80)
        up(0, 400f, 450f, 100)
        up(1, 600f, 450f, 110)
        down(0, 400f, 300f, 1000)
        down(1, 600f, 300f, 1020)
        moveBoth(550f, 280f, 750f, 280f, 1080)
        up(0, 550f, 280f, 1100)
        up(1, 750f, 280f, 1110)
        scheduler.advanceTo(3000)
        assertTrue(out.presses().isEmpty())
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
