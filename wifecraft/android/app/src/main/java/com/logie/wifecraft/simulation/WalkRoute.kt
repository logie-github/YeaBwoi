package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.math.hypot

/**
 * The live scene's walk ([SceneSimulation]'s route): a straight segment when the floor between is clear, else one detour
 * point beside the middle, tried at 1, 2 and 3.5 yards each way, on the floor there ([ground]) and clear both ways
 * ([clear]). SceneView.glWalkRoute asks it with the engine's walk test; the desktop film asks it with the same test over a
 * pipe. Null: no way found (the floor is blocked, or the walk is under 0.1 or over 55 yards). Pure JVM.
 */
object WalkRoute {
    private val DETOURS = floatArrayOf(1f, -1f, 2f, -2f, 3.5f, -3.5f)

    fun route(from: Vec3, to: Vec3, clear: (Vec3, Vec3) -> Boolean, ground: (x: Float, y: Float, z: Float) -> Float): List<Vec3>? {
        if (clear(from, to)) return listOf(to)
        val delta = to - from
        val length = hypot(delta.x, delta.y)
        if (length < 0.1f || length > 55f) return null
        val side = Vec3(-delta.y / length, delta.x / length, 0f)
        val middle = from.lerp(to, 0.5f)
        for (offset in DETOURS) {
            val point = middle + side * offset
            val z = ground(point.x, point.y, point.z - 0.85f)
            if (z.isNaN()) continue
            val waypoint = point.copy(z = z)
            if (clear(from, waypoint) && clear(waypoint, to)) return listOf(waypoint, to)
        }
        return null
    }
}
