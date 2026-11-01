// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.controls

import kotlin.math.abs
import kotlin.math.hypot

/** A mouse button the gestures click. */
enum class Button { LEFT, RIGHT }

/** A key the gestures hold. */
enum class Key { LEFT, RIGHT, UP, DOWN, SHIFT }

/** What the gestures do, in the game: the game screen ([com.logie.warcrafts.game.GestureView]) does it. */
interface GestureOutput {
    /** The mouse pointer to this point of the screen (the view's pixels). */
    fun pointerTo(x: Float, y: Float)
    fun click(button: Button)
    /** Two clicks in quick succession: the game's double click. */
    fun doubleClick(button: Button)
    /** One notch of the mouse wheel ([up]: away from you, which zooms in). */
    fun wheel(up: Boolean)
    fun keyDown(key: Key)
    fun keyUp(key: Key)
    /** The game's own menu (F10 in Warcraft I, II and III). */
    fun gameMenu()
    /** The phone's keyboard, for a chat line or a cheat code (Enter, the text, Enter). */
    fun openKeyboard()
    /** The app's own menu over the game. */
    fun showMenu()
    /** A short vibration: a hold was recognised. */
    fun feedback()

    /** A mouse button pressed where the pointer is, and held (a selection box's start). */
    fun press(button: Button) {}
    /** The button [press]ed let go where the pointer is now (the box's other corner). */
    fun release(button: Button) {}

    /**
     * The game is waiting for an order's target (a spell, attack-move, a building's place): a tap
     * is the left click that gives it, and a hold that drags aims instead of drawing a box.
     */
    fun aiming(): Boolean = false

    /** A second tap on the same spot: attack-move there, if the game screen does it (true). */
    fun attackMove(): Boolean = false

    /** Two more fingers tapped while one rests: the selection's next kind of unit (Tab). */
    fun nextSubgroup() {}

    /** A finger is held here: the magnifier shows what's under it. */
    fun magnify(x: Float, y: Float) {}
    /** No finger is held any more: the magnifier goes. */
    fun magnifyOff() {}
}

/**
 * The touch controls the user chose for these games, with nothing drawn on the screen but a moment's
 * marker and, during a hold, a magnifier. Plain Kotlin (no Android types), fed touch events by the
 * game screen and unit-tested:
 *
 * - **Swipe** one finger: the map pans the way the finger goes (the arrow keys), for as long as the
 *   finger stays away from where it landed.
 * - **Tap**: right click there (move, attack, gather, set a rally point). Where a right click does
 *   nothing, the games make it a left click: in Warcraft I and II the engine does (menus, buttons,
 *   nothing of the player's selected: patches/stratagus), in Warcraft III the game screen does for
 *   its bottom panel, top bar and hero portraits ([com.logie.warcrafts.game.GestureView]). While
 *   the game waits for an order's target ([GestureOutput.aiming]), a tap is the left click.
 * - **Double tap** on the same spot: attack-move there ([GestureOutput.attackMove]).
 * - **Touch and hold** ([holdMs], a buzz): left click there when the finger lifts (select a unit,
 *   press a button of the game's panel or menus). Two holds in a row on the same spot: a double
 *   click (every unit of that kind on the screen).
 * - **Hold, then drag**: a selection box from where the finger landed to where it lifts; while the
 *   game waits for a target, the pointer follows the finger instead and clicks where it lifts.
 * - **A finger resting** while another taps or holds: those are Shift-clicks (queue orders, add to
 *   the selection), and a swipe of the other finger is a Shift box (adds what it holds). Two more
 *   fingers tapping together while one rests: Tab, the selection's next kind of unit.
 * - **Two-finger tap**: the game's menu. **Pinch**: zoom (the mouse wheel).
 * - **Three-finger tap**: the phone's keyboard. **Four-finger tap**: the app's menu.
 */
