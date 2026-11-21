package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Switch positions: a two-person animation's role A (its actor 0, the order's lead) and role B (actor 1, the partner) come
 * from the animation's own slots, and nothing says which body each was made for. Switching gives each of the two people the
 * other's role: its clip, its slot and its facing. Played, that is the same order with the two people the other way round
 * ([swapped]); mid-play the simulation does it in place ([SceneSimulation.switchRoles]). Pure.
 */
object RoleSwitch {
    /** Whether an animation of [participants] people can switch positions (two roles; one alone has nothing to switch). */
    fun switchable(participants: Int): Boolean = participants == 2

    /**
     * [order] (led by [lead]) with the two people the other way round: the partner leads (plays role A) and [lead] plays role
     * B. Roles, parts, sounds and the pair are kept as they are (a sound names its role, not its person). Null: not a
     * two-person order.
     */
    fun swapped(lead: String, order: PerformanceOrder): Pair<String, PerformanceOrder>? {
        val other = order.partnerId ?: return null
        if (order.roles.size != 2 || other == lead) return null
        return other to order.copy(partnerId = lead)
    }

    /** [lead] and [order] as played: switched when [switch], else as they are. */
    fun apply(lead: String, order: PerformanceOrder, switch: Boolean): Pair<String, PerformanceOrder> =
        (if (switch) swapped(lead, order) else null) ?: (lead to order)

    /**
     * Two roles fitted ([PairFit.roles]) for a body [sa] times a human's on role A and [sb] on role B, fitted again for the
     * two the other way round ([sb] on A, [sa] on B): the contact point and each role's direction from it kept, each slot's
     * distance from it taken by its new body's size. Equal sizes (everyone but gnomes): the roles as they are.
     */
    fun refit(roles: List<PlaybackRole>, sa: Float, sb: Float): List<PlaybackRole> {
        if (roles.size != 2 || sa == sb || !(sa > 0f) || !(sb > 0f)) return roles
        val a = roles[0].offset; val b = roles[1].offset
        val dx = (a.x - b.x) / (sa + sb); val dy = (a.y - b.y) / (sa + sb)
        val cx = a.x - dx * sa; val cy = a.y - dy * sa
        return listOf(
            roles[0].copy(offset = Vec3(cx + dx * sb, cy + dy * sb, a.z)),
            roles[1].copy(offset = Vec3(cx - dx * sa, cy - dy * sa, b.z)),
        )
    }
}

/** One role of a two-person animation as cast: who plays it ([npc]), its clip, its slot from the anchor and its facing. */
data class RoleSeat(val npc: String, val clip: String, val offset: Vec3, val facing: Float)

/** Who plays role [a] (A: actor 0) and role [b] (B: actor 1). [switched]: each person in the other's role. */
data class RoleCast(val a: RoleSeat, val b: RoleSeat) {
    /** Role A keeps its clip, slot and facing and B its own; the people change places. */
    fun switched() = RoleCast(a.copy(npc = b.npc), b.copy(npc = a.npc))

    /** The role [npc] plays: 0 (A), 1 (B), or -1. */
    fun roleOf(npc: String): Int = when (npc) { a.npc -> 0; b.npc -> 1; else -> -1 }

    /** [npc]'s seat (its clip, slot, facing), or null. */
    fun seatOf(npc: String): RoleSeat? = when (npc) { a.npc -> a; b.npc -> b; else -> null }

    companion object {
        /** The cast of [order] led by [lead]; null: not a two-person order. */
        fun of(lead: String, order: PerformanceOrder): RoleCast? {
            val other = order.partnerId ?: return null
            if (order.roles.size != 2) return null
            val (ra, rb) = order.roles
            return RoleCast(RoleSeat(lead, ra.clip, ra.offset, ra.facing), RoleSeat(other, rb.clip, rb.offset, rb.facing))
        }
    }
}

/**
 * The switch remembered per animation (by its browser id): the ids played switched. Kept for the session and saved as
 * [encode] (one id a line) so the choice sticks. Pure; not thread-safe (main thread).
 */
class RoleMemory(switched: Collection<String> = emptyList()) {
    private val ids = LinkedHashSet(switched.filter { it.isNotBlank() && '\n' !in it })

    fun isSwitched(id: String): Boolean = id in ids

    /** Sets [id]'s switch; true when it changed. */
    fun set(id: String, switched: Boolean): Boolean {
        if (id.isBlank() || '\n' in id) return false
        return if (switched) ids.add(id) else ids.remove(id)
    }

