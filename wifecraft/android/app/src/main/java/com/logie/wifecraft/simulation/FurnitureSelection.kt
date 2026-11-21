package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyFrame
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.Hints
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.anim.Posture
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.RequirementClassifier
import kotlin.math.hypot
import kotlin.random.Random

/**
 * An animation furniture can offer: [id] (the playback's key: a pair's id, a clip's id), [name], what it needs
 * ([requirement], one actor need per role in role order) and the [pair] it plays when it is a partner animation.
 */
data class FurnitureAnimation(val id: String, val name: String, val requirement: Requirement, val pair: PartnerPair? = null) {
    val participants: Int get() = requirement.actors.size
    /** The posture of the actor on the furniture: the group the Play animation window lists it under. */
    val posture: Posture get() = requirement.actors.getOrNull(requirement.primary)?.posture ?: Posture.UNKNOWN

    companion object {
        /**
         * A partner pair as the simulation has it, classified from its two clips at their slots and its names and locations.
         * Its slots are at human scale across the floor (yards: [PartnerSlot.human]); the clips' frame is the reference
         * body's, so they are taken back by [HumanScale.PAIR] (up and down a slot is the clip's own already).
         */
        fun of(p: PartnerPair): FurnitureAnimation = FurnitureAnimation(p.id, p.name, RequirementClassifier.classify(bodies(p),
            Hints(listOf(p.name, p.a.sourceName, p.b.sourceName), p.locations)), p)

        /** [p]'s two bodies over the clip at their slots, in the pair's shared frame (body units), as [of] reads them. */
        fun bodies(p: PartnerPair): List<List<BodyFrame>> = listOf(frames(p.a, p.slotA), frames(p.b, p.slotB))

        /** How far [p] may be lowered for a floor play (body units: [FurnitureAvailability.floorDrop]). */
        fun floorDrop(p: PartnerPair): Float = FurnitureAvailability.floorDrop(bodies(p))

        private fun frames(a: Anim, s: PartnerSlot) = BodyGeometry.frames(a, (s.x / HumanScale.PAIR).toDouble(),
            (s.y / HumanScale.PAIR).toDouble(), s.z.toDouble(), s.yawDeg.toDouble())

        /** A single clip. */
        fun of(a: Anim): FurnitureAnimation = FurnitureAnimation(a.id, a.name, RequirementClassifier.classify(a))
    }
}

/** Someone near the furniture who could take part: [id] and where they stand (world). */
data class NearbyNpc(val id: String, val x: Float, val y: Float)

/** What [FurnitureSelection.pickFor] chose: the [animation], where it plays ([fit]) and who plays each role ([cast], role order). */
data class FurniturePick(val animation: FurnitureAnimation, val fit: FurnitureFit, val cast: List<String>)

/** An entry of the furniture's Play animation window: the [animation] and where it would play. */
data class FurnitureEntry(val animation: FurnitureAnimation, val fit: FurnitureFit)

/**
 * Which animations a piece of furniture allows and who plays them: only those whose every actor lands on something that
 * takes their posture at their height ([FurnitureFit.fit]). Pure.
 */
object FurnitureSelection {
    /** The order the window's groups come in. */
    private val GROUPS = listOf("Lying", "Sitting", "Kneeling", "On all fours", "Leaning", "Crouching", "Standing", "Unsorted")

    /**
     * The animations [f] allows (where they fit, else where they nearly fit: [fitOrNear]), grouped by the posture of the actor
     * on it (Lying, Sitting...), each group by name.
     */
    fun listFor(f: Furniture, library: List<FurnitureAnimation>, bodyUnit: Float = FurnitureFit.DEFAULT_BODY_UNIT): Map<String, List<FurnitureEntry>> {
        val entries = library.mapNotNull { a -> fitOrNear(a.requirement, f, bodyUnit)?.let { FurnitureEntry(a, it) } }
        val grouped = entries.groupBy { it.animation.posture.group }
        val out = LinkedHashMap<String, List<FurnitureEntry>>()
        for (g in GROUPS + grouped.keys.filter { it !in GROUPS }) grouped[g]?.let { list -> out[g] = list.sortedBy { it.animation.name.lowercase() } }
        return out
    }

    /**
     * A random animation [f] allows that [nearby] people can cast (enough of them; [busy] ids left out), and who plays which
     * role: the cast whose walk to their places is shortest. Null when none can.
     */
    fun pickFor(f: Furniture, nearby: List<NearbyNpc>, library: List<FurnitureAnimation>, random: Random,
                bodyUnit: Float = FurnitureFit.DEFAULT_BODY_UNIT, busy: Set<String> = emptySet()): FurniturePick? {
        val free = nearby.filter { it.id !in busy }
        val usable = library.filter { it.participants in 1..free.size }
            .mapNotNull { a -> fitOrNear(a.requirement, f, bodyUnit)?.let { a to it } }
        if (usable.isEmpty()) return null
        val (animation, fit) = usable[random.nextInt(usable.size)]
        return FurniturePick(animation, fit, cast(fit, free) ?: return null)
    }

    /**
     * Where [r] plays on [f]: where it fits ([FurnitureFit.fit]), else, needing furniture, where it nearly fits (relaxed: a bed
     * taller than the clip's, a couch for a bed), as the scene plays it ([FurnitureAvailability]); null: neither.
     */
    fun fitOrNear(r: Requirement, f: Furniture, bodyUnit: Float = FurnitureFit.DEFAULT_BODY_UNIT): FurnitureFit? =
        FurnitureFit.fit(r, f, bodyUnit) ?: if (r.surface in Requirement.FLOOR_LEVEL) null else FurnitureFit.fit(r, f, bodyUnit, relaxed = true)

    /** The people (one per role) whose walks to [fit]'s positions add up shortest; null without enough. */
    fun cast(fit: FurnitureFit, people: List<NearbyNpc>): List<String>? {
        val n = fit.positions.size
        if (people.size < n) return null
        // the nearest few to the furniture are enough to choose from
        val near = people.sortedBy { hypot(it.x - fit.x, it.y - fit.y) }.take(maxOf(n, 6))
        var best: List<NearbyNpc>? = null
        var bestCost = Float.MAX_VALUE
        fun search(chosen: MutableList<NearbyNpc>, cost: Float) {
            if (cost >= bestCost) return
            if (chosen.size == n) { best = chosen.toList(); bestCost = cost; return }
            val role = fit.positions[chosen.size]
            for (p in near) {
                if (p in chosen) continue
                chosen += p
                search(chosen, cost + hypot(p.x - role.x, p.y - role.y))
                chosen.removeAt(chosen.size - 1)
            }
        }
        search(ArrayList(), 0f)
        return best?.map { it.id }
    }
}
