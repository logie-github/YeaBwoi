package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.ActorSlot
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.sims.PairDefinition
import com.logie.wifecraft.sims.SimsClip
import com.logie.wifecraft.sims.SimsRig
import java.util.IdentityHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sims 4 clip -> WoW-side [Anim], by the [profile] (assets/sims_profile.json) and the player's Sims 4 [rig]
 * (the skeleton read from their game package; without one every clip fails with [NEEDS_RIG]).
 *
 * The maths. A Sims key is a bone's local rotation, relative to its parent, in Sims axes (y up, facing +z).
 * Per key time every rig bone's posed world rotation G and the bind's world rotation B are built down the
 * hierarchy; D = G * inv(B) is how far the bone turned from its bind, in world axes (the same as the bone's
 * own delta inv(bind) * pose carried into the world by B). D is taken into WoW's axes by the profile's axis
 * remap (C D C^-1). WoW's M2 bones are all at identity at rest with model-space pivots, so a WoW bone's local
 * rotation is inv(E_parent) * E with E = D * Q, Q turning the WoW rest direction of the bone (profile wowRest,
 * pivot to its aim's pivot) onto the Sims bind direction (bind position to the aim's, remapped): the Sims
 * bind pose lands on the WoW skeleton before the clip's motion. Per-bone flips negate rotation axis
 * components last (a fix for a bone that turns the wrong way, none by default). A position-flagged bone (the
 * root) moves by its world displacement from the bind, remapped and scaled to WoW units.
 *
 * Bones the profile leaves out still feed the hierarchy (ROOT, ROOT_bind, twists); only mapped bones present
 * in the rig become tracks. Dropped names ([Profile.drop]) are never mapped. Keys are taken at every time any
 * rig bone has a key (resampled at 30 per second past [MAX_KEYS]).
 */
class Retargeter(private val profile: Profile, private val rig: SimsRig?) : Retarget {

    private class Mapped(val rule: Profile.BoneRule, val index: Int) {
        var q = Q.ID
        var parent: Mapped? = null
    }

    private val prep: Prep? = rig?.let { Prep(it) }

    private inner class Prep(val rig: SimsRig) {
        val n = rig.bones.size
        val indexOf = IdentityHashMap<Any, Int>().also { m -> rig.bones.forEachIndexed { i, b -> m[b] = i } }
        val order: IntArray = topo()
        val bindRot = Array(n) { Q.ID }
        val bindPos = Array(n) { DoubleArray(3) }
        val axis = profile.axisMatrix().map { it.toDouble() }.toDoubleArray()
        val mapped: List<Mapped>
        val scale: Double

        init {
            for (i in order) {
                val b = rig.bones[i]
                val lr = Q.norm(b.bindRotation.map { it.toDouble() }.toDoubleArray())
                val lp = b.bindPosition.map { it.toDouble() }.toDoubleArray()
                if (b.parent < 0) { bindRot[i] = lr; bindPos[i] = lp } else {
                    bindRot[i] = Q.mul(bindRot[b.parent], lr)
                    bindPos[i] = V.add(bindPos[b.parent], Q.rotate(bindRot[b.parent], lp))
                }
            }
            val byName = HashMap<String, Int>()
            rig.bones.forEachIndexed { i, b -> byName.putIfAbsent(b.name.lowercase(), i) }
            val list = profile.bones.mapNotNull { r ->
                if (profile.dropped(r.sims)) return@mapNotNull null
                byName[r.sims.lowercase()]?.let { Mapped(r, it) }
            }
            val byWow = list.associateBy { it.rule.wow }
            for (m in list) {
                // The nearest mapped WoW ancestor the rig has (a skipped one stays at rest in the engine).
                var p = m.rule.wowParent
                while (p != null && p !in byWow) p = profile.wowRule(p)?.wowParent
                m.parent = p?.let { byWow[it] }
                val aim = m.rule.aim?.let { byWow[it] }
                val wowDir = profile.wowRest[m.rule.wow]
                if (aim != null && wowDir != null) {
                    val s = V.mat(axis, V.sub(bindPos[aim.index], bindPos[m.index]))
                    m.q = Q.arc(wowDir.map { it.toDouble() }.toDoubleArray(), s)
                }
            }
            mapped = list
            val root = list.firstOrNull { it.rule.position }
            val up = V.mat(axis, root?.let { bindPos[it.index] } ?: doubleArrayOf(0.0, 0.0, 0.0))[2]
            scale = if (profile.rootHeight > 0 && up > 1e-3) profile.rootHeight / up else profile.positionScale.toDouble()
        }

        private fun topo(): IntArray {
            val out = ArrayList<Int>(n)
            val state = IntArray(n)
            fun visit(i: Int) {
                if (state[i] == 2) return
                require(state[i] == 0) { "The Sims skeleton's bone ${rig.bones[i].name} is its own ancestor; the skeleton is damaged" }
                state[i] = 1
                val p = rig.bones[i].parent
                if (p >= 0) visit(p)
                state[i] = 2
                out.add(i)
            }
            for (i in 0 until n) visit(i)
            return out.toIntArray()
        }
    }

