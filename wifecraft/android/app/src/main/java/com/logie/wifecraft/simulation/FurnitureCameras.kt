package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The named views of furniture in use. */
enum class FurnitureView(val label: String) {
    FOOT("Foot"), HEAD("Head"), LEFT("Left side"), RIGHT("Right side"), TOP("From above"),
    FRONT("Front"), BEHIND("Over the shoulder"), THREE_QUARTER("Three-quarter");
}

/**
 * One view of the people on a piece of furniture: [eye] looking at [at] with a vertical field of view [fov] (degrees), and the
 * same as SceneCamera's orbit takes it: [yaw] and [pitch] (radians) and [distance] from [at] (eye = at + (cos pitch cos yaw,
 * cos pitch sin yaw, sin pitch) * distance). [moved]: the eye was brought nearer (or higher) to stay inside the room.
 */
data class FurniturePreset(
    val view: FurnitureView, val eye: Vec3, val at: Vec3, val fov: Float,
    val yaw: Float, val pitch: Float, val distance: Float, val moved: Boolean,
) {
    val pose: CameraPose get() = CameraPose(eye, at, fov)
}

/** The room the camera must stay in: an axis-aligned box ([min], [max]) less [margin] from its walls, floor and ceiling. */
data class RoomBounds(val min: Vec3, val max: Vec3, val margin: Float = 0.25f) {
    fun inside(p: Vec3) = p.x >= min.x + margin && p.x <= max.x - margin && p.y >= min.y + margin && p.y <= max.y - margin &&
        p.z >= min.z + margin && p.z <= max.z - margin
}

/** Whether the camera may stand at [eye] seeing [target] (no wall in between, not inside one): the scene's own test. */
fun interface CameraClearance { fun clear(eye: Vec3, target: Vec3): Boolean }

/**
 * Preset views of furniture in use, framed on the people on it: a bed from its foot, its head, each side and above; a couch
 * or chair from the front, each side, over a sitter's shoulder and three-quarter; a table or counter from the front and each
 * side. Each looks at the middle of the people (the furniture's surface when nobody is given), as far as the people and the
 * furniture fit the field of view, kept inside [RoomBounds] and clear of walls ([CameraClearance]): an eye that would be
 * outside comes nearer (the field of view widens to keep them in), and a view that can't be had is left out. Pure.
 */
object FurnitureCameras {
    const val FOV = 50f
    const val MAX_FOV = 90f
    /** SceneCamera's orbit limits (its MIN_DISTANCE and MAX_DISTANCE). */
    const val MIN_DISTANCE = 1.2f
    const val MAX_DISTANCE = 8f
    /** Room the framing leaves around the people and furniture (world units). */
    const val PADDING = 0.25f

    /** The views each kind offers, with their direction in the furniture's frame (radians from its front) and pitch. */
    fun views(kind: FurnitureKind): List<Triple<FurnitureView, Float, Float>> {
        val q = (PI / 2).toFloat(); val h = PI.toFloat()
        return when {
            kind.bed -> listOf(Triple(FurnitureView.FOOT, 0f, 0.38f), Triple(FurnitureView.HEAD, h, 0.62f),
                Triple(FurnitureView.LEFT, q, 0.35f), Triple(FurnitureView.RIGHT, -q, 0.35f), Triple(FurnitureView.TOP, 0f, 1.45f))
            kind == FurnitureKind.COUCH || kind == FurnitureKind.CHAIR -> listOf(Triple(FurnitureView.FRONT, 0f, 0.22f),
                Triple(FurnitureView.LEFT, q, 0.25f), Triple(FurnitureView.RIGHT, -q, 0.25f),
                Triple(FurnitureView.BEHIND, h - 0.45f, 0.5f), Triple(FurnitureView.THREE_QUARTER, q / 2, 0.3f))
            kind == FurnitureKind.TABLE || kind == FurnitureKind.COUNTER -> listOf(Triple(FurnitureView.FRONT, 0f, 0.3f),
                Triple(FurnitureView.LEFT, q, 0.3f), Triple(FurnitureView.RIGHT, -q, 0.3f), Triple(FurnitureView.THREE_QUARTER, q / 2, 0.35f))
            else -> listOf(Triple(FurnitureView.FRONT, 0f, 0.35f), Triple(FurnitureView.LEFT, q, 0.35f),
                Triple(FurnitureView.RIGHT, -q, 0.35f), Triple(FurnitureView.TOP, 0f, 1.45f))
        }
    }

