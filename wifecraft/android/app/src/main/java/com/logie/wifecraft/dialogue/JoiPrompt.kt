package com.logie.wifecraft.dialogue

/*
 * JOI prompts: what the on-device model is asked for each line of a JOI the selected NPC gives. The NPC believes she (he)
 * is instructing another person in the room, "you": the prompt never names a player, a game, a screen, a camera or an
 * audience (the output is checked for those words too). The words are the model's; nothing here is dialogue. Pure Kotlin.
 */

/** Who speaks: the NPC's name, race and class keys for the pack's twists, personality words, and whether she is a dragon. */
data class JoiPersona(
    val name: String,
    val races: List<String> = emptyList(),
    val classes: List<String> = emptyList(),
    val personalities: Set<String> = setOf("teasing"),
    val role: String? = "dom",
    /** The dragon voice (Sindragosa): ancient, imperious, frost; no person's way of speaking. */
    val dragon: Boolean = false,
    val bio: String = "",
    /** The speaker's sex, for the few-shot lines that only a woman or a man says (null: the lines for anyone). */
    val female: Boolean? = null,
)

/** Steer chips: what the viewer can lean the lines toward. Each has the instruction the prompt gets. */
enum class JoiSteer(val label: String, val instruction: String) {
    TEASE("Tease more", "Tease more: mock them, make them wait, deny them a little, make them squirm."),
    SOFT("Softer", "Be softer and warmer, still in your own dirty voice; praise them with hunger."),
    FIRM("Firmer", "Be firmer and more commanding; short, hard orders."),
    SLOWER("Slower", "Slow everything down; longer waits, smaller steps."),
    DESCRIBE("Describe", "Describe what you see them doing, vividly and dirty, in your own voice."),
    PRAISE("Praise", "Praise them often for what they do well, in your own dirty voice."),
}

/** The stage's request: everything the prompt needs, no state. */
data class JoiLineRequest(
    val persona: JoiPersona,
    val stage: String,
    val band: Band,
    val steer: Set<JoiSteer> = emptySet(),
    /** The last lines she said (newest last): the model must not repeat them. */
    val recent: List<String> = emptyList(),
    /** For the countdown: the number about to be counted, null otherwise. */
    val countdownFrom: Int? = null,
    /** The speaker's id in the scene (the runtime keeps a state per NPC). */
    val npcId: String = "",
    /** What she is doing with her hands as she speaks (JoiMode.action: "your hand is stroking them, slowly"); null: nothing the line must match. */
    val action: String? = null,
    /** How filthy her words are ([DirtyLevel]): Cute .. WTF??!. It sets the register; the band never blocks it. */
    val level: DirtyLevel = DirtyLevel.DEFAULT,
    /** What the viewer typed to steer the lines (standing until cleared): in the next line she says or does it. Null: none. */
    val customSteer: String? = null,
    /** Words to add to the user message (the retry notes). */
    val note: String? = null,
)

object JoiPrompt {
    /** Words that would break the world: the NPC never knows of a player, a game, a screen or a camera. */
    private val FOURTH_WALL = Regex(
        "\\b(player|players|game|gamer|screen|camera|viewer|viewers|audience|app|pixel|pixels|npc|npcs|simulation|ai|model|chatbot|prompt|user|streamer|livestream|subscribers?)\\b",
        RegexOption.IGNORE_CASE)

    const val MAX_CHARS = 260

    /** How many few-shot lines the system prompt shows. */
    const val EXAMPLES = 8
    /** The most tries one line gets: the first, then one retry (a wrong answer costs the model's whole wait again: at 4 to 6 tokens a second two tries are the most a player will sit through). */
    const val TRIES = 2

    /** The comfort band the pack is asked with: the plan's, raised by the level and never lowered by it or by the band. */
    fun bandFor(r: JoiLineRequest): Band = if (r.level.joiBand.ordinal > r.band.ordinal) r.level.joiBand else r.band

    /** Which of the level's lines the ask shows: a place in the list that moves with the stage only, so the prompt's front stays byte for byte the same through a stage (the runtime reuses what it has read of it). */
    private fun seedOf(r: JoiLineRequest): Int = r.stage.length

    /** The few-shot lines [system] shows for [r] (a reply may not copy them). */
    fun shown(r: JoiLineRequest, pack: KinkContext?): List<String> =
        pack?.dirty()?.examples(r.level, r.stage, EXAMPLES, r.persona.female, seedOf(r)).orEmpty()