    override fun convert(clip: SimsClip): Anim {
        val p = prep ?: throw IllegalStateException(NEEDS_RIG)
        return convertWith(p, clip, p.scale)
    }

    override fun convertPair(a: SimsClip, b: SimsClip, def: PairDefinition?): PairAnim = convertPair(a, b, def, null)

    /**
     * Two clips made around one shared origin, as a [PairAnim]. Each actor's start (its root's first key: where its
     * pelvis stands from the origin, horizontally, and which way it faces) is taken out of its Root track into its
     * [PairOffset]; the tuning's per-actor offset and angle ([def], Sims axes and metres: an assumption) and the
     * clip header's initial offset are added on top. Both actors' horizontal movement and offsets use ONE scale,
     * the mean of the two skeletons' (rootHeight over the Sims root bind height), so they stay where the clips put
     * them even when the two Sims skeletons differ ([rigB], for actor B; null: the same as A's); vertical movement
     * keeps each skeleton's own. Clip lengths that differ by up to a frame are trimmed to the shorter; more is refused.
     */
    fun convertPair(a: SimsClip, b: SimsClip, def: PairDefinition?, rigB: SimsRig?): PairAnim {
        val pa = prep ?: throw IllegalStateException(NEEDS_RIG)
        val pb = rigB?.let { Prep(it) } ?: pa
        val shared = (pa.scale + pb.scale) / 2
        PairAssembly.checkLengths(a, b)
        val slotA = def?.actors?.getOrNull(0)
        val slotB = def?.actors?.getOrNull(1)
        val (animA, offA) = place(pa, convertWith(pa, a, shared), a, slotA, shared)
        val (animB, offB) = place(pb, convertWith(pb, b, shared), b, slotB, shared)
        return PairAssembly.assemble(a, b, animA, animB, def, offA, offB)
    }

