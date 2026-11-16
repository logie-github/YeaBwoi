package com.logie.wifecraft.ui

import com.logie.wifecraft.catalog.Asset
import com.logie.wifecraft.editor.Placement
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Pure Kotlin, no android.* and no Compose: the placement editor's tools as state and maths, JVM-tested
// (unit-tests/.../ui/PlaceModesTest.kt): the touch ray from the renderer's camera matrices, the gizmo's drags (a point of
// an axis under the finger, the ring's turn, the boxes' size), what a tap on the gizmo hits, the placing mode (the ghost
// under the finger, lift to drop, armed until Done), cycling through the things under a finger, double taps, and the
// one-line tips. WoW's coordinates: Z up, yaw radians about Z counter-clockwise from +X.

private fun vlen(v: Vec3) = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
private fun vnorm(v: Vec3): Vec3 = vlen(v).let { l -> if (l < 1e-6f) Vec3(0f, 0f, 0f) else Vec3(v.x / l, v.y / l, v.z / l) }
private fun vdot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z
private fun vcross(a: Vec3, b: Vec3) = Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

// ------------------------------------------------------------------------------------------------- the camera's matrices

/** 4x4 matrices as OpenGL keeps them: 16 floats, column-major (m[col * 4 + row]). */
object Mat4 {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0..3) for (row in 0..3) {
            var s = 0f
            for (k in 0..3) s += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = s
        }
        return r
    }

    /** The point [p] (w = 1) through [m]: x, y, z, w. */
    fun transform(m: FloatArray, x: Float, y: Float, z: Float, w: Float = 1f): FloatArray = FloatArray(4) { row ->
        m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row] * w
    }

    /** The inverse of [m], or null when it has none. */
    fun invert(m: FloatArray): FloatArray? {
        val a = DoubleArray(16) { m[it].toDouble() }
        val inv = DoubleArray(16)
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10]
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10]
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9]
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9]
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10]
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10]
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9]
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9]
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6]
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6]
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5]
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5]
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6]
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6]
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5]
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5]
        val det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12]
        if (abs(det) < 1e-12) return null
        return FloatArray(16) { (inv[it] / det).toFloat() }
    }

    /** A view matrix looking from [eye] at [at], Z up (gluLookAt). */
    fun lookAt(eye: Vec3, at: Vec3, up: Vec3 = Vec3(0f, 0f, 1f)): FloatArray {
        val f = vnorm(at - eye)
        val s = vnorm(vcross(f, up))
        val u = vcross(s, f)
        val m = identity()
        m[0] = s.x; m[4] = s.y; m[8] = s.z
        m[1] = u.x; m[5] = u.y; m[9] = u.z
        m[2] = -f.x; m[6] = -f.y; m[10] = -f.z
        m[12] = -vdot(s, eye); m[13] = -vdot(u, eye); m[14] = vdot(f, eye)
        return m
    }

    /** A perspective projection of a vertical field of view [fovYDeg] (gluPerspective). */
    fun perspective(fovYDeg: Float, aspect: Float, near: Float = 0.1f, far: Float = 5000f): FloatArray {
        val f = 1f / tan(Math.toRadians(fovYDeg / 2.0).toFloat())
        val m = FloatArray(16)
        m[0] = f / aspect; m[5] = f
        m[10] = (far + near) / (near - far); m[11] = -1f
        m[14] = 2f * far * near / (near - far)
        return m
    }
}

/**
 * How a screen point becomes a ray into the world and a world point a screen point: from the renderer's own camera
 * matrices ([MatrixRay], SceneView.cameraState) or, before they have come, from the editor's camera pose ([PoseRay]: the
 * renderer draws that same pose). Pixels of a [w] x [h] view, y down.
 */
interface ViewRay {
    val eye: Vec3
    fun ray(sx: Float, sy: Float, w: Float, h: Float): Pair<Vec3, Vec3>
    fun project(p: Vec3, w: Float, h: Float): Pair<Float, Float>?
    /** Yards per pixel at [p]'s depth. */
    fun worldPerPixel(p: Vec3, h: Float): Float
}

