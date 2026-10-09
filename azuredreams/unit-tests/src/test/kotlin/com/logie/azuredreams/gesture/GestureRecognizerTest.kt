package com.logie.azuredreams.gesture

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val pressed = HashSet<PsButton>()
        var menuOpened = 0

        override fun setButton(button: PsButton, pressed: Boolean) {
            if (pressed) this.pressed.add(button) else this.pressed.remove(button)
            events.add("${scheduler.now} $button ${if (pressed) "down" else "up"}")
        }

        override fun openMenu() {
            menuOpened++
            events.add("${scheduler.now} menu")
        }

        /** The buttons that were pressed at some point, in order. */
        fun presses() = events.filter { it.endsWith(" down") }.map { it.split(" ")[1] }
    }

    private val config = GestureConfig(
        touchSlopPx = 20f,
        dpadRadiusPx = 60f,
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
        recognizer.width = 1000f
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

    private val dpad = setOf(PsButton.DPAD_UP, PsButton.DPAD_DOWN, PsButton.DPAD_LEFT, PsButton.DPAD_RIGHT)

    private fun heldDpad() = out.pressed.intersect(dpad)

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
        scheduler.advanceTo(280)
        assertEquals(setOf(PsButton.CIRCLE), out.pressed)
        up(0, 200f, 300f, 2000)
        assertTrue(out.pressed.isEmpty())
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
    fun tapThenHoldIsR1OnTheRightAndL1OnTheLeft() {
        tap(0, 800f, 300f, 0)
        down(0, 800f, 300f, 150)
        scheduler.advanceTo(430)
        assertEquals(setOf(PsButton.R1), out.pressed)
        scheduler.advanceTo(1500)
        assertEquals(setOf(PsButton.R1), out.pressed, "Held while the finger stays down")
        up(0, 800f, 300f, 1600)
        assertTrue(out.pressed.isEmpty())

        tap(0, 200f, 300f, 3000)
        down(0, 200f, 300f, 3150)
        scheduler.advanceTo(3430)
        assertEquals(setOf(PsButton.L1), out.pressed)
        up(0, 200f, 300f, 3600)
        scheduler.advanceTo(5000)
        assertEquals(listOf("R1", "L1"), out.presses())
    }

    @Test
    fun swipeHoldsTheDpadInItsDirection() {
        down(0, 500f, 500f, 0)
        move(0, 500f, 470f, 30)
        assertEquals(setOf(PsButton.DPAD_UP), heldDpad())
        scheduler.advanceTo(2000)
        assertEquals(setOf(PsButton.DPAD_UP), heldDpad(), "Held while the finger stays down")
        up(0, 500f, 470f, 2100)
        assertTrue(out.pressed.isEmpty())
    }

    @Test
    fun diagonalSwipesHoldTwoDirections() {
        val expected = mapOf(
            (40f to -40f) to setOf(PsButton.DPAD_UP, PsButton.DPAD_RIGHT),
            (-40f to -40f) to setOf(PsButton.DPAD_UP, PsButton.DPAD_LEFT),
            (-40f to 40f) to setOf(PsButton.DPAD_DOWN, PsButton.DPAD_LEFT),
            (40f to 40f) to setOf(PsButton.DPAD_DOWN, PsButton.DPAD_RIGHT),
            (40f to 0f) to setOf(PsButton.DPAD_RIGHT),
            (0f to 40f) to setOf(PsButton.DPAD_DOWN),
            (-40f to 0f) to setOf(PsButton.DPAD_LEFT),
        )
        var t = 0L
        for ((offset, buttons) in expected) {
            down(0, 500f, 500f, t)
            move(0, 500f + offset.first, 500f + offset.second, t + 30)
            assertEquals(buttons, heldDpad(), "Swipe by $offset")
            up(0, 500f + offset.first, 500f + offset.second, t + 300)
            assertTrue(out.pressed.isEmpty())
            t += 1000
        }
    }

    @Test
    fun turningAroundRespondsRightAway() {
        down(0, 500f, 500f, 0)
        move(0, 600f, 500f, 30)
        assertEquals(setOf(PsButton.DPAD_RIGHT), heldDpad())
        // The start point was dragged to 60 px behind the finger (x 540); 30 px back turns left.
        move(0, 570f, 500f, 60)
        assertEquals(setOf(PsButton.DPAD_RIGHT), heldDpad(), "Still right of the start point")
        move(0, 510f, 500f, 400)
        assertEquals(setOf(PsButton.DPAD_LEFT), heldDpad())
    }

    @Test
    fun aWobblyFingerKeepsItsDirection() {
        down(0, 500f, 500f, 0)
        move(0, 500f, 440f, 30)
        assertEquals(setOf(PsButton.DPAD_UP), heldDpad())
        // 26° off straight up: past the edge of up's slice, but within the margin.
        move(0, 500f + 60f * 0.44f, 500f - 60f * 0.9f, 60)
        assertEquals(setOf(PsButton.DPAD_UP), heldDpad())
    }

    @Test
    fun backToTheStartPointLetsGo() {
        down(0, 500f, 500f, 0)
        move(0, 500f, 460f, 30)
        assertEquals(setOf(PsButton.DPAD_UP), heldDpad())
        move(0, 500f, 495f, 400)
        assertTrue(heldDpad().isEmpty())
        move(0, 530f, 495f, 500)
        assertEquals(setOf(PsButton.DPAD_RIGHT), heldDpad())
    }

    @Test
    fun quickFlickStillReachesTheGame() {
        down(0, 500f, 500f, 0)
        move(0, 500f, 470f, 10)
        up(0, 500f, 470f, 20)
        assertEquals(setOf(PsButton.DPAD_UP), out.pressed, "Kept down a moment")
        scheduler.advanceTo(200)
        assertTrue(out.pressed.isEmpty())
    }

    @Test
    fun holdThenDragIsCircleAndWalking() {
        down(0, 200f, 300f, 0)
        scheduler.advanceTo(300)
        move(0, 260f, 300f, 400)
        assertEquals(setOf(PsButton.CIRCLE, PsButton.DPAD_RIGHT), out.pressed)
        up(0, 260f, 300f, 600)
        assertTrue(out.pressed.isEmpty())
    }

    @Test
    fun holdAndSwipeWithAnotherFingerRuns() {
        down(0, 200f, 300f, 0)
        scheduler.advanceTo(400)
        assertEquals(setOf(PsButton.CIRCLE), out.pressed)
        down(1, 600f, 300f, 500)
        move(1, 600f, 250f, 540)
        assertEquals(setOf(PsButton.CIRCLE, PsButton.DPAD_UP), out.pressed)
        up(1, 600f, 250f, 700)
        assertEquals(setOf(PsButton.CIRCLE), out.pressed, "Still holding Circle")
        up(0, 200f, 300f, 800)
        assertTrue(out.pressed.isEmpty())
    }

    @Test
    fun quickHoldAndSwipeRunsToo() {
        // Both fingers land before the hold time: one stays still, the other swipes.
        down(0, 200f, 300f, 0)
        down(1, 600f, 300f, 60)
        move(1, 640f, 300f, 100)
        move(1, 680f, 302f, 140)
        assertEquals(setOf(PsButton.CIRCLE, PsButton.DPAD_RIGHT), out.pressed)
        up(1, 680f, 302f, 1000)
        up(0, 200f, 300f, 1050)
        scheduler.advanceTo(3000)
        assertTrue(out.pressed.isEmpty())
        assertEquals(listOf("CIRCLE", "DPAD_RIGHT"), out.presses(), "No Select or L1")
    }

    @Test
    fun secondFingerTapWhileWalkingIsCross() {
        down(0, 200f, 300f, 0)
        move(0, 200f, 250f, 30)
        tap(1, 800f, 300f, 100)
        assertTrue(PsButton.CROSS in out.pressed)
        assertEquals(setOf(PsButton.DPAD_UP), heldDpad())
    }

    @Test
    fun secondFingerSwipeWhileWalkingIsIgnored() {
        down(0, 200f, 300f, 0)
        move(0, 200f, 250f, 30)
        down(1, 800f, 300f, 100)
        move(1, 900f, 300f, 130)
        assertEquals(setOf(PsButton.DPAD_UP), out.pressed)
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
    fun twoFingerSwipesAreTriangleL1R1AndSquare() {
        twoFingerSwipe(0f, -200f, 0)
        twoFingerSwipe(200f, 0f, 2000)
        twoFingerSwipe(-200f, 10f, 4000)
        twoFingerSwipe(0f, 200f, 6000)
        assertEquals(listOf("TRIANGLE", "L1", "R1", "SQUARE"), out.presses())
        assertTrue(out.pressed.isEmpty())
    }

    @Test
    fun twoFingerHoldIsSelectUntilTheFingersLift() {
        down(0, 400f, 300f, 0)
        down(1, 600f, 300f, 30)
        scheduler.advanceTo(400)
        assertEquals(setOf(PsButton.SELECT), out.pressed)
        up(0, 400f, 300f, 900)
        assertEquals(setOf(PsButton.SELECT), out.pressed)
        up(1, 600f, 300f, 950)
        assertTrue(out.pressed.isEmpty())
        assertEquals(0, out.menuOpened)
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
        up(0, 400f, 300f, 900)
        up(1, 600f, 300f, 950)
        scheduler.advanceTo(3000)
        assertTrue(out.presses().isEmpty(), "No Select or Cross around the menu")
    }

    @Test
    fun cancelAllLetsGoOfEverything() {
        down(0, 200f, 300f, 0)
        move(0, 200f, 250f, 30)
        down(1, 800f, 300f, 100)
        scheduler.advanceTo(500)
        assertEquals(setOf(PsButton.DPAD_UP, PsButton.CIRCLE), out.pressed)
        recognizer.cancelAll()
        assertTrue(out.pressed.isEmpty())
        scheduler.advanceTo(5000)
        assertTrue(out.pressed.isEmpty())
    }

    @Test
    fun staleTouchesAreDropped() {
        down(0, 200f, 300f, 0)
        scheduler.advanceTo(400)
        assertEquals(setOf(PsButton.CIRCLE), out.pressed)
        recognizer.dropStaleTouches()
        assertTrue(out.pressed.isEmpty())
        tap(0, 200f, 300f, 1000)
        scheduler.advanceTo(2000)
        assertEquals(listOf("CIRCLE", "CROSS"), out.presses())
    }
}
