// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.controls

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** The keys the gestures press. */
enum class Key { W, S, Q, E, B, M, SPACE, ESCAPE, ENTER }

enum class Button { LEFT, RIGHT, WHEEL_UP, WHEEL_DOWN }

/** What the gestures do in the game. Positions and distances are in the touch screen's pixels. */
interface GestureOutput {
    /** Puts the pointer where the screen was touched. */
    fun pointerTo(x: Float, y: Float)

    /**
     * A tap: a click there, as WoW's mouse would do it (the screen decides between a plain click
     * and talking, attacking, looting or gathering, by the game's own cursor: [CursorKinds]).
     */
    fun tapAt(x: Float, y: Float)

    /** A right click there: talk, loot, attack, use. */
    fun interactAt(x: Float, y: Float)

    /**
     * The second tap of a double tap, there: attacks the enemy the first one selected, or jumps
     * (Space) when the first tap wasn't on an enemy.
     */
    fun doubleTapAt(x: Float, y: Float)

    /** Moves the pointer (with the right button held: turns the camera and the character). */
    fun pointerBy(dx: Float, dy: Float)

    fun press(button: Button)
    fun release(button: Button)

    /** Presses and releases. */
    fun click(button: Button)

    fun keyDown(key: Key)
    fun keyUp(key: Key)

    /** Presses and releases. */
    fun tap(key: Key)

    /** The player started walking or turning. */
    fun playing()

    /** Two fingers swiped up: open the chat, with the keyboard. */
    fun openChat()

    fun showKeyboard()
    fun showMenu()
}

/**
 * The game's touch controls, split like most phone MMOs (the user's choice, warcraft/PLAN.md
 * "Controls"):
 *
 * - **Left half, swipe and hold:** walks, like a stick under the thumb (nothing drawn): up W,
 *   down S, left Q and right E (sideways steps), and the diagonals between them. It follows a
 *   thumb that goes further.
 * - **Right half, drag:** turns the camera and the character (the right button held down in the
 *   middle of the screen, so it never lands on the action bars or the map).
 * - **Anywhere:** tap = a click where the finger is, which talks to NPCs, loots and gathers where
 *   WoW's cursor offers it, and selects an enemy ([GestureOutput.tapAt]); double tap on an enemy
 *   = attack it, elsewhere = jump (Space) ([GestureOutput.doubleTapAt]); hold still = bags (B);
 *   tap then hold = map (M).
 * - **Two fingers together:** pinch = zoom (the mouse wheel); tap = right click (talk, loot,
 *   attack); swipe up = chat, with the keyboard.
 * - **Three-finger tap:** the keyboard. **Four-finger tap:** the menu.
 *
 * Each finger counts on its own, so the left thumb can walk while the right one turns, and taps
 * with another finger still click. Two fingers that come down together (within [PAIR_MS]) are a
 * two-finger gesture until they show otherwise: a pinch, a swipe up, or each going its own way.
 *
 * Fed the touch screen's events in its pixels, with the time in milliseconds; [onHold] is called
 * by the screen [HOLD_MS] after a finger comes down, and [setSize] when its size changes.
 */
class TouchGestures(private val out: GestureOutput, density: Float) {
    companion object {
        const val HOLD_MS = 450L
        /** Fingers of a two- to four-finger tap land and lift a little apart. */
        const val MULTI_TAP_MS = 400L
        const val DOUBLE_TAP_MS = 300L
        /** Two fingers this close in time are one gesture. */
        const val PAIR_MS = 200L

        private const val TAP_SLOP_DP = 12f
        private const val SWIPE_START_DP = 20f
        private const val DEAD_ZONE_DP = 12f
        private const val STICK_RADIUS_DP = 70f
        private const val DOUBLE_TAP_SLOP_DP = 64f
        private const val PINCH_START_DP = 24f
        /** How far the fingers spread or close for one notch of the wheel. */
        private const val ZOOM_STEP_DP = 28f

        /** Where the camera drag holds the right button: the middle of the world, under the UI. */
        const val LOOK_X = 0.5f
        const val LOOK_Y = 0.4f
    }

