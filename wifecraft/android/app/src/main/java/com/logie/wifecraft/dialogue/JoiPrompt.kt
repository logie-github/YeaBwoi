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
    /** The compact prompt for a small, fast model (the Fast model): a short persona, three examples, a short last message. */
    val compact: Boolean = false,
)

object JoiPrompt {
    /** Words that would break the world: the NPC never knows of a player, a game, a screen or a camera. */
    private val FOURTH_WALL = Regex(
        "\\b(player|players|game|gamer|screen|camera|viewer|viewers|audience|app|pixel|pixels|npc|npcs|simulation|ai|model|chatbot|prompt|user|streamer|livestream|subscribers?)\\b",
        RegexOption.IGNORE_CASE)

    const val MAX_CHARS = 260

    /** How many few-shot lines the system prompt shows. */
    const val EXAMPLES = 8
    /** The compact prompt's examples: one each of the opening, build and edge families, the same through every stage (the prefix stays byte for byte the same). */
    val COMPACT_FAMILIES = listOf("setup", "build", "edge")
    /** The compact prompt's line: 24 words, 40 tokens, three sentences at the most. */
    const val COMPACT_WORDS = 24
    const val COMPACT_TOKENS = 40
    const val COMPACT_SENTENCES = 3
    /** What a compact last message may hold of the recent lines, the stage's hint and the hand, in characters. */
    const val COMPACT_HINT_CHARS = 110
    const val COMPACT_RECENT_CHARS = 70
    /** The most tries one line gets: the first, then one retry (a wrong answer costs the model's whole wait again: at 4 to 6 tokens a second two tries are the most a player will sit through). */
    const val TRIES = 2

    /** The comfort band the pack is asked with: the plan's, raised by the level and never lowered by it or by the band. */
    fun bandFor(r: JoiLineRequest): Band = if (r.level.joiBand.ordinal > r.band.ordinal) r.level.joiBand else r.band

    /** Which of the level's lines the ask shows: a place in the list that moves with the stage only, so the prompt's front stays byte for byte the same through a stage (the runtime reuses what it has read of it). */
    private fun seedOf(r: JoiLineRequest): Int = r.stage.length

    /** The few-shot lines [system] shows for [r] (a reply may not copy them). */
    fun shown(r: JoiLineRequest, pack: KinkContext?): List<String> =
        if (r.compact) compactShown(r, pack) + stageExamples(r, pack)
        else pack?.dirty()?.examples(r.level, r.stage, EXAMPLES, r.persona.female, seedOf(r)).orEmpty()

    /** The compact prompt's few-shot lines: the first of each of the level's [COMPACT_FAMILIES], the same for every stage. */
    fun compactShown(r: JoiLineRequest, pack: KinkContext?): List<String> =
        COMPACT_FAMILIES.mapNotNull { f -> pack?.dirty()?.examples(r.level, f, 12, r.persona.female, 0)?.let(::shortExample) }

    /** The shortest of [lines] that is still a whole order (35 characters or more): short examples keep the prefix small and make short lines. */
    private fun shortExample(lines: List<String>): String? = lines.filter { it.length >= 35 }.minByOrNull { it.length } ?: lines.maxByOrNull { it.length }

    /** The stage's own voice for the last message: the shortest line of its family, for the stages whose words differ most from the examples (the permit, the afterglow, the count). */
    fun stageExamples(r: JoiLineRequest, pack: KinkContext?): List<String> =
        if (r.stage in STAGE_VOICE) listOfNotNull(pack?.dirty()?.examples(r.level, r.stage, 12, r.persona.female, 0)?.let(::shortExample)) else emptyList()

    private val STAGE_VOICE = setOf("permit", "afterglow", "countdown")

