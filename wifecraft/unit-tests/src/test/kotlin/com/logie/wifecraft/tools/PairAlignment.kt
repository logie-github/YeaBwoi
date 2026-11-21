package com.logie.wifecraft.tools

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.BodyFrame
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.anim.FleshPart
import com.logie.wifecraft.anim.FleshProfile
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.anim.Posture
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsRig
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The geometry of the pair alignment audit ([PairAlignmentAudit]) and its regression tests ([PairAlignmentTest]), in world
 * yards: two bodies of a pair placed as the engine places them, and the same two clips as their author made them.
 *
 * **As played** ([played]): a converted clip turned on a body's own skeleton ([Body.flesh]'s rest, the reference's when not
 * measured), the Root's movement across the floor taken by [HumanScale.PAIR] (times a gnome's [Body.pairScale]) as the engine
 * takes it, up and down and every bone below the Root by the body's own standing pelvis ([Body.pelvis], yards), then stood at
 * its slot (yards across, the slot's z a lift into the Root track in reference units) turned by its yaw, plus a lift (yards).
 *
 * **As authored** ([authored]): the Sims clip's own bones by forward kinematics on the rig (each bone's keys, its bind where
 * the clip has none), in Sims metres, taken into WoW axes and placed by the tuning's offset and facing, then scaled by
 * [yardsPerMetre] (the skeleton's model units per metre times [HumanScale.PAIR]: the one scale the retarget gives the pair's
 * root movement), so both bodies keep the author's relative transform; their flesh is the generic body's at [AUTHORED_UNIT].
 */
object PairAlignment {
    /** Yards per body unit of the generic body (the human man's and woman's mean standing pelvis). */
    val AUTHORED_UNIT = BodyGeometry.REF_UNIT * HumanScale.PAIR
    const val CM_PER_YARD = 91.44

    /** A body: its flesh (and skeleton), its standing pelvis in yards as drawn, and its pair scale (a gnome's size; else 1). */
    class Body(val name: String, val flesh: FleshProfile, val pelvis: Double, val pairScale: Double = 1.0) {
        override fun toString() = name
        companion object {
            val GENERIC = Body("generic", FleshProfile.GENERIC, AUTHORED_UNIT)
        }
    }

    /** One moment of a placed body: its joints (yards, the pair's frame) and its flesh's unit (yards per body unit). */
    class Pose(val joints: Map<String, DoubleArray>, val flesh: FleshProfile, val unit: Double) {
        operator fun get(j: String) = joints[j]
        /** The same joints in this body's units, for [FleshProfile]'s frames. */
        val frame: BodyFrame by lazy { BodyFrame(joints.mapValues { (_, v) -> DoubleArray(3) { v[it] / unit } }, joints.keys) }
    }

    /** Moments at about [hz] per second over [duration] (at least 2, at most [maxN]; a still clip: 1). */
    fun times(duration: Float, hz: Double = 10.0, maxN: Int = 160): List<Float> {
        if (!(duration > 0f) || !duration.isFinite()) return listOf(0f)
        val n = (duration * hz).toInt().coerceIn(1, maxN - 1)
        return List(n + 1) { duration * it / n }
    }

    // ---- as played ----

    /**
     * [anim] on [body] at [times], stood at [x], [y] (yards) with [zRef] (reference units, into the Root track) turned [yawDeg],
     * raised [lift] yards.
     */
    fun played(anim: Anim, body: Body, times: List<Float>, x: Double, y: Double, zRef: Double, yawDeg: Double, lift: Double = 0.0): List<Pose> {
        val rest = body.flesh.rest ?: BodyGeometry.REF
        val rot = HashMap<String, BoneTrack>()
        var rootPos: BoneTrack? = null
        for (t in anim.tracks) {
            val per = if (t.channel == Channel.ROTATION) 4 else 3
            if (t.times.isEmpty() || t.values.size != t.times.size * per) continue
            if (t.channel == Channel.ROTATION) { if (t.bone in BodyGeometry.REF) rot.putIfAbsent(t.bone, t) }
            else if (t.bone == "Root" && rootPos == null) rootPos = t
        }
        val c = cos(Math.toRadians(yawDeg)); val s = sin(Math.toRadians(yawDeg))
        val ref = BodyGeometry.REF_UNIT
        val across = HumanScale.PAIR * body.pairScale
        val unit = body.pelvis
        return times.map { t ->
            val e = HashMap<String, DoubleArray>()
            val p = HashMap<String, DoubleArray>()
            for (j in ORDER) {
                val local = rot[j]?.let { q(it, t) } ?: ID
                val parent = BodyGeometry.PARENT[j]
                val rj = rest[j] ?: BodyGeometry.REF.getValue(j)
                if (parent == null) {
                    e[j] = local
                    val tr = rootPos?.let { v3(it, t) } ?: DoubleArray(3)
                    p[j] = doubleArrayOf(rj[0] + tr[0], rj[1] + tr[1], rj[2] + tr[2] + zRef)
                } else {
                    val pe = e.getValue(parent)
                    e[j] = qnorm(qmul(pe, local))
                    val rp = rest[parent] ?: BodyGeometry.REF.getValue(parent)
                    p[j] = add(p.getValue(parent), rotate(pe, doubleArrayOf(rj[0] - rp[0], rj[1] - rp[1], rj[2] - rp[2])))
                }
            }
            val root = p.getValue("Root")
            // the Root across the floor by the pair's scale; up and down and every bone below it by the body's own pelvis
            val rw = doubleArrayOf(root[0] * across, root[1] * across, root[2] / ref * unit)
            Pose(p.mapValues { (_, v) ->
                val lx = rw[0] + (v[0] - root[0]) / ref * unit
                val ly = rw[1] + (v[1] - root[1]) / ref * unit
                val lz = rw[2] + (v[2] - root[2]) / ref * unit
                doubleArrayOf(x + lx * c - ly * s, y + lx * s + ly * c, lz + lift)
            }, body.flesh, unit)
        }
    }