class RtsGestures(
    private val out: GestureOutput,
    density: Float,
    /** A finger that stays this long without moving is a hold (left click). */
    val holdMs: Long = HOLD_MS,
    /** How far (dp) a swipe goes before the map pans. */
    panStartDp: Float = PAN_START_DP,
) {
    companion object {
        /** The hold's time unless the launcher's Controls say otherwise. */
        const val HOLD_MS = 400L
        /** The swipe's distance (dp) before the map pans, unless the launcher's Controls say otherwise. */
        const val PAN_START_DP = 14f
        /** Fingers that land and lift within this long without moving are a multi-finger tap. */
        const val MULTI_TAP_MS = 500L
        /** A finger down this long before another lands is resting (Shift), not half of a two-finger tap. */
        const val REST_MS = 200L
        /** A second hold this soon after the first one lifted, on the same spot, is a double click. */
        const val DOUBLE_HOLD_MS = 900L
        /** A second tap landing this soon after the first one lifted, on the same spot, is attack-move. */
        const val DOUBLE_TAP_MS = 300L
    }

    private val slop = 10f * density
    private val panDeadZone = panStartDp * density
    private val pinchStep = 48f * density
    private val sameSpot = 40f * density

    private class Finger(val startX: Float, val startY: Float, val downAt: Long) {
        var x = startX
        var y = startY
        var held = false
        var moved = false
    }

    private enum class Mode { IDLE, PENDING, HELD, PAN, BOX, AIM, MULTI, SHIFT, SUBGROUP, DONE }

    private enum class Multi { UNDECIDED, MOVED, PINCH }

    private val fingers = LinkedHashMap<Int, Finger>()
    private var mode = Mode.IDLE
    private var maxFingers = 0
    private var firstDownAt = 0L

    private var multi = Multi.UNDECIDED
    private var baseCx = 0f
    private var baseCy = 0f
    private var baseSpread = 0f
    private var wheelSteps = 0
    private val heldKeys = mutableSetOf<Key>()

    /** The finger tapping, holding, boxing or aiming (in Shift mode: the one that doesn't rest). */
    private var actor: Int? = null
    /** In Shift mode: the resting finger. */
    private var rester: Int? = null
    /** The left button is down for a selection box. */
    private var boxing = false
    /** Shift mode's two-finger tap (Tab): when it started, and whether a finger of it moved. */
    private var subgroupAt = 0L
    private var subgroupMoved = false
    private var magnifying = false

    /** The last hold's left click: where and when, for a double click. */
    private var lastHoldAt = Long.MIN_VALUE / 2
    private var lastHoldX = 0f
    private var lastHoldY = 0f

    /** The last tap's right click: where and when, for attack-move. */
    private var lastTapAt = Long.MIN_VALUE / 2
    private var lastTapX = 0f
    private var lastTapY = 0f

    fun down(id: Int, x: Float, y: Float, now: Long) {
        val finger = Finger(x, y, now)
        if (fingers.isEmpty()) {
            fingers[id] = finger
            mode = Mode.PENDING
            firstDownAt = now
            maxFingers = 1
            actor = id
            return
        }
        fingers[id] = finger
        maxFingers = maxOf(maxFingers, fingers.size)
        when (mode) {
            Mode.PENDING, Mode.HELD -> if (now - firstDownAt >= REST_MS && fingers.size == 2) {
                // The first finger rests: what the second does is Shift-clicked.
                endMagnifier()
                mode = Mode.SHIFT
                rester = actor
                actor = id
                holdKeys(setOf(Key.SHIFT))
            } else {
                endMagnifier()
                mode = Mode.MULTI
                multi = Multi.UNDECIDED
                startMulti()
            }
            Mode.SHIFT -> when {
                actor == null -> actor = id
                // Two fingers tapping together while one rests: Tab (Shift lets go for it).
                !boxing && fingers.size == 3 -> {
                    endMagnifier()
                    mode = Mode.SUBGROUP
                    subgroupAt = now
                    subgroupMoved = false
                    releaseKeys()
                }
                else -> {}
            }
            Mode.SUBGROUP -> {
                // More fingers: no Tab.
                subgroupMoved = true
            }
            Mode.PAN -> {
                releaseKeys()
                mode = Mode.DONE
            }
            Mode.MULTI -> startMulti()
            // A box or an aim carries on: other fingers landing meanwhile are left alone.
            Mode.BOX, Mode.AIM, Mode.IDLE, Mode.DONE -> {}
        }
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
            if (hypot(finger.x - finger.startX, finger.y - finger.startY) > slop) finger.moved = true
        }
        when (mode) {
            Mode.PENDING -> {
                val finger = fingers.values.firstOrNull() ?: return
                if (finger.moved) {
                    mode = Mode.PAN
                    pan(finger)
                }
            }
            Mode.HELD -> {
                val finger = actor?.let { fingers[it] } ?: return
                if (!finger.moved) return
                if (out.aiming()) {
                    // The game waits for a target: the pointer follows the finger, the click comes on lift.
                    mode = Mode.AIM
                    out.pointerTo(finger.x, finger.y)
                } else {
                    mode = Mode.BOX
                    startBox(finger)
                }
                magnifyAt(finger)
            }
            Mode.BOX -> actor?.let { fingers[it] }?.let {
                out.pointerTo(it.x, it.y)
                magnifyAt(it)
            }
            Mode.AIM -> actor?.let { fingers[it] }?.let {
                out.pointerTo(it.x, it.y)
                magnifyAt(it)
            }
            Mode.SHIFT -> {
                val finger = actor?.let { fingers[it] } ?: return
                if (!finger.moved) return
                // The other finger swiped (or dragged a hold): a Shift box, adding what it holds.
                if (!boxing) startBox(finger) else out.pointerTo(finger.x, finger.y)
                magnifyAt(finger)
            }
            Mode.SUBGROUP -> if (fingers.any { (id, f) -> id != rester && f.moved }) subgroupMoved = true
            Mode.PAN -> fingers.values.firstOrNull()?.let { pan(it) }
            Mode.MULTI -> moveMulti()
            Mode.IDLE, Mode.DONE -> {}
        }
    }

    fun up(id: Int, x: Float, y: Float, now: Long) {
        val finger = fingers.remove(id) ?: return
        finger.x = x
        finger.y = y
        when (mode) {
            Mode.SHIFT -> if (id == actor) {
                if (boxing) {
                    endBox(finger)
                } else if (!finger.moved) {
                    clickFor(finger, now)
                }
                endMagnifier()
                actor = null
            } else if (id == rester) {
                // The resting finger lifted: Shift ends (and a box being drawn with it).
                if (boxing) actor?.let { fingers[it] }?.let { endBox(it) }
                endMagnifier()
                releaseKeys()
                mode = Mode.DONE
            }
            Mode.SUBGROUP -> if (id == rester) {
                releaseKeys()
                mode = Mode.DONE
            } else if (fingers.size == 1 && rester in fingers) {
                // Both tapping fingers are up and the resting one stays: Tab, then Shift again.
                if (!subgroupMoved && now - subgroupAt <= MULTI_TAP_MS) out.nextSubgroup()
                mode = Mode.SHIFT
                actor = null
                holdKeys(setOf(Key.SHIFT))
            }
            Mode.PENDING, Mode.HELD -> if (fingers.isEmpty()) {
                endMagnifier()
                clickFor(finger, now)
            }
            Mode.BOX -> if (id == actor) {
                endBox(finger)
                endMagnifier()
                mode = Mode.DONE
            }
            Mode.AIM -> if (id == actor) {
                out.pointerTo(finger.x, finger.y)
                out.click(Button.LEFT)
                endMagnifier()
                mode = Mode.DONE
            }
            Mode.PAN -> if (fingers.isEmpty()) releaseKeys()
            Mode.MULTI -> if (fingers.isNotEmpty()) {
                startMulti()
            } else if (multi == Multi.UNDECIDED && now - firstDownAt <= MULTI_TAP_MS) {
                when {
                    maxFingers >= 4 -> out.showMenu()
                    maxFingers == 3 -> out.openKeyboard()
                    else -> out.gameMenu()
                }
            }
            Mode.IDLE, Mode.DONE -> {}
        }
        if (fingers.isEmpty()) {
            if (boxing) {
                out.release(Button.LEFT)
                boxing = false
            }
            endMagnifier()
            releaseKeys()
            mode = Mode.IDLE
            actor = null
            rester = null
        }
    }

    /** [holdMs] after a finger landed (the game screen calls it then, for every finger). */
    fun onHold(now: Long) {
        when (mode) {
            Mode.PENDING -> {
                val finger = fingers.values.singleOrNull() ?: return
                if (finger.moved || now - finger.downAt < holdMs) return
                finger.held = true
                mode = Mode.HELD
                out.feedback()
                magnifyAt(finger)
            }
            Mode.SHIFT -> {
                val finger = actor?.let { fingers[it] } ?: return
                if (finger.held || finger.moved || now - finger.downAt < holdMs) return
                finger.held = true
                out.feedback()
                magnifyAt(finger)
            }
            else -> {}
        }
    }

    /** The touch was taken away (another window, the system): lets go of everything. */
    fun cancel() {
        if (boxing) {
            out.release(Button.LEFT)
            boxing = false
        }
        endMagnifier()
        releaseKeys()
        fingers.clear()
        mode = Mode.IDLE
        actor = null
        rester = null
    }

    /**
     * A lifted finger that didn't move: a tap right-clicks (the left click while the game waits for
     * a target; attack-move for a second tap on the same spot), a hold left-clicks (twice, for a
     * second hold there).
     */
    private fun clickFor(finger: Finger, now: Long) {
        out.pointerTo(finger.startX, finger.startY)
        if (!finger.held) {
            if (out.aiming()) {
                out.click(Button.LEFT)
                lastTapAt = Long.MIN_VALUE / 2
                return
            }
            val again = finger.downAt - lastTapAt <= DOUBLE_TAP_MS &&
                hypot(finger.startX - lastTapX, finger.startY - lastTapY) <= sameSpot
            if (again && out.attackMove()) {
                lastTapAt = Long.MIN_VALUE / 2
                return
            }
            out.click(Button.RIGHT)
            lastTapAt = now
            lastTapX = finger.startX
            lastTapY = finger.startY
            return
        }
        val again = finger.downAt - lastHoldAt <= DOUBLE_HOLD_MS &&
            hypot(finger.startX - lastHoldX, finger.startY - lastHoldY) <= sameSpot
        if (again) {
            out.doubleClick(Button.LEFT)
            lastHoldAt = Long.MIN_VALUE / 2
        } else {
            out.click(Button.LEFT)
            lastHoldAt = now
            lastHoldX = finger.startX
            lastHoldY = finger.startY
        }
    }

    /** The left button down where the finger landed, then the pointer to where it is: the game draws the box. */
    private fun startBox(finger: Finger) {
        out.pointerTo(finger.startX, finger.startY)
        out.press(Button.LEFT)
        boxing = true
        out.pointerTo(finger.x, finger.y)
    }

    /** The box's other corner where the finger lifted: the game selects what's inside. */
    private fun endBox(finger: Finger) {
        out.pointerTo(finger.x, finger.y)
        out.release(Button.LEFT)
        boxing = false
    }

    private fun magnifyAt(finger: Finger) {
        magnifying = true
        out.magnify(finger.x, finger.y)
    }

    private fun endMagnifier() {
        if (!magnifying) return
        magnifying = false
        out.magnifyOff()
    }

    /** The arrow keys toward where the finger went from where it landed, beyond a small dead zone. */
    private fun pan(finger: Finger) {
        val dx = finger.x - finger.startX
        val dy = finger.y - finger.startY
        val ax = abs(dx)
        val ay = abs(dy)
        holdKeys(buildSet {
            if (ax > panDeadZone && ax > ay * 0.4f) add(if (dx > 0) Key.RIGHT else Key.LEFT)
            if (ay > panDeadZone && ay > ax * 0.4f) add(if (dy > 0) Key.DOWN else Key.UP)
        })
    }

    // Two fingers or more ----------------------------------------------------------------------

    /** Where the fingers are now is where pinches count from (again, with a finger more or less). */
    private fun startMulti() {
        baseCx = fingers.values.map { it.x }.average().toFloat()
        baseCy = fingers.values.map { it.y }.average().toFloat()
        baseSpread = spread()
        wheelSteps = 0
    }

    private fun spread(): Float {
        if (fingers.size < 2) return 0f
        val (a, b) = fingers.values.take(2)
        return hypot(a.x - b.x, a.y - b.y)
    }

    private fun moveMulti() {
        val cx = fingers.values.map { it.x }.average().toFloat()
        val cy = fingers.values.map { it.y }.average().toFloat()
        val stretch = spread() - baseSpread
        if (fingers.size == 2 && (multi == Multi.PINCH || (multi == Multi.UNDECIDED && abs(stretch) > slop * 2))) {
            multi = Multi.PINCH
            val steps = (stretch / pinchStep).toInt()
            while (wheelSteps < steps) {
                out.wheel(up = true)
                wheelSteps++
            }
            while (wheelSteps > steps) {
                out.wheel(up = false)
                wheelSteps--
            }
        } else if (multi == Multi.UNDECIDED && hypot(cx - baseCx, cy - baseCy) > slop * 2) {
            multi = Multi.MOVED
        }
    }

    private fun holdKeys(keys: Set<Key>) {
        for (key in heldKeys - keys) out.keyUp(key)
        for (key in keys - heldKeys) out.keyDown(key)
        heldKeys.clear()
        heldKeys += keys
    }

    private fun releaseKeys() = holdKeys(emptySet())
}