class PoseRay(val pose: CameraPose) : ViewRay {
    override val eye: Vec3 get() = pose.eye
    override fun ray(sx: Float, sy: Float, w: Float, h: Float) = FloorMath.ray(pose, sx, sy, w, h)
    override fun project(p: Vec3, w: Float, h: Float) = FloorMath.project(pose, p, w, h)
    override fun worldPerPixel(p: Vec3, h: Float) = FloorMath.worldPerPixelAt(pose, p, h)
}

/** The renderer's camera as matrices (SceneView.cameraState's CameraInfo without the engine's types). */
data class ViewMatrices(val eye: Vec3, val view: FloatArray, val proj: FloatArray, val aspect: Float) {
    override fun equals(other: Any?) = other is ViewMatrices && eye == other.eye && view.contentEquals(other.view) && proj.contentEquals(other.proj) && aspect == other.aspect
    override fun hashCode() = eye.hashCode() * 31 + view.contentHashCode()
}

class MatrixRay(private val m: ViewMatrices) : ViewRay {
    private val vp = Mat4.multiply(m.proj, m.view)
    private val inv = Mat4.invert(vp)
    override val eye: Vec3 get() = m.eye

    override fun ray(sx: Float, sy: Float, w: Float, h: Float): Pair<Vec3, Vec3> {
        val nx = 2f * sx / w.coerceAtLeast(1f) - 1f
        val ny = 1f - 2f * sy / h.coerceAtLeast(1f)
        val i = inv ?: return m.eye to Vec3(0f, 0f, -1f)
        val a = Mat4.transform(i, nx, ny, -1f)
        val b = Mat4.transform(i, nx, ny, 1f)
        val near = Vec3(a[0] / a[3], a[1] / a[3], a[2] / a[3])
        val far = Vec3(b[0] / b[3], b[1] / b[3], b[2] / b[3])
        return m.eye to vnorm(far - near)
    }

    override fun project(p: Vec3, w: Float, h: Float): Pair<Float, Float>? {
        val c = Mat4.transform(vp, p.x, p.y, p.z)
        if (c[3] <= 0.01f) return null
        return (c[0] / c[3] + 1f) * w / 2f to (1f - c[1] / c[3]) * h / 2f
    }

    override fun worldPerPixel(p: Vec3, h: Float): Float {
        val v = Mat4.transform(m.view, p.x, p.y, p.z)
        val depth = (-v[2]).coerceAtLeast(0.1f)
        val f = m.proj[5].takeIf { it > 1e-4f } ?: 1f
        return 2f * depth / (f * h.coerceAtLeast(1f))
    }
}

object ViewRays {
    /**
     * The ray to use: the renderer's matrices when they are of the camera the editor has now ([pose]'s eye, within a few
     * centimetres) and of a view this shape; the pose otherwise (the matrices come a frame late after the camera moves).
     */
    fun choose(m: ViewMatrices?, pose: CameraPose, w: Float, h: Float): ViewRay {
        if (m == null || h <= 0f) return PoseRay(pose)
        val same = vlen(m.eye - pose.eye) < 0.05f && abs(m.aspect - w / h) < 0.02f
        return if (same) MatrixRay(m) else PoseRay(pose)
    }
}

// ------------------------------------------------------------------------------------------------- the gizmo

/** The gizmo's parts, as SceneView.gizmoHit names them. */
object GizmoAxis {
    const val NONE = -1
    const val X = 0
    const val Y = 1
    const val Z = 2
    const val YAW = 3
    const val UNIFORM = 4
    const val FLOOR = 5

    fun dir(axis: Int): Vec3? = when (axis) {
        X -> Vec3(1f, 0f, 0f); Y -> Vec3(0f, 1f, 0f); Z -> Vec3(0f, 0f, 1f); else -> null
    }

