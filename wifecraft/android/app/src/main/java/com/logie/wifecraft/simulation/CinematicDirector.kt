package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

// Pure Kotlin (no Android, no Compose): cinematic mode's camera director, JVM-tested (unit-tests/.../simulation/
// CinematicDirectorTest.kt). Stage (world) coordinates, Z up; a yaw is radians about Z from +X; an eye is at
// at + (cos pitch cos yaw, cos pitch sin yaw, sin pitch) * distance, as SceneCamera's orbit and FurnitureCameras have it.

/** The shots the director picks from. */
enum class ShotType { ORBIT, LOW_ANGLE, CLOSE_UP, OVER_SHOULDER, HIGH_ANGLE, WIDE, FURNITURE, DOLLY_IN, DOLLY_OUT, HOLD }

/** A camera relative to the focus: it looks at focus + [at], from [yaw], [pitch] and [distance] away, [fov] degrees high. */
data class ShotFrame(val yaw: Float, val pitch: Float, val distance: Float, val at: Vec3, val fov: Float) {
    /** Between this and [o] by [t], the yaw turned by [turn] radians in all (as given: an arc may be longer than half a turn). */
    fun toward(o: ShotFrame, t: Float, turn: Float = OrbitMath.wrap(o.yaw - yaw)) = ShotFrame(
        yaw + turn * t, pitch + (o.pitch - pitch) * t, distance + (o.distance - distance) * t, at.lerp(o.at, t), fov + (o.fov - fov) * t)
}

/**
 * One shot: [type], its [preset] (the type and its variant: a side, a view, who: never one of the last three again), its
 * camera moving from [from] to [to] over [seconds] (eased in and out; the yaw by `to.yaw - from.yaw` exactly), reached by a
 * move of [travel] seconds from where the camera was turning the yaw by [turn] (the way round that stays in the room), or by
 * a [cut].
 */
data class CinematicShot(
    val type: ShotType, val preset: String, val from: ShotFrame, val to: ShotFrame, val seconds: Float,
    val cut: Boolean, val turn: Float = 0f, val travel: Float = 0f,
)

/**
 * What the director sees each frame (filled by LiveScene on the GL thread; nothing allocated per frame): the people
 * ([count] of them, each a hip and a head point), the furniture they use ([furniture]: its people's middle, its views and
 * box), the floor and ceiling heights, the boxes the eye can't stand in ([obstacles]), the scene's wall test ([clearance],
 * asked along the floor), the picture's [aspect] (width over height) and the camera as last drawn (where an entry starts).
 */
class CinematicStage(capacity: Int) {
    var count = 0
    val hipX = FloatArray(capacity); val hipY = FloatArray(capacity); val hipZ = FloatArray(capacity)
    val headX = FloatArray(capacity); val headY = FloatArray(capacity); val headZ = FloatArray(capacity)
    var furniture: FurnitureFocus? = null
    var floorZ = Float.NaN
    var ceilingZ = Float.NaN
    var obstacles: List<Pair<Vec3, Vec3>> = emptyList()
    var clearance: CameraClearance? = null
    var aspect = 16f / 9f
    var eyeX = 0f; var eyeY = 0f; var eyeZ = 0f
    var atX = 0f; var atY = 0f; var atZ = 0f
    var fov = 50f
    /**
     * What is being played (null: nothing: the director's own mix of shots), who plays its roles ([roleA]: the lead, [roleB]),
     * the line of action's yaw ([lineYaw]: the performance's heading; NaN: from A's hips to B's), and a standing body's height
     * in yards ([bodyHeight]: the shot library's unit).
     */
    var action: ShotAction? = null
    var roleA = 0
    var roleB = 1
    var lineYaw = Float.NaN
    var bodyHeight = 1.8f

    fun person(i: Int, hx: Float, hy: Float, hz: Float, ex: Float, ey: Float, ez: Float) {
        hipX[i] = hx; hipY[i] = hy; hipZ[i] = hz; headX[i] = ex; headY[i] = ey; headZ[i] = ez
    }

    fun camera(ex: Float, ey: Float, ez: Float, ax: Float, ay: Float, az: Float, f: Float) {
        eyeX = ex; eyeY = ey; eyeZ = ez; atX = ax; atY = ay; atZ = az; fov = f
    }

    fun head(i: Int) = Vec3(headX[i], headY[i], headZ[i])
    fun hip(i: Int) = Vec3(hipX[i], hipY[i], hipZ[i])
}

/**
 * The furniture the action is on: [id] (a new piece is a new focus), the middle of its people, its views, how far it reaches
 * from its middle ([radius]) and its box on the floor ([box], world: the walls' test starts beside it; null: a circle of
 * [radius]).
 */
data class FurnitureFocus(val id: String, val centre: Vec3, val presets: List<FurniturePreset>, val radius: Float,
                          val box: Pair<Vec3, Vec3>? = null)

/** The framing's maths: a point's place in a camera's picture (-1..1 each way), and whether points are inside with a margin. */
object CinematicFraming {
    /**
     * Where [p] falls in the picture of a camera at [eye] looking at [at] with a vertical field of view [fovDeg] and width
     * over height [aspect]: x right, y up, -1..1 at the picture's edges; null behind the camera (or too near its plane).
     */
    fun project(eye: Vec3, at: Vec3, fovDeg: Float, aspect: Float, p: Vec3, out: FloatArray): Boolean {
        var fx = at.x - eye.x; var fy = at.y - eye.y; var fz = at.z - eye.z
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        if (fl < 1e-5f) return false
        fx /= fl; fy /= fl; fz /= fl
        // right = f x up, up' = right x f
        var rx = fy; var ry = -fx
        val rl = hypot(rx, ry)
        if (rl < 1e-5f) return false
        rx /= rl; ry /= rl
        val ux = ry * fz; val uy = -rx * fz; val uz = rx * fy - ry * fx
        val vx = p.x - eye.x; val vy = p.y - eye.y; val vz = p.z - eye.z
        val depth = vx * fx + vy * fy + vz * fz
        if (depth < 0.15f) return false
        val t = tan(Math.toRadians(fovDeg / 2.0).toFloat())
        out[0] = (vx * rx + vy * ry) / (depth * t * aspect)
        out[1] = (vx * ux + vy * uy + vz * uz) / (depth * t)
        return true
    }

    /** Whether every point of [points] is in the picture, at least [margin] (of the half picture) inside its edges. */
    fun inFrame(eye: Vec3, at: Vec3, fovDeg: Float, aspect: Float, points: List<Vec3>, margin: Float): Boolean {
        val out = FloatArray(2)
        val lim = 1f - margin
        for (p in points) {
            if (!project(eye, at, fovDeg, aspect, p, out)) return false
            if (abs(out[0]) > lim || abs(out[1]) > lim) return false
        }
        return true
    }

    /** The narrowest field of view (from [fovDeg] up to [maxFov] by 2 degrees) that frames [points]; null: none does. */
    fun fit(eye: Vec3, at: Vec3, fovDeg: Float, maxFov: Float, aspect: Float, points: List<Vec3>, margin: Float): Float? {
        var f = fovDeg
        while (f <= maxFov + 1e-3f) {
            if (inFrame(eye, at, f, aspect, points, margin)) return f
            f += 2f
        }
        return null
    }

