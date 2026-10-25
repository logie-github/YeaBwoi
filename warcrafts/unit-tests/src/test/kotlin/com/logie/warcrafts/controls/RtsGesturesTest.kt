package com.logie.warcrafts.controls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RtsGesturesTest {
    private class Recorder : GestureOutput {
        val events = mutableListOf<String>()
        override fun pointerTo(x: Float, y: Float) { events += "move ${x.toInt()},${y.toInt()}" }
        override fun click(button: Button) { events += "click $button" }
        override fun doubleClick(button: Button) { events += "double $button" }
        override fun wheel(up: Boolean) { events += if (up) "wheel up" else "wheel down" }
        override fun keyDown(key: Key) { events += "down $key" }
        override fun keyUp(key: Key) { events += "up $key" }
        override fun gameMenu() { events += "game menu" }
        override fun openKeyboard() { events += "keyboard" }
        override fun showMenu() { events += "menu" }
        override fun feedback() { events += "buzz" }
        override fun press(button: Button) { events += "press $button" }
        override fun release(button: Button) { events += "release $button" }
        var held = HeldDrag.PAN
        var minimap: ((Float, Float) -> Boolean)? = null
        override fun heldDrag(x: Float, y: Float) = held
        override fun dragZone(x: Float, y: Float) = minimap?.invoke(x, y) ?: false
    }

    private val out = Recorder()
    private val gestures = RtsGestures(out, density = 1f)

    private fun hold(id: Int, x: Float, y: Float, at: Long, liftAt: Long = at + 600) {
        gestures.down(id, x, y, at)
        gestures.onHold(at + RtsGestures.HOLD_MS)
        gestures.up(id, x, y, liftAt)
    }

    @Test
    fun `a tap right-clicks where the finger landed`() {
        gestures.down(0, 100f, 200f, 0)
        gestures.up(0, 103f, 201f, 120)
        assertEquals(listOf("move 100,200", "click RIGHT"), out.events)
    }

    @Test
    fun `a hold buzzes, then left-clicks there when the finger lifts`() {
        gestures.down(0, 50f, 60f, 0)
        gestures.onHold(RtsGestures.HOLD_MS)
        assertEquals(listOf("buzz"), out.events)
        gestures.up(0, 52f, 61f, 700)
        assertEquals(listOf("buzz", "move 50,60", "click LEFT"), out.events)
    }

    @Test
    fun `two holds in a row on the same spot are a double click`() {
        hold(0, 50f, 60f, 0, liftAt = 500)
        hold(0, 55f, 62f, 800, liftAt = 1300)
        assertEquals(listOf("buzz", "move 50,60", "click LEFT", "buzz", "move 55,62", "double LEFT"), out.events)
    }

    @Test
    fun `holds far apart or long apart are single clicks`() {
        hold(0, 50f, 60f, 0, liftAt = 500)
        hold(0, 300f, 60f, 800, liftAt = 1300)
        hold(0, 300f, 60f, 5000, liftAt = 5500)
        assertEquals(3, out.events.count { it == "click LEFT" })
        assertTrue("double LEFT" !in out.events)
    }

    @Test
    fun `a swipe right pans right while the finger is away, and lets go when it lifts`() {
        gestures.down(0, 100f, 100f, 0)
        gestures.move(0, 140f, 102f)
        assertEquals(listOf("down RIGHT"), out.events)
        gestures.move(0, 200f, 104f)
        gestures.up(0, 200f, 104f, 900)
        assertEquals(listOf("down RIGHT", "up RIGHT"), out.events)
    }

    @Test
    fun `a swipe up and left pans up and left, and turning around follows the finger`() {
        gestures.down(0, 100f, 100f, 0)
        gestures.move(0, 70f, 70f)
        assertEquals(setOf("down LEFT", "down UP"), out.events.toSet())
        gestures.move(0, 100f, 140f)
        assertTrue("down DOWN" in out.events && "up LEFT" in out.events && "up UP" in out.events)
        gestures.cancel()
        assertTrue("up DOWN" in out.events)
    }

    @Test
    fun `a swipe after a hold pans and doesn't click`() {
        gestures.down(0, 100f, 100f, 0)
        gestures.onHold(RtsGestures.HOLD_MS)
        gestures.move(0, 100f, 160f)
        gestures.up(0, 100f, 160f, 900)
        assertEquals(listOf("buzz", "down DOWN", "up DOWN"), out.events)
    }

    @Test
    fun `taps while a finger rests are Shift-clicks, and Shift ends when it lifts`() {
        gestures.down(0, 20f, 500f, 0)
        gestures.down(1, 300f, 200f, 600)
        gestures.up(1, 300f, 200f, 700)
        gestures.down(2, 320f, 220f, 900)
        gestures.onHold(900 + RtsGestures.HOLD_MS)
        gestures.up(2, 320f, 220f, 1500)
        gestures.up(0, 20f, 500f, 1700)
        assertEquals(
            listOf("down SHIFT", "move 300,200", "click RIGHT", "buzz", "move 320,220", "click LEFT", "up SHIFT"),
            out.events,
        )
    }

    @Test
    fun `the resting finger's own hold does nothing`() {
        gestures.down(0, 20f, 500f, 0)
        gestures.onHold(RtsGestures.HOLD_MS)
        gestures.down(1, 300f, 200f, 600)
        gestures.up(1, 300f, 200f, 650)
        gestures.up(0, 20f, 500f, 900)
        assertEquals(listOf("buzz", "down SHIFT", "move 300,200", "click RIGHT", "up SHIFT"), out.events)
    }

    @Test
    fun `a two-finger tap opens the game's menu`() {
        gestures.down(0, 100f, 100f, 0)
        gestures.down(1, 300f, 120f, 40)
        gestures.up(1, 300f, 120f, 150)
        gestures.up(0, 100f, 100f, 170)
        assertEquals(listOf("game menu"), out.events)
    }

    @Test
    fun `a pinch turns the wheel, a notch per step, and opens no menu`() {
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
    fun `three fingers open the keyboard and four the app's menu`() {
        for (id in 0..2) gestures.down(id, 100f + id * 50, 100f, id * 10L)
        for (id in 0..2) gestures.up(id, 100f + id * 50, 100f, 200L + id)
        assertEquals(listOf("keyboard"), out.events)
        for (id in 0..3) gestures.down(id, 100f + id * 50, 100f, 1000L + id * 10)
        for (id in 0..3) gestures.up(id, 100f + id * 50, 100f, 1200L + id)
        assertEquals(listOf("keyboard", "menu"), out.events)
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
    fun `a second finger during a pan stops it and taps nothing`() {
        gestures.down(0, 10f, 10f, 0)
        gestures.move(0, 60f, 10f)
        gestures.down(1, 300f, 300f, 100)
        gestures.up(1, 300f, 300f, 150)
        gestures.up(0, 60f, 10f, 200)
        assertEquals(listOf("down RIGHT", "up RIGHT"), out.events)
    }

    @Test
    fun `held then moved draws a selection box with the left button down`() {
        out.held = HeldDrag.SELECT
        gestures.down(0, 100f, 100f, 0)
        gestures.onHold(RtsGestures.HOLD_MS)
        gestures.move(0, 160f, 150f)
        gestures.move(0, 200f, 190f)
        gestures.up(0, 210f, 200f, 900)
        assertEquals(listOf("buzz", "move 100,100", "press LEFT", "move 160,150", "move 200,190", "move 210,200", "release LEFT"), out.events)
    }

    @Test
    fun `held then slid off takes the hold back`() {
        out.held = HeldDrag.CANCEL
        gestures.down(0, 100f, 100f, 0)
        gestures.onHold(RtsGestures.HOLD_MS)
        gestures.move(0, 160f, 150f)
        gestures.up(0, 160f, 150f, 900)
        assertEquals(listOf("buzz"), out.events)
    }

    @Test
    fun `held then moved still pans where the game wants it`() {
        gestures.down(0, 100f, 100f, 0)
        gestures.onHold(RtsGestures.HOLD_MS)
        gestures.move(0, 200f, 100f)
        gestures.up(0, 200f, 100f, 900)
        assertEquals(listOf("buzz", "down RIGHT", "up RIGHT"), out.events)
    }

    @Test
    fun `a swipe from the minimap drags on it instead of panning`() {
        out.minimap = { x, y -> x < 50f && y > 400f }
        gestures.down(0, 20f, 450f, 0)
        gestures.move(0, 40f, 470f)
        gestures.up(0, 45f, 480f, 300)
        assertEquals(listOf("move 20,450", "press LEFT", "move 40,470", "move 45,480", "release LEFT"), out.events)
        out.events.clear()
        gestures.down(0, 300f, 300f, 1000)
        gestures.move(0, 340f, 300f)
        gestures.up(0, 340f, 300f, 1300)
        assertEquals(listOf("down RIGHT", "up RIGHT"), out.events)
    }

    @Test
    fun `a second finger during a drag lets go of the button`() {
        out.held = HeldDrag.SELECT
        gestures.down(0, 100f, 100f, 0)
        gestures.onHold(RtsGestures.HOLD_MS)
        gestures.move(0, 160f, 150f)
        gestures.down(1, 300f, 300f, 800)
        assertTrue(out.events.last() == "release LEFT")
    }
}
