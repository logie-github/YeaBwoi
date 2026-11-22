package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Build mode's per-actor moves (the live scene's build mode: ui/BuildModeUi.kt). A selected NPC that plays a pair animation
 * can be moved as the pair (its shared origin: [MoveTarget.BOTH]), alone ([MoveTarget.THIS]) or its partner alone
 * ([MoveTarget.PARTNER]). One actor alone is the pair's placement plus that role's own nudge (the [RoleDelta] the simulation
 * already lays on a role: [SceneSimulation.movePlaying]'s `extra`), so both clips keep playing on the one clock and nothing
 * snaps back; the maths here turns the gizmo's pose for one actor into that nudge, and the other way. Everything is in the
 * simulation's frame (yards, radians); the pair's frame is x its front, y its left (turned by the heading), z up. Pure.
 */
enum class MoveTarget(val label: String) {
    BOTH("Both"), THIS("This NPC only"), PARTNER("Partner only");

    /** The role (0 the lead's, 1 the other's) this target moves for the selected NPC at role [own]; -1: the shared origin. */
    fun role(own: Int): Int = when (this) { BOTH -> -1; THIS -> own; PARTNER -> 1 - own }
}

object BuildActors {
    /** The targets offered to the selected NPC: all three in a pair, just the NPC alone. */
    fun targets(pair: PlayingPair?, selected: String): List<MoveTarget> =
        if (pair != null && pair.other != null && pair.roleOf(selected) >= 0) MoveTarget.entries else listOf(MoveTarget.BOTH)

    /** Where role [role] is drawn: its place and facing in the world (z: over the pair's origin, by the whole correction). */
    fun poseOf(anchor: Vec3, heading: Float, role: PlaybackRole, fixed: RoleDelta, extra: RoleDelta): BuildPose {
        val total = fixed + extra
        val placed = total.applyTo(role)
        val c = cos(heading); val s = sin(heading)
        val ox = placed.offset.x; val oy = placed.offset.y
        val at = Vec3(anchor.x + ox * c - oy * s, anchor.y + ox * s + oy * c, anchor.z + total.dz)
        return BuildPose.of(at, heading + placed.facing)
    }

    /**
     * The nudge for role [role] that puts it at [target] (a world pose) when the pair stands at [anchor] turned [heading]
     * and the correction the role already carries is [fixed] (remembered placements, a creature's): the target's place in the
     * pair's frame less the role's computed place less [fixed], and its turn less the role's and the pair's.
     */
    fun extraFor(anchor: Vec3, heading: Float, role: PlaybackRole, fixed: RoleDelta, target: BuildPose): RoleDelta {
        val c = cos(heading); val s = sin(heading)
        val dx = target.x - anchor.x; val dy = target.y - anchor.y
        val lx = dx * c + dy * s
        val ly = -dx * s + dy * c
        val faceDeg = Math.toDegrees((heading + role.facing).toDouble()).toFloat()
        val total = RoleDelta(lx - role.offset.x, ly - role.offset.y, target.z - anchor.z, ItemFrame.wrapDeg(target.yawDeg - faceDeg))
        return total - fixed
    }

    /** Both clips keep their own corrections: [extra] with role [index] set to [d] (the list grown to two). */
    fun withExtra(extra: List<RoleDelta>, index: Int, d: RoleDelta): List<RoleDelta> {
        val out = ArrayList<RoleDelta>(extra)
        while (out.size < 2) out += RoleDelta.ZERO
        if (index in 0..1) out[index] = d
        return out
    }

    /** The roles whose placement differs from the computed one now (what Remember offers first): moved alone, or with the pair. */
    fun changedRoles(p: PlayingPair): Set<Int> {
        val out = HashSet<Int>()
        for ((i, role) in p.roles.withIndex()) {
            if (i > 1) break
            val d = RaceOffsets.deltaFor(p.fixed.getOrElse(i) { RoleDelta.ZERO }.applyTo(role), p.baseAnchor, p.baseHeading, p.anchor, p.heading,
                p.extra.getOrElse(i) { RoleDelta.ZERO })
            if (!d.none) out += i
        }
        return out
    }

    /**
     * How a move since the pair's computed placement is saved: [PairMove.target] "both" when only the shared origin moved,
     * "A" or "B" when only that role was nudged, "mixed" when both kinds are there.
     */
    fun moveKind(p: PlayingPair): String {
        val origin = p.baseAnchor != p.anchor || p.baseHeading != p.heading
        val nudged = p.extra.withIndex().filter { !it.value.none }.map { it.index }
        return when {
            nudged.isEmpty() -> "both"
            origin -> "mixed"
            nudged == listOf(0) -> "A"
            nudged == listOf(1) -> "B"
            else -> "mixed"
        }
    }
}
