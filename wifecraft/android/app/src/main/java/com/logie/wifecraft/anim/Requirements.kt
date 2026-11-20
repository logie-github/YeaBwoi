package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.SimsClip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/** How a body is held through a clip. LIE_* by which side faces up; SIT_ON_SURFACE: sitting up on a table or counter, feet off the floor. */
enum class Posture { STAND, SIT, SIT_ON_SURFACE, LIE_BACK, LIE_FRONT, LIE_SIDE, KNEEL, ALL_FOURS, CROUCH, LEAN, UNKNOWN;

    val lying: Boolean get() = this == LIE_BACK || this == LIE_FRONT || this == LIE_SIDE
    val seated: Boolean get() = this == SIT || this == SIT_ON_SURFACE
    /** The family the menus group by ("Lying", "Sitting"...). */
    val group: String get() = when (this) {
        STAND -> "Standing"; SIT, SIT_ON_SURFACE -> "Sitting"; LIE_BACK, LIE_FRONT, LIE_SIDE -> "Lying"
        KNEEL -> "Kneeling"; ALL_FOURS -> "On all fours"; CROUCH -> "Crouching"; LEAN -> "Leaning"; UNKNOWN -> "Unsorted"
    }
}

/**
 * What a body rests on. FLOOR: on the ground itself (sitting, lying, kneeling: a rug, the floor, or lifted onto a bed when
 * the whole pair is); NONE: standing on open ground; ANY_SEAT: a seat height (chair, couch, a bed's edge); UNKNOWN: not
 * decided (no usable body, no hint): offered nowhere until reviewed.
 */
enum class Surface { FLOOR, BED, COUCH, CHAIR, TABLE_OR_COUNTER, ANY_SEAT, NONE, UNKNOWN }

/**
 * One actor's need. [height] is the support's height above the floor in metres for a body whose standing pelvis is
 * [RequirementClassifier.PELVIS_METRES] high ([heightUnits] the same in body units: 1 = that standing pelvis height): the
 * seat under a sitter, the bed under a lying body, the floor (0) under a kneeling one, the surface the hands rest on for
 * LEAN. [x], [y] where the support is in the clip's frame (body units: the pelvis; for LEAN the hands), [yaw] degrees:
 * pelvis-to-head for a lying body, the facing otherwise; [headX]..[feetY] the head and feet over the ground; [feetOnFloor]
 * the feet stay on the floor (the support may not be lifted); [liftUnits] how far (body units) the clip must be raised for
 * no joint it rests on to come nearer its support (the floor, the seat, the bed) than the flesh around it ([RequirementClassifier.groundLift]: a floor sit whose hips
 * the clip puts 6 cm up, made for a thinner body, is raised; 0 when nothing reaches under).
 */
data class ActorRequirement(
    val posture: Posture,
    val surface: Surface,
    val height: Float,
    val heightUnits: Float,
    val x: Float, val y: Float, val yaw: Float,
    val headX: Float, val headY: Float, val feetX: Float, val feetY: Float,
    val feetOnFloor: Boolean,
    val confidence: Float,
    val reason: String,
    val liftUnits: Float = 0f,
) {
    /** Where the clip's support must be put for its flesh to rest on the surface (body units): [heightUnits] less [liftUnits]. */
    val restUnits: Float get() = heightUnits - liftUnits
}

/**
 * What an animation needs: [surface] the furniture it is played on (the actor's that is not on the floor, else FLOOR or
 * NONE), each [actors]' own need in the clip's role order, a [confidence] (0..1), the [reason] in words and the [basis]
 * ("geometry", "geometry+names", "names", "tuning"...).
 */
data class Requirement(
    val surface: Surface,
    val actors: List<ActorRequirement>,
    val confidence: Float,
    val reason: String,
    val basis: String,
    val version: Int = VERSION,
) {
    /** The actor that is on the furniture (the first one not on the floor), else 0. */
    val primary: Int get() = actors.indexOfFirst { it.surface !in FLOOR_LEVEL }.takeIf { it >= 0 } ?: 0

    companion object {
        /** Raised when the classifier changes so stored results are made again (2: [ActorRequirement.liftUnits]). */
        const val VERSION = 2
        val FLOOR_LEVEL = setOf(Surface.FLOOR, Surface.NONE)
    }
}

/**
 * The names and tuning an animation came with: [names] (its own name, the source clip's, the pack's, the category),
 * [locations] (a tuning's `animation_locations`, as written: SOFA, DOUBLE_BED...), [slotNames] (a Sims clip's IK slot
 * targets and other actors' namespaces, which name the object a clip was made against).
 */
data class Hints(val names: List<String> = emptyList(), val locations: Set<String> = emptySet(), val slotNames: List<String> = emptyList()) {
    companion object {
        fun of(a: Anim) = Hints(listOf(a.name, a.sourceName, a.category) + a.performances.flatMap { listOf(it.name, it.stage) },
            a.performances.mapNotNull { it.setting.takeIf(String::isNotBlank)?.uppercase()?.replace(' ', '_') }.toSet())
        fun of(p: PairAnim) = Hints(listOf(p.name, p.category, p.a.sourceName, p.b.sourceName), p.locations)
        fun of(c: SimsClip) = Hints(listOf(c.name, c.sourceAsset), emptySet(),
            c.explicitNamespaces + c.slots.flatMap { listOf(it.actor, it.joint) })
    }
}