    /**
     * Takes the actor's start out of its root's tracks: the first key's horizontal position and its turn about up
     * (from the bind) become the offset; every key of the root is then relative to that start. Then the clip's
     * initial offset and the tuning's [slot] offset are put in front (outermost: the tuning's).
     */
    private fun place(p: Prep, anim: Anim, clip: SimsClip, slot: ActorSlot?, shared: Double): Pair<Anim, PairOffset> {
        val root = p.mapped.firstOrNull { it.rule.position && it.parent == null }
        var start = Placement(0.0, 0.0, 0.0, 0.0)
        var tracks = anim.tracks
        if (root != null) {
            val rt = anim.tracks.firstOrNull { it.bone == root.rule.wow && it.channel == Channel.ROTATION }
            val pt = anim.tracks.firstOrNull { it.bone == root.rule.wow && it.channel == Channel.POSITION }
            var twist = Q.ID
            if (rt != null && rt.times.isNotEmpty() && root.rule.flip.isEmpty()) {
                val r0 = DoubleArray(4) { rt.values[it].toDouble() }
                val d0 = Q.mul(r0, Q.inv(root.q))   // the turn from the bind, without the rest-pose alignment
                if (d0[2] * d0[2] + d0[3] * d0[3] > 1e-12) twist = Q.norm(doubleArrayOf(0.0, 0.0, d0[2], d0[3]))
            }
            val x0 = pt?.values?.getOrNull(0)?.toDouble() ?: 0.0
            val y0 = pt?.values?.getOrNull(1)?.toDouble() ?: 0.0
            val back = Q.inv(twist)
            tracks = anim.tracks.map { t ->
                when {
                    t === rt -> {
                        val v = t.values.copyOf()
                        for (k in t.times.indices) {
                            val r = Q.mul(back, DoubleArray(4) { t.values[k * 4 + it].toDouble() })
                            for (c in 0 until 4) v[k * 4 + c] = r[c].toFloat()
                        }
                        BoneTrack(t.bone, t.channel, t.times, v)
                    }
                    t === pt -> {
                        val v = t.values.copyOf()
                        for (k in t.times.indices) {
                            val d = doubleArrayOf(t.values[k * 3] - x0, t.values[k * 3 + 1] - y0, t.values[k * 3 + 2].toDouble())
                            val r = Q.rotate(back, d)
                            for (c in 0 until 3) v[k * 3 + c] = r[c].toFloat()
                        }
                        BoneTrack(t.bone, t.channel, t.times, v)
                    }
                    else -> t
                }
            }
            start = Placement(x0, y0, 0.0, Math.toDegrees(2 * atan2(twist[2], twist[3])))
        }
        // the clip header's own offset (identity in every clip seen) and the tuning's, both in Sims axes and metres
        val header = clip.initialOffset
        val initial = if (header.isIdentity) Placement(0.0, 0.0, 0.0, 0.0) else simsPlacement(p, header.translation.map { it.toDouble() },
            yawOf(p, header.rotation.map { it.toDouble() }.toDoubleArray()), shared)
        val tuned = if (slot == null) Placement(0.0, 0.0, 0.0, 0.0) else simsPlacement(p, listOf(slot.x.toDouble(), slot.y.toDouble(), slot.z.toDouble()),
            upSign(p) * slot.angle.toDouble(), shared)
        val total = tuned.then(initial).then(start)
        return anim.copy(tracks = tracks) to PairOffset(total.x.toFloat(), total.y.toFloat(), total.z.toFloat(), wrapDeg(total.yaw).toFloat())
    }

    /** A placement on the ground: position (WoW axes, scaled) and a turn about up in degrees. */
    private class Placement(val x: Double, val y: Double, val z: Double, val yaw: Double) {
        /** This placement, then [inner] inside it. */
        fun then(inner: Placement): Placement {
            val r = Math.toRadians(yaw)
            return Placement(x + inner.x * cos(r) - inner.y * sin(r), y + inner.x * sin(r) + inner.y * cos(r), z + inner.z, yaw + inner.yaw)
        }
    }

    private fun simsPlacement(p: Prep, t: List<Double>, yawDeg: Double, shared: Double): Placement {
        val w = V.mat(p.axis, t.toDoubleArray())
        return Placement(w[0] * shared, w[1] * shared, w[2] * p.scale, yawDeg)
    }

    /** +1 when the Sims up axis (y) is WoW's up (z), -1 when the profile turns it upside down. */
    private fun upSign(p: Prep): Double = if (V.mat(p.axis, doubleArrayOf(0.0, 1.0, 0.0))[2] < 0) -1.0 else 1.0

    /** A Sims-axes rotation's turn about up, in degrees (WoW's sense). */
    private fun yawOf(p: Prep, q: DoubleArray): Double {
        val n = Q.norm(q)
        val w = V.mat(p.axis, n)
        val t = Q.norm(doubleArrayOf(0.0, 0.0, w[2], n[3]))
        return Math.toDegrees(2 * atan2(t[2], t[3]))
    }

    private fun wrapDeg(d: Double): Double { var a = d % 360.0; if (a > 180) a -= 360.0; if (a <= -180) a += 360.0; return a }

