// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.controls

/**
 * Reading Warcraft III's command card (the 4×3 grid of buttons at the bottom right) off the screen,
 * to know when the game waits for an order's target. While it does (a spell, attack-move, a
 * building's place), the card shows only Cancel, in its bottom-right slot; otherwise a unit or a
 * building has several buttons. Plain Kotlin, unit-tested: the game screen copies the card's
 * pixels ([area]) and asks [Look.of] and [AimWatch].
 *
 * Where the card is comes from the game's classic 4:3 interface, its height a fifth of the screen's
 * and its width about 0.31 of the height, at the right edge (Warcraft III 1.29 and later keep it
 * there, sized by the height, on wider screens). It's approximate: the reading compares the slots
 * with each other and with the card just before a button was pressed, never with fixed pictures.
 * Not yet checked on the game itself.
 */
object CommandCard {
    const val COLUMNS = 4
    const val ROWS = 3

    /** The card's width and height as parts of the screen's height. */
    const val WIDTH_OF_HEIGHT = 0.31f
    const val HEIGHT_OF_HEIGHT = 0.20f

    /** The card's rectangle on a game screen of [width]×[height]: left, top, right, bottom. */
    fun area(width: Float, height: Float): FloatArray {
        val w = height * WIDTH_OF_HEIGHT
        val h = height * HEIGHT_OF_HEIGHT
        return floatArrayOf(width - w, height - h, width, height)
    }

    fun contains(width: Float, height: Float, x: Float, y: Float): Boolean {
        val (left, top, right, bottom) = area(width, height)
        return x in left..right && y in top..bottom
    }

    /** How bright each of the card's 12 slots is (0–255, row by row from the top left). */
    class Look(val slots: FloatArray) {
        val brightest: Float get() = slots.max()

        /** The slots with a button in them, by [threshold]. */
        fun filled(threshold: Float): Int = slots.count { it > threshold }

        fun filled(slot: Int, threshold: Float): Boolean = slots[slot] > threshold

        companion object {
            /**
             * The card's look from its pixels ([pixels]: ARGB, [width]×[height], row by row). Each slot
             * by the mean brightness of its middle, away from the frame between slots.
             */
            fun of(pixels: IntArray, width: Int, height: Int): Look {
                val slots = FloatArray(COLUMNS * ROWS)
                for (row in 0 until ROWS) {
                    for (column in 0 until COLUMNS) {
                        val x0 = width * column / COLUMNS
                        val x1 = width * (column + 1) / COLUMNS
                        val y0 = height * row / ROWS
                        val y1 = height * (row + 1) / ROWS
                        val insetX = (x1 - x0) / 5
                        val insetY = (y1 - y0) / 5
                        var sum = 0L
                        var count = 0
                        for (y in y0 + insetY until y1 - insetY) {
                            for (x in x0 + insetX until x1 - insetX) {
                                val p = pixels[y * width + x]
                                val r = p shr 16 and 0xff
                                val g = p shr 8 and 0xff
                                val b = p and 0xff
                                sum += (r * 299 + g * 587 + b * 114) / 1000
                                count++
                            }
                        }
                        slots[row * COLUMNS + column] = if (count == 0) 0f else sum.toFloat() / count
                    }
                }
                return Look(slots)
            }
        }
    }

    /** The bottom-right slot: Cancel, while the game waits for a target. */
    const val CANCEL_SLOT = COLUMNS * ROWS - 1
}

/**
 * Whether Warcraft III waits for an order's target, from the command card before and after a
 * button of it was pressed, then from looks at it every so often while it waits.
 */
class AimWatch {
    companion object {
        /** No card darker than this has buttons to tell apart. */
        const val MIN_BRIGHTEST = 30f
        /** A slot with a button is at least this part as bright as the card's brightest. */
        const val FILLED_PART = 0.5f
        /** A card with this many buttons or more isn't waiting for a target. */
        const val FULL_CARD = 3
        /** Waiting longer than this, the game has probably moved on unseen. */
        const val GIVE_UP_MS = 20_000L
    }

    var aiming = false
        private set
    private var threshold = 0f
    private var since = 0L

    /**
     * A button of the card was pressed: [before] it, the card had buttons, and [after] it only
     * Cancel is left: the game waits for a target.
     */
    fun pressed(before: CommandCard.Look, after: CommandCard.Look, now: Long) {
        val brightest = before.brightest
        if (brightest < MIN_BRIGHTEST) {
            aiming = false
            return
        }
        threshold = brightest * FILLED_PART
        aiming = before.filled(threshold) >= FULL_CARD &&
            after.filled(threshold) <= 2 &&
            after.filled(CommandCard.CANCEL_SLOT, threshold)
        since = now
    }

    /** A look at the card while the game waits: buttons back, or Cancel gone, end it. */
    fun seen(look: CommandCard.Look, now: Long) {
        if (!aiming) return
        if (look.filled(threshold) >= FULL_CARD || !look.filled(CommandCard.CANCEL_SLOT, threshold) ||
            now - since > GIVE_UP_MS
        ) {
            aiming = false
        }
    }

    /** The target was given (a left click on the battlefield) or the order cancelled (Escape). */
    fun done() {
        aiming = false
    }
}
