package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.CameraPose
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

// Pure Kotlin (no Android, no Compose): the live scene's camera and its touch handling, run by the JVM tests
// (unit-tests/.../simulation/SceneCameraTest.kt). WoW's coordinates: Z up, yaw radians about Z from +X.

/**
 * A critically damped spring toward a goal: the fastest approach that never overshoots from rest, integrated exactly for
 * any time step (the same path at 30, 60 or 120 frames a second). [omega] is its speed (about 4.6 / omega seconds to
 * settle within 1%).
 */
class Spring(var value: Float, var omega: Float) {
    var velocity = 0f

    fun update(goal: Float, dt: Float): Float {
        if (dt <= 0f) return value
        val y = value - goal
        val e = exp(-omega * dt)
        val t = (velocity + omega * y) * dt
        value = goal + (y + t) * e
        velocity = (velocity - omega * t) * e
        return value
    }

    fun snap(to: Float) { value = to; velocity = 0f }
}

/** Ease in and out (cubic) over 0..1: slow to start, slow to stop, no step at either end. */
fun easeInOut(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f
}

/**
 * The live scene's camera: the room's own view, or the NPC camera orbiting the selected NPC. Every value it shows is
 * smoothed: the orbit (yaw, pitch, distance) follows its goal on [Spring]s, the target follows the NPC's walking position
 * on its own slower springs (walking never jitters it), a flick keeps turning with friction, the pitch and distance give a
 * little past their limits while a finger holds them and spring back after, and going between the room and the NPC camera
 * eases over [TRANSITION_S]. The touch side ([dragBy], [release], [pinch], [twist], [zoomBy], [setNpc]) may be called from
 * the main thread; [update] runs once a frame on the GL thread and writes the pose into [eyeX]..[fov] (nothing allocated).
 */
class SceneCamera(room: CameraPose) {
    companion object {
        const val MIN_PITCH = -0.10f
        const val MAX_PITCH = 1.30f
        const val MIN_DISTANCE = 1.2f
        const val MAX_DISTANCE = 8f
        /** How far a finger may push past a limit (it springs back on release). */
        const val PITCH_GIVE = 0.12f
        const val DISTANCE_GIVE = 0.15f
        /** Room camera to NPC camera and back, seconds. */
        const val TRANSITION_S = 0.35f
        /** A flick's turning dies away at this rate (1/s). */
        const val FRICTION = 5f
        /** The fastest a flick turns, radians a second. */
        const val MAX_SPIN = 6f
        const val START_PITCH = 0.32f
        const val START_DISTANCE = 4.5f
        /** The orbit's springs and the target's (slower: a walk's steps are smoothed out). */
        const val ORBIT_OMEGA = 18f
        const val TARGET_OMEGA = 7f
    }

    @Volatile var room: CameraPose = room

    // ---- the touch side's requests, taken by [update] (guarded by `this`)
    private var pendYaw = 0f
    private var pendPitch = 0f
    private var pendZoom = 1f
    private var pendFlickYaw = Float.NaN
    private var pendFlickPitch = 0f
    private var pendHold: Boolean? = null
    private var pendNpc: Boolean? = null
    private var pendSnap = false

    // ---- the GL thread's state
    private var goalYaw = 0f
    private var goalPitch = START_PITCH
    private var goalDistance = START_DISTANCE
    private val yaw = Spring(0f, ORBIT_OMEGA)
    private val pitch = Spring(START_PITCH, ORBIT_OMEGA)
    private val distance = Spring(START_DISTANCE, ORBIT_OMEGA)
    private val tx = Spring(0f, TARGET_OMEGA)
    private val ty = Spring(0f, TARGET_OMEGA)
    private val tz = Spring(0f, TARGET_OMEGA)
    private var spinYaw = 0f
    private var spinPitch = 0f
    private var holding = false
    private var hasTarget = false
    /** 0 the room's view, 1 the NPC camera; [blendFrom] where a transition started, [blendTime] how far it is. */
    private var npc = false
    private var blend = 0f
    private var blendFrom = 0f
    private var blendTime = TRANSITION_S

    /** The pose drawn this frame (written by [update]). */
    var eyeX = room.eye.x; private set
    var eyeY = room.eye.y; private set
    var eyeZ = room.eye.z; private set
    var atX = room.at.x; private set
    var atY = room.at.y; private set
    var atZ = room.at.z; private set
    var fov = room.fov; private set

