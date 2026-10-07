package com.logie.dolphinxd.gesture

import kotlin.math.abs
import kotlin.math.hypot
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
        val pressed = HashSet<PadButton>()
        var stickX = 0f
        var stickY = 0f
        var menuOpened = 0

        override fun setButton(button: PadButton, pressed: Boolean) {
            if (pressed) this.pressed.add(button) else this.pressed.remove(button)
            events.add("${scheduler.now} $button ${if (pressed) "down" else "up"}")
        }

        override fun setStick(x: Float, y: Float) {
            stickX = x
            stickY = y
        }

        override fun openMenu() {
            menuOpened++
            events.add("${scheduler.now} menu")
        }

        fun buttonEvents() = events.filter { !it.endsWith("menu") }
    }

    private val config = GestureConfig(touchSlopPx = 20f, stickRadiusPx = 100f)
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
    }

    private fun up(id: Int, x: Float, y: Float, t: Long) {
        scheduler.advanceTo(t)
        recognizer.pointerUp(id, x, y, t)
    }

    private fun tap(id: Int, x: Float, y: Float, t: Long, duration: Long = 60) {
        down(id, x, y, t)
        up(id, x, y, t + duration)
    }

    @Test
    fun singleTapPressesAAfterDoubleTapWindow() {
        tap(0, 500f, 300f, 0)
        scheduler.advanceTo(200)
        assertTrue(out.events.isEmpty(), "A must wait for the double tap window")
        scheduler.advanceTo(1000)
        assertEquals(listOf("310 A down", "410 A up"), out.buttonEvents())
    }

    @Test
    fun holdPressesBUntilRelease() {
        down(0, 500f, 300f, 0)
        scheduler.advanceTo(279)
        assertTrue(out.pressed.isEmpty())
        scheduler.advanceTo(280)
        assertEquals(setOf(PadButton.B), out.pressed)
        scheduler.advanceTo(2000)
        assertEquals(setOf(PadButton.B), out.pressed)
        up(0, 500f, 300f, 2000)
        scheduler.advanceTo(3000)
        assertEquals(listOf("280 B down", "2000 B up"), out.buttonEvents())
    }

    @Test
    fun doubleTapPressesStartOnly() {
        tap(0, 500f, 300f, 0)
        tap(0, 510f, 305f, 150)
        scheduler.advanceTo(1000)
        assertEquals(listOf("210 START down", "310 START up"), out.buttonEvents())
    }

    @Test
    fun tapTapHoldLeftHalfIsL() {
        tap(0, 200f, 300f, 0)
        down(0, 200f, 300f, 150)
        scheduler.advanceTo(1000)
        assertEquals(setOf(PadButton.L), out.pressed)
        up(0, 200f, 300f, 1000)
        scheduler.advanceTo(2000)
        assertEquals(listOf("430 L down", "1000 L up"), out.buttonEvents())
    }

    @Test
    fun tapTapHoldRightHalfIsX() {
        tap(0, 800f, 300f, 0)
        down(0, 800f, 300f, 150)
        scheduler.advanceTo(1000)
        assertEquals(setOf(PadButton.X), out.pressed)
        up(0, 800f, 300f, 1000)
        scheduler.advanceTo(2000)
        assertTrue(out.pressed.isEmpty())
        assertEquals(listOf("430 X down", "1000 X up"), out.buttonEvents())
    }

    @Test
    fun slowSecondTapIsTwoSeparateAs() {
        tap(0, 500f, 300f, 0)
        tap(0, 500f, 300f, 600)
        scheduler.advanceTo(2000)
        assertEquals(
            listOf("310 A down", "410 A up", "910 A down", "1010 A up"),
            out.buttonEvents()
        )
    }

    @Test
    fun swipeMovesStickInEveryDirection() {
        down(0, 500f, 300f, 0)
        move(0, 500f, 250f, 20) // up 50px
        assertEquals(0f, out.stickX, 0.001f)
        assertEquals(0.5f, out.stickY, 0.001f)

        move(0, 600f, 300f, 40) // right, full tilt
        assertEquals(1f, out.stickX, 0.001f)
        assertEquals(0f, out.stickY, 0.001f)

        move(0, 300f, 300f, 60) // left, beyond the radius: origin follows the finger
        assertEquals(-1f, out.stickX, 0.001f)

        move(0, 300f, 500f, 80) // down-ish diagonal
        assertTrue(out.stickY < -0.5f && out.stickX < 0f)
        assertTrue(hypot(out.stickX, out.stickY) <= 1.0001f)

        up(0, 300f, 500f, 100)
        assertEquals(0f, out.stickX)
        assertEquals(0f, out.stickY)
        scheduler.advanceTo(1000)
        assertTrue(out.buttonEvents().isEmpty(), "A swipe must not press any button")
    }

    @Test
    fun diagonalSwipeGivesDiagonalStick() {
        down(0, 500f, 300f, 0)
        move(0, 560f, 240f, 30)
        assertTrue(abs(out.stickX - out.stickY) < 0.001f)
        assertEquals(0.6f, out.stickX, 0.001f)

        move(0, 600f, 200f, 60)
        assertTrue(abs(out.stickX - out.stickY) < 0.001f)
        assertEquals(0.7071f, out.stickX, 0.001f)
    }

    @Test
    fun smallJitterIsStillATap() {
        down(0, 500f, 300f, 0)
        move(0, 510f, 305f, 30)
        up(0, 510f, 305f, 60)
        scheduler.advanceTo(1000)
        assertEquals(listOf("310 A down", "410 A up"), out.buttonEvents())
        assertEquals(0f, out.stickX)
    }

    @Test
    fun tapThenSwipeIsAThenStick() {
        tap(0, 500f, 300f, 0)
        down(0, 500f, 300f, 150)
        move(0, 500f, 200f, 180)
        assertEquals(setOf(PadButton.A), out.pressed)
        assertEquals(1f, out.stickY, 0.001f)
        up(0, 500f, 200f, 400)
        scheduler.advanceTo(1000)
        assertEquals(listOf("180 A down", "280 A up"), out.buttonEvents())
    }

    @Test
    fun holdThenDragKeepsBAndMovesStick() {
        down(0, 500f, 300f, 0)
        scheduler.advanceTo(300)
        move(0, 600f, 300f, 400)
        assertEquals(setOf(PadButton.B), out.pressed)
        assertEquals(1f, out.stickX, 0.001f)
        up(0, 600f, 300f, 500)
        assertTrue(out.pressed.isEmpty())
        assertEquals(0f, out.stickX)
    }

    @Test
    fun secondFingerTapWhileWalkingPressesAImmediately() {
        down(0, 300f, 300f, 0)
        move(0, 300f, 200f, 30)
        tap(1, 800f, 300f, 100)
        assertEquals(setOf(PadButton.A), out.pressed)
        assertEquals(1f, out.stickY, 0.001f)
        scheduler.advanceTo(400)
        assertTrue(out.pressed.isEmpty())
        assertEquals(1f, out.stickY, 0.001f, "Stick must stay pushed while the other finger taps")
    }

    @Test
    fun secondFingerHoldWhileWalkingPressesB() {
        down(0, 300f, 300f, 0)
        move(0, 300f, 200f, 30)
        down(1, 800f, 300f, 100)
        scheduler.advanceTo(500)
        assertEquals(setOf(PadButton.B), out.pressed)
        up(1, 800f, 300f, 600)
        assertTrue(out.pressed.isEmpty())
    }

    @Test
    fun twoFingerTapTapHoldOpensMenu() {
        // First two-finger tap
        down(0, 400f, 300f, 0)
        down(1, 600f, 300f, 30)
        up(0, 400f, 300f, 90)
        up(1, 600f, 300f, 100)
        // Second two-finger touch, held
        down(0, 400f, 300f, 220)
        down(1, 600f, 300f, 250)
        scheduler.advanceTo(520)
        assertEquals(0, out.menuOpened)
        scheduler.advanceTo(530)
        assertEquals(1, out.menuOpened)
        up(0, 400f, 300f, 900)
        up(1, 600f, 300f, 910)
        scheduler.advanceTo(2000)
        assertEquals(1, out.menuOpened)
        assertTrue(out.buttonEvents().isEmpty(), "Two-finger gestures must not press buttons")
    }

    @Test
    fun singleTwoFingerHoldDoesNothing() {
        down(0, 400f, 300f, 0)
        down(1, 600f, 300f, 30)
        scheduler.advanceTo(2000)
        up(0, 400f, 300f, 2000)
        up(1, 600f, 300f, 2010)
        scheduler.advanceTo(3000)
        assertEquals(0, out.menuOpened)
        assertTrue(out.buttonEvents().isEmpty())
    }

    @Test
    fun twoFingerDoubleTapWithoutHoldDoesNothing() {
        down(0, 400f, 300f, 0)
        down(1, 600f, 300f, 20)
        up(0, 400f, 300f, 80)
        up(1, 600f, 300f, 90)
        down(0, 400f, 300f, 200)
        down(1, 600f, 300f, 210)
        up(0, 400f, 300f, 260)
        up(1, 600f, 300f, 270)
        scheduler.advanceTo(2000)
        assertEquals(0, out.menuOpened)
        assertTrue(out.buttonEvents().isEmpty())
    }

    @Test
    fun cancelReleasesEverything() {
        down(0, 500f, 300f, 0)
        scheduler.advanceTo(300)
        move(0, 600f, 300f, 350)
        assertEquals(setOf(PadButton.B), out.pressed)
        recognizer.cancelAll()
        assertTrue(out.pressed.isEmpty())
        assertEquals(0f, out.stickX)
        scheduler.advanceTo(2000)
        assertTrue(out.pressed.isEmpty())
    }

    @Test
    fun rapidTapsAlternateStartAndA() {
        // Mashing: tap, tap (Start), tap (new single tap -> A)
        tap(0, 500f, 300f, 0)
        tap(0, 500f, 300f, 150)
        tap(0, 500f, 300f, 300)
        scheduler.advanceTo(2000)
        assertEquals(
            listOf("210 START down", "310 START up", "610 A down", "710 A up"),
            out.buttonEvents()
        )
    }
}