    // ---- as authored ----

    private val SIMS_TO_WOW = mapOf(
        "b__ROOT_bind__" to "Root", "b__Pelvis__" to "Pelvis", "b__Spine0__" to "Spine1", "b__Spine1__" to "Spine2",
        "b__Spine2__" to "Chest", "b__Neck__" to "Neck1", "b__Head__" to "Head",
        "b__L_Clavicle__" to "L_Clavicle", "b__L_UpperArm__" to "L_UpperArm", "b__L_Forearm__" to "L_Forearm", "b__L_Hand__" to "L_Hand",
        "b__R_Clavicle__" to "R_Clavicle", "b__R_UpperArm__" to "R_UpperArm", "b__R_Forearm__" to "R_Forearm", "b__R_Hand__" to "R_Hand",
        "b__L_Thigh__" to "L_Thigh", "b__L_Calf__" to "L_Calf", "b__L_Foot__" to "L_Foot", "b__L_Toe__" to "L_Toe",
        "b__R_Thigh__" to "R_Thigh", "b__R_Calf__" to "R_Calf", "b__R_Foot__" to "R_Foot", "b__R_Toe__" to "R_Toe",
    )

    /**
     * The Sims [clip] on [rig] at [times] (WoW axes), placed by its tuning offset ([ox], [oy], [oz] Sims metres, x forward,
     * y left, z up) and [facingRad], scaled to yards by [yardsPerMetre]; null when the rig lacks a joint.
     */
    fun authored(clip: SimsClip, rig: SimsRig, times: List<Float>, ox: Double, oy: Double, oz: Double, facingRad: Double,
                 yardsPerMetre: Double): List<Pose>? {
        val n = rig.bones.size
        val idx = HashMap<Int, Int>()
        rig.bones.forEachIndexed { i, b -> idx[b.hash] = i; idx.putIfAbsent(com.logie.wifecraft.sims.SimsHash.fnv32(b.name), i) }
        val rot = HashMap<Int, com.logie.wifecraft.sims.SimsTrack>()
        val pos = HashMap<Int, com.logie.wifecraft.sims.SimsTrack>()
        for (t in clip.tracks) {
            val i = rig.byHash(t.boneHash)?.let { b -> rig.bones.indexOf(b) } ?: continue
            val per = if (t.channel == Channel.ROTATION) 4 else 3
            if (t.times.isEmpty() || t.values.size < t.times.size * per) continue
            if (t.channel == Channel.ROTATION) rot.putIfAbsent(i, t) else pos.putIfAbsent(i, t)
        }
        val wanted = SIMS_TO_WOW.mapNotNull { (s, w) -> rig.bones.indexOfFirst { it.name.equals(s, true) }.takeIf { it >= 0 }?.let { it to w } }
        if (wanted.size < SIMS_TO_WOW.size) return null
        val order = ArrayList<Int>(n)
        val state = IntArray(n)
        fun visit(i: Int) { if (state[i] != 0) return; state[i] = 1; rig.bones[i].parent.takeIf { it >= 0 }?.let { visit(it) }; order += i }
        for (i in 0 until n) visit(i)
        val c = cos(facingRad); val s = sin(facingRad)
        val unit = AUTHORED_UNIT
        return times.map { t ->
            val g = arrayOfNulls<DoubleArray>(n); val w = arrayOfNulls<DoubleArray>(n)
            for (i in order) {
                val b = rig.bones[i]
                val lr = rot[i]?.let { q(it.times, it.values, t) } ?: qnorm(DoubleArray(4) { b.bindRotation[it].toDouble() })
                val lp = pos[i]?.let { v3(it.times, it.values, t) } ?: DoubleArray(3) { b.bindPosition[it].toDouble() }
                if (b.parent < 0) { g[i] = lr; w[i] = lp } else {
                    g[i] = qnorm(qmul(g[b.parent]!!, lr)); w[i] = add(w[b.parent]!!, rotate(g[b.parent]!!, lp))
                }
            }
            // the Root as the retarget makes it: a root-locked clip's pelvis movement folded in (Retargeter.PELVIS)
            val root = wanted.first { it.second == "Root" }.first
            val pelvis = wanted.first { it.second == "Pelvis" }.first
            val pb = rig.bones[pelvis]
            val fold = if (pb.parent == root) sub(w[pelvis]!!, rotate(g[root]!!, DoubleArray(3) { pb.bindPosition[it].toDouble() }))
                else w[root]!!
            Pose(wanted.associate { (i, name) ->
                val v = if (i == root) fold else w[i]!!
                // Sims (x left, y up, z forward) to WoW (x forward, y left, z up), turned by the facing, offset, in yards
                val fx = v[2]; val fy = v[0]; val fz = v[1]
                name to doubleArrayOf((ox + fx * c - fy * s) * yardsPerMetre, (oy + fx * s + fy * c) * yardsPerMetre, (oz + fz) * yardsPerMetre)
            }, FleshProfile.GENERIC, unit)
        }
    }

