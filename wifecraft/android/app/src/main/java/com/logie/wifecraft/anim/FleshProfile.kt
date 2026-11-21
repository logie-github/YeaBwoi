package com.logie.wifecraft.anim

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A body's main bone segments as engine/flesh.h measures them: each runs from joint [from] to [to] ("Hips": between the hip
 * joints), or, with [extend], on past [to] along [extend] to [to] by its own tip. Its frame on a [BodyFrame]: the axis
 * along it; n = [sign] x unit(side x axis), the side running from the right hip joint to the left (the pelvis, legs, feet)
 * or the right upper arm to the left ([upper]: the spine, chest, head), so n is the body's front at rest (the foot's top);
 * m = axis x n. Arms and hands are [round]. The order is the engine's (FleshMeasure::pack).
 */
enum class FleshPart(val from: String, val to: String, val extend: String?, val sign: Double, val round: Boolean, val upper: Boolean) {
    PELVIS("Root", "Hips", null, -1.0, false, false),
    SPINE("Spine1", "Chest", null, 1.0, false, true),
    CHEST("Chest", "Neck1", null, 1.0, false, true),
    HEAD("Neck1", "Head", "Neck1", 1.0, false, true),
    L_UPPER_ARM("L_UpperArm", "L_Forearm", null, 1.0, true, true),
    L_FOREARM("L_Forearm", "L_Hand", null, 1.0, true, true),
    L_HAND("L_Hand", "L_Hand", "L_Forearm", 1.0, true, true),
    R_UPPER_ARM("R_UpperArm", "R_Forearm", null, 1.0, true, true),
    R_FOREARM("R_Forearm", "R_Hand", null, 1.0, true, true),
    R_HAND("R_Hand", "R_Hand", "R_Forearm", 1.0, true, true),
    L_THIGH("L_Thigh", "L_Calf", null, -1.0, false, false),
    L_CALF("L_Calf", "L_Foot", null, -1.0, false, false),
    L_FOOT("L_Foot", "L_Toe", null, -1.0, false, false),
    R_THIGH("R_Thigh", "R_Calf", null, -1.0, false, false),
    R_CALF("R_Calf", "R_Foot", null, -1.0, false, false),
    R_FOOT("R_Foot", "R_Toe", null, -1.0, false, false);

    companion object {
        val TRUNK = listOf(PELVIS, SPINE, CHEST)
        val ARMS = listOf(L_UPPER_ARM, L_FOREARM, R_UPPER_ARM, R_FOREARM)
        val THIGHS = listOf(L_THIGH, R_THIGH)
    }
}

/**
 * One segment's flesh, body units (1: the body's standing pelvis): how far it reaches along its frame's +n ([front]) and -n
 * ([back]), either way along m ([side]), past its start and end along it, and an extended segment's [tip] (its length past
 * its joint).
 */
class FleshSegment(val front: Double, val back: Double, val side: Double, val start: Double = 0.0, val end: Double = 0.0, val tip: Double = 0.0) {
    val most: Double get() = max(front, max(back, side))
}

/** A segment placed on a [BodyFrame]: its ends, unit axis, front (n) and side (m), its length; [round]: no front or side. */
class PartFrame(val a0: DoubleArray, val a1: DoubleArray, val axis: DoubleArray, val n: DoubleArray, val m: DoubleArray, val len: Double, val round: Boolean) {
    fun shifted(dx: Double, dy: Double) = PartFrame(doubleArrayOf(a0[0] + dx, a0[1] + dy, a0[2]), doubleArrayOf(a1[0] + dx, a1[1] + dy, a1[2]), axis, n, m, len, round)
}