    fun eye(at: Vec3, yaw: Float, pitch: Float, distance: Float): Vec3 = FurnitureCameras.eyeOf(at, yaw, pitch, distance)
}

/**
 * The scene's wall test asked along the floor, as LiveScene asks it for the furniture views: how far the floor is clear from
 * beside the focus outward (by 3 degrees, found once by stepping out [STEP] yards at a time), an eye clear when its floor
 * point is within that reach. Beside the focus: [BOX_OUT] past the furniture's own box when the focus is on it (the walk
 * can't start inside the bed; a radius round a big bed would reach through the wall it stands against), else a small circle.
 * [reset] when the focus moves or the furniture changes.
 */
class FloorReach(private val clear: CameraClearance) {
    companion object {
        const val STEP = 0.5f
        const val FAR = 9f
        const val BUCKET_DEG = 3
        const val BOX_OUT = 0.15f
    }

    private var cx = 0f; private var cy = 0f; private var floor = 0f; private var beside = 0.3f
    private var box: Pair<Vec3, Vec3>? = null
    private val reach = HashMap<Int, Float>()
    /** Walk tests asked so far (tests, logs). */
    var asked = 0
        private set

    fun reset(centre: Vec3, besideRadius: Float, floorZ: Float, furnitureBox: Pair<Vec3, Vec3>? = null) {
        cx = centre.x; cy = centre.y; floor = floorZ; beside = besideRadius.coerceAtLeast(0.2f)
        box = furnitureBox?.takeIf { (lo, hi) -> centre.x > lo.x && centre.x < hi.x && centre.y > lo.y && centre.y < hi.y }
        reach.clear()
    }

    /** How far from the focus the walk starts along (ux, uy): out of the furniture's box, or [beside]. */
    private fun start(ux: Float, uy: Float): Float {
        val (lo, hi) = box ?: return beside
        var t = Float.MAX_VALUE
        if (ux > 1e-6f) t = min(t, (hi.x + BOX_OUT - cx) / ux) else if (ux < -1e-6f) t = min(t, (lo.x - BOX_OUT - cx) / ux)
        if (uy > 1e-6f) t = min(t, (hi.y + BOX_OUT - cy) / uy) else if (uy < -1e-6f) t = min(t, (lo.y - BOX_OUT - cy) / uy)
        return if (t.isFinite()) t.coerceAtLeast(0.2f) else beside
    }

    fun clear(eye: Vec3): Boolean {
        val dx = eye.x - cx; val dy = eye.y - cy
        val d = hypot(dx, dy)
        if (d <= 1e-4f || d <= start(dx / d, dy / d)) return true
        val deg = Math.toDegrees(atan2(dy, dx).toDouble())
        val key = Math.floorDiv((deg + 360.0).roundToInt(), BUCKET_DEG)
        val far = reach.getOrPut(key) {
            val a = Math.toRadians(key * BUCKET_DEG.toDouble() + BUCKET_DEG / 2.0)
            val ux = cos(a).toFloat(); val uy = sin(a).toFloat()
            val out = start(ux, uy)
            val from = Vec3(cx + ux * out, cy + uy * out, floor)
            var r = out
            var step = STEP
            // out by STEP, then the last step halved twice (the wall found within an eighth of a yard)
            while (r < FAR && step >= STEP / 4f - 1e-4f) {
                val next = r + step
                asked++
                if (clear.clear(Vec3(cx + ux * next, cy + uy * next, floor), from)) r = next else step /= 2f
            }
            r
        }
        return d <= far
    }
}

/**
 * Cinematic mode's camera director: a list of shots built round the focus of the action (the furniture the people use, else
 * the middle of the people): slow orbits, low angles, close-ups on the pair, over a shoulder, high angles, wide establishing
 * shots, the furniture's own views (FurnitureCameras: a bed's foot, head, sides), slow dollies in and out. Each shot lasts a
 * random time in the settings' range; never the same type twice running nor a preset of the last three. Shots are joined by
 * an eased move (the yaw the way round that stays clear of the walls), or cut together in the Cuts style (or where no move is
 * clear). Every eye it uses is tested: above the floor and under the ceiling, outside the furniture's boxes, clear of the walls
 * along the floor, the pitch within limits, the people's heads and hips inside the picture with a margin. The focus is
 * followed on a slow spring (people walking: the shot glides with them); a shot whose eye stops being clear is left early.
 * [update] once a frame (GL thread) writes the pose into [eyeX]..[fov]. Seeded: the same seed and stage, the same film.
 */
class CinematicDirector(settings: CinematicSettings = CinematicSettings.DEFAULT, seed: Long = Random.nextLong()) {
    companion object {
        /** The pitch's limits (radians): not looking up more than about 17 degrees, nor down more than about 66. */
        const val MIN_PITCH = -0.30f
        const val MAX_PITCH = 1.15f
        /** The eye stays this far over the floor and under the ceiling (yards). */
        const val FLOOR_GAP = 0.35f
        const val CEILING_GAP = 0.3f
        /** A room's ceiling over its floor when nobody knows it (yards). */
        const val CEILING_H = 3.4f
        /** How far the eye keeps from a person's body and a piece of furniture's box (yards). */
        const val BODY_GAP = 0.5f
        /** A close-up's or shoulder shot's line to a face may pass no nearer another person's body (yards). */
        const val OCCLUDE = 0.25f
        /** A close-up's wall test ends this far in front of the person it frames (their own spot, a chair, may block it). */
        const val FACE_GAP = 0.6f
        /** Close-ups and shoulder shots only while nobody moves faster than this (yards a second, smoothed). */
        const val STILL_SPEED = 0.25f
        /** Two heads this near their middle (yards) are one close-up; farther apart, a close-up is on one of them. */
        const val CLOSE_TOGETHER = 0.5f
        const val BOX_GAP = 0.12f
        /** The framing's margin (of the half picture) and the widest field of view a shot may open to. */
        const val MARGIN = 0.10f
        const val MAX_FOV = 70f
        /** The focus's spring (1/s: about 3 s to settle) and the output's (a light smoothing; 1/s). */
        const val FOCUS_OMEGA = 1.6f
        const val OUTPUT_OMEGA = 7f
        /** A move between shots: on average this many yards a second (at 100% speed), at least and at most so long (s). */
        const val TRAVEL_SPEED = 1.6f
        const val MIN_TRAVEL_S = 1.6f
        const val MAX_TRAVEL_S = 5.0f
        /** A shot is kept at least this long (seconds) before anything but its end leaves it. */
        const val MIN_HOLD_S = 2.0f
        /** How often the current shot's eye is tested again (seconds). */
        const val RECHECK_S = 0.5f
        /** The people within this of the action's middle are framed (yards). */
        const val NEAR = 3.5f
        /** A library shot's eye keeps this far (degrees) off the line of action unless the shot may sit on it. */
        const val LINE_EDGE = 2f
        /** The random drift: its pull back to the shot (1/s), its noise, and its widest reach round, up and in (degrees, share). */
        const val DRIFT_PULL = 0.35f
        const val DRIFT_NOISE = 0.55f
        const val DRIFT_AZ_MAX = 12f
        const val DRIFT_PITCH_MAX = 5f
        const val DRIFT_DIST = 0.08f
        /** The presets of the library's shots start with this. */
        const val LIBRARY = "LIB:"
        private const val TAU = (2.0 * PI).toFloat()
    }