    fun name(axis: Int): String = when (axis) {
        X -> "X"; Y -> "Y"; Z -> "Z (up)"; YAW -> "Turn"; UNIFORM -> "Size"; FLOOR -> "Floor"; else -> ""
    }
}

/** The gizmo's mode buttons (the Creation Kit's Move, Rotate and Scale). */
enum class GizmoTool(val label: String) { MOVE("Move"), ROTATE("Rotate"), SCALE("Scale") }

object GizmoMath {
    /** The renderer draws the arrows this share of the view's height long, wherever the thing stands (cpp editview.cpp). */
    const val HANDLE_SHARE = 0.19f
    /** One drag never moves a thing further than this along an axis (a ray nearly along the axis reaches far). */
    const val MAX_MOVE = 60f

    /**
     * Where along the axis through [ao] in direction [ad] (a unit vector) the ray ([ro], [rd]) passes closest: the
     * parameter t of the axis's point ao + ad * t. Null when the ray runs (nearly) along the axis.
     */
    fun closestOnAxis(ro: Vec3, rd: Vec3, ao: Vec3, ad: Vec3): Float? {
        val w0 = ao - ro
        val a = vdot(ad, ad); val b = vdot(ad, rd); val c = vdot(rd, rd)
        val d = vdot(ad, w0); val e = vdot(rd, w0)
        val den = a * c - b * b
        if (den < 1e-4f) return null
        return (b * e - c * d) / den
    }

    /** Where the ray meets the level plane at height [z]; null when it runs along it or looks away. */
    fun planeHit(ro: Vec3, rd: Vec3, z: Float): Vec3? {
        if (abs(rd.z) < 1e-5f) return null
        val t = (z - ro.z) / rd.z
        if (t <= 0f || t > FloorMath.MAX_REACH) return null
        return ro + rd * t
    }

    /**
     * What a drag of the handle [axis] does in [tool]'s mode: in Scale, the per-axis boxes size the whole thing (a placement
     * has one size), as the middle box does; Move takes the arrows and the floor square; Rotate the ring. NONE: no drag.
     */
    fun dragAxis(tool: GizmoTool, axis: Int): Int = when (tool) {
        GizmoTool.MOVE -> if (axis in GizmoAxis.X..GizmoAxis.Z || axis == GizmoAxis.FLOOR) axis else GizmoAxis.NONE
        GizmoTool.ROTATE -> if (axis == GizmoAxis.YAW) axis else GizmoAxis.NONE
        GizmoTool.SCALE -> if (axis in GizmoAxis.X..GizmoAxis.Z || axis == GizmoAxis.UNIFORM) GizmoAxis.UNIFORM else GizmoAxis.NONE
    }

