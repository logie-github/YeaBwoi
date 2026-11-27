package com.logie.wifecraft.simulation

import com.logie.wifecraft.dialogue.DirtyLevel
import com.logie.wifecraft.dialogue.Disposition
import com.logie.wifecraft.dialogue.WillingnessModel
import kotlin.random.Random

// Pure Kotlin (JVM-tested: InteractModeTest): Interact mode's touch, step one. A touch is an act like any other for the Consent and
// limits screen: each body part maps to the category of acts it belongs to (a hand on the head is Friendly and play, on the thigh
// Hands, on the foot Foot fetish), so a Won't on a category declines the touch in character, a Will lets it through and an Ask is
// decided by her willingness (how tender the part is, her nature, how well she knows the player, her mood). What she does about it
// (a blush, a giggle, a turn of the head, a step back...) is chosen by the part, the verdict, her nature and a seed.

/** How a touch is made: a tap, a stroke along her, a pull at what she wears (the stockings), or a pinch of her skin between two fingers. */
enum class TouchKind { TAP, STROKE, PULL, PINCH }

/** What she lets happen: [ALLOW], [HESITATE] (not sure: she shows it and the touch goes no further) or [DECLINE] (in character). */
enum class Verdict { ALLOW, HESITATE, DECLINE }

/** Who she is for a touch: her nature, her mood (-1..1), how well she knows the player (0..1) and how the talk runs ([dirty]). */
data class InteractPersona(
    val disposition: Disposition = Disposition(),
    val mood: Float = 0f,
    val affinity: Float = 0.3f,
    val dirty: DirtyLevel = DirtyLevel.DEFAULT,
)

object TouchConsent {
    /** How tender each part is to touch, 0 (a hand on the head) to 1 (the most private); the stockings' pull adds a little. */
    fun sensitivity(r: TouchRegion): Float = when (r) {
        TouchRegion.NONE -> 0f
        TouchRegion.HEAD -> 0.05f; TouchRegion.HAIR -> 0.08f; TouchRegion.SHOULDER -> 0.08f; TouchRegion.ARM -> 0.06f
        TouchRegion.HAND -> 0.08f; TouchRegion.BACK -> 0.14f; TouchRegion.FACE -> 0.26f; TouchRegion.KNEE -> 0.14f
        TouchRegion.CALF -> 0.18f; TouchRegion.FOOT -> 0.3f; TouchRegion.NECK -> 0.32f; TouchRegion.BELLY -> 0.45f
        TouchRegion.THIGH -> 0.52f; TouchRegion.HIP -> 0.56f; TouchRegion.CHEST -> 0.86f; TouchRegion.BUTT -> 0.86f; TouchRegion.GROIN -> 0.95f
    }

    /** The words that put a touch into a category of [ActCategories] (an act's name and kind are read by words). */
    private fun kindWord(r: TouchRegion): String = when (r) {
        TouchRegion.CHEST, TouchRegion.BUTT, TouchRegion.HIP, TouchRegion.THIGH -> "grope"
        TouchRegion.FOOT -> "foot"
        TouchRegion.GROIN -> "intimate touch"
        TouchRegion.FACE, TouchRegion.NECK, TouchRegion.BELLY -> "caress"
        else -> "pat"
    }

    /** The act a touch of [region] is to the Consent and limits screen: "touch:<region>", in the category its words say. */
    fun act(region: TouchRegion, kind: TouchKind = TouchKind.TAP): ConsentAct =
        ConsentAct("touch:${region.name.lowercase()}", "Touch ${region.label}${if (kind == TouchKind.PULL) " stockings" else ""}", kindWord(region), people = 1)

    /** The category [region]'s touch is under ([ActCategories]). */
    fun category(region: TouchRegion): String = act(region).category

    /** How much she resists a touch of [region] ([kind]) by herself: its tenderness, her nature, how well she knows the player, her mood. 0..1. */
    fun resistance(region: TouchRegion, kind: TouchKind, p: InteractPersona): Float {
        val d = p.disposition
        var r = sensitivity(region) + (if (kind == TouchKind.PULL) 0.08f else 0f)
        r += com.logie.wifecraft.simulation.ConsentDefaults.shift(d) * 0.8f
        r -= p.affinity.coerceIn(0f, 1f) * 0.4f
        r -= p.mood.coerceIn(-1f, 1f) * 0.1f
        // a touch that goes along her body is less than one that stays at a private part
        if (kind == TouchKind.STROKE) r += 0.04f
        // a pinch is more than a touch in the same place
        if (kind == TouchKind.PINCH) r += 0.06f
        return r.coerceIn(0f, 1f)
    }

