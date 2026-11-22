package com.logie.wifecraft.dialogue

/*
 * Chat mode's prompts: the NPC talks with someone who stands in front of her, an ordinary person of Azeroth with the race, sex,
 * class and name the player chose. She never knows of a player, a game, a screen or typing; nothing here is something she says
 * (the words are the model's). Pure Kotlin, JVM-tested (ChatModeTest).
 */

/** How long a reply may be (the Chat controls' Short/Normal/Long). */
enum class ChatLength(val label: String, val sentences: Int, val chars: Int, val words: Int, val tokens: Int) {
    SHORT("Short", 1, 150, 24, 60),
    NORMAL("Normal", 2, 280, 42, 100),
    LONG("Long", 3, 440, 66, 150);

    companion object { fun of(name: String?): ChatLength = entries.firstOrNull { it.name == name } ?: NORMAL }
}

/** How a message of the other person reads, for the ease between them ([ChatSession]). */
enum class ChatTone {
    KIND, NEUTRAL, HOSTILE;

    companion object {
        private val HOSTILE_RE = Regex("\\b(idiot|stupid|fool|ugly|shut up|i hate you|hate you|bitch|bastard|worthless|pathetic|disgusting|die)\\b", RegexOption.IGNORE_CASE)
        private val KIND_RE = Regex("\\b(thank|thanks|please|kind|lovely|beautiful|pretty|handsome|friend|glad|sorry|welcome|well met|pleasure|charming|gentle|dear)\\b", RegexOption.IGNORE_CASE)
        fun of(text: String): ChatTone = when {
            HOSTILE_RE.containsMatchIn(text) -> HOSTILE
            KIND_RE.containsMatchIn(text) -> KIND
            else -> NEUTRAL
        }
    }
}

/** The topic chips above the keyboard: each is a steer (a nudge for the model), never a line anyone says. */
object ChatTopics {
    data class Chip(val label: String, val topic: String)
    /** The desire chip's topic: a steer that lets the talk go there at once (as her nature allows). */
    const val DESIRE = "desire and what you would like of someone"
    val CHIPS = listOf(
        Chip("Her day", "how your day has gone"),
        Chip("The war", "the war, the Scourge and the Horde and the Alliance"),
        Chip("Flirt", "flirting with them, lightly and in your own way"),
        Chip("Her past", "your past and where you came from"),
        Chip("Her people", "your people and your home"),
        Chip("Desire", DESIRE),
    )
}

object ChatPrompt {
    /** The persona's rules without the line about length (the chat's own length is set per reply). */
    val RULES: String = PersonaPrompt.RULES.replace("Speak in short natural lines, one or two sentences. ", "Speak in natural lines. ")

    /** Words that mean the user raised desire themselves (the talk may go there at once, as the NPC's nature allows). */
    private val DESIRE_RE = Regex("\\b(sex|sexy|bed|kiss|naked|touch|lust|want you|desire|aroused|body|bodies|breasts?|thighs?|undress|seduce|lover|make love|fuck|fantasy|fantasies)\\b", RegexOption.IGNORE_CASE)
    fun raisesDesire(text: String): Boolean = DESIRE_RE.containsMatchIn(text)

    /**
     * The system prompt: stable for the NPC all chat long (the person in front of her is in each turn). A dragon (Sindragosa) has
     * her own voice: ancient, imperious, patient, cold.
     */
    fun system(npc: NpcProfile, lore: Lore, dragon: Boolean): String {
        val b = StringBuilder()
        if (dragon) {
            b.append("You are ${npc.name}, a frost dragon of great age and power, an adult being of Azeroth. ").append(PersonaPrompt.ERA).append('\n')
            if (npc.bio.isNotBlank()) b.append("About you: ").append(npc.bio.trim()).append('\n')
            b.append("Your voice: ancient, imperious, patient and cold as the high ice; vast and unhurried, never crude, never hurried; ")
                .append("you regard mortals as small warm creatures and speak to them as a queen speaks to a petitioner, but you are not blind to a mortal who earns your interest. ")
                .append("In love and in bed you are dominant, as a being of your age would be.\n")
            b.append(RULES)
        } else {
            b.append(PersonaPrompt.system(npc, lore).replace(PersonaPrompt.RULES, RULES))
        }
        b.append("\nSomeone stands in front of you and you are talking with them face to face, as people do. They are an ordinary person of this world, like you. ")
        b.append("Answer what they say, remember what has been said, and now and then ask them something or tell them something of yourself.")
        return b.toString()
    }