    /**
     * The fallback of SceneView.gizmoHit (used until the renderer answers), laid out as the renderer draws the gizmo (an
     * arrow [HANDLE_SHARE] of the view's height long, L): which handle of [tool]'s gizmo on [p] the point ([sx], [sy]) is
     * within [slopPx] of. Move: the arrows (from 0.1 L), the floor square (0.35 L out along x and y, 0.26 L across) and the
     * pivot; Rotate: the ring (0.9 L); Scale: the boxes at the arrows' ends (their axis) and the middle box (which wins
     * where both are under the finger). [GizmoAxis.NONE] for none.
     */
    fun hit(cam: ViewRay, tool: GizmoTool, p: Placement, sx: Float, sy: Float, w: Float, h: Float, slopPx: Float = 40f): Int {
        val o = Vec3(p.x, p.y, p.z)
        val c = cam.project(o, w, h) ?: return GizmoAxis.NONE
        val len = HANDLE_SHARE * h * cam.worldPerPixel(o, h)
        fun dist(a: Pair<Float, Float>) = hypot(a.first - sx, a.second - sy)
        fun segDist(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
            val vx = b.first - a.first; val vy = b.second - a.second
            val l2 = vx * vx + vy * vy
            val t = if (l2 < 1e-3f) 0f else (((sx - a.first) * vx + (sy - a.second) * vy) / l2).coerceIn(0f, 1f)
            return hypot(a.first + vx * t - sx, a.second + vy * t - sy)
        }
        /** A box handle at [at] [half] yards across: the distance to it less its size on the screen. */
        fun knob(at: Vec3, half: Float): Float? {
            val q = cam.project(at, w, h) ?: return null
            val r = cam.project(at + Vec3(0f, 0f, half), w, h)?.let { hypot(it.first - q.first, it.second - q.second) } ?: 0f
            return (dist(q) - r).coerceAtLeast(0f)
        }
        var best = GizmoAxis.NONE
        var bestD = slopPx
        fun offer(axis: Int, d: Float?) { if (d != null && d <= bestD) { best = axis; bestD = d } }
        when (tool) {
            GizmoTool.MOVE -> {
                for (axis in GizmoAxis.X..GizmoAxis.Z) {
                    val d = GizmoAxis.dir(axis)!!
                    val a = cam.project(o + d * (0.10f * len), w, h) ?: continue
                    val b = cam.project(o + d * len, w, h) ?: continue
                    offer(axis, segDist(a, b))
                }
                offer(GizmoAxis.FLOOR, knob(o + Vec3(0.35f * len, 0.35f * len, 0f), 0.13f * len))
                offer(GizmoAxis.FLOOR, knob(o, 0.10f * len))
            }
            GizmoTool.ROTATE -> {
                var prev: Pair<Float, Float>? = null
                for (i in 0..72) {
                    val a = 2.0 * PI * i / 72
                    val q = cam.project(o + Vec3(cos(a).toFloat() * 0.9f * len, sin(a).toFloat() * 0.9f * len, 0f), w, h)
                    if (q != null && prev != null) offer(GizmoAxis.YAW, segDist(prev, q))
                    prev = q
                }
            }
            GizmoTool.SCALE -> {
                for (axis in GizmoAxis.X..GizmoAxis.Z) {
                    val d = GizmoAxis.dir(axis)!!
                    val a = cam.project(o + d * (0.12f * len), w, h)
                    val b = cam.project(o + d * (0.82f * len), w, h)
                    if (a != null && b != null) offer(axis, segDist(a, b))
                    offer(axis, knob(o + d * (0.88f * len), 0.06f * len))
                }
                offer(GizmoAxis.UNIFORM, knob(o, 0.09f * len))
                val r = cam.project(o + Vec3(0f, 0f, 0.09f * len), w, h)?.let { hypot(it.first - c.first, it.second - c.second) } ?: 0f
                if (dist(c) <= r + slopPx * 0.5f) best = GizmoAxis.UNIFORM
            }
        }
        return best
    }

    /** A drag's size factor: how much further from the handle's centre [c] the finger is now ([p1]) than it began ([p0]). */
    fun scaleFactor(c: Pair<Float, Float>, p0: Pair<Float, Float>, p1: Pair<Float, Float>): Float {
        val d0 = hypot(p0.first - c.first, p0.second - c.second)
        val d1 = hypot(p1.first - c.first, p1.second - c.second)
        // a drag that began on the centre: up grows, down shrinks
        if (d0 < 16f) return exp((p0.second - p1.second) / 200f)
        return (d1 / d0).coerceIn(0.02f, 50f)
    }
}

/**
 * One drag of a gizmo handle (or of the thing's body: [GizmoAxis.FLOOR]) on [start], begun at the screen point [p0]. [to]
 * gives the placement as the finger is now: an arrow moves it along its axis (the axis's point closest to the touch ray,
 * or, when the ray runs along the axis, the finger's move along the axis on the screen); the floor square and the body
 * slide it on its level floor; the ring turns it about its feet (the point of the ring's plane under the finger, or the
 * finger's turn about it on the screen when the plane is seen edge on); the boxes resize it. Snapping applies as it goes.
 */
