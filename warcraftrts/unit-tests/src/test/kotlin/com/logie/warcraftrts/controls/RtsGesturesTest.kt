package com.logie.warcraftrts.controls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RtsGesturesTest {
    private class Recorder : GestureOutput {
        val events = mutableListOf<String>()
        override fun pointerTo(x: Float, y: Float) { events += "move ${x.toInt()},${y.toInt()}" }
        override fun press(button: Button) { events += "press $button" }
        override fun release(button: Button) { events += "release $button" }
        override fun click(button: Button) { events += "click $button" }
        override fun wheel(up: Boolean) { events += if (up) "wheel up" else "wheel down" }
        override fun keyDown(key: Key) { events += "down $key" }
        override fun keyUp(key: Key) { events += "up $key" }
        override fun toggleQuickKeys() { events += "quick keys" }
        override fun showMenu() { events += "menu" }
        override fun feedback() { events += "buzz" }
    }

    private val out = Recorder()
    private val gestures = RtsGestures(out, density = 1f)

    @Test
    fun `a tap left-clicks where the finger landed`() {
        gestures.down(0, 100f, 200f, 0)
        gestures.up(0, 103f, 201f, 120)
        assertEquals(listOf("move 100,200", "click LEFT"), out.events)
    }

    @Test
    fun `two quick taps are two left clicks, the game's double click`() {
        gestures.down(0, 100f, 200f, 0)
        gestures.up(0, 100f, 200f, 80)
        gestures.down(0, 102f, 199f, 160)
        gestures.up(0, 102f, 199f, 230)
        assertEquals(listOf("move 100,200", "click LEFT", "move 102,199", "click LEFT"), out.events)
    }

    @Test
    fun `a hold right-clicks once, with a buzz, and lifting does nothing more`() {
        gestures.down(0, 50f, 60f, 0)
        gestures.onHold(RtsGestures.HOLD_MS)
        gestures.move(0, 90f, 60f)
        gestures.up(0, 90f, 60f, 900)
        assertEquals(listOf("move 50,60", "click RIGHT", "buzz"), out.events)
    }

    @Test
    fun `a hold that comes too early or after the finger moved is ignored`() {
        gestures.down(0, 50f, 60f, 0)
        gestures.onHold(100)
        assertTrue(out.events.isEmpty())
        gestures.move(0, 80f, 60f)
        gestures.onHold(RtsGestures.HOLD_MS)
        assertEquals("press LEFT", out.events[1])
        assertTrue("click RIGHT" !in out.events)
    }

    @Test
    fun `a drag holds the left button from where the finger landed to where it lifts`() {
        gestures.down(0, 10f, 10f, 0)
        gestures.move(0, 15f, 12f)
        gestures.move(0, 40f, 30f)
        gestures.move(0, 80f, 70f)
        gestures.up(0, 80f, 70f, 600)
        assertEquals(
            listOf("move 10,10", "press LEFT", "move 40,30", "move 80,70", "move 80,70", "release LEFT"),
            out.events,
        )
    }

    @Test
    fun `a two-finger tap right-clicks where the first finger landed`() {
        gestures.down(0, 100f, 100f, 0)
        gestures.down(1, 300f, 120f, 30)
        gestures.up(1, 300f, 120f, 150)
        gestures.up(0, 100f, 100f, 170)
        assertEquals(listOf("move 100,100", "click RIGHT"), out.events)
    }

    @Test
    fun `two fingers dragged right scroll the camera left until they lift`() {
        gestures.down(0, 100f, 100f, 0)
        gestures.down(1, 200f, 100f, 20)
        gestures.move(listOf(RtsGestures.Touch(0, 140f, 100f), RtsGestures.Touch(1, 240f, 100f)))
        assertEquals(listOf("down LEFT"), out.events)
        gestures.up(0, 140f, 100f, 900)
        gestures.up(1, 240f, 100f, 920)
        assertEquals(listOf("down LEFT", "up LEFT"), out.events)
    }

    @Test
    fun `two fingers dragged down and left scroll up and right together`() {
        gestures.down(0, 100f, 100f, 0)
        gestures.down(1, 200f, 100f, 10)
        gestures.move(listOf(RtsGestures.Touch(0, 70f, 130f), RtsGestures.Touch(1, 170f, 130f)))
        assertEquals(setOf("down RIGHT", "down UP"), out.events.toSet())
        gestures.cancel()
        assertEquals(setOf("down RIGHT", "down UP", "up RIGHT", "up UP"), out.events.toSet())
    }

    @Test
    fun `a pinch turns the wheel, a notch per step, and never right-clicks`() {
        gestures.down(0, 100f, 100f, 0)
        gestures.down(1, 200f, 100f, 10)
        gestures.move(listOf(RtsGestures.Touch(0, 50f, 100f), RtsGestures.Touch(1, 250f, 100f)))
        assertEquals(listOf("wheel up", "wheel up"), out.events)
        gestures.move(listOf(RtsGestures.Touch(0, 100f, 100f), RtsGestures.Touch(1, 200f, 100f)))
        gestures.up(0, 100f, 100f, 200)
        gestures.up(1, 200f, 100f, 210)
        assertEquals(listOf("wheel up", "wheel up", "wheel down", "wheel down"), out.events)
    }

    @Test
    fun `three fingers show the quick keys and four the menu`() {
        for (id in 0..2) gestures.down(id, 100f + id * 50, 100f, id * 10L)
        for (id in 0..2) gestures.up(id, 100f + id * 50, 100f, 200L + id)
        assertEquals(listOf("quick keys"), out.events)
        for (id in 0..3) gestures.down(id, 100f + id * 50, 100f, 1000L + id * 10)
        for (id in 0..3) gestures.up(id, 100f + id * 50, 100f, 1200L + id)
        assertEquals(listOf("quick keys", "menu"), out.events)
    }

    @Test
    fun `fingers resting too long are no tap`() {
        gestures.down(0, 100f, 100f, 0)
        gestures.down(1, 200f, 100f, 10)
        gestures.up(0, 100f, 100f, 2000)
        gestures.up(1, 200f, 100f, 2010)
        assertTrue(out.events.isEmpty())
    }

    @Test
    fun `a second finger during a drag lets go of the box and taps nothing`() {
        gestures.down(0, 10f, 10f, 0)
        gestures.move(0, 60f, 60f)
        gestures.down(1, 300f, 300f, 100)
        gestures.up(1, 300f, 300f, 150)
        gestures.up(0, 60f, 60f, 200)
        assertEquals(listOf("move 10,10", "press LEFT", "move 60,60", "release LEFT"), out.events)
    }
}