/**
 * Decides which surface and posture each actor of an animation needs, from the body itself (pelvis height, the torso's
 * pitch, which side faces up, the thighs' angle and the knees' bend, where the knees, feet and hands are over the floor,
 * sampled over the clip) and from its names and tuning (furniture words, posture words, the tuning's locations, the Sims
 * clip's slot targets). The body decides the posture; a hint confirms it or picks the furniture where the body only says
 * "a seat" or "on the floor". Unknown clips are classified by the body alone; a clip whose body can't be read and that
 * names nothing stays UNKNOWN (never assumed to stand on open ground). Heuristics: checked on synthetic bodies and the
 * user's sitting pose pack, not on real partner packs (none were available).
 */
object RequirementClassifier {
    /** A standing pelvis height in metres: the nominal body the metres are given for. */
    const val PELVIS_METRES = 0.95f
    /** Below this support height (metres) a body is on the floor. */
    const val FLOOR_MAX = 0.25f
    /**
     * Below this a sitting body sits on the floor; above it, it needs a (low) seat: hips held 0.2 m up with the feet on the
     * floor are a sit on a stool or step, and on the bare floor they hang in the air (the audit: the pose pack's 4, 11, 12
     * and 14 looked like chair sits with no chair).
     */
    const val FLOOR_SIT_MAX = 0.12f
    /** Above this a seat is a table or counter top. */
    const val SEAT_MAX = 0.75f
    /** Above this a lying or kneeling body is on a table or counter rather than a bed. */
    const val BED_MAX = 0.8f

    // body units: the hip joints above a seat, the back's joints above a bed under a lying body, a kneeling knee's pivot
    // above the floor, the reference skeleton's Root pivot above its hip joints (a Sims root sits at the hip joints: the
    // hips are read from the thighs, so both rigs agree)
    private const val SEAT_PAD = 0.1
    private const val ROOT_OVER_HIPS = 0.097
    private const val LIE_PAD = 0.11
    private const val KNEE_PAD = 0.055
    private const val FOOT_PAD = 0.02

    fun classify(anim: Anim, hints: Hints = Hints.of(anim)): Requirement =
        classify(listOf(BodyGeometry.frames(anim)), hints)

    fun classify(pair: PairAnim, hints: Hints = Hints.of(pair)): Requirement = classify(listOf(
        BodyGeometry.frames(pair.a, pair.offsetA.x.toDouble(), pair.offsetA.y.toDouble(), pair.offsetA.z.toDouble(), pair.offsetA.yawDeg.toDouble()),
        BodyGeometry.frames(pair.b, pair.offsetB.x.toDouble(), pair.offsetB.y.toDouble(), pair.offsetB.z.toDouble(), pair.offsetB.yawDeg.toDouble()),
    ), hints)

    fun classify(clip: SimsClip, hints: Hints = Hints.of(clip)): Requirement = classify(listOf(BodyGeometry.frames(clip)), hints)

    /** The need of a clip whose actors' bodies are [bodies] (each sampled in the shared frame, body units). */
    fun classify(bodies: List<List<BodyFrame>>, hints: Hints): Requirement {
        val hint = NameHints.read(hints)
        val actors = bodies.map { actor(it, hint) }
        val onFurniture = actors.filter { it.surface !in Requirement.FLOOR_LEVEL && it.surface != Surface.UNKNOWN }
        val surface = when {
            onFurniture.isNotEmpty() -> onFurniture.minBy { RANK.indexOf(it.surface).let { r -> if (r < 0) 99 else r } }.surface
            actors.any { it.surface == Surface.UNKNOWN } -> Surface.UNKNOWN
            actors.any { it.surface == Surface.FLOOR } -> Surface.FLOOR
            else -> Surface.NONE
        }
        val basis = listOfNotNull(
            "geometry".takeIf { bodies.any { it.isNotEmpty() } },
            "names".takeIf { hint.posture != null || hint.surface != null && hint.surfaceFrom == "name" },
            "tuning".takeIf { hint.surfaceFrom == "tuning" },
            "slots".takeIf { hint.surfaceFrom == "slot" },
        ).joinToString("+").ifEmpty { "none" }
        val reason = actors.withIndex().joinToString("; ") { (i, a) -> (if (actors.size > 1) "actor ${'A' + i}: " else "") + a.reason }
        return Requirement(surface, actors, actors.minOfOrNull { it.confidence } ?: 0f, reason, basis)
    }

    private val CORE = setOf("Root", "Pelvis", "Spine1", "Spine2", "Chest", "L_Thigh", "R_Thigh", "L_Calf", "R_Calf")

    private val RANK = listOf(Surface.BED, Surface.COUCH, Surface.CHAIR, Surface.TABLE_OR_COUNTER, Surface.ANY_SEAT)

    /** One moment's reading. */
    private class Reading(
        val posture: Posture, val support: Double, val clarity: Double,
        val sx: Double, val sy: Double, val yaw: Double, val hx: Double, val hy: Double, val fx: Double, val fy: Double,
        val feetDown: Boolean, val usedLegs: Boolean,
    )