    @Volatile var settings: CinematicSettings = settings
    private val random = Random(seed)
    /** The shot library (assets/shot_library.json): its shots for what is being played ([CinematicStage.action]); null: the mix only. */
    @Volatile var library: ShotLibrary? = null
    // the drift's own random numbers (seeded: the same seed, the same film), so drifting never changes which shots are picked
    private val wander = Random(seed xor 0x5DEECE66DL)
    /** The library shot now (null: one of the mix's). */
    var spec: ShotSpec? = null
        private set
    private var actionKey: String? = null
    private var actionCls: ShotClass? = null
    private var repick = false
    private var cadenceAt = 0
    /** The 180-degree rule: the side of the line of action the library's shots keep (+1 left of A to B, -1 right; 0 not yet), the line's yaw. */
    var lineSide = 0f
        private set
    var lineYaw = 0f
        private set
    // the drift's walk (each -1..1: round, up, in) and its clock
    private var du = 0f; private var dp = 0f; private var dd = 0f
    private var clock = 0f

    /** The pose (written by [update]). */
    var eyeX = 0f; private set
    var eyeY = 0f; private set
    var eyeZ = 0f; private set
    var atX = 0f; private set
    var atY = 0f; private set
    var atZ = 0f; private set
    var fov = 50f; private set

    /** The shot now, its time, the shots so far (each one as it starts, for tests and the film's log). */
    var shot: CinematicShot? = null
        private set
    var shotTime = 0f
        private set
    val history = ArrayList<CinematicShot>()
    /** Called as each shot starts (GL thread). */
    var onShot: ((CinematicShot) -> Unit)? = null

    private var started = false
    private var start: ShotFrame? = null
    private val fx = Spring(0f, FOCUS_OMEGA); private val fy = Spring(0f, FOCUS_OMEGA); private val fz = Spring(0f, FOCUS_OMEGA)
    private val ox = Spring(0f, OUTPUT_OMEGA); private val oy = Spring(0f, OUTPUT_OMEGA); private val oz = Spring(0f, OUTPUT_OMEGA)
    private val ax = Spring(0f, OUTPUT_OMEGA); private val ay = Spring(0f, OUTPUT_OMEGA); private val az = Spring(0f, OUTPUT_OMEGA)
    private val of = Spring(50f, OUTPUT_OMEGA)
    private var furnitureId: String? = null
    private var recheck = 0f
    private var reach: FloorReach? = null
    private var reachFor: CameraClearance? = null
    private var reachAt = Vec3(0f, 0f, 0f)
    private var reachOf: String? = null
    // the people's heads last frame (x, y each) and how fast the quickest walks, smoothed (yards a second)
    private var lastHeads = FloatArray(0)
    private var walking = 0f
    /** The frame shown last (relative to the focus), where a shot left early hands over from. */
    private var lastFrame: ShotFrame? = null
    /** The frame shown last, the drift included (tests). */
    val frameNow: ShotFrame? get() = lastFrame

    /** The next [update] starts afresh from the stage's camera (entering cinematic mode). */
    fun restart() { started = false }

    /**
     * One frame, [dt] seconds after the last, on [stage]: the shot moves on (a new one when it ends or its eye is no longer
     * clear) and the pose is written. False (nothing written): there is nobody to film.
     */
    fun update(dt: Float, stage: CinematicStage): Boolean {
        if (stage.count <= 0) return false
        val step = if (dt.isFinite()) dt.coerceIn(0f, 0.1f) else 0f
        val raw = rawFocus(stage)
        val furn = stage.furniture?.id
        if (!started) {
            fx.snap(raw.x); fy.snap(raw.y); fz.snap(raw.z)
            started = true
            shot = null
            furnitureId = furn
            val focus = raw
            start = frameOf(Vec3(stage.eyeX, stage.eyeY, stage.eyeZ), Vec3(stage.atX, stage.atY, stage.atZ), stage.fov, focus)
            next(stage, focus, start!!)
            ox.snap(stage.eyeX); oy.snap(stage.eyeY); oz.snap(stage.eyeZ)
            ax.snap(stage.atX); ay.snap(stage.atY); az.snap(stage.atZ); of.snap(stage.fov)
        } else {
            fx.update(raw.x, step); fy.update(raw.y, step); fz.update(raw.z, step)
        }
        val focus = Vec3(fx.value, fy.value, fz.value)
        // how fast the quickest person moves (yards a second, smoothed over about a second): close-ups wait for them
        var fastest = 0f
        if (lastHeads.size == stage.count * 2 && step > 0f) {
            for (i in 0 until stage.count)
                fastest = max(fastest, hypot(stage.headX[i] - lastHeads[2 * i], stage.headY[i] - lastHeads[2 * i + 1]) / step)
        }
        if (lastHeads.size != stage.count * 2) lastHeads = FloatArray(stage.count * 2)
        for (i in 0 until stage.count) { lastHeads[2 * i] = stage.headX[i]; lastHeads[2 * i + 1] = stage.headY[i] }
        walking += (fastest - walking) * (1f - kotlin.math.exp(-step / 1f))
        shotTime += step
        recheck += step
        // what is played changed (another animation, or its next stage): the library's shots are picked again
        val act = stage.action
        if (act?.key != actionKey) {
            if (act != null && (act.cls != actionCls || lineSide == 0f)) { cadenceAt = 0; line(stage) }
            actionKey = act?.key; actionCls = act?.cls
            repick = act != null || spec != null
        }
        val s = shot
        // a new shot: this one is over; or, once it has been seen a while ([MIN_HOLD_S]) and its move is done, the furniture
        // in use changed or its eye is no longer good (people walking: the shot follows them on the focus's spring, and is
        // left only when its eye would go into a wall or a piece of furniture)
        var leave = s == null || shotTime >= s.seconds
        if (!leave && repick && s != null && shotTime >= 1f) leave = true
        if (!leave && s != null && shotTime >= max(MIN_HOLD_S, if (s.cut) 0f else s.travel)) {
            if (furn != furnitureId) leave = true
            else if (recheck >= RECHECK_S) {
                recheck = 0f
                if (!eyeGood(stage, focus, frameAt(shotTime), people(stage))) leave = true
            }
        }
        if (leave) {
            furnitureId = furn
            next(stage, focus, lastFrame ?: frameAt(shotTime))
        }
        var f = frameAt(shotTime)
        val sp = spec
        if (sp != null) f = drifted(stage, focus, f, sp, step)
        lastFrame = f
        val at = focus + f.at
        var eye = CinematicFraming.eye(at, f.yaw, f.pitch, f.distance)
        val floor = stage.floorZ
        if (!floor.isNaN()) eye = Vec3(eye.x, eye.y, eye.z.coerceIn(floor + FLOOR_GAP, ceiling(stage) - CEILING_GAP))
        val cut = shot?.cut == true && shotTime <= step + 1e-6f
        if (cut) {
            ox.snap(eye.x); oy.snap(eye.y); oz.snap(eye.z); ax.snap(at.x); ay.snap(at.y); az.snap(at.z); of.snap(f.fov)
        } else {
            ox.update(eye.x, step); oy.update(eye.y, step); oz.update(eye.z, step)
            ax.update(at.x, step); ay.update(at.y, step); az.update(at.z, step); of.update(f.fov, step)
        }
        eyeX = ox.value; eyeY = oy.value; eyeZ = oz.value
        atX = ax.value; atY = ay.value; atZ = az.value
        fov = of.value
        return true
    }

