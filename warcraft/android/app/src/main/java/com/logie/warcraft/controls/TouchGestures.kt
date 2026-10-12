// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.controls

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The keys the gestures press. */
enum class Key { W, A, S, D, B, M, SPACE, ESCAPE }

enum class Button { LEFT, RIGHT }

/** What the gestures do in the game. Positions and distances are in the touch screen's pixels. */
interface GestureOutput {
    /** Puts the pointer where the screen was touched. */
    fun pointerTo(x: Float, y: Float)

    /** Moves the pointer (with a button held: turns the camera). */
    fun pointerBy(dx: Float, dy: Float)

    fun press(button: Button)
    fun release(button: Button)

    /** Presses and releases. */
    fun click(button: Button)

    fun keyDown(key: Key)
    fun keyUp(key: Key)

    /** Presses and releases. */
    fun tap(key: Key)

    fun showKeyboard()
    fun showMenu()
}

/**
 * The game's touch controls (the user's, in warcraft/PLAN.md under "Controls"):
 *
 * - **Tap**: left click where the finger is.
 * - **Swipe** (and keep the finger down): walk, with W/A/S/D in eight directions from where the
 *   finger came down, like a stick that follows the finger when it goes further. Lifting stops.
 * - **Hold** one finger still: bags (B).
 * - **Double tap**: jump (Space). The first tap still clicks.
 * - **Tap, then tap and hold**: map (M).
 * - **Tap with a second finger while swiping**: jump while walking.
 * - **Two-finger tap**: right click (talk, loot, attack). **Two-finger drag**: turn the camera and
 *   the character (the right button held while the pointer moves).
 * - **Three-finger tap**: the phone's keyboard (chat). **Four-finger tap**: the game screen's menu.
 *
 * Fed the touch screen's events in its pixels, with the time in milliseconds; [onHold] is called
 * by the screen [HOLD_MS] after a first finger comes down.
 */
class TouchGestures(private val out: GestureOutput, density: Float) {
    companion object {
        const val HOLD_MS = 450L
        const val TAP_MS = 250L
        /** Fingers of a two- to four-finger tap land and lift a little apart. */
        const val MULTI_TAP_MS = 400L
        const val DOUBLE_TAP_MS = 300L

        private const val TAP_SLOP_DP = 12f
        private const val SWIPE_START_DP = 24f
        private const val DEAD_ZONE_DP = 14f
        private const val STICK_RADIUS_DP = 80f
        private const val DOUBLE_TAP_SLOP_DP = 64f
    }

    private enum class Mode { NONE, ONE, HELD, SWIPE, TWO, CAMERA, MANY, IGNORE }

    private class Finger(val startX: Float, val startY: Float, val downAt: Long) {
        var x = startX
        var y = startY
        var moved = false
        fun travel() = hypot(x - startX, y - startY)
    }

    private val tapSlop = TAP_SLOP_DP * density
    private val swipeStart = SWIPE_START_DP * density
    private val deadZone = DEAD_ZONE_DP * density
    private val stickRadius = STICK_RADIUS_DP * density
    private val doubleTapSlop = DOUBLE_TAP_SLOP_DP * density

    private val fingers = LinkedHashMap<Int, Finger>()
    private var mode = Mode.NONE
    private var mostFingers = 0
    /** Every finger of this gesture so far was a tap (short, still). */
    private var allTaps = true

    private var secondTap = false
    private var lastTapAt = Long.MIN_VALUE / 2
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var swipeId = -1
    private var anchorX = 0f
    private var anchorY = 0f
    private val walking = mutableSetOf<Key>()

    private var cameraX = 0f
    private var cameraY = 0f
    private var rightClickX = 0f
    private var rightClickY = 0f

    fun down(id: Int, x: Float, y: Float, now: Long) {
        val finger = Finger(x, y, now)
        fingers[id] = finger
        if (fingers.size == 1) {
            mode = Mode.ONE
            mostFingers = 1
            allTaps = true
            secondTap = now - lastTapAt <= DOUBLE_TAP_MS && hypot(x - lastTapX, y - lastTapY) <= doubleTapSlop
            return
        }
        mostFingers = maxOf(mostFingers, fingers.size)
        when (mode) {
            // A finger alongside a swipe: a tap with it jumps (when it lifts).
            Mode.SWIPE, Mode.IGNORE -> {}
            Mode.ONE, Mode.HELD -> {
                mode = Mode.TWO
                val first = fingers.values.first()
                rightClickX = first.x
                rightClickY = first.y
            }
            Mode.TWO -> mode = Mode.MANY
            Mode.CAMERA -> {
                out.release(Button.RIGHT)
                mode = Mode.MANY
            }
            Mode.MANY, Mode.NONE -> {}
        }
    }

