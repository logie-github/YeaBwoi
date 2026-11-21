package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Build mode's maths (the live scene's build mode: ui/BuildModeUi.kt): the camera as the renderer draws it ([BuildView]: a
 * look-at with z up and a vertical field of view, so a world point is projected exactly where it is drawn), the axis gizmo's
 * handles on the screen ([BuildGizmo.hit]: its three arrows and its yaw ring, hit within a finger's tolerance), what a drag on
 * a handle does ([BuildGizmo.alongAxis]: the drag's component along the arrow as the screen shows it, in yards; [BuildGizmo.ringDelta]:
 * the turn about the pivot), the snaps ([BuildSnap]) and the engine's gizmo size (the same share of the view near or far).
 * Nothing is picked by the engine: the screen projects the handles with the camera the renderer reported. Pure.
 */
data class ScreenPoint(val x: Float, val y: Float)

/** The camera drawn: [eye] looking at [target] (z up), [fovYDeg] vertical, on a [width] x [height] pixel view. */
data class BuildView(val eye: Vec3, val target: Vec3, val fovYDeg: Float, val width: Float, val height: Float) {
    private val f: Vec3 = norm(target - eye)
    private val s: Vec3 = norm(Vec3(f.y, -f.x, 0f).let { if (it.x * it.x + it.y * it.y < 1e-8f) Vec3(1f, 0f, 0f) else it })
    private val u: Vec3 = Vec3(s.y * f.z - s.z * f.y, s.z * f.x - s.x * f.z, s.x * f.y - s.y * f.x)
    private val tanHalf = tan(Math.toRadians(fovYDeg.toDouble() / 2.0).toFloat()).coerceAtLeast(1e-4f)
    private val aspect = width / height.coerceAtLeast(1f)

    /** How far in front of the eye [p] is (along the view's direction). */
    fun depth(p: Vec3): Float = dot(p - eye, f)

    /** Where [p] is drawn (pixels, y down), or null behind the eye. */
    fun project(p: Vec3): ScreenPoint? {
        val d = p - eye
        val z = dot(d, f)
        if (z <= 1e-4f) return null
        val nx = dot(d, s) / (z * tanHalf * aspect)
        val ny = dot(d, u) / (z * tanHalf)
        return ScreenPoint((nx + 1f) * 0.5f * width, (1f - ny) * 0.5f * height)
    }

    /** The ray from the eye through the screen point: origin and unit direction. */
    fun ray(sx: Float, sy: Float): Pair<Vec3, Vec3> {
        val nx = 2f * sx / width.coerceAtLeast(1f) - 1f
        val ny = 1f - 2f * sy / height.coerceAtLeast(1f)
        return eye to norm(f + s * (nx * tanHalf * aspect) + u * (ny * tanHalf))
    }

    /** Where the ray through the screen point meets the level plane at height [z] (null: parallel or behind). */
    fun onPlane(sx: Float, sy: Float, z: Float): Vec3? {
        val (o, d) = ray(sx, sy)
        if (abs(d.z) < 1e-5f) return null
        val t = (z - o.z) / d.z
        return if (t <= 0f) null else o + d * t
    }

    companion object {
        fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z
        fun norm(v: Vec3): Vec3 {
            val l = sqrt(dot(v, v))
            return if (l < 1e-8f) Vec3(0f, 0f, 1f) else v * (1f / l)
        }
    }
}

/** A handle of build mode's gizmo: an arrow along the thing's own x, y or z, or the yaw ring ([axis]: the engine's numbering). */
enum class GizmoHandle(val axis: Int, val label: String) { X(0, "X"), Y(1, "Y"), Z(2, "Z"), RING(3, "Turn") }

/**
 * The snap of a drag: [yards] a step along an axis (0: none) and [degrees] a step of the ring; Fine moves a quarter as far as
 * the finger (no snap, 1 degree steps).
 */
enum class BuildSnap(val id: String, val label: String, val yards: Float, val degrees: Float, val gain: Float) {
    FINE("fine", "Fine", 0f, 1f, 0.25f),
    CM1("1cm", "1 cm", 0.01f / 0.9144f, 15f, 1f),
    CM5("5cm", "5 cm", 0.05f / 0.9144f, 15f, 1f),
    CM10("10cm", "10 cm", 0.10f / 0.9144f, 15f, 1f);

    fun next(): BuildSnap = entries[(ordinal + 1) % entries.size]

    companion object {
        const val METRES_PER_YARD = 0.9144f
        fun of(id: String?): BuildSnap? = entries.firstOrNull { it.id == id }
    }
}

object BuildGizmo {
    /** The arrows are this share of the view's height long (the engine's kBuildShare). */
    const val SHARE = 0.19f
    /** A finger within this many dp of a handle takes it. */
    const val TOUCH_DP = 24f
    /** Where the arrow's grip starts along it, and the ring's radius (shares of the length, as drawn). */
    const val ARROW_FROM = 0.10f
    const val RING = 0.9f

    /** The gizmo's length in yards at [pivot] seen by [view] (the same share of the screen near or far, as the engine draws it). */
    fun length(view: BuildView, pivot: Vec3): Float =
        SHARE * 2f * max(0.3f, view.depth(pivot)) * tan(Math.toRadians(view.fovYDeg.toDouble() / 2.0).toFloat())

    /** The thing's own axes: x its front, y its left (turned by [yaw] radians), z up. */
    fun axes(yaw: Float): List<Vec3> = listOf(Vec3(cos(yaw), sin(yaw), 0f), Vec3(-sin(yaw), cos(yaw), 0f), Vec3(0f, 0f, 1f))

    fun segDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 > 1e-6f) (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f) else 0f
        return hypot(px - (ax + dx * t), py - (ay + dy * t))
    }

    /**
     * The handle under the screen point ([sx], [sy]) within [tolerancePx] (the nearest; an arrow before the ring on a tie):
     * the arrows ([arrows]) from the grip to the tip, the ring ([ring]) as 48 short segments. Null: none.
     */
    fun hit(view: BuildView, pivot: Vec3, yaw: Float, sx: Float, sy: Float, tolerancePx: Float,
            arrows: Boolean = true, ring: Boolean = true): GizmoHandle? {
        val len = length(view, pivot)
        var best: GizmoHandle? = null
        var bestD = tolerancePx
        if (arrows) {
            val ax = axes(yaw)
            for ((i, h) in listOf(GizmoHandle.X, GizmoHandle.Y, GizmoHandle.Z).withIndex()) {
                val a = view.project(pivot + ax[i] * (ARROW_FROM * len)) ?: continue
                val b = view.project(pivot + ax[i] * len) ?: continue
                val d = segDistance(sx, sy, a.x, a.y, b.x, b.y)
                if (d <= bestD) { bestD = d; best = h }
            }
        }
        if (ring) {
            var prev: ScreenPoint? = null
            val n = 48
            for (i in 0..n) {
                val a = 2.0 * PI * i / n
                val p = view.project(pivot + Vec3(cos(a).toFloat(), sin(a).toFloat(), 0f) * (RING * len))
                if (p != null && prev != null) {
                    val d = segDistance(sx, sy, prev.x, prev.y, p.x, p.y)
                    if (d < bestD) { bestD = d; best = GizmoHandle.RING }
                }
                prev = p
            }
        }
        return best
    }

    /**
     * Yards along [axis] (a unit vector) for a finger moved from ([sx0], [sy0]) to ([sx1], [sy1]): the drag's component along
     * the axis as the screen shows it at [pivot], scaled by how long a yard of it is on the screen. An axis pointing at the
     * eye (shorter than [minPx] on the screen) gives nothing: it can't be dragged from this side.
     */
    fun alongAxis(view: BuildView, pivot: Vec3, axis: Vec3, sx0: Float, sy0: Float, sx1: Float, sy1: Float, minPx: Float = 6f): Float {
        val len = length(view, pivot)
        val a = view.project(pivot) ?: return 0f
        val b = view.project(pivot + axis * len) ?: return 0f
        val vx = b.x - a.x; val vy = b.y - a.y
        val px = hypot(vx, vy)
        if (px < minPx) return 0f
        val along = ((sx1 - sx0) * vx + (sy1 - sy0) * vy) / px
        return along / px * len
    }

    /** The finger's angle about the pivot as the screen shows it (radians, counter-clockwise), null when the pivot is behind. */
    fun screenAngle(view: BuildView, pivot: Vec3, sx: Float, sy: Float): Float? {
        val c = view.project(pivot) ?: return null
        if (hypot(sx - c.x, sy - c.y) < 1f) return null
        return atan2(-(sy - c.y), sx - c.x)
    }

    /**
     * The turn (radians, counter-clockwise seen from above) a finger moving round the ring from ([sx0], [sy0]) to ([sx1], [sy1])
     * gives: the change of its angle about the pivot on the screen, the other way round when the eye is under the ring.
     */
    fun ringDelta(view: BuildView, pivot: Vec3, sx0: Float, sy0: Float, sx1: Float, sy1: Float): Float {
        val a0 = screenAngle(view, pivot, sx0, sy0) ?: return 0f
        val a1 = screenAngle(view, pivot, sx1, sy1) ?: return 0f
        val d = ItemFrame.wrapRad(a1 - a0)
        return if (view.eye.z < pivot.z) -d else d
    }

    /** [value] to the nearest [step] (0: as it is). */
    fun snap(value: Float, step: Float): Float = if (step <= 0f) value else round(value / step) * step

    /** Degrees to the nearest [stepDeg], wrapped to -180..180. */
    fun snapDeg(deg: Float, stepDeg: Float): Float = ItemFrame.wrapDeg(snap(deg, stepDeg))
}

