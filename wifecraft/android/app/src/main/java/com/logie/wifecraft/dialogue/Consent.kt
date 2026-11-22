package com.logie.wifecraft.dialogue

/** A hesitant NPC waiting to be talked round, for the UI's persuasion chips. [resistance]: 0..1. */
data class HesitantView(val npcId: String, val name: String, val otherId: String, val otherName: String, val resistance: Float, val attempts: Int)

/**
 * Willingness before a sexual stage, in the flow the simulation asks about ([ask]): the prior from the faction, race, nature
 * and relationship; the model's answer inside its bounds; a hesitant NPC voices the doubt and the stage waits while they are
 * talked round, by the partner (the model, in character) or by the player's chips ([persuade]); agreed, they say so and the
 * stage goes on; refused for now, they say so, the invitation ends and a cooldown holds. Every spoken word is the model's:
 * with no answer from it a line is simply not spoken, and the numbers still decide. Single-threaded: driven by the director.
 */
class ConsentCoordinator(
    private val runner: ModelRunner,
    private val lore: Lore,
    private val comfort: ComfortLedger,
    private val speak: (npcId: String, line: String) -> Unit,
    private val profile: (String) -> NpcProfile?,
    private val relation: (String, String) -> Relation,
    private val clock: () -> Double,
) {
    private enum class Phase { DECIDING, HESITANT, CLEARED, REFUSED }

    private class Session(val key: String, val initiator: String, val target: String, val directed: Boolean, val stage: String, val startedAt: Double) {
        var phase = Phase.DECIDING
        var waiting = 0
        val hesitations = ArrayList<Hesitation>()
        var nextAuto = 0.0
        var busy = false
        var closedAt = 0.0
    }

    private val sessions = HashMap<String, Session>()
    private val cooldownUntil = HashMap<String, Double>()
    private var generation = 0

    private fun key(a: String, b: String) = if (a <= b) "$a|$b" else "$b|$a"

    fun reset() { sessions.clear(); cooldownUntil.clear(); generation++ }

    /** The answer for [initiator] and [target] beginning [stage] now. Starts the decision the first time; WAIT until it is made. */
    fun ask(initiator: String, target: String, directed: Boolean, stage: String): ConsentGate {
        val k = key(initiator, target)
        val now = clock()
        if ((cooldownUntil[k] ?: 0.0) > now) return ConsentGate.REFUSED
        val s = sessions[k]
        if (s == null) {
            val a = profile(initiator); val b = profile(target)
            if (a == null || b == null) return ConsentGate.PROCEED
            val ns = Session(k, initiator, target, directed, stage, now)
            sessions[k] = ns
            decide(ns, a, b)
            return answer(ns)
        }
        return answer(s)
    }

    private fun answer(s: Session): ConsentGate {
        val now = clock()
        return when (s.phase) {
            Phase.CLEARED -> { sessions.remove(s.key); ConsentGate.PROCEED }
            Phase.REFUSED -> { sessions.remove(s.key); ConsentGate.REFUSED }
            Phase.DECIDING -> if (now - s.startedAt > DECIDE_WAIT) { s.phase = Phase.CLEARED; sessions.remove(s.key); ConsentGate.PROCEED } else ConsentGate.WAIT
            Phase.HESITANT -> if (now - s.startedAt > HESITATE_WAIT) { close(s, refused = true); sessions.remove(s.key); ConsentGate.REFUSED } else ConsentGate.WAIT
        }
    }

    /** Who decides: the invitee always, and the initiator too when the player ordered the stage. */
    private fun decide(s: Session, a: NpcProfile, b: NpcProfile) {
        val deciders = if (s.directed) listOf(b to a, a to b) else listOf(b to a)
        s.waiting = deciders.size
        val gen = generation
        val results = ArrayList<Triple<NpcProfile, NpcProfile, Pair<Float, String?>>>()
        for ((me, other) in deciders) {
            val rel = relation(me.id, other.id)
            val band = comfort.band(me.disposition, me.id, other.id, rel.friendship, rel.romance)
            val prior = WillingnessModel.prior(lore, me, other, rel, band)
            val req = DialogueRequest(me.id, PersonaPrompt.system(me, lore), emptyList(), askPrompt(me, other, prior, band, s.stage), maxTokens = 70, temperature = 0.6f)
            runner.submit(PRIORITY, "consent", req, ttl = DECIDE_WAIT) { raw ->
                if (gen != generation || sessions[s.key] !== s || s.phase != Phase.DECIDING) return@submit
                val v = WillingnessModel.parse(raw)
                results += Triple(me, other, WillingnessModel.applyModel(prior, v) to v?.line)
                if (--s.waiting == 0) settle(s, results)
            }
        }
    }

    private fun askPrompt(me: NpcProfile, other: NpcProfile, prior: Float, band: ComfortBand, stage: String): String {
        val lean = when (WillingnessModel.classify(prior)) { Willing.WILLING -> "You are inclined to say yes."; Willing.HESITANT -> "You feel some fear or doubt about this."; Willing.REFUSED -> "You are strongly against it." }
        val o = lore.race(other.raceId)
        return "${other.name}${o?.let { ", a ${it.name}" } ?: ""}, whom you ${band.label.let { "know as $it" }}, wants to lie with you (${stage.ifBlank { "intimately" }}). " +
            (lore.stance(me.raceId, other.raceId)?.let { "How your kind looks on theirs: $it " } ?: "") + lean +
            " Decide for yourself, as you are. Reply as JSON only: {\"willing\":\"yes\" or \"hesitant\" or \"no\" or \"maybe-later\",\"reason\":\"one short sentence in your own voice\"}"
    }

    private fun settle(s: Session, results: List<Triple<NpcProfile, NpcProfile, Pair<Float, String?>>>) {
        val refused = results.firstOrNull { WillingnessModel.classify(it.third.first) == Willing.REFUSED }
        if (refused != null) {
            refused.third.second?.let { speak(refused.first.id, it) }
            comfort.refused(s.initiator, s.target)
            close(s, refused = true)
            return
        }
        for (r in results) if (WillingnessModel.classify(r.third.first) == Willing.HESITANT) {
            val (me, other, res) = r
            s.hesitations += Hesitation(me.id, other.id, res.first, me.disposition, WillingnessModel.fear(lore, me, other))
            res.second?.let { speak(me.id, it) }
            comfort.hesitated(me.id, other.id)
        }
        if (s.hesitations.isEmpty()) { s.phase = Phase.CLEARED; return }
        s.phase = Phase.HESITANT
        s.nextAuto = clock() + AUTO_FIRST
    }

    private fun close(s: Session, refused: Boolean) {
        s.phase = if (refused) Phase.REFUSED else Phase.CLEARED
        s.closedAt = clock()
        if (refused) cooldownUntil[s.key] = clock() + COOLDOWN
    }

    /** Hesitant NPCs now, for the persuasion chips. */
    fun hesitant(): List<HesitantView> = sessions.values.filter { it.phase == Phase.HESITANT }.flatMap { s ->
        s.hesitations.mapNotNull { h ->
            val me = profile(h.npcId) ?: return@mapNotNull null
            val o = profile(h.otherId) ?: return@mapNotNull null
            HesitantView(me.id, me.first, o.id, o.first, h.resistance, h.attempts)
        }
    }

    /** The player's try at calming [npcId] with [approach]. Judged by the model in character (a heuristic without it). */
    fun persuade(npcId: String, approach: Approach, by: String? = null): Boolean {
        val s = sessions.values.firstOrNull { it.phase == Phase.HESITANT && it.hesitations.any { h -> h.npcId == npcId } } ?: return false
        val h = s.hesitations.first { it.npcId == npcId }
        if (s.busy) return false
        s.busy = true
        val me = profile(npcId) ?: run { s.busy = false; return false }
        val other = profile(h.otherId)
        val gen = generation
        val who = by?.let { profile(it)?.first } ?: "Someone"
        val req = DialogueRequest(me.id, PersonaPrompt.system(me, lore), emptyList(),
            "You are hesitant about lying with ${other?.name ?: "them"}. ${who} tries to calm you: ${approach.label.lowercase()}. " +
                "Your fear is ${fearText(h.fear)} and your nature is ${me.disposition.style}. React in character. " +
                "Reply as JSON only: {\"reaction\":\"calmer\" or \"same\" or \"warier\",\"line\":\"one short sentence you say\"}",
            maxTokens = 70, temperature = 0.7f)
        runner.submit(PRIORITY, "consent", req, ttl = DECIDE_WAIT) { raw ->
            if (gen != generation) return@submit
            s.busy = false
            val (reaction, line) = parseReaction(raw)
            val outcome = h.persuade(approach, reaction)
            by?.let { if (outcome != Hesitation.Outcome.REFUSED) comfort.kindness(h.npcId, it) }
            finishAttempt(s, h, outcome, line, me, other)
        }
        return true
    }

    private fun finishAttempt(s: Session, h: Hesitation, outcome: Hesitation.Outcome, line: String?, me: NpcProfile, other: NpcProfile?) {
        when (outcome) {
            Hesitation.Outcome.STILL_HESITANT -> { line?.let { speak(me.id, it) }; s.nextAuto = clock() + AUTO_GAP }
            Hesitation.Outcome.AGREED -> {
                s.hesitations.remove(h)
                closingLine(me, other, "You have been calmed and you agree; say so, in your own voice, in one short sentence.") { speak(me.id, it) }
                if (s.hesitations.isEmpty()) close(s, refused = false)
            }
            Hesitation.Outcome.REFUSED -> {
                closingLine(me, other, "You are not persuaded and say no for now, in your own voice, in one short sentence.") { speak(me.id, it) }
                comfort.refused(s.initiator, s.target)
                close(s, refused = true)
            }
        }
    }

    private fun closingLine(me: NpcProfile, other: NpcProfile?, what: String, then: (String) -> Unit) {
        val gen = generation
        runner.submit(PRIORITY - 1, "consent", DialogueRequest(me.id, PersonaPrompt.system(me, lore), emptyList(),
            "${other?.name ?: "Your partner"} is with you. $what Reply with the spoken words only.", maxTokens = 40), ttl = 8.0) { raw ->
            if (gen == generation) LineCleaner.clean(raw, me.name)?.let(then)
        }
    }

    /** Drives the partner NPC's own attempts when the player doesn't: call every frame. */
    fun tick() {
        val now = clock()
        for (s in sessions.values.toList()) {
            if (s.phase != Phase.HESITANT || s.busy || now < s.nextAuto) continue
            val h = s.hesitations.firstOrNull() ?: continue
            val partner = profile(h.otherId) ?: continue
            val approach = WillingnessModel.bestApproach(h.disposition, h.fear)
            val me = profile(h.npcId) ?: continue
            s.nextAuto = now + AUTO_GAP
            // the partner speaks the attempt (the model's words), then the hesitant one answers and is judged
            val gen = generation
            s.busy = true
            val ask = DialogueRequest(partner.id, PersonaPrompt.system(partner, lore), emptyList(),
                "${me.name} is hesitant about lying with you. Your way of calming them is: ${approach.label.lowercase()}. " +
                    "Say it in your own voice, one or two short sentences. Reply with the spoken words only.", maxTokens = 50, temperature = 0.8f)
            runner.submit(PRIORITY, "consent", ask, ttl = DECIDE_WAIT) { raw ->
                if (gen != generation) return@submit
                s.busy = false
                LineCleaner.clean(raw, partner.name)?.let { speak(partner.id, it) }
                if (s.phase == Phase.HESITANT && s.hesitations.contains(h)) persuade(h.npcId, approach, by = partner.id)
            }
        }
    }

    private fun fearText(f: Fear) = when (f) {
        Fear.PREJUDICE -> "the old distrust between your peoples"; Fear.SHYNESS -> "shyness and nerves"
        Fear.DEVOUT -> "your faith and scruples"; Fear.GENERAL -> "a general unease"
    }

    private fun parseReaction(raw: String?): Pair<Reaction?, String?> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null to null
        val json = Regex("\\{[^{}]*}").find(text)?.value
        if (json != null) runCatching {
            val o = org.json.JSONObject(json)
            val r = when (o.optString("reaction").lowercase().trim()) { "calmer", "calm" -> Reaction.CALMER; "warier", "wary", "worse" -> Reaction.WARIER; "same", "unmoved" -> Reaction.SAME; else -> null }
            return r to LineCleaner.clean(o.optString("line", "").takeIf { it.isNotBlank() })
        }
        return null to LineCleaner.clean(text)
    }

    companion object {
        const val PRIORITY = 100
        /** Seconds the simulation waits for the decision before it goes on as if willing. */
        const val DECIDE_WAIT = 12.0
        /** Seconds a hesitant pair is given before the invitation ends for now. */
        const val HESITATE_WAIT = 60.0
        /** Seconds before the partner's first try, and between tries. */
        const val AUTO_FIRST = 3.0
        const val AUTO_GAP = 6.0
        /** Seconds a refusal holds before they are asked again. */
        const val COOLDOWN = 150.0
    }
}