    /** The shot's frame at [t] seconds into it: its own move eased, reached from [start] by an eased move (no cut). */
    fun frameAt(t: Float): ShotFrame {
        val s = shot ?: return start ?: ShotFrame(0f, 0.3f, 3f, Vec3(0f, 0f, 0f), 50f)
        val u = easeInOut(if (s.seconds > 0f) t / s.seconds else 1f)
        val own = s.from.toward(s.to, u, s.to.yaw - s.from.yaw)
        val from = start
        if (s.cut || from == null || s.travel <= 0f) return own
        val w = easeInOut(t / s.travel)
        if (w >= 1f) return own
        // the yaw: from where it was, by the planned turn to the shot's start, plus the shot's own move since
        val total = s.turn + (own.yaw - s.from.yaw)
        return ShotFrame(from.yaw + total * w, from.pitch + (own.pitch - from.pitch) * w, from.distance + (own.distance - from.distance) * w,
            from.at.lerp(own.at, w), from.fov + (own.fov - from.fov) * w)
    }

    // ---------------------------------------------------------------------------------------------- choosing a shot

    private fun next(stage: CinematicStage, focus: Vec3, from: ShotFrame) {
        repick = false
        du = 0f; dp = 0f; dd = 0f
        val chosen = choose(stage, focus, from)
        start = from
        shot = chosen
        shotTime = 0f
        recheck = 0f
        history += chosen
        if (history.size > 64) history.removeAt(0)
        onShot?.invoke(chosen)
    }

    /** The types the style allows, the furniture's views only when there is furniture with views, a shoulder only for two. */
    private fun types(stage: CinematicStage): List<ShotType> {
        if (settings.style == CinematicStyle.SLOW_ORBIT) return listOf(ShotType.ORBIT)
        val out = ArrayList<ShotType>()
        out += listOf(ShotType.ORBIT, ShotType.LOW_ANGLE, ShotType.HIGH_ANGLE, ShotType.WIDE, ShotType.DOLLY_IN, ShotType.DOLLY_OUT)
        // a close-up and a shoulder shot look at one face: only while nobody is walking (the shot would lose them)
        val still = walking < STILL_SPEED
        if (still) out += ShotType.CLOSE_UP
        if (still && stage.count >= 2) out += ShotType.OVER_SHOULDER
        // the furniture's own views (a bed's foot, head, sides) three times as likely: the action is on it
        if (stage.furniture?.presets?.isNotEmpty() == true) repeat(3) { out += ShotType.FURNITURE }
        return out
    }

    private fun choose(stage: CinematicStage, focus: Vec3, from: ShotFrame): CinematicShot {
        val act = stage.action
        if (act != null && library != null && settings.style != CinematicStyle.SLOW_ORBIT) {
            chooseLibrary(stage, focus, from, act)?.let { return it }
        }
        spec = null
        val all = types(stage)
        val lastType = history.lastOrNull()?.type
        val recent = history.takeLast(3).map { it.preset }.toSet()
        val cuts = settings.style == CinematicStyle.CUTS
        val pts = people(stage)
        // strict (the rules), then the type may repeat, then a cut where no move is clear; else hold where it is
        for (pass in 0..2) {
            val order = all.shuffled(random)
            for (type in order) {
                if (pass == 0 && type == lastType && settings.style != CinematicStyle.SLOW_ORBIT) continue
                repeat(if (type == ShotType.FURNITURE) 3 else 4) {
                    val shot = build(type, stage, focus, pts) ?: return@repeat
                    if (shot.preset in recent) return@repeat
                    val placed = place(shot, stage, focus, pts, from, cut = cuts || pass == 2) ?: return@repeat
                    return placed
                }
            }
        }
        val hold = from.copy()
        return CinematicShot(ShotType.HOLD, "HOLD", hold, hold, shotSeconds(), cut = false, turn = 0f, travel = 0f)
    }

    private fun shotSeconds(): Float {
        val s = settings.clamped()
        return s.shortest + random.nextFloat() * (s.longest - s.shortest)
    }

