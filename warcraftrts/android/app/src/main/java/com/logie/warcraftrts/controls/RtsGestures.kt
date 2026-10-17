// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraftrts.controls

import kotlin.math.abs
import kotlin.math.hypot

/** A mouse button the gestures press. */
enum class Button { LEFT, RIGHT }

/** A key the gestures press. */
enum class Key { LEFT, RIGHT, UP, DOWN, ESCAPE, ENTER }

/** What the gestures do, in the game: the game screen ([com.logie.warcraftrts.game.GestureView]) does it. */
interface GestureOutput {
    /** The mouse pointer to this point of the screen (the view's pixels). */
    fun pointerTo(x: Float, y: Float)
    fun press(button: Button)
    fun release(button: Button)
    fun click(button: Button)
    /** One notch of the mouse wheel ([up]: away from you, which zooms in). */
    fun wheel(up: Boolean)
    fun keyDown(key: Key)
    fun keyUp(key: Key)
    /** Shows or hides the quick keys (control groups, Shift, heroes...: the game screen's [com.logie.warcraftrts.game.QuickKeys]). */
    fun toggleQuickKeys()
    /** The app's own menu over the game. */
    fun showMenu()
    /** A short vibration: a hold was recognised. */
    fun feedback()
}

/**
 * The touch controls of a real-time strategy game, the way a mouse plays it, with nothing drawn on
 * the screen. Plain Kotlin (no Android types), fed touch events by the game screen and unit-tested:
 *
 * - **Tap**: left click there (select a unit or building, press a button of the game's panel). Two
 *   quick taps are the game's double click (all units of that type).
 * - **Touch and hold** ([HOLD_MS]): right click there (move, attack, gather, set a rally point).
 * - **Drag one finger**: the left button held from where it landed (a selection box, a slider).
 * - **Two-finger tap**: right click where the first finger landed.
 * - **Two fingers dragged together**: the camera scrolls (the arrow keys), the way a map follows the
 *   finger; it keeps scrolling while the fingers stay away from where they landed.
 * - **Pinch**: the mouse wheel (zoom where the game has it).
 * - **Three-finger tap**: the quick keys, for what a keyboard does in these games (control groups,
 *   Shift to queue orders, heroes, chat): [GestureOutput.toggleQuickKeys].
 * - **Four-finger tap**: the app's menu.
 */
class RtsGestures(private val out: GestureOutput, density: Float) {
    companion object {
        /** A finger that stays this long without moving is a hold (right click). */
        const val HOLD_MS = 450L
        /** Fingers that land and lift within this long without moving are a multi-finger tap. */
        const val MULTI_TAP_MS = 500L
    }

    private val slop = 10f * density
    private val panDeadZone = 18f * density
    private val pinchStep = 48f * density

    private class Finger(val startX: Float, val startY: Float) {
        var x = startX
        var y = startY
    }

    private enum class Mode { IDLE, PENDING, DRAG, HELD, MULTI }

    private enum class Multi { UNDECIDED, PAN, PINCH }

    private val fingers = LinkedHashMap<Int, Finger>()
    private var mode = Mode.IDLE
    private var downAt = 0L
    private var firstX = 0f
    private var firstY = 0f
    private var maxFingers = 0

    private var multi = Multi.UNDECIDED
    private var multiMoved = false
    private var baseCx = 0f
    private var baseCy = 0f
    private var baseSpread = 0f
    private var wheelSteps = 0
    private val heldKeys = mutableSetOf<Key>()

    /** The screen's size, in the view's pixels (unused for now: every gesture works anywhere). */
    fun setSize(width: Float, height: Float) {}

    fun down(id: Int, x: Float, y: Float, now: Long) {
        if (fingers.isEmpty()) {
            fingers[id] = Finger(x, y)
            mode = Mode.PENDING
            downAt = now
            firstX = x
            firstY = y
            maxFingers = 1
            multiMoved = false
            return
        }
        fingers[id] = Finger(x, y)
        maxFingers = maxOf(maxFingers, fingers.size)
        when (mode) {
            Mode.DRAG -> {
                out.release(Button.LEFT)
                multiMoved = true
            }
            // A hold already right-clicked: whatever follows isn't a tap.
            Mode.HELD -> multiMoved = true
            else -> {}
        }
        mode = Mode.MULTI
        startMulti()
    }

    /** A finger where it is now. */
    data class Touch(val id: Int, val x: Float, val y: Float)

    fun move(id: Int, x: Float, y: Float) = move(listOf(Touch(id, x, y)))