/**
 * A person's flesh round their bones (engine/flesh.h, measured on their dressed model: [Native.actorFlesh]), what keeps
 * them out of floors, seats, beds and each other: [segments] by part, [rest] their own skeleton's joints in the clips'
 * stance (the reference skeleton's units: the standing pelvis 1.441 over the soles, as [BodyGeometry.REF]; null: the
 * reference's), [pelvis] the standing pelvis in model units (a check). [GENERIC] is the reference body's, the rules' own
 * numbers before the bodies were measured ([measured] false): used for anyone not measured yet (a body still loading, the
 * tests), and the rules then work exactly as they did.
 */
class FleshProfile(
    val segments: Map<FleshPart, FleshSegment>,
    val rest: Map<String, DoubleArray>?,
    val pelvis: Float = 0f,
    val measured: Boolean = true,
    /** The body's lowest vertex standing in the clips' stance over its soles (body units): the engine's check. */
    val lowestStanding: Float = 0f,
) {
    /**
     * How far (body units) the segments' model of the feet reaches under the soles of this body standing in the clips'
     * stance, past what the engine measured on the dressed model ([lowestStanding]): the foot's capsule from the ankle to
     * the toe joint reaches under the sole by its flesh round the axis and its rounded ends (the human man's 0.07, about
     * 8 cm; the woman's 0.04; a dwarf's or a gnome's 0.12), which raised every standing clip by that much. The feet's lowest
     * flesh is read this much higher wherever a support is measured ([lowestOver]); 0 for the generic body.
     */
    val footBias: Double by lazy {
        if (!measured) 0.0 else {
            val r = rest ?: BodyGeometry.REF
            val f = BodyFrame(r.mapValues { (_, v) -> DoubleArray(3) { v[it] / BodyGeometry.REF_UNIT } }, r.keys)
            val low = listOfNotNull(lowest(FleshPart.L_FOOT, f), lowest(FleshPart.R_FOOT, f)).minOrNull()
            if (low == null || !lowestStanding.isFinite()) 0.0 else (lowestStanding - low).coerceIn(0.0, MAX_FOOT_BIAS)
        }
    }

    /**
     * The lowest flesh of [parts] on [f] as [lowest] reads it ([half], [bare] the same), the feet raised by [footBias]: what
     * rests on a floor, a seat or a bed.
     */
    fun lowestOver(f: BodyFrame, parts: Collection<FleshPart>, half: Collection<FleshPart> = emptySet(), bare: Collection<FleshPart> = emptySet()): Double? {
        var low: Double? = null
        for (p in parts) {
            val z0 = lowest(p, f, if (p in half) 0.5 else 1.0, p !in bare) ?: continue
            val z = if (p == FleshPart.L_FOOT || p == FleshPart.R_FOOT) z0 + footBias else z0
            if (low == null || z < low) low = z
        }
        return low
    }

    /** [part]'s flesh, or the generic one's where this body's wasn't measured. */
    fun segment(part: FleshPart): FleshSegment = segments[part] ?: GENERIC_SEGMENTS.getValue(part)

    /** [part] placed on [f], or null when [f] lacks its joints. */
    fun frame(part: FleshPart, f: BodyFrame): PartFrame? = frameOf(part, f, segment(part).tip)

    /**
     * The lowest point of [part]'s flesh on [f] (body units over the floor), its first [upTo] of its length only, its ends'
     * reach past its joints left out unless [ends]; null: no joints.
     */
    fun lowest(part: FleshPart, f: BodyFrame, upTo: Double = 1.0, ends: Boolean = true): Double? {
        val fr = frame(part, f) ?: return null
        return lowest(segment(part), fr, upTo, ends)
    }

    /**
     * The lowest flesh of every part in [parts] ([half]: their first half only; [bare]: without their ends' reach) on [f];
     * null: none could be placed.
     */
    fun lowest(f: BodyFrame, parts: Collection<FleshPart>, half: Collection<FleshPart> = emptySet(), bare: Collection<FleshPart> = emptySet()): Double? {
        var low: Double? = null
        for (p in parts) {
            val z = lowest(p, f, if (p in half) 0.5 else 1.0, p !in bare) ?: continue
            if (low == null || z < low) low = z
        }
        return low
    }

    companion object {
        /** The layout's version (FleshMeasure::pack). */
        const val VERSION = 1

        /** The generic body's segments: the rules' own radii before bodies were measured (PairSpacing's, the classifier's). */
        val GENERIC_SEGMENTS: Map<FleshPart, FleshSegment> = FleshPart.entries.associateWith { p ->
            val r = when (p) {
                FleshPart.PELVIS, FleshPart.SPINE, FleshPart.CHEST -> 0.13
                FleshPart.HEAD -> 0.08
                FleshPart.L_UPPER_ARM, FleshPart.R_UPPER_ARM, FleshPart.L_FOREARM, FleshPart.R_FOREARM -> 0.045
                FleshPart.L_HAND, FleshPart.R_HAND -> 0.02
                FleshPart.L_THIGH, FleshPart.R_THIGH -> 0.07
                FleshPart.L_CALF, FleshPart.R_CALF -> 0.05
                FleshPart.L_FOOT, FleshPart.R_FOOT -> 0.05
            }
            // round, its ends rounded as a capsule's
            FleshSegment(r, r, r, r, r, if (p.extend != null) 0.08 else 0.0)
        }

        /** The most [footBias] is taken to be (body units): a profile reading more is damaged. */
        const val MAX_FOOT_BIAS = 0.2

        /** The reference body's: the rules exactly as before ([measured] false). */
        val GENERIC = FleshProfile(GENERIC_SEGMENTS, null, 0f, measured = false)

        /** Parts whose first half a seat bears (the thighs: the knees may reach past its edge). */
        val HALF = setOf(FleshPart.L_THIGH, FleshPart.R_THIGH)
        /** What a seat bears: the pelvis and the thighs ([RequirementClassifier.HIPS]). */
        val SEAT = setOf(FleshPart.PELVIS, FleshPart.L_THIGH, FleshPart.R_THIGH)
        /**
         * Read without their ends' reach on a seat: the pelvis (the buttocks that hang below the hip joints standing roll under
         * onto the thighs sitting, which the thighs' own flesh holds: the audit's seated dwarf and goblin floated 0.15 yards
         * over a bed's edge with it).
         */
        val SEAT_BARE = setOf(FleshPart.PELVIS)

        /**
         * The engine's floats ([Native.actorFlesh], FleshMeasure::pack: version, pelvis, lowest, the segment count, each
         * segment's ok, front, back, side, start, end, tip; the joint count, each joint's x, y, z) as a profile; null when they
         * aren't that (another version, too short, the trunk or thighs missing).
         */
        fun of(v: FloatArray?): FleshProfile? {
            if (v == null || v.size < 4 || v[0].toInt() != VERSION) return null
            val count = v[3].toInt()
            if (count != FleshPart.entries.size || v.size < 4 + 7 * count + 1) return null
            val segs = HashMap<FleshPart, FleshSegment>()
            for ((i, p) in FleshPart.entries.withIndex()) {
                val o = 4 + 7 * i
                if (v[o] < 0.5f) continue
                val vals = DoubleArray(6) { v[o + 1 + it].toDouble() }
                if (vals.any { !it.isFinite() || it < 0 || it > 3 }) return null
                segs[p] = FleshSegment(vals[0], vals[1], vals[2], vals[3], vals[4], vals[5])
            }
            if ((FleshPart.TRUNK + FleshPart.THIGHS).any { it !in segs }) return null
            val jo = 4 + 7 * count
            val joints = v[jo].toInt()
            var rest: Map<String, DoubleArray>? = null
            if (joints == BodyGeometry.JOINTS.size && v.size >= jo + 1 + 3 * joints) {
                val r = HashMap<String, DoubleArray>()
                for ((k, name) in BodyGeometry.JOINTS.withIndex()) {
                    val x = v[jo + 1 + 3 * k]; val y = v[jo + 2 + 3 * k]; val z = v[jo + 3 + 3 * k]
                    if (x.isFinite() && y.isFinite() && z.isFinite()) r[name] = doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())
                }
                // a body's own skeleton only when it has every joint the clips turn
                if (r.size == BodyGeometry.JOINTS.size) rest = r
            }
            return FleshProfile(segs, rest, v[1], measured = true, lowestStanding = v[2])
        }

        /** [part] on [f] with an extended part's [tip] (body units); null when [f] lacks its joints. */
        fun frameOf(part: FleshPart, f: BodyFrame, tip: Double): PartFrame? {
            fun joint(n: String): DoubleArray? = if (n == "Hips") {
                val l = f["L_Thigh"]; val r = f["R_Thigh"]
                if (l == null || r == null) null else DoubleArray(3) { (l[it] + r[it]) / 2 }
            } else f[n]
            val a0 = joint(part.from) ?: return null
            val to = joint(part.to) ?: return null
            val a1 = if (part.extend != null) {
                val e = joint(part.extend) ?: return null
                val d = V.unit(V.sub(to, e)) ?: doubleArrayOf(0.0, 0.0, 1.0)
                V.add(to, V.scale(d, tip))
            } else to
            val ax = V.sub(a1, a0)
            val len = sqrt(V.dot(ax, ax))
            val axis = V.unit(ax) ?: part.extend?.let { joint(it) }?.let { V.unit(V.sub(to, it)) } ?: doubleArrayOf(0.0, 0.0, 1.0)
            var l = joint(if (part.upper) "L_UpperArm" else "L_Thigh"); var r = joint(if (part.upper) "R_UpperArm" else "R_Thigh")
            if (l == null || r == null) { l = joint(if (part.upper) "L_Thigh" else "L_UpperArm"); r = joint(if (part.upper) "R_Thigh" else "R_UpperArm") }
            val side = if (l != null && r != null) V.unit(V.sub(l, r)) ?: doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
            val c = V.cross(side, axis)
            val cl = sqrt(V.dot(c, c))
            val round = part.round || cl < 0.25
            val n = if (cl < 1e-9) doubleArrayOf(1.0, 0.0, 0.0) else V.scale(c, part.sign / cl)
            return PartFrame(a0, a1, axis, n, V.cross(axis, n), len, round)
        }

        /**
         * How far [s]'s flesh reaches from its axis toward unit direction [d] at a point on it ([atStart], [atEnd]: at an
         * end, where the end's reach past it counts too): its section's (an ellipse, front or back by the side [d] is on)
         * and the end's.
         */
        fun reach(s: FleshSegment, f: PartFrame, d: DoubleArray, atStart: Boolean, atEnd: Boolean): Double {
            val dn = V.dot(d, f.n); val dm = V.dot(d, f.m); val da = V.dot(d, f.axis)
            var r = if (f.round) s.most * sqrt(max(0.0, 1 - da * da)) else {
                val rn = if (dn > 0) s.front else s.back
                sqrt(rn * rn * dn * dn + s.side * s.side * dm * dm)
            }
            if (atEnd && da > 0) r += s.end * da
            if (atStart && da < 0) r += s.start * -da
            return r
        }

        /** The lowest point of [s]'s flesh on [f] (its first [upTo] of its length; [ends]: with its ends' reach): engine/flesh.h fleshLowest. */
        fun lowest(s: FleshSegment, f: PartFrame, upTo: Double = 1.0, ends: Boolean = true): Double {
            val down = doubleArrayOf(0.0, 0.0, -1.0)
            val far = if (upTo >= 1.0) f.a1 else DoubleArray(3) { f.a0[it] + (f.a1[it] - f.a0[it]) * upTo }
            val az = f.axis[2]
            val disk = reach(s, f, down, atStart = false, atEnd = false)
            val z0 = f.a0[2] - disk - (if (ends && az > 0) s.start * az else 0.0)
            val z1 = far[2] - disk - (if (ends && az < 0 && upTo >= 1.0) s.end * -az else 0.0)
            return min(z0, z1)
        }
    }
}