    fun system(r: JoiLineRequest, pack: KinkContext?): String {
        if (r.compact) return compactSystem(r)
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

    /** The compact system prompt: who she is, what she does, her register and the rules, in about 150 tokens; the same all JOI long while the level stays. */
    fun compactSystem(r: JoiLineRequest): String {
        val p = r.persona
        val sb = StringBuilder()
        sb.append("You are ").append(p.name).append(", an adult").append(if (p.dragon) " frost dragon of great age" else " person")
            .append(" alone with one other adult, who asked you for a JOI (jerk-off instructions). Tell them aloud what to do to themself, how fast, when to stop and when they may cum. ")
            .append("Speak only as ").append(p.name).append(", directly to them as \"you\", in the present tense.\n")
        if (p.dragon) sb.append("Voice: ancient, imperious, patient, cold as the high ice; you address them as a small warm creature at your feet.\n")
        else if (p.personalities.isNotEmpty()) sb.append("Your manner: ").append(p.personalities.sorted().joinToString(", ")).append(".\n")
        if (p.bio.isNotBlank()) sb.append("About you: ").append(PersonaPrompt.cap(p.bio, 100)).append('\n')
        sb.append(DirtyTalk.registerCompact(r.level))
        if (r.level.explicit) sb.append(" Each order is ").append(when (r.level) { DirtyLevel.DEPRAVED -> "crude, perverse and shameless"; DirtyLevel.WTF -> "obscene and wildly over the top"; else -> r.level.taskTail }).append('.')
        sb.append("\nRules: one to ").append(COMPACT_SENTENCES).append(" short sentences, at most ").append(COMPACT_WORDS).append(" words, spoken words only: no actions, asterisks, quotes or narration; ")
        sb.append("nothing outside the room; never repeat an earlier line.")
        return sb.toString()
    }

    /** The few-shot lines as earlier turns of the talk (an order asked for, the line said): a small model follows the register of turns better than a list, and says one line. */
    fun history(r: JoiLineRequest, pack: KinkContext?): List<DialogueTurn> {
        if (r.compact) return compactShown(r, pack).flatMap { listOf(DialogueTurn(DialogueTurn.Role.USER, COMPACT_TASK), DialogueTurn(DialogueTurn.Role.ASSISTANT, it)) }
        val task = task(r)
        return shown(r, pack).flatMap { listOf(DialogueTurn(DialogueTurn.Role.USER, task), DialogueTurn(DialogueTurn.Role.ASSISTANT, it)) }
    }

    /** The compact prompt's few-shot ask: the same words every time, whatever the stage. */
    const val COMPACT_TASK = "Next order."

    /** What she is asked to say, by the stage. */
    private fun task(r: JoiLineRequest): String = if (r.compact) compactTask(r) else when {
        r.countdownFrom != null -> "Say one short line before you begin counting down from ${r.countdownFrom}, firm and slow."
        r.stage == "permit" -> "Say the line that lets them cum, right now, in your own words."
        r.stage == "afterglow" -> "Say what you say to them now that they have cum, in your own voice."
        r.level.explicit -> "Say your next order for the ${r.stage} part, ${r.level.taskTail}."
        else -> "Say your next order for the ${r.stage} part."
    }

    private fun compactTask(r: JoiLineRequest): String = when {
        r.countdownFrom != null -> "Say one short line before you count down from ${r.countdownFrom}."
        r.stage == "permit" -> "Say the line that lets them cum, now."
        r.stage == "afterglow" -> "Say what you say to them now that they have cum."
        else -> "Next order, for the ${r.stage} part."
    }

    /** The compact last message: only what changes each line, in about 80 tokens at the most. */
    private fun compactUser(r: JoiLineRequest, pack: KinkContext?): String {
        val sb = StringBuilder()
        val p = r.persona
        val q = KinkQuery(bandFor(r), p.personalities, p.role, p.races, p.classes, stage = "joi", limit = 1, level = r.level)
        pack?.joiHint(r.stage, q)?.let { sb.append("This moment: ").append(PersonaPrompt.cap(it, if (r.stage in STAGE_VOICE) COMPACT_HINT_CHARS * 2 / 3 else COMPACT_HINT_CHARS)).append('\n') }
        for (s in r.steer) sb.append(s.instruction).append('\n')
        stageExamples(r, pack).forEach { sb.append("In this stage you sound like: ").append(it).append('\n') }
        if (r.recent.isNotEmpty()) sb.append("You already said: ").append(r.recent.takeLast(if (r.stage in STAGE_VOICE) 1 else 2).joinToString(" / ") { PersonaPrompt.cap(it, COMPACT_RECENT_CHARS) }).append('\n')
        if (r.action != null && !p.dragon && r.countdownFrom == null && r.stage != "afterglow") sb.append("Right now ").append(r.action).append("; match its pace.\n")
        r.customSteer?.let { sb.append(DirtyTalk.steerHint(it)).append('\n') }
        sb.append(task(r))
        r.note?.let { sb.append('\n').append(it) }
        return sb.toString()
    }

    /**
     * The prompt is laid out for the runtime's cache (it reuses the byte-identical front of the last prompt): the persona, the rules and the
     * level's register first ([system], the same all JOI long while the level stays), then the level's few-shot turns ([history], the same
     * through a stage), and last what changes every line: the stage hint, the chips, her hand, what she said last, the viewer's steer, the task.
     */
    fun user(r: JoiLineRequest, pack: KinkContext? = null): String {
        if (r.compact) return compactUser(r, pack)
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

    fun request(r: JoiLineRequest, pack: KinkContext?, onPartial: ((String) -> Unit)? = null): DialogueRequest =
        DialogueRequest(npcId = engineKey(r), system = system(r, pack), history = history(r, pack), user = user(r, pack), maxTokens = tokensFor(r), stop = listOf("\n"),
            temperature = if (r.persona.dragon) 0.75f else r.level.temperature, topP = r.level.topP, repeatPenalty = r.level.repeatPenalty,
            priority = DialoguePriority.SELECTED, tag = "joi", onPartial = onPartial,
            guard = { text -> breaksWorld(StreamGuard.settled(text)) },
            sentences = if (r.compact) COMPACT_SENTENCES else 0)

    /** The most tokens a line may take: 40 for the compact prompts (28 for the line before a count), the level's own for the full ones. */
    fun tokensFor(r: JoiLineRequest): Int = if (r.compact) (if (r.countdownFrom != null) 28 else COMPACT_TOKENS) else r.level.joiTokens

    /**
     * The speaker's place in the runtime: one per NPC and level (the stable prefix, the persona and the level's examples, is read once
     * for it and kept; another level has other examples and another register, so another place), apart from her ambient and chat places.
     */
    fun engineKey(r: JoiLineRequest): String = JOI_PREFIX + r.npcId.ifEmpty { r.persona.name } + ":" + r.level.key

    const val JOI_PREFIX = "joi:"

    /**
     * Reads this NPC's stable prefix (the persona, the register and the level's examples: [system] and [history]) into the model now,
     * before the first line is asked for: when JOI is tapped, when the level changes. The first line then reads only its own last message.
     */
    fun warm(engine: DialogueEngine, r: JoiLineRequest, pack: KinkContext?, urgent: Boolean = true) {
        if (!engine.ready) return
        val c = r.copy(compact = r.compact || engine.compactPrompts)
        engine.warm(engineKey(c), system(c, pack), urgent, history(c, pack))
    }

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
    suspend fun line(engine: DialogueEngine, r0: JoiLineRequest, pack: KinkContext?, onPartial: ((String) -> Unit)? = null): String? {
        val r = if (engine.compactPrompts && !r0.compact) r0.copy(compact = true) else r0
        var note: String? = r.note
        var wellness: String? = null
        var copy: String? = null
        var empty = 0
        val shown = shown(r, pack)
        for (attempt in 0 until TRIES) {
            // the words as they come, for the subtitle that types itself out (the first try only: a retry replaces a line that was refused)
            var sofar = ""
            val live: ((String) -> Unit)? = if (onPartial != null && attempt == 0) { piece -> sofar += piece; clean(StreamGuard.settled(sofar), r.persona.name)?.let(onPartial) } else null
            if (attempt > 0) onPartial?.invoke("")   // the first try was refused: its words leave the screen while the second is written
            val req = request(r.copy(note = note), pack, live)
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