    private enum class Role { NEW, STICK, LOOK, HELD, PAIR, GROUP, DONE }

    private class Finger(val id: Int, val startX: Float, val startY: Float, val downAt: Long, val left: Boolean, val secondTap: Boolean) {
        var x = startX
        var y = startY
        var moved = false
        var role = Role.NEW
        fun travel() = hypot(x - startX, y - startY)
    }

    private enum class PairMode { WAITING, PINCH, SWIPED }

    private class Pair(val a: Finger, val b: Finger) {
        var mode = PairMode.WAITING
        val startDistance = distance()
        val startMidX = (a.x + b.x) / 2
        val startMidY = (a.y + b.y) / 2
        var zoomBase = 0f
        var allTaps = true
        fun distance() = hypot(a.x - b.x, a.y - b.y)

        /**
         * Each finger that moved went along the line between where the two came down (closer or
         * apart), as in a pinch; not across it, as a thumb walking up while the other turns does.
         */
        fun alongAxis(slop: Float): Boolean {
            val ax = b.startX - a.startX
            val ay = b.startY - a.startY
            val length = hypot(ax, ay).takeIf { it > 0f } ?: return false
            return listOf(a, b).all { f ->
                val dx = f.x - f.startX
                val dy = f.y - f.startY
                if (hypot(dx, dy) <= slop) return@all true
                val along = abs(dx * ax + dy * ay) / length
                val across = abs(dx * ay - dy * ax) / length
                along >= 2 * across
            }
        }
    }

    private val tapSlop = TAP_SLOP_DP * density
    private val swipeStart = SWIPE_START_DP * density
    private val deadZone = DEAD_ZONE_DP * density
    private val stickRadius = STICK_RADIUS_DP * density
    private val doubleTapSlop = DOUBLE_TAP_SLOP_DP * density
    private val pinchStart = PINCH_START_DP * density
    private val zoomStep = ZOOM_STEP_DP * density

    private var width = 0f
    private var height = 0f

    private val fingers = LinkedHashMap<Int, Finger>()

    private var lastTapAt = Long.MIN_VALUE / 2
    private var lastTapX = 0f
    private var lastTapY = 0f

    private var stickId = -1
    private var anchorX = 0f
    private var anchorY = 0f
    private val walking = mutableSetOf<Key>()

    private var lookId = -1
    private var lookX = 0f
    private var lookY = 0f

    private var pair: Pair? = null

    private var groupSize = 0
    private var groupAllTaps = true

    fun setSize(width: Float, height: Float) {
        this.width = width
        this.height = height
    }

    fun down(id: Int, x: Float, y: Float, now: Long) {
        val secondTap = now - lastTapAt <= DOUBLE_TAP_MS && hypot(x - lastTapX, y - lastTapY) <= doubleTapSlop
        val finger = Finger(id, x, y, now, left = x < width / 2, secondTap = secondTap)
        val waitingPair = pair?.takeIf { it.mode == PairMode.WAITING }
        val partner = fingers.values.firstOrNull { it.role == Role.NEW && !it.moved && now - it.downAt <= PAIR_MS }
        fingers[id] = finger
        when {
            // A third or fourth finger with a pair that hasn't done anything yet: a group tap.
            waitingPair != null || groupSize > 0 -> {
                if (waitingPair != null) {
                    waitingPair.a.role = Role.GROUP
                    waitingPair.b.role = Role.GROUP
                    groupSize = 2
                    groupAllTaps = waitingPair.allTaps
                    pair = null
                }
                finger.role = Role.GROUP
                groupSize++
            }
            pair != null -> finger.role = Role.DONE
            partner != null -> {
                partner.role = Role.PAIR
                finger.role = Role.PAIR
                pair = Pair(partner, finger)
            }
        }
    }

