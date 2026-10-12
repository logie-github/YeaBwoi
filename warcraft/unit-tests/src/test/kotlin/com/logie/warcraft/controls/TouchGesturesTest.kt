package com.logie.warcraft.controls

import kotlin.test.Test
import kotlin.test.assertEquals

class TouchGesturesTest {
    /** What the gestures did, as text. */
    private class Log : GestureOutput {
        val events = mutableListOf<String>()
        override fun pointerTo(x: Float, y: Float) { events += "to ${x.toInt()},${y.toInt()}" }
        override fun pointerBy(dx: Float, dy: Float) { events += "by ${dx.toInt()},${dy.toInt()}" }
        override fun press(button: Button) { events += "press $button" }
        override fun release(button: Button) { events += "release $button" }
        override fun click(button: Button) { events += "click $button" }
        override fun keyDown(key: Key) { events += "down $key" }
        override fun keyUp(key: Key) { events += "up $key" }
        override fun tap(key: Key) { events += "tap $key" }
        override fun showKeyboard() { events += "keyboard" }
        override fun showMenu() { events += "menu" }
    }

    private val log = Log()
    // Density 1: the distances below are in dp.
    private val gestures = TouchGestures(log, density = 1f)

    @Test
    fun tapClicksWhereTheFingerIs() {
        gestures.down(0, 100f, 200f, 0)
        gestures.up(0, 102f, 201f, 80)
        assertEquals(listOf("to 102,201", "click LEFT"), log.events)
    }

    @Test
    fun doubleTapJumps() {
        gestures.down(0, 100f, 200f, 0)
        gestures.up(0, 100f, 200f, 80)
        gestures.down(0, 110f, 205f, 200)
        gestures.up(0, 110f, 205f, 260)
        assertEquals(listOf("to 100,200", "click LEFT", "tap SPACE"), log.events)
    }

    @Test
    fun twoTapsApartAreTwoClicks() {
        gestures.down(0, 100f, 200f, 0)
        gestures.up(0, 100f, 200f, 80)
        gestures.down(0, 100f, 200f, 1000)
        gestures.up(0, 100f, 200f, 1080)
        gestures.down(0, 400f, 200f, 1200)
        gestures.up(0, 400f, 200f, 1280)
        assertEquals(listOf("to 100,200", "click LEFT", "to 100,200", "click LEFT", "to 400,200", "click LEFT"), log.events)
    }

    @Test
    fun holdOpensTheBagsOnce() {
        gestures.down(0, 100f, 200f, 0)
        gestures.move(0, 103f, 200f)
        gestures.onHold(TouchGestures.HOLD_MS)
        gestures.up(0, 103f, 200f, 900)
        assertEquals(listOf("tap B"), log.events)
    }

    @Test
    fun tapThenHoldOpensTheMap() {
        gestures.down(0, 100f, 200f, 0)
        gestures.up(0, 100f, 200f, 80)
        gestures.down(0, 100f, 200f, 200)
        gestures.onHold(200 + TouchGestures.HOLD_MS)
        gestures.up(0, 100f, 200f, 900)
        assertEquals(listOf("to 100,200", "click LEFT", "tap M"), log.events)
    }

    @Test
    fun aHoldThatMovedIsntOne() {
        gestures.down(0, 100f, 200f, 0)
        gestures.move(0, 100f, 160f)
        gestures.onHold(TouchGestures.HOLD_MS)
        gestures.up(0, 100f, 160f, 900)
        assertEquals(listOf("down W", "up W"), log.events)
    }

    @Test
    fun swipeWalksAndSteers() {
        gestures.down(0, 500f, 500f, 0)
        gestures.move(0, 500f, 470f) // up
        gestures.move(0, 470f, 470f) // up and left
        gestures.move(0, 460f, 500f) // left
        gestures.up(0, 460f, 500f, 2000)
        assertEquals(listOf("down W", "down A", "up W", "up A"), log.events)
    }

    @Test
    fun theStickFollowsAFingerThatGoesFurther() {
        gestures.down(0, 500f, 500f, 0)
        gestures.move(0, 500f, 300f) // far up: the anchor follows to 500,380
        gestures.move(0, 500f, 330f) // back down a little: still up, past the anchor
        gestures.move(0, 500f, 430f) // below the anchor: back
        gestures.up(0, 500f, 430f, 2000)
        assertEquals(listOf("down W", "up W", "down S", "up S"), log.events)
    }

    @Test
    fun aSecondFingersTapWhileWalkingJumps() {
        gestures.down(0, 500f, 500f, 0)
        gestures.move(0, 500f, 450f)
        gestures.down(1, 900f, 300f, 500)
        gestures.up(1, 900f, 300f, 560)
        gestures.move(0, 500f, 440f)
        gestures.up(0, 500f, 440f, 2000)
        assertEquals(listOf("down W", "tap SPACE", "up W"), log.events)
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
    fun twoFingerDragTurnsTheCamera() {
        gestures.down(0, 300f, 300f, 0)
        gestures.down(1, 400f, 300f, 30)
        gestures.move(0, 340f, 300f)
        gestures.move(1, 440f, 300f)
        gestures.up(0, 340f, 300f, 600)
        gestures.up(1, 440f, 300f, 610)
        assertEquals(listOf("to 370,300", "press RIGHT", "by 20,0", "release RIGHT"), log.events)
    }

    @Test
    fun threeFingerTapShowsTheKeyboardAndFourTheMenu() {
        for (id in 0..2) gestures.down(id, 100f * id, 300f, id * 20L)
        for (id in 0..2) gestures.up(id, 100f * id, 300f, 200 + id * 20L)
        for (id in 0..3) gestures.down(id, 100f * id, 300f, 1000 + id * 20L)
        for (id in 0..3) gestures.up(id, 100f * id, 300f, 1200 + id * 20L)
        assertEquals(listOf("keyboard", "menu"), log.events)
    }

    @Test
    fun cancelLetsGoOfEverything() {
        gestures.down(0, 500f, 500f, 0)
        gestures.move(0, 530f, 470f)
        gestures.cancel()
        assertEquals(listOf("down W", "down D", "up W", "up D"), log.events)
    }

    @Test
    fun eightDirections() {
        assertEquals(setOf(Key.D), directionKeys(1f, 0f))
        assertEquals(setOf(Key.S), directionKeys(0f, 1f))
        assertEquals(setOf(Key.A), directionKeys(-1f, 0.1f))
        assertEquals(setOf(Key.A), directionKeys(-1f, -0.1f))
        assertEquals(setOf(Key.W), directionKeys(0f, -1f))
        assertEquals(setOf(Key.W, Key.D), directionKeys(1f, -1f))
        assertEquals(setOf(Key.S, Key.A), directionKeys(-1f, 1f))
    }
}
