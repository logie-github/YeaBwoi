package com.logie.wifecraft.story

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun lerp(o: Vec3, t: Float) = Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
}

/** Where the camera is, what it looks at, and its vertical field of view in degrees. */
data class CameraPose(val eye: Vec3, val at: Vec3, val fov: Float) {
    fun lerp(o: CameraPose, t: Float) = CameraPose(eye.lerp(o.eye, t), at.lerp(o.at, t), fov + (o.fov - fov) * t)
}

/**
 * The camera's framings, in the game's coordinates (z up; a model faces +x turned by its facing).
 * The text box covers the screen's lower third, so a speaker's face is framed above the middle.
 */
object Shots {
    /** A speaker from the waist up, seen from a little to their right. */
    fun bust(pos: Vec3, facing: Float, height: Float): CameraPose {
        // From the thighs to just above the head, at a 32° field of view.
        val h = height.coerceIn(1.0f, 3.5f)
        val d = max(1.6f, h * 1.08f)
        val fx = cos(facing); val fy = sin(facing)
        val rx = sin(facing); val ry = -cos(facing)
        val side = 0.12f * d
        val eye = Vec3(pos.x + fx * d + rx * side, pos.y + fy * d + ry * side, pos.z + h * 0.92f)
        val at = Vec3(pos.x, pos.y, pos.z + h * 0.80f)
        return CameraPose(eye, at, 32f)
    }

    /** Two people side by side, filmed across the line between them from [side] (+1 or -1). */
    fun two(a: Vec3, b: Vec3, side: Int, height: Float = 2.2f): CameraPose {
        val mx = (a.x + b.x) / 2; val my = (a.y + b.y) / 2
        val dx = b.x - a.x; val dy = b.y - a.y
        val s = max(0.01f, hypot(dx, dy))
        val px = -dy / s * side; val py = dx / s * side
        val fov = 40f
        val d = max(3.0f, (s / 2 + 1.2f) / tan(Math.toRadians(fov * 1.6 / 2).toFloat()))
        val z = (a.z + b.z) / 2
        return CameraPose(Vec3(mx + px * d, my + py * d, z + height * 0.80f), Vec3(mx, my, z + height * 0.62f), fov)
    }

    /** The facing (radians) that turns someone at [from] toward [to]. */
    fun facing(from: Vec3, to: Vec3): Float = atan2(to.y - from.y, to.x - from.x)

    /** Eases a move in and out. */
    fun ease(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3 - 2 * c)
    }
}