    /** Whether anything is still moving (a transition, a spring, a flick): false once the camera rests. */
    var moving = false; private set

    /** 0..1: how far the view is the NPC camera's (eased). */
    val npcAmount: Float get() = easeInOut(blend)

    /** Radians a finger's move of one pixel turns the orbit on a view [viewH] pixels high (half the height: a quarter turn). */
    fun radiansPerPixel(viewH: Float) = (PI.toFloat() / 2f) / (viewH / 2f).coerceAtLeast(1f)

    /** The NPC camera on or off (eased over [TRANSITION_S] from wherever the view is now). */
    @Synchronized fun setNpc(on: Boolean) { pendNpc = on }

    /** A finger moved [dx], [dy] pixels on a view [viewH] high: the orbit turns with it (no flick while it holds). */
    @Synchronized fun dragBy(dx: Float, dy: Float, viewH: Float) {
        val k = radiansPerPixel(viewH)
        pendYaw -= dx * k
        pendPitch += dy * k
        pendHold = true
        pendFlickYaw = 0f; pendFlickPitch = 0f
    }

    /** The finger let go moving [vx], [vy] pixels a second: the orbit keeps turning and slows (0, 0: it stops). */
    @Synchronized fun release(vx: Float, vy: Float, viewH: Float) {
        val k = radiansPerPixel(viewH)
        pendFlickYaw = (-vx * k).coerceIn(-MAX_SPIN, MAX_SPIN)
        pendFlickPitch = (vy * k).coerceIn(-MAX_SPIN, MAX_SPIN)
        pendHold = false
    }

    /** Two fingers pinched by [factor] (more than 1: spread, nearer) while held: a little give past the limits. */
    @Synchronized fun pinch(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        pendZoom *= factor
        pendHold = true
        pendFlickYaw = 0f; pendFlickPitch = 0f
    }

    /** Two fingers turned by [radians] (counter-clockwise on the screen): the orbit turns with them. */
    @Synchronized fun twist(radians: Float) {
        if (radians.isFinite()) pendYaw += radians
    }

    /** The zoom buttons: nearer (more than 1) or further, eased by the springs; held to the hard limits. */
    @Synchronized fun zoomBy(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        pendZoom *= factor
        pendHold = false
    }

    /** Fingers up without a flick (a pinch ended): limits spring back. */
    @Synchronized fun letGo() { pendHold = false }

    /** The next frame takes the target where it is, without gliding (a new scene). */
    @Synchronized fun snapTarget() { pendSnap = true }