/**
 * The flesh-under-joint lift ([ActorRequirement.liftUnits], [RequirementClassifier.groundLift]) for one person: how far
 * (body units) a clip must be raised for none of its resting flesh to come nearer its support than that person's own
 * flesh lets it. The generic profile gives the classifier's own lift unchanged. What rests is the classifier's own rule
 * ([RequirementClassifier.bearing]): a body lying with its pelvis on the surface rests on its trunk, head, arms and thighs
 * (its shins and feet may hang off a bed's edge, as their authors made them: they raised the whole body by up to 50 cm);
 * the hands never (their fingers, read as a long tip along the forearm, pointed into the support); the feet by their own
 * soles ([FleshProfile.footBias]).
 */
object FleshLift {
    /**
     * For [need] (an actor's requirement, read from the clip) played on a body with [flesh], [frames] the clip's body on that
     * body's skeleton ([BodyGeometry.frames] with [flesh]): standing, leaning, crouching: everything but the hands over the
     * floor; on a seat (hips more than [RequirementClassifier.FLOOR_SIT_MAX] up): the pelvis and the thighs' first half over
     * the seat; lying with the pelvis on the surface: the trunk, head, arms and thighs over it; otherwise (kneeling, a floor
     * sit, lying with the hips raised) everything but the hands over the support.
     */
    fun liftUnits(need: ActorRequirement, frames: List<BodyFrame>, flesh: FleshProfile): Double {
        if (!flesh.measured || frames.isEmpty()) return need.liftUnits.toDouble()
        val stand = need.posture == Posture.STAND || need.posture == Posture.LEAN || need.posture == Posture.CROUCH
        val support = if (stand) 0.0 else need.heightUnits.toDouble()
        val seat = !stand && need.posture.seated && need.height >= RequirementClassifier.FLOOR_SIT_MAX
        if (seat) return groundLift(frames, flesh, support, FleshProfile.SEAT, FleshProfile.HALF, FleshProfile.SEAT_BARE)
        val lying = need.posture.lying && RequirementClassifier.pelvisRests(frames, support)
        return groundLift(frames, flesh, support, if (lying) LYING else BEARING)
    }

