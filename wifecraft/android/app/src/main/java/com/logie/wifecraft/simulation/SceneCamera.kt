package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.CameraPose
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

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
 * The live scene's three cameras: [ROOM] the place's own fixed view, [ORBIT] a free orbit around a point of the room (it starts
 * on the room's view, then turns, zooms, pans and rises from there), [FOLLOW] the orbit around the controlled NPC (it glides
 * after them as they walk).
 */
enum class CameraMode {
    ROOM, ORBIT, FOLLOW;

    /** The camera button's next mode: Room, Orbit, Follow (only with someone controlled to follow), then Room again. */
    fun next(canFollow: Boolean): CameraMode = when (this) {
        ROOM -> ORBIT
        ORBIT -> if (canFollow) FOLLOW else ROOM
        FOLLOW -> ROOM
    }
}

/** The orbit's pure maths (no state): the yaw's wrap, the target's height and pan limits, a finger's pixels in yards. */
object OrbitMath {
    private const val TAU = (2.0 * PI).toFloat()

    /** [a] radians brought within -PI..PI, the same direction (not finite: 0). */
    fun wrap(a: Float): Float {
        if (!a.isFinite()) return 0f
        var x = a % TAU
        if (x > PI) x -= TAU
        if (x < -PI) x += TAU
        return x
    }

    /**
     * The lowest lift (yards above the [baseZ] the orbit looks at) with the floor at [floorZ] (NaN: unknown): never below
     * [SceneCamera.MIN_TARGET_H] over the floor nor [SceneCamera.MAX_LIFT] under the base; 0 always allowed.
     */
    fun liftLow(baseZ: Float, floorZ: Float): Float {
        val byRoom = if (floorZ.isNaN()) -SceneCamera.MAX_LIFT else floorZ + SceneCamera.MIN_TARGET_H - baseZ
        return min(0f, max(-SceneCamera.MAX_LIFT, byRoom))
    }

    /** The highest lift: never above [SceneCamera.MAX_TARGET_H] over the floor (about the ceiling) nor [SceneCamera.MAX_LIFT]. */
    fun liftHigh(baseZ: Float, floorZ: Float): Float {
        val byRoom = if (floorZ.isNaN()) SceneCamera.MAX_LIFT else floorZ + SceneCamera.MAX_TARGET_H - baseZ
        return max(0f, min(SceneCamera.MAX_LIFT, byRoom))
    }

    /** The factor that shortens a ground pan ([x], [y] yards) to at most [limit] long (1: it is within it). */
    fun panScale(x: Float, y: Float, limit: Float): Float {
        val d = hypot(x, y)
        return if (d <= limit || d <= 0f) 1f else limit / d
    }

    /** Yards a finger's pixel moves the target at [distance] with a vertical field of view of [fovDeg] on a view [viewH] high. */
    fun yardsPerPixel(distance: Float, fovDeg: Float, viewH: Float): Float {
        val fov = if (fovDeg.isFinite() && fovDeg > 1f && fovDeg < 170f) fovDeg else 50f
        return 2f * distance * tan(Math.toRadians(fov / 2.0).toFloat()) / viewH.coerceAtLeast(1f)
    }
}