    fun system(r: JoiLineRequest, pack: KinkContext?): String {
        val p = r.persona
        val sb = StringBuilder()
        sb.append("You are ").append(p.name).append(", an adult").append(if (p.dragon) " frost dragon of great age" else " person")
            .append(" alone in a private room with one other adult, who wants exactly this and asked you for it. ")
        sb.append("You are giving them a JOI: jerk-off instructions. You tell them aloud what to do to themself, how fast, when to stop and when they may cum, and you say it as dirty as your voice goes. ")
        sb.append("Speak only as ").append(p.name).append(" speaks, directly to them as \"you\", in the present tense.\n")
        if (p.dragon) {
            sb.append("Voice: ancient, imperious, patient, cold as the high ice; vast and unhurried; you address them as a small warm creature at your feet. ")
        } else if (p.personalities.isNotEmpty()) {
            sb.append("Your manner: ").append(p.personalities.sorted().joinToString(", ")).append(". ")
        }
        if (p.bio.isNotBlank()) sb.append("About you: ").append(p.bio.take(160)).append(' ')
        sb.append('\n').append(DirtyTalk.register(r.level)).append(' ')
        if (r.level.explicit) sb.append("No word is off limits; say it plainly. ")
        sb.append('\n')
        sb.append("\nRules: one to three short sentences, at most ").append(r.level.joiWords).append(" words. Spoken words only: no stage directions, no asterisks, no quotation marks, ")
        sb.append("no narration, no lists. Never mention players, games, screens, cameras, audiences, apps or anything outside the room. ")
        sb.append("Never repeat an earlier line. Stay in this moment and keep to the voice above.")
        return sb.toString()
    }

    /** The few-shot lines as earlier turns of the talk (an order asked for, the line said): a small model follows the register of turns better than a list, and says one line. */
    fun history(r: JoiLineRequest, pack: KinkContext?): List<DialogueTurn> {
        val task = task(r)
        return shown(r, pack).flatMap { listOf(DialogueTurn(DialogueTurn.Role.USER, task), DialogueTurn(DialogueTurn.Role.ASSISTANT, it)) }
    }

    /** What she is asked to say, by the stage. */
    private fun task(r: JoiLineRequest): String = when {
        r.countdownFrom != null -> "Say one short line before you begin counting down from ${r.countdownFrom}, firm and slow."
        r.stage == "permit" -> "Say the line that lets them cum, right now, in your own words."
        r.stage == "afterglow" -> "Say what you say to them now that they have cum, in your own voice."
        r.level.explicit -> "Say your next order for the ${r.stage} part, ${r.level.taskTail}."
        else -> "Say your next order for the ${r.stage} part."
    }

    /**
     * The prompt is laid out for the runtime's cache (it reuses the byte-identical front of the last prompt): the persona, the rules and the
     * level's register first ([system], the same all JOI long while the level stays), then the level's few-shot turns ([history], the same
     * through a stage), and last what changes every line: the stage hint, the chips, her hand, what she said last, the viewer's steer, the task.
     */
    fun user(r: JoiLineRequest, pack: KinkContext? = null): String {
        val sb = StringBuilder()
        val p = r.persona
        val q = KinkQuery(bandFor(r), p.personalities, p.role, p.races, p.classes, stage = "joi", limit = 2, level = r.level)
        pack?.joiHint(r.stage, q)?.let { sb.append("This moment: ").append(it).append('\n') }
        // care: only in the afterglow, and from Spicy up only now and then, in your own voice
        if (r.stage == "afterglow" && (!r.level.explicit || r.recent.size % 3 == 0)) pack?.care(q.copy(stage = "aftercare"))?.let { sb.append("Care, if it fits, in your own words: ").append(it).append('\n') }
        for (s in r.steer) sb.append(s.instruction).append('\n')
        if (r.recent.isNotEmpty()) sb.append("You already said:\n").append(r.recent.takeLast(3).joinToString("\n") { "- $it" }).append("\n")
        if (r.action != null && !r.persona.dragon) sb.append("Right now ").append(r.action).append(". Let the pace of your orders match it (slow is slow, quick is quick), in the same crude voice as the lines above.\n")
        r.customSteer?.let { sb.append(DirtyTalk.steerHint(it)).append('\n') }
        sb.append(task(r))
        r.note?.let { sb.append('\n').append(it) }
        return sb.toString()
    }

    fun request(r: JoiLineRequest, pack: KinkContext?): DialogueRequest =
        DialogueRequest(npcId = r.npcId.ifEmpty { r.persona.name }, system = system(r, pack), history = history(r, pack), user = user(r, pack), maxTokens = r.level.joiTokens, stop = listOf("\n"),
            temperature = if (r.persona.dragon) 0.75f else r.level.temperature, topP = r.level.topP, repeatPenalty = r.level.repeatPenalty,
            priority = DialoguePriority.SELECTED, tag = "joi")

    /** Adults only: a JOI line that names a child or anyone under age (a small model's wild escalation can reach for one) is refused like the fourth wall. */
    private val MINORS = Regex("\\b(child|children|kid|kids|orphans?|schoolgirls?|schoolboys?|underage|minors?|teens?|teenagers?|toddlers?|infants?|newborns?|little (boy|girl)s?)\\b", RegexOption.IGNORE_CASE)

    /** Whether [text] names something outside the room, or a minor. */
    fun breaksWorld(text: String): Boolean = FOURTH_WALL.containsMatchIn(text) || MINORS.containsMatchIn(text)