    /**
     * The views of [f] with people at [people] (their pelvises and heads, world; empty: the furniture alone), at a field of
     * view of [fov] degrees, kept in [room] (null: anywhere) and clear by [clearance] (null: always clear).
     */
    fun presets(
        f: Furniture, people: List<Vec3>, room: RoomBounds? = null, clearance: CameraClearance? = null, fov: Float = FOV,
    ): List<FurniturePreset> {
        val (fmin, fmax) = f.bounds()
        // what is framed: the people (padded by a body's width), the furniture's surface; looked at: the people's middle
        val pts = ArrayList<Vec3>()
        for (p in people) {
            pts += Vec3(p.x - PADDING, p.y - PADDING, p.z - PADDING); pts += Vec3(p.x + PADDING, p.y + PADDING, p.z + PADDING)
        }
        val surfaceZ = f.z + f.spec.surface
        pts += Vec3(fmin.x, fmin.y, f.z); pts += Vec3(fmax.x, fmax.y, max(surfaceZ, f.z + 0.3f))
        val lo = Vec3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z })
        val hi = Vec3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z })
        val at = if (people.isNotEmpty()) Vec3(people.map { it.x }.average().toFloat(), people.map { it.y }.average().toFloat(),
            people.map { it.z }.average().toFloat()) else f.toWorld(0f, 0f, max(f.spec.surface, 0.5f))
        // the radius of a sphere round [at] holding all of it
        val radius = listOf(lo.x, hi.x).flatMap { x -> listOf(lo.y, hi.y).flatMap { y -> listOf(lo.z, hi.z).map { z -> Vec3(x, y, z) } } }
            .maxOf { dist(it, at) }.coerceAtLeast(0.4f)
        val half = Math.toRadians(fov / 2.0)
        val want = (radius / sin(half)).toFloat().coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        val out = ArrayList<FurniturePreset>()
        for ((view, dir, pitch0) in views(f.kind)) {
            val yaw = wrap(f.yaw + dir)
            preset(view, at, yaw, pitch0, want, radius, fov, room, clearance)?.let { out += it }
        }
        return out
    }

    /** The view along [yaw], [pitch] at [want] from [at], brought nearer (then higher) until it is inside and clear. */
    private fun preset(view: FurnitureView, at: Vec3, yaw: Float, pitch0: Float, want: Float, radius: Float, fov: Float,
                       room: RoomBounds?, clearance: CameraClearance?): FurniturePreset? {
        val pitches = listOf(pitch0, min(pitch0 + 0.3f, 1.5f), min(pitch0 + 0.6f, 1.5f)).distinct()
        for ((pi, pitch) in pitches.withIndex()) {
            var d = want
            while (d >= MIN_DISTANCE - 1e-4f) {
                val eye = eyeOf(at, yaw, pitch, d)
                if ((room == null || room.inside(eye)) && (clearance == null || clearance.clear(eye, at))) {
                    // nearer than wanted: widen the view so the same radius still fits
                    val needed = Math.toDegrees(2.0 * asin(min(1.0, (radius / d).toDouble()))).toFloat()
                    val f = max(fov, needed).coerceAtMost(MAX_FOV)
                    return FurniturePreset(view, eye, at, f, yaw, pitch, d, moved = d < want - 1e-4f || pi > 0)
                }
                d -= 0.1f
            }
        }
        return null
    }

    /** SceneCamera's orbit eye: [at] + (cos pitch cos yaw, cos pitch sin yaw, sin pitch) * [d]. */
    fun eyeOf(at: Vec3, yaw: Float, pitch: Float, d: Float): Vec3 {
        val cp = cos(pitch)
        return Vec3(at.x + cp * cos(yaw) * d, at.y + cp * sin(yaw) * d, at.z + sin(pitch) * d)
    }

    /** The orbit's yaw, pitch and distance of a pose (to hand an arbitrary [CameraPose] to an orbit). */
    fun orbitOf(p: CameraPose): Triple<Float, Float, Float> {
        val dx = p.eye.x - p.at.x; val dy = p.eye.y - p.at.y; val dz = p.eye.z - p.at.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        return Triple(atan2(dy, dx), if (d > 0f) asin((dz / d).coerceIn(-1f, 1f)) else 0f, d)
    }

    private fun dist(a: Vec3, b: Vec3) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    private fun wrap(a: Float): Float {
        var x = a % (2 * PI).toFloat()
        if (x > PI) x -= (2 * PI).toFloat()
        if (x < -PI) x += (2 * PI).toFloat()
        return x
    }
}