    /**
     * What the Consent and limits screen says outright about [act]: a Won't declines, a Will the player set allows; nothing set
     * (the nature's default, which is a Will for a gentle category) leaves it to her willingness (null).
     */
    fun decided(policy: ConsentPolicy?, npc: String, act: ConsentAct): Verdict? {
        val r = policy?.resolve(npc, act) ?: return null
        return when {
            r.stance == Stance.WONT -> Verdict.DECLINE
            r.stance == Stance.WILL && r.source != Resolved.Source.DEFAULT -> Verdict.ALLOW
            else -> null
        }
    }

    /**
     * Whether she lets [region] be touched ([kind]): a Won't from [policy] declines; a Will allows; an Ask is decided by her resistance
     * ([WillingnessModel.AGREE_BELOW] allows, from [WillingnessModel.REFUSE_AT] declines, between hesitates).
     */
    fun verdict(policy: ConsentPolicy?, npc: String, region: TouchRegion, kind: TouchKind, p: InteractPersona): Verdict {
        if (region == TouchRegion.NONE) return Verdict.ALLOW
        decided(policy, npc, act(region, kind))?.let { return it }
        val r = resistance(region, kind, p)
        return when {
            r < WillingnessModel.AGREE_BELOW -> Verdict.ALLOW
            r >= WillingnessModel.REFUSE_AT -> Verdict.DECLINE
            else -> Verdict.HESITATE
        }
    }
}

/** The body expressions a reaction may show (the models have no face rig: they are head and shoulder postures and a look, plus the line). */
enum class Expression(val label: String) { NEUTRAL("neutral"), SMILE("smile"), BLUSH("blush"), TEASE("tease"), SHY("shy") }

/** What a reaction does, in the order it is picked from. */
enum class ReactionKind { SMILE, GIGGLE, BLUSH, TURN_HEAD, LEAN_IN, STEP_BACK, FLINCH, LOOK_AWAY, SWAT, SHIVER }

/** A haptic pattern: on/off [timings] in ms (first on) and the [amplitudes] of the on parts (1..255). */
data class HapticPattern(val timings: LongArray, val amplitudes: IntArray) {
    override fun equals(other: Any?) = other is HapticPattern && timings.contentEquals(other.timings) && amplitudes.contentEquals(other.amplitudes)
    override fun hashCode() = timings.contentHashCode() * 31 + amplitudes.contentHashCode()
}

/**
 * What she does about one touch. [turnHead] is the yaw of the head toward the player (+) or away (-), radians; [stepBack] the yards she
 * moves back (negative: leans in); [line] the words she says (null: none); [declined] a Won't or a refusal: the touch goes no further.
 */
data class Reaction(
    val kind: ReactionKind, val verdict: Verdict, val expression: Expression, val turnHead: Float, val stepBack: Float,
    val line: String?, val haptic: HapticPattern, val seconds: Float,
) { val declined: Boolean get() = verdict != Verdict.ALLOW }

object InteractReactions {
    private fun group(r: TouchRegion): Int = when (r) {
        TouchRegion.HEAD, TouchRegion.HAIR, TouchRegion.FACE -> 0
        TouchRegion.NECK -> 1
        TouchRegion.SHOULDER, TouchRegion.ARM, TouchRegion.HAND, TouchRegion.BACK -> 2
        TouchRegion.CHEST, TouchRegion.BELLY, TouchRegion.HIP, TouchRegion.BUTT, TouchRegion.THIGH -> 3
        TouchRegion.GROIN -> 5
        TouchRegion.KNEE, TouchRegion.CALF, TouchRegion.FOOT -> 4
        TouchRegion.NONE -> 2
    }

    /** The reaction kinds a touch of [region] may get when she allows it, with their weights before her nature. */
    fun allowedKinds(region: TouchRegion): List<Pair<ReactionKind, Float>> = when (group(region)) {
        0 -> listOf(ReactionKind.SMILE to 3f, ReactionKind.GIGGLE to 2f, ReactionKind.LEAN_IN to 2f, ReactionKind.BLUSH to 1.5f, ReactionKind.TURN_HEAD to 1f)
        1 -> listOf(ReactionKind.SHIVER to 3f, ReactionKind.BLUSH to 2f, ReactionKind.TURN_HEAD to 1.5f, ReactionKind.LEAN_IN to 1.5f)
        2 -> listOf(ReactionKind.SMILE to 3f, ReactionKind.TURN_HEAD to 2f, ReactionKind.LEAN_IN to 1.5f, ReactionKind.GIGGLE to 1f)
        3 -> listOf(ReactionKind.BLUSH to 3f, ReactionKind.GIGGLE to 2f, ReactionKind.TURN_HEAD to 2f, ReactionKind.SHIVER to 1.5f, ReactionKind.LEAN_IN to 1f)
        5 -> listOf(ReactionKind.SHIVER to 3f, ReactionKind.BLUSH to 2.5f, ReactionKind.LEAN_IN to 1.5f, ReactionKind.TURN_HEAD to 1f, ReactionKind.GIGGLE to 0.5f)
        else -> listOf(ReactionKind.GIGGLE to 3f, ReactionKind.FLINCH to 2f, ReactionKind.SHIVER to 1.5f, ReactionKind.BLUSH to 1f)
    }