    fun toggle(id: String): Boolean { set(id, !isSwitched(id)); return isSwitched(id) }

    val size: Int get() = ids.size

    fun encode(): String = ids.joinToString("\n")

    companion object {
        fun decode(text: String?): RoleMemory = RoleMemory(text.orEmpty().split('\n').map { it.trim() })
    }
}

/** Where the confirmation of a two-person animation is: none, its preview shown, played, or cancelled. */
enum class ConfirmPhase { IDLE, PREVIEW, PLAYING, CANCELLED }

/**
 * The confirmation before a two-person animation plays: [owner] chose [choiceId] with [partner]; the preview shows them in
 * their roles ([lead] plays A, [other] B), Switch positions turns them round ([switch], only when [switchable]), Play plays
 * it ([play]) and Cancel drops it ([cancel]). Nothing plays before [play]. Immutable: each step returns the next state.
 */
data class PairConfirm(
    val choiceId: String, val owner: String, val partner: String,
    val switchable: Boolean, val switched: Boolean = false, val phase: ConfirmPhase = ConfirmPhase.PREVIEW,
) {
    val lead: String get() = if (switched) partner else owner
    val other: String get() = if (switched) owner else partner
    val open: Boolean get() = phase == ConfirmPhase.PREVIEW

    fun switch(): PairConfirm = if (open && switchable) copy(switched = !switched) else this
    fun play(): PairConfirm = if (open) copy(phase = ConfirmPhase.PLAYING) else this
    fun cancel(): PairConfirm = if (open) copy(phase = ConfirmPhase.CANCELLED) else this

    companion object {
        val IDLE = PairConfirm("", "", "", switchable = false, phase = ConfirmPhase.IDLE)

        /** The preview of [choiceId] for [owner] with [partner], switched as [memory] remembers it. */
        fun open(choiceId: String, owner: String, partner: String, participants: Int, memory: RoleMemory?): PairConfirm {
            val can = RoleSwitch.switchable(participants) && owner != partner
            return PairConfirm(choiceId, owner, partner, can, can && memory?.isSwitched(choiceId) == true)
        }
    }
}

/** One person of the preview where the order would start them: cast index, place (world), floor under it, facing, clip key. */
data class PreviewPlace(val index: Int, val x: Float, val y: Float, val z: Float, val floor: Float, val facing: Float, val clip: String)

/** The preview's clip keys: an order's clip held at its first frame. */
object PreviewKeys {
    private const val HOLD = "hold:"
    fun hold(key: String) = "$HOLD$key"
    /** The clip key a held key holds, else null. */
    fun heldOf(key: String): String? = if (key.startsWith(HOLD)) key.substring(HOLD.length) else null
}

/** A clip held at its first frame (the preview's still): every track its first key, twice, over a second, looping. */
object HoldFrame {
    fun of(anim: Anim): Anim = anim.copy(duration = 1f, loop = true, tracks = anim.tracks.map { t ->
        val keys = t.times.size
        if (keys == 0 || t.values.size % keys != 0) t else {
            val stride = t.values.size / keys
            val first = t.values.copyOfRange(0, stride)
            BoneTrack(t.bone, t.channel, floatArrayOf(0f, 1f), first + first)
        }
    })
}

/**
 * The preview's camera (stage coordinates): both people seen side by side from across the line between them, at chest
 * height, far enough for both bodies; the side the room allows ([CameraClearance]) first, the facing's right as a tie.
 */
object PreviewFraming {
    const val CHEST = 1.0f
    const val MIN_DISTANCE = 3.0f

    fun frame(ax: Float, ay: Float, bx: Float, by: Float, z: Float, heading: Float, fov: Float, clearance: CameraClearance?): CameraPose {
        val cx = (ax + bx) * 0.5f; val cy = (ay + by) * 0.5f
        val apart = hypot(bx - ax, by - ay)
        // across the line between them; on one spot, across the heading
        var ux: Float; var uy: Float
        if (apart > 0.05f) { ux = -(by - ay) / apart; uy = (bx - ax) / apart } else { ux = -sin(heading); uy = cos(heading) }
        val distance = max(MIN_DISTANCE, apart * 1.8f + 2.2f)
        val at = Vec3(cx, cy, z + CHEST)
        fun eye(sign: Float) = Vec3(cx + ux * distance * sign, cy + uy * distance * sign, z + CHEST + 0.6f)
        val first = eye(1f)
        val pick = if (clearance == null || clearance.clear(first, at)) first else eye(-1f).takeIf { clearance.clear(it, at) } ?: first
        return CameraPose(pick, at, fov)
    }
}