    private fun convertWith(p: Prep, clip: SimsClip, hScale: Double): Anim {
        if (p.mapped.isEmpty()) throw IllegalStateException("This Sims skeleton has none of the bones the profile maps (is it a pet's skeleton?); pick the game's own adult skeleton")
        // tracks per rig bone
        val rot = HashMap<Int, com.logie.wifecraft.sims.SimsTrack>()
        val pos = HashMap<Int, com.logie.wifecraft.sims.SimsTrack>()
        var foreign = 0
        for (t in clip.tracks) {
            val b = p.rig.byHash(t.boneHash)
            val i = b?.let { p.indexOf[it] }
            if (i == null) { foreign++; continue }
            val per = if (t.channel == Channel.ROTATION) 4 else 3
            if (t.times.isEmpty() || t.values.size < t.times.size * per) continue
            (if (t.channel == Channel.ROTATION) rot else pos)[i] = t
        }
        if (rot.isEmpty() && pos.isEmpty()) {
            throw IllegalArgumentException(
                if (clip.tracks.isEmpty()) "The clip has no bone curves to convert"
                else "None of the clip's ${clip.tracks.size} bone curves are bones of your Sims 4 skeleton (an animation for another body, such as a pet or a child?)"
            )
        }
        // Does any keyed bone reach a mapped one (itself or an ancestor of it)?
        val reach = BooleanArray(p.n)
        for (m in p.mapped) { var i = m.index; while (i >= 0 && !reach[i]) { reach[i] = true; i = p.rig.bones[i].parent } }
        if ((rot.keys + pos.keys).none { reach[it] }) {
            throw IllegalArgumentException("The clip moves none of the bones the WoW skeleton is driven by (only ${rot.size + pos.size} helper bones); nothing to convert")
        }

        val times = keyTimes(clip, rot.values + pos.values)
        val outRot = p.mapped.associateWith { FloatArray(times.size * 4) }
        val posMapped = p.mapped.filter { it.rule.position }
        val outPos = posMapped.associateWith { FloatArray(times.size * 3) }
        val g = Array(p.n) { Q.ID }
        val w = Array(p.n) { DoubleArray(3) }
        val e = IdentityHashMap<Mapped, DoubleArray>()
        val prev = IdentityHashMap<Mapped, DoubleArray>()
        val bindRel = profile.trackSpace == "bindRelative"
        for ((k, t) in times.withIndex()) {
            for (i in p.order) {
                val b = p.rig.bones[i]
                val bindR = Q.norm(b.bindRotation.map { it.toDouble() }.toDoubleArray())
                val bindP = b.bindPosition.map { it.toDouble() }.toDoubleArray()
                val lr = rot[i]?.let { sampleRot(it, t) }?.let { if (bindRel) Q.mul(bindR, it) else it } ?: bindR
                val lp = pos[i]?.let { sampleV3(it, t) }?.let { if (bindRel) V.add(bindP, it) else it } ?: bindP
                if (b.parent < 0) { g[i] = lr; w[i] = lp } else {
                    g[i] = Q.mul(g[b.parent], lr)
                    w[i] = V.add(w[b.parent], Q.rotate(g[b.parent], lp))
                }
            }
            e.clear()
            for (m in p.mapped) {
                val d = Q.norm(Q.mul(g[m.index], Q.inv(p.bindRot[m.index])))
                val dw = doubleArrayOf(0.0, 0.0, 0.0, d[3]).also { val v = V.mat(p.axis, d); it[0] = v[0]; it[1] = v[1]; it[2] = v[2] }
                e[m] = Q.norm(Q.mul(dw, m.q))
            }
            for (m in p.mapped) {
                val parentE = m.parent?.let { e[it] } ?: Q.ID
                var r = Q.norm(Q.mul(Q.inv(parentE), e[m]!!))
                if (m.rule.flip.isNotEmpty()) {
                    r = r.copyOf()
                    for (c in m.rule.flip) r["xyz".indexOf(c)] = -r["xyz".indexOf(c)]
                }
                val last = prev[m]
                if (last != null && Q.dot(last, r) < 0) r = doubleArrayOf(-r[0], -r[1], -r[2], -r[3])
                prev[m] = r
                val o = outRot.getValue(m)
                for (c in 0 until 4) o[k * 4 + c] = r[c].toFloat()
                if (m.rule.position) {
                    // horizontal by [hScale] (a pair's shared one), vertical by this skeleton's own
                    val raw = V.mat(p.axis, V.sub(w[m.index], p.bindPos[m.index]))
                    val disp = doubleArrayOf(raw[0] * hScale, raw[1] * hScale, raw[2] * p.scale)
                    val local = Q.rotate(Q.inv(parentE), disp)
                    val op = outPos.getValue(m)
                    for (c in 0 until 3) op[k * 3 + c] = local[c].toFloat()
                }
            }
        }
        val tracks = ArrayList<BoneTrack>()
        for (m in p.mapped) {
            tracks.add(BoneTrack(m.rule.wow, Channel.ROTATION, times, outRot.getValue(m)))
            outPos[m]?.let { tracks.add(BoneTrack(m.rule.wow, Channel.POSITION, times, it)) }
        }
        for (tr in tracks) require(tr.values.all { it.isFinite() }) { "The conversion of ${tr.bone} gave a value that is not a number; the clip or skeleton is damaged" }
        val duration = if (clip.duration > 0f && clip.duration.isFinite()) clip.duration else times.last()
        val distinct = times.size
        return Anim(
            id = "",
            name = clip.name.substringAfterLast(':').ifBlank { clip.sourceAsset.removeSuffix(".blend") }.trim(),
            category = "",
            duration = duration,
            // A pose (one moment, or two keys a frame apart) holds; a longer clip loops.
            loop = distinct > 2 && duration > 0.2f,
            tracks = tracks,
            source = clip.sourceKey,
            importedAt = System.currentTimeMillis(),
        )
    }

