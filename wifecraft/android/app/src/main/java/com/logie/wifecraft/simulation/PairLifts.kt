package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyFrame
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.FleshProfile
import com.logie.wifecraft.anim.Posture
import com.logie.wifecraft.anim.Requirement
import kotlin.math.min

/**
 * Stages made in water: a pool, a hot tub, a hot spring, a bath (the audit: Anarcis' poolside and hot spring stages, authored
 * up to 2 m under the floor, the water's surface being the Sims floor). The scenes have no water to put them in (no pool or
 * hot tub prop, FurnitureKind), so they are played on the floor or the bed they are put on: the pair raised out of it
 * together, by the more either body needs ([PairLift.lifts]: neither stays under the floor, and the two keep their places
 * against each other, as one piece). A stage where one body is in the water and the other out of it (a body on the pool's
 * edge, another standing in the pool: their lifts more than [APART] apart) can't be shown so without water: it [needsWater],
 * and the NPCs never pick it by themselves (SceneSimulation.pick); played by hand it is raised the same way. Pure.
 */
object PairWater {
    /** A role whose lift is this deep (body units, about 43 cm) was authored under the floor: in water. */
    const val DEPTH = 0.45f
    /** A role this deep (about 19 cm) in a stage whose name or place says water ([named]). */
    const val NAMED_DEPTH = 0.2f
    /** The two roles' lifts this far apart (body units, about 28 cm): one in the water, one out of it ([needsWater]). */
    const val APART = 0.3f

    private val WORDS = Regex("pool|hot ?tub|jacuzzi|hot ?spring|under ?water|swim|bath ?tub|in the bath|in the water|soak", RegexOption.IGNORE_CASE)

    /** Whether [p]'s name or places say water (a pool, hot tub, hot spring, bath). */
    fun named(p: PartnerPair): Boolean = WORDS.containsMatchIn(p.name) || p.locations.any { WORDS.containsMatchIn(it.replace('_', ' ')) }

    /** Whether [p] (needing [r]) was made in water: a role deeper than [DEPTH], or than [NAMED_DEPTH] where it is [named]. */
    fun of(r: Requirement, p: PartnerPair): Boolean {
        val deep = r.actors.maxOfOrNull { it.liftUnits } ?: return false
        return deep >= DEPTH || (deep >= NAMED_DEPTH && named(p))
    }

    /** Whether [p] (needing [r]) is made in water with one body in it and one out ([APART]): not offered by the NPCs. */
    fun needsWater(r: Requirement, p: PartnerPair): Boolean {
        if (r.actors.size < 2 || !of(r, p)) return false
        return r.actors.maxOf { it.liftUnits } - r.actors.minOf { it.liftUnits } > APART
    }
}

/**
 * The hips of a pair's two bodies kept at the height their author put them against each other, by each body's own legs.
 * The clips are made for one body; played on two measured ones, each body's hip joints stand as high as its own legs make
 * them (the human woman's 4 cm higher than the man's standing, more for a dwarf or a gnome), and a pair made to meet at the
 * hips meets 4 to 10 cm apart. Where neither body stands on its feet (kneeling, lying, sitting on one support: the feet
 * carry nobody, so a body may come up off its knees or back a little without anything showing), the one whose hips come
 * too low against the other's is raised by the difference, at most [MAX_RAISE]; standing pairs keep their feet on the
 * floor and their hips as their legs make them. Pure.
 */
object PairHeight {
    /** The most a body is raised for its hips (yards, about 4 cm: the audit's sink tolerance, a knee or back just off its support). */
    const val MAX_RAISE = 0.044f

    /** The median height of [frames]' hip joints (between the two thigh joints; body units); null without them. */
    fun hipUnits(frames: List<BodyFrame>): Double? {
        val z = frames.mapNotNull { f -> val l = f["L_Thigh"]; val r = f["R_Thigh"]; if (l == null || r == null) null else (l[2] + r[2]) / 2 }
        if (z.isEmpty()) return null
        val s = z.sorted()
        return s[s.size / 2]
    }

    /**
     * One role's hips: [measured] on the body's own skeleton and [authored] on the reference's (both yards over the clip's
     * origin, before any lift).
     */
    class Hips(val measured: Float, val authored: Float)

    /**
     * [anim] (its slot [z] high, reference units) on a body with [flesh] drawn [unit] yards to its standing pelvis (its own
     * measured pelvis), against the same clip on the reference body at [authoredUnit] (the body's size: HumanScale's); null
     * when the clip has no hips.
     */
    fun hips(anim: Anim, z: Float, flesh: FleshProfile, unit: Float, authoredUnit: Float): Hips? {
        val own = hipUnits(BodyGeometry.frames(anim, z = z.toDouble(), flesh = flesh.takeIf { it.measured })) ?: return null
        val ref = if (flesh.measured) hipUnits(BodyGeometry.frames(anim, z = z.toDouble())) ?: return null else own
        return Hips((own * unit).toFloat(), (ref * authoredUnit).toFloat())
    }

    /** Whether [x] stands on its feet (standing, leaning, crouching, a sit with the feet down) or can't be read. */
    fun onFeet(x: com.logie.wifecraft.anim.ActorRequirement) = x.feetOnFloor || x.posture == Posture.STAND || x.posture == Posture.LEAN ||
        x.posture == Posture.CROUCH || x.posture == Posture.UNKNOWN

