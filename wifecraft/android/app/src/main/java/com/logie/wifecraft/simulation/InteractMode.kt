package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.ClipLayers
import com.logie.wifecraft.anim.ClipTuning
import com.logie.wifecraft.anim.PoseKey
import com.logie.wifecraft.anim.PoseTrack
import com.logie.wifecraft.dialogue.Approach
import com.logie.wifecraft.dialogue.Fear
import com.logie.wifecraft.dialogue.Hesitation
import kotlin.random.Random

// Pure Kotlin (JVM-tested: InteractModeTest): Interact mode's state machine. One NPC at the centre; a tool (Touch: taps and strokes land
// on her body; Look: drags only turn the view); a sheet of commands (Commands, Pose, Outfit tabs) and a "Tell her..." field; her standing
// orders (follow me, stay); a command she hesitates at waits for the persuasion flow; what she did lately. The Compose layer
// (ui/InteractUi.kt) feeds it hits and taps and plays what it answers; nothing here touches Android.

enum class InteractTool(val label: String) { TOUCH("Touch"), LOOK("Look") }

/** The tabs of the commands sheet. */
enum class InteractTab(val label: String) { COMMANDS("Commands"), POSE("Pose"), OUTFIT("Outfit") }

/** What the player's last screen was before entering, to put back on leaving (as Chat mode's prior). */
data class InteractPrior(val cinematic: Boolean, val cameraMode: String, val cameraView: Int)

/** What a command comes to. */
sealed class CommandResult {
    /** She does it. */
    data class Do(val action: InteractAction) : CommandResult()
    /** She declines in character ([line] is what she says). */
    data class Declined(val action: InteractAction, val line: String) : CommandResult()
    /** She hesitates: the persuasion chips are up, the command waits ([line] is what she says). */
    data class Hesitates(val action: InteractAction, val line: String) : CommandResult()
    /** The next tap on the floor is where she goes. */
    object AwaitSpot : CommandResult()
    /** Nothing in the words is something she can be told. */
    data class Unknown(val text: String) : CommandResult()
}

class InteractMode(seed: Long = Random.nextLong()) {
    private val rnd = Random(seed)

    var actor: String? = null; private set
    val on: Boolean get() = actor != null
    var tool = InteractTool.TOUCH; private set
    /** The open tab of the sheet (null: the sheet is closed). */
    var tab: InteractTab? = null; private set
    /** The "Tell her..." row is open. */
    var tell = false; private set
    var following = false; private set
    var staying = false; private set
    /** The next floor tap is her destination. */
    var awaitingSpot = false; private set
    /** The command that waits on her hesitation (null: none), and the hesitation being talked round. */
    var pending: InteractAction? = null; private set
    var hesitation: Hesitation? = null; private set
    /** The haptics switch and the sound switch of the panel (the sound is the stockings' fabric: step three). */
    var haptics = true
    var sound = false
    var prior: InteractPrior? = null; private set
    /** How much she has come to trust the player in this session (0..0.25): accepted touches raise it, a refused one lowers it. */
    var trust = 0f; private set
    private val recent = ArrayList<ReactionKind>()
    var lastReaction: Reaction? = null; private set
    var accepted = 0; private set
    var refused = 0; private set

    /** Enters the mode on [id]. A fresh session: the tool is Touch, nothing pending. Only when not on already. */
    fun enter(id: String, before: InteractPrior): Boolean {
        if (on) return false
        actor = id; prior = before; tool = InteractTool.TOUCH; tab = null; tell = false; following = false; staying = false
        awaitingSpot = false; pending = null; hesitation = null; trust = 0f; recent.clear(); lastReaction = null; accepted = 0; refused = 0
        return true
    }

    /** Leaves the mode; what the screen was before. */
    fun leave(): InteractPrior? {
        val p = prior
        actor = null; prior = null; tab = null; tell = false; following = false; staying = false; awaitingSpot = false; pending = null; hesitation = null
        return p
    }

    fun toggleTool() { tool = if (tool == InteractTool.TOUCH) InteractTool.LOOK else InteractTool.TOUCH }
    fun setTool(t: InteractTool) { tool = t }

    /** Opens the sheet on [t], or closes it when it is the open one; the "Tell her" row closes (one thing over the dock at a time). */
    fun openTab(t: InteractTab) { tab = if (tab == t) null else t; if (tab != null) tell = false }
    fun closeSheet() { tab = null }
    fun toggleTell() { tell = !tell; if (tell) tab = null }

    /** The persona the touches and the commands are judged by now: [base] and the trust gained in this session. */
    fun persona(base: InteractPersona): InteractPersona = base.copy(affinity = (base.affinity + trust).coerceIn(0f, 1f))

