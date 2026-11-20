package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic retargeted clips for the requirement tests: a body on the reference WoW skeleton given by world rotations of a
 * few bones (the rest inherit their parent's), the pelvis at a height in body units. Turns about y: -90 degrees lays the
 * torso on its back with the head toward -x; +90 lays it on its front, or tips a thigh or shin forward or back.
 */
object PoseFixtures {
    fun rotY(deg: Double): DoubleArray { val h = Math.toRadians(deg) / 2; return doubleArrayOf(0.0, sin(h), 0.0, cos(h)) }
    fun rotZ(deg: Double): DoubleArray { val h = Math.toRadians(deg) / 2; return doubleArrayOf(0.0, 0.0, sin(h), cos(h)) }
    fun rotX(deg: Double): DoubleArray { val h = Math.toRadians(deg) / 2; return doubleArrayOf(sin(h), 0.0, 0.0, cos(h)) }

    /**
     * A clip holding one pose over [seconds] (two keys, so it is sampled as a held loop): [world] the world rotation of
     * some bones, the pelvis (Root pivot) at [hip] body units over the floor, moved [dx], [dy] body units.
     */
    fun pose(name: String, world: Map<String, DoubleArray>, hip: Double, dx: Double = 0.0, dy: Double = 0.0,
             seconds: Float = 2f, legs: Boolean = true): Anim {
        val e = HashMap<String, DoubleArray>()
        val order = BodyGeometry.JOINTS
        fun worldOf(j: String): DoubleArray = e.getOrPut(j) {
            world[j] ?: BodyGeometry.PARENT[j]?.let { worldOf(it) } ?: Q.ID
        }
        val tracks = ArrayList<BoneTrack>()
        val times = floatArrayOf(0f, seconds)
        for (j in order) {
            if (!legs && (j.contains("Thigh") || j.contains("Calf") || j.contains("Foot") || j.contains("Toe"))) continue
            val parent = BodyGeometry.PARENT[j]
            val local = Q.norm(Q.mul(Q.inv(parent?.let { worldOf(it) } ?: Q.ID), worldOf(j)))
            val v = FloatArray(8) { local[it % 4].toFloat() }
            tracks += BoneTrack(j, Channel.ROTATION, times, v)
        }
        val u = BodyGeometry.REF_UNIT
        val p = floatArrayOf((dx * u).toFloat(), (dy * u).toFloat(), ((hip - 1.0) * u).toFloat())
        tracks += BoneTrack("Root", Channel.POSITION, times, p + p)
        return Anim(name, name, "", seconds, true, tracks, "test:$name", 0L, sourceName = name)
    }

    val standing get() = pose("idle", emptyMap(), 1.0)

    /** On a seat [seatM] metres high: thighs forward, shins down. */
    fun sitting(seatM: Double = 0.47, name: String = "pose_a") = pose(name,
        mapOf("L_Thigh" to rotY(-90.0), "R_Thigh" to rotY(-90.0), "L_Calf" to Q.ID, "R_Calf" to Q.ID),
        seatM / 0.95 + 0.19)

    /** On the back, head toward -x, the body's underside [surfaceM] metres up. */
    fun lyingBack(surfaceM: Double, name: String = "loop", dx: Double = 0.0, dy: Double = 0.0) =
        pose(name, mapOf("Root" to rotY(-90.0)), surfaceM / 0.95 + 0.11 + 0.02, dx, dy)

    /** On the front, head toward +x. */
    fun lyingFront(surfaceM: Double, name: String = "loop", dx: Double = 0.0, dy: Double = 0.0) =
        pose(name, mapOf("Root" to rotY(90.0)), surfaceM / 0.95 + 0.11 + 0.02, dx, dy)

    /** Upright on the knees (thighs down, shins back) with the knees [surfaceM] metres up. */
    fun kneeling(surfaceM: Double = 0.0, name: String = "loop", dx: Double = 0.0, facingDeg: Double = 0.0) = pose(name,
        mapOf("Root" to rotZ(facingDeg), "L_Calf" to Q.mul(rotZ(facingDeg), rotY(90.0)), "R_Calf" to Q.mul(rotZ(facingDeg), rotY(90.0))),
        surfaceM / 0.95 + 0.567, dx)

    /** On hands and knees: torso forward and face down, thighs down, shins back. */
    fun allFours(name: String = "loop") = pose(name,
        mapOf("Root" to rotY(90.0), "Pelvis" to Q.ID, "L_Thigh" to Q.ID, "R_Thigh" to Q.ID,
            "L_Calf" to rotY(90.0), "R_Calf" to rotY(90.0), "L_UpperArm" to Q.ID, "R_UpperArm" to Q.ID), 0.47)

    /** Standing, bent at the hips over a top, the arms reaching forward and down onto it. */
    fun leaning(name: String = "loop") = pose(name,
        mapOf("Spine1" to rotY(90.0), "L_UpperArm" to rotZ(-90.0), "R_UpperArm" to rotZ(90.0)), 1.0)

}