/**
 * The live scene's camera: the room's own view, or an orbit ([CameraMode.ORBIT] round a point of the room,
 * [CameraMode.FOLLOW] round the controlled NPC). Every value it shows is smoothed: the orbit (yaw, pitch, distance) follows
 * its goal on [Spring]s, the point it looks at is a base (the room's point, or the NPC's walking position on slower springs:
 * walking never jitters it) plus an offset the player moves (a two-finger pan along the ground, the lift up and down) on the
 * orbit's springs; a flick keeps turning with friction, the pitch and distance give a little past their limits while a finger
 * holds them and spring back after, and going between the room and an orbit eases over [TRANSITION_S]. Going between Orbit
 * and Follow keeps the view where it is and glides the target. The touch side ([setMode], [dragBy], [release], [pinch],
 * [twist], [pan], [liftBy], [zoomBy]) may be called from the main thread; [update] runs once a frame on the GL thread and
 * writes the pose into [eyeX]..[fov] (nothing allocated).
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
        /** Room camera to an orbit and back, seconds. */
        const val TRANSITION_S = 0.35f
        /** A flick's turning dies away at this rate (1/s). */
        const val FRICTION = 5f
        /** The fastest a flick turns, radians a second. */
        const val MAX_SPIN = 6f
        const val START_PITCH = 0.32f
        const val START_DISTANCE = 4.5f
        /** The orbit's springs (and the player's pan and lift) and the base target's (slower: a walk's steps are smoothed out). */
        const val ORBIT_OMEGA = 18f
        const val TARGET_OMEGA = 7f
        /** The farthest the target is panned along the ground from its base, yards. */
        const val MAX_PAN = 12f
        /** The most the target is lifted or lowered from its base, yards. */
        const val MAX_LIFT = 3f
        /** The target's lowest and highest over the floor (when the floor is known): just above it, about the ceiling. */
        const val MIN_TARGET_H = 0.1f
        const val MAX_TARGET_H = 4.0f
        /** The eye never goes lower than this over the floor (when the floor is known). */
        const val EYE_ABOVE_FLOOR = 0.2f
        /** One press (or repeat) of the up and down buttons, yards. */
        const val LIFT_STEP = 0.15f
        /** Into a directed pose (cinematic mode) and back to the player's camera, seconds: at least this, longer when the two
         *  are far apart (on average no faster than [DIRECT_SPEED] yards a second). */
        const val DIRECT_S = 1.2f
        const val DIRECT_SPEED = 2.5f
    }

    @Volatile var room: CameraPose = room
    /** The stage's floor height (NaN: unknown): the target's lift and the eye are kept within the room. Any thread. */
    @Volatile var floorZ: Float = Float.NaN

    // ---- the touch side's requests, taken by [update] (guarded by `this`)
    private var pendYaw = 0f
    private var pendPitch = 0f
    private var pendZoom = 1f
    private var pendFlickYaw = Float.NaN
    private var pendFlickPitch = 0f
    private var pendHold: Boolean? = null
    private var pendMode: CameraMode? = null
    private var pendSnap = false
    private var pendPanX = 0f
    private var pendPanY = 0f
    private var pendPanH = 1f
    private var pendLift = 0f

    // ---- the GL thread's state
    private var goalYaw = 0f
    private var goalPitch = START_PITCH
    private var goalDistance = START_DISTANCE
    private val yaw = Spring(0f, ORBIT_OMEGA)
    private val pitch = Spring(START_PITCH, ORBIT_OMEGA)
    private val distance = Spring(START_DISTANCE, ORBIT_OMEGA)
    // the base the orbit looks at (the room's point, or the NPC) and the player's offset from it (pan x, y and lift)
    private val tx = Spring(0f, TARGET_OMEGA)
    private val ty = Spring(0f, TARGET_OMEGA)
    private val tz = Spring(0f, TARGET_OMEGA)
    private var goalPanX = 0f
    private var goalPanY = 0f
    private var goalLift = 0f
    private val px = Spring(0f, ORBIT_OMEGA)
    private val py = Spring(0f, ORBIT_OMEGA)
    private val pz = Spring(0f, ORBIT_OMEGA)
    private var spinYaw = 0f
    private var spinPitch = 0f
    private var holding = false
    private var hasTarget = false
    /** What the player asked for, what is shown ([ROOM] while a Follow has nobody to follow yet), and the last orbit. */
    private var requested = CameraMode.ROOM
    private var shown = CameraMode.ROOM
    private var orbitKind = CameraMode.ORBIT
    /** 0 the room's view, 1 an orbit; [blendFrom] where a transition started, [blendTime] how far it is. */
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

    // ---- cinematic mode: a pose from outside (CinematicDirector) laid over the camera's own, eased in and out over DIRECT_S;
    // the player's camera goes on underneath untouched (its mode, orbit and springs), so leaving glides back to it exactly
    @Volatile private var directWanted = false
    private var directBlend = 0f
    private var directHas = false
    private var dEyeX = 0f; private var dEyeY = 0f; private var dEyeZ = 0f
    private var dAtX = 0f; private var dAtY = 0f; private var dAtZ = 0f
    private var dFov = 50f

    /** Cinematic mode on (the directed pose eases in) or off (it eases back to the player's camera). Any thread. */
    fun setDirecting(on: Boolean) { directWanted = on }
    val directing: Boolean get() = directWanted
    /** 0..1: how far the view is the directed pose (linear in time; eased when applied). GL thread. */
    val directAmount: Float get() = directBlend

    /** This frame's directed pose (GL thread, before [update]); nothing given: the player's camera stays. */
    fun directPose(ex: Float, ey: Float, ez: Float, ax: Float, ay: Float, az: Float, f: Float) {
        dEyeX = ex; dEyeY = ey; dEyeZ = ez; dAtX = ax; dAtY = ay; dAtZ = az; dFov = f
        directHas = true
    }

    /** Whether anything is still moving (a transition, a spring, a flick): false once the camera rests. */
    var moving = false; private set

    /** The mode shown now (GL thread's view of it; tests). */
    val mode: CameraMode get() = shown

    /** 0..1: how far the view is an orbit's (eased). */
    val orbitAmount: Float get() = easeInOut(blend)
    val npcAmount: Float get() = orbitAmount

    /** The orbit's yaw now (radians, kept within about -PI..PI) and its lift goal (yards). Tests. */
    internal val orbitYaw: Float get() = yaw.value
    internal val liftGoal: Float get() = goalLift

    /** Radians a finger's move of one pixel turns the orbit on a view [viewH] pixels high (half the height: a quarter turn). */
    fun radiansPerPixel(viewH: Float) = (PI.toFloat() / 2f) / (viewH / 2f).coerceAtLeast(1f)

    /** The camera's mode (eased over [TRANSITION_S] to or from the room's view; Orbit and Follow glide into each other). */
    @Synchronized fun setMode(next: CameraMode) { pendMode = next }

    /** The NPC camera on ([CameraMode.FOLLOW]) or off ([CameraMode.ROOM]). */
    fun setNpc(on: Boolean) = setMode(if (on) CameraMode.FOLLOW else CameraMode.ROOM)

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

    /**
     * Two fingers moved together by [dx], [dy] pixels on a view [viewH] high: the room is held under them. Sideways moves
     * the target along the ground (left and right of the view); up and down lowers and raises it (fingers down: the
     * camera rises), within the room.
     */
    @Synchronized fun pan(dx: Float, dy: Float, viewH: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        pendPanX += dx; pendPanY += dy; pendPanH = viewH
        pendHold = true
        pendFlickYaw = 0f; pendFlickPitch = 0f
    }

    /** The up and down buttons: the target (and the camera with it) [yards] higher, or lower when negative; eased. */
    @Synchronized fun liftBy(yards: Float) {
        if (yards.isFinite()) pendLift += yards
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
     * One frame, [dt] seconds after the last, the selected NPC's point to look at [fx], [fy], [fz] ([hasFocus] false: none;
     * Follow then keeps looking where the NPC last was, and waits for one before it starts). GL thread.
     */
    fun update(dt: Float, hasFocus: Boolean, fx: Float, fy: Float, fz: Float) {
        val step = if (dt.isFinite()) dt.coerceIn(0f, 0.1f) else 0f
        var dYaw: Float; var dPitch: Float; var zoom: Float; var flickYaw: Float; var flickPitch: Float
        var panX: Float; var panY: Float; var panH: Float; var lift: Float
        val hold: Boolean?; val want: CameraMode?; val snap: Boolean
        synchronized(this) {
            dYaw = pendYaw; dPitch = pendPitch; zoom = pendZoom; flickYaw = pendFlickYaw; flickPitch = pendFlickPitch
            panX = pendPanX; panY = pendPanY; panH = pendPanH; lift = pendLift
            hold = pendHold; want = pendMode; snap = pendSnap
            pendYaw = 0f; pendPitch = 0f; pendZoom = 1f; pendFlickYaw = Float.NaN; pendFlickPitch = 0f
            pendPanX = 0f; pendPanY = 0f; pendLift = 0f
            pendHold = null; pendMode = null; pendSnap = false
        }
        if (hold != null) holding = hold
        if (!flickYaw.isNaN()) { spinYaw = flickYaw; spinPitch = flickPitch }
        val r = room

        // the mode: Follow waits in the room's view until there is someone to follow, then keeps their last spot
        if (want != null) requested = want
        val waiting = requested == CameraMode.FOLLOW && !hasFocus && shown == CameraMode.ROOM && (blend <= 0f || !hasTarget)
        val next = if (waiting) CameraMode.ROOM else requested
        if (next != shown) changeMode(next, fx, fy, fz)
        blendTime = (blendTime + step).coerceAtMost(TRANSITION_S)
        val goal = if (shown != CameraMode.ROOM) 1f else 0f
        blend = blendFrom + (goal - blendFrom) * (blendTime / TRANSITION_S)

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
            goalDistance = goalDistance.coerceIn(MIN_DISTANCE, maxDistance())
        }
        // the yaw kept within a turn of zero (the spring moved by the same whole turn: nothing seen changes)
        if (goalYaw > PI || goalYaw < -PI) {
            val wrapped = OrbitMath.wrap(goalYaw)
            yaw.value += wrapped - goalYaw
            goalYaw = wrapped
        }
        yaw.update(goalYaw, step)
        pitch.update(goalPitch, step)
        distance.update(goalDistance, step)

        // the base: the room's point for an orbit, the NPC for Follow (it glides after them; a new NPC: it glides there too)
        if (orbitKind == CameraMode.ORBIT) {
            tx.update(r.at.x, step); ty.update(r.at.y, step); tz.update(r.at.z, step)
        } else if (hasFocus) {
            if (!hasTarget || snap) { tx.snap(fx); ty.snap(fy); tz.snap(fz); hasTarget = true }
            tx.update(fx, step); ty.update(fy, step); tz.update(fz, step)
        }

        // the player's offset: two fingers pan along the ground and lift; the buttons lift; all of it within the room
        if (panX != 0f || panY != 0f) {
            val k = OrbitMath.yardsPerPixel(distance.value, r.fov, panH)
            val rightX = -sin(yaw.value)
            val rightY = cos(yaw.value)
            goalPanX -= rightX * panX * k
            goalPanY -= rightY * panX * k
            goalLift += panY * k
        }
        goalLift += lift
        val s = OrbitMath.panScale(goalPanX, goalPanY, MAX_PAN)
        goalPanX *= s; goalPanY *= s
        val baseZ = if (orbitKind == CameraMode.ORBIT) r.at.z else if (hasFocus) fz else tz.value
        val floor = floorZ
        goalLift = goalLift.coerceIn(OrbitMath.liftLow(baseZ, floor), OrbitMath.liftHigh(baseZ, floor))
        px.update(goalPanX, step); py.update(goalPanY, step); pz.update(goalLift, step)

        val ax = tx.value + px.value
        val ay = ty.value + py.value
        val az = tz.value + pz.value
        val cp = cos(pitch.value)
        val ox = ax + cp * cos(yaw.value) * distance.value
        val oy = ay + cp * sin(yaw.value) * distance.value
        var oz = az + sin(pitch.value) * distance.value
        if (!floor.isNaN()) oz = max(oz, floor + EYE_ABOVE_FLOOR)
        val e = easeInOut(blend)
        eyeX = r.eye.x + (ox - r.eye.x) * e
        eyeY = r.eye.y + (oy - r.eye.y) * e
        eyeZ = r.eye.z + (oz - r.eye.z) * e
        atX = r.at.x + (ax - r.at.x) * e
        atY = r.at.y + (ay - r.at.y) * e
        atZ = r.at.z + (az - r.at.z) * e
        fov = r.fov
        // cinematic mode's pose over it (eased in and out; the player's camera above is the one it leaves and returns to)
        val dGoal = if (directWanted && directHas) 1f else 0f
        if (dGoal != directBlend) {
            val apart = sqrt((dEyeX - eyeX) * (dEyeX - eyeX) + (dEyeY - eyeY) * (dEyeY - eyeY) + (dEyeZ - eyeZ) * (dEyeZ - eyeZ))
            val rate = step / max(DIRECT_S, if (directHas) apart / DIRECT_SPEED else 0f)
            directBlend = if (dGoal > directBlend) (directBlend + rate).coerceAtMost(1f) else (directBlend - rate).coerceAtLeast(0f)
        }
        if (directBlend > 0f && directHas) {
            val k = easeInOut(directBlend)
            eyeX += (dEyeX - eyeX) * k; eyeY += (dEyeY - eyeY) * k; eyeZ += (dEyeZ - eyeZ) * k
            atX += (dAtX - atX) * k; atY += (dAtY - atY) * k; atZ += (dAtZ - atZ) * k
            fov += (dFov - fov) * k
        } else if (!directWanted) directHas = false
        moving = directBlend > 0f || blendTime < TRANSITION_S || spinYaw != 0f || spinPitch != 0f || holding ||
            (blend > 0f && (abs(yaw.velocity) + abs(pitch.velocity) + abs(distance.velocity) +
                abs(tx.velocity) + abs(ty.velocity) + abs(tz.velocity) +
                abs(px.velocity) + abs(py.velocity) + abs(pz.velocity) > 1e-3f))
    }

    /** From the shown mode to [next]: the room's view eases; one orbit to the other keeps the view and glides the target. */
    private fun changeMode(next: CameraMode, fx: Float, fy: Float, fz: Float) {
        val from = shown
        if (next == CameraMode.ROOM) {
            shown = next
            blendFrom = blend; blendTime = 0f
            return
        }
        if (from == CameraMode.ROOM) {
            if (blend <= 0f) enterFromRoom(next, fx, fy, fz) else if (next != orbitKind) switchOrbit(next)
            blendFrom = blend; blendTime = 0f
        } else {
            switchOrbit(next)
        }
        shown = next
        orbitKind = next
    }

    /**
     * An orbit from the room's view. Orbit starts exactly on the room's view (its point, from where the room looks: nothing
     * moves until a finger does); Follow starts on the NPC from the room's side at the starting pitch and distance.
     */
    private fun enterFromRoom(next: CameraMode, fx: Float, fy: Float, fz: Float) {
        val r = room
        if (next == CameraMode.ORBIT) {
            val vx = r.eye.x - r.at.x
            val vy = r.eye.y - r.at.y
            val vz = r.eye.z - r.at.z
            val d = sqrt(vx * vx + vy * vy + vz * vz).coerceAtLeast(1e-3f)
            goalYaw = OrbitMath.wrap(atan2(vy, vx))
            goalPitch = asin((vz / d).coerceIn(-1f, 1f)).coerceIn(MIN_PITCH, MAX_PITCH)
            goalDistance = d.coerceIn(MIN_DISTANCE, max(MAX_DISTANCE, d))
            tx.snap(r.at.x); ty.snap(r.at.y); tz.snap(r.at.z)
        } else {
            goalYaw = OrbitMath.wrap(atan2(r.eye.y - fy, r.eye.x - fx))
            goalPitch = START_PITCH
            goalDistance = START_DISTANCE
            tx.snap(fx); ty.snap(fy); tz.snap(fz)
        }
        yaw.snap(goalYaw); pitch.snap(goalPitch); distance.snap(goalDistance)
        goalPanX = 0f; goalPanY = 0f; goalLift = 0f
        px.snap(0f); py.snap(0f); pz.snap(0f)
        hasTarget = true
        spinYaw = 0f; spinPitch = 0f
    }

    /**
     * Orbit to Follow: the base glides from where it is to the NPC and the player's offset back to none (the sum moves
     * smoothly). Follow to Orbit: the target stays exactly where it is, now held as the room's point plus an offset.
     */
    private fun switchOrbit(next: CameraMode) {
        if (next == CameraMode.ORBIT) {
            val r = room
            val offX = tx.value + px.value - r.at.x
            val offY = ty.value + py.value - r.at.y
            val offZ = tz.value + pz.value - r.at.z
            tx.snap(r.at.x); ty.snap(r.at.y); tz.snap(r.at.z)
            px.value = offX; py.value = offY; pz.value = offZ
            goalPanX = offX; goalPanY = offY; goalLift = offZ
        } else {
            goalPanX = 0f; goalPanY = 0f; goalLift = 0f
        }
    }

    /** The farthest an orbit goes: [MAX_DISTANCE], or as far as the room's own view for Orbit (it starts there). */
    private fun maxDistance(): Float {
        if (orbitKind != CameraMode.ORBIT) return MAX_DISTANCE
        val r = room
        val vx = r.eye.x - r.at.x
        val vy = r.eye.y - r.at.y
        val vz = r.eye.z - r.at.z
        return max(MAX_DISTANCE, sqrt(vx * vx + vy * vy + vz * vz))
    }

    private fun pushed(value: Float, delta: Float, min: Float, max: Float, give: Float): Float {
        if (delta == 0f) return value
        val outward = (delta > 0f && value >= max) || (delta < 0f && value <= min)
        val next = value + if (outward) delta * 0.35f else delta
        return if (holding) next.coerceIn(min - give, max + give) else next.coerceIn(min, max)
    }

    private fun zoomed(value: Float, factor: Float): Float {
        val next = value / factor
        val far = maxDistance()
        return if (holding) {
            val outward = (factor < 1f && value >= far) || (factor > 1f && value <= MIN_DISTANCE)
            val eased = if (outward) value + (next - value) * 0.35f else next
            eased.coerceIn(MIN_DISTANCE * (1f - DISTANCE_GIVE), far * (1f + DISTANCE_GIVE))
        } else next.coerceIn(MIN_DISTANCE, far)
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
