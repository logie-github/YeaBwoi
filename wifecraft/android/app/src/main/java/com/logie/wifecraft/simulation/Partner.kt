package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.AnimationChoice
import com.logie.wifecraft.anim.ClipType
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.anim.PERFORMANCE_SCALE
import com.logie.wifecraft.anim.PairAnim
import com.logie.wifecraft.story.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The partner activity's inputs and its pure maths. A [PartnerPair] is one two-person animation as the simulation takes
 * it (the library's pair, adapted by the live screen); [PartnerEmote] the Warcraft fallback (one game emote both play,
 * facing each other) when no imported pair can run; [ModelEmotes] says which game animations an NPC's model has;
 * [PartnerSoundSink] plays a pair's sound events. Pure JVM.
 */

/** An actor's start slot from the pair's shared origin: WoW axes (X forward, Y left, Z up), yards; [yawDeg] its facing. */
data class PartnerSlot(val x: Float, val y: Float, val z: Float, val yawDeg: Float) {
    companion object {
        /**
         * A pair's slot as the live scene stands it, its NPCs at human scale (anim/HumanScale): [x] and [y], in the clips'
         * reference units, taken by [HumanScale.PAIR] as the engine takes the clips' root motion across the floor, so the two
         * stand where their clips put them; [z] stays the clip's (lifted into its Root track, which each body's own pelvis
         * scales).
         */
        fun human(x: Float, y: Float, z: Float, yawDeg: Float) = PartnerSlot(x * HumanScale.PAIR, y * HumanScale.PAIR, z, yawDeg)
    }
}

/** A pair's sound event: [name] played for actor [actor] (0 the initiator, 1 the partner) at [timeSec] on the pair's clock. */
data class PartnerSound(val timeSec: Float, val name: String, val actor: Int)

/**
 * One two-person animation: [a] is played by the initiator, [b] by the partner, from their slots [slotA] and [slotB];
 * [loops]: it repeats; [locations]: where it may play (SOFA, DOUBLE_BED, CHAIR...: [PartnerPlaces]; empty or unknown: a
 * free spot on the floor); [sounds]: its sound events.
 */
class PartnerPair(
    val id: String,
    val name: String,
    val a: Anim,
    val b: Anim,
    val slotA: PartnerSlot,
    val slotB: PartnerSlot,
    val loops: Boolean,
    val locations: Set<String> = emptySet(),
    val sounds: List<PartnerSound> = emptyList(),
) {
    /** One cycle (both clips are made the same length; the longer wins if not). */
    val duration: Float = max(a.duration, b.duration).let { if (it.isFinite() && it > 0f) it else 0f }

    override fun toString() = "PartnerPair($id, $name)"

    companion object {
        /** The library's pair as the simulation takes it (its slots at human scale: [PartnerSlot.human]). */
        fun of(p: PairAnim) = PartnerPair(
            p.id, p.name, p.a, p.b,
            PartnerSlot.human(p.offsetA.x, p.offsetA.y, p.offsetA.z, p.offsetA.yawDeg),
            PartnerSlot.human(p.offsetB.x, p.offsetB.y, p.offsetB.z, p.offsetB.yawDeg),
            p.loops, p.locations, p.sounds.map { PartnerSound(it.timeSec, it.name, it.actor) },
        )

        /** The library's enabled pairs ([com.logie.wifecraft.anim.AnimLibrary.pairs]), for the live scene. */
        fun enabled(all: List<PairAnim>): List<PartnerPair> = all.filter { it.enabled }.map(::of)

        /**
         * An author's two-actor performance (the animation browser's [choice], made from the importer's
         * ImportedPerformances) as a pair the NPCs can pick by themselves; null when it is not a playable two-role one or
         * is a library pair's own entry (those come through [of]). Offsets are scaled as the browser's orders scale them, then
         * to human scale ([PartnerSlot.human]).
         */
        fun of(choice: AnimationChoice): PartnerPair? {
            if (choice.participantCount != 2 || !choice.playable || choice.id.startsWith(PairAnim.CHOICE_PREFIX)) return null
            val a = choice.clips[0].forPlayback() ?: return null
            val b = choice.clips[1].forPlayback() ?: return null
            val scale = if (choice.yards) 1f else PERFORMANCE_SCALE
            val (ra, rb) = choice.roles
            fun slot(r: com.logie.wifecraft.anim.PerformanceRole) =
                PartnerSlot.human(r.offset.x * scale, r.offset.y * scale, r.offset.z * scale, Math.toDegrees(r.facing.toDouble()).toFloat())
            val loops = choice.clips.any { it.inspection.type == ClipType.LOOP }
            val place = choice.setting.trim().uppercase().replace(' ', '_')
            return PartnerPair(choice.id, choice.name, a, b, slot(ra), slot(rb), loops,
                if (place.isEmpty()) emptySet() else setOf(place))
        }
    }
}