    /**
     * One frame, [dt] seconds after the last, the selected NPC's point to look at [fx], [fy], [fz] ([hasFocus] false: none,
     * the NPC camera goes back to the room's view). GL thread.
     */
    fun update(dt: Float, hasFocus: Boolean, fx: Float, fy: Float, fz: Float) {
        val step = if (dt.isFinite()) dt.coerceIn(0f, 0.1f) else 0f
        var dYaw: Float; var dPitch: Float; var zoom: Float; var flickYaw: Float; var flickPitch: Float
        val hold: Boolean?; val wantNpc: Boolean?; val snap: Boolean
        synchronized(this) {
            dYaw = pendYaw; dPitch = pendPitch; zoom = pendZoom; flickYaw = pendFlickYaw; flickPitch = pendFlickPitch
            hold = pendHold; wantNpc = pendNpc; snap = pendSnap
            pendYaw = 0f; pendPitch = 0f; pendZoom = 1f; pendFlickYaw = Float.NaN; pendFlickPitch = 0f
            pendHold = null; pendNpc = null; pendSnap = false
        }
        if (hold != null) holding = hold
        if (!flickYaw.isNaN()) { spinYaw = flickYaw; spinPitch = flickPitch }

        // the NPC camera on or off: a transition from where the view is now (a reversal mid-way goes back smoothly)
        val on = (wantNpc ?: npc) && hasFocus
        if (on != npc) {
            if (on) enterOrbit(fx, fy, fz)
            npc = on
            blendFrom = blend
            blendTime = 0f
        }
        blendTime = (blendTime + step).coerceAtMost(TRANSITION_S)
        val goal = if (npc) 1f else 0f
        val p = blendTime / TRANSITION_S
        blend = blendFrom + (goal - blendFrom) * p

        // the orbit's goals: a finger past a limit meets resistance, a flick slows with friction
        goalYaw += dYaw
        goalPitch = pushed(goalPitch, dPitch, MIN_PITCH, MAX_PITCH, PITCH_GIVE)
        if (zoom != 1f) goalDistance = zoomed(goalDistance, zoom)
        if (!holding && (spinYaw != 0f || spinPitch != 0f)) {
            goalYaw += spinYaw * step
            goalPitch += spinPitch * step
            val f = exp(-FRICTION * step)
            spinYaw *= f; spinPitch *= f
            if (goalPitch <= MIN_PITCH || goalPitch >= MAX_PITCH) spinPitch = 0f
            if (abs(spinYaw) < 0.02f) spinYaw = 0f
            if (abs(spinPitch) < 0.02f) spinPitch = 0f
        }
        if (!holding) {
            goalPitch = goalPitch.coerceIn(MIN_PITCH, MAX_PITCH)
            goalDistance = goalDistance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        }
        yaw.update(goalYaw, step)
        pitch.update(goalPitch, step)
        distance.update(goalDistance, step)

        // the target glides after the NPC (a new NPC: it glides there too)
        if (hasFocus) {
            if (!hasTarget || snap) { tx.snap(fx); ty.snap(fy); tz.snap(fz); hasTarget = true }
            tx.update(fx, step); ty.update(fy, step); tz.update(fz, step)
        }

        val cp = cos(pitch.value)
        val ox = tx.value + cp * cos(yaw.value) * distance.value
        val oy = ty.value + cp * sin(yaw.value) * distance.value
        val oz = tz.value + sin(pitch.value) * distance.value
        val r = room
        val e = easeInOut(blend)
        eyeX = r.eye.x + (ox - r.eye.x) * e
        eyeY = r.eye.y + (oy - r.eye.y) * e
        eyeZ = r.eye.z + (oz - r.eye.z) * e
        atX = r.at.x + (tx.value - r.at.x) * e
        atY = r.at.y + (ty.value - r.at.y) * e
        atZ = r.at.z + (tz.value - r.at.z) * e
        fov = r.fov
        moving = blendTime < TRANSITION_S || spinYaw != 0f || spinPitch != 0f || holding ||
            (blend > 0f && (abs(yaw.velocity) + abs(pitch.velocity) + abs(distance.velocity) +
                abs(tx.velocity) + abs(ty.velocity) + abs(tz.velocity) > 1e-3f))
    }

    /** The orbit starts where the room's view looks from (so the swing is short) at the starting pitch and distance. */
    private fun enterOrbit(fx: Float, fy: Float, fz: Float) {
        val r = room
        val wasOrbiting = blend > 0f
        if (!wasOrbiting) {
            val startYaw = atan2(r.eye.y - fy, r.eye.x - fx)
            // the nearest equivalent of that angle to the current one (no whole turn on the way)
            val turns = Math.round((yaw.value - startYaw) / (2f * PI.toFloat()))
            goalYaw = startYaw + turns * 2f * PI.toFloat()
            goalPitch = START_PITCH
            goalDistance = START_DISTANCE
            yaw.snap(goalYaw); pitch.snap(goalPitch); distance.snap(goalDistance)
            tx.snap(fx); ty.snap(fy); tz.snap(fz)
            hasTarget = true
            spinYaw = 0f; spinPitch = 0f
        }
    }

    private fun pushed(value: Float, delta: Float, min: Float, max: Float, give: Float): Float {
        if (delta == 0f) return value
        val outward = (delta > 0f && value >= max) || (delta < 0f && value <= min)
        val next = value + if (outward) delta * 0.35f else delta
        return if (holding) next.coerceIn(min - give, max + give) else next.coerceIn(min, max)
    }

