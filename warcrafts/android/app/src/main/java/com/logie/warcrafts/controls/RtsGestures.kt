// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.controls

import kotlin.math.abs
import kotlin.math.hypot

/** A mouse button the gestures click. */
enum class Button { LEFT, RIGHT }

/** A key the gestures hold. */
enum class Key { LEFT, RIGHT, UP, DOWN, SHIFT }

/** What a finger that was held, then moved, does ([GestureOutput.heldDrag]). */
enum class HeldDrag {
    /** Pans the map, as a swipe does. */
    PAN,
    /** Drags with the left button down from where it was held: a selection box. */
    SELECT,
    /** Nothing: the hold is taken back (slid off a button before lifting). */
    CANCEL,
}

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

    /** The button held down, and let go ([HeldDrag.SELECT], [dragZone]). */
    fun press(button: Button) {}
    fun release(button: Button) {}

    /** What a held finger that then moves does, from where it was held. */
    fun heldDrag(x: Float, y: Float): HeldDrag = HeldDrag.PAN

    /** Whether a swipe starting here drags with the left button down (a minimap) instead of panning. */
    fun dragZone(x: Float, y: Float): Boolean = false

    /**
     * Whether a tap here waits [RtsGestures.TAP_WAIT_MS] for what may follow before it clicks: a
     * touch that drags (a selection box, where [heldDrag] says so) or a second tap there
     * ([doubleTap]). The game screen then calls [RtsGestures.tapTimeout].
     */
    fun tapWaits(x: Float, y: Float): Boolean = false

    /**
     * A finger landed where it may tap ([x], [y], the view's pixels): the first of a touch, or the
     * one tapping while another rests. A game can look at what's there before the tap is decided.
     */
    fun fingerDown(x: Float, y: Float) {}

    /** Two quick taps on the same spot where taps wait ([tapWaits]), the pointer there. */
    fun doubleTap() {
        click(Button.RIGHT)
        click(Button.RIGHT)
    }
}

/**
 * The touch controls the user chose for these games, with nothing drawn on the screen. Plain Kotlin
 * (no Android types), fed touch events by the game screen and unit-tested:
 *
 * - **Swipe** one finger: the map pans the way the finger goes (the arrow keys), for as long as the
 *   finger stays away from where it landed.
 * - **Tap**: right click there (move, attack, gather, set a rally point). Where a right click does
 *   nothing, the games make it a left click: in Warcraft I and II the engine does (menus, buttons,
 *   nothing of the player's selected: patches/stratagus), in Warcraft III the game screen does for
 *   its bottom panel and top bar ([com.logie.warcrafts.game.GestureView]).
 * - **Touch and hold** ([HOLD_MS], a buzz): left click there when the finger lifts (select a unit,
 *   press a button of the game's panel or menus). Two holds in a row on the same spot: a double
 *   click (every unit of that kind on the screen). Warcraft III's screen makes a hold in a match
 *   the right click ([com.logie.warcrafts.game.GestureView]).
 * - **A finger resting** while another taps or holds: those are Shift-clicks (queue orders, add to
 *   the selection).
 * - **Two-finger tap**: the game's menu. **Pinch**: zoom (the mouse wheel).
 * - **Three-finger tap**: the phone's keyboard. **Four-finger tap**: the app's menu.
 *
 * What a game may add ([GestureOutput]'s defaults keep the above): a held finger that then moves
 * draws a selection box or takes the hold back ([HeldDrag]), a swipe from a minimap drags on it
 * ([GestureOutput.dragZone]), and, where taps wait ([GestureOutput.tapWaits]), a tap followed at
 * once by a touch that drags draws a selection box from where the second touch landed (the tap's
 * own click left out), and two quick taps on one spot are a double tap ([GestureOutput.doubleTap]).
 */
class RtsGestures(private val out: GestureOutput, density: Float) {
    companion object {
        /** A finger that stays this long without moving is a hold (left click). */
        const val HOLD_MS = 400L
        /** Fingers that land and lift within this long without moving are a multi-finger tap. */
        const val MULTI_TAP_MS = 500L
        /** A finger down this long before another lands is resting (Shift), not half of a two-finger tap. */
        const val REST_MS = 200L
        /** A second hold this soon after the first one lifted, on the same spot, is a double click. */
        const val DOUBLE_HOLD_MS = 900L
        /**
         * Where taps wait ([GestureOutput.tapWaits]): a touch this soon after a tap lifted that then
         * drags draws a selection box; one that lifts on the same spot is a double tap.
         */
        const val TAP_WAIT_MS = 280L
    }