    /** "a woman", "a man". */
    private fun sexWord(female: Boolean) = if (female) "woman" else "man"

    /** What she is told of the person before her: race, class, name, sex and side. No word of a player or a game. */
    fun presence(you: ChatCharacter, lore: Lore): String {
        val race = lore.race(you.raceId)?.name ?: "traveller"
        val cls = lore.className(you.classId)?.let { " $it" } ?: ""
        val f = lore.faction(you.raceId)
        val side = if (f == Faction.NEUTRAL) "" else " of the ${f.label}"
        return "A $race$cls called ${you.name} stands before you, a ${sexWord(you.female)}$side."
    }

    /** How wary she is of them at the start, from the willingness model (faction, race, her nature, the ease between them). */
    fun wariness(me: NpcProfile, you: ChatCharacter, lore: Lore, band: ComfortBand): String? {
        val r = WillingnessModel.prior(lore, me, you.profile(), Relation(), band)
        return when (WillingnessModel.classify(r)) {
            Willing.WILLING -> null
            Willing.HESITANT -> "You are wary of ${you.name}: what they are, or that you do not know them, gives you pause. Stay civil, keep some distance, and let them earn your trust before you open up; you may hesitate or deflect if they press."
            Willing.REFUSED -> "You distrust ${you.name} deeply. Be cold and curt, give little away, and refuse anything intimate for now; the ease between you must grow before that changes."
        }
    }

    /** Everything one turn's request needs. */
    class Turn(
        val me: NpcProfile,
        val you: ChatCharacter,
        val place: PlaceView,
        val band: ComfortBand,
        val steers: List<Steer>,
        /** What they just said (null: they stand waiting for you to speak). */
        val heard: String?,
        val desireTopic: Boolean,
        val length: ChatLength,
        val kink: KinkContext? = null,
        /** Extra words appended after a refused reply (the anachronism or the fourth wall). */
        val retryNote: String? = null,
    )

    /** The user message of one turn: the place, who stands before her and what the lore says of them, the ease, steers, what was said, the task. */
    fun user(lore: Lore, t: Turn): String {
        val b = StringBuilder()
        b.append(SceneContext.place(t.place)).append('\n')
        b.append(presence(t.you, lore)).append('\n')
        val know = lore.forScene(t.me.raceId, t.me.classId, t.place.name, listOf(t.you.raceId to t.you.classId), withBodies = false, maxChars = 900, includeSelf = false)
        if (know.isNotEmpty()) b.append("What you know: ").append(know.joinToString(" ")).append('\n')
        b.append("You and ${t.you.name} are ${t.band.label} with each other.\n")
        wariness(t.me, t.you, lore, t.band)?.let { b.append(it).append('\n') }
        if (t.desireTopic && t.band.bodyTalk) {
            lore.body(t.you.raceId)?.let { b.append("Their body, for you to draw on as you like: ").append(it).append('\n') }
        }
        if (t.desireTopic) {
            lore.kinkGuide(t.band.key)?.let { b.append("How open you are now: ").append(it).append('\n') }
            b.append("In bed you are ${t.me.disposition.style}.\n")
            kinkNotes(lore, t)?.let { b.append(it) }
        }
        for (s in t.steers) b.append(s.hint()).append('\n')
        b.append("You are only talking; nothing intimate is happening this moment. Talk of the world, your day, the person before you, the place, lore and gossip")
        if (t.desireTopic) b.append(", or, as your nature and your ease with them allow, of desire, bodies, memories and what you would like to do with someone; that is talk only")
        b.append(". Do not make the sounds of lovemaking and do not describe an act as it happens.\n")
        if (t.heard != null) b.append("${t.you.name} says to you: \"").append(t.heard).append("\"\n")
        else b.append("${t.you.name} stands quietly, waiting for you to speak.\n")
        t.retryNote?.let { b.append(it).append('\n') }
        b.append("Say your next line to ${t.you.name} in at most ${t.length.words} words, ${if (t.length.sentences == 1) "one sentence" else "up to ${t.length.sentences} sentences"}, in your own voice. Reply with the spoken words only.")
        return b.toString()
    }

