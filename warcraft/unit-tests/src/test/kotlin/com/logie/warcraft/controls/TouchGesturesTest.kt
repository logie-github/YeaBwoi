package com.logie.warcraft.controls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TouchGesturesTest {
    /** What the gestures did, as text (the stick's drawing apart). */
    private class Log : GestureOutput {
        val events = mutableListOf<String>()
        val stick = mutableListOf<String>()
        override fun pointerTo(x: Float, y: Float) { events += "to ${x.toInt()},${y.toInt()}" }
        override fun pointerBy(dx: Float, dy: Float) { events += "by ${dx.toInt()},${dy.toInt()}" }
        override fun press(button: Button) { events += "press $button" }
        override fun release(button: Button) { events += "release $button" }
        override fun click(button: Button) { events += "click $button" }
        override fun keyDown(key: Key) { events += "down $key" }
        override fun keyUp(key: Key) { events += "up $key" }
        override fun tap(key: Key) { events += "tap $key" }
        override fun stick(baseX: Float, baseY: Float, knobX: Float, knobY: Float) {
            stick += "${baseX.toInt()},${baseY.toInt()} ${knobX.toInt()},${knobY.toInt()}"
        }
        override fun stickGone() { stick += "gone" }
        override fun playing() { events += "playing" }
        override fun openChat() { events += "chat" }
        override fun showKeyboard() { events += "keyboard" }
        override fun showMenu() { events += "menu" }
    }

    private val log = Log()
    // Density 1: the distances below are in dp. A 1000 x 600 screen: the left half is x < 500.
    private val gestures = TouchGestures(log, density = 1f).apply { setSize(1000f, 600f) }

    @Test
    fun tapClicksWhereTheFingerIsOnEitherHalf() {
        gestures.down(0, 100f, 200f, 0)
        gestures.up(0, 102f, 201f, 80)
        gestures.down(0, 800f, 500f, 1000)
        gestures.up(0, 800f, 500f, 1080)
        assertEquals(listOf("to 102,201", "click LEFT", "to 800,500", "click LEFT"), log.events)
    }

    @Test
    fun doubleTapJumps() {
        gestures.down(0, 700f, 200f, 0)
        gestures.up(0, 700f, 200f, 80)
        gestures.down(0, 710f, 205f, 200)
        gestures.up(0, 710f, 205f, 260)
        assertEquals(listOf("to 700,200", "click LEFT", "tap SPACE"), log.events)
    }

    @Test
    fun holdOpensTheBagsAndTapThenHoldTheMap() {
        gestures.down(0, 100f, 200f, 0)
        gestures.onHold(TouchGestures.HOLD_MS)
        gestures.up(0, 100f, 200f, 900)
        gestures.down(0, 600f, 300f, 2000)
        gestures.up(0, 600f, 300f, 2080)
        gestures.down(0, 600f, 300f, 2200)
        gestures.onHold(2200 + TouchGestures.HOLD_MS)
        gestures.up(0, 600f, 300f, 2900)
        assertEquals(listOf("tap B", "to 600,300", "click LEFT", "tap M"), log.events)
    }

    @Test
    fun leftSwipeWalksWithSidewaysSteps() {
        gestures.down(0, 200f, 400f, 0)
        gestures.move(0, 200f, 370f) // up
        gestures.move(0, 170f, 370f) // up and left
        gestures.move(0, 160f, 400f) // left
        gestures.move(0, 240f, 400f) // right
        gestures.up(0, 240f, 400f, 2000)
        assertEquals(listOf("playing", "down W", "down Q", "up W", "up Q", "down E", "up E"), log.events)
        assertEquals("200,400 200,370", log.stick.first())
        assertEquals("gone", log.stick.last())
    }

    @Test
    fun theStickFollowsAThumbThatGoesFurther() {
        gestures.down(0, 200f, 500f, 0)
        gestures.move(0, 200f, 300f) // far up: the base follows to 200,370
        gestures.move(0, 200f, 330f) // back down a little: still up, past the base
        gestures.move(0, 200f, 420f) // below the base: back
        gestures.up(0, 200f, 420f, 2000)
        assertEquals(listOf("playing", "down W", "up W", "down S", "up S"), log.events)
        assertEquals("200,370 200,300", log.stick.first())
    }

    @Test
    fun rightDragTurnsFromTheMiddleOfTheScreen() {
        gestures.down(0, 800f, 300f, 0)
        gestures.move(0, 830f, 300f)
        gestures.move(0, 850f, 290f)
        gestures.up(0, 850f, 290f, 900)
        assertEquals(listOf("playing", "to 500,240", "press RIGHT", "by 20,-10", "release RIGHT"), log.events)
    }

    @Test
    fun walkAndTurnTogetherFromThumbsThatLandTogether() {
        gestures.down(0, 200f, 400f, 0)
        gestures.down(1, 800f, 400f, 50)
        gestures.move(0, 200f, 375f) // the left thumb up
        gestures.move(1, 825f, 400f) // the right thumb right
        gestures.move(0, 200f, 360f)
        gestures.move(1, 840f, 400f)
        gestures.up(0, 200f, 360f, 2000)
        gestures.up(1, 840f, 400f, 2000)
        assertTrue("down W" in log.events, log.events.toString())
        assertTrue("press RIGHT" in log.events, log.events.toString())
        assertTrue(log.events.none { it.startsWith("click WHEEL") }, log.events.toString())
        assertEquals(listOf("up W", "release RIGHT"), log.events.takeLast(2))
    }

    @Test
    fun aTapWhileWalkingClicksAndADoubleTapJumps() {
        gestures.down(0, 200f, 400f, 0)
        gestures.move(0, 200f, 370f)
        gestures.down(1, 700f, 550f, 500)
        gestures.up(1, 700f, 550f, 560)
        gestures.down(1, 700f, 550f, 700)
        gestures.up(1, 700f, 550f, 760)
        gestures.up(0, 200f, 370f, 2000)
        assertEquals(listOf("playing", "down W", "to 700,550", "click LEFT", "tap SPACE", "up W"), log.events)
    }

    @Test
    fun pinchZooms() {
        gestures.down(0, 400f, 300f, 0)
        gestures.down(1, 600f, 300f, 40)
        gestures.move(0, 370f, 300f)
        gestures.move(1, 630f, 300f) // 60 apart more: two notches in
        gestures.move(0, 400f, 300f)
        gestures.move(1, 600f, 300f) // back: two notches out
        gestures.up(0, 400f, 300f, 900)
        gestures.up(1, 600f, 300f, 910)
        assertEquals(
            listOf("to 500,240", "click WHEEL_UP", "click WHEEL_UP", "click WHEEL_DOWN", "click WHEEL_DOWN"),
            log.events,
        )
    }

    @Test
    fun twoFingerTapRightClicks() {
        gestures.down(0, 300f, 300f, 0)
        gestures.down(1, 400f, 300f, 30)
        gestures.up(1, 400f, 300f, 120)
        gestures.up(0, 300f, 300f, 150)
        assertEquals(listOf("to 300,300", "click RIGHT"), log.events)
    }

    @Test
    fun twoFingersUpOpenTheChat() {
        gestures.down(0, 450f, 400f, 0)
        gestures.down(1, 550f, 400f, 30)
        for (step in 1..5) {
            gestures.move(0, 450f, 400f - step * 10)
            gestures.move(1, 550f, 400f - step * 10)
        }
        gestures.up(0, 450f, 350f, 500)
        gestures.up(1, 550f, 350f, 510)
        assertEquals(listOf("chat"), log.events)
    }

    @Test
    fun threeFingerTapShowsTheKeyboardAndFourTheMenu() {
        for (id in 0..2) gestures.down(id, 300f + 100f * id, 300f, id * 20L)
        for (id in 0..2) gestures.up(id, 300f + 100f * id, 300f, 200 + id * 20L)
        for (id in 0..3) gestures.down(id, 300f + 100f * id, 300f, 1000 + id * 20L)
        for (id in 0..3) gestures.up(id, 300f + 100f * id, 300f, 1200 + id * 20L)
        assertEquals(listOf("keyboard", "menu"), log.events)
    }

    @Test
    fun cancelLetsGoOfEverything() {
        gestures.down(0, 200f, 400f, 0)
        gestures.move(0, 230f, 370f)
        gestures.down(1, 800f, 300f, 600)
        gestures.move(1, 840f, 300f)
        gestures.cancel()
        assertEquals(
            listOf("playing", "down W", "down E", "playing", "to 500,240", "press RIGHT", "up W", "up E", "release RIGHT"),
            log.events,
        )
        assertEquals("gone", log.stick.last())
    }

    @Test
    fun eightDirections() {
        assertEquals(setOf(Key.E), directionKeys(1f, 0f))
        assertEquals(setOf(Key.S), directionKeys(0f, 1f))
        assertEquals(setOf(Key.Q), directionKeys(-1f, 0.1f))
        assertEquals(setOf(Key.Q), directionKeys(-1f, -0.1f))
        assertEquals(setOf(Key.W), directionKeys(0f, -1f))
        assertEquals(setOf(Key.W, Key.E), directionKeys(1f, -1f))
        assertEquals(setOf(Key.S, Key.Q), directionKeys(-1f, 1f))
    }
}