class GizmoDrag private constructor(
    val axis: Int,
    val start: Placement,
    private val p0: Pair<Float, Float>,
    private val t0: Float?,
    private val floor0: Vec3?,
    private val angle0: Float?,
) {
    private val origin get() = Vec3(start.x, start.y, start.z)

    fun to(cam: ViewRay, sx: Float, sy: Float, w: Float, h: Float, snap: Snap = Snap()): Placement {
        val o = origin
        return when (axis) {
            GizmoAxis.X, GizmoAxis.Y, GizmoAxis.Z -> {
                val ad = GizmoAxis.dir(axis)!!
                val (ro, rd) = cam.ray(sx, sy, w, h)
                val t1 = GizmoMath.closestOnAxis(ro, rd, o, ad)
                val dt = (if (t0 != null && t1 != null) t1 - t0 else screenAlong(cam, ad, sx, sy, w, h))
                    .coerceIn(-GizmoMath.MAX_MOVE, GizmoMath.MAX_MOVE)
                when (axis) {
                    GizmoAxis.X -> start.copy(x = snap.pos(start.x + dt))
                    GizmoAxis.Y -> start.copy(y = snap.pos(start.y + dt))
                    else -> start.copy(z = snap.pos(start.z + dt))
                }
            }
            GizmoAxis.FLOOR -> {
                val (ro, rd) = cam.ray(sx, sy, w, h)
                val now = GizmoMath.planeHit(ro, rd, start.z)
                if (floor0 == null || now == null) return start
                var dx = now.x - floor0.x; var dy = now.y - floor0.y
                val l = hypot(dx, dy)
                if (l > FloorMath.MAX_STEP) { dx *= FloorMath.MAX_STEP / l; dy *= FloorMath.MAX_STEP / l }
                start.copy(x = snap.pos(start.x + dx), y = snap.pos(start.y + dy))
            }
            GizmoAxis.YAW -> {
                val turn = if (angle0 != null) {
                    val (ro, rd) = cam.ray(sx, sy, w, h)
                    val hit = if (abs(rd.z) > EDGE_ON) GizmoMath.planeHit(ro, rd, start.z) else null
                    if (hit != null) wrapAngle(atan2(hit.y - o.y, hit.x - o.x) - angle0) else screenTurn(cam, sx, sy, w, h)
                } else screenTurn(cam, sx, sy, w, h)
                start.copy(yaw = snap.yaw(start.yaw + turn))
            }
            GizmoAxis.UNIFORM -> {
                val c = cam.project(Vec3(o.x, o.y, o.z), w, h) ?: return start
                start.copy(scale = snap.scale(start.scale * GizmoMath.scaleFactor(c, p0, sx to sy)))
            }
            else -> start
        }
    }

    /** The finger's move along the axis's direction on the screen, in yards. */
    private fun screenAlong(cam: ViewRay, ad: Vec3, sx: Float, sy: Float, w: Float, h: Float): Float {
        val a = cam.project(origin, w, h) ?: return 0f
        val b = cam.project(origin + ad, w, h) ?: return 0f
        val vx = b.first - a.first; val vy = b.second - a.second
        val l2 = vx * vx + vy * vy
        if (l2 < 1f) return 0f     // the axis points at the eye: no move along it can be seen
        return ((sx - p0.first) * vx + (sy - p0.second) * vy) / l2
    }

    private fun screenTurn(cam: ViewRay, sx: Float, sy: Float, w: Float, h: Float): Float {
        val c = cam.project(origin, w, h) ?: return 0f
        return Gestures.yawAround(c, p0, sx to sy)
    }

    companion object {
        /** The ring's plane is "edge on" when the ray's slope is under this. */
        const val EDGE_ON = 0.08f

        fun begin(axis: Int, start: Placement, cam: ViewRay, sx: Float, sy: Float, w: Float, h: Float): GizmoDrag? {
            if (axis !in GizmoAxis.X..GizmoAxis.FLOOR) return null
            val o = Vec3(start.x, start.y, start.z)
            val (ro, rd) = cam.ray(sx, sy, w, h)
            val t0 = GizmoAxis.dir(axis)?.let { GizmoMath.closestOnAxis(ro, rd, o, it) }
            val floor0 = if (axis == GizmoAxis.FLOOR) GizmoMath.planeHit(ro, rd, start.z) else null
            val angle0 = if (axis == GizmoAxis.YAW && abs(rd.z) > EDGE_ON) GizmoMath.planeHit(ro, rd, start.z)?.let { atan2(it.y - o.y, it.x - o.x) } else null
            return GizmoDrag(axis, start, sx to sy, t0, floor0, angle0)
        }
    }
}

