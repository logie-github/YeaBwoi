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
        var fastForwarding = false
        val fastForwardEvents = ArrayList<String>()
        val stateEvents = ArrayList<String>()

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

        override fun setFastForward(enabled: Boolean) {
            assertTrue(enabled != fastForwarding, "Fast-forward must only report changes")
            fastForwarding = enabled
            fastForwardEvents.add("${scheduler.now} ${if (enabled) "on" else "off"}")
        }

        override fun quickSave() {
            stateEvents.add("${scheduler.now} save")
        }

        override fun quickLoad() {
            stateEvents.add("${scheduler.now} load")
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
        assertTrue(
            out.buttonEvents().all { "DPAD" in it },
            "A swipe must not press any button besides the D-pad"
        )
    }

    @Test
    fun swipingUpOrDownAlsoHoldsTheDpad() {
        down(0, 500f, 300f, 0)
        move(0, 500f, 240f, 30) // up
        assertEquals(setOf(PadButton.DPAD_UP), out.pressed)
        assertEquals(0.6f, out.stickY, 0.001f)
        scheduler.advanceTo(1000)
        assertEquals(setOf(PadButton.DPAD_UP), out.pressed, "Held for as long as the swipe is")

        move(0, 500f, 400f, 1100) // back down past where the finger landed
        assertEquals(setOf(PadButton.DPAD_DOWN), out.pressed)
        assertEquals(-1f, out.stickY, 0.001f)

        up(0, 500f, 400f, 1500)
        assertTrue(out.pressed.isEmpty())
        assertEquals(
            listOf("30 DPAD_UP down", "1100 DPAD_UP up", "1100 DPAD_DOWN down", "1500 DPAD_DOWN up"),
            out.buttonEvents()
        )
    }

    @Test
    fun swipingSidewaysHoldsTheDpadLeftOrRight() {
        down(0, 500f, 300f, 0)
        move(0, 440f, 300f, 30) // left
        assertEquals(setOf(PadButton.DPAD_LEFT), out.pressed)
        assertEquals(-0.6f, out.stickX, 0.001f)
        scheduler.advanceTo(1000)
        assertEquals(setOf(PadButton.DPAD_LEFT), out.pressed, "Held for as long as the swipe is")

        move(0, 600f, 300f, 1100) // back across to the right
        assertEquals(setOf(PadButton.DPAD_RIGHT), out.pressed)
        assertEquals(1f, out.stickX, 0.001f)

        up(0, 600f, 300f, 1500)
        assertTrue(out.pressed.isEmpty())
        assertEquals(
            listOf("30 DPAD_LEFT down", "1100 DPAD_LEFT up", "1100 DPAD_RIGHT down", "1500 DPAD_RIGHT up"),
            out.buttonEvents()
        )
    }

    @Test
    fun diagonalSwipesPressTheCloserDirection() {
        down(0, 500f, 300f, 0)
        move(0, 570f, 245f, 30) // up-right, but more right than up
        assertEquals(setOf(PadButton.DPAD_RIGHT), out.pressed)
        up(0, 570f, 245f, 300)

        down(0, 500f, 300f, 1000)
        move(0, 455f, 370f, 1030) // down-left, but more down than left
        assertEquals(setOf(PadButton.DPAD_DOWN), out.pressed)
        up(0, 455f, 370f, 1300)
        assertTrue(out.pressed.isEmpty())
    }

    @Test
    fun shortSwipesDontPressTheDpad() {
        down(0, 500f, 300f, 0)
        move(0, 500f, 340f, 30) // down, but only 40% of the way
        assertEquals(-0.4f, out.stickY, 0.001f)
        up(0, 500f, 340f, 60)

        down(0, 500f, 300f, 1000)
        move(0, 460f, 300f, 1030) // left, 40%
        assertEquals(-0.4f, out.stickX, 0.001f)
        up(0, 460f, 300f, 1060)

        scheduler.advanceTo(2000)
        assertTrue(out.buttonEvents().isEmpty())
    }

    @Test
    fun wobblyUpSwipeKeepsTheDpadSteady() {
        down(0, 500f, 300f, 0)
        move(0, 500f, 220f, 30)
        assertEquals(setOf(PadButton.DPAD_UP), out.pressed)
        move(0, 575f, 235f, 300) // drifts to about 49° off vertical
        assertEquals(setOf(PadButton.DPAD_UP), out.pressed)
        move(0, 500f, 265f, 400) // eases off to 35%
        assertEquals(setOf(PadButton.DPAD_UP), out.pressed)
        move(0, 500f, 280f, 500) // nearly back to center
        assertTrue(out.pressed.isEmpty())
        assertEquals(listOf("30 DPAD_UP down", "500 DPAD_UP up"), out.buttonEvents())
    }

    @Test
    fun turningFromUpToSidewaysSwitchesToRight() {
        down(0, 500f, 300f, 0)
        move(0, 500f, 220f, 30) // up
        move(0, 590f, 250f, 300) // turns about 61° to the right
        assertEquals(setOf(PadButton.DPAD_RIGHT), out.pressed)
        move(0, 572f, 229f, 400) // back to an even diagonal: stays Right instead of flipping to Up
        assertEquals(setOf(PadButton.DPAD_RIGHT), out.pressed)
        up(0, 572f, 229f, 500)
        assertEquals(
            listOf("30 DPAD_UP down", "300 DPAD_UP up", "300 DPAD_RIGHT down", "500 DPAD_RIGHT up"),
            out.buttonEvents()
        )
    }

    @Test
    fun quickFlickStillPressesTheDpadLongEnough() {
        down(0, 500f, 300f, 0)
        move(0, 500f, 360f, 10)
        up(0, 500f, 360f, 20)
        scheduler.advanceTo(1000)
        assertEquals(listOf("10 DPAD_DOWN down", "110 DPAD_DOWN up"), out.buttonEvents())
    }

    @Test
    fun flickingBackNeverPressesUpAndDownTogether() {
        down(0, 500f, 300f, 0)
        move(0, 500f, 240f, 10) // up
        move(0, 500f, 400f, 30) // straight back down, while Up's flick press is still running
        assertEquals(setOf(PadButton.DPAD_DOWN), out.pressed)
        up(0, 500f, 400f, 300)
        scheduler.advanceTo(1000)
        assertEquals(
            listOf("10 DPAD_UP down", "30 DPAD_UP up", "30 DPAD_DOWN down", "300 DPAD_DOWN up"),
            out.buttonEvents()
        )
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
        assertEquals(setOf(PadButton.A, PadButton.DPAD_UP), out.pressed)
        assertEquals(1f, out.stickY, 0.001f)
        up(0, 500f, 200f, 400)
        scheduler.advanceTo(1000)
        assertEquals(
            listOf("180 A down", "180 DPAD_UP down", "280 A up", "400 DPAD_UP up"),
            out.buttonEvents()
        )
    }

    @Test
    fun holdThenDragKeepsBAndMovesStick() {
        down(0, 500f, 300f, 0)
        scheduler.advanceTo(300)
        move(0, 600f, 300f, 400)
        assertEquals(setOf(PadButton.B, PadButton.DPAD_RIGHT), out.pressed)
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
        assertEquals(setOf(PadButton.A, PadButton.DPAD_UP), out.pressed)
        assertEquals(1f, out.stickY, 0.001f)
        scheduler.advanceTo(400)
        assertEquals(setOf(PadButton.DPAD_UP), out.pressed)
        assertEquals(1f, out.stickY, 0.001f, "Stick must stay pushed while the other finger taps")
    }

    @Test
    fun secondFingerHoldWhileWalkingPressesB() {
        down(0, 300f, 300f, 0)
        move(0, 300f, 200f, 30)
        down(1, 800f, 300f, 100)
        scheduler.advanceTo(500)
        assertEquals(setOf(PadButton.B, PadButton.DPAD_UP), out.pressed)
        up(1, 800f, 300f, 600)
        assertEquals(setOf(PadButton.DPAD_UP), out.pressed)
        assertTrue(out.fastForwardEvents.isEmpty())
    }

    /** Tap with one finger, then put two fingers down and keep them there. */
    private fun startFastForward(t: Long) {
        tap(0, 500f, 300f, t)
        down(0, 400f, 300f, t + 150)
        down(1, 600f, 300f, t + 170)
    }

    @Test
    fun tapThenTwoFingerHoldFastForwards() {
        startFastForward(0)
        scheduler.advanceTo(449)
        assertTrue(!out.fastForwarding)
        scheduler.advanceTo(450)
        assertTrue(out.fastForwarding)
        scheduler.advanceTo(3000)
        assertTrue(out.fastForwarding, "Lasts for as long as the fingers are held")
        up(0, 400f, 300f, 3000)
        assertTrue(out.fastForwarding, "Until the last finger lifts")
        up(1, 600f, 300f, 3010)
        assertTrue(!out.fastForwarding)
        scheduler.advanceTo(4000)
        assertEquals(listOf("450 on", "3010 off"), out.fastForwardEvents)
        assertTrue(out.buttonEvents().isEmpty(), "The tap before it doesn't press A")
        assertEquals(0, out.menuOpened)
    }

    @Test
    fun tapThenQuickTwoFingerTapDoesNothing() {
        startFastForward(0)
        up(0, 400f, 300f, 250)
        up(1, 600f, 300f, 260)
        scheduler.advanceTo(2000)
        assertTrue(out.fastForwardEvents.isEmpty())
        assertTrue(out.buttonEvents().isEmpty())
        assertEquals(0, out.menuOpened)
    }

    @Test
    fun movingBeforeTheHoldCancelsFastForward() {
        startFastForward(0)
        move(0, 400f, 200f, 200)
        scheduler.advanceTo(1000)
        up(0, 400f, 200f, 1000)
        up(1, 600f, 300f, 1000)
        assertTrue(out.fastForwardEvents.isEmpty())
    }

    @Test
    fun fastForwardFingersDontMoveTheStick() {
        startFastForward(0)
        scheduler.advanceTo(500)
        move(0, 400f, 100f, 600)
        assertEquals(0f, out.stickY)
        assertTrue(out.fastForwarding, "Moving after the hold started doesn't stop it")
        up(0, 400f, 100f, 700)
        up(1, 600f, 300f, 700)
        assertEquals(listOf("450 on", "700 off"), out.fastForwardEvents)
        assertTrue(out.pressed.isEmpty())
    }

    @Test
    fun aThirdFingerDuringFastForwardIsIgnored() {
        startFastForward(0)
        scheduler.advanceTo(500)
        tap(2, 800f, 300f, 600)
        scheduler.advanceTo(1500)
        assertTrue(out.fastForwarding)
        assertTrue(out.stateEvents.isEmpty(), "Not a three-finger quick save")
        up(0, 400f, 300f, 1500)
        up(1, 600f, 300f, 1500)
        assertEquals(listOf("450 on", "1500 off"), out.fastForwardEvents)
    }

    @Test
    fun aSingleHoldIsStillBNotFastForward() {
        down(0, 500f, 300f, 0)
        scheduler.advanceTo(1000)
        assertEquals(setOf(PadButton.B), out.pressed)
        assertTrue(out.fastForwardEvents.isEmpty())
    }

    @Test
    fun cancelAndStaleTouchesStopFastForward() {
        startFastForward(0)
        scheduler.advanceTo(500)
        recognizer.cancelAll()
        assertTrue(!out.fastForwarding)

        startFastForward(1000)
        scheduler.advanceTo(1500)
        assertTrue(out.fastForwarding)
        recognizer.dropStaleTouches()
        assertTrue(!out.fastForwarding)
        assertEquals(listOf("450 on", "500 off", "1450 on", "1500 off"), out.fastForwardEvents)
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
    fun cancelReleasesTheDpad() {
        down(0, 500f, 300f, 0)
        move(0, 500f, 200f, 30)
        assertEquals(setOf(PadButton.DPAD_UP), out.pressed)
        recognizer.cancelAll()
        assertTrue(out.pressed.isEmpty())
        scheduler.advanceTo(2000)
        assertEquals(listOf("30 DPAD_UP down", "30 DPAD_UP up"), out.buttonEvents())
    }

    @Test
    fun cancelReleasesEverything() {
        down(0, 500f, 300f, 0)
        scheduler.advanceTo(300)
        move(0, 600f, 300f, 350)
        assertEquals(setOf(PadButton.B, PadButton.DPAD_RIGHT), out.pressed)
        recognizer.cancelAll()
        assertTrue(out.pressed.isEmpty())
        assertEquals(0f, out.stickX)
        scheduler.advanceTo(2000)
        assertTrue(out.pressed.isEmpty())
    }

    @Test
    fun staleTouchesAreReleasedWithoutLosingAPendingTap() {
        // A hold whose up event never arrives...
        down(0, 500f, 300f, 0)
        move(0, 600f, 300f, 400)
        assertEquals(setOf(PadButton.B, PadButton.DPAD_RIGHT), out.pressed)
        // ...is dropped when a new first finger lands.
        scheduler.advanceTo(600)
        recognizer.dropStaleTouches()
        assertTrue(out.pressed.isEmpty())
        assertEquals(0f, out.stickX)

        // A tap waiting for its double tap partner survives.
        tap(1, 500f, 300f, 1000)
        scheduler.advanceTo(1100)
        recognizer.dropStaleTouches()
        tap(2, 500f, 300f, 1150)
        scheduler.advanceTo(2000)
        assertEquals(
            listOf("280 B down", "1210 START down", "1310 START up"),
            out.buttonEvents().filter { !it.contains("B up") && "DPAD" !in it }
        )
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

    private fun threeFingerDown(t: Long) {
        down(0, 300f, 300f, t)
        down(1, 500f, 300f, t + 15)
        down(2, 700f, 300f, t + 30)
    }

    private fun threeFingerUp(t: Long) {
        up(0, 300f, 300f, t)
        up(1, 500f, 300f, t + 10)
        up(2, 700f, 300f, t + 20)
    }

    @Test
    fun threeFingerTapQuickSaves() {
        threeFingerDown(0)
        threeFingerUp(150)
        scheduler.advanceTo(2000)
        assertEquals(listOf("170 save"), out.stateEvents)
        assertTrue(out.buttonEvents().isEmpty(), "No buttons, no menu")
        assertEquals(0, out.menuOpened)
    }

    @Test
    fun threeFingerHoldQuickLoads() {
        threeFingerDown(0)
        scheduler.advanceTo(529)
        assertTrue(out.stateEvents.isEmpty())
        scheduler.advanceTo(530)
        assertEquals(listOf("530 load"), out.stateEvents)
        threeFingerUp(900)
        scheduler.advanceTo(2000)
        assertEquals(listOf("530 load"), out.stateEvents, "Letting go after a hold doesn't also save")
        assertTrue(out.buttonEvents().isEmpty())
    }

    @Test
    fun threeFingerSwipeDoesNothing() {
        threeFingerDown(0)
        move(0, 300f, 200f, 100)
        move(1, 500f, 200f, 100)
        move(2, 700f, 200f, 100)
        scheduler.advanceTo(1000)
        threeFingerUp(1000)
        scheduler.advanceTo(2000)
        assertTrue(out.stateEvents.isEmpty())
        assertEquals(0f, out.stickY)
    }

    @Test
    fun liftingOneFingerEarlyCancelsTheHold() {
        threeFingerDown(0)
        up(2, 700f, 300f, 200)
        scheduler.advanceTo(2000)
        up(0, 300f, 300f, 2000)
        up(1, 500f, 300f, 2000)
        assertTrue(out.stateEvents.isEmpty(), "Neither a tap (too long) nor a hold (a finger left)")
    }

    @Test
    fun threeFingerTapThenTwoFingerHoldDoesNotOpenTheMenu() {
        threeFingerDown(0)
        threeFingerUp(100)
        down(0, 400f, 300f, 200)
        down(1, 600f, 300f, 210)
        scheduler.advanceTo(1500)
        assertEquals(0, out.menuOpened)
        assertEquals(listOf("120 save"), out.stateEvents)
    }

    @Test
    fun twoFingerMenuGestureStillWorksAfterThreeFingerGestures() {
        threeFingerDown(0)
        threeFingerUp(100)
        scheduler.advanceTo(1000)
        // Two-finger tap, then two-finger tap and hold.
        down(0, 400f, 300f, 1000)
        down(1, 600f, 300f, 1010)
        up(0, 400f, 300f, 1100)
        up(1, 600f, 300f, 1110)
        down(0, 400f, 300f, 1250)
        down(1, 600f, 300f, 1260)
        scheduler.advanceTo(1600)
        assertEquals(1, out.menuOpened)
    }

    @Test
    fun aFourthFingerIsIgnored() {
        threeFingerDown(0)
        down(3, 900f, 300f, 40)
        up(3, 900f, 300f, 80)
        threeFingerUp(150)
        scheduler.advanceTo(2000)
        assertEquals(listOf("170 save"), out.stateEvents)
        assertTrue(out.buttonEvents().isEmpty())
    }

    @Test
    fun cancelStopsAPendingQuickLoad() {
        threeFingerDown(0)
        recognizer.cancelAll()
        scheduler.advanceTo(2000)
        assertTrue(out.stateEvents.isEmpty())
    }
}
