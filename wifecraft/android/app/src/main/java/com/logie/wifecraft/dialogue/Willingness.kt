package com.logie.wifecraft.dialogue

import org.json.JSONObject

/** What two NPCs are to each other as the life simulation keeps it (-100..100 each). */
data class Relation(val friendship: Float = 0f, val romance: Float = 0f)

/** Where an NPC stands about a sexual stage with another: willing, hesitant (afraid or doubtful: it can be talked round) or refusing for now. */
enum class Willing { WILLING, HESITANT, REFUSED }

/** What the model answered about willingness. [line] is the NPC's own in-character words (null: none). */
data class ModelVerdict(val decision: Decision, val line: String?) {
    enum class Decision(val implied: Float) { YES(0.1f), HESITANT(0.45f), NO(0.85f), LATER(0.85f) }
}

/** What fear a hesitation is made of: it decides which persuasion fits. */
enum class Fear { PREJUDICE, SHYNESS, DEVOUT, GENERAL }

/** The ways to calm and win someone round (the player's chips and the partner NPC's choices). Labels are UI text, not dialogue. */
enum class Approach(val label: String) {
    REASSURE("Reassure"), FLATTER("Flatter"), PATIENT("Be patient"), JOKE("Joke"), CURIOSITY("Appeal to curiosity"), KINDNESS("Show kindness")
}

/**
 * Willingness as a number, the resistance (0 none .. 1 total), from the faction and race attitude, the NPC's nature and the
 * relationship; the model's answer moves it within bounds ([applyModel]). Hesitation is a state: below [AGREE_BELOW] they are
 * willing, from there to [REFUSE_AT] hesitant, and the resistance can be lowered by persuasion ([Hesitation]) until it drops
 * under [CONVINCED_BELOW]. Pure JVM.
 */
object WillingnessModel {
    const val AGREE_BELOW = 0.25f
    const val CONVINCED_BELOW = 0.22f
    const val REFUSE_AT = 0.72f
    /** How far the model's answer may move the prior. */
    const val MODEL_BOUND = 0.25f

    /** How wary [from]'s people are of [to]'s in bed, 0..~0.45: the faction war, the Forsaken's corpse flesh, the old grudges. */
    fun attitude(lore: Lore, from: Int, to: Int): Float {
        val a = lore.raceKey(from) ?: return 0f
        val b = lore.raceKey(to) ?: return 0f
        val fa = lore.faction(from)
        val fb = lore.faction(to)
        val undeadTo = b == "undead"
        var v = 0f
        when {
            fa == Faction.ALLIANCE && undeadTo -> v = 0.34f
            fa == Faction.ALLIANCE && fb == Faction.HORDE -> v = 0.22f
            fa == Faction.HORDE && fb == Faction.ALLIANCE -> v = if (a == "undead") 0.18f else 0.2f
            fa == Faction.HORDE && undeadTo -> v = 0.06f
            fa == Faction.NEUTRAL && undeadTo -> v = 0.1f
            a == "undead" && fb == Faction.HORDE -> v = 0.08f
        }
        if (a == "nightelf" && fb == Faction.HORDE) v += 0.08f
        if (a == "nightelf" && undeadTo) v += 0.06f
        if (a == "bloodelf" && undeadTo) v += 0.1f
        if (a == "tauren" && undeadTo) v += 0.06f
        if (a == "worgen" && undeadTo) v += 0.04f
        if (a == "draenei" && b == "orc") v += 0.06f
        return v.coerceIn(0f, 0.5f)
    }

    /**
     * [me]'s resistance to a sexual stage with [other]: a base, the attitude scaled by prejudice (lowered by curiosity, warmth and
     * boldness), caution and devotion up, boldness, warmth and curiosity down, trust (friendship and romance) down, familiarity
     * (the comfort band) down.
     */
    fun prior(lore: Lore, me: NpcProfile, other: NpcProfile, rel: Relation = Relation(), band: ComfortBand = ComfortBand.STRANGERS): Float {
        val d = me.disposition
        val att = attitude(lore, me.raceId, other.raceId)
        val scale = (1f + d.prejudice * 0.6f - d.curiosity * 0.4f - d.warmth * 0.25f - d.boldness * 0.2f).coerceIn(0.3f, 1.6f)
        var r = 0.1f + att * scale + d.caution * 0.18f + d.devout * 0.12f - d.boldness * 0.1f - d.warmth * 0.08f - d.curiosity * 0.06f
        r -= (rel.friendship / 100f).coerceIn(-0.5f, 1f) * 0.25f + (rel.romance / 100f).coerceIn(-0.5f, 1f) * 0.3f
        r -= band.ordinal * 0.06f
        if (rel.friendship < -30f) r += 0.4f
        return r.coerceIn(0f, 0.95f)
    }

    fun classify(r: Float): Willing = when {
        r < AGREE_BELOW -> Willing.WILLING
        r >= REFUSE_AT -> Willing.REFUSED
        else -> Willing.HESITANT
    }

    fun fear(lore: Lore, me: NpcProfile, other: NpcProfile): Fear = when {
        attitude(lore, me.raceId, other.raceId) >= 0.15f -> Fear.PREJUDICE
        me.disposition.caution >= 0.3f -> Fear.SHYNESS
        me.disposition.devout >= 0.3f -> Fear.DEVOUT
        else -> Fear.GENERAL
    }

    /** The prior moved by the model's answer, within [MODEL_BOUND] either way. */
    fun applyModel(prior: Float, v: ModelVerdict?): Float =
        if (v == null) prior else v.decision.implied.coerceIn(prior - MODEL_BOUND, prior + MODEL_BOUND).coerceIn(0f, 1f)