    private fun read(f: BodyFrame): Reading? {
        val p = f["Root"] ?: return null
        val neck = f["Neck1"] ?: f["Head"] ?: return null
        val torso = V.sub(neck, p)
        val tl = len(torso).takeIf { it > 1e-6 } ?: return null
        val torsoUp = torso[2] / tl
        fun mid(a: String, b: String) = f[a]?.let { x -> f[b]?.let { y -> V.scale(V.add(x, y), 0.5) } }
        val leftTop = mid("L_UpperArm", "L_Thigh") ?: f["L_UpperArm"] ?: f["L_Thigh"] ?: return null
        val rightTop = mid("R_UpperArm", "R_Thigh") ?: f["R_UpperArm"] ?: f["R_Thigh"] ?: return null
        val left = V.sub(leftTop, rightTop)
        val forward = V.unit(V.cross(left, torso)) ?: return null
        val frontUp = forward[2]
        val legs = listOf("L", "R").mapNotNull { s ->
            val hip = f["${s}_Thigh"]; val knee = f["${s}_Calf"]; val ankle = f["${s}_Foot"]
            if (hip == null || knee == null || ankle == null) null else Triple(hip, knee, ankle)
        }
        if (legs.isEmpty()) return null
        val thighDown = legs.map { (h, k, _) -> V.unit(V.sub(k, h))?.let { -it[2] } ?: 1.0 }.average()
        val kneeBend = legs.map { (h, k, a) ->
            val u = V.unit(V.sub(k, h)); val v = V.unit(V.sub(a, k))
            if (u == null || v == null) 0.0 else Math.toDegrees(acos(V.dot(u, v).coerceIn(-1.0, 1.0)))
        }.average()
        val kneeZ = legs.minOf { it.second[2] }
        val feet = listOfNotNull(f["L_Foot"], f["R_Foot"], f["L_Toe"], f["R_Toe"])
        val footZ = feet.minOf { it[2] }
        val hands = listOfNotNull(f["L_Hand"], f["R_Hand"])
        val handZ = hands.minOfOrNull { it[2] } ?: Double.NaN
        val chest = f["Chest"] ?: f["Spine2"] ?: p
        // the pelvis height as the reference skeleton's Root pivot would be, read from the hip joints
        val hipJoints = legs.map { it.first[2] }.average()
        val hip = hipJoints + ROOT_OVER_HIPS
        val feetMid = V.scale(feet.fold(DoubleArray(3)) { acc, v -> V.add(acc, v) }, 1.0 / feet.size)
        val head = f["Head"] ?: neck
        val facing = Math.toDegrees(atan2(forward[1], forward[0]))
        val axis = Math.toDegrees(atan2(torso[1], torso[0]))
        fun r(posture: Posture, support: Double, clarity: Double, sx: Double = p[0], sy: Double = p[1], feetDown: Boolean = false, legsUsed: Boolean = true) =
            Reading(posture, support, clarity.coerceIn(0.0, 1.0), sx, sy, if (posture.lying) axis else facing,
                head[0], head[1], feetMid[0], feetMid[1], feetDown, legsUsed)

        if (torsoUp < 0.5) {
            val torsoClear = (0.5 - torsoUp) / 0.4
            val legsUnder = thighDown > 0.55 && kneeZ < hip - 0.25 && kneeBend > 45
            if (frontUp < -0.3 && legsUnder) return r(Posture.ALL_FOURS, kneeZ - KNEE_PAD, minOf(torsoClear, (thighDown - 0.55) / 0.3 + 0.3))
            if (hip > 0.7 && thighDown > 0.7 && footZ < 0.15) {
                // bent at the hips over something, feet on the floor: the hands (or the chest) rest on it
                val handsDown = !handZ.isNaN() && handZ < chest[2] - 0.05
                val contact = if (handsDown) handZ - 0.03 else chest[2] - 0.12
                val at = if (handsDown) V.scale(hands.fold(DoubleArray(3)) { a, v -> V.add(a, v) }, 1.0 / hands.size) else chest
                return if (contact > 0.3) r(Posture.LEAN, contact, minOf(torsoClear, (hip - 0.7) / 0.2), at[0], at[1], feetDown = true)
                else r(Posture.STAND, footZ - FOOT_PAD, 0.5, feetDown = true)
            }
            val torsoLow = listOfNotNull(f["Root"], f["Spine2"], f["Chest"]).minOf { it[2] }
            val side = abs(V.unit(left)?.get(2) ?: 0.0)
            val posture = when {
                frontUp > 0.35 -> Posture.LIE_BACK
                frontUp < -0.35 -> Posture.LIE_FRONT
                else -> Posture.LIE_SIDE
            }
            val faceClear = if (posture == Posture.LIE_SIDE) side else (abs(frontUp) - 0.35) / 0.4 + 0.3
            return r(posture, torsoLow - LIE_PAD, minOf(torsoClear, faceClear), legsUsed = false)
        }
        val upClear = (torsoUp - 0.5) / 0.4
        if (thighDown < 0.5) {
            val seat = hipJoints - SEAT_PAD
            val feetDown = footZ < 0.12
            val onTop = seat * PELVIS_METRES > SEAT_MAX && footZ > 0.25
            return r(if (onTop) Posture.SIT_ON_SURFACE else Posture.SIT, seat, minOf(upClear, (0.5 - thighDown) / 0.4 + 0.2), feetDown = feetDown)
        }
        // the pelvis over what the legs rest on (the floor, or a bed under kneeling knees)
        val lowest = minOf(kneeZ, footZ)
        val over = hip - lowest
        if (over > 0.82) return r(Posture.STAND, footZ - FOOT_PAD, minOf(upClear, (over - 0.82) / 0.12 + 0.3), feetDown = true)
        if (kneeZ - lowest < 0.2 && kneeBend > 50) return r(Posture.KNEEL, kneeZ - KNEE_PAD, minOf(upClear, (kneeBend - 50) / 40))
        return r(Posture.CROUCH, footZ - FOOT_PAD, 0.4, feetDown = true)
    }