    fun move(id: Int, x: Float, y: Float) {
        val finger = fingers[id] ?: return
        finger.x = x
        finger.y = y
        if (!finger.moved && finger.travel() > tapSlop) finger.moved = true
        when (finger.role) {
            Role.NEW -> if (finger.travel() >= swipeStart) startOwn(finger)
            Role.STICK -> steer(finger)
            Role.LOOK -> look(finger)
            Role.PAIR -> pairMoved(finger)
            Role.GROUP -> if (finger.moved) groupAllTaps = false
            Role.HELD, Role.DONE -> {}
        }
    }

    fun up(id: Int, x: Float, y: Float, now: Long) {
        val finger = fingers[id] ?: return
        move(id, x, y)
        fingers.remove(id)
        val inGesture = finger.role == Role.PAIR || finger.role == Role.GROUP
        val tap = !finger.moved && now - finger.downAt <= if (inGesture) MULTI_TAP_MS else HOLD_MS
        when (finger.role) {
            Role.NEW -> if (tap) {
                if (finger.secondTap) {
                    out.doubleTapAt(finger.x, finger.y)
                    lastTapAt = Long.MIN_VALUE / 2
                } else {
                    out.tapAt(finger.x, finger.y)
                    lastTapAt = now
                    lastTapX = finger.x
                    lastTapY = finger.y
                }
            }
            Role.STICK -> {
                walk(emptySet())
                stickId = -1
            }
            Role.LOOK -> {
                out.release(Button.RIGHT)
                lookId = -1
            }
            Role.PAIR -> pairUp(finger, tap)
            Role.GROUP -> {
                if (!tap) groupAllTaps = false
                if (fingers.values.none { it.role == Role.GROUP }) {
                    if (groupAllTaps) {
                        if (groupSize == 3) out.showKeyboard() else out.showMenu()
                    }
                    groupSize = 0
                }
            }
            Role.HELD, Role.DONE -> {}
        }
    }

    /** [HOLD_MS] after a finger came down: one still there, not moved and on its own, is a hold. */
    fun onHold(now: Long) {
        for (finger in fingers.values) {
            if (finger.role != Role.NEW || finger.moved || now - finger.downAt < HOLD_MS) continue
            out.tap(if (finger.secondTap) Key.M else Key.B)
            finger.role = Role.HELD
        }
    }

    /** The screen lost the touches (another window, the screen off): lets go of everything. */
    fun cancel() {
        walk(emptySet())
        if (lookId != -1) out.release(Button.RIGHT)
        stickId = -1
        lookId = -1
        pair = null
        groupSize = 0
        fingers.clear()
    }

    /** A finger on its own that moved: the stick on the left, the camera on the right. */
    private fun startOwn(finger: Finger) {
        when {
            finger.left && stickId == -1 -> {
                finger.role = Role.STICK
                stickId = finger.id
                anchorX = finger.startX
                anchorY = finger.startY
                out.playing()
                steer(finger)
            }
            !finger.left && lookId == -1 -> {
                finger.role = Role.LOOK
                lookId = finger.id
                out.playing()
                out.pointerTo(width * LOOK_X, height * LOOK_Y)
                out.press(Button.RIGHT)
                lookX = finger.x
                lookY = finger.y
            }
            else -> finger.role = Role.DONE
        }
    }

    private fun look(finger: Finger) {
        val dx = finger.x - lookX
        val dy = finger.y - lookY
        if (dx != 0f || dy != 0f) out.pointerBy(dx, dy)
        lookX = finger.x
        lookY = finger.y
    }

