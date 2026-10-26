// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What is under Warcraft III's pointer, read from the game's own cursor: the game draws its
 * gauntlet tinted by what it points at (seen in 1.27 under Wine, day and night, on dirt, grass and
 * trees): **green** over the player's own units and buildings, **red** over enemies (and hostile
 * creeps), **yellow** over neutral ones (a gold mine, shops), and **untinted** (silver, the race's
 * own) over the ground and trees. Graphics mods (Classic+) don't change the cursor.
 *
 * [classify] compares a square of the picture around the pointer just before the cursor came
 * there (the cursor elsewhere) with the same square just after: what changed and is strongly
 * coloured is the cursor's tint (and the hovered unit's selection circle, which has the same
 * colour). The square is centred on the pointer, so it holds the cursor whichever way up the
 * picture was read. A hovered unit's health bar (green for a healthy unit of anyone's) adds a
 * little green, far less than the gauntlet.
 */
object Wc3Cursor {
    enum class Under { OWN, ENEMY, NEUTRAL, NOTHING }

    /** Summed red, green and blue change for a pixel to count as the cursor's. */
    private const val CHANGED = 60
    /** Tinted pixels needed at 1080 lines (the gauntlet is about 40 by 45 there, most of it tinted). */
    private const val NEEDED_AT_1080 = 80

    /**
     * What the cursor's tint says: [before] and [after] are the same [size] x [size] square of
     * the picture (ARGB or RGB ints, rows in either order), [lines] the game's screen height.
     */
    fun classify(before: IntArray, after: IntArray, size: Int, lines: Int): Under {
        require(before.size == size * size && after.size == size * size)
        var own = 0
        var enemy = 0
        var neutral = 0
        for (i in after.indices) {
            val a = after[i]
            val b = before[i]
            if (abs(r(a) - r(b)) + abs(g(a) - g(b)) + abs(bl(a) - bl(b)) < CHANGED) continue
            when (tint(a)) {
                Under.OWN -> own++
                Under.ENEMY -> enemy++
                Under.NEUTRAL -> neutral++
                Under.NOTHING -> {}
            }
        }
        val needed = max(12, NEEDED_AT_1080 * lines / 1080 * lines / 1080)
        val best = maxOf(own, enemy, neutral)
        return when {
            best < needed -> Under.NOTHING
            best == own -> Under.OWN
            best == enemy -> Under.ENEMY
            else -> Under.NEUTRAL
        }
    }

    /** A pixel's tint: green, red or yellow when strongly coloured (saturation 0.5 or more, value 0.3 or more). */
    fun tint(colour: Int): Under {
        val r = r(colour)
        val g = g(colour)
        val b = bl(colour)
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        if (high < 77 || (high - low) * 2 < high) return Under.NOTHING
        val hue = hue(r, g, b, high, low)
        return when {
            hue in 90f..150f -> Under.OWN
            hue in 45f..90f -> Under.NEUTRAL
            hue <= 20f || hue >= 340f -> Under.ENEMY
            else -> Under.NOTHING
        }
    }

    private fun hue(r: Int, g: Int, b: Int, high: Int, low: Int): Float {
        val span = (high - low).toFloat()
        if (span == 0f) return 0f
        val h = when (high) {
            r -> 60f * (((g - b) / span) % 6f)
            g -> 60f * ((b - r) / span + 2f)
            else -> 60f * ((r - g) / span + 4f)
        }
        return if (h < 0f) h + 360f else h
    }

    private fun r(c: Int) = (c shr 16) and 0xff
    private fun g(c: Int) = (c shr 8) and 0xff
    private fun bl(c: Int) = c and 0xff
}