    /** Whether [r]'s two roles may be matched: neither on its feet ([onFeet]), both on one support. */
    fun eligible(r: Requirement): Boolean {
        if (r.actors.size != 2) return false
        val (a, b) = r.actors
        if (onFeet(a) || onFeet(b)) return false
        return (a.surface in Requirement.FLOOR_LEVEL) == (b.surface in Requirement.FLOOR_LEVEL)
    }

    /**
     * What each role is raised by (yards, the first's then the second's) for its hips, given [lifts] (the two as placed),
     * [authoredLifts] (the two as the reference bodies would be lifted) and each role's [a], [b] hips: the vertical error of
     * the second's hips against the first's, taken off by raising the lower one by it (at most [MAX_RAISE]); never lowered.
     */
    fun raise(lifts: FloatArray, authoredLifts: FloatArray, a: Hips, b: Hips): FloatArray {
        val app = (b.measured + lifts[1]) - (a.measured + lifts[0])
        val intended = (b.authored + authoredLifts[1]) - (a.authored + authoredLifts[0])
        val e = app - intended
        return if (e > 0f) floatArrayOf(min(e, MAX_RAISE), 0f) else floatArrayOf(0f, min(-e, MAX_RAISE))
    }
}

/**
 * Where a pair's two roles are raised (SceneSimulation.roleLifts and the audit, the same steps): each role's own lift for
 * its own flesh ([com.logie.wifecraft.anim.FleshLift]), shared when the bodies meet or the stage was made in water
 * ([PairLift.lifts], [PairWater]; a body on top of the other is raised with the one under it: [under], [underLift]), then the hips
 * matched ([PairHeight]) where neither stands. Pure.
 */
object PairLifts {
    /** A support this much higher than the other's (body units, about 11 cm) is on top of the other body ([under]). */
    const val ON_TOP = 0.1f

    /**
     * The role lying or kneeling under the other in [r] (0 or 1), or -1: neither on its feet ([PairHeight.onFeet]) and one's
     * support more than [ON_TOP] over the other's (a body lying or kneeling on the other's, not on the bed). The pair is
     * raised by the one under (the one on the surface); the one on top rests on it as its author put it, and its own lift
     * (read against a support that is the other body, its arms reaching down past it to the bed) raised both by up to 38 cm.
     */
    fun under(r: Requirement): Int {
        if (r.actors.size != 2) return -1
        val (a, b) = r.actors
        if (PairHeight.onFeet(a) || PairHeight.onFeet(b)) return -1
        return when {
            a.heightUnits + ON_TOP < b.heightUnits -> 0
            b.heightUnits + ON_TOP < a.heightUnits -> 1
            else -> -1
        }
    }

    /**
     * How far (world units) the pair is raised when [u] is under the other ([under]): by the one under's own lift ([own]),
     * or, where the one on top's own flesh reaches the surface past its support (its lift past the one under's more than its
     * support is over the other's, [units]: each role's world units per body unit; read on top, lying across the other onto
     * the bed), by as much more as keeps that flesh out of the surface, no more.
     *
     * Continuous in both bodies' flesh: before, a guard switched the pair between the one under's lift and the one on top's
     * whole own lift (the two as side by side) at that threshold, so a body 3 cm slimmer at the chest (a woman in Eve under a
     * man on all fours) moved the pair 5 to 14 cm, up in one stage and down in another (the audit's lying stages).
     */
    fun underLift(r: Requirement, u: Int, own: FloatArray, units: FloatArray): Float {
        val top = 1 - u
        val room = r.actors[top].heightUnits * units[top] - r.actors[u].heightUnits * units[u]
        return maxOf(own[u], own[top] - room)
    }

    /** The roles' lifts (world units, the pair's actor order), whether the stage was made in water, and the hips' part of the lifts. */
    class Placement(val lifts: FloatArray, val water: Boolean, val hipRaise: FloatArray)

    /**
     * [own] each role's lift for its own flesh (world units; on furniture the difference from the fit's), [authored] each
     * role's as the reference bodies would have it (the same on the floor for unmeasured bodies; 0 on furniture), [touching]
     * ([PairSpacing.touching]), [hipsA], [hipsB] each role's hips ([PairHeight.hips]; null: not matched).
     */
    fun of(r: Requirement, p: PartnerPair, own: FloatArray, authored: FloatArray, touching: Boolean, hipsA: PairHeight.Hips?, hipsB: PairHeight.Hips?,
           units: FloatArray): Placement {
        val water = PairWater.of(r, p)
        // who is under whom by the two supports alone (the stage as authored: the same for every body), the lift continuous
        val under = if (touching && !water) under(r) else -1
        val lifts = if (under >= 0) underLift(r, under, own, units).let { floatArrayOf(it, it) } else PairLift.lifts(own[0], own[1], touching, water)
        val gen = if (under >= 0) underLift(r, under, authored, units).let { floatArrayOf(it, it) } else PairLift.lifts(authored[0], authored[1], touching, water)
        val raise = if (hipsA != null && hipsB != null && touching && !water && PairHeight.eligible(r)) PairHeight.raise(lifts, gen, hipsA, hipsB)
            else floatArrayOf(0f, 0f)
        return Placement(floatArrayOf(lifts[0] + raise[0], lifts[1] + raise[1]), water, raise)
    }
}