    // ---------------------------------------------------------------- touch
    /**
     * A touch on her. With the tool on Look nothing is touched (null). A pull at the stockings is a touch like another. The answer is what
     * she does; trust moves with it (+0.02 for an allowed touch, -0.03 for a hesitation, -0.05 for a refusal).
     */
    fun touch(hit: TouchHit, kind: TouchKind, policy: ConsentPolicy?, base: InteractPersona): Reaction? {
        val id = actor ?: return null
        if (tool != InteractTool.TOUCH) return null
        val p = persona(base)
        val v = TouchConsent.verdict(policy, id, hit.region, kind, p)
        val r = InteractReactions.choose(hit, kind, v, p, rnd, recent)
        recent += r.kind
        if (recent.size > 4) recent.removeAt(0)
        lastReaction = r
        when (v) {
            Verdict.ALLOW -> { accepted++; trust = (trust + 0.02f).coerceAtMost(0.25f) }
            Verdict.HESITATE -> trust = (trust - 0.03f).coerceAtLeast(0f)
            Verdict.DECLINE -> { refused++; trust = (trust - 0.05f).coerceAtLeast(0f) }
        }
        return r
    }

    /** A stroke along her: a gate that stops where she does (made afresh for each stroke). */
    fun stroke(policy: ConsentPolicy?, base: InteractPersona): StrokeGate? =
        actor?.takeIf { tool == InteractTool.TOUCH }?.let { id -> StrokeGate(policy, id) { persona(base) } }

    // ---------------------------------------------------------------- commands
    /**
     * Tells her to do [action]. A Won't is declined at once; an Ask she hesitates at waits for the persuasion; otherwise she does it
     * (a follow or a stay set the standing order; a move to a spot waits for the tap).
     */
    fun command(action: InteractAction, policy: ConsentPolicy?, base: InteractPersona): CommandResult {
        val id = actor ?: return CommandResult.Unknown("")
        val p = persona(base)
        return when (InteractGate.verdict(policy, id, action, p)) {
            Verdict.DECLINE -> { refused++; trust = (trust - 0.05f).coerceAtLeast(0f); CommandResult.Declined(action, InteractLines.refusal(p, rnd)) }
            Verdict.HESITATE -> {
                pending = action
                hesitation = Hesitation(id, "player", InteractGate.resistance(action, p), p.disposition, Fear.GENERAL)
                CommandResult.Hesitates(action, InteractLines.hesitation(p, rnd))
            }
            Verdict.ALLOW -> perform(action)
        }
    }

    private fun perform(action: InteractAction): CommandResult {
        when (action) {
            InteractAction.Follow -> { following = true; staying = false }
            InteractAction.Stay -> { following = false; staying = true; awaitingSpot = false }
            InteractAction.MoveToSpot -> { awaitingSpot = true; following = false; return CommandResult.AwaitSpot }
            InteractAction.ComeHere -> following = false
            else -> Unit
        }
        pending = null; hesitation = null
        accepted++
        return CommandResult.Do(action)
    }

    /** The floor was tapped: where she goes when a move waits for it (null: nothing was waiting). */
    fun spotTapped(): Boolean { val w = awaitingSpot; awaitingSpot = false; return w }

    /** The player's try at calming her with [approach]: what the pending command comes to ([Do] when she agrees, [Declined] when she gives up, else she hesitates still). */
    fun persuade(approach: Approach, base: InteractPersona): CommandResult? {
        val h = hesitation ?: return null
        val act = pending ?: return null
        return when (h.persuade(approach)) {
            Hesitation.Outcome.AGREED -> { trust = (trust + 0.03f).coerceAtMost(0.25f); perform(act) }
            Hesitation.Outcome.REFUSED -> { pending = null; hesitation = null; refused++; CommandResult.Declined(act, InteractLines.refusal(persona(base), rnd)) }
            Hesitation.Outcome.STILL_HESITANT -> CommandResult.Hesitates(act, InteractLines.hesitation(persona(base), rnd))
        }
    }

    /** "Later": the hesitation is let go. */
    fun later() { pending = null; hesitation = null }

    /** Whether the persuasion chips are up. */
    val persuading: Boolean get() = pending != null && hesitation != null

    /**
     * "Tell her...": the words go through the keyword intents first; when they name nothing and [model] has a pick among [InteractAction.catalogue]
     * (constrained JSON: [InteractIntents.parseChoice]) that is taken. Then the same gate as a button.
     */
    fun tell(text: String, policy: ConsentPolicy?, base: InteractPersona, model: ((String, List<InteractAction>) -> InteractAction?)? = null): CommandResult {
        val action = InteractIntents.parse(text) ?: model?.invoke(text, InteractAction.catalogue())?.takeIf { it in InteractAction.catalogue() || InteractAction.byId(it.id) != null }
            ?: return CommandResult.Unknown(text)
        return command(action, policy, base)
    }

