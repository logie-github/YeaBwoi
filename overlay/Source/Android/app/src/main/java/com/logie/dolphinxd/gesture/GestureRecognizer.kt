// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd.gesture

import kotlin.math.abs
import kotlin.math.hypot

/** The GameCube buttons the touch gestures can produce. */
enum class PadButton { A, B, X, L, START, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT }

/** Receives the controller state produced by [GestureRecognizer]. */
interface GestureOutput {
    fun setButton(button: PadButton, pressed: Boolean)

    /**
     * Main stick position. +x is right and +y is up (GameCube convention).
     * The magnitude is at most 1.
     */
    fun setStick(x: Float, y: Float)

    /** Two-finger tap, tap and hold. */
    fun openMenu()

    /** A hold gesture (B, L, X or the menu) was recognized. Used for haptic feedback. */
    fun onHoldRecognized() {}
}

fun interface Cancellable {
    fun cancel()
}

/** Runs delayed work on the same thread that feeds touch events into the recognizer. */
fun interface GestureScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable
}

data class GestureConfig(
    /** How far a finger has to move before a touch counts as a swipe. */
    val touchSlopPx: Float,
    /** How far a finger has to move from where it landed to push the stick all the way. */
    val stickRadiusPx: Float,
    /** How long a finger has to stay still before a touch counts as a hold. */
    val holdTimeoutMs: Long = 280,
    /** Maximum time between lifting the first tap and landing the second one. */
    val doubleTapTimeoutMs: Long = 250,
    /** Same as [doubleTapTimeoutMs], for two-finger taps (fingers rarely land together). */
    val twoFingerDoubleTapTimeoutMs: Long = 350,
    /** How long a tap keeps its button pressed so the game is sure to see it. */
    val pulseMs: Long = 100,
    /** How far (as a fraction of [stickRadiusPx]) a swipe goes before it presses the D-pad. */
    val dpadPressTilt: Float = 0.5f,
    /** A swipe pressing the D-pad lets go of it when it comes back closer than this. */
    val dpadReleaseTilt: Float = 0.3f,
)

/**
 * Turns raw touch points into GameCube controller input:
 *
 * - Tap: A
 * - Hold: B (held for as long as the finger stays down)
 * - Double tap: Start
 * - Tap, then tap and hold: L on the left half of the screen, X on the right half
 * - Swipe in any direction: main stick, for as long as the finger stays down. The swipe also holds
 *   the D-pad in its main direction (up, down, left or right), for menus that only follow the D-pad.
 * - Two-finger tap, then two-finger tap and hold: [GestureOutput.openMenu]
 *
 * While one finger is driving the stick or holding a button, a second finger can tap for A or
 * hold for B, so the player can walk and talk/run at the same time.
 *
 * Coordinates are in pixels with y pointing down; times are in milliseconds from the same clock
 * that [GestureScheduler] uses.
 */
