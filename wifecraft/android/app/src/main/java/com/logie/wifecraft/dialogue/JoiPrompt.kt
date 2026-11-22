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
)

/** Steer chips: what the viewer can lean the lines toward. Each has the instruction the prompt gets. */
enum class JoiSteer(val label: String, val instruction: String) {
    TEASE("Tease more", "Tease more: mock-stern, playful, make them wait."),
    SOFT("Softer", "Be softer and more tender; praise warmly."),
    FIRM("Firmer", "Be firmer and more commanding; short orders."),
    SLOWER("Slower", "Slow everything down; longer pauses, smaller steps."),
    DESCRIBE("Describe", "Describe what you see them doing, vividly, in your own voice."),
    PRAISE("Praise", "Praise them often for doing well."),
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
)

object JoiPrompt {
    /** Words that would break the world: the NPC never knows of a player, a game, a screen or a camera. */
    private val FOURTH_WALL = Regex(
        "\\b(player|players|game|gamer|screen|camera|viewer|viewers|audience|app|pixel|pixels|npc|npcs|simulation|ai|model|chatbot|prompt|user|stream|subscribers?)\\b",
        RegexOption.IGNORE_CASE)

    const val MAX_CHARS = 220

    fun system(r: JoiLineRequest, pack: KinkContext?): String {
        val p = r.persona
        val q = KinkQuery(r.band, p.personalities, p.role, p.races, p.classes, stage = "joi", limit = 2)
        val sb = StringBuilder()
        sb.append("You are ").append(p.name).append(", an adult").append(if (p.dragon) " frost dragon of great age" else " person")
            .append(" in a private room with one other adult who has agreed to this and may stop it with a word. ")
        sb.append("You are talking them through a slow, consensual exercise of self-touch, giving instructions aloud. ")
        sb.append("Speak only as ").append(p.name).append(" speaks, directly to them as \"you\", in the present tense. ")
        if (p.dragon) {
            sb.append("Voice: ancient, imperious, patient, cold as the high ice; vast and unhurried, never crude; she addresses you as a small warm creature at her feet. ")
        } else if (p.personalities.isNotEmpty()) {
            sb.append("Your manner: ").append(p.personalities.sorted().joinToString(", ")).append(". ")
        }
        if (p.bio.isNotBlank()) sb.append("About you: ").append(p.bio.take(160)).append(' ')
        pack?.joiHint(r.stage, q)?.let { sb.append("This moment: ").append(it).append(' ') }
        pack?.care(q)?.let { if (r.stage == "setup" || r.stage == "afterglow") sb.append("Care: ").append(it).append(' ') }
        for (s in r.steer) sb.append(s.instruction).append(' ')
        pack?.examples(q, 1)?.firstOrNull()?.let { ex ->
            sb.append("\nExample of the style (do not copy):\n").append(ex.render().take(360)).append('\n')
        }
        sb.append("\nRules: one or two short sentences, at most 30 words. Spoken words only: no stage directions, no asterisks, no quotation marks, ")
        sb.append("no narration, no lists. Never mention players, games, screens, cameras, audiences, apps or anything outside the room. ")
        sb.append("Never repeat an earlier line. Stay in this moment.")
        return sb.toString()
    }

    fun user(r: JoiLineRequest): String {
        val sb = StringBuilder()
        if (r.recent.isNotEmpty()) sb.append("You already said:\n").append(r.recent.takeLast(3).joinToString("\n") { "- $it" }).append("\n")
        when {
            r.countdownFrom != null -> sb.append("Say one short line before you begin counting down from ${r.countdownFrom}, firm and slow.")
            r.stage == "permit" -> sb.append("Say the line that lets you finish, warm and certain.")
            r.stage == "afterglow" -> sb.append("Say something soft and true to bring you down gently.")
            else -> sb.append("Say your next line for the ").append(r.stage).append(" part.")
        }
        return sb.toString()
    }

    fun request(r: JoiLineRequest, pack: KinkContext?): DialogueRequest =
        DialogueRequest(npcId = r.npcId.ifEmpty { r.persona.name }, system = system(r, pack), user = user(r), maxTokens = 70,
            temperature = if (r.persona.dragon) 0.7f else 0.85f, priority = DialoguePriority.SELECTED)

    /** Whether [text] names something outside the room. */
    fun breaksWorld(text: String): Boolean = FOURTH_WALL.containsMatchIn(text)

    /**
     * The model's reply cleaned for a subtitle: quotes, asterisk actions and stage directions in brackets taken out, a
     * leading "Name:" dropped, clipped to [MAX_CHARS] at a sentence or word; null when nothing usable is left or it breaks
     * the world.
     */
    fun clean(raw: String?, name: String = ""): String? {
        var t = raw ?: return null
        t = t.replace(Regex("\\*[^*]*\\*"), " ").replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\[[^\\]]*\\]"), " ")
        t = t.replace("\"", "").replace("“", "").replace("”", "").replace('\n', ' ')
        if (name.isNotBlank()) t = t.trim().removePrefix("$name:").removePrefix("$name :")
        t = t.replace(Regex("\\s+"), " ").trim()
        if (t.length < 2) return null
        if (breaksWorld(t)) return null
        if (t.length > MAX_CHARS) {
            val cut = t.take(MAX_CHARS)
            val end = maxOf(cut.lastIndexOf('.'), cut.lastIndexOf('!'), cut.lastIndexOf('?'))
            t = if (end >= 40) cut.substring(0, end + 1) else cut.substring(0, cut.lastIndexOf(' ').takeIf { it > 40 } ?: MAX_CHARS).trimEnd(',', ';', ' ') + "…"
        }
        return t
    }

    /** One line from [engine] for [r]: tries twice (the second with the model's first reply refused), null when it has none. */
    suspend fun line(engine: DialogueEngine, r: JoiLineRequest, pack: KinkContext?): String? {
        val req = request(r, pack)
        repeat(2) {
            val got = clean(engine.complete(req), r.persona.name)
            if (got != null && got !in r.recent) return got
        }
        return null
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

    /** The persona keys of a race id (the kink pack's twist keys) for the ids the creator uses. */
    fun raceKey(raceId: Int): String? = when (raceId) {
        1 -> "human"; 2 -> "orc"; 3 -> "dwarf"; 4 -> "nightelf"; 5 -> "undead"; 6 -> "tauren"; 7 -> "gnome"; 8 -> "troll"
        9 -> "goblin"; 10 -> "bloodelf"; 11 -> "draenei"; 22 -> "worgen"; else -> null
    }
}
