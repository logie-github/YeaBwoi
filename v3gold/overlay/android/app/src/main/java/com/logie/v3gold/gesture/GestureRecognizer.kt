// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.v3gold.gesture

import kotlin.math.abs
import kotlin.math.hypot

/** The PS Vita buttons the touch gestures can produce. */
enum class VitaButton {
    CROSS, CIRCLE, SQUARE, TRIANGLE, START, SELECT, L1, R1,
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT
}

enum class Stick { LEFT, RIGHT }

/** Receives the controller state produced by [GestureRecognizer]. */
interface GestureOutput {
    fun setButton(button: VitaButton, pressed: Boolean)

    /** Stick position. +x is right and +y is up. The magnitude is at most 1. */
    fun setStick(stick: Stick, x: Float, y: Float)

    /** Two-finger tap, then two-finger tap and hold. */
    fun openMenu()

    /** A hold gesture (Circle, Square, Select or the menu) was recognized. Used for haptic feedback. */
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
    /** How far a finger has to move from where it landed to push a stick all the way. */
    val stickRadiusPx: Float,
    /** How far a quick sideways swipe on the right half has to go to count as L1 or R1. */
    val flickMinPx: Float,
    /** How far two fingers have to move up together to count as Triangle. */
    val twoFingerSwipePx: Float,
    /** How long a finger has to stay still before a touch counts as a hold. */
    val holdTimeoutMs: Long = 280,
    /** Maximum time between lifting the first tap and landing the second one. */
    val doubleTapTimeoutMs: Long = 250,
    /** Same as [doubleTapTimeoutMs], for two-finger taps (fingers rarely land together). */
    val twoFingerDoubleTapTimeoutMs: Long = 350,
    /** How long a tap keeps its button pressed so the game is sure to see it. */
    val pulseMs: Long = 100,
    /**
     * A swipe on the right half that lifts within this time (from touching down) is a flick for
     * L1 or R1. One that stays down longer moves the right stick.
     */
    val flickMaxMs: Long = 250,
    /** How far (as a fraction of [stickRadiusPx]) a swipe goes before it presses the D-pad. */
    val dpadPressTilt: Float = 0.5f,
    /** A swipe pressing the D-pad lets go of it when it comes back closer than this. */
    val dpadReleaseTilt: Float = 0.3f,
)

