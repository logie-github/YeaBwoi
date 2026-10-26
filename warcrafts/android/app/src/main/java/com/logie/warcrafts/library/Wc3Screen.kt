// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

import kotlin.math.abs

/**
 * What Warcraft III shows, read from its picture, for the touch controls to know what a tap is:
 * a left click on its menus and interface, a right click on the battlefield.
 *
 * - **In a match** the game draws its console (the stone panel along the bottom with the minimap,
 *   portrait and command buttons, the bar along the top), the same way every time: its art is in
 *   the game's files ([Wc3ScreenData], from `ConsoleUI.fdf` and the race's console textures). [race]
 *   finds it by comparing the colours of blocks of its frame, light and dark ones (so a plain
 *   screen can't pass), with what they are for each race. No console: the game's menus, a loading
 *   or score screen, a cinematic.
 * - **On the console** ([onConsole]): below where its panel starts at that point of the width, or
 *   in the top bar.
 * - **Under a dialog** (the F10 menu, the quest log): in single player the game pauses, so the
 *   battlefield stops changing; [still] tells two readings of it apart.
 *
 * [Sampler] gives the average colour around a point of the picture (fractions of its width and
 * height), or null where there's no picture.
 */
object Wc3Screen {
    fun interface Sampler {
        fun colour(x: Float, y: Float): Int?
    }

    /** How far (summed over red, green and blue) a block may be from the console's colour. */
    private const val TOLERANCE = 40
    /** The share of the light blocks, and of the dark ones, that must match. */
    private const val NEEDED = 0.8f
    private const val DARK = 45
    /** The top bar: 0.032 of the 0.6 the game's interface is tall. */
    const val TOP_BAR = 0.032f / 0.6f

    /** The console's race ("Human", "Orc", "NightElf", "Undead") if the game is showing it, or null. */
    fun race(sampler: Sampler): String? {
        var best: String? = null
        var bestScore = 0f
        for ((race, blocks) in Wc3ScreenData.CONSOLES) {
            var light = 0
            var lightHit = 0
            var dark = 0
            var darkHit = 0
            for ((x, y, expected) in blocks) {
                val isDark = brightness(expected) < DARK
                if (isDark) dark++ else light++
                val got = sampler.colour(x, y) ?: return null
                if (distance(got, expected) < TOLERANCE) {
                    if (isDark) darkHit++ else lightHit++
                }
            }
            val score = minOf(if (light > 0) lightHit.toFloat() / light else 1f, if (dark > 0) darkHit.toFloat() / dark else 1f)
            if (score > bestScore) {
                bestScore = score
                best = race
            }
        }
        return if (bestScore >= NEEDED) best else null
    }

    /** Whether ([x], [y]) is on the console of [race]: its top bar, or its panel. */
    fun onConsole(race: String, x: Float, y: Float): Boolean {
        if (y <= TOP_BAR) return true
        val tops = Wc3ScreenData.PANEL_TOP[race] ?: return y >= 0.76f
        val column = (x * tops.size).toInt().coerceIn(0, tops.size - 1)
        return y >= tops[column]
    }

    /**
     * The hero buttons down the left edge (below the top bar) and the idle-worker button above the
     * minimap: left clicks, though they're over the battlefield.
     */
    fun onHeroButtons(x: Float, y: Float): Boolean = x < 0.055f && y > TOP_BAR && y < 0.8f

    /** The command buttons (the grid at the bottom right) and the inventory: what may wait for a target. */
    fun onOrders(x: Float, y: Float): Boolean = onCommandCard(x, y) || onInventory(x, y)

    /** The hero's or unit's inventory: the six slots left of the command buttons. */
    fun onInventory(x: Float, y: Float): Boolean = x in 0.64f..0.74f && y >= 0.80f

    /** The command buttons alone (the grid at the bottom right, 4 across and 3 down), not the inventory. */
    fun onCommandCard(x: Float, y: Float): Boolean = x in 0.765f..0.99f && y >= 0.74f

