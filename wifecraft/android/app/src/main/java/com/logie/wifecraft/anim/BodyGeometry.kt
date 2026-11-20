package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsHash
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One moment of a body: where its main joints are, in the pair's (or clip's) frame, WoW axes (x forward, y left, z up,
 * the floor at z = 0), in BODY UNITS (1 = the body's standing pelvis height above the floor). [driven] are the joints the
 * clip actually moves (the rest sit at the reference rest pose and say nothing about the posture).
 */
class BodyFrame(val joints: Map<String, DoubleArray>, val driven: Set<String>) {
    operator fun get(name: String): DoubleArray? = joints[name]
}

/**
 * Samples a clip's body over time for [RequirementClassifier]: from a retargeted [Anim] (forward kinematics on the reference
 * WoW skeleton, night elf female 921844, the one the retarget aims at: [REF]) or straight from a Sims [SimsClip] (forward
 * kinematics on the clip's own local transforms, so no Sims skeleton is needed; the bone hierarchy is [SIMS_PARENTS], the
 * one the retarget tests use, from the community's rig documentation: not checked against a rig from the game).
 */
object BodyGeometry {
    /** Joints the classifier reads (WoW-side names). */
    val JOINTS = listOf(
        "Root", "Pelvis", "Spine1", "Spine2", "Chest", "Neck1", "Head",
        "L_Clavicle", "L_UpperArm", "L_Forearm", "L_Hand", "R_Clavicle", "R_UpperArm", "R_Forearm", "R_Hand",
        "L_Thigh", "L_Calf", "L_Foot", "L_Toe", "R_Thigh", "R_Calf", "R_Foot", "R_Toe",
    )

    /** The mapped WoW parent of each joint (as assets/sims_profile.json's "parent"). */
    val PARENT = mapOf(
        "Pelvis" to "Root", "Spine1" to "Root", "Spine2" to "Spine1", "Chest" to "Spine2", "Neck1" to "Chest", "Head" to "Neck1",
        "L_Clavicle" to "Chest", "L_UpperArm" to "L_Clavicle", "L_Forearm" to "L_UpperArm", "L_Hand" to "L_Forearm",
        "R_Clavicle" to "Chest", "R_UpperArm" to "R_Clavicle", "R_Forearm" to "R_UpperArm", "R_Hand" to "R_Forearm",
        "L_Thigh" to "Pelvis", "L_Calf" to "L_Thigh", "L_Foot" to "L_Calf", "L_Toe" to "L_Foot",
        "R_Thigh" to "Pelvis", "R_Calf" to "R_Thigh", "R_Foot" to "R_Calf", "R_Toe" to "R_Foot",
    )

    /** The reference skeleton's pivots (model units; WoW bones rest at identity rotation about these). */
    val REF: Map<String, DoubleArray> = mapOf(
        "Root" to d(-0.016, 0.0, 1.441), "Pelvis" to d(-0.014, 0.001, 1.434), "Spine1" to d(-0.016, 0.0, 1.473),
        "Spine2" to d(-0.021, 0.0, 1.537), "Chest" to d(-0.021, 0.0, 1.611), "Neck1" to d(-0.067, 0.0, 1.965),
        "Head" to d(-0.054, 0.0, 2.042),
        "L_Clavicle" to d(-0.043, 0.055, 1.877), "L_UpperArm" to d(-0.097, 0.170, 1.903), "L_Forearm" to d(-0.105, 0.461, 1.713),
        "L_Hand" to d(-0.044, 0.746, 1.525),
        "R_Clavicle" to d(-0.043, -0.055, 1.877), "R_UpperArm" to d(-0.097, -0.170, 1.903), "R_Forearm" to d(-0.105, -0.461, 1.713),
        "R_Hand" to d(-0.044, -0.746, 1.525),
        "L_Thigh" to d(-0.009, 0.099, 1.301), "L_Calf" to d(-0.035, 0.150, 0.703), "L_Foot" to d(-0.129, 0.199, 0.108),
        "L_Toe" to d(0.087, 0.200, 0.028),
        "R_Thigh" to d(-0.009, -0.099, 1.301), "R_Calf" to d(-0.035, -0.149, 0.703), "R_Foot" to d(-0.129, -0.199, 0.108),
        "R_Toe" to d(0.087, -0.198, 0.028),
    )

    /** The reference skeleton's standing pelvis (Root pivot) height: one body unit, in model units. */
    const val REF_UNIT = 1.441

    /** A standing pelvis height is about this many times thigh plus shin (the reference skeleton's ratio). */
    const val LEG_TO_UNIT = 1.196

    /** At most this many moments are sampled from a clip. */
    const val SAMPLES = 24

    /** Joints parents first. */
    private val ORDER: List<String> = run {
        val out = ArrayList<String>()
        fun visit(j: String) { if (j in out) return; PARENT[j]?.let { visit(it) }; out += j }
        JOINTS.forEach { visit(it) }
        out
    }

    /**
     * The body of a retargeted [anim], stood at [x], [y], [z] (model units) turned [yawDeg] about up (a pair's actor
     * offset; zero for a single clip), at up to [samples] moments over its length. With [flesh] measured on a body
     * ([FleshProfile.rest]: its own skeleton in the clips' stance), the clip turns that body's bones, as the engine plays
     * it on them; otherwise the reference skeleton's ([REF]).
     */
    fun frames(anim: Anim, x: Double = 0.0, y: Double = 0.0, z: Double = 0.0, yawDeg: Double = 0.0, samples: Int = SAMPLES,
               flesh: FleshProfile? = null): List<BodyFrame> {
        val rest = flesh?.rest ?: REF
        val rot = HashMap<String, BoneTrack>()
        var rootPos: BoneTrack? = null
        for (t in anim.tracks) {
            val per = if (t.channel == Channel.ROTATION) 4 else 3
            if (t.times.isEmpty() || t.values.size != t.times.size * per) continue
            if (t.channel == Channel.ROTATION) { if (t.bone in REF) rot.putIfAbsent(t.bone, t) }
            else if (t.bone == "Root" && rootPos == null) rootPos = t
        }
        if (rot.isEmpty() && rootPos == null) return emptyList()
        val driven = rot.keys.toMutableSet().also { if (rootPos != null) it += "Root" }
        val c = cos(Math.toRadians(yawDeg)); val s = sin(Math.toRadians(yawDeg))
        return times(anim.duration, (rot.values + listOfNotNull(rootPos)).maxOf { it.times.size }, samples).map { t ->
            val e = HashMap<String, DoubleArray>()
            val p = HashMap<String, DoubleArray>()
            for (j in ORDER) {
                val local = rot[j]?.let { sampleRot(it, t) } ?: Q.ID
                val parent = PARENT[j]
                if (parent == null) {
                    e[j] = local
                    val tr = rootPos?.let { sampleV3(it, t) } ?: DoubleArray(3)
                    p[j] = V.add(rest[j] ?: REF.getValue(j), tr)
                } else {
                    val pe = e.getValue(parent)
                    e[j] = Q.norm(Q.mul(pe, local))
                    p[j] = V.add(p.getValue(parent), Q.rotate(pe, V.sub(rest[j] ?: REF.getValue(j), rest[parent] ?: REF.getValue(parent))))
                }
            }
            BodyFrame(p.mapValues { (_, v) ->
                doubleArrayOf((x + v[0] * c - v[1] * s) / REF_UNIT, (y + v[0] * s + v[1] * c) / REF_UNIT, (z + v[2]) / REF_UNIT)
            }, driven)
        }
    }

    /** Sims rig bone -> its parent (the hierarchy the retarget tests use) and -> the WoW-side joint name. */
    val SIMS_PARENTS = mapOf(
        "b__ROOT_bind__" to "b__ROOT__", "b__Pelvis__" to "b__ROOT_bind__", "b__Spine0__" to "b__ROOT_bind__",
        "b__Spine1__" to "b__Spine0__", "b__Spine2__" to "b__Spine1__", "b__Neck__" to "b__Spine2__", "b__Head__" to "b__Neck__",
        "b__L_Clavicle__" to "b__Spine2__", "b__L_UpperArm__" to "b__L_Clavicle__", "b__L_Forearm__" to "b__L_UpperArm__",
        "b__L_Hand__" to "b__L_Forearm__",
        "b__R_Clavicle__" to "b__Spine2__", "b__R_UpperArm__" to "b__R_Clavicle__", "b__R_Forearm__" to "b__R_UpperArm__",
        "b__R_Hand__" to "b__R_Forearm__",
        "b__L_Thigh__" to "b__Pelvis__", "b__L_Calf__" to "b__L_Thigh__", "b__L_Foot__" to "b__L_Calf__", "b__L_Toe__" to "b__L_Foot__",
        "b__R_Thigh__" to "b__Pelvis__", "b__R_Calf__" to "b__R_Thigh__", "b__R_Foot__" to "b__R_Calf__", "b__R_Toe__" to "b__R_Foot__",
    )

    private val SIMS_TO_WOW = mapOf(
        "b__ROOT_bind__" to "Root", "b__Pelvis__" to "Pelvis", "b__Spine0__" to "Spine1", "b__Spine1__" to "Spine2",
        "b__Spine2__" to "Chest", "b__Neck__" to "Neck1", "b__Head__" to "Head",
        "b__L_Clavicle__" to "L_Clavicle", "b__L_UpperArm__" to "L_UpperArm", "b__L_Forearm__" to "L_Forearm", "b__L_Hand__" to "L_Hand",
        "b__R_Clavicle__" to "R_Clavicle", "b__R_UpperArm__" to "R_UpperArm", "b__R_Forearm__" to "R_Forearm", "b__R_Hand__" to "R_Hand",
        "b__L_Thigh__" to "L_Thigh", "b__L_Calf__" to "L_Calf", "b__L_Foot__" to "L_Foot", "b__L_Toe__" to "L_Toe",
        "b__R_Thigh__" to "R_Thigh", "b__R_Calf__" to "R_Calf", "b__R_Foot__" to "R_Foot", "b__R_Toe__" to "R_Toe",
    )

    /**
     * The body of a Sims [clip] from its own keys: every bone on the chain to a joint needs a translation key (its local
     * offset) and a rotation key, as whole-body clips have (a pose pack's: 96 bones, both channels); a clip that lacks one
     * gives nothing. Sims axes (x left, y up, z forward, metres) are taken into WoW's; one body unit is
     * [LEG_TO_UNIT] times the first moment's thigh plus shin.
     */
    fun frames(clip: SimsClip, samples: Int = SAMPLES): List<BodyFrame> {
        val bones = (SIMS_PARENTS.keys + SIMS_PARENTS.values).toSet()
        val byHash = bones.associateBy { SimsHash.fnv32(it) }
        val rot = HashMap<String, com.logie.wifecraft.sims.SimsTrack>()
        val pos = HashMap<String, com.logie.wifecraft.sims.SimsTrack>()
        for (t in clip.tracks) {
            val n = byHash[t.boneHash] ?: continue
            val per = if (t.channel == Channel.ROTATION) 4 else 3
            if (t.times.isEmpty() || t.values.size < t.times.size * per) continue
            if (t.channel == Channel.ROTATION) rot.putIfAbsent(n, t) else pos.putIfAbsent(n, t)
        }
        // the floor root may carry no keys (identity at the origin); every other bone of the chain needs both
        val chain = SIMS_PARENTS.keys
        if (chain.any { it !in pos || it !in rot }) return emptyList()
        val order = ArrayList<String>()
        fun visit(b: String) { if (b in order) return; SIMS_PARENTS[b]?.let { visit(it) }; order += b }
        chain.forEach { visit(it) }
        val keys = (rot.values + pos.values).maxOf { it.times.size }
        var unit = 0.0
        return times(clip.duration, keys, samples).mapNotNull { t ->
            val g = HashMap<String, DoubleArray>()
            val w = HashMap<String, DoubleArray>()
            for (b in order) {
                val lr = rot[b]?.let { sampleRot(it, t) } ?: Q.ID
                val lp = pos[b]?.let { sampleV3(it, t) } ?: DoubleArray(3)
                val parent = SIMS_PARENTS[b]
                if (parent == null) { g[b] = lr; w[b] = lp } else {
                    g[b] = Q.norm(Q.mul(g.getValue(parent), lr))
                    w[b] = V.add(w.getValue(parent), Q.rotate(g.getValue(parent), lp))
                }
            }
            if (unit == 0.0) {
                fun len(a: String, b: String) = sqrt(V.dot(V.sub(w.getValue(a), w.getValue(b)), V.sub(w.getValue(a), w.getValue(b))))
                val leg = (len("b__L_Thigh__", "b__L_Calf__") + len("b__L_Calf__", "b__L_Foot__") +
                    len("b__R_Thigh__", "b__R_Calf__") + len("b__R_Calf__", "b__R_Foot__")) / 2
                if (leg < 1e-4 || !leg.isFinite()) return@mapNotNull null
                unit = leg * LEG_TO_UNIT
            }
            val out = HashMap<String, DoubleArray>()
            for ((sims, wow) in SIMS_TO_WOW) {
                val v = w.getValue(sims)
                out[wow] = doubleArrayOf(v[2] / unit, v[0] / unit, v[1] / unit)
            }
            BodyFrame(out, out.keys)
        }
    }

    /** Up to [samples] moments over [duration] (a clip with one key, or no length: one moment). */
    private fun times(duration: Float, keys: Int, samples: Int): List<Float> {
        if (!(duration > 0f) || !duration.isFinite() || keys <= 1) return listOf(0f)
        val n = minOf(samples, keys).coerceAtLeast(2)
        return List(n) { duration * it / (n - 1) }
    }

    private fun bracket(times: FloatArray, t: Float): Triple<Int, Int, Double> {
        if (times.size == 1 || t <= times[0]) return Triple(0, 0, 0.0)
        val last = times.size - 1
        if (t >= times[last]) return Triple(last, last, 0.0)
        var lo = 0; var hi = last
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (times[mid] <= t) lo = mid else hi = mid }
        val span = times[hi] - times[lo]
        return Triple(lo, hi, if (span > 0) ((t - times[lo]) / span).toDouble() else 0.0)
    }

    private fun sampleRot(times: FloatArray, values: FloatArray, t: Float): DoubleArray {
        val (a, b, f) = bracket(times, t)
        return Q.nlerp(DoubleArray(4) { values[a * 4 + it].toDouble() }, DoubleArray(4) { values[b * 4 + it].toDouble() }, f)
    }

    private fun sampleV3(times: FloatArray, values: FloatArray, t: Float): DoubleArray {
        val (a, b, f) = bracket(times, t)
        return DoubleArray(3) { values[a * 3 + it] + (values[b * 3 + it] - values[a * 3 + it]) * f }
    }

    private fun sampleRot(t: BoneTrack, at: Float) = sampleRot(t.times, t.values, at)
    private fun sampleV3(t: BoneTrack, at: Float) = sampleV3(t.times, t.values, at)
    private fun sampleRot(t: com.logie.wifecraft.sims.SimsTrack, at: Float) = sampleRot(t.times, t.values, at)
    private fun sampleV3(t: com.logie.wifecraft.sims.SimsTrack, at: Float) = sampleV3(t.times, t.values, at)

    private fun d(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
}