class GestureRecognizer(
    private val config: GestureConfig,
    private val scheduler: GestureScheduler,
    private val output: GestureOutput,
) {
    private enum class State { PENDING, STICK, HOLD, TWO_FINGER, IGNORED }

    private class Touch(
        val id: Int,
        val downX: Float,
        val downY: Float,
        /** This touch landed while an earlier tap was waiting to see if it becomes a double tap. */
        val secondTap: Boolean,
        /** This touch landed while another finger was already moving the stick or holding. */
        val secondary: Boolean,
    ) {
        var state = State.PENDING
        var x = downX
        var y = downY
        var holdTimer: Cancellable? = null
        var heldButton: PadButton? = null
        var originX = downX
        var originY = downY
    }

    private val touches = LinkedHashMap<Int, Touch>()
    private var stickOwner: Touch? = null

    /** The D-pad direction the stick finger is holding. */
    private var stickDpad: PadButton? = null
    /** Keeps a quick flick's D-pad press down for [GestureConfig.pulseMs] so the game sees it. */
    private var dpadPulse: Cancellable? = null
    private var dpadPulseButton: PadButton? = null
    private var viewWidth = 0f

    /** A finished tap waiting for the double tap window to run out before it becomes A. */
    private var pendingTap: Cancellable? = null

    private var twoFingerStart = 0L
    private var twoFingerSecond = false
    private var twoFingerMoved = false
    private var twoFingerTimer: Cancellable? = null
    private var lastTwoFingerTapUp: Long? = null

    private val pressCount = IntArray(PadButton.entries.size)
    private val pulseTimers = HashSet<Cancellable>()

    fun setViewWidth(width: Float) {
        viewWidth = width
    }

    fun pointerDown(id: Int, x: Float, y: Float, timeMs: Long) {
        touches[id]?.let { pointerUp(id, it.x, it.y, timeMs) }

        val others = touches.values.toList()

        if (others.size == 1 && others[0].state == State.PENDING && !others[0].secondary) {
            // A second finger joined a touch that hasn't turned into anything yet.
            val first = others[0]
            first.holdTimer?.cancel()
            if (first.secondTap) {
                // The single tap before this one was never followed up, so it still counts as A.
                pulse(PadButton.A)
            }
            first.state = State.TWO_FINGER
            touches[id] = Touch(id, x, y, secondTap = false, secondary = false).apply {
                state = State.TWO_FINGER
            }
            startTwoFinger(timeMs)
            return
        }

        if (others.any { it.state == State.TWO_FINGER }) {
            touches[id] = Touch(id, x, y, secondTap = false, secondary = false).apply {
                state = State.IGNORED
            }
            return
        }

        val secondary = others.any { it.state == State.STICK || it.state == State.HOLD }
        var secondTap = false
        if (!secondary && pendingTap != null) {
            pendingTap?.cancel()
            pendingTap = null
            secondTap = true
        }

        val touch = Touch(id, x, y, secondTap, secondary)
        touch.holdTimer = scheduler.schedule(config.holdTimeoutMs) { onHold(touch) }
        touches[id] = touch
    }

    fun pointerMove(id: Int, x: Float, y: Float, @Suppress("UNUSED_PARAMETER") timeMs: Long) {
        val touch = touches[id] ?: return
        touch.x = x
        touch.y = y
        val moved = hypot(x - touch.downX, y - touch.downY)

        when (touch.state) {
            State.PENDING -> if (moved > config.touchSlopPx) {
                touch.holdTimer?.cancel()
                touch.holdTimer = null
                if (touch.secondTap) {
                    // Tap followed by a swipe: the tap still counts as A.
                    pulse(PadButton.A)
                }
                if (stickOwner == null) {
                    touch.state = State.STICK
                    stickOwner = touch
                    updateStick(touch)
                } else {
                    touch.state = State.IGNORED
                }
            }

            State.HOLD -> {
                if (stickOwner == null && moved > config.touchSlopPx) {
                    // Holding a button and then dragging moves the stick too (e.g. B + stick).
                    stickOwner = touch
                }
                if (stickOwner === touch) {
                    updateStick(touch)
                }
            }

            State.STICK -> updateStick(touch)

            State.TWO_FINGER -> if (moved > config.touchSlopPx * 2) {
                twoFingerMoved = true
                twoFingerTimer?.cancel()
                twoFingerTimer = null
            }

            State.IGNORED -> {}
        }
    }

    fun pointerUp(id: Int, x: Float, y: Float, timeMs: Long) {
        val touch = touches.remove(id) ?: return
        touch.x = x
        touch.y = y
        touch.holdTimer?.cancel()
        touch.holdTimer = null

        when (touch.state) {
            State.PENDING -> when {
                touch.secondary -> pulse(PadButton.A)
                touch.secondTap -> pulse(PadButton.START)
                else -> {
                    val timer = arrayOfNulls<Cancellable>(1)
                    timer[0] = scheduler.schedule(config.doubleTapTimeoutMs) {
                        if (pendingTap === timer[0]) {
                            pendingTap = null
                            pulse(PadButton.A)
                        }
                    }
                    pendingTap = timer[0]
                }
            }

            State.HOLD -> {
                touch.heldButton?.let { release(it) }
                if (stickOwner === touch) {
                    centerStick()
                }
            }

            State.STICK -> centerStick()

            State.TWO_FINGER -> if (touches.values.none { it.state == State.TWO_FINGER }) {
                endTwoFinger(timeMs)
            }

            State.IGNORED -> {}
        }
    }

    /**
     * Forgets touches whose up event never arrived. Call when the first finger of a new gesture
     * lands (no other finger can still be down then). Unlike [cancelAll], a tap waiting to become
     * a double tap is kept.
     */
    fun dropStaleTouches() {
        for (touch in touches.values) {
            touch.holdTimer?.cancel()
            if (touch.state == State.HOLD) {
                touch.heldButton?.let { release(it) }
            }
        }
        touches.clear()
        twoFingerTimer?.cancel()
        twoFingerTimer = null
        if (stickOwner != null) {
            centerStick()
        }
    }

    /** Drops every touch and releases every button, e.g. when the view loses its touch stream. */
    fun cancelAll() {
        for (touch in touches.values) {
            touch.holdTimer?.cancel()
        }
        touches.clear()
        stickOwner = null
        stickDpad = null
        dpadPulse?.cancel()
        dpadPulse = null
        dpadPulseButton = null

        pendingTap?.cancel()
        pendingTap = null
        twoFingerTimer?.cancel()
        twoFingerTimer = null
        lastTwoFingerTapUp = null

        for (timer in pulseTimers) {
            timer.cancel()
        }
        pulseTimers.clear()

        for (button in PadButton.entries) {
            if (pressCount[button.ordinal] > 0) {
                pressCount[button.ordinal] = 0
                output.setButton(button, false)
            }
        }
        output.setStick(0f, 0f)
    }

    private fun onHold(touch: Touch) {
        touch.holdTimer = null
        if (touches[touch.id] !== touch || touch.state != State.PENDING) {
            return
        }

        val button = when {
            touch.secondary -> PadButton.B
            touch.secondTap -> if (touch.downX < viewWidth / 2) PadButton.L else PadButton.X
            else -> PadButton.B
        }
        touch.state = State.HOLD
        touch.heldButton = button
        press(button)
        output.onHoldRecognized()
    }

    private fun startTwoFinger(timeMs: Long) {
        twoFingerStart = timeMs
        twoFingerMoved = false
        val lastTapUp = lastTwoFingerTapUp
        twoFingerSecond =
            lastTapUp != null && timeMs - lastTapUp <= config.twoFingerDoubleTapTimeoutMs
        lastTwoFingerTapUp = null

        if (twoFingerSecond) {
            twoFingerTimer = scheduler.schedule(config.holdTimeoutMs) {
                twoFingerTimer = null
                openMenu()
            }
        }
    }

    private fun endTwoFinger(timeMs: Long) {
        twoFingerTimer?.cancel()
        twoFingerTimer = null

        val wasTap = !twoFingerSecond && !twoFingerMoved &&
                timeMs - twoFingerStart < config.holdTimeoutMs
        lastTwoFingerTapUp = if (wasTap) timeMs else null
    }

    private fun openMenu() {
        // Let go of everything so nothing stays pressed while the menu is open.
        val fingersStillDown = touches.keys.toList()
        cancelAll()
        for (id in fingersStillDown) {
            touches[id] = Touch(id, 0f, 0f, secondTap = false, secondary = false).apply {
                state = State.IGNORED
            }
        }
        output.onHoldRecognized()
        output.openMenu()
    }

    private fun updateStick(touch: Touch) {
        var dx = touch.x - touch.originX
        var dy = touch.y - touch.originY
        val distance = hypot(dx, dy)
        val radius = config.stickRadiusPx

        if (distance > radius) {
            // Drag the origin along so that reversing direction responds immediately.
            touch.originX = touch.x - dx / distance * radius
            touch.originY = touch.y - dy / distance * radius
            dx = touch.x - touch.originX
            dy = touch.y - touch.originY
        }

        val x = dx / radius
        val y = -dy / radius
        output.setStick(x, y)
        setStickDpad(dpadFor(x, y))
    }

    private fun centerStick() {
        stickOwner = null
        output.setStick(0f, 0f)
        setStickDpad(null)
    }

    /**
     * The D-pad direction for a stick position: whichever of up, down, left and right the swipe
     * points closest to, once it's pushed at least [GestureConfig.dpadPressTilt]. A pressed
     * direction stays pressed until the swipe clearly turns away from it, so a wobbly or diagonal
     * finger doesn't make the D-pad flicker between two directions.
     */
    private fun dpadFor(x: Float, y: Float): PadButton? {
        val tilt = hypot(x, y)
        stickDpad?.let { current ->
            val (along, across) = when (current) {
                PadButton.DPAD_UP -> y to x
                PadButton.DPAD_DOWN -> -y to x
                PadButton.DPAD_LEFT -> -x to y
                else -> x to y
            }
            if (along > 0 && tilt >= config.dpadReleaseTilt && abs(across) <= along * DPAD_KEEP_SLOPE) {
                return current
            }
        }
        return when {
            tilt < config.dpadPressTilt -> null
            abs(y) >= abs(x) -> if (y > 0) PadButton.DPAD_UP else PadButton.DPAD_DOWN
            else -> if (x > 0) PadButton.DPAD_RIGHT else PadButton.DPAD_LEFT
        }
    }

    private fun setStickDpad(button: PadButton?) {
        if (button == stickDpad) {
            return
        }
        stickDpad?.let { release(it) }
        stickDpad = button
        if (button != null) {
            // Never two directions at once, even right after a quick flick another way.
            endDpadPulse()
            press(button)
            press(button)
            dpadPulseButton = button
            dpadPulse = scheduler.schedule(config.pulseMs) { endDpadPulse() }
        }
    }

    private fun endDpadPulse() {
        dpadPulse?.cancel()
        dpadPulse = null
        dpadPulseButton?.let { release(it) }
        dpadPulseButton = null
    }

    private fun pulse(button: PadButton) {
        press(button)
        val timer = arrayOfNulls<Cancellable>(1)
        timer[0] = scheduler.schedule(config.pulseMs) {
            if (pulseTimers.remove(timer[0])) {
                release(button)
            }
        }
        pulseTimers.add(timer[0]!!)
    }

    private fun press(button: PadButton) {
        if (pressCount[button.ordinal]++ == 0) {
            output.setButton(button, true)
        }
    }

    private fun release(button: PadButton) {
        if (pressCount[button.ordinal] > 0 && --pressCount[button.ordinal] == 0) {
            output.setButton(button, false)
        }
    }

    private companion object {
        /** A pressed D-pad direction lets go once the swipe is more than about 56° away from it. */
        const val DPAD_KEEP_SLOPE = 1.5f
    }
}