/**
 * One drag of a handle, from the pose the thing had when the finger went down ([start]: x, y, z yards, yaw degrees) to where
 * the finger is now: the total since the start is snapped (so a slow drag never drifts off the grid), never added frame by frame.
 */
class BuildGizmoDrag(val handle: GizmoHandle, val start: BuildPose, private val startX: Float, private val startY: Float, val snap: BuildSnap) {
    /** The pose for the finger at ([sx], [sy]) seen by [view]. */
    fun poseAt(view: BuildView, sx: Float, sy: Float): BuildPose {
        val pivot = Vec3(start.x, start.y, start.z)
        val yaw = Math.toRadians(start.yawDeg.toDouble()).toFloat()
        return when (handle) {
            GizmoHandle.RING -> {
                val turn = Math.toDegrees(BuildGizmo.ringDelta(view, pivot, startX, startY, sx, sy).toDouble()).toFloat() * snap.gain
                start.copy(yawDeg = BuildGizmo.snapDeg(start.yawDeg + turn, snap.degrees))
            }
            else -> {
                val axis = BuildGizmo.axes(yaw)[handle.axis]
                val d = BuildGizmo.snap(BuildGizmo.alongAxis(view, pivot, axis, startX, startY, sx, sy) * snap.gain, snap.yards)
                start.copy(x = start.x + axis.x * d, y = start.y + axis.y * d, z = start.z + axis.z * d)
            }
        }
    }
}

/** A place and facing in the place's frame (yards, degrees from +x). */
data class BuildPose(val x: Float, val y: Float, val z: Float, val yawDeg: Float) {
    val at: Vec3 get() = Vec3(x, y, z)
    val yaw: Float get() = Math.toRadians(yawDeg.toDouble()).toFloat()

    companion object {
        fun of(v: Vec3, yawRad: Float) = BuildPose(v.x, v.y, v.z, ItemFrame.wrapDeg(Math.toDegrees(yawRad.toDouble()).toFloat()))
    }
}