    /** One actor: the body's posture held for most of the clip, its support, and the hints. */
    private fun actor(frames: List<BodyFrame>, hint: NameHints): ActorRequirement {
        // a clip that moves none of the trunk or legs (hands, face) says nothing about the posture
        val readings = if (frames.none { f -> f.driven.any { it in CORE } }) emptyList() else frames.mapNotNull { read(it) }
        if (readings.isEmpty()) return fromHints(hint)
        val votes = readings.groupingBy { it.posture }.eachCount()
        val posture = votes.maxBy { it.value }.key
        val held = readings.filter { it.posture == posture }
        val share = held.size.toDouble() / readings.size
        val clarity = held.map { it.clarity }.average()
        val legsDriven = frames.any { f -> listOf("L_Thigh", "R_Thigh", "L_Calf", "R_Calf").any { it in f.driven } }
        val legsMatter = held.any { it.usedLegs }
        var conf = 0.35 + 0.6 * share * clarity * (if (legsMatter && !legsDriven) 0.5 else 1.0)
        val support = median(held.map { it.support }).coerceAtLeast(0.0)
        val metres = (support * PELVIS_METRES).toFloat()
        val why = StringBuilder()
        why.append("the body ${describe(posture)}")
        if (share < 0.999) why.append(" for ${(share * 100).toInt()}% of the clip")
        why.append(", ${"%.2f".format(java.util.Locale.ROOT, metres)} m up")
        if (legsMatter && !legsDriven) why.append(" (the clip moves no leg: the legs were not read)")
        var final = posture
        if (posture == Posture.SIT && metres > SEAT_MAX && held.none { it.feetDown }) final = Posture.SIT_ON_SURFACE
        // the name's posture: agreeing raises the confidence; disagreeing lowers it (the body wins)
        hint.posture?.let { hp ->
            if (family(hp) == family(final)) { conf += 0.1; why.append("; the name agrees (\"${hint.postureWord}\")") }
            else { conf -= 0.15; why.append("; the name says \"${hint.postureWord}\" but the body says otherwise: the body wins") }
        }
        val (surface, note, adjust) = surfaceOf(final, metres, held.any { it.feetDown }, hint)
        if (note.isNotEmpty()) why.append("; ").append(note)
        conf += adjust
        val feetOnFloor = final in setOf(Posture.STAND, Posture.LEAN, Posture.CROUCH) ||
            (final == Posture.SIT && held.count { it.feetDown } * 2 >= held.size)
        return ActorRequirement(
            final, surface, metres, support.toFloat(),
            median(held.map { it.sx }).toFloat(), median(held.map { it.sy }).toFloat(), circularMean(held.map { it.yaw }).toFloat(),
            median(held.map { it.hx }).toFloat(), median(held.map { it.hy }).toFloat(),
            median(held.map { it.fx }).toFloat(), median(held.map { it.fy }).toFloat(),
            feetOnFloor, conf.coerceIn(0.05, 0.99).toFloat(), why.toString(),
            // standing, leaning, crouching: the feet carry them, on the floor; a seat bears the hips; the rest lie or kneel on it
            (if (final in setOf(Posture.STAND, Posture.LEAN, Posture.CROUCH)) groundLift(frames)
            else groundLift(frames, support, if (final.seated && metres >= FLOOR_SIT_MAX) HIPS else FLESH.keys)).toFloat(),
        )
    }

    /**
     * Each joint's flesh below it, body units (the reference body's: its ankle joint stands 0.075 over the floor, its toe
     * joint 0.02; a lying back's spine about 0.1; a floor sit's hip joints about 0.1 over the seat): no joint may come
     * nearer the floor than this, or the skin under it is in the floor.
     */
    val FLESH = mapOf(
        "Root" to 0.1, "Pelvis" to 0.1, "Spine1" to 0.09, "Spine2" to 0.09, "Chest" to 0.09, "Neck1" to 0.06, "Head" to 0.08,
        "L_Clavicle" to 0.05, "R_Clavicle" to 0.05, "L_UpperArm" to 0.05, "R_UpperArm" to 0.05, "L_Forearm" to 0.04, "R_Forearm" to 0.04,
        "L_Hand" to 0.02, "R_Hand" to 0.02, "L_Thigh" to 0.1, "R_Thigh" to 0.1, "L_Calf" to 0.05, "R_Calf" to 0.05,
        "L_Foot" to 0.06, "R_Foot" to 0.06, "L_Toe" to 0.015, "R_Toe" to 0.015,
    )