    /** A shot of [type] with its random parameters (not yet tested). */
    private fun build(type: ShotType, stage: CinematicStage, focus: Vec3, pts: List<Vec3>): CinematicShot? {
        val m = settings.motionScale
        val r = radius(pts, focus)
        val deg = (PI / 180).toFloat()
        val yaw0 = OrbitMath.wrap(random.nextFloat() * TAU)
        val sector = sector(yaw0)
        val sign = if (random.nextBoolean()) 1f else -1f
        val seconds = shotSeconds()
        fun shot(preset: String, a: ShotFrame, b: ShotFrame) = CinematicShot(type, "$type:$preset", a, b, seconds, cut = false)
        return when (type) {
            ShotType.ORBIT -> {
                val arc = (20f + random.nextFloat() * 40f) * deg * m * sign
                val d = (2f + random.nextFloat() * 2f).coerceAtLeast(r + 1.2f)
                val p = 0.25f + random.nextFloat() * 0.2f
                shot("$sector${if (sign > 0) "+" else "-"}", ShotFrame(yaw0, p, d, Vec3(0f, 0f, 0f), 50f),
                    ShotFrame(yaw0 + arc, p, d, Vec3(0f, 0f, 0f), 50f))
            }
            ShotType.LOW_ANGLE -> {
                val d = (2.2f + random.nextFloat() * 0.8f).coerceAtLeast(r + 1.0f)
                val lift = 0.25f
                val p = -0.05f - random.nextFloat() * 0.12f
                shot("$sector", ShotFrame(yaw0, p, d, Vec3(0f, 0f, lift), 55f),
                    ShotFrame(yaw0 + 10f * deg * m * sign, p, d * 0.95f, Vec3(0f, 0f, lift), 55f))
            }
            ShotType.HIGH_ANGLE -> {
                val d = (1.8f + random.nextFloat() * 0.6f).coerceAtLeast(r + 1.0f)
                val p = 0.70f + random.nextFloat() * 0.2f
                shot("$sector", ShotFrame(yaw0, p, d, Vec3(0f, 0f, 0f), 50f),
                    ShotFrame(yaw0 + 15f * deg * m * sign, p, d, Vec3(0f, 0f, 0f), 50f))
            }
            ShotType.WIDE -> {
                val d = 6f + random.nextFloat() * 1.5f
                val p = 0.30f + random.nextFloat() * 0.15f
                shot("$sector", ShotFrame(yaw0, p, d, Vec3(0f, 0f, 0f), 55f),
                    ShotFrame(yaw0 + 10f * deg * m * sign, p, d * 0.97f, Vec3(0f, 0f, 0f), 55f))
            }
            ShotType.DOLLY_IN, ShotType.DOLLY_OUT -> {
                val near = (2.2f).coerceAtLeast(r + 1.0f)
                val far = near + 1.8f * m
                val p = 0.20f + random.nextFloat() * 0.15f
                val a = ShotFrame(yaw0, p, if (type == ShotType.DOLLY_IN) far else near, Vec3(0f, 0f, 0f), 48f)
                val b = ShotFrame(yaw0, p, if (type == ShotType.DOLLY_IN) near else far, Vec3(0f, 0f, 0f), 48f)
                shot("$sector", a, b)
            }
            ShotType.CLOSE_UP -> {
                // the pair's faces: their heads' middle when they are close together, else one of them (never the empty
                // middle between two people apart)
                val who = near(stage)
                val all = who.map { stage.head(it) }
                val mid = Vec3(all.map { it.x }.average().toFloat(), all.map { it.y }.average().toFloat(), all.map { it.z }.average().toFloat())
                val together = all.maxOf { hypot(it.x - mid.x, it.y - mid.y) } <= CLOSE_TOGETHER
                val one = if (together) -1 else who[random.nextInt(who.size)]
                val target = (if (one < 0) mid else stage.head(one)).let { Vec3(it.x, it.y, it.z - 0.08f) }
                val spread = if (one < 0) all.maxOf { hypot(it.x - target.x, it.y - target.y) } else 0f
                val d = (1.0f + random.nextFloat() * 0.5f).coerceAtLeast(spread + 0.7f)
                val p = 0.08f + random.nextFloat() * 0.2f
                val at = target - focus
                shot(if (one < 0) "$sector" else "$sector@$one", ShotFrame(yaw0, p, d, at, 40f),
                    ShotFrame(yaw0 + 6f * deg * m * sign, p, d * (1f - 0.1f * m), at, 40f))
            }
            ShotType.OVER_SHOULDER -> {
                val who = near(stage)
                if (who.size < 2) return null
                val ia = random.nextInt(who.size)
                var ib = random.nextInt(who.size - 1); if (ib >= ia) ib++
                val a = who[ia]; val b = who[ib]
                val ha = stage.head(a); val hb = stage.head(b)
                val dx = ha.x - hb.x; val dy = ha.y - hb.y
                val dl = hypot(dx, dy)
                if (dl < 0.3f) return null
                val bx = dx / dl; val by = dy / dl              // from B toward A (behind A, seen from B)
                val side = if (random.nextBoolean()) 1f else -1f
                val eye = Vec3(ha.x + bx * 0.55f - by * 0.32f * side, ha.y + by * 0.55f + bx * 0.32f * side, ha.z + 0.12f)
                val at = Vec3(hb.x, hb.y, hb.z - 0.1f)
                val f = frameOf(eye, at, 45f, focus)
                shot("$a>$b${if (side > 0) "R" else "L"}", f, f.copy(distance = f.distance * (1f - 0.08f * m)))
            }
            ShotType.FURNITURE -> {
                val furn = stage.furniture ?: return null
                if (furn.presets.isEmpty()) return null
                val p = furn.presets[random.nextInt(furn.presets.size)]
                val f = frameOf(p.eye, p.at, p.fov, focus)
                shot(p.view.name, f, f.copy(yaw = f.yaw + 8f * deg * m * sign, distance = f.distance * (1f - 0.08f * m)))
            }
            ShotType.HOLD -> null
        }
    }

    /**
     * [shot] tested and placed: every eye of it good (its field of view opened as far as [MAX_FOV] to keep the people in),
     * and the way to it from [from]: a move whose eyes are good too (the short way round, else the long way), else a cut
     * when [cut] allows it; null: it can't be had.
     */
    private fun place(shot: CinematicShot, stage: CinematicStage, focus: Vec3, pts: List<Vec3>, from: ShotFrame, cut: Boolean,
                      framed: List<Vec3>? = null, faces: List<Int>? = null, overIdx: Int = -1, forceCut: Boolean = false): CinematicShot? {
        val framing = framed ?: when (shot.type) {
            ShotType.CLOSE_UP, ShotType.OVER_SHOULDER -> heads(stage, shot)
            else -> pts
        }
        var fov = max(shot.from.fov, shot.to.fov)
        for (k in 0..4) {
            val u = k / 4f
            val f = shot.from.toward(shot.to, u, shot.to.yaw - shot.from.yaw)
            if (!eyeGood(stage, focus, f, pts)) return reject(shot, "eye")
            val at = focus + f.at
            val eye = CinematicFraming.eye(at, f.yaw, f.pitch, f.distance)
            fov = CinematicFraming.fit(eye, at, fov, max(MAX_FOV, fov), stage.aspect, framing, MARGIN) ?: return reject(shot, "framing")
        }
        // a close-up or a shoulder shot: nobody else's body between the camera and the face, and the floor from under the
        // camera to just before the person it frames clear (no wall between them: they may stand away from the focus)
        val faceOwners = if (framed != null) faces else if (shot.type == ShotType.CLOSE_UP || shot.type == ShotType.OVER_SHOULDER) owners(stage, shot) else null
        if (faceOwners != null) {
            val except = if (framed != null) overIdx else shoulder(shot)
            val c = stage.clearance
            val floor = stage.floorZ
            for (k in 0..2) {
                val f = shot.from.toward(shot.to, k / 2f, shot.to.yaw - shot.from.yaw)
                val at = focus + f.at
                val eye = CinematicFraming.eye(at, f.yaw, f.pitch, f.distance)
                for (o in faceOwners) {
                    if (occluded(stage, eye, stage.head(o), o, except)) return reject(shot, "occluded")
                    if (c != null && !floor.isNaN() && k != 1) {
                        val dx = eye.x - stage.hipX[o]; val dy = eye.y - stage.hipY[o]
                        val dl = hypot(dx, dy)
                        if (dl > FACE_GAP + 0.1f) {
                            val before = Vec3(stage.hipX[o] + dx / dl * FACE_GAP, stage.hipY[o] + dy / dl * FACE_GAP, floor)
                            if (!c.clear(Vec3(eye.x, eye.y, floor), before)) return reject(shot, "wall to face")
                        }
                    }
                }
            }
        }
        val sized = shot.copy(from = shot.from.copy(fov = fov), to = shot.to.copy(fov = fov))
        if (settings.style == CinematicStyle.CUTS || forceCut) return sized.copy(cut = true)
        // the last pass ([cut]): a longer move first, a cut only when no move at all is clear
        val limit = if (cut) MAX_TRAVEL_S * 1.6f else MAX_TRAVEL_S
        val short = OrbitMath.wrap(sized.from.yaw - from.yaw)
        val long = if (short >= 0f) short - TAU else short + TAU
        val m = settings.motionScale
        for (turn in listOf(short, long)) {
            // the move's length (round the arc, in and out, the point looked at sliding) at a slow camera's pace; one that
            // would take too long is left for a nearer shot (or a cut, when nothing nearer can be had)
            val path = abs(turn) * (from.distance + sized.from.distance) / 2f + abs(sized.from.distance - from.distance) +
                dist(from.at, sized.from.at) + abs(sized.from.pitch - from.pitch) * sized.from.distance
            val travel = (path / (TRAVEL_SPEED * sqrt(m))).coerceAtLeast(MIN_TRAVEL_S)
            if (travel > limit) continue
            val moved = sized.copy(cut = false, turn = turn, travel = travel, seconds = max(sized.seconds, travel + 1.5f))
            var ok = true
            // (from where the camera is, which may be why it is leaving: only the rest of the way must be good)
            for (k in 2..7) {
                val w = k / 8f
                val f = ShotFrame(from.yaw + turn * w, from.pitch + (sized.from.pitch - from.pitch) * w,
                    from.distance + (sized.from.distance - from.distance) * w, from.at.lerp(sized.from.at, w), sized.from.fov)
                if (!eyeGood(stage, focus, f, pts, framing = false)) { ok = false; break }
            }
            if (ok) return moved
            if (abs(turn) > PI.toFloat() * 1.5f) break
        }
        return if (cut) sized.copy(cut = true) else reject(shot, "no clear move")
    }

