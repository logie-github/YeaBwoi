package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.PairAnim
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
data class PartnerSlot(val x: Float, val y: Float, val z: Float, val yawDeg: Float)

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
        /** The library's pair as the simulation takes it. */
        fun of(p: PairAnim) = PartnerPair(
            p.id, p.name, p.a, p.b,
            PartnerSlot(p.offsetA.x, p.offsetA.y, p.offsetA.z, p.offsetA.yawDeg),
            PartnerSlot(p.offsetB.x, p.offsetB.y, p.offsetB.z, p.offsetB.yawDeg),
            p.loops, p.locations, p.sounds.map { PartnerSound(it.timeSec, it.name, it.actor) },
        )

        /** The library's enabled pairs ([com.logie.wifecraft.anim.AnimLibrary.pairs]), for the live scene. */
        fun enabled(all: List<PairAnim>): List<PartnerPair> = all.filter { it.enabled }.map(::of)
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