    /** What a seat bears: the hips and thighs (the shins and feet hang in front of it). */
    val HIPS = setOf("Root", "Pelvis", "L_Thigh", "R_Thigh")

    /**
     * How far (body units) [frames] must be raised for none of [joints] to come nearer the support at [support] (body units
     * over the floor; 0: the floor) than its flesh ([FLESH]): a lying body's face or knee in the mattress, a floor sit's hips
     * in the floor. 0 when nothing reaches under.
     */
    fun groundLift(frames: List<BodyFrame>, support: Double = 0.0, joints: Collection<String> = FLESH.keys): Double {
        var lift = 0.0
        for (f in frames) for (j in joints) {
            val pad = FLESH[j] ?: continue
            val p = f[j] ?: continue
            if (pad - (p[2] - support) > lift) lift = pad - (p[2] - support)
        }
        return lift
    }

    /** No readable body: the hints alone, with a low confidence; nothing at all: UNKNOWN. */
    private fun fromHints(hint: NameHints): ActorRequirement {
        val surface = hint.surface
        val posture = hint.posture ?: when (surface) {
            Surface.BED -> Posture.LIE_BACK
            Surface.COUCH, Surface.CHAIR, Surface.ANY_SEAT -> Posture.SIT
            Surface.TABLE_OR_COUNTER -> Posture.LEAN
            else -> Posture.UNKNOWN
        }
        val resolved = surface ?: when {
            posture == Posture.STAND -> Surface.NONE
            posture == Posture.UNKNOWN -> Surface.UNKNOWN
            posture.seated -> Surface.ANY_SEAT
            else -> Surface.FLOOR
        }
        val metres = when {
            posture == Posture.STAND || posture == Posture.UNKNOWN -> 0f
            resolved == Surface.BED -> 0.55f
            resolved == Surface.COUCH || resolved == Surface.CHAIR || resolved == Surface.ANY_SEAT -> 0.45f
            resolved == Surface.TABLE_OR_COUNTER -> 0.85f
            else -> 0f
        }
        val conf = if (posture == Posture.UNKNOWN) 0.05f else if (hint.surface != null && hint.posture != null) 0.45f else 0.3f
        val why = if (posture == Posture.UNKNOWN) "the body can't be read and nothing names a place: not decided"
        else "the body can't be read; from ${hint.surfaceFrom.ifEmpty { "the name" }}: ${hint.words.joinToString(", ")}"
        return ActorRequirement(posture, resolved, metres, metres / PELVIS_METRES, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
            posture == Posture.STAND || (posture.seated && resolved != Surface.TABLE_OR_COUNTER), conf, why)
    }

    /** The surface for a body's [posture] at [metres], then the hint's furniture where it fits; a note and a confidence change. */
    private fun surfaceOf(posture: Posture, metres: Float, feetDown: Boolean, hint: NameHints): Triple<Surface, String, Double> {
        val base = when (posture) {
            Posture.STAND -> if (metres < FLOOR_MAX) Surface.NONE else if (metres < BED_MAX) Surface.BED else Surface.TABLE_OR_COUNTER
            Posture.LEAN -> if (metres < 0.65f) Surface.BED else Surface.TABLE_OR_COUNTER
            Posture.KNEEL, Posture.ALL_FOURS, Posture.CROUCH, Posture.LIE_BACK, Posture.LIE_FRONT, Posture.LIE_SIDE ->
                if (metres < FLOOR_MAX) Surface.FLOOR else if (metres < BED_MAX) Surface.BED else Surface.TABLE_OR_COUNTER
            Posture.SIT -> if (metres < FLOOR_SIT_MAX) Surface.FLOOR else if (metres <= SEAT_MAX || feetDown) Surface.ANY_SEAT else Surface.TABLE_OR_COUNTER
            Posture.SIT_ON_SURFACE -> Surface.TABLE_OR_COUNTER
            Posture.UNKNOWN -> Surface.UNKNOWN
        }
        val h = hint.surface ?: return Triple(base, "", 0.0)
        val from = "the ${hint.surfaceFrom} says ${h.name.lowercase().replace('_', ' ')}"
        if (h == base) return Triple(base, "$from: agrees", 0.05)
        val onGround = base == Surface.NONE || (base == Surface.FLOOR && posture in setOf(Posture.KNEEL, Posture.CROUCH) )
        return when {
            // standing or kneeling on the floor beside the furniture the other actor is on
            onGround && h != Surface.FLOOR -> Triple(base, "$from: this actor is on the floor beside it", 0.0)
            base == Surface.FLOOR && h == Surface.BED -> Triple(Surface.BED, "$from: played on the bed (lifted from floor level)", 0.0)
            base == Surface.FLOOR && h == Surface.COUCH && posture.lying -> Triple(Surface.COUCH, "$from: lying on the couch", 0.0)
            base == Surface.FLOOR && h == Surface.TABLE_OR_COUNTER && posture.lying -> Triple(Surface.TABLE_OR_COUNTER, "$from: on the top", -0.05)
            base == Surface.ANY_SEAT && h in setOf(Surface.CHAIR, Surface.COUCH, Surface.BED) -> Triple(h, "$from: that seat", 0.05)
            base == Surface.ANY_SEAT && h == Surface.TABLE_OR_COUNTER -> Triple(Surface.CHAIR, "$from: a seat at it", 0.0)
            base == Surface.BED && h == Surface.COUCH && metres < 0.6f -> Triple(Surface.COUCH, "$from: on the couch", 0.0)
            base == Surface.BED && h == Surface.TABLE_OR_COUNTER && metres > 0.6f -> Triple(Surface.TABLE_OR_COUNTER, "$from: on the top", 0.0)
            base == Surface.TABLE_OR_COUNTER && h == Surface.BED && metres < 0.85f -> Triple(Surface.BED, "$from: on the bed", -0.05)
            else -> Triple(base, "$from, but the body's height doesn't fit it: the body wins", -0.15)
        }
    }