/** Off, Sometimes, Often: the chance an idle NPC's decision is to go to a partner (the scene setup's Partner row). */
enum class PartnerFrequency(val label: String, val chance: Float) {
    OFF("Off", 0f),
    SOMETIMES("Sometimes", 0.25f),
    OFTEN("Often", 0.6f);

    companion object {
        fun of(name: String?): PartnerFrequency = entries.firstOrNull { it.name == name } ?: SOMETIMES
    }
}

/** A game emote two NPCs can play together, facing each other ([anim]: AnimationData id; [loop]: held, not once). */
class PartnerEmote(val name: String, val anim: Int, val loop: Boolean)

/** Whether the model of NPC [actorId] has game animation [anim]. */
fun interface ModelEmotes {
    fun has(actorId: String, anim: Int): Boolean

    companion object {
        /** Every look fetches the standard emotes (data/Emotes.all): all of [PartnerEmotes.ALL] are taken as there. */
        val STANDARD = ModelEmotes { _, anim -> PartnerEmotes.ALL.any { it.anim == anim } }
    }
}

/** The mutual emotes of the fallback: greetings and play, none needing an object (no drink, no book, no chair). */
object PartnerEmotes {
    val ALL = listOf(
        PartnerEmote("Wave", 67, false),
        PartnerEmote("Cheer", 68, false),
        PartnerEmote("Dance", 69, true),
        PartnerEmote("Laugh", 70, false),
        PartnerEmote("Bow", 66, false),
        PartnerEmote("Kiss", 76, false),
        PartnerEmote("Applaud", 80, false),
        PartnerEmote("Salute", 113, false),
    )

    /** Where each actor stands for an emote: 1.2 yards apart, facing each other. */
    val SLOT_A = PartnerSlot(-0.6f, 0f, 0f, 0f)
    val SLOT_B = PartnerSlot(0.6f, 0f, 0f, 180f)

    /** The emotes both [a]'s and [b]'s models have, into [out] (cleared; nothing allocated). */
    fun mutual(a: String, b: String, source: ModelEmotes, out: MutableList<PartnerEmote>): MutableList<PartnerEmote> {
        out.clear()
        for (e in ALL) if (source.has(a, e.anim) && source.has(b, e.anim)) out.add(e)
        return out
    }
}

/** A pair's location names (WickedWhims-style categories) to the scene's furniture. */
object PartnerPlaces {
    /** The furniture [location] needs, or null (a free spot: floor, standing, or a name this scene has no furniture for). */
    fun furniture(location: String): SceneObject? = when (location.trim().uppercase()) {
        "SOFA", "LOVESEAT", "COUCH", "SETTEE" -> SceneObject.COUCH
        "DOUBLE_BED", "SINGLE_BED", "BED", "KING_BED" -> SceneObject.BED
        "CHAIR", "DINING_CHAIR", "ARMCHAIR", "LIVING_CHAIR", "DESK_CHAIR" -> SceneObject.CHAIR
        "TABLE", "DINING_TABLE", "DESK", "COUNTER" -> SceneObject.TABLE
        else -> null
    }

    /** Whether [pair] can run on the floor (no location, or one that names no furniture). */
    fun free(pair: PartnerPair): Boolean = pair.locations.isEmpty() || pair.locations.any { furniture(it) == null }
}

/** The shared origin's maths: slots turned by the pair's yaw (WoW axes, Z up, counter-clockwise from +X). */
object PartnerMath {
    private const val DEG = 0.017453292f

