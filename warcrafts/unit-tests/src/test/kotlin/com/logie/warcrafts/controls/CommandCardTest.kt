package com.logie.warcrafts.controls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandCardTest {
    /** A card of 48×30 pixels whose slots ([CommandCard.COLUMNS]×[CommandCard.ROWS]) are lit as [lit] says. */
    private fun card(vararg lit: Int, bright: Int = 200, dark: Int = 12): CommandCard.Look {
        val width = 48
        val height = 30
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val slot = (y / 10) * CommandCard.COLUMNS + x / 12
            val v = if (slot in lit) bright else dark
            (0xff shl 24) or (v shl 16) or (v shl 8) or v
        }
        return CommandCard.Look.of(pixels, width, height)
    }

    private val unit = card(0, 1, 2, 3, 4, 8)
    private val cancelOnly = card(CommandCard.CANCEL_SLOT)

    @Test
    fun `the card is at the bottom right, sized by the screen's height`() {
        val (left, top, right, bottom) = CommandCard.area(1440f, 1080f)
        assertEquals(1440f - 1080f * 0.31f, left, 0.01f)
        assertEquals(1080f * 0.8f, top, 0.01f)
        assertEquals(1440f, right)
        assertEquals(1080f, bottom)
        assertTrue(CommandCard.contains(1440f, 1080f, 1400f, 1050f))
        assertFalse(CommandCard.contains(1440f, 1080f, 700f, 1050f))
        // A wide screen keeps it at the right, as wide.
        assertEquals(1920f - 1080f * 0.31f, CommandCard.area(1920f, 1080f)[0], 0.01f)
    }

    @Test
    fun `each slot's brightness is its middle's`() {
        val look = card(0, 11)
        assertEquals(200f, look.slots[0])
        assertEquals(12f, look.slots[5])
        assertEquals(2, look.filled(100f))
        assertTrue(look.filled(CommandCard.CANCEL_SLOT, 100f))
    }

    @Test
    fun `a button that leaves only Cancel means the game waits for a target`() {
        val watch = AimWatch()
        watch.pressed(unit, cancelOnly, now = 0)
        assertTrue(watch.aiming)
        watch.done()
        assertFalse(watch.aiming)
    }

    @Test
    fun `a button that leaves the card full, or another card, is no aim`() {
        val watch = AimWatch()
        watch.pressed(unit, unit, now = 0)
        assertFalse(watch.aiming)
        // A building's menu (Build): another full card.
        watch.pressed(unit, card(0, 1, 2, 4, 5, 11), now = 0)
        assertFalse(watch.aiming)
        // An empty card after (nothing selected any more): no Cancel.
        watch.pressed(unit, card(), now = 0)
        assertFalse(watch.aiming)
    }

    @Test
    fun `Cancel pressed while waiting (a card of one button before) is no aim`() {
        val watch = AimWatch()
        watch.pressed(cancelOnly, card(), now = 0)
        assertFalse(watch.aiming)
    }

    @Test
    fun `a dark screen tells nothing`() {
        val watch = AimWatch()
        watch.pressed(card(bright = 20), card(CommandCard.CANCEL_SLOT, bright = 20), now = 0)
        assertFalse(watch.aiming)
    }

    @Test
    fun `waiting ends when the buttons come back, Cancel goes, or it lasts too long`() {
        val watch = AimWatch()
        watch.pressed(unit, cancelOnly, now = 0)
        watch.seen(cancelOnly, now = 400)
        assertTrue(watch.aiming)
        watch.seen(unit, now = 800)
        assertFalse(watch.aiming)

        watch.pressed(unit, cancelOnly, now = 0)
        watch.seen(card(), now = 400)
        assertFalse(watch.aiming)

        watch.pressed(unit, cancelOnly, now = 0)
        watch.seen(cancelOnly, now = AimWatch.GIVE_UP_MS + 1)
        assertFalse(watch.aiming)
    }
}