    /** The screen the mode puts up: what [ScreenPanel] and dock row it wants (the screen reads these). */
    val sheetOpen: Boolean get() = tab != null
}

/** Her refusals and hesitations in her own voice (the lines of a touch's, [InteractLines]). */
internal fun InteractLines.refusal(p: InteractPersona, rnd: Random): String {
    val hit = TouchHit(TouchRegion.NONE, GarmentSlot.BARE, 0, true, 0.5f)
    return line(Verdict.DECLINE, ReactionKind.SWAT, hit, p, rnd) ?: "No."
}

internal fun InteractLines.hesitation(p: InteractPersona, rnd: Random): String {
    val hit = TouchHit(TouchRegion.NONE, GarmentSlot.BARE, 0, true, 0.5f)
    return line(Verdict.HESITATE, ReactionKind.LOOK_AWAY, hit, p, rnd) ?: "Hm."
}

/** The small bone layers a reaction lays over her stance: a turn of the head, a lean, a shiver, a step back (the engine's bone layer, WoW-side bone names). */
object InteractLayers {
    const val SECONDS = 2.2f

    /** A held look: her head turned to [t] for a few seconds (at the player: back to straight). */
    fun look(t: LookTarget): Anim {
        val (pitch, yaw) = when (t) { LookTarget.CAMERA -> 0f to 0f; LookTarget.AWAY -> 0f to -38f; LookTarget.DOWN -> 24f to 0f }
        val keys = listOf(PoseKey(0f, 0f, 0f, 0f), PoseKey(0.35f, pitch, yaw, 0f), PoseKey(3.0f, pitch, yaw, 0f), PoseKey(3.6f, 0f, 0f, 0f))
        return ClipLayers.bake("interact:look", "Look", listOf(PoseTrack("Head", keys), PoseTrack("Neck1", keys.map { PoseKey(it.time, it.pitch * 0.4f, it.yaw * 0.4f, 0f) })), 3.6f, false, ClipTuning(1f, 1f, 20))
    }

    /** The posture of an expression (the models have no face rig): the same layers a reaction lays. */
    fun expression(e: Expression): Anim? {
        val kind = when (e) { Expression.SMILE -> ReactionKind.SMILE; Expression.BLUSH -> ReactionKind.BLUSH; Expression.TEASE -> ReactionKind.LEAN_IN; Expression.SHY -> ReactionKind.LOOK_AWAY; Expression.NEUTRAL -> return null }
        return layer(Reaction(kind, Verdict.ALLOW, e, if (e == Expression.SHY) 0.5f else 0f, 0f, null, HapticPattern(longArrayOf(10), intArrayOf(40)), 2f))
    }

