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
        var aim = false
        override fun aiming() = aim
        var attacks = false
        override fun attackMove(): Boolean {
            if (attacks) events += "attack-move"
            return attacks
        }
        override fun nextSubgroup() { events += "tab" }
        var magnifier = false
        override fun magnify(x: Float, y: Float) { if (magnifier) events += "magnify ${x.toInt()},${y.toInt()}" }
        override fun magnifyOff() { if (magnifier) events += "magnify off" }
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
    fun `a drag after a hold draws a selection box from where it landed to where it lifts`() {
        gestures.down(0, 100f, 100f, 0)
        gestures.onHold(RtsGestures.HOLD_MS)
        gestures.move(0, 140f, 160f)
        gestures.move(0, 200f, 260f)
        gestures.up(0, 210f, 270f, 900)
        assertEquals(
            listOf("buzz", "move 100,100", "press LEFT", "move 140,160", "move 200,260", "move 210,270", "release LEFT"),
            out.events,
        )
    }

    @Test
    fun `a box lets go of the button if the touch is taken away`() {
        gestures.down(0, 100f, 100f, 0)
        gestures.onHold(RtsGestures.HOLD_MS)
        gestures.move(0, 160f, 160f)
        gestures.cancel()
        assertEquals("release LEFT", out.events.last())
    }

    @Test
    fun `another finger landing during a box is left alone`() {
        gestures.down(0, 100f, 100f, 0)
        gestures.onHold(RtsGestures.HOLD_MS)
        gestures.move(0, 160f, 160f)
        gestures.down(1, 400f, 400f, 700)
        gestures.up(1, 400f, 400f, 750)
        gestures.move(0, 180f, 190f)
        gestures.up(0, 180f, 190f, 900)
        assertEquals(listOf("move 180,190", "move 180,190", "release LEFT"), out.events.takeLast(3))
        assertTrue("game menu" !in out.events)
    }

    @Test
    fun `while the game waits for a target, a tap is the left click`() {
        out.aim = true
        gestures.down(0, 100f, 200f, 0)
        gestures.up(0, 100f, 200f, 100)
        assertEquals(listOf("move 100,200", "click LEFT"), out.events)
    }

    @Test
    fun `while the game waits for a target, a drag after a hold aims and clicks where it lifts`() {
        out.aim = true
        gestures.down(0, 100f, 100f, 0)
        gestures.onHold(RtsGestures.HOLD_MS)
        gestures.move(0, 150f, 130f)
        gestures.up(0, 160f, 140f, 900)
        assertEquals(listOf("buzz", "move 150,130", "move 160,140", "click LEFT"), out.events)
    }

    @Test
    fun `a second tap on the same spot attack-moves`() {
        out.attacks = true
        gestures.down(0, 100f, 200f, 0)
        gestures.up(0, 100f, 200f, 80)
        gestures.down(0, 104f, 203f, 250)
        gestures.up(0, 104f, 203f, 320)
        assertEquals(listOf("move 100,200", "click RIGHT", "move 104,203", "attack-move"), out.events)
        // A third tap starts again: a right click.
        gestures.down(0, 104f, 203f, 450)
        gestures.up(0, 104f, 203f, 500)
        assertEquals("click RIGHT", out.events.last())
    }

    @Test
    fun `a second tap where attack-move isn't done is another click`() {
        gestures.down(0, 100f, 200f, 0)
        gestures.up(0, 100f, 200f, 80)
        gestures.down(0, 100f, 200f, 250)
        gestures.up(0, 100f, 200f, 320)
        assertEquals(listOf("move 100,200", "click RIGHT", "move 100,200", "click RIGHT"), out.events)
    }

    @Test
    fun `taps far apart or slow are no attack-move`() {
        out.attacks = true
        gestures.down(0, 100f, 200f, 0)
        gestures.up(0, 100f, 200f, 80)
        gestures.down(0, 300f, 200f, 250)
        gestures.up(0, 300f, 200f, 320)
        gestures.down(0, 300f, 200f, 1000)
        gestures.up(0, 300f, 200f, 1080)
        assertTrue("attack-move" !in out.events)
        assertEquals(3, out.events.count { it == "click RIGHT" })
    }

    @Test
    fun `the magnifier shows during a hold and its drag, and goes when the finger lifts`() {
        out.magnifier = true
        gestures.down(0, 100f, 100f, 0)
        gestures.onHold(RtsGestures.HOLD_MS)
        gestures.move(0, 150f, 150f)
        gestures.up(0, 150f, 150f, 900)
        assertEquals(listOf("buzz", "magnify 100,100"), out.events.take(2))
        assertTrue("magnify 150,150" in out.events)
        assertEquals("magnify off", out.events.last())
    }

    @Test
    fun `a tap shows no magnifier`() {
        out.magnifier = true
        gestures.down(0, 100f, 100f, 0)
        gestures.up(0, 100f, 100f, 100)
        assertTrue(out.events.none { it.startsWith("magnify") })
    }

    @Test
    fun `the hold's time is the launcher's`() {
        val quick = RtsGestures(out, density = 1f, holdMs = 250)
        quick.down(0, 50f, 60f, 0)
        quick.onHold(250)
        quick.up(0, 50f, 60f, 300)
        assertEquals(listOf("buzz", "move 50,60", "click LEFT"), out.events)
    }

    @Test
    fun `a shorter pan start pans sooner`() {
        val near = RtsGestures(out, density = 1f, panStartDp = 8f)
        near.down(0, 100f, 100f, 0)
        near.move(0, 111f, 100f)
        assertEquals(listOf("down RIGHT"), out.events)
        near.cancel()
        out.events.clear()
        gestures.down(0, 100f, 100f, 0)
        gestures.move(0, 111f, 100f)
        assertTrue(out.events.isEmpty())
        gestures.cancel()
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
    fun `a swipe while a finger rests is a Shift box`() {
        gestures.down(0, 20f, 500f, 0)
        gestures.down(1, 300f, 200f, 600)
        gestures.move(1, 340f, 240f)
        gestures.up(1, 400f, 300f, 900)
        gestures.up(0, 20f, 500f, 1000)
        assertEquals(
            listOf("down SHIFT", "move 300,200", "press LEFT", "move 340,240", "move 400,300", "release LEFT", "up SHIFT"),
            out.events,
        )
    }

    @Test
    fun `the resting finger lifting during a Shift box ends it`() {
        gestures.down(0, 20f, 500f, 0)
        gestures.down(1, 300f, 200f, 600)
        gestures.move(1, 340f, 240f)
        gestures.up(0, 20f, 500f, 800)
        assertEquals(listOf("release LEFT", "up SHIFT"), out.events.takeLast(2))
        gestures.up(1, 340f, 240f, 900)
        assertEquals(1, out.events.count { it == "release LEFT" })
    }

    @Test
    fun `two fingers tapping while one rests are Tab, with Shift let go for it and back after`() {
        gestures.down(0, 20f, 500f, 0)
        gestures.down(1, 300f, 200f, 600)
        gestures.down(2, 360f, 200f, 620)
        gestures.up(1, 300f, 200f, 700)
        gestures.up(2, 360f, 200f, 710)
        assertEquals(listOf("down SHIFT", "up SHIFT", "tab", "down SHIFT"), out.events)
        // Shift-clicks go on.
        gestures.down(3, 200f, 200f, 900)
        gestures.up(3, 200f, 200f, 950)
        gestures.up(0, 20f, 500f, 1100)
        assertEquals(listOf("move 200,200", "click RIGHT", "up SHIFT"), out.events.takeLast(3))
    }

    @Test
    fun `two fingers that move while one rests are no Tab`() {
        gestures.down(0, 20f, 500f, 0)
        gestures.down(1, 300f, 200f, 600)
        gestures.down(2, 360f, 200f, 620)
        gestures.move(listOf(RtsGestures.Touch(1, 360f, 260f), RtsGestures.Touch(2, 420f, 260f)))
        gestures.up(1, 360f, 260f, 700)
        gestures.up(2, 420f, 260f, 710)
        gestures.up(0, 20f, 500f, 900)
        assertTrue("tab" !in out.events)
        assertEquals("up SHIFT", out.events.last())
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
}