    /**
     * The command card's 12 buttons, row by row from the top left: their centres. The grid of
     * [onCommandCard] (0.612 to 0.792 across the interface's 0.8, 0.444 to 0.594 down its 0.6) in
     * 4 columns of 0.045 and 3 rows of 0.05.
     */
    val ORDER_SLOTS: List<Pair<Float, Float>> = buildList {
        for (row in 0 until 3) for (column in 0 until 4) {
            add((0.6345f + 0.045f * column) / 0.8f to (0.469f + 0.05f * row) / 0.6f)
        }
    }

    /** Where the command card is read: three points across the middle of each of [ORDER_SLOTS]. */
    val ORDER_POINTS: List<Pair<Float, Float>> =
        ORDER_SLOTS.flatMap { (x, y) -> listOf((x - 0.012f) to y, x to y, (x + 0.012f) to y) }

    /** The command button nearest to ([x], [y]): its index in [ORDER_SLOTS]. */
    fun orderSlot(x: Float, y: Float): Int =
        ORDER_SLOTS.indices.minBy { val (sx, sy) = ORDER_SLOTS[it]; (sx - x) * (sx - x) + (sy - y) * (sy - y) }

    /** A slot with no button: dark at all three of its points. */
    private const val EMPTY_SLOT = 120
    /** How far (summed over red, green and blue) a button's point may change and still be the same button. */
    private const val CARD_TOLERANCE = 60

    private fun slotEmpty(points: List<Int>, slot: Int) = (0 until 3).all { brightness(points[slot * 3 + it]) < EMPTY_SLOT }

    /**
     * Whether the command card (read at [ORDER_POINTS]) shows the game waiting for a target: in
     * Warcraft III an order that needs one (Move, Attack, Patrol, a spell, a building's place)
     * empties the card but for Cancel in its bottom-right corner.
     */
    fun targeting(points: List<Int>?): Boolean {
        if (points == null || points.size != ORDER_POINTS.size) return false
        val cancel = ORDER_SLOTS.size - 1
        val empty = (0 until cancel).count { slotEmpty(points, it) }
        return empty >= cancel - 1 && !slotEmpty(points, cancel)
    }

    /**
     * Whether two readings of the command card show the same buttons: the same units selected,
     * not another unit's card, a submenu (Build) or a wait for a target. A slot or two may differ
     * (a button greyed for its cooldown, a hotkey's highlight).
     */
    fun sameCard(a: List<Int>?, b: List<Int>?): Boolean {
        if (a == null || b == null || a.size != ORDER_POINTS.size || b.size != ORDER_POINTS.size) return false
        if (ORDER_SLOTS.indices.all { slotEmpty(a, it) }) return false
        val same = ORDER_SLOTS.indices.count { slot ->
            (0 until 3).all { distance(a[slot * 3 + it], b[slot * 3 + it]) <= CARD_TOLERANCE }
        }
        return same >= ORDER_SLOTS.size - 2
    }

    /** The minimap (measured from the console's art: the same for every race). */
    fun onMinimap(x: Float, y: Float): Boolean = x in 0.013f..0.185f && y in 0.803f..0.986f

    /** Where to read the battlefield, to tell whether it moves: a grid over the part above the console. */
    val FIELD: List<Pair<Float, Float>> = buildList {
        for (row in 0 until 4) for (column in 0 until 6) add((0.08f + column * 0.168f) to (0.12f + row * 0.15f))
    }

    /**
     * Whether two readings of [FIELD] show a battlefield that didn't move, as when the game is
     * paused under a dialog. A field that is all near black (unexplored ground, a black screen)
     * doesn't count: a dialog over it would show.
     */
    fun still(before: List<Int>?, now: List<Int>?): Boolean {
        if (before == null || now == null || before.size != now.size) return false
        if (now.all { brightness(it) < DARK }) return false
        return before.indices.all { distance(before[it], now[it]) <= 3 }
    }

    /** Whether every colour is (nearly) black. */
    fun black(colours: List<Int>): Boolean = colours.isNotEmpty() && colours.all { brightness(it) < 24 }

    private fun channel(colour: Int, shift: Int) = (colour shr shift) and 0xff

    private fun brightness(colour: Int) = channel(colour, 16) + channel(colour, 8) + channel(colour, 0)

    private fun distance(a: Int, b: Int) =
        abs(channel(a, 16) - channel(b, 16)) + abs(channel(a, 8) - channel(b, 8)) + abs(channel(a, 0) - channel(b, 0))
}