    // ---------------------------------------------------------------------------------------------- the shot library

    /** The lead's and the other role's person (the other: someone else than the lead when there are two or more). */
    private fun roleA(stage: CinematicStage): Int = stage.roleA.coerceIn(0, max(0, stage.count - 1))
    private fun roleB(stage: CinematicStage): Int {
        if (stage.count < 2) return roleA(stage)
        val a = roleA(stage)
        val b = stage.roleB.coerceIn(0, stage.count - 1)
        return if (b == a) (a + 1) % stage.count else b
    }

    /**
     * The line of action for the 180-degree rule: the stage's (the performance's heading), else from A's hips to B's when
     * they stand apart, else the line it was; its side is picked once (at random) and kept for the scene.
     */
    private fun line(stage: CinematicStage) {
        val a = roleA(stage); val b = roleB(stage)
        val dx = stage.hipX[b] - stage.hipX[a]; val dy = stage.hipY[b] - stage.hipY[a]
        lineYaw = when {
            stage.lineYaw.isFinite() -> stage.lineYaw
            a != b && hypot(dx, dy) > 0.25f -> atan2(dy, dx)
            else -> lineYaw
        }
        if (lineSide == 0f) {
            // the side with more room: medium-distance eyes round each side tested; a tie is decided at random
            val focus = rawFocus(stage)
            val d = 1.3f * stage.bodyHeight.coerceIn(0.5f, 4f)
            fun room(side: Float): Int = (0..6).count { k ->
                val az = (30f + 20f * k) * (PI / 180).toFloat()
                eyeGood(stage, focus, ShotFrame(lineYaw + side * az, 0.3f, d, Vec3(0f, 0f, 0f), 50f), emptyList())
            }
            val left = room(1f); val right = room(-1f)
            lineSide = when {
                left > right -> 1f
                right > left -> -1f
                else -> if (random.nextBoolean()) 1f else -1f
            }
        }
    }

    /**
     * A library shot for [act]: its class's set weighed by the style, the stage and the cadence (a wide, a medium, a close, a
     * detail...: Classic follows it, Randomness loosens it), never the shot just seen, close and detail shots only while
     * nobody walks; each tried with its random framing and placed as the mix's are (every eye clear, the people in the
     * picture, no one in the line to a face). Null: none fitted (the mix is used).
     */
    private fun chooseLibrary(stage: CinematicStage, focus: Vec3, from: ShotFrame, act: ShotAction): CinematicShot? {
        val lib = library ?: return null
        if (lineSide == 0f || actionKey != act.key) {
            if (act.cls != actionCls || lineSide == 0f) { cadenceAt = 0; line(stage) }
            actionKey = act.key; actionCls = act.cls
        }
        val cands = lib.set(act.cls)
        if (cands.isEmpty()) return null
        val st = settings.clamped()
        val style = st.shotStyle
        val r = if (style == ShotStyle.RANDOM) 1f else st.randomness / 100f
        val ids = history.takeLast(2).map { libraryId(it.preset) }
        val lastId = ids.lastOrNull()
        val still = walking < STILL_SPEED
        val want = lib.cadence[cadenceAt % lib.cadence.size]
        val two = stage.count >= 2
        val raw = FloatArray(cands.size) { k ->
            val (sp, w0) = cands[k]
            var w = w0 * lib.styleWeight(style, sp.size) * lib.stageWeight(act.part, sp.size)
            // Classic follows the cadence throughout; the other styles (but Random) only open with the wide
            if (sp.size == want && (style == ShotStyle.CLASSIC || (style != ShotStyle.RANDOM && cadenceAt == 0))) w *= 1f + 6f * (1f - r)
            if (sp.id == lastId) w = 0f
            else if (cands.size >= 4 && sp.id in ids) w *= 0.3f
            if (!still && (sp.size == ShotSize.CLOSE || sp.size == ShotSize.DETAIL)) w = 0f
            if (!two && (sp.anchor == ShotAnchor.HEAD_B || sp.anchor == ShotAnchor.HIP_B)) w = 0f
            w
        }
        // the randomness flattens the weights toward their mean (100%: every candidate nearly as likely)
        val live = raw.filter { it > 0f }
        if (live.isEmpty()) return null
        val mean = live.average().toFloat()
        val weights = FloatArray(raw.size) { if (raw[it] > 0f) raw[it] * (1f - 0.7f * r) + mean * 0.7f * r else 0f }
        val cutsOk = st.style == CinematicStyle.CUTS || st.allowCuts
        val pts = people(stage)
        repeat(cands.size) {
            val total = weights.sum()
            if (total <= 0f) return null
            var x = random.nextFloat() * total
            var k = 0
            while (k < weights.size - 1 && (weights[k] <= 0f || x > weights[k])) { if (weights[k] > 0f) x -= weights[k]; k++ }
            if (weights[k] <= 0f) k = weights.indexOfLast { it > 0f }
            if (k < 0) return null
            weights[k] = 0f
            val sp = cands[k].first
            val wantCut = st.style == CinematicStyle.CUTS || (cutsOk && when (sp.transition) {
                ShotTransition.CUT -> true; ShotTransition.BLEND -> false; ShotTransition.ANY -> random.nextFloat() < 0.5f
            })
            repeat(3) { attempt ->
                // a wide or medium shot that doesn't fit the room is tried nearer (as wide as the room allows)
                val nearer = when (sp.size) { ShotSize.WIDE -> 1f - 0.2f * attempt; ShotSize.MEDIUM -> 1f - 0.15f * attempt; else -> 1f }
                val built = buildLibrary(sp, stage, focus, nearer) ?: return@repeat
                val placed = place(built.shot, stage, focus, pts, from, cut = cutsOk, framed = built.framed, faces = built.faces,
                    overIdx = built.over, forceCut = wantCut) ?: return@repeat
                spec = sp
                cadenceAt++
                return placed
            }
        }
        return null
    }

    private class Built(val shot: CinematicShot, val framed: List<Vec3>, val faces: List<Int>?, val over: Int)