    /** The kinds for a hesitation and for a refusal. */
    val HESITATING = listOf(ReactionKind.STEP_BACK, ReactionKind.LOOK_AWAY, ReactionKind.FLINCH, ReactionKind.BLUSH)
    val DECLINING = listOf(ReactionKind.SWAT, ReactionKind.STEP_BACK, ReactionKind.LOOK_AWAY)

    /** The weight her nature gives [k]: the bold lean in, the shy blush and look away, the tender smile. */
    internal fun natured(k: ReactionKind, p: InteractPersona): Float {
        val d = p.disposition
        return when (k) {
            ReactionKind.LEAN_IN -> 1f + d.boldness * 1.2f - d.caution
            ReactionKind.BLUSH, ReactionKind.LOOK_AWAY -> 1f + d.caution * 1.2f - d.boldness * 0.5f
            ReactionKind.SMILE -> 1f + d.warmth
            ReactionKind.GIGGLE -> 1f + d.curiosity * 0.5f + p.mood.coerceAtLeast(0f)
            ReactionKind.SWAT -> 1f + d.boldness
            ReactionKind.STEP_BACK -> 1f + d.caution
            else -> 1f
        }.coerceAtLeast(0.1f)
    }

    private fun pick(options: List<Pair<ReactionKind, Float>>, p: InteractPersona, rnd: Random, avoid: List<ReactionKind>): ReactionKind {
        // not the one she did last time unless nothing else is there
        val pool = options.filter { it.first !in avoid.takeLast(1) }.ifEmpty { options }
        val weights = pool.map { it.second * natured(it.first, p) }
        var x = rnd.nextFloat() * weights.sum()
        for ((i, w) in weights.withIndex()) { x -= w; if (x <= 0f) return pool[i].first }
        return pool.last().first
    }

    private fun expressionOf(k: ReactionKind, p: InteractPersona): Expression = when (k) {
        ReactionKind.SMILE -> Expression.SMILE
        ReactionKind.GIGGLE -> if (p.disposition.boldness > 0.3f) Expression.TEASE else Expression.SMILE
        ReactionKind.BLUSH, ReactionKind.LOOK_AWAY, ReactionKind.FLINCH -> Expression.SHY
        ReactionKind.LEAN_IN -> Expression.TEASE
        ReactionKind.TURN_HEAD, ReactionKind.SHIVER -> Expression.BLUSH
        ReactionKind.STEP_BACK, ReactionKind.SWAT -> Expression.NEUTRAL
    }

    /** The haptic pattern for a touch of [region] with [verdict]: soft ticks for an allowed one by tenderness, a double buzz for a refusal. */
    fun haptic(region: TouchRegion, verdict: Verdict, kind: TouchKind = TouchKind.TAP): HapticPattern {
        val s = TouchConsent.sensitivity(region)
        val amp = (40 + 150 * s).toInt().coerceIn(1, 255)
        return when {
            verdict == Verdict.DECLINE -> HapticPattern(longArrayOf(30, 60, 30), intArrayOf(200, 0, 200))
            verdict == Verdict.HESITATE -> HapticPattern(longArrayOf(25), intArrayOf(90))
            kind == TouchKind.PULL -> HapticPattern(longArrayOf(12, 18, 12), intArrayOf(amp, 0, amp))
            kind == TouchKind.PINCH -> PinchHaptics.start(region)
            kind == TouchKind.STROKE -> HapticPattern(longArrayOf(10), intArrayOf((amp * 0.6f).toInt().coerceAtLeast(1)))
            else -> HapticPattern(longArrayOf(18), intArrayOf(amp))
        }
    }