/**
 * Turns raw touch points into PS Vita controller input, made for Persona 4 Golden:
 *
 * - Tap: Cross
 * - Hold: Circle (held for as long as the finger stays down)
 * - Tap, then tap and hold: Square
 * - Double tap: Start
 * - Swipe on the left half: left stick, for as long as the finger stays down. The swipe also holds
 *   the D-pad in its main direction (up, down, left or right), for menus that only follow the D-pad.
 * - Swipe and keep the finger down on the right half: right stick
 * - Quick flick on the right half: right for L1, left for R1
 * - Two fingers swiping up: Triangle
 * - Two-finger hold: Select (held for as long as the fingers stay down)
 * - Two-finger tap, then two-finger tap and hold: [GestureOutput.openMenu]
 *
 * While one finger is driving a stick or holding a button, a second finger can tap for Cross, hold
 * for Circle or drive the other stick. Holding Circle and walking at the same time is done with one
 * finger: hold, then drag.
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
        val downTime: Long,
        /** The half of the screen the finger landed on, which decides the stick it drives. */
        val side: Stick,
        /** This touch landed while an earlier tap was waiting to see if it becomes a double tap. */
        val secondTap: Boolean,
        /** This touch landed while another finger was already moving a stick or holding. */
        val secondary: Boolean,
    ) {
        var state = State.PENDING
        var x = downX
        var y = downY
        var holdTimer: Cancellable? = null
        var heldButton: VitaButton? = null
        var originX = downX
        var originY = downY
        /** A right-half swipe that may still turn out to be a flick; the stick waits until it's not. */
        var flickPending = false
        var flickTimer: Cancellable? = null
    }

    private val touches = LinkedHashMap<Int, Touch>()
    private val stickOwners = arrayOfNulls<Touch>(Stick.entries.size)

    /** The D-pad direction the left stick finger is holding. */
    private var stickDpad: VitaButton? = null
    /** Keeps a quick flick's D-pad press down for [GestureConfig.pulseMs] so the game sees it. */
    private var dpadPulse: Cancellable? = null
    private var dpadPulseButton: VitaButton? = null
    private var viewWidth = 0f

    /** A finished tap waiting for the double tap window to run out before it becomes Cross. */
    private var pendingTap: Cancellable? = null

    private var twoFingerStart = 0L
    private var twoFingerSecond = false
    private var twoFingerMoved = false
    private var twoFingerSwiped = false
    private var twoFingerTimer: Cancellable? = null
    private var lastTwoFingerTapUp: Long? = null
    private var selectHeld = false
    /** A two-finger touch moved since the last [movesFinished]. */
    private var twoFingerMovePending = false

    private val pressCount = IntArray(VitaButton.entries.size)
    private val pulseTimers = HashSet<Cancellable>()

    fun setViewWidth(width: Float) {
        viewWidth = width
    }

    fun pointerDown(id: Int, x: Float, y: Float, timeMs: Long) {
        touches[id]?.let { pointerUp(id, it.x, it.y, timeMs) }

        val others = touches.values.toList()
        val side = if (viewWidth > 0f && x >= viewWidth / 2) Stick.RIGHT else Stick.LEFT

        if (others.size == 1 && others[0].state == State.PENDING && !others[0].secondary) {
            // A second finger joined a touch that hasn't turned into anything yet.
            val first = others[0]
            first.holdTimer?.cancel()
            first.holdTimer = null
            if (first.secondTap) {
                // The single tap before this one was never followed up, so it still counts.
                pulse(VitaButton.CROSS)
            }
            first.state = State.TWO_FINGER
            touches[id] = Touch(id, x, y, timeMs, side, secondTap = false, secondary = false).apply {
                state = State.TWO_FINGER
            }
            startTwoFinger(timeMs)
            return
        }

        if (others.any { it.state == State.TWO_FINGER }) {
            touches[id] = Touch(id, x, y, timeMs, side, secondTap = false, secondary = false).apply {
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

        val touch = Touch(id, x, y, timeMs, side, secondTap, secondary)
        touch.holdTimer = scheduler.schedule(config.holdTimeoutMs) { onHold(touch) }
        touches[id] = touch
    }

    fun pointerMove(id: Int, x: Float, y: Float, timeMs: Long) {
        val touch = touches[id] ?: return
        touch.x = x
        touch.y = y
        val moved = hypot(x - touch.downX, y - touch.downY)

        when (touch.state) {
            State.PENDING -> if (moved > config.touchSlopPx) {
                touch.holdTimer?.cancel()
                touch.holdTimer = null
                if (touch.secondTap) {
                    // Tap followed by a swipe: the tap still counts as Cross.
                    pulse(VitaButton.CROSS)
                }
                startStick(touch, timeMs)
            }

            State.HOLD -> {
                if (stickOwners[touch.side.ordinal] == null && moved > config.touchSlopPx) {
                    // Holding a button and then dragging moves the stick too (e.g. Circle + stick).
                    stickOwners[touch.side.ordinal] = touch
                }
                if (stickOwners[touch.side.ordinal] === touch) {
                    updateStick(touch)
                }
            }

            State.STICK -> if (!touch.flickPending) {
                updateStick(touch)
            }

            State.TWO_FINGER -> twoFingerMovePending = true

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
                touch.secondary -> pulse(VitaButton.CROSS)
                touch.secondTap -> pulse(VitaButton.START)
                else -> {
                    val timer = arrayOfNulls<Cancellable>(1)
                    timer[0] = scheduler.schedule(config.doubleTapTimeoutMs) {
                        if (pendingTap === timer[0]) {
                            pendingTap = null
                            pulse(VitaButton.CROSS)
                        }
                    }
                    pendingTap = timer[0]
                }
            }

            State.HOLD -> {
                touch.heldButton?.let { release(it) }
                if (stickOwners[touch.side.ordinal] === touch) {
                    centerStick(touch.side)
                }
            }

            State.STICK -> if (touch.flickPending) {
                touch.flickTimer?.cancel()
                touch.flickTimer = null
                touch.flickPending = false
                stickOwners[touch.side.ordinal] = null
                flickButton(touch)?.let { pulse(it) }
            } else {
                centerStick(touch.side)
            }

            State.TWO_FINGER -> {
                // Lifting either finger ends the chance of a hold.
                twoFingerTimer?.cancel()
                twoFingerTimer = null
                if (touches.values.none { it.state == State.TWO_FINGER }) {
                    endTwoFinger(timeMs)
                }
            }

            State.IGNORED -> {}
        }
    }

    /**
     * Call after the moves of every finger in one touch event. Two-finger swipes are judged on both
     * fingers' new positions together.
     */
    fun movesFinished() {
        if (twoFingerMovePending) {
            twoFingerMovePending = false
            updateTwoFinger()
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
            touch.flickTimer?.cancel()
            if (touch.state == State.HOLD) {
                touch.heldButton?.let { release(it) }
            }
        }
        touches.clear()
        twoFingerTimer?.cancel()
        twoFingerTimer = null
        if (selectHeld) {
            selectHeld = false
            release(VitaButton.SELECT)
        }
        for (stick in Stick.entries) {
            if (stickOwners[stick.ordinal] != null) {
                centerStick(stick)
            }
        }
    }

    /** Drops every touch and releases every button, e.g. when the view loses its touch stream. */
    fun cancelAll() {
        for (touch in touches.values) {
            touch.holdTimer?.cancel()
            touch.flickTimer?.cancel()
        }
        touches.clear()
        stickOwners.fill(null)
        stickDpad = null
        dpadPulse?.cancel()
        dpadPulse = null
        dpadPulseButton = null

        pendingTap?.cancel()
        pendingTap = null
        twoFingerTimer?.cancel()
        twoFingerTimer = null
        lastTwoFingerTapUp = null
        selectHeld = false
        twoFingerMovePending = false

        for (timer in pulseTimers) {
            timer.cancel()
        }
        pulseTimers.clear()

        for (button in VitaButton.entries) {
            if (pressCount[button.ordinal] > 0) {
                pressCount[button.ordinal] = 0
                output.setButton(button, false)
            }
        }
        for (stick in Stick.entries) {
            output.setStick(stick, 0f, 0f)
        }
    }

    private fun onHold(touch: Touch) {
        touch.holdTimer = null
        if (touches[touch.id] !== touch || touch.state != State.PENDING) {
            return
        }

        touch.state = State.HOLD
        val button = if (touch.secondTap) VitaButton.SQUARE else VitaButton.CIRCLE
        touch.heldButton = button
        press(button)
        output.onHoldRecognized()
    }

    private fun startStick(touch: Touch, timeMs: Long) {
        val side = touch.side
        if (stickOwners[side.ordinal] != null) {
            touch.state = State.IGNORED
            return
        }
        touch.state = State.STICK
        stickOwners[side.ordinal] = touch

        if (side == Stick.RIGHT) {
            val remaining = config.flickMaxMs - (timeMs - touch.downTime)
            if (remaining > 0) {
                // Wait to see whether the finger lifts (a flick for L1/R1) or stays down (the stick).
                touch.flickPending = true
                touch.flickTimer = scheduler.schedule(remaining) {
                    touch.flickTimer = null
                    if (touch.flickPending && stickOwners[side.ordinal] === touch) {
                        touch.flickPending = false
                        updateStick(touch)
                    }
                }
                return
            }
        }
        updateStick(touch)
    }

    /** L1 for a flick to the right, R1 for one to the left, or nothing if it went another way. */
    private fun flickButton(touch: Touch): VitaButton? {
        val dx = touch.x - touch.downX
        val dy = touch.y - touch.downY
        if (abs(dx) < config.flickMinPx || abs(dx) < abs(dy) * FLICK_SLOPE) {
            return null
        }
        return if (dx > 0) VitaButton.L1 else VitaButton.R1
    }

    private fun startTwoFinger(timeMs: Long) {
        twoFingerStart = timeMs
        twoFingerMoved = false
        twoFingerSwiped = false
        twoFingerMovePending = false
        val lastTapUp = lastTwoFingerTapUp
        twoFingerSecond =
            lastTapUp != null && timeMs - lastTapUp <= config.twoFingerDoubleTapTimeoutMs
        lastTwoFingerTapUp = null

        twoFingerTimer = scheduler.schedule(config.holdTimeoutMs) {
            twoFingerTimer = null
            if (twoFingerSecond) {
                openMenu()
            } else {
                selectHeld = true
                press(VitaButton.SELECT)
                output.onHoldRecognized()
            }
        }
    }

    private fun updateTwoFinger() {
        val fingers = touches.values.filter { it.state == State.TWO_FINGER }
        if (fingers.size != 2) {
            return
        }

        val farthest = fingers.maxOf { hypot(it.x - it.downX, it.y - it.downY) }
        if (!twoFingerMoved && farthest > config.touchSlopPx * 2) {
            twoFingerMoved = true
            twoFingerTimer?.cancel()
            twoFingerTimer = null
        }

        if (twoFingerSwiped || selectHeld) {
            return
        }
        val dx = fingers.sumOf { (it.x - it.downX).toDouble() }.toFloat() / 2
        val dy = fingers.sumOf { (it.y - it.downY).toDouble() }.toFloat() / 2
        val bothUp = fingers.all { it.y < it.downY }
        if (bothUp && -dy >= config.twoFingerSwipePx && -dy >= abs(dx) * FLICK_SLOPE) {
            twoFingerSwiped = true
            pulse(VitaButton.TRIANGLE)
        }
    }

    private fun endTwoFinger(timeMs: Long) {
        twoFingerTimer?.cancel()
        twoFingerTimer = null
        if (selectHeld) {
            selectHeld = false
            release(VitaButton.SELECT)
        }

        val wasTap = !twoFingerSecond && !twoFingerMoved && timeMs - twoFingerStart < config.holdTimeoutMs
        lastTwoFingerTapUp = if (wasTap) timeMs else null
    }

    private fun openMenu() {
        // Let go of everything so nothing stays pressed while the menu is open.
        val fingersStillDown = touches.keys.toList()
        cancelAll()
        for (id in fingersStillDown) {
            touches[id] = Touch(id, 0f, 0f, 0L, Stick.LEFT, secondTap = false, secondary = false).apply {
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
        output.setStick(touch.side, x, y)
        if (touch.side == Stick.LEFT) {
            setStickDpad(dpadFor(x, y))
        }
    }

    private fun centerStick(stick: Stick) {
        stickOwners[stick.ordinal] = null
        output.setStick(stick, 0f, 0f)
        if (stick == Stick.LEFT) {
            setStickDpad(null)
        }
    }

    /**
     * The D-pad direction for a stick position: whichever of up, down, left and right the swipe
     * points closest to, once it's pushed at least [GestureConfig.dpadPressTilt]. A pressed
     * direction stays pressed until the swipe clearly turns away from it, so a wobbly or diagonal
     * finger doesn't make the D-pad flicker between two directions.
     */
    private fun dpadFor(x: Float, y: Float): VitaButton? {
        val tilt = hypot(x, y)
        stickDpad?.let { current ->
            val (along, across) = when (current) {
                VitaButton.DPAD_UP -> y to x
                VitaButton.DPAD_DOWN -> -y to x
                VitaButton.DPAD_LEFT -> -x to y
                else -> x to y
            }
            if (along > 0 && tilt >= config.dpadReleaseTilt && abs(across) <= along * DPAD_KEEP_SLOPE) {
                return current
            }
        }
        return when {
            tilt < config.dpadPressTilt -> null
            abs(y) >= abs(x) -> if (y > 0) VitaButton.DPAD_UP else VitaButton.DPAD_DOWN
            else -> if (x > 0) VitaButton.DPAD_RIGHT else VitaButton.DPAD_LEFT
        }
    }

    private fun setStickDpad(button: VitaButton?) {
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

    private fun pulse(button: VitaButton) {
        press(button)
        val timer = arrayOfNulls<Cancellable>(1)
        timer[0] = scheduler.schedule(config.pulseMs) {
            if (pulseTimers.remove(timer[0])) {
                release(button)
            }
        }
        pulseTimers.add(timer[0]!!)
    }

    private fun press(button: VitaButton) {
        if (pressCount[button.ordinal]++ == 0) {
            output.setButton(button, true)
        }
    }

    private fun release(button: VitaButton) {
        if (pressCount[button.ordinal] > 0 && --pressCount[button.ordinal] == 0) {
            output.setButton(button, false)
        }
    }

    private companion object {
        /** A pressed D-pad direction lets go once the swipe is more than about 56° away from it. */
        const val DPAD_KEEP_SLOPE = 1.5f

        /** Flicks and two-finger swipes have to go mostly one way: within about 34° of it. */
        const val FLICK_SLOPE = 1.5f
    }
}