    fun move(id: Int, x: Float, y: Float) {
        val finger = fingers[id] ?: return
        finger.x = x
        finger.y = y
        if (!finger.moved && finger.travel() > tapSlop) {
            finger.moved = true
            allTaps = false
        }
        when (mode) {
            Mode.ONE -> if (finger.travel() >= swipeStart) {
                mode = Mode.SWIPE
                swipeId = id
                anchorX = finger.startX
                anchorY = finger.startY
                steer(finger)
            }
            Mode.SWIPE -> if (id == swipeId) steer(finger)
            Mode.TWO -> if (finger.moved) {
                mode = Mode.CAMERA
                val (mx, my) = middle()
                out.pointerTo(mx, my)
                out.press(Button.RIGHT)
                cameraX = mx
                cameraY = my
            }
            Mode.CAMERA -> {
                val (mx, my) = middle()
                if (mx != cameraX || my != cameraY) out.pointerBy(mx - cameraX, my - cameraY)
                cameraX = mx
                cameraY = my
            }
            Mode.NONE, Mode.HELD, Mode.MANY, Mode.IGNORE -> {}
        }
    }

    fun up(id: Int, x: Float, y: Float, now: Long) {
        val finger = fingers[id] ?: return
        move(id, x, y)
        fingers.remove(id)
        val tap = !finger.moved && now - finger.downAt <= if (mostFingers > 1) MULTI_TAP_MS else HOLD_MS
        if (!tap) allTaps = false
        when (mode) {
            Mode.ONE -> {
                if (tap && secondTap) {
                    out.tap(Key.SPACE)
                    lastTapAt = Long.MIN_VALUE / 2
                } else if (tap) {
                    out.pointerTo(finger.x, finger.y)
                    out.click(Button.LEFT)
                    lastTapAt = now
                    lastTapX = finger.x
                    lastTapY = finger.y
                }
                mode = Mode.NONE
            }
            Mode.SWIPE -> if (id == swipeId) {
                walk(emptySet())
                mode = if (fingers.isEmpty()) Mode.NONE else Mode.IGNORE
            } else if (tap) {
                out.tap(Key.SPACE)
            }
            Mode.TWO -> if (fingers.isEmpty()) {
                if (allTaps) {
                    out.pointerTo(rightClickX, rightClickY)
                    out.click(Button.RIGHT)
                }
                mode = Mode.NONE
            }
            Mode.CAMERA -> if (fingers.size < 2) {
                out.release(Button.RIGHT)
                mode = if (fingers.isEmpty()) Mode.NONE else Mode.IGNORE
            }
            Mode.MANY -> if (fingers.isEmpty()) {
                if (allTaps) {
                    if (mostFingers == 3) out.showKeyboard() else out.showMenu()
                }
                mode = Mode.NONE
            }
            Mode.HELD, Mode.IGNORE, Mode.NONE -> if (fingers.isEmpty()) mode = Mode.NONE
        }
    }

    /** [HOLD_MS] after a first finger came down: still there, not moved, it's a hold. */
    fun onHold(now: Long) {
        if (mode != Mode.ONE || fingers.size != 1) return
        val finger = fingers.values.first()
        if (finger.moved || now - finger.downAt < HOLD_MS) return
        out.tap(if (secondTap) Key.M else Key.B)
        mode = Mode.HELD
    }

    /** The screen lost the touches (another window, the screen off): lets go of everything. */
    fun cancel() {
        walk(emptySet())
        if (mode == Mode.CAMERA) out.release(Button.RIGHT)
        fingers.clear()
        mode = Mode.NONE
    }

    private fun middle(): Pair<Float, Float> {
        val all = fingers.values.take(2)
        return all.map { it.x }.average().toFloat() to all.map { it.y }.average().toFloat()
    }

    /** The keys for the finger's direction from the anchor, which follows a finger that goes further. */
    private fun steer(finger: Finger) {
        var dx = finger.x - anchorX
        var dy = finger.y - anchorY
        val distance = hypot(dx, dy)
        if (distance > stickRadius) {
            val pull = (distance - stickRadius) / distance
            anchorX += dx * pull
            anchorY += dy * pull
            dx = finger.x - anchorX
            dy = finger.y - anchorY
        }
        walk(if (distance < deadZone) emptySet() else directionKeys(dx, dy))
    }

    private fun walk(keys: Set<Key>) {
        for (key in walking - keys) out.keyUp(key)
        for (key in keys - walking) out.keyDown(key)
        walking.clear()
        walking.addAll(keys)
    }
}

/** W/A/S/D for a direction on the screen (y down), in eight directions. */
fun directionKeys(dx: Float, dy: Float): Set<Key> {
    val sector = ((atan2(dy.toDouble(), dx.toDouble()) / (PI / 4)).roundToInt() + 8) % 8
    return when (sector) {
        0 -> setOf(Key.D)
        1 -> setOf(Key.S, Key.D)
        2 -> setOf(Key.S)
        3 -> setOf(Key.S, Key.A)
        4 -> setOf(Key.A)
        5 -> setOf(Key.W, Key.A)
        6 -> setOf(Key.W)
        else -> setOf(Key.W, Key.D)
    }
}