    /** What bears a body that is not lying on its trunk (standing, kneeling, a floor sit): everything but the hands. */
    val BEARING: List<FleshPart> = FleshPart.entries.filter { it != FleshPart.L_HAND && it != FleshPart.R_HAND }
    /** What bears a body lying with its pelvis on the surface: the trunk, the head, the arms and the thighs. */
    val LYING: List<FleshPart> = FleshPart.TRUNK + FleshPart.HEAD + FleshPart.ARMS + FleshPart.THIGHS

    /** How far [frames] must be raised for [parts]' flesh ([half]: their first half; [bare]: without their ends) to stay over [support] (body units). */
    fun groundLift(frames: List<BodyFrame>, flesh: FleshProfile, support: Double = 0.0, parts: Collection<FleshPart> = FleshPart.entries,
                   half: Collection<FleshPart> = emptySet(), bare: Collection<FleshPart> = emptySet()): Double {
        var lift = 0.0
        for (f in frames) {
            val low = flesh.lowestOver(f, parts, half, bare) ?: continue
            if (support - low > lift) lift = support - low
        }
        return lift
    }

    /** [need] with its lift for a body with [flesh] (the same [need] for the generic body, or when nothing changes). */
    fun adjusted(need: ActorRequirement, frames: List<BodyFrame>, flesh: FleshProfile): ActorRequirement {
        if (!flesh.measured) return need
        val lift = liftUnits(need, frames, flesh).toFloat()
        return if (abs(lift - need.liftUnits) < 1e-6f) need else need.copy(liftUnits = lift)
    }
}
