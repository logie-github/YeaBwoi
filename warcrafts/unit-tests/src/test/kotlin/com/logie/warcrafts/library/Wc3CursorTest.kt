package com.logie.warcrafts.library

import kotlin.test.Test
import kotlin.test.assertEquals

class Wc3CursorTest {
    private val size = 128

    /** A square of ground ([ground]) with a gauntlet of [tint] drawn into it (40 by 45, half of it tinted, the rest silver). */
    private fun square(ground: (Int, Int) -> Int, tint: Int? = null, silver: Boolean = false): IntArray = IntArray(size * size) { i ->
        val x = i % size
        val y = i / size
        val inCursor = x in 60..100 && y in 66..111
        when {
            inCursor && tint != null && (x + y) % 2 == 0 -> tint
            inCursor && (tint != null || silver) -> 0x8a96a0 // the gauntlet's silver
            else -> ground(x, y)
        }
    }

    private val dirt = { _: Int, _: Int -> 0x6e4a2c }
    /** Grass: green, strongly coloured, and patterned (as the game's is). */
    private val grass = { x: Int, y: Int -> if ((x / 3 + y / 2) % 2 == 0) 0x2e7a1e else 0x3f9a26 }

    @Test
    fun `the gauntlet's tint tells whose it points at`() {
        val before = square(dirt)
        assertEquals(Wc3Cursor.Under.OWN, Wc3Cursor.classify(before, square(dirt, tint = 0x18d018), size, 1080))
        assertEquals(Wc3Cursor.Under.ENEMY, Wc3Cursor.classify(before, square(dirt, tint = 0xd01818), size, 1080))
        assertEquals(Wc3Cursor.Under.NEUTRAL, Wc3Cursor.classify(before, square(dirt, tint = 0xa8c020), size, 1080))
        assertEquals(Wc3Cursor.Under.NOTHING, Wc3Cursor.classify(before, square(dirt, silver = true), size, 1080))
    }

    @Test
    fun `green grass under the silver gauntlet is nothing`() {
        assertEquals(Wc3Cursor.Under.NOTHING, Wc3Cursor.classify(square(grass), square(grass, silver = true), size, 1080))
    }

    @Test
    fun `a picture that didn't change is nothing, however green`() {
        val green = square(grass, tint = 0x18d018)
        assertEquals(Wc3Cursor.Under.NOTHING, Wc3Cursor.classify(green, green, size, 1080))
    }

    @Test
    fun `a hovered enemy's green health bar doesn't outweigh the red gauntlet`() {
        val before = square(dirt)
        val after = square(dirt, tint = 0xd01818).also { px -> for (x in 40..100) for (y in 30..36) px[y * size + x] = 0x10c010 }
        assertEquals(Wc3Cursor.Under.ENEMY, Wc3Cursor.classify(before, after, size, 1080))
    }

    @Test
    fun `tints by hue`() {
        assertEquals(Wc3Cursor.Under.OWN, Wc3Cursor.tint(0x00ff00))
        assertEquals(Wc3Cursor.Under.ENEMY, Wc3Cursor.tint(0xff1010))
        assertEquals(Wc3Cursor.Under.NEUTRAL, Wc3Cursor.tint(0xd0d020))
        assertEquals(Wc3Cursor.Under.NOTHING, Wc3Cursor.tint(0x8a96a0)) // silver
        assertEquals(Wc3Cursor.Under.NOTHING, Wc3Cursor.tint(0x102010)) // too dark
    }
}