    private val slop = 10f * density
    private val panDeadZone = 14f * density
    private val pinchStep = 48f * density
    private val sameSpot = 40f * density

    private class Finger(val startX: Float, val startY: Float, val downAt: Long) {
        var x = startX
        var y = startY
        var held = false
        var moved = false
    }

    private enum class Mode { IDLE, PENDING, HELD, PAN, DRAG, CANCELLED, MULTI, SHIFT, DONE }

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

    /** In Shift mode: the finger tapping or holding (the other one rests). */
    private var actor: Int? = null

    /** A tap whose click waits for a touch that may drag a selection box ([GestureOutput.tapWaits]). */
    private var waitingTap: Finger? = null
    private var waitingTapUpAt = 0L
    /** The finger that landed while [waitingTap] waited: dragged, it draws the box. */
    private var boxFinger: Int? = null

    /** Whether a tap's click is waiting: the game screen calls [tapTimeout] [TAP_WAIT_MS] after the finger lifted. */
    val tapWaiting: Boolean get() = waitingTap != null

    /** The last hold's left click: where and when, for a double click. */
    private var lastHoldAt = Long.MIN_VALUE / 2
    private var lastHoldX = 0f
    private var lastHoldY = 0f

    fun down(id: Int, x: Float, y: Float, now: Long) {
        val finger = Finger(x, y, now)
        if (waitingTap != null) {
            if (fingers.isEmpty() && boxFinger == null && now - waitingTapUpAt <= TAP_WAIT_MS) {
                // Right after a tap: dragged, this finger draws a selection box.
                boxFinger = id
            } else {
                flushTap()
            }
        }
        if (fingers.isEmpty()) {
            fingers[id] = finger
            mode = Mode.PENDING
            firstDownAt = now
            maxFingers = 1
            out.fingerDown(x, y)
            return
        }
        fingers[id] = finger
        maxFingers = maxOf(maxFingers, fingers.size)
        when (mode) {
            Mode.PENDING, Mode.HELD -> if (now - firstDownAt >= REST_MS && fingers.size == 2) {
                // The first finger rests: what the second does is Shift-clicked.
                mode = Mode.SHIFT
                actor = id
                holdKeys(setOf(Key.SHIFT))
                out.fingerDown(x, y)
            } else {
                mode = Mode.MULTI
                multi = Multi.UNDECIDED
                startMulti()
            }
            Mode.SHIFT -> if (actor == null) {
                actor = id
                out.fingerDown(x, y)
            }
            Mode.PAN -> {
                releaseKeys()
                mode = Mode.DONE
            }
            Mode.DRAG -> {
                out.release(Button.LEFT)
                mode = Mode.DONE
            }
            Mode.CANCELLED -> mode = Mode.DONE
            Mode.MULTI -> startMulti()
            Mode.IDLE, Mode.DONE -> {}
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
            Mode.PENDING, Mode.HELD -> {
                val finger = fingers.values.firstOrNull() ?: return
                if (finger.moved) {
                    val box = boxFinger != null && waitingTap != null &&
                        out.heldDrag(finger.startX, finger.startY) == HeldDrag.SELECT
                    if (box) {
                        // Tap, then touch and drag: a selection box; the tap's own click is left out.
                        waitingTap = null
                        boxFinger = null
                    } else {
                        flushTap()
                    }
                    val what = when {
                        box -> HeldDrag.SELECT
                        mode == Mode.HELD -> out.heldDrag(finger.startX, finger.startY)
                        out.dragZone(finger.startX, finger.startY) -> HeldDrag.SELECT
                        else -> HeldDrag.PAN
                    }
                    when (what) {
                        HeldDrag.PAN -> {
                            mode = Mode.PAN
                            pan(finger)
                        }
                        HeldDrag.SELECT -> {
                            mode = Mode.DRAG
                            out.pointerTo(finger.startX, finger.startY)
                            out.press(Button.LEFT)
                            out.pointerTo(finger.x, finger.y)
                        }
                        HeldDrag.CANCEL -> mode = Mode.CANCELLED
                    }
                }
            }
            Mode.PAN -> fingers.values.firstOrNull()?.let { pan(it) }
            Mode.DRAG -> fingers.values.firstOrNull()?.let { out.pointerTo(it.x, it.y) }
            Mode.MULTI -> moveMulti()
            Mode.SHIFT, Mode.CANCELLED, Mode.IDLE, Mode.DONE -> {}
        }
    }