    private fun keyTimes(clip: SimsClip, tracks: Collection<com.logie.wifecraft.sims.SimsTrack>): FloatArray {
        val set = java.util.TreeSet<Float>()
        for (t in tracks) for (x in t.times) if (x.isFinite() && x >= 0f) set.add(Math.round(x * 10000f) / 10000f)
        if (set.isEmpty()) set.add(0f)
        if (set.size <= MAX_KEYS) return set.toFloatArray()
        val end = if (clip.duration > 0f) clip.duration else set.last()
        val n = (end * 30f).toInt().coerceIn(1, MAX_KEYS - 1)
        return FloatArray(n + 1) { end * it / n }
    }

    private fun bracket(times: FloatArray, t: Float): Triple<Int, Int, Double> {
        if (times.size == 1 || t <= times[0]) return Triple(0, 0, 0.0)
        val last = times.size - 1
        if (t >= times[last]) return Triple(last, last, 0.0)
        var lo = 0
        var hi = last
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (times[mid] <= t) lo = mid else hi = mid }
        val span = times[hi] - times[lo]
        return Triple(lo, hi, if (span > 0) ((t - times[lo]) / span).toDouble() else 0.0)
    }

    private fun sampleRot(tr: com.logie.wifecraft.sims.SimsTrack, t: Float): DoubleArray {
        val (a, b, f) = bracket(tr.times, t)
        val qa = DoubleArray(4) { tr.values[a * 4 + it].toDouble() }
        val qb = DoubleArray(4) { tr.values[b * 4 + it].toDouble() }
        return Q.nlerp(qa, qb, f)
    }

    private fun sampleV3(tr: com.logie.wifecraft.sims.SimsTrack, t: Float): DoubleArray {
        val (a, b, f) = bracket(tr.times, t)
        return DoubleArray(3) { tr.values[a * 3 + it] + (tr.values[b * 3 + it] - tr.values[a * 3 + it]) * f }
    }

    companion object {
        const val MAX_KEYS = 900
        const val NEEDS_RIG = "Converting Sims animations needs your Sims 4 skeleton: in Options, \"Sims skeleton\", pick a package of your Sims 4 game (such as ClientFullBuild0.package) once, then import again"
    }
}