    /**
     * The reaction to a touch [hit] made as [kind], with her [verdict], her [persona], a [rnd] and what she did lately ([recent], the
     * last first is avoided). Lines are spoken when she hesitates or declines, and at times when she allows it.
     */
    fun choose(hit: TouchHit, kind: TouchKind, verdict: Verdict, persona: InteractPersona, rnd: Random, recent: List<ReactionKind> = emptyList()): Reaction {
        val region = hit.region
        val k = when (verdict) {
            Verdict.ALLOW -> pick(if (kind == TouchKind.PINCH) PinchReactions.allowedKinds(region) else allowedKinds(region), persona, rnd, recent)
            Verdict.HESITATE -> pick(HESITATING.map { it to 1f }, persona, rnd, recent)
            Verdict.DECLINE -> pick(DECLINING.map { it to 1f }, persona, rnd, recent)
        }
        val turn = when (k) {
            ReactionKind.TURN_HEAD -> 0.5f * (if (hit.side >= 0) 1f else -1f)
            ReactionKind.LOOK_AWAY -> -0.6f * (if (hit.side >= 0) 1f else -1f)
            ReactionKind.SWAT -> -0.4f
            else -> 0f
        }
        val step = when (k) {
            ReactionKind.STEP_BACK -> 0.35f
            ReactionKind.SWAT -> 0.2f
            ReactionKind.FLINCH -> 0.08f
            ReactionKind.LEAN_IN -> -0.05f
            else -> 0f
        }
        val speakChance = when (verdict) {
            Verdict.ALLOW -> 0.25f + persona.disposition.desireTalk * 0.35f + (if (TouchConsent.sensitivity(region) > 0.4f) 0.2f else 0f)
            else -> 1f
        }
        val line = if (rnd.nextFloat() < speakChance) (if (kind == TouchKind.PINCH && verdict == Verdict.ALLOW) PinchReactions.line(persona, rnd) else InteractLines.line(verdict, k, hit, persona, rnd)) else null
        return Reaction(k, verdict, expressionOf(k, persona), turn, step, line, haptic(region, verdict, kind), seconds = if (verdict == Verdict.ALLOW) 1.8f else 2.4f)
    }
}

/**
 * The spoken lines she has with no model: short, in her style ([Disposition.style]) and no cruder than the dirty talk level lets them
 * be (Cute and Mild stay sweet; from Spicy up an allowed touch at a private part may be said plainly, but still never explicit: the
 * model's lines take the level to its end). A refusal is the same at every level: clear and in her own voice.
 */
object InteractLines {
    private val DECLINE: Map<String, List<String>> = mapOf(
        "shy" to listOf("N-no, please don't.", "I'd rather you didn't.", "Not there. Please."),
        "bold" to listOf("Hands off. Not now.", "No. Ask me properly.", "Try that again and see what happens."),
        "tender" to listOf("Please, no. I'm not ready for that.", "I'd rather not, love.", "Not that. I'm sorry."),
        "dominant" to listOf("Don't. You don't touch me there.", "No. Remember who decides.", "I said no."),
        "natural" to listOf("No, don't do that.", "Not there.", "I'd rather you didn't."),
    )
    private val HESITATE: Map<String, List<String>> = mapOf(
        "shy" to listOf("I... wait. Not so fast.", "I'm not sure... give me a moment.", "That's... a lot, so soon."),
        "bold" to listOf("Slow down. Earn it first.", "Hm. Not yet.", "You're bold. Be patient."),
        "tender" to listOf("Wait... talk to me first.", "I'm not sure yet, love.", "Gently. Not so quick."),
        "dominant" to listOf("Patience. I'll tell you when.", "Not yet.", "Wait. I decide when."),
        "natural" to listOf("Hold on a moment.", "I'm not sure about that.", "Not yet, all right?"),
    )
    private val SOFT: Map<String, List<String>> = mapOf(
        "shy" to listOf("Oh... that's nice.", "H-hello to you too.", "You're making me blush."),
        "bold" to listOf("Mm, careful. I might like that.", "Bold of you.", "Keep that up and see."),
        "tender" to listOf("That's lovely.", "Mm, that's sweet.", "I like when you do that."),
        "dominant" to listOf("Good. Stay there.", "Mm. Go on.", "You may."),
        "natural" to listOf("Mm, that's nice.", "Hey, that tickles.", "Oh, hello."),
    )
    private val WARM: Map<String, List<String>> = mapOf(
        "shy" to listOf("I can feel myself going red.", "Don't stop... just be slow.", "Oh. Oh my."),
        "bold" to listOf("Now you have my attention.", "Don't tease unless you mean it.", "Mm. More, if you dare."),
        "tender" to listOf("Mm, I'm all warm now.", "You make me shiver.", "Stay close, love."),
        "dominant" to listOf("Hands there. Keep them there.", "Mm. Good.", "Slowly. I'm watching you."),
        "natural" to listOf("Mm, you've got me shivering.", "That's making me warm.", "Oh, that's rather good."),
    )