    /** An actor's world slot: [ox], [oy], [oz] + [slot] turned [yaw] radians; out = x, y, z, facing (radians). */
    fun place(ox: Float, oy: Float, oz: Float, yaw: Float, slot: PartnerSlot, out: FloatArray) {
        val c = cos(yaw); val s = sin(yaw)
        out[0] = ox + slot.x * c - slot.y * s
        out[1] = oy + slot.x * s + slot.y * c
        out[2] = oz + slot.z
        out[3] = yaw + slot.yawDeg * DEG
    }

    /**
     * The pair's yaw that lines the slots up with where the two stand (a at [ax], [ay]; b at [bx], [by]): slot A to slot B
     * turned onto a to b, so each walks only a little to its slot. Slots on one spot: a's facing, less its slot's yaw.
     */
    fun yaw(ax: Float, ay: Float, bx: Float, by: Float, slotA: PartnerSlot, slotB: PartnerSlot, aFacing: Float): Float {
        val ex = slotB.x - slotA.x; val ey = slotB.y - slotA.y
        val dx = bx - ax; val dy = by - ay
        if (ex * ex + ey * ey < 1e-4f || dx * dx + dy * dy < 1e-4f) return aFacing - slotA.yawDeg * DEG
        return atan2(dy, dx) - atan2(ey, ex)
    }
}

/**
 * Two bodies of different sizes in one pair (a gnome keeps its own size: anim/HumanScale.pairScale). A pair's slots are made
 * for two human-sized bodies meeting at the pair's contact point, taken as the slots' midpoint; a body [s] times a human's
 * (its clip's reach and, in the engine, its root motion across the floor are [s] times a human's) stands [s] times as far
 * from that point, in the same direction, so its hands and face come to the contact as a human's would. Up and down nothing
 * changes: a slot's z is a lift into the clip's Root track, which the engine already takes by each body's own pelvis. Facing
 * is the clip's. Everyone at 1 (all but gnomes) leaves the slots as they are. Pure; allocates only when something moves.
 */
object PairFit {
    /** [a] and [b]'s slots for bodies [sa] and [sb] times a human's. */
    fun slots(a: PartnerSlot, b: PartnerSlot, sa: Float, sb: Float): Pair<PartnerSlot, PartnerSlot> {
        if (sa == 1f && sb == 1f) return a to b
        val cx = (a.x + b.x) * 0.5f; val cy = (a.y + b.y) * 0.5f
        fun fit(p: PartnerSlot, s: Float) = if (s == 1f) p else p.copy(x = cx + (p.x - cx) * s, y = cy + (p.y - cy) * s)
        return fit(a, sa) to fit(b, sb)
    }

    /**
     * A performance's roles (the lead's, then the partner's) for bodies [sizes] (in the same order) times a human's: two roles
     * as [slots]; one (alone) about the performance's anchor; more are left as they are.
     */
    fun roles(roles: List<PlaybackRole>, sizes: FloatArray): List<PlaybackRole> {
        if (sizes.all { it == 1f }) return roles
        return when (roles.size) {
            1 -> listOf(roles[0].let { r -> r.copy(offset = Vec3(r.offset.x * sizes[0], r.offset.y * sizes[0], r.offset.z)) })
            2 -> {
                val (a, b) = slots(slot(roles[0]), slot(roles[1]), sizes.getOrElse(0) { 1f }, sizes.getOrElse(1) { 1f })
                listOf(roles[0].copy(offset = Vec3(a.x, a.y, a.z)), roles[1].copy(offset = Vec3(b.x, b.y, b.z)))
            }
            else -> roles
        }
    }

    /** How far apart two people stand talking: [human] for two humans, each half of it taken by their own size. */
    fun gap(human: Float, sa: Float, sb: Float): Float = human * 0.5f * (sa + sb)

    private fun slot(r: PlaybackRole) = PartnerSlot(r.offset.x, r.offset.y, r.offset.z, 0f)
}

/** Plays a pair's sound events (the audio side: W46's PartnerSounds). Main thread. */
interface PartnerSoundSink {
    fun play(name: String, actorKey: String?, loop: Boolean = false)
    fun stopAll()

    companion object {
        val NONE: PartnerSoundSink = object : PartnerSoundSink {
            override fun play(name: String, actorKey: String?, loop: Boolean) = Unit
            override fun stopAll() = Unit
        }
    }
}