    /** The layer for [r] ([yaw] sign: toward her left/right of the player), or null for a reaction with no posture of its own. */
    fun layer(r: Reaction): Anim? {
        val head = ArrayList<PoseKey>()
        val spine = ArrayList<PoseKey>()
        val neck = ArrayList<PoseKey>()
        val t = SECONDS
        fun key(list: MutableList<PoseKey>, time: Float, pitch: Float, yaw: Float, roll: Float) { list += PoseKey(time * t, pitch, yaw, roll) }
        val deg = 57.29578f
        when (r.kind) {
            ReactionKind.TURN_HEAD, ReactionKind.LOOK_AWAY -> {
                val yaw = r.turnHead * deg
                for (l in listOf(head, neck)) { key(l, 0f, 0f, 0f, 0f); key(l, 0.2f, 0f, yaw * (if (l === head) 1f else 0.4f), 0f); key(l, 0.75f, 0f, yaw * (if (l === head) 1f else 0.4f), 0f); key(l, 1f, 0f, 0f, 0f) }
            }
            ReactionKind.LEAN_IN -> {
                key(spine, 0f, 0f, 0f, 0f); key(spine, 0.25f, 9f, 0f, 0f); key(spine, 0.7f, 9f, 0f, 0f); key(spine, 1f, 0f, 0f, 0f)
                key(head, 0f, 0f, 0f, 0f); key(head, 0.25f, -5f, 0f, 4f); key(head, 0.7f, -5f, 0f, 4f); key(head, 1f, 0f, 0f, 0f)
            }
            ReactionKind.SHIVER -> {
                for (i in 0..8) key(spine, i / 8f, 0f, if (i % 2 == 0) 2.2f else -2.2f, 0f)
                key(head, 0f, 0f, 0f, 0f); key(head, 0.3f, 4f, 0f, 0f); key(head, 1f, 0f, 0f, 0f)
            }
            ReactionKind.BLUSH, ReactionKind.SMILE, ReactionKind.GIGGLE -> {
                key(head, 0f, 0f, 0f, 0f); key(head, 0.3f, if (r.kind == ReactionKind.BLUSH) 9f else 3f, 0f, if (r.kind == ReactionKind.GIGGLE) 6f else 3f)
                key(head, 0.7f, if (r.kind == ReactionKind.BLUSH) 9f else 3f, 0f, if (r.kind == ReactionKind.GIGGLE) -6f else 3f); key(head, 1f, 0f, 0f, 0f)
                if (r.kind == ReactionKind.GIGGLE) { key(spine, 0f, 0f, 0f, 0f); key(spine, 0.3f, 3f, 0f, 2f); key(spine, 0.6f, 0f, 0f, -2f); key(spine, 1f, 0f, 0f, 0f) }
            }
            ReactionKind.FLINCH, ReactionKind.STEP_BACK, ReactionKind.SWAT -> {
                key(spine, 0f, 0f, 0f, 0f); key(spine, 0.15f, -7f, 0f, 0f); key(spine, 0.6f, -5f, 0f, 0f); key(spine, 1f, 0f, 0f, 0f)
                key(head, 0f, 0f, 0f, 0f); key(head, 0.2f, 4f, r.turnHead * deg * 0.6f, 0f); key(head, 0.7f, 3f, r.turnHead * deg * 0.6f, 0f); key(head, 1f, 0f, 0f, 0f)
            }
        }
        val tracks = listOf(PoseTrack("Head", head.ifEmpty { listOf(PoseKey(0f, 0f, 0f, 0f)) }), PoseTrack("Spine2", spine.ifEmpty { listOf(PoseKey(0f, 0f, 0f, 0f)) }),
            PoseTrack("Neck1", neck.ifEmpty { listOf(PoseKey(0f, 0f, 0f, 0f)) }))
        return ClipLayers.bake("interact:${r.kind.name.lowercase()}", "Reaction", tracks, t, false, ClipTuning(1f, 1f, 20))
    }
}

/** Which clip of the solo library a [PoseKind] plays (by the words of its name), and the fallbacks that need no clip. */
object PoseLibrary {
    /** The clips of [library] that [pose] names, best match first (an exact word in the label before one in the group). */
    fun clipsFor(pose: PoseKind, library: List<JoiClip>): List<JoiClip> {
        val words = pose.words
        fun score(c: JoiClip): Int {
            val label = " ${c.label.lowercase()} "
            val group = " ${c.group.lowercase()} "
            val key = c.key.lowercase()
            return when {
                words.any { label.contains(" $it") } -> 3
                words.any { key.contains(it.replace(' ', '_')) || key.contains(it.replace(" ", "")) } -> 2
                words.any { group.contains(" $it") } -> 1
                else -> 0
            }
        }
        return library.filter { c -> !c.handjob && score(c) > 0 && !unfit(pose, c) }.sortedByDescending { score(it) }
    }

    /** A clip that matches by a word but is not that pose (a "sitting" dance, a partner stage). */
    private fun unfit(pose: PoseKind, c: JoiClip): Boolean {
        val t = " ${c.label} ${c.group} ${c.key} ".lowercase()
        if (c.kind.isNotBlank() || t.contains("pair:") || t.contains("partner")) return true
        return when (pose) {
            PoseKind.DANCE -> t.contains(" sit") || t.contains(" lie")
            else -> false
        }
    }

    /** The emote that stands in for a pose when the library has no clip (the game's own emotes, Partner.kt: Dance); null: none. */
    fun emoteFor(pose: PoseKind): PartnerEmote? = when (pose) {
        PoseKind.DANCE -> PartnerEmotes.ALL.firstOrNull { it.name == "Dance" }
        PoseKind.HAIR_PLAY -> null
        else -> null
    }

    /** Whether the pose is just a turn of her body (no clip): the screen turns her by [turnRadians]. */
    fun turnOf(pose: PoseKind): Float? = if (pose == PoseKind.TURN_AROUND) Math.PI.toFloat() else null

    /** The poses [library] can play (plus the ones that need no clip), for the Pose tab: each with how many clips it has. */
    fun available(library: List<JoiClip>): List<Pair<PoseKind, Int>> =
        PoseKind.entries.map { it to clipsFor(it, library).size }.filter { (p, n) -> n > 0 || turnOf(p) != null || emoteFor(p) != null }
}