// ------------------------------------------------------------------------------------------------- placing mode

/**
 * The placing mode: a library entry ([asset]) is armed and its see-through copy (the ghost: SceneView.ghostBegin) follows
 * one finger over the floor, or sits at the crosshair (a tap moves it there). Lifting the finger after a drag drops it
 * (a Placement, one undo step) and the mode stays armed for the next; Drop puts one at the crosshair; Done or Back leaves.
 * [yaw] is the ghost's turn (the rotate control), [at] its floor point now, [placed] how many were dropped since it was
 * armed. The ghost's model loads first ([ready]; [error] when it can't be drawn: then nothing drops).
 */
data class Placing(
    val asset: Asset,
    val yaw: Float = 0f,
    val ready: Boolean = false,
    val error: String? = null,
    val cross: Pair<Float, Float>? = null,
    val at: Vec3? = null,
    val dragging: Boolean = false,
    val placed: Int = 0,
) {
    val canDrop: Boolean get() = ready && error == null && at != null

    fun loaded(ok: Boolean, why: String?): Placing =
        if (ok) copy(ready = true, error = null) else copy(ready = false, error = why?.takeIf { it.isNotBlank() } ?: "The model couldn't be loaded.")

    fun turned(delta: Float): Placing = copy(yaw = wrapAngle(yaw + delta))
    /** A finger went down on the stage: the ghost follows it until it lifts. */
    fun press(): Placing = copy(dragging = true)
    /** The ghost's floor point now (null: the finger isn't over the floor: it stays where it was). */
    fun follow(p: Vec3?): Placing = if (p == null) this else copy(at = p)
    /** A tap: the crosshair (and the ghost) go there; nothing drops. */
    fun aim(sx: Float, sy: Float): Placing = copy(cross = sx to sy, dragging = false)
    /** Two fingers came: the drag is a camera move after all; nothing drops. */
    fun cancelDrag(): Placing = copy(dragging = false)

    /** The finger lifted after a drag: the point to drop at (null: nothing drops) and the mode after (still armed). */
    fun release(): Pair<Placing, Vec3?> {
        val drop = if (dragging && canDrop) at else null
        return copy(dragging = false, placed = placed + if (drop != null) 1 else 0) to drop
    }

    /** Drop: one at the ghost's point (the crosshair's) without a drag. */
    fun dropHere(): Pair<Placing, Vec3?> {
        val drop = if (canDrop) at else null
        return copy(dragging = false, placed = placed + if (drop != null) 1 else 0) to drop
    }

    /** The placing bar's title and its line under it. */
    val title: String get() = "Placing: " + asset.name.ifBlank { "thing" }
    val line: String get() = when {
        error != null -> error
        !ready -> "Loading the model…"
        placed == 0 -> "Drag on the stage to place it; lift to drop. Tap to aim, Drop puts one at the crosshair."
        else -> "${placed} placed. Drag again for another, or Done."
    }
}

// ------------------------------------------------------------------------------------------------- selection

/**
 * The things under a tap, for tapping the same spot again to go to the next one behind (the renderer's pick gives the
 * nearest only): [ids] in order (the renderer's hit first, then the others under the finger, nearest first), [index] the
 * one selected, [at] where the tap was. index -1: nothing.
 */
