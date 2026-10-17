// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcrafts.library

/** The games' resolution for the phone's screen. Plain Kotlin, unit-tested. */
object Screen {
    /**
     * In landscape, [lines] high and as wide as the screen's shape, even, but never narrower than
     * 4:3: the Fold's inside screen is nearly square (about 10:9), narrower than the games' screens
     * were ever made for, so there the game is 4:3 with bars at its sides rather than squeezed. At
     * least 800x600, which the games need.
     */
    fun resolution(width: Int, height: Int, lines: Int): Pair<Int, Int> {
        val long = maxOf(width, height).coerceAtLeast(1).toLong()
        val short = minOf(width, height).coerceAtLeast(1).toLong()
        val wide = maxOf(lines * long / short, lines * 4L / 3).toInt() and 1.inv()
        return maxOf(wide, 800) to maxOf(lines, 600)
    }

    /**
     * For a choice of [lines] (null: as many as the screen has), never more than the screen has:
     * on a screen narrower than 4:3, as many lines as a 4:3 picture as wide as the screen.
     */
    fun resolutionFor(width: Int, height: Int, lines: Int?): Pair<Int, Int> {
        val long = maxOf(width, height).coerceAtLeast(1)
        val short = minOf(width, height).coerceAtLeast(1)
        val most = if (long * 3 < short * 4) long * 3 / 4 else short
        return resolution(width, height, minOf(lines ?: most, most) and 1.inv())
    }
}