    private fun pairMoved(finger: Finger) {
        val p = pair ?: return
        if (finger.moved) p.allTaps = false
        // One of the two already lifted: the other goes its own way.
        if (p.a.id !in fingers || p.b.id !in fingers) {
            if (finger.moved) {
                pair = null
                finger.role = Role.NEW
                if (finger.travel() >= swipeStart) startOwn(finger)
            }
            return
        }
        when (p.mode) {
            PairMode.WAITING -> {
                val spread = p.distance() - p.startDistance
                val midX = (p.a.x + p.b.x) / 2
                val midY = (p.a.y + p.b.y) / 2
                val shift = hypot(midX - p.startMidX, midY - p.startMidY)
                val bothUp = p.a.y - p.a.startY < -swipeStart / 2 && p.b.y - p.b.startY < -swipeStart / 2
                // Each finger still, or going mostly up: it may yet be a swipe up.
                val mayBeSwipe = listOf(p.a, p.b).all {
                    it.travel() <= tapSlop || (it.y < it.startY && abs(it.x - it.startX) <= it.startY - it.y)
                }
                val most = max(p.a.travel(), p.b.travel())
                when {
                    abs(spread) >= pinchStart && abs(spread) >= shift && p.alongAxis(tapSlop) -> {
                        p.mode = PairMode.PINCH
                        p.zoomBase = p.startDistance
                        out.pointerTo(width * LOOK_X, height * LOOK_Y)
                        zoom(p)
                    }
                    shift >= swipeStart * 2 && bothUp && abs(spread) < shift / 2 && abs(midX - p.startMidX) < shift / 2 -> {
                        p.mode = PairMode.SWIPED
                        out.openChat()
                    }
                    // Not together after all, each finger on its own: soon when each thumb moves its
                    // own way, otherwise once it's clearly neither a pinch nor a swipe up.
                    most >= swipeStart * (if (mayBeSwipe) 3 else 2) || (most >= swipeStart && !mayBeSwipe && !p.alongAxis(tapSlop)) -> {
                        pair = null
                        for (each in listOf(p.a, p.b)) {
                            each.role = Role.NEW
                            if (each.travel() >= swipeStart) startOwn(each)
                        }
                    }
                }
            }
            PairMode.PINCH -> zoom(p)
            PairMode.SWIPED -> {}
        }
    }

    /** A notch of the wheel for each [zoomStep] the fingers spread (in) or close (out). */
    private fun zoom(p: Pair) {
        val distance = p.distance()
        while (distance - p.zoomBase >= zoomStep) {
            out.click(Button.WHEEL_UP)
            p.zoomBase += zoomStep
        }
        while (p.zoomBase - distance >= zoomStep) {
            out.click(Button.WHEEL_DOWN)
            p.zoomBase -= zoomStep
        }
    }

    private fun pairUp(finger: Finger, tap: Boolean) {
        val p = pair ?: return
        if (!tap) p.allTaps = false
        val other = if (p.a === finger) p.b else p.a
        if (other.id in fingers) {
            // The first of the two to lift. A pinch or a swipe is over; a tap waits for the other.
            if (p.mode != PairMode.WAITING || !p.allTaps) {
                pair = null
                other.role = Role.DONE
            }
            return
        }
        if (p.mode == PairMode.WAITING && p.allTaps) out.interactAt(p.a.startX, p.a.startY)
        pair = null
    }

    /** The keys for the thumb's direction from the stick's base, which follows a thumb that goes further. */
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

/** The keys for a direction on the screen (y down), in eight directions: W/S forward and back, Q/E sideways. */
fun directionKeys(dx: Float, dy: Float): Set<Key> {
    val sector = ((atan2(dy.toDouble(), dx.toDouble()) / (PI / 4)).roundToInt() + 8) % 8
    return when (sector) {
        0 -> setOf(Key.E)
        1 -> setOf(Key.S, Key.E)
        2 -> setOf(Key.S)
        3 -> setOf(Key.S, Key.Q)
        4 -> setOf(Key.Q)
        5 -> setOf(Key.W, Key.Q)
        6 -> setOf(Key.W)
        else -> setOf(Key.W, Key.E)
    }
}