    fun up(id: Int, x: Float, y: Float, now: Long) {
        val finger = fingers.remove(id) ?: return
        finger.x = x
        finger.y = y
        when (mode) {
            Mode.SHIFT -> if (id == actor) {
                if (!finger.moved) clickFor(finger, now, mayWait = false)
                actor = null
            } else {
                // The resting finger lifted: Shift ends.
                releaseKeys()
                mode = Mode.DONE
            }
            Mode.PENDING, Mode.HELD -> if (fingers.isEmpty()) {
                val tap = waitingTap
                if (tap != null && id == boxFinger && !finger.held &&
                    hypot(finger.startX - tap.startX, finger.startY - tap.startY) <= sameSpot
                ) {
                    // A second tap on the same spot: a double tap.
                    waitingTap = null
                    boxFinger = null
                    out.pointerTo(tap.startX, tap.startY)
                    out.doubleTap()
                } else {
                    // A second touch after a tap that didn't drag: the tap's click first, then this one's.
                    flushTap()
                    clickFor(finger, now)
                }
            }
            Mode.PAN -> if (fingers.isEmpty()) releaseKeys()
            Mode.DRAG -> {
                out.pointerTo(x, y)
                out.release(Button.LEFT)
                mode = Mode.DONE
            }
            Mode.MULTI -> if (fingers.isNotEmpty()) {
                startMulti()
            } else if (multi == Multi.UNDECIDED && now - firstDownAt <= MULTI_TAP_MS) {
                when {
                    maxFingers >= 4 -> out.showMenu()
                    maxFingers == 3 -> out.openKeyboard()
                    else -> out.gameMenu()
                }
            }
            Mode.CANCELLED, Mode.IDLE, Mode.DONE -> {}
        }
        if (fingers.isEmpty()) {
            releaseKeys()
            mode = Mode.IDLE
            actor = null
        }
    }

    /** [HOLD_MS] after a finger landed (the game screen calls it then, for every finger). */
    fun onHold(now: Long) {
        when (mode) {
            Mode.PENDING -> {
                val finger = fingers.values.singleOrNull() ?: return
                if (finger.moved || now - finger.downAt < HOLD_MS) return
                finger.held = true
                mode = Mode.HELD
                out.feedback()
            }
            Mode.SHIFT -> {
                val finger = actor?.let { fingers[it] } ?: return
                if (finger.held || finger.moved || now - finger.downAt < HOLD_MS) return
                finger.held = true
                out.feedback()
            }
            else -> {}
        }
    }

    /** [TAP_WAIT_MS] after a tap lifted ([tapWaiting]): no touch came to drag a box, so the tap clicks. */
    fun tapTimeout(now: Long) {
        if (waitingTap != null && fingers.isEmpty() && now - waitingTapUpAt >= TAP_WAIT_MS) flushTap()
    }

    /** The waiting tap's click, now. */
    private fun flushTap() {
        val tap = waitingTap ?: return
        waitingTap = null
        boxFinger = null
        out.pointerTo(tap.startX, tap.startY)
        out.click(Button.RIGHT)
    }

    /** The touch was taken away (another window, the system): lets go of everything. */
    fun cancel() {
        waitingTap = null
        boxFinger = null
        if (mode == Mode.DRAG) out.release(Button.LEFT)
        releaseKeys()
        fingers.clear()
        mode = Mode.IDLE
        actor = null
    }

    /**
     * A lifted finger that didn't move: a tap right-clicks (after [TAP_WAIT_MS] where a selection box
     * may follow, unless [mayWait] is off), a hold left-clicks (twice, for a second hold there).
     */
    private fun clickFor(finger: Finger, now: Long, mayWait: Boolean = true) {
        if (!finger.held && mayWait && out.tapWaits(finger.startX, finger.startY)) {
            waitingTap = finger
            waitingTapUpAt = now
            return
        }
        out.pointerTo(finger.startX, finger.startY)
        if (!finger.held) {
            out.click(Button.RIGHT)
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