    /** Every finger of one touch event moved: all of them first, then what that means. */
    fun move(touches: List<Touch>) {
        for (touch in touches) {
            val finger = fingers[touch.id] ?: continue
            finger.x = touch.x
            finger.y = touch.y
        }
        when (mode) {
            Mode.PENDING -> {
                val finger = fingers.values.firstOrNull() ?: return
                if (hypot(finger.x - finger.startX, finger.y - finger.startY) > slop) {
                    mode = Mode.DRAG
                    out.pointerTo(finger.startX, finger.startY)
                    out.press(Button.LEFT)
                    out.pointerTo(finger.x, finger.y)
                }
            }
            Mode.DRAG -> fingers.values.firstOrNull()?.let { out.pointerTo(it.x, it.y) }
            Mode.MULTI -> moveMulti()
            Mode.HELD, Mode.IDLE -> {}
        }
    }

    fun up(id: Int, x: Float, y: Float, now: Long) {
        val finger = fingers.remove(id) ?: return
        finger.x = x
        finger.y = y
        if (fingers.isNotEmpty()) {
            if (mode == Mode.MULTI) startMulti()
            return
        }
        when (mode) {
            Mode.PENDING -> {
                out.pointerTo(finger.startX, finger.startY)
                out.click(Button.LEFT)
            }
            Mode.DRAG -> {
                out.pointerTo(x, y)
                out.release(Button.LEFT)
            }
            Mode.MULTI -> {
                releaseKeys()
                if (!multiMoved && now - downAt <= MULTI_TAP_MS) {
                    when {
                        maxFingers >= 4 -> out.showMenu()
                        maxFingers == 3 -> out.toggleQuickKeys()
                        else -> {
                            out.pointerTo(firstX, firstY)
                            out.click(Button.RIGHT)
                        }
                    }
                }
            }
            Mode.HELD, Mode.IDLE -> {}
        }
        mode = Mode.IDLE
    }

    /** [HOLD_MS] after a finger landed (the game screen calls it then). */
    fun onHold(now: Long) {
        if (mode != Mode.PENDING || fingers.size != 1 || now - downAt < HOLD_MS) return
        val finger = fingers.values.first()
        mode = Mode.HELD
        out.pointerTo(finger.startX, finger.startY)
        out.click(Button.RIGHT)
        out.feedback()
    }

    /** The touch was taken away (another window, the system): lets go of everything. */
    fun cancel() {
        if (mode == Mode.DRAG) out.release(Button.LEFT)
        releaseKeys()
        fingers.clear()
        mode = Mode.IDLE
    }

    // Two fingers or more ----------------------------------------------------------------------

    /** Where the fingers are now is where pans and pinches count from (again, with a finger more or less). */
    private fun startMulti() {
        releaseKeys()
        baseCx = fingers.values.map { it.x }.average().toFloat()
        baseCy = fingers.values.map { it.y }.average().toFloat()
        baseSpread = spread()
        wheelSteps = 0
        multi = Multi.UNDECIDED
    }

    private fun spread(): Float {
        if (fingers.size < 2) return 0f
        val (a, b) = fingers.values.take(2)
        return hypot(a.x - b.x, a.y - b.y)
    }

    private fun moveMulti() {
        val cx = fingers.values.map { it.x }.average().toFloat()
        val cy = fingers.values.map { it.y }.average().toFloat()
        val dx = cx - baseCx
        val dy = cy - baseCy
        if (fingers.size != 2) {
            if (hypot(dx, dy) > slop * 2) multiMoved = true
            return
        }
        val stretch = spread() - baseSpread
        if (multi == Multi.UNDECIDED) {
            multi = when {
                abs(stretch) > slop * 2 -> Multi.PINCH
                hypot(dx, dy) > slop * 1.5f -> Multi.PAN
                else -> return
            }
            multiMoved = true
        }
        when (multi) {
            Multi.PINCH -> {
                val steps = (stretch / pinchStep).toInt()
                while (wheelSteps < steps) {
                    out.wheel(up = true)
                    wheelSteps++
                }
                while (wheelSteps > steps) {
                    out.wheel(up = false)
                    wheelSteps--
                }
            }
            Multi.PAN -> holdKeys(panKeys(dx, dy))
            Multi.UNDECIDED -> {}
        }
    }

    /**
     * The arrow keys for fingers [dx], [dy] away from where they landed: the camera goes the other
     * way, as a map does under the finger (fingers to the right show what's to the left).
     */
    private fun panKeys(dx: Float, dy: Float): Set<Key> = buildSet {
        val ax = abs(dx)
        val ay = abs(dy)
        if (ax > panDeadZone && ax > ay * 0.4f) add(if (dx > 0) Key.LEFT else Key.RIGHT)
        if (ay > panDeadZone && ay > ax * 0.4f) add(if (dy > 0) Key.UP else Key.DOWN)
    }

    private fun holdKeys(keys: Set<Key>) {
        for (key in heldKeys - keys) out.keyUp(key)
        for (key in keys - heldKeys) out.keyDown(key)
        heldKeys.clear()
        heldKeys += keys
    }

    private fun releaseKeys() = holdKeys(emptySet())
}