/** Quaternions as x, y, z, w (doubles). */
internal object Q {
    val ID = doubleArrayOf(0.0, 0.0, 0.0, 1.0)

    fun mul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )

    fun inv(a: DoubleArray) = doubleArrayOf(-a[0], -a[1], -a[2], a[3])
    fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]

    fun norm(a: DoubleArray): DoubleArray {
        val l = sqrt(dot(a, a))
        return if (l < 1e-12 || !l.isFinite()) ID.copyOf() else doubleArrayOf(a[0] / l, a[1] / l, a[2] / l, a[3] / l)
    }

    fun rotate(q: DoubleArray, v: DoubleArray): DoubleArray {
        val r = mul(mul(q, doubleArrayOf(v[0], v[1], v[2], 0.0)), inv(q))
        return doubleArrayOf(r[0], r[1], r[2])
    }

    fun nlerp(a: DoubleArray, b0: DoubleArray, f: Double): DoubleArray {
        val b = if (dot(a, b0) < 0) DoubleArray(4) { -b0[it] } else b0
        return norm(DoubleArray(4) { a[it] + (b[it] - a[it]) * f })
    }

    /** The shortest turn taking direction [from] onto direction [to]. */
    fun arc(from: DoubleArray, to: DoubleArray): DoubleArray {
        val a = V.unit(from)
        val b = V.unit(to)
        if (a == null || b == null) return ID.copyOf()
        val d = V.dot(a, b)
        if (d < -0.999999) {
            var axis = V.cross(doubleArrayOf(1.0, 0.0, 0.0), a)
            if (V.dot(axis, axis) < 1e-6) axis = V.cross(doubleArrayOf(0.0, 1.0, 0.0), a)
            val u = V.unit(axis)!!
            return doubleArrayOf(u[0], u[1], u[2], 0.0)
        }
        val c = V.cross(a, b)
        return norm(doubleArrayOf(c[0], c[1], c[2], 1.0 + d))
    }
}

internal object V {
    fun add(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] + b[it] }
    fun sub(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] - b[it] }
    fun scale(a: DoubleArray, s: Double) = DoubleArray(3) { a[it] * s }
    fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    fun unit(a: DoubleArray): DoubleArray? { val l = sqrt(dot(a, a)); return if (l < 1e-9) null else scale(a, 1 / l) }
    /** A 3x3 row-major matrix times the first three components of [v]. */
    fun mat(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { r -> m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2] }
}

/**
 * An [Anim] as the engine takes it (engine/Native.boneAnim): one entry per track in [bones] and [channels]
 * (0 rotation x,y,z,w; 1 position x,y,z), each track's key count in [keyCounts], all times (seconds) and values
 * concatenated in track order. Bad tracks (values that don't fit the times) are left out.
 */
class AnimArrays(
    val bones: Array<String>,
    val channels: IntArray,
    val keyCounts: IntArray,
    val times: FloatArray,
    val values: FloatArray,
    val duration: Float,
    val loop: Boolean,
) {
    companion object {
        fun of(a: Anim): AnimArrays {
            val ok = a.tracks.filter { t ->
                val per = if (t.channel == Channel.ROTATION) 4 else 3
                t.times.isNotEmpty() && t.values.size == t.times.size * per &&
                    t.times.all { it.isFinite() } && t.values.all { it.isFinite() }
            }
            val times = FloatArray(ok.sumOf { it.times.size })
            val values = FloatArray(ok.sumOf { it.values.size })
            var ti = 0
            var vi = 0
            for (t in ok) {
                t.times.copyInto(times, ti); ti += t.times.size
                t.values.copyInto(values, vi); vi += t.values.size
            }
            return AnimArrays(
                ok.map { it.bone }.toTypedArray(),
                IntArray(ok.size) { if (ok[it].channel == Channel.ROTATION) 0 else 1 },
                IntArray(ok.size) { ok[it].times.size },
                times, values,
                if (a.duration.isFinite() && a.duration > 0f) a.duration else 0f,
                a.loop,
            )
        }
    }
}