    /**
     * Parses the model's willingness answer: JSON ({"willing":"yes","reason":"..."} or "decision"), else the first yes, no,
     * maybe-later or hesitant word. Anything it can't read: null (the prior stands, and the prior mostly says yes).
     */
    fun parse(text: String?): ModelVerdict? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val json = Regex("\\{[^{}]*}").find(raw)?.value
        var word: String? = null
        var line: String? = null
        if (json != null) runCatching {
            val o = JSONObject(json)
            word = listOf("willing", "decision", "answer", "consent").firstNotNullOfOrNull { k -> if (o.has(k)) o.get(k).toString() else null }
            line = listOf("reason", "line", "say", "words", "response").firstNotNullOfOrNull { k -> o.optString(k, "").takeIf { it.isNotBlank() } }
        }
        val decision = decisionOf(word) ?: if (json == null) decisionOf(raw.take(60)) else null
        decision ?: return null
        val spoken = LineCleaner.clean(line ?: if (json == null) raw.replace(Regex("^(yes|no|maybe later|maybe-later|hesitant|later)\\b[:,.\\-\\s]*", RegexOption.IGNORE_CASE), "") else null)
        return ModelVerdict(decision, spoken)
    }

    private fun decisionOf(s: String?): ModelVerdict.Decision? {
        val t = s?.trim()?.lowercase() ?: return null
        return when {
            Regex("^(true|yes|y|willing|agree[sd]?|accept\\w*)\\b").containsMatchIn(t) -> ModelVerdict.Decision.YES
            Regex("later|not now|not yet|another time").containsMatchIn(t) -> ModelVerdict.Decision.LATER
            Regex("hesitan\\w*|unsure|afraid|nervous|maybe|uncertain|doubt\\w*").containsMatchIn(t) -> ModelVerdict.Decision.HESITANT
            Regex("^(false|no|n|refus\\w*|unwilling|never|decline\\w*)\\b").containsMatchIn(t) -> ModelVerdict.Decision.NO
            else -> null
        }
    }

    /** How well [a] fits a hesitant NPC with [d] and fear [fear], -1..1. */
    fun fit(a: Approach, d: Disposition, fear: Fear): Float {
        val v = when (a) {
            Approach.REASSURE -> 0.2f + d.caution * 0.8f + (if (fear == Fear.SHYNESS || fear == Fear.GENERAL) 0.1f else 0f)
            Approach.FLATTER -> 0.15f + d.warmth * 0.2f + d.boldness * 0.3f - d.prejudice * 0.4f - d.devout * 0.3f
            Approach.PATIENT -> 0.3f + d.caution * 0.5f - d.boldness * 0.2f + (if (fear == Fear.SHYNESS) 0.2f else 0f)
            Approach.JOKE -> 0.1f - d.caution * 0.5f + d.boldness * 0.3f + d.warmth * 0.1f - d.devout * 0.4f - (if (fear == Fear.PREJUDICE) 0.1f else 0f)
            Approach.CURIOSITY -> -0.05f + d.curiosity * 1.0f - d.devout * 0.2f + (if (fear == Fear.PREJUDICE) 0.15f else 0f)
            Approach.KINDNESS -> 0.25f + d.warmth * 0.5f - d.prejudice * 0.2f + (if (fear == Fear.PREJUDICE) 0.1f else 0f)
        }
        return v.coerceIn(-1f, 1f)
    }

    /** The change in resistance one attempt of [fit] makes: from +0.06 (a poor fit) to -0.40 (a perfect one). */
    fun delta(fit: Float): Float = -0.17f - 0.23f * fit

    /** The approach that suits [d] best (the partner NPC's choice when no player picks). */
    fun bestApproach(d: Disposition, fear: Fear): Approach = Approach.entries.maxByOrNull { fit(it, d, fear) } ?: Approach.KINDNESS
}

/** How the NPC reacted to an approach, as the model judged it. */
enum class Reaction { CALMER, SAME, WARIER }

/**
 * One hesitant NPC being talked round. [resistance] moves with every attempt by how well it fits their nature, the model's
 * judgement ([Reaction]) shifting it within bounds; under [WillingnessModel.CONVINCED_BELOW] they agree, and after three poor
 * attempts (or five of any kind) they refuse for now. Most are won within one to three.
 */
class Hesitation(val npcId: String, val otherId: String, var resistance: Float, val disposition: Disposition, val fear: Fear) {
    enum class Outcome { AGREED, STILL_HESITANT, REFUSED }
    var attempts = 0; private set
    var poor = 0; private set
    var lastDelta = 0f; private set

    fun persuade(a: Approach, reaction: Reaction? = null): Outcome {
        val heur = WillingnessModel.delta(WillingnessModel.fit(a, disposition, fear))
        var d = heur
        if (reaction != null) {
            val judged = when (reaction) { Reaction.CALMER -> heur - 0.12f; Reaction.SAME -> heur * 0.4f; Reaction.WARIER -> heur + 0.12f }
            d = judged.coerceIn(heur - 0.15f, heur + 0.15f)
        }
        d = d.coerceAtMost(0.1f)
        attempts++
        if (d > -0.1f) poor++
        lastDelta = d
        resistance = (resistance + d).coerceIn(0f, 1f)
        return when {
            resistance < WillingnessModel.CONVINCED_BELOW -> Outcome.AGREED
            poor >= 3 || attempts >= 5 || resistance >= 0.9f -> Outcome.REFUSED
            else -> Outcome.STILL_HESITANT
        }
    }
}