    private fun family(p: Posture): String = when {
        p.lying -> "lie"; p.seated -> "sit"; else -> p.name
    }

    private fun describe(p: Posture) = when (p) {
        Posture.STAND -> "stands"; Posture.SIT -> "sits"; Posture.SIT_ON_SURFACE -> "sits up on a top, feet off the floor"
        Posture.LIE_BACK -> "lies on its back"; Posture.LIE_FRONT -> "lies on its front"; Posture.LIE_SIDE -> "lies on its side"
        Posture.KNEEL -> "kneels"; Posture.ALL_FOURS -> "is on all fours"; Posture.CROUCH -> "crouches"
        Posture.LEAN -> "leans over, hands on a top"; Posture.UNKNOWN -> "can't be read"
    }

    private fun len(v: DoubleArray) = sqrt(V.dot(v, v))
    private fun median(v: List<Double>): Double { if (v.isEmpty()) return 0.0; val s = v.sorted(); return s[s.size / 2] }
    private fun circularMean(deg: List<Double>): Double {
        var x = 0.0; var y = 0.0
        for (d in deg) { x += kotlin.math.cos(Math.toRadians(d)); y += kotlin.math.sin(Math.toRadians(d)) }
        return Math.toDegrees(atan2(y, x))
    }
}

/**
 * What the names say: the furniture (a tuning location first, then a Sims slot target, then a word of the names) and a
 * posture word. Words are whole tokens (split at punctuation and case changes), so "set_69" names no pose but "69" alone does.
 */
class NameHints(val surface: Surface?, val surfaceFrom: String, val posture: Posture?, val postureWord: String, val words: List<String>) {
    companion object {
        private val FURNITURE = mapOf(
            "bed" to Surface.BED, "doublebed" to Surface.BED, "singlebed" to Surface.BED, "mattress" to Surface.BED,
            "sofa" to Surface.COUCH, "couch" to Surface.COUCH, "loveseat" to Surface.COUCH, "settee" to Surface.COUCH,
            "chair" to Surface.CHAIR, "armchair" to Surface.CHAIR, "stool" to Surface.CHAIR, "bench" to Surface.CHAIR,
            "counter" to Surface.TABLE_OR_COUNTER, "countertop" to Surface.TABLE_OR_COUNTER, "table" to Surface.TABLE_OR_COUNTER,
            "desk" to Surface.TABLE_OR_COUNTER, "bar" to Surface.TABLE_OR_COUNTER,
            "floor" to Surface.FLOOR, "ground" to Surface.FLOOR, "rug" to Surface.FLOOR, "carpet" to Surface.FLOOR,
        )
        private val POSTURES = mapOf(
            "lie" to Posture.LIE_BACK, "lying" to Posture.LIE_BACK, "laying" to Posture.LIE_BACK, "lay" to Posture.LIE_BACK,
            "supine" to Posture.LIE_BACK, "sleep" to Posture.LIE_BACK, "sleeping" to Posture.LIE_BACK, "69" to Posture.LIE_BACK,
            "prone" to Posture.LIE_FRONT, "spoon" to Posture.LIE_SIDE, "spooning" to Posture.LIE_SIDE,
            "sit" to Posture.SIT, "sitting" to Posture.SIT, "seated" to Posture.SIT, "sits" to Posture.SIT,
            "kneel" to Posture.KNEEL, "kneeling" to Posture.KNEEL, "knees" to Posture.KNEEL,
            "allfours" to Posture.ALL_FOURS, "crawl" to Posture.ALL_FOURS,
            "stand" to Posture.STAND, "standing" to Posture.STAND,
            "lean" to Posture.LEAN, "leaning" to Posture.LEAN, "bentover" to Posture.LEAN,
            "squat" to Posture.CROUCH, "squatting" to Posture.CROUCH, "crouch" to Posture.CROUCH, "crouching" to Posture.CROUCH,
        )

        /** Words that number what follows them ("set_69", "stage 2"): a number after one is a count, not a word. */
        private val COUNTERS = setOf("set", "stage", "part", "pose", "v", "version", "no", "num", "take", "clip", "loop")

        /**
         * Words of [s]: lower case, split at anything but letters and digits and at a lower-to-upper case change, a number
         * after a counting word dropped; joined neighbours too ("all fours", "bent over", "double bed").
         */
        fun tokens(s: String): List<String> {
            val spaced = s.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            val raw = spaced.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            val words = raw.filterIndexed { i, w -> !(w.all(Char::isDigit) && i > 0 && raw[i - 1] in COUNTERS) }
            return words + words.zipWithNext { a, b -> a + b }
        }

        fun read(h: Hints): NameHints {
            val words = ArrayList<String>()
            var surface: Surface? = null
            var from = ""
            for (raw in h.locations) {
                val l = raw.trim().uppercase()
                val s = when (l) {
                    "FLOOR" -> Surface.FLOOR
                    "COUNTER", "TABLE", "DESK", "DINING_TABLE", "BAR" -> Surface.TABLE_OR_COUNTER
                    else -> when (PairLocations.normalize(listOf(l)).firstOrNull()) {
                        "BED" -> Surface.BED; "COUCH" -> Surface.COUCH; "CHAIR" -> Surface.CHAIR; "TABLE" -> Surface.TABLE_OR_COUNTER
                        else -> null
                    }
                }
                if (s != null && surface == null) { surface = s; from = "tuning"; words += l.lowercase() }
            }
            if (surface == null) for (n in h.slotNames) {
                val t = tokens(n).firstOrNull { it in FURNITURE } ?: continue
                surface = FURNITURE.getValue(t); from = "slot"; words += t; break
            }
            if (surface == null) for (n in h.names) {
                val t = tokens(n.substringAfterLast(':')).firstOrNull { it in FURNITURE } ?: continue
                surface = FURNITURE.getValue(t); from = "name"; words += t; break
            }
            var posture: Posture? = null
            var word = ""
            for (n in h.names) {
                val t = tokens(n.substringAfterLast(':')).firstOrNull { it in POSTURES } ?: continue
                posture = POSTURES.getValue(t); word = t; words += t; break
            }
            return NameHints(surface, from, posture, word, words)
        }
    }
}