    // ---- measures ----

    /** The parts read for the bodies' contact: the trunk, the head, the arms and the legs down to the calves. */
    val CONTACT_PARTS = FleshPart.TRUNK + FleshPart.HEAD + FleshPart.ARMS + FleshPart.THIGHS + listOf(FleshPart.L_CALF, FleshPart.R_CALF)
    /** Parts whose meeting is the stage's contact at the hips. */
    val HIP_PARTS = listOf(FleshPart.PELVIS, FleshPart.L_THIGH, FleshPart.R_THIGH)

    /**
     * The surface distance between [a] and [b] (yards; negative: that deep in each other): the least, over [partsA] x [partsB],
     * of the axes' distance less each part's flesh toward the other.
     */
    fun surface(a: Pose, b: Pose, partsA: Collection<FleshPart> = FleshPart.TRUNK + FleshPart.ARMS + FleshPart.THIGHS,
                partsB: Collection<FleshPart> = partsA, skip: (FleshPart, FleshPart) -> Boolean = { _, _ -> false }): Double {
        var best = Double.MAX_VALUE
        for (pa in partsA) {
            val fa = a.flesh.frame(pa, a.frame) ?: continue
            for (pb in partsB) {
                if (skip(pa, pb)) continue
                val fb = b.flesh.frame(pb, b.frame) ?: continue
                // the axes in yards
                val a0 = sc(fa.a0, a.unit); val a1 = sc(fa.a1, a.unit); val b0 = sc(fb.a0, b.unit); val b1 = sc(fb.a1, b.unit)
                val (d, ta, tb) = com.logie.wifecraft.simulation.PairSpacing.nearestAt(a0, a1, b0, b1)
                val pA = lerp(a0, a1, ta); val pB = lerp(b0, b1, tb)
                val sa = a.flesh.segment(pa); val sb = b.flesh.segment(pb)
                val reach = if (d < 1e-9) sa.most * a.unit + sb.most * b.unit else {
                    val dir = DoubleArray(3) { (pB[it] - pA[it]) / d }
                    FleshProfile.reach(sa, fa, dir, ta <= 1e-6, ta >= 1 - 1e-6) * a.unit +
                        FleshProfile.reach(sb, fb, DoubleArray(3) { -dir[it] }, tb <= 1e-6, tb >= 1 - 1e-6) * b.unit
                }
                best = min(best, d - reach)
            }
        }
        return best
    }

    /** Parts that may meet (skipped by the penetration measure): limb on limb but thigh on thigh, and the head on anything. */
    val mayTouch: (FleshPart, FleshPart) -> Boolean = { a, b ->
        val limbA = a !in FleshPart.TRUNK; val limbB = b !in FleshPart.TRUNK
        (limbA && limbB && !(a in FleshPart.THIGHS && b in FleshPart.THIGHS)) || a == FleshPart.HEAD || b == FleshPart.HEAD
    }

    /** The lowest flesh of [p] over the floor (yards). */
    fun lowest(p: Pose): Double = (p.flesh.lowest(p.frame, CONTACT_PARTS + listOf(FleshPart.L_FOOT, FleshPart.R_FOOT)) ?: 0.0) * p.unit