    /** The library's id of a shot's [preset] (null: one of the mix's). */
    private fun libraryId(preset: String): String? =
        if (preset.startsWith(LIBRARY)) preset.removePrefix(LIBRARY).substringBefore(':') else null

    /** [sp] made a shot with its random framing: round the line on the scene's side, its height, distance, lens and move. */
    private fun buildLibrary(sp: ShotSpec, stage: CinematicStage, focus: Vec3, nearer: Float = 1f): Built? {
        val a = roleA(stage); val b = roleB(stage)
        val m = settings.motionScale
        fun face(i: Int) = stage.head(i).let { Vec3(it.x, it.y, it.z - 0.08f) }
        val (anchor, framed, faces) = when (sp.anchor) {
            ShotAnchor.PAIR -> Triple(focus, people(stage), null)
            ShotAnchor.FACES -> {
                val fa = face(a); val fb = face(b)
                Triple(fa.lerp(fb, 0.5f), listOf(fa, fb), null)
            }
            ShotAnchor.HEAD_A -> Triple(face(a), listOf(face(a)), listOf(a))
            ShotAnchor.HEAD_B -> Triple(face(b), listOf(face(b)), listOf(b))
            ShotAnchor.PELVIS -> Triple(stage.hip(a).lerp(stage.hip(b), 0.5f), listOf(stage.hip(a), stage.hip(b)), null)
            ShotAnchor.HIP_A -> Triple(stage.hip(a), listOf(stage.hip(a)), null)
            ShotAnchor.HIP_B -> Triple(stage.hip(b), listOf(stage.hip(b)), null)
        }
        val over = when (sp.over) { 0 -> a; 1 -> b; else -> -1 }.takeIf { it >= 0 && a != b } ?: -1
        val deg = (PI / 180).toFloat()
        val s = settings.clamped()
        val seconds = sp.dwell.pick(random).coerceIn(s.shortest.toFloat(), s.longest.toFloat())
        val edge = if (sp.crossLine) 0f else LINE_EDGE
        val az0 = sp.azimuth.pick(random).coerceIn(edge, 180f - edge)
        val arc = sp.orbit.pick(random) * seconds * m * (if (random.nextBoolean()) 1f else -1f)
        val az1 = (az0 + arc).coerceIn(edge, 180f - edge)
        val pitch = (sp.pitch.pick(random) * deg).coerceIn(MIN_PITCH + 0.01f, MAX_PITCH - 0.01f)
        val bh = stage.bodyHeight.coerceIn(0.5f, 4f)
        val d0 = sp.distance.pick(random) * bh * nearer
        val d1 = (d0 * (1f - sp.push.pick(random) * m)).coerceAtLeast(0.5f)
        val fov = sp.fov.pick(random)
        val at = anchor - focus
        val yaw0 = lineYaw + lineSide * az0 * deg
        val yaw1 = lineYaw + lineSide * az1 * deg
        val shot = CinematicShot(sp.type, "$LIBRARY${sp.id}:${sector(yaw0)}", ShotFrame(yaw0, pitch, d0, at, fov),
            ShotFrame(yaw1, pitch, d1, at, fov), seconds, cut = false)
        return Built(shot, framed, faces, over)
    }

    /** Where the eye is round the line of action now (degrees, 0..180 on the scene's side; negative: the other side). */
    fun azimuthOf(f: ShotFrame): Float = Math.toDegrees((OrbitMath.wrap(f.yaw - lineYaw) * lineSide).toDouble()).toFloat()

    /**
     * The library shot's frame [f] moved by the random drift: a seeded walk round, up and in, pulled back to the shot, within
     * the shot's own ranges (half of each, at most [DRIFT_AZ_MAX] and [DRIFT_PITCH_MAX] degrees and [DRIFT_DIST] of the
     * distance) scaled by Randomness, plus the shot's handheld [ShotSpec.sway]; eased in as the shot is reached, kept on the
     * scene's side of the line, and given up (halved) where the drifted eye would not be clear.
     */
    private fun drifted(stage: CinematicStage, focus: Vec3, f: ShotFrame, sp: ShotSpec, step: Float): ShotFrame {
        val s = shot ?: return f
        clock += step
        val r = settings.clamped().randomness / 100f
        if (r <= 0f && sp.sway <= 0f) return f
        if (step > 0f) {
            val pull = DRIFT_PULL * step
            val noise = DRIFT_NOISE * sqrt(step)
            du = (du - du * pull + noise * gauss()).coerceIn(-1f, 1f)
            dp = (dp - dp * pull + noise * gauss()).coerceIn(-1f, 1f)
            dd = (dd - dd * pull + noise * gauss()).coerceIn(-1f, 1f)
        }
        val w = if (s.cut || s.travel <= 0f) 1f else easeInOut((shotTime / s.travel).coerceIn(0f, 1f))
        val deg = (PI / 180).toFloat()
        val azAmp = min(sp.azimuth.half, DRIFT_AZ_MAX) * r * w * deg
        val pAmp = min(sp.pitch.half, DRIFT_PITCH_MAX) * r * w * deg
        val dAmp = DRIFT_DIST * r * w
        val sway = sp.sway * deg * w * (0.5f + 0.5f * r)
        val sy = sway * (0.6f * sin(clock * 0.83f) + 0.4f * sin(clock * 1.71f + 1.3f))
        val sp2 = sway * 0.6f * (0.6f * sin(clock * 0.67f + 0.4f) + 0.4f * sin(clock * 1.37f + 2.1f))
        val g = f.copy(yaw = f.yaw + du * azAmp + sy, pitch = (f.pitch + dp * pAmp + sp2).coerceIn(MIN_PITCH, MAX_PITCH),
            distance = f.distance * (1f + dd * dAmp))
        val az = azimuthOf(g)
        val edge = if (sp.crossLine) -1f else LINE_EDGE * 0.5f
        if ((sp.crossLine || (az >= edge && az <= 180f - edge)) && eyeGood(stage, focus, g, emptyList())) return g
        du *= 0.5f; dp *= 0.5f; dd *= 0.5f
        return f
    }

    /** A standard normal number from the drift's random numbers (Box-Muller). */
    private fun gauss(): Float {
        val u = wander.nextDouble().coerceAtLeast(1e-9)
        val v = wander.nextDouble()
        return (sqrt(-2.0 * kotlin.math.ln(u)) * cos(2.0 * PI * v)).toFloat()
    }

    /** Why shots were turned down, by type and reason (tests and the film's report). */
    val rejections = HashMap<String, Int>()

    private fun reject(shot: CinematicShot, why: String): CinematicShot? {
        val key = "${shot.type}: $why"
        rejections[key] = (rejections[key] ?: 0) + 1
        return null
    }

    private fun dist(a: Vec3, b: Vec3): Float = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    /** The shot's own framing points: the heads (and for a shoulder shot the far one's alone), each with the hips under them. */
    private fun heads(stage: CinematicStage, shot: CinematicShot): List<Vec3> = owners(stage, shot).map { stage.head(it) }