/**
 * The optional "requires" field of a library file (an [Anim]'s or a [PairAnim]'s JSON, or the cache's): files written before
 * it have none and are classified when loaded. Readers that don't know it ignore it (org.json keeps unknown keys).
 */
object RequirementJson {
    const val FIELD = "requires"

    fun toJson(r: Requirement): JSONObject = JSONObject().put("v", r.version).put("surface", r.surface.name)
        .put("confidence", r.confidence.toDouble()).put("reason", r.reason).put("basis", r.basis)
        .put("actors", JSONArray().also { arr ->
            for (a in r.actors) arr.put(JSONObject().put("posture", a.posture.name).put("surface", a.surface.name)
                .put("h", a.height.toDouble()).put("hu", a.heightUnits.toDouble())
                .put("x", a.x.toDouble()).put("y", a.y.toDouble()).put("yaw", a.yaw.toDouble())
                .put("hx", a.headX.toDouble()).put("hy", a.headY.toDouble()).put("fx", a.feetX.toDouble()).put("fy", a.feetY.toDouble())
                .put("feet", a.feetOnFloor).put("confidence", a.confidence.toDouble()).put("reason", a.reason)
                .put("lift", a.liftUnits.toDouble()))
        })

    /** The requirement in [o], or null when it is missing, from another classifier version, or damaged (never throws). */
    fun fromJson(o: JSONObject?): Requirement? = try {
        if (o == null || o.optInt("v", 0) != Requirement.VERSION) null else {
            val arr = o.getJSONArray("actors")
            val actors = List(arr.length()) { i ->
                val a = arr.getJSONObject(i)
                fun f(k: String) = a.optDouble(k, 0.0).toFloat().also { require(it.isFinite()) }
                ActorRequirement(Posture.valueOf(a.getString("posture")), Surface.valueOf(a.getString("surface")),
                    f("h"), f("hu"), f("x"), f("y"), f("yaw"), f("hx"), f("hy"), f("fx"), f("fy"),
                    a.optBoolean("feet", false), f("confidence"), a.optString("reason"), f("lift"))
            }
            if (actors.isEmpty()) null
            else Requirement(Surface.valueOf(o.getString("surface")), actors, o.optDouble("confidence", 0.0).toFloat(),
                o.optString("reason"), o.optString("basis"))
        }
    } catch (e: Exception) { null }

    /** [file] (an animation's or pair's JSON) with [r] in its "requires" field. */
    fun attach(file: JSONObject, r: Requirement): JSONObject = file.put(FIELD, toJson(r))

    /** The stored requirement of [file], else [classify]'s (absent, outdated or damaged: classify at load). */
    fun resolve(file: JSONObject, classify: () -> Requirement): Requirement = fromJson(file.optJSONObject(FIELD)) ?: classify()
}

/**
 * The library's requirements kept beside it without touching its files: dir/requires.cache (JSON), {"v":1,"entries":{id:
 * {"key","requires"}}}. An entry is used while its key (the animation's source and conversion revision) is the same, else it
 * is classified again; a missing or damaged cache is classified again (never throws on read).
 */
class RequirementCache(private val dir: File) {
    private var loaded: MutableMap<String, Pair<String, Requirement>>? = null

    @Synchronized fun get(id: String, key: String, classify: () -> Requirement): Requirement {
        val map = load()
        map[id]?.takeIf { it.first == key }?.let { return it.second }
        val r = classify()
        map[id] = key to r
        save(map)
        return r
    }

    @Synchronized fun forget(id: String) { val map = load(); if (map.remove(id) != null) save(map) }