data class TapCycle(val at: Pair<Float, Float>? = null, val ids: List<String> = emptyList(), val index: Int = -1) {
    val selected: String? get() = ids.getOrNull(index)
}

object SelectCycle {
    /** How tall a placed thing stands for the screen tests (people about two yards; props by their size, unknown here). */
    private fun tall(p: Placement) = 1.8f * p.scale.coerceIn(0.3f, 4f)

    /**
     * The placements whose feet, middle or head are within [reachPx] of the screen point, the nearest the eye first
     * (the editor's own "pick everything": SceneView.pick returns only the nearest).
     */
    fun under(cam: ViewRay, placements: List<Placement>, sx: Float, sy: Float, w: Float, h: Float, reachPx: Float): List<String> =
        placements.mapNotNull { p ->
            val pts = listOf(0f, tall(p) / 2f, tall(p)).mapNotNull { dz -> cam.project(Vec3(p.x, p.y, p.z + dz), w, h) }
            val near = pts.minOfOrNull { hypot(it.first - sx, it.second - sy) } ?: return@mapNotNull null
            if (near > reachPx) null else p.id to vlen(Vec3(p.x, p.y, p.z) - cam.eye)
        }.sortedBy { it.second }.map { it.first }

    /** The cycle's order: the renderer's own hit first (when it is a placement), then the rest under the finger. */
    fun order(engineHit: String?, under: List<String>): List<String> =
        if (engineHit == null) under else listOf(engineHit) + under.filter { it != engineHit }

    /**
     * A tap at ([sx], [sy]) with [ids] under it: the same spot again (within [samePx]) with the same things under it goes
     * to the next of them (round); anywhere else starts again at the first. Nothing under it: nothing selected.
     */
    fun next(c: TapCycle, sx: Float, sy: Float, ids: List<String>, samePx: Float = 48f): TapCycle {
        if (ids.isEmpty()) return TapCycle(sx to sy, emptyList(), -1)
        val same = c.at != null && hypot(c.at.first - sx, c.at.second - sy) <= samePx && c.ids.toSet() == ids.toSet() && c.index >= 0
        return if (same) c.copy(at = sx to sy, index = (c.index + 1) % c.ids.size) else TapCycle(sx to sy, ids, 0)
    }
}

/** A tap is the second of a double tap when it comes within [WINDOW_MS] of the last, within [SLOP_PX] of it. */
object TapTiming {
    const val WINDOW_MS = 300L
    const val SLOP_PX = 60f
    fun isDouble(lastMs: Long?, lastAt: Pair<Float, Float>?, ms: Long, at: Pair<Float, Float>, slopPx: Float = SLOP_PX): Boolean =
        lastMs != null && lastAt != null && ms - lastMs in 0..WINDOW_MS && hypot(lastAt.first - at.first, lastAt.second - at.second) <= slopPx
}

// ------------------------------------------------------------------------------------------------- tips

/** The first-use tips: one line that says what the current mode does; each dismissed once is remembered by [id]. */
enum class PlaceTip(val id: String, val text: String) {
    BROWSE("browse", "Tap a thing to select it, tap again to reach the one behind, double-tap to look at it, long-press for more. Add opens the library."),
    PLACING("placing", "Drag the ghost to place, lift to drop. It stays armed for the next one: Done when you're finished."),
    MOVE("move", "Drag an arrow to move along an axis; drag the square or the thing itself to slide it on the floor."),
    ROTATE("rotate", "Drag the ring to turn it about its feet."),
    SCALE("scale", "Drag a box to make it bigger or smaller."),
    LOCKED("locked", "It's locked: unlock it (long-press it, or the Unlock line above) to move it.");

    companion object {
        fun of(placing: Boolean, selected: Boolean, locked: Boolean, tool: GizmoTool): PlaceTip = when {
            placing -> PLACING
            !selected -> BROWSE
            locked -> LOCKED
            tool == GizmoTool.MOVE -> MOVE
            tool == GizmoTool.ROTATE -> ROTATE
            else -> SCALE
        }
    }
}