    /** Whose heads a close-up or a shoulder shot frames (the far one of a shoulder shot; one person's close-up: theirs). */
    private fun owners(stage: CinematicStage, shot: CinematicShot): List<Int> {
        val last = stage.count - 1
        if (shot.type == ShotType.OVER_SHOULDER)
            return listOf((shot.preset.substringAfter('>').takeWhile { it.isDigit() }.toIntOrNull() ?: 0).coerceIn(0, last))
        shot.preset.substringAfter('@', "").toIntOrNull()?.let { return listOf(it.coerceIn(0, last)) }
        return near(stage)
    }

    /** The shoulder a shoulder shot looks over (its near person), else -1. */
    private fun shoulder(shot: CinematicShot): Int =
        if (shot.type == ShotType.OVER_SHOULDER) shot.preset.substringAfter(':').takeWhile { it.isDigit() }.toIntOrNull() ?: -1 else -1

    /**
     * Whether someone else's body stands between [eye] and the face it frames ([target], person [owner]'s): the line passes
     * within [OCCLUDE] of another person's body (but [except]'s: the shoulder looked over), short of the face itself.
     */
    private fun occluded(stage: CinematicStage, eye: Vec3, target: Vec3, owner: Int, except: Int): Boolean {
        for (j in 0 until stage.count) {
            if (j == owner || j == except) continue
            for (k in 1..9) {
                val t = k / 12f
                val p = Vec3(eye.x + (target.x - eye.x) * t, eye.y + (target.y - eye.y) * t, eye.z + (target.z - eye.z) * t)
                if (bodyDistance(p, stage.hip(j), stage.head(j)) < OCCLUDE) return true
            }
        }
        return false
    }

    /**
     * Whether a frame's eye is good: the pitch within limits, the eye over the floor and under the ceiling, out of the
     * furniture's boxes and the people's bodies, clear of the walls along the floor; with [framing], the people in the picture.
     */
    internal fun eyeGood(stage: CinematicStage, focus: Vec3, f: ShotFrame, pts: List<Vec3>, framing: Boolean = false): Boolean {
        if (f.pitch < MIN_PITCH || f.pitch > MAX_PITCH || !f.distance.isFinite() || f.distance <= 0.3f) return false
        val at = focus + f.at
        val eye = CinematicFraming.eye(at, f.yaw, f.pitch, f.distance)
        val floor = stage.floorZ
        if (!floor.isNaN() && (eye.z < floor + FLOOR_GAP || eye.z > ceiling(stage) - CEILING_GAP)) return false
        for ((lo, hi) in stage.obstacles) {
            if (eye.x > lo.x - BOX_GAP && eye.x < hi.x + BOX_GAP && eye.y > lo.y - BOX_GAP && eye.y < hi.y + BOX_GAP &&
                eye.z > lo.z - BOX_GAP && eye.z < hi.z + BOX_GAP) return false
        }
        for (i in 0 until stage.count) {
            if (bodyDistance(eye, stage.hip(i), stage.head(i)) < BODY_GAP) return false
        }
        val c = stage.clearance
        if (c != null && !floor.isNaN()) {
            val r = reachFor(stage, c)
            if (!r.clear(eye)) return false
        }
        if (framing && !CinematicFraming.inFrame(eye, at, max(f.fov, MAX_FOV), stage.aspect, pts, MARGIN)) return false
        return true
    }

    /** The floor reach for the stage's wall test, reset when the focus moved or the furniture changed. */
    private fun reachFor(stage: CinematicStage, c: CameraClearance): FloorReach {
        val furn = stage.furniture
        val centre = furn?.centre ?: rawFocus(stage)
        val beside = if (furn != null && furn.box == null) furn.radius + 0.3f else 0.3f
        var r = reach
        if (r == null || reachFor !== c) { r = FloorReach(c); reach = r; reachFor = c; reachAt = Vec3(Float.NaN, 0f, 0f) }
        if (reachAt.x.isNaN() || hypot(reachAt.x - centre.x, reachAt.y - centre.y) > 0.5f || reachOf != furn?.id) {
            r.reset(centre, beside, stage.floorZ, furn?.box)
            reachAt = centre
            reachOf = furn?.id
        }
        return r
    }

    /** Walk tests asked of the scene so far (logs). */
    val wallTests: Int get() = reach?.asked ?: 0

    // ---------------------------------------------------------------------------------------------- the stage's maths

    /** The action's middle: the furniture's people when there is furniture in use, else every head's and hip's middle. */
    fun rawFocus(stage: CinematicStage): Vec3 {
        stage.furniture?.let { return it.centre }
        var x = 0f; var y = 0f; var z = 0f
        for (i in 0 until stage.count) {
            x += stage.hipX[i] + stage.headX[i]; y += stage.hipY[i] + stage.headY[i]; z += stage.hipZ[i] + stage.headZ[i]
        }
        val n = 2f * stage.count.coerceAtLeast(1)
        return Vec3(x / n, y / n, z / n)
    }

    /** The people the shots frame: those within [NEAR] yards of the action's middle (someone across the room is left out). */
    private fun near(stage: CinematicStage): List<Int> {
        val c = rawFocus(stage)
        val close = (0 until stage.count).filter { hypot(stage.hipX[it] - c.x, stage.hipY[it] - c.y) <= NEAR }
        return close.ifEmpty { (0 until stage.count).toList() }
    }

    private fun people(stage: CinematicStage): List<Vec3> {
        val out = ArrayList<Vec3>(stage.count * 2)
        for (i in near(stage)) { out += stage.hip(i); out += stage.head(i) }
        return out
    }

    private fun radius(pts: List<Vec3>, focus: Vec3): Float = pts.maxOfOrNull { hypot(it.x - focus.x, it.y - focus.y) } ?: 0f

    private fun ceiling(stage: CinematicStage): Float =
        if (!stage.ceilingZ.isNaN()) stage.ceilingZ else stage.floorZ + CEILING_H

    /** Which eighth of a turn [yaw] is in (a shot's variant: two shots from the same side are the same preset). */
    private fun sector(yaw: Float): Int = Math.floorMod((OrbitMath.wrap(yaw) / (PI.toFloat() / 4f)).roundToInt(), 8)

    private fun bodyDistance(p: Vec3, a: Vec3, b: Vec3): Float {
        val abx = b.x - a.x; val aby = b.y - a.y; val abz = b.z - a.z
        val l2 = abx * abx + aby * aby + abz * abz
        val t = if (l2 > 1e-6f) (((p.x - a.x) * abx + (p.y - a.y) * aby + (p.z - a.z) * abz) / l2).coerceIn(0f, 1f) else 0f
        val cx = a.x + abx * t - p.x; val cy = a.y + aby * t - p.y; val cz = a.z + abz * t - p.z
        return sqrt(cx * cx + cy * cy + cz * cz)
    }

    /** A camera's pose as a frame relative to [focus]. */
    fun frameOf(eye: Vec3, at: Vec3, fovDeg: Float, focus: Vec3): ShotFrame {
        val dx = eye.x - at.x; val dy = eye.y - at.y; val dz = eye.z - at.z
        val d = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-3f)
        return ShotFrame(atan2(dy, dx), asin((dz / d).coerceIn(-1f, 1f)), d, at - focus,
            if (fovDeg.isFinite() && fovDeg > 1f) fovDeg else 50f)
    }
}