    fun requirement(a: Anim): Requirement = get(a.id, RequirementStore.keyOf(a)) { RequirementStore.seeded(a) ?: RequirementClassifier.classify(a) }
    fun requirement(p: PairAnim): Requirement = get("pair:" + p.id, RequirementStore.keyOf(p)) { RequirementStore.seeded(p) ?: RequirementClassifier.classify(p) }

    /**
     * The requirements of all of [anims] and [pairs] (by the animation's id, a pair's as "pair:<id>"), those not kept yet
     * classified and the file written once at the end (a library opened for the first time: not once per animation).
     */
    @Synchronized fun all(anims: List<Anim>, pairs: List<PairAnim>): Map<String, Requirement> {
        val map = load()
        val out = HashMap<String, Requirement>()
        var changed = false
        fun one(id: String, key: String, classify: () -> Requirement) {
            val kept = map[id]?.takeIf { it.first == key }?.second
            val r = kept ?: classify().also { map[id] = key to it; changed = true }
            out[id] = r
        }
        for (a in anims) one(a.id, RequirementStore.keyOf(a)) { RequirementStore.seeded(a) ?: RequirementClassifier.classify(a) }
        for (p in pairs) one("pair:" + p.id, RequirementStore.keyOf(p)) { RequirementStore.seeded(p) ?: RequirementClassifier.classify(p) }
        if (changed) save(map)
        return out
    }

    private fun load(): MutableMap<String, Pair<String, Requirement>> {
        loaded?.let { return it }
        val out = LinkedHashMap<String, Pair<String, Requirement>>()
        try {
            val f = File(dir, FILE)
            if (f.isFile) {
                val o = JSONObject(f.readText())
                val e = o.optJSONObject("entries")
                if (o.optInt("v", 0) == 1 && e != null) for (k in e.keys()) {
                    val row = e.optJSONObject(k) ?: continue
                    val r = RequirementJson.fromJson(row.optJSONObject(RequirementJson.FIELD)) ?: continue
                    out[k] = row.optString("key") to r
                }
            }
        } catch (x: Exception) { out.clear() }
        loaded = out
        return out
    }

    private fun save(map: Map<String, Pair<String, Requirement>>) {
        try {
            dir.mkdirs()
            val e = JSONObject()
            for ((k, v) in map) e.put(k, JSONObject().put("key", v.first).put(RequirementJson.FIELD, RequirementJson.toJson(v.second)))
            val target = File(dir, FILE)
            val tmp = File(dir, "$FILE.tmp")
            tmp.writeText(JSONObject().put("v", 1).put("entries", e).toString())
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (x: Exception) {
            // a cache: it is made again next time
        }
    }

    /** Not ".json": the library reads every other .json in its folder as an animation and would list this one as damaged. */
    companion object { const val FILE = "requires.cache" }
}

/**
 * Where the live scene gets an animation's requirement: the library's [RequirementCache] beside its files once the library
 * is opened ([AnimLibrary] sets [cache]), else classified in memory. The importer [seed]s what it classified with the Sims
 * clip's own names and slot targets ([Hints.of] a SimsClip, which the saved animation no longer has), by the animation's
 * source and conversion revision: the library's cache takes the seed when it first meets that animation (in this run of
 * the app; after a restart an animation not met yet is classified from its own names). Thread-safe.
 */
object RequirementStore {
    @Volatile var cache: RequirementCache? = null
    private val seeds = java.util.concurrent.ConcurrentHashMap<String, Requirement>()
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Requirement>()

    fun keyOf(a: Anim) = "${a.source}#${a.conversionRevision}"
    fun keyOf(p: PairAnim) = "${p.source}#${p.a.conversionRevision}"

    /** [r] is what an imported animation with [key] ([keyOf]) needs. */
    fun seed(key: String, r: Requirement) { seeds[key] = r }
    fun seeded(a: Anim): Requirement? = seeds[keyOf(a)]
    fun seeded(p: PairAnim): Requirement? = seeds[keyOf(p)]

    fun of(a: Anim): Requirement = cache?.requirement(a)
        ?: memory.getOrPut("a:" + keyOf(a) + ":" + a.id) { seeded(a) ?: RequirementClassifier.classify(a) }

    fun of(p: PairAnim): Requirement = cache?.requirement(p)
        ?: memory.getOrPut("p:" + keyOf(p) + ":" + p.id) { seeded(p) ?: RequirementClassifier.classify(p) }

    /** Both lists at once (the cache's file written once); ids as [RequirementCache.all]. */
    fun all(anims: List<Anim>, pairs: List<PairAnim>): Map<String, Requirement> = cache?.all(anims, pairs)
        ?: HashMap<String, Requirement>().also { out ->
            for (a in anims) out[a.id] = of(a)
            for (p in pairs) out["pair:" + p.id] = of(p)
        }

    /** The importer's hints: the converted animation's own and the Sims clips' (their names, slot targets). */
    fun hints(a: Anim, clip: SimsClip): Hints {
        val own = Hints.of(a); val sims = Hints.of(clip)
        return Hints(own.names + sims.names, own.locations + sims.locations, own.slotNames + sims.slotNames)
    }

    fun hints(p: PairAnim, a: SimsClip, b: SimsClip): Hints {
        val own = Hints.of(p); val x = Hints.of(a); val y = Hints.of(b)
        return Hints(own.names + x.names + y.names, own.locations, x.slotNames + y.slotNames)
    }
}