    private fun zoomed(value: Float, factor: Float): Float {
        val next = value / factor
        return if (holding) {
            val outward = (factor < 1f && value >= MAX_DISTANCE) || (factor > 1f && value <= MIN_DISTANCE)
            val eased = if (outward) value + (next - value) * 0.35f else next
            eased.coerceIn(MIN_DISTANCE * (1f - DISTANCE_GIVE), MAX_DISTANCE * (1f + DISTANCE_GIVE))
        } else next.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    /** The current pose as a [CameraPose] (tests and debugging; allocates). */
    fun pose(): CameraPose = CameraPose(
        com.logie.wifecraft.story.Vec3(eyeX, eyeY, eyeZ), com.logie.wifecraft.story.Vec3(atX, atY, atZ), fov)
}

/**
 * One finger on the 3D view, told apart: a tap (it never went further than [slop] pixels, one finger, short) picks what
 * is under it; a drag orbits, starting from where it crossed the slop (no jump by the slop's distance); a second finger
 * makes it a pinch for the rest of the gesture (never a tap, never an orbit again until all fingers are up). The release
 * velocity is the finger's over its last [VELOCITY_WINDOW_MS] (zero when it had stopped before letting go). Pure.
 */
class TouchGesture(private val slop: Float, private val tapMs: Long = 500L) {
    enum class Kind { PENDING, DRAG, MULTI }

    companion object {
        const val VELOCITY_WINDOW_MS = 100L
        /** A finger still this long before it lifts made no flick. */
        const val STILL_MS = 60L
        private const val SAMPLES = 16
    }

    var kind = Kind.PENDING; private set
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    // the last few moves, a ring
    private val ts = LongArray(SAMPLES)
    private val xs = FloatArray(SAMPLES)
    private val ys = FloatArray(SAMPLES)
    private var n = 0
    private var head = 0

    /** The orbit's move the last [move] made (pixels; 0 unless dragging). */
    var dx = 0f; private set
    var dy = 0f; private set

    fun down(x: Float, y: Float, timeMs: Long) {
        kind = Kind.PENDING
        startX = x; startY = y; lastX = x; lastY = y; downAt = timeMs
        n = 0; head = 0; dx = 0f; dy = 0f
        sample(x, y, timeMs)
    }

    /** The first finger moved to [x], [y]: true when it orbits by [dx], [dy]. */
    fun move(x: Float, y: Float, timeMs: Long): Boolean {
        dx = 0f; dy = 0f
        if (kind == Kind.MULTI) return false
        if (kind == Kind.PENDING) {
            if (hypot(x - startX, y - startY) < slop) return false
            kind = Kind.DRAG
            lastX = x; lastY = y       // the orbit starts here: the slop itself never turns it
            n = 0; head = 0
            sample(x, y, timeMs)
            return false
        }
        dx = x - lastX; dy = y - lastY
        lastX = x; lastY = y
        sample(x, y, timeMs)
        return dx != 0f || dy != 0f
    }

    /** A second finger came down: a pinch from now to the end of the gesture. */
    fun multi() { kind = Kind.MULTI; dx = 0f; dy = 0f }

    /** All fingers up at [timeMs]: whether it was a tap. */
    fun up(timeMs: Long): Boolean = kind == Kind.PENDING && timeMs - downAt <= tapMs

    /** The release velocity in pixels a second (x then y), as the finger moved over the last moments before [timeMs]. */
    fun velocity(timeMs: Long, out: FloatArray) {
        out[0] = 0f; out[1] = 0f
        if (kind != Kind.DRAG || n < 2) return
        val newest = (head - 1 + SAMPLES) % SAMPLES
        if (timeMs - ts[newest] > STILL_MS) return
        var oldest = newest
        for (i in 1 until n) {
            val j = (newest - i + SAMPLES) % SAMPLES
            if (ts[newest] - ts[j] > VELOCITY_WINDOW_MS) break
            oldest = j
        }
        val dt = (ts[newest] - ts[oldest]) / 1000f
        if (dt <= 0.004f) return
        out[0] = (xs[newest] - xs[oldest]) / dt
        out[1] = (ys[newest] - ys[oldest]) / dt
    }

    private fun sample(x: Float, y: Float, t: Long) {
        ts[head] = t; xs[head] = x; ys[head] = y
        head = (head + 1) % SAMPLES
        if (n < SAMPLES) n++
    }
}

/** The distance between two fingers and their angle, for a pinch's zoom and twist from one move to the next. Pure. */
object TwoFingers {
    fun span(x0: Float, y0: Float, x1: Float, y1: Float): Float = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
    fun angle(x0: Float, y0: Float, x1: Float, y1: Float): Float = atan2(y1 - y0, x1 - x0)
    /** The turn from [before] to [after] radians, the short way round. */
    fun turn(before: Float, after: Float): Float {
        var d = after - before
        val tau = 2f * PI.toFloat()
        while (d > PI) d -= tau
        while (d < -PI) d += tau
        return d
    }
}