    /**
     * The model's reply cleaned for a subtitle: quotes, asterisk actions and stage directions in brackets taken out, a
     * leading "Name:" dropped, clipped to [MAX_CHARS] at a sentence or word; null when nothing usable is left or it breaks
     * the world.
     */
    fun clean(raw: String?, name: String = ""): String? {
        var t = raw ?: return null
        t = t.replace(Regex("\\*[^*]*\\*"), " ").replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\[[^\\]]*\\]"), " ")
        t = t.replace(Regex("[\\p{So}\\p{Sk}\\uFE0F\\u200D]"), "")
        t = t.replace("\"", "").replace("“", "").replace("”", "").replace('\n', ' ')
        if (name.isNotBlank()) t = t.trim().removePrefix("$name:").removePrefix("$name :")
        t = t.replace(Regex("\\s+"), " ").trim().trimStart('-', '•', ' ')
        if (t.length < 2) return null
        if (breaksWorld(t)) return null
        if (t.length > MAX_CHARS) {
            val cut = t.take(MAX_CHARS)
            val end = maxOf(cut.lastIndexOf('.'), cut.lastIndexOf('!'), cut.lastIndexOf('?'))
            t = if (end >= 40) cut.substring(0, end + 1) else cut.substring(0, cut.lastIndexOf(' ').takeIf { it > 40 } ?: MAX_CHARS).trimEnd(',', ';', ' ') + "…"
        }
        return t
    }

    /**
     * One line from [engine] for [r]. A line that names something outside the room, repeats an earlier one or comes back empty is
     * asked again; from Spicy up one with wellness language ([DirtyTalk.WELLNESS]) is asked again once with a note, and so is one that
     * copies a shown line. If the last try still has wellness talk its sentences are cut out; null when nothing is left or none came.
     */
    suspend fun line(engine: DialogueEngine, r: JoiLineRequest, pack: KinkContext?): String? {
        var note: String? = r.note
        var wellness: String? = null
        var copy: String? = null
        var empty = 0
        val shown = shown(r, pack)
        for (attempt in 0 until TRIES) {
            val req = request(r.copy(note = note), pack)
            val got = clean(engine.complete(req.copy(temperature = req.temperature + 0.06f * attempt)), r.persona.name)   // a little livelier each retry
            if (got == null) { if (++empty >= 2) break; continue }   // nothing, or the fourth wall: once more, no more
            if (got in r.recent) { note = DirtyTalk.REPEAT_NOTE; continue }   // a small model sticks: it is told, and gets another try
            if (DirtyTalk.refused(got, r.level)) { wellness = got; note = DirtyTalk.WELLNESS_NOTE }
            else if (DirtyTalk.copies(got, shown)) { copy = got; note = DirtyTalk.COPY_NOTE }
            else return got
        }
        return wellness?.let { DirtyTalk.stripWellness(it) } ?: copy
    }

    private val TRAIT_TO_VARIANT = mapOf(
        "dominant" to "dominant", "bossy" to "dominant", "commanding" to "dominant", "controlling" to "dominant", "domineering" to "dominant",
        "tender" to "tender", "gentle" to "tender", "kind" to "tender", "romantic" to "tender", "caring" to "tender", "loving" to "tender",
        "teasing" to "teasing", "flirty" to "teasing", "playful" to "teasing", "mischievous" to "teasing", "cheeky" to "teasing",
        "strict" to "strict", "orderly" to "strict", "disciplined" to "strict", "perfectionist" to "strict",
        "shy" to "shy", "bashful" to "shy", "timid" to "shy", "modest" to "shy",
        "bold" to "bold", "confident" to "bold", "brash" to "bold", "daring" to "bold", "fearless" to "bold",
    )

    /** The pack's JOI manners for an NPC's creator traits (the ones that name a manner; teasing when none does; the dragon: dominant and strict). */
    fun personalitiesFrom(traits: List<String>, dragon: Boolean = false): Set<String> {
        if (dragon) return setOf("dominant", "strict")
        val out = traits.mapNotNull { TRAIT_TO_VARIANT[it.trim().lowercase()] }.toSet()
        return out.ifEmpty { setOf("teasing") }
    }

    /** The persona key of a class id (the kink pack's class twists) for the ids the creator uses. */
    fun classKey(classId: Int): String? = when (classId) {
        1 -> "warrior"; 2 -> "paladin"; 3 -> "hunter"; 4 -> "rogue"; 5 -> "priest"; 6 -> "deathknight"; 7 -> "shaman"; 8 -> "mage"
        9 -> "warlock"; 10 -> "monk"; 11 -> "druid"; 12 -> "demonhunter"; else -> null
    }

    /** The persona keys of a race id (the kink pack's twist keys) for the ids the creator uses. */
    fun raceKey(raceId: Int): String? = when (raceId) {
        1 -> "human"; 2 -> "orc"; 3 -> "dwarf"; 4 -> "nightelf"; 5 -> "undead"; 6 -> "tauren"; 7 -> "gnome"; 8 -> "troll"
        9 -> "goblin"; 10 -> "bloodelf"; 11 -> "draenei"; 22 -> "worgen"; else -> null
    }
}
