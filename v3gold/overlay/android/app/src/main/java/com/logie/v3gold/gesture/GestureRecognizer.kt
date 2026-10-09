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
    /** How far two fingers have to move together to count as a two-finger swipe. */
    val twoFingerSwipePx: Float,
    /**
     * Walking: a swipe up to this long tilts the left stick by [walkTilt] however long it is, so
     * the game walks; longer swipes tilt it further, all the way at [runDistancePx], so it runs.
     * 0 tilts the left stick in proportion to the swipe up to [stickRadiusPx], like the camera.
     */
    val walkDistancePx: Float = 0f,
    /** How far a swipe goes to push the left stick all the way (running) when walking is on. */
    val runDistancePx: Float = 0f,
    /** How far the left stick tilts for a walking swipe. */
    val walkTilt: Float = 0.4f,
    /**
     * Swipes also hold the D-pad in their main direction, for menus that ignore the stick. The
     * D-pad moves the character at full speed in only 4 directions, so this is off by default.
     */
    val swipeDpad: Boolean = false,
    /** How long a finger has to stay still before a touch counts as a hold. */
    val holdTimeoutMs: Long = 280,
    /** Maximum time between lifting the first tap and landing the second one. */
    val doubleTapTimeoutMs: Long = 250,
    /** Same as [doubleTapTimeoutMs], for two-finger taps (fingers rarely land together). */
    val twoFingerDoubleTapTimeoutMs: Long = 350,
    /** How long a tap keeps its button pressed so the game is sure to see it. */
    val pulseMs: Long = 100,
    /** How far a swipe tilts the left stick before it presses the D-pad (with [swipeDpad]). */
    val dpadPressTilt: Float = 0.5f,
    /** A swipe pressing the D-pad lets go of it when it comes back closer than this. */
    val dpadReleaseTilt: Float = 0.3f,
)

/**
 * Turns raw touch points into PS Vita controller input for Persona 4 Golden, the same way the
 * Dolphin apps' gestures work:
 *
 * - Swipe anywhere: left stick in any direction, for as long as the finger stays down. A short
 *   swipe walks and a longer one runs ([GestureConfig.walkDistancePx]). With
 *   [GestureConfig.swipeDpad] it also holds the D-pad in its main direction (up, down, left or right).
 * - One finger held while another swipes: right stick (the camera), following the swiping finger
 * - Tap: Cross
 * - Hold: Circle (held for as long as the finger stays down)
 * - Tap, then tap and hold: Square
 * - Double tap: Start
 * - Two fingers swiping up: Triangle; right: L1; left: R1
 * - Two-finger hold: Select (held for as long as the fingers stay down)
 * - Two-finger tap, then two-finger tap and hold: [GestureOutput.openMenu]
 *
 * While one finger walks, a second finger can tap for Cross, hold for Circle or swipe to turn the
 * camera. Holding Circle and walking at the same time is done with one finger: hold, then drag.
 *
 * Coordinates are in pixels with y pointing down; times are in milliseconds from the same clock
 * that [GestureScheduler] uses.
 */
