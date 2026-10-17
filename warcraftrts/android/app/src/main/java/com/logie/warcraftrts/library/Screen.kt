// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraftrts.library

/** The games' resolution for the phone's screen. Plain Kotlin, unit-tested. */
object Screen {
    /**
     * In landscape, [lines] high and as wide as the screen's shape, even; at least 800x600, which
     * the games need.
     */
    fun resolution(width: Int, height: Int, lines: Int): Pair<Int, Int> {
        val long = maxOf(width, height).coerceAtLeast(1)
        val short = minOf(width, height).coerceAtLeast(1)
        val wide = (lines.toLong() * long / short).toInt() and 1.inv()
        return maxOf(wide, 800) to maxOf(lines, 600)
    }

    /** For a choice of [lines] (null: the screen's own pixels), never more than the screen has. */
    fun resolutionFor(width: Int, height: Int, lines: Int?): Pair<Int, Int> {
        val short = minOf(width, height).coerceAtLeast(1)
        return resolution(width, height, minOf(lines ?: short, short) and 1.inv())
    }
}