    // an allowed touch at the groin from Spicy up: by her style, suggestive and in her voice (the model's lines take the level further)
    private val INTIMATE: Map<String, List<String>> = mapOf(
        "shy" to listOf("Oh... there. Just... slowly.", "I can't think when you do that.", "Please be gentle... don't stop."),
        "bold" to listOf("Now you're getting somewhere.", "Mm. Don't stop there.", "Good. Keep going and I'll show you what that does."),
        "tender" to listOf("Mm, that's... oh, love, yes.", "Stay there. Softly.", "You know just how, don't you."),
        "dominant" to listOf("Slowly. Exactly there.", "Good. You've earned a little more.", "Mm. Don't you dare stop."),
        "natural" to listOf("Oh... that's good.", "Mm, right there.", "Keep doing that."),
    )
    private val INTIMATE_HOT: Map<String, List<String>> = mapOf(
        "shy" to listOf("I'm so wet... I mean, warm... oh, I can't hide it.", "Don't stop, please, don't stop."),
        "bold" to listOf("Feel how ready I am for you.", "Faster, or I'll take over."),
        "tender" to listOf("I'm aching for you. Please, love.", "Keep touching me, I'm so close to melting."),
        "dominant" to listOf("Harder. And don't look away.", "You feel how I'm soaked for you. Keep going."),
        "natural" to listOf("God, yes, I'm really into this.", "More. Don't stop."),
    )

    /** One line for a touch (null: none for this combination). */
    fun line(verdict: Verdict, k: ReactionKind, hit: TouchHit, p: InteractPersona, rnd: Random): String? {
        val style = p.disposition.style
        if (verdict == Verdict.ALLOW && hit.region == TouchRegion.GROIN && p.dirty >= DirtyLevel.SPICY) {
            val t = if (p.dirty >= DirtyLevel.HOT) INTIMATE_HOT else INTIMATE
            val l = t[style] ?: t.getValue("natural")
            return l[rnd.nextInt(l.size)]
        }
        val table = when (verdict) {
            Verdict.DECLINE -> DECLINE
            Verdict.HESITATE -> HESITATE
            Verdict.ALLOW -> if (p.dirty >= DirtyLevel.MILD && TouchConsent.sensitivity(hit.region) >= 0.4f) WARM else SOFT
        }
        val pool = table[style] ?: table.getValue("natural")
        return pool[rnd.nextInt(pool.size)]
    }
}

/**
 * A stroke along her (a drag on her body): each sample's [Verdict] decides whether it goes on. The first part that is not allowed ends
 * the stroke there (she shows it once, in character) and the stroke goes no further until the player lifts and touches again; her
 * willingness is the limit of how far it goes, and a part she hesitates at still lets the stroke reach up to it.
 */
class StrokeGate(private val policy: ConsentPolicy?, private val npc: String, private val persona: () -> InteractPersona) {
    /** Where the stroke stopped (null: it is going on). */
    var stoppedAt: TouchRegion? = null; private set
    var reached = 0f; private set
    private var last: TouchRegion = TouchRegion.NONE
    private var steps = 0

    /** What the sample [hit] does: [Step.GO] (the touch is felt), [Step.STOP] (this is where she stops it: [Step.reaction] says how) or [Step.NONE] (stopped before, or off her). */
    fun sample(hit: TouchHit?, rnd: Random): Step {
        if (stoppedAt != null || hit == null || hit.region == TouchRegion.NONE) return Step(Step.Kind.NONE, null, hit?.region ?: TouchRegion.NONE)
        val p = persona()
        val v = TouchConsent.verdict(policy, npc, hit.region, TouchKind.STROKE, p)
        if (v != Verdict.ALLOW) {
            stoppedAt = hit.region
            return Step(Step.Kind.STOP, InteractReactions.choose(hit, TouchKind.STROKE, v, p, rnd), hit.region)
        }
        steps++
        reached = maxOf(reached, TouchConsent.sensitivity(hit.region))
        val changed = hit.region != last
        last = hit.region
        // a new part under the finger gets its own little reaction now and then; the same one only ticks
        return Step(if (changed && steps > 1) Step.Kind.NEW_PART else Step.Kind.GO, null, hit.region)
    }

    class Step(val kind: Kind, val reaction: Reaction?, val region: TouchRegion) {
        enum class Kind { NONE, GO, NEW_PART, STOP }
    }
}