class GestureRecognizer(
    /** Change it only between gestures (when the first finger of one lands). */
    var config: GestureConfig,
    private val scheduler: GestureScheduler,
    private val output: GestureOutput,
) {
    private enum class State {
        PENDING,
        /** Driving the left stick. */
        STICK,
        /** Driving the right stick (the camera). */
        CAMERA,
        HOLD,
        /** Held still while another finger turns the camera. */
        ANCHOR,
        TWO_FINGER,
        IGNORED,
    }

    private class Touch(
        val id: Int,
        val downX: Float,
        val downY: Float,
        /** This touch landed while an earlier tap was waiting to see if it becomes a double tap. */
        val secondTap: Boolean,
        /** This touch landed while another finger was already walking, holding or turning the camera. */
        val secondary: Boolean,
    ) {
        var state = State.PENDING
        var x = downX
        var y = downY
        var holdTimer: Cancellable? = null
        var heldButton: VitaButton? = null
        var originX = downX
        var originY = downY

        val moved: Float
            get() = hypot(x - downX, y - downY)
    }

    private val touches = LinkedHashMap<Int, Touch>()
    private var stickOwner: Touch? = null
    private var cameraOwner: Touch? = null

    /** The D-pad direction the walking finger is holding. */
    private var stickDpad: VitaButton? = null
    /** Keeps a quick flick's D-pad press down for [GestureConfig.pulseMs] so the game sees it. */
    private var dpadPulse: Cancellable? = null
    private var dpadPulseButton: VitaButton? = null

    /** A finished tap waiting for the double tap window to run out before it becomes Cross. */
    private var pendingTap: Cancellable? = null

    private var twoFingerStart = 0L
    private var twoFingerSecond = false
    private var twoFingerMoved = false
    /** The two-finger touch already did its one thing (a swipe button). */
    private var twoFingerSwiped = false
    private var twoFingerTimer: Cancellable? = null
    private var lastTwoFingerTapUp: Long? = null
    private var selectHeld = false
    /** A two-finger touch moved since the last [movesFinished]. */
    private var twoFingerMovePending = false

    private val pressCount = IntArray(VitaButton.entries.size)
    private val pulseTimers = HashSet<Cancellable>()

    fun pointerDown(id: Int, x: Float, y: Float, timeMs: Long) {
        touches[id]?.let { pointerUp(id, it.x, it.y, timeMs) }

        val others = touches.values.toList()

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

        // While a finger walks, holds or anchors the camera, another one can swipe the camera again.
        val secondary = others.any {
            it.state == State.STICK || it.state == State.HOLD || it.state == State.CAMERA || it.state == State.ANCHOR
        }
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

        when (touch.state) {
            State.PENDING -> if (touch.moved > config.touchSlopPx) {
                touch.holdTimer?.cancel()
                touch.holdTimer = null
                if (touch.secondTap) {
                    // Tap followed by a swipe: the tap still counts as Cross.
                    pulse(VitaButton.CROSS)
                }
                if (touch.secondary) {
                    // A second finger swiping while another is down turns the camera.
                    startCamera(touch)
                } else {
                    startStick(touch)
                }
            }

            State.HOLD -> {
                if (stickOwner == null && touch.moved > config.touchSlopPx) {
                    // Holding a button and then dragging walks too (e.g. Circle + stick).
                    stickOwner = touch
                }
                if (stickOwner === touch) {
                    updateStick(touch)
                }
            }

            State.STICK -> updateStick(touch)

            State.CAMERA -> updateCamera(touch)

            State.TWO_FINGER -> twoFingerMovePending = true

            State.ANCHOR, State.IGNORED -> {}
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
                if (stickOwner === touch) {
                    centerStick()
                }
            }

            State.STICK -> centerStick()

            State.CAMERA -> centerCamera()

            State.TWO_FINGER -> {
                // Lifting either finger ends the chance of a hold.
                twoFingerTimer?.cancel()
                twoFingerTimer = null
                if (touches.values.none { it.state == State.TWO_FINGER }) {
                    endTwoFinger(timeMs)
                }
            }

            State.ANCHOR, State.IGNORED -> {}
        }
    }

    /**
     * Call after the moves of every finger in one touch event. Two-finger gestures are judged on
     * both fingers' new positions together.
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
        if (stickOwner != null) {
            centerStick()
        }
        if (cameraOwner != null) {
            centerCamera()
        }
    }

    /** Drops every touch and releases every button, e.g. when the view loses its touch stream. */
    fun cancelAll() {
        for (touch in touches.values) {
            touch.holdTimer?.cancel()
        }
        touches.clear()
        stickOwner = null
        cameraOwner = null
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

    private fun startStick(touch: Touch) {
        if (stickOwner != null) {
            touch.state = State.IGNORED
            return
        }
        touch.state = State.STICK
        stickOwner = touch
        updateStick(touch)
    }

    /**
     * [touch] turns the camera. A finger that's only holding (not walking) becomes its anchor and
     * lets go of its button, so holding to steady the camera doesn't also press Circle.
     */
    private fun startCamera(touch: Touch) {
        if (cameraOwner != null) {
            touch.state = State.IGNORED
            return
        }
        for (held in touches.values) {
            if (held.state == State.HOLD && held !== stickOwner) {
                held.heldButton?.let { release(it) }
                held.heldButton = null
                held.state = State.ANCHOR
            }
        }
        touch.state = State.CAMERA
        cameraOwner = touch
        updateCamera(touch)
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

        val farthest = fingers.maxBy { it.moved }
        val nearest = fingers.minBy { it.moved }
        if (!twoFingerMoved && farthest.moved > config.touchSlopPx * 2) {
            twoFingerMoved = true
            twoFingerTimer?.cancel()
            twoFingerTimer = null
        }
        if (twoFingerSwiped || selectHeld) {
            return
        }

        // One finger holding still while the other swipes: the camera.
        if (farthest.moved > config.touchSlopPx * CAMERA_START_SLOPS && nearest.moved < config.touchSlopPx) {
            twoFingerTimer?.cancel()
            twoFingerTimer = null
            lastTwoFingerTapUp = null
            nearest.state = State.ANCHOR
            if (cameraOwner == null) {
                farthest.state = State.CAMERA
                cameraOwner = farthest
                updateCamera(farthest)
            } else {
                farthest.state = State.IGNORED
            }
            return
        }

        // Both fingers swiping the same way: Triangle (up), L1 (right) or R1 (left).
        if (nearest.moved < config.touchSlopPx) {
            return
        }
        val dx = fingers.sumOf { (it.x - it.downX).toDouble() }.toFloat() / 2
        val dy = fingers.sumOf { (it.y - it.downY).toDouble() }.toFloat() / 2
        if (hypot(dx, dy) < config.twoFingerSwipePx) {
            return
        }
        val button = when {
            abs(dy) >= abs(dx) * SWIPE_SLOPE && fingers.all { it.y < it.downY } -> VitaButton.TRIANGLE
            abs(dx) >= abs(dy) * SWIPE_SLOPE && fingers.all { it.x > it.downX } -> VitaButton.L1
            abs(dx) >= abs(dy) * SWIPE_SLOPE && fingers.all { it.x < it.downX } -> VitaButton.R1
            else -> null
        }
        twoFingerSwiped = true
        button?.let { pulse(it) }
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
            touches[id] = Touch(id, 0f, 0f, secondTap = false, secondary = false).apply {
                state = State.IGNORED
            }
        }
        output.onHoldRecognized()
        output.openMenu()
    }

    /**
     * How far [touch] is from its origin, as (dx, dy, distance). The origin is first dragged along
     * so it's at most [maxDistance] away, so that reversing direction responds immediately.
     */
    private fun offset(touch: Touch, maxDistance: Float): Triple<Float, Float, Float> {
        val dx = touch.x - touch.originX
        val dy = touch.y - touch.originY
        val distance = hypot(dx, dy)
        if (distance <= maxDistance) {
            return Triple(dx, dy, distance)
        }
        touch.originX = touch.x - dx / distance * maxDistance
        touch.originY = touch.y - dy / distance * maxDistance
        return Triple(touch.x - touch.originX, touch.y - touch.originY, maxDistance)
    }

    /** Where [touch] pushes a stick in proportion to its distance, as (x, y) with +y up. */
    private fun linearPosition(touch: Touch): Pair<Float, Float> {
        val radius = config.stickRadiusPx
        val (dx, dy) = offset(touch, radius)
        return dx / radius to -dy / radius
    }

    /**
     * Where [touch] pushes the left stick, as (x, y) with +y up: in its direction, whatever the
     * angle, by [GestureConfig.walkTilt] for a short swipe and further for a longer one.
     */
    private fun walkPosition(touch: Touch): Pair<Float, Float> {
        val walk = config.walkDistancePx
        val run = config.runDistancePx
        if (walk <= 0f || run <= walk) {
            return linearPosition(touch)
        }
        val (dx, dy, distance) = offset(touch, run)
        if (distance < config.touchSlopPx / 2) {
            // Back where the swipe started: stand still.
            return 0f to 0f
        }
        val tilt = if (distance <= walk) {
            config.walkTilt
        } else {
            config.walkTilt + (1f - config.walkTilt) * (distance - walk) / (run - walk)
        }
        return dx / distance * tilt to -dy / distance * tilt
    }

    private fun updateStick(touch: Touch) {
        val (x, y) = walkPosition(touch)
        output.setStick(Stick.LEFT, x, y)
        setStickDpad(if (config.swipeDpad) dpadFor(x, y) else null)
    }

    private fun updateCamera(touch: Touch) {
        val (x, y) = linearPosition(touch)
        output.setStick(Stick.RIGHT, x, y)
    }

    private fun centerStick() {
        stickOwner = null
        output.setStick(Stick.LEFT, 0f, 0f)
        setStickDpad(null)
    }

    private fun centerCamera() {
        cameraOwner = null
        output.setStick(Stick.RIGHT, 0f, 0f)
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

        /** Two-finger swipes have to go mostly one way: within about 34° of it. */
        const val SWIPE_SLOPE = 1.5f

        /**
         * With two fingers down, one has to move this many touch slops (while the other stays
         * within one) to start the camera. Fingers swiping together both move by then.
         */
        const val CAMERA_START_SLOPS = 3f
    }
}