    private fun kinkNotes(lore: Lore, t: Turn): String? {
        val k = t.kink ?: return null
        if (t.band.kink < Band.ACQUAINTANCES) return null
        val me = t.me
        val q = KinkQuery(t.band.kink, TurnPrompt.personalityWords(me), TurnPrompt.roleOf(me.disposition),
            listOfNotNull(lore.raceKey(me.raceId), lore.raceKey(t.you.raceId)),
            listOfNotNull(lore.className(me.classId), lore.className(t.you.classId)).map { it.replace(" ", "").lowercase() }, stage = "flirt", limit = 3)
        val sb = StringBuilder()
        val snippets = k.snippets(q)
        if (snippets.isNotEmpty()) sb.append("Ideas you may draw on, in your own words: ").append(snippets.joinToString(" ")).append('\n')
        k.examples(q, 1).firstOrNull()?.let { sb.append("A sample of the register (do not copy it):\n").append(it.render()).append('\n') }
        return sb.takeIf { it.isNotEmpty() }?.toString()
    }

    /** The request for a turn: its own state in the runtime ("chat:" + the NPC's id), selected priority, the length's cap, partial text passed on. */
    fun request(system: String, history: List<DialogueTurn>, user: String, npcId: String, length: ChatLength, onPartial: ((String) -> Unit)?): DialogueRequest =
        DialogueRequest(npcId = CHAT_PREFIX + npcId, system = system, history = history, user = user, maxTokens = length.tokens,
            temperature = 0.85f, priority = DialoguePriority.SELECTED, onPartial = onPartial)

    const val CHAT_PREFIX = "chat:"
}

/** The model's raw answer made into the reply shown: tags, actions and quotes taken out, cut to the length. */
object ChatClean {
    private val ACTIONS = Regex("\\*[^*]*\\*|\\([^)]*\\)|\\[[^\\]]*]")

    /** A reply for [length]: up to its sentences and characters, cut at a sentence end (else a word); null when nothing is left. */
    fun clean(raw: String?, speakerName: String?, length: ChatLength): String? {
        var s = raw ?: return null
        s = s.replace(ACTIONS, " ").replace('\n', ' ')
        s = tidy(s, speakerName)
        if (s.isEmpty()) return null
        val parts = Regex("[^.!?…]+[.!?…]+[\"”']?|[^.!?…]+$").findAll(s).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        val out = StringBuilder()
        var n = 0
        for (p in parts) {
            if (n >= length.sentences) break
            val add = if (out.isEmpty()) p else " $p"
            if (out.length + add.length > length.chars) {
                if (out.isEmpty()) out.append(p.take(length.chars - 1).substringBeforeLast(' ', p.take(length.chars - 1)).trimEnd(',', ';', ':', ' ')).append("...")
                break
            }
            out.append(add); n++
        }
        val line = out.toString().trim()
        return if (line.length < 2) null else line
    }

    /** What the typing indicator shows while the answer is coming: the same tidying, no cut (an unfinished action or quote is left out). */
    fun preview(partial: String, speakerName: String?, length: ChatLength): String {
        var s = partial.replace(ACTIONS, " ")
        s = s.substringBefore('*')   // an action still open
        s = tidy(s.replace('\n', ' '), speakerName)
        return if (s.length > length.chars) s.take(length.chars) else s
    }

    private fun tidy(text: String, speakerName: String?): String {
        var s = text.trim()
        if (speakerName != null) {
            val first = speakerName.substringBefore(' ')
            s = s.replace(Regex("^(${Regex.escape(speakerName)}|${Regex.escape(first)})\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE), "")
        }
        return s.replace(Regex("^[\"“”'‘’]+|[\"“”]+$"), "").replace(Regex("\\s+"), " ").trim()
    }
}