    /**
     * Which way [p] faces (radians, the pair's frame): square to its hips (the right hip joint to the left one, turned a
     * quarter right), the same way for a body standing, sitting, kneeling, on all fours or lying on its back or front; a body
     * on its side (the hips one over the other) by its trunk's way.
     */
    fun yaw(p: Pose): Double {
        val l = p["L_Thigh"]!!; val rt = p["R_Thigh"]!!
        val sx = l[0] - rt[0]; val sy = l[1] - rt[1]; val sz = l[2] - rt[2]
        if (hypot(sx, sy) > 0.5 * abs(sz)) return atan2(-sx, sy)
        val r = p["Root"]!!; val n = p["Neck1"]!!
        return atan2(n[1] - r[1], n[0] - r[0])
    }

    fun wrapDeg(d: Double): Double { var a = d % 360.0; if (a > 180) a -= 360.0; if (a <= -180) a += 360.0; return a }

    /** The pose classes the audit reports by: how the two face, and their postures' families. */
    fun poseClass(a: Pose, b: Pose, pa: Posture, pb: Posture): String {
        val rel = abs(wrapDeg(Math.toDegrees(yaw(b) - yaw(a))))
        val ra = a["Root"]!!; val rb = b["Root"]!!
        val ya = yaw(a)
        // how far B's pelvis is ahead of A's along A's facing, and across it
        val dx = rb[0] - ra[0]; val dy = rb[1] - ra[1]
        val along = dx * cos(ya) + dy * sin(ya); val across = -dx * sin(ya) + dy * cos(ya)
        val lyingA = pa.lying; val lyingB = pb.lying
        val way = when {
            rel < 50 && abs(along) > abs(across) && abs(along) > 0.08 -> "rear-facing"
            rel < 50 -> "side-by-side"
            rel > 130 -> "face-to-face"
            else -> "perpendicular"
        }
        val fam = listOf(family(pa), family(pb)).sorted().joinToString("+")
        return if (lyingA && lyingB && way == "side-by-side") "side-by-side lying" else "$way $fam"
    }

    private fun family(p: Posture) = when (p) {
        Posture.STAND, Posture.LEAN, Posture.CROUCH -> "standing"
        Posture.SIT, Posture.SIT_ON_SURFACE -> "seated"
        Posture.LIE_BACK, Posture.LIE_FRONT, Posture.LIE_SIDE -> "lying"
        Posture.KNEEL, Posture.ALL_FOURS -> "kneeling"
        Posture.UNKNOWN -> "unknown"
    }

    // ---- maths ----

    private val ORDER: List<String> = run {
        val out = ArrayList<String>()
        fun visit(j: String) { if (j in out) return; BodyGeometry.PARENT[j]?.let { visit(it) }; out += j }
        BodyGeometry.JOINTS.forEach { visit(it) }
        out
    }
    private val ID = doubleArrayOf(0.0, 0.0, 0.0, 1.0)
    fun sc(a: DoubleArray, k: Double) = DoubleArray(3) { a[it] * k }
    fun add(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] + b[it] }
    fun sub(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] - b[it] }
    fun len(a: DoubleArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
    fun lerp(a: DoubleArray, b: DoubleArray, t: Double) = DoubleArray(3) { a[it] + (b[it] - a[it]) * t }
    fun horiz(a: DoubleArray) = hypot(a[0], a[1])
    private fun qmul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )
    private fun qnorm(a: DoubleArray): DoubleArray {
        val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2] + a[3] * a[3])
        return if (l < 1e-12 || !l.isFinite()) ID.copyOf() else DoubleArray(4) { a[it] / l }
    }
    private fun rotate(q: DoubleArray, v: DoubleArray): DoubleArray {
        val r = qmul(qmul(q, doubleArrayOf(v[0], v[1], v[2], 0.0)), doubleArrayOf(-q[0], -q[1], -q[2], q[3]))
        return doubleArrayOf(r[0], r[1], r[2])
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
    private fun q(times: FloatArray, values: FloatArray, t: Float): DoubleArray {
        val (a, b, f) = bracket(times, t)
        val qa = DoubleArray(4) { values[a * 4 + it].toDouble() }
        var qb = DoubleArray(4) { values[b * 4 + it].toDouble() }
        if (qa[0] * qb[0] + qa[1] * qb[1] + qa[2] * qb[2] + qa[3] * qb[3] < 0) qb = DoubleArray(4) { -qb[it] }
        return qnorm(DoubleArray(4) { qa[it] + (qb[it] - qa[it]) * f })
    }
    private fun v3(times: FloatArray, values: FloatArray, t: Float): DoubleArray {
        val (a, b, f) = bracket(times, t)
        return DoubleArray(3) { values[a * 3 + it] + (values[b * 3 + it] - values[a * 3 + it]) * f }
    }
    private fun q(t: BoneTrack, at: Float) = q(t.times, t.values, at)
    private fun v3(t: BoneTrack, at: Float) = v3(t.times, t.values, at)
}
