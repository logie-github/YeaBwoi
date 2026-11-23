package com.logie.wifecraft.dialogue

/*
 * Chat mode's prompts: the NPC talks with someone who stands in front of her, an ordinary person of Azeroth with the race, sex,
 * class and name the player chose. She never knows of a player, a game, a screen or typing; nothing here is something she says
 * (the words are the model's). Pure Kotlin, JVM-tested (ChatModeTest).
 */

/** How long a reply may be (the Chat controls' Short/Normal/Long). */
enum class ChatLength(val label: String, val sentences: Int, val chars: Int, val words: Int, val tokens: Int) {
    // [tokens] is the most the model may write (a reply is cut at its sentences before that): about 1.4 tokens a word with the
    // punctuation, so 24 words are about 35 tokens, 42 about 60 and 66 about 95: the caps leave room for a long last sentence and
    // no more. At 4 to 6 tokens a second on the phone every token not written is 0.2 seconds saved.
    SHORT("Short", 1, 150, 24, 45),
    NORMAL("Normal", 2, 280, 42, 80),
    LONG("Long", 3, 440, 66, 140);

    companion object { fun of(name: String?): ChatLength = entries.firstOrNull { it.name == name } ?: NORMAL }
}

/** How a message of the other person reads, for the ease between them ([ChatSession]). */
enum class ChatTone {
    KIND, NEUTRAL, HOSTILE;

    companion object {
        private val HOSTILE_RE = Regex("\\b(idiot|stupid|fool|ugly|shut up|i hate you|hate you|bitch|bastard|worthless|pathetic|disgusting|die)\\b", RegexOption.IGNORE_CASE)
        private val KIND_RE = Regex("\\b(thank|thanks|please|kind|lovely|beautiful|pretty|handsome|friend|glad|sorry|welcome|well met|pleasure|charming|gentle|dear)\\b", RegexOption.IGNORE_CASE)
        fun of(text: String): ChatTone = when {
            HOSTILE_RE.containsMatchIn(text) && !ChatPrompt.raisesDesire(text) -> HOSTILE   // a filthy name in dirty talk is not an insult
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
    /** Words that mean the user raised desire themselves (the talk may go there at once, as the NPC's nature allows). */
    private val DESIRE_RE = Regex("\\b(sex|sexy|bed|kiss|naked|touch|lust|want you|desire|aroused|body|bodies|breasts?|thighs?|undress|seduce|lover|make love|fuck\\w*|fantasy|fantasies|cock|cunt|pussy|tits|ass|cum|horny|slut|whore)\\b", RegexOption.IGNORE_CASE)
    fun raisesDesire(text: String): Boolean = DESIRE_RE.containsMatchIn(text)

    /** What a chat adds after the persona and before the context: the setting of the talk (the same for everyone she talks to). */
    const val FRAME = "Someone stands before you and you talk face to face. They are an ordinary person of this world, like you. " +
        "Answer what they say, remember it, and now and then ask or tell them something."

    /**
     * The system prompt's stable part, for the NPC all chat long: the shared world and rules, her persona, the chat's frame
     * ([FRAME]). Its first lines are the very bytes of the NPC's ambient persona ([PersonaPrompt.persona]), so what the model
     * read for one it borrows for the other. A dragon (Sindragosa) has her own voice: ancient, imperious, patient, cold.
     */
    fun system(npc: NpcProfile, lore: Lore, dragon: Boolean): String {
        val b = StringBuilder()
        if (dragon) {
            b.append(PersonaPrompt.shared(lore)).append("\n\n")
            b.append("You are ${npc.name}, a frost dragon of great age and power, an adult being of Azeroth.\n")
            lore.kit?.let { b.append(it.systemAddition(npc, lore)).append('\n') }
            if (npc.bio.isNotBlank()) b.append("About you: ").append(npc.bio.trim()).append('\n')
            b.append("Your voice: ancient, imperious, patient and cold as the high ice; vast and unhurried, never crude, never hurried; ")
                .append("you regard mortals as small warm creatures and speak to them as a queen speaks to a petitioner, but you are not blind to a mortal who earns your interest. ")
                .append("In love and in bed you are dominant, as a being of your age would be.\n")
        } else {
            b.append(PersonaPrompt.persona(npc, lore))
        }
        b.append(PersonaPrompt.LENGTH_CHAT).append('\n').append(FRAME)
        return b.toString()
    }

    /**
     * What stays the same for a whole talk, read once and kept with the persona: the place, who stands before her, what they are
     * to her, and what she knows of them and the place (at most [KNOW_ITEMS] snippets, [KNOW_CHARS] characters). It goes in the
     * system message, after [system]'s stable part, so the history that follows it is a prefix the model has already read; it is
     * rebuilt (and read again) only when what they are to her changes.
     */
    fun context(lore: Lore, me: NpcProfile, you: ChatCharacter, place: PlaceView, rel: ChatRelationship): String {
        val b = StringBuilder()
        b.append(SceneContext.place(place)).append('\n')
        b.append(presence(you, lore, rel)).append('\n')
        rel.note(you.name)?.let { b.append(it).append('\n') }
        val spouse = rel.tier == ChatRelationship.Tier.SPOUSE
        val know = if (spouse) emptyList() else lore.forScene(me.raceId, me.classId, place.name, listOf(you.raceId to you.classId), withBodies = false, maxChars = KNOW_CHARS, includeSelf = false, maxItems = KNOW_ITEMS)
        if (know.isNotEmpty()) b.append("What you know: ").append(know.joinToString(" ")).append('\n')
        b.append("You are only talking; nothing intimate is happening this moment. Talk of the world, your day, the person before you, the place, lore and gossip. ")
        b.append("Do not make the sounds of lovemaking and do not describe an act as it happens.")
        return b.toString()
    }

    /** Lore snippets a chat's context carries, and their length: two, so the context stays about 150 tokens. */
    const val KNOW_ITEMS = 2
    const val KNOW_CHARS = 420

    /** "a woman", "a man". */
    private fun sexWord(female: Boolean) = if (female) "woman" else "man"

    /** What she is told of the person before her: race, class, name, sex, side and (when not a stranger) what they are to her. No word of a player or a game. */
    fun presence(you: ChatCharacter, lore: Lore, rel: ChatRelationship = you.relationship): String {
        val race = lore.race(you.raceId)?.name ?: "traveller"
        val cls = lore.className(you.classId)?.let { " $it" } ?: ""
        val f = lore.faction(you.raceId)
        val side = if (f == Faction.NEUTRAL) "" else " of the ${f.label}"
        val what = rel.described() ?: return "A $race$cls called ${you.name} stands before you, a ${sexWord(you.female)}$side."
        return "${you.name}, a $race$cls, who is your $what, stands before you: a ${sexWord(you.female)}$side."
    }

    /** The neutral stance she starts a talk in (calm, polite, attentive, no assumed intimacy), told for the first few exchanges. */
    const val NEUTRAL_EXCHANGES = 3
    fun neutralStance(rel: ChatRelationship): String =
        if (rel.tier == ChatRelationship.Tier.STRANGER)
            "You begin this meeting calm and even in mood: polite, attentive and yourself. You assume no intimacy, you are not flirting, aroused, angry or sulking; let how they treat you decide whether you warm or cool."
        else
            "You begin this meeting calm and natural in mood: warm as you are with someone you know, but not already aroused, quarrelling or sulking; let how they treat you decide where the talk goes."

    /**
     * How wary she is of them, from the willingness model (faction, race, her nature, the ease and what they are to her). In the first
     * exchanges ([mildOnly]) never more than a mild reserve: no refusal and no hostility at the start.
     */
    fun wariness(me: NpcProfile, you: ChatCharacter, lore: Lore, band: ComfortBand, rel: ChatRelationship = you.relationship, mildOnly: Boolean = false): String? {
        val r = WillingnessModel.prior(lore, me, you.profile(), rel.relation, band, rel.attitudeScale)
        val c = WillingnessModel.classify(r)
        return when {
            c == Willing.WILLING -> null
            mildOnly -> "You are a little wary of ${you.name}, only because you do not know them or their kind yet: keep a small reserve, stay civil and open, and let them show who they are before you open up; you may hesitate over anything intimate."
            c == Willing.HESITANT -> "You are wary of ${you.name}: what they are, or that you do not know them, gives you pause. Stay civil, keep some distance, and let them earn your trust before you open up; you may hesitate or deflect if they press."
            else -> "You distrust ${you.name} deeply. Be cold and curt, give little away, and refuse anything intimate for now; the ease between you must grow before that changes."
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
        /** How filthy her words are (Options' Dirty talk level) when the talk turns to desire. */
        val dirty: DirtyLevel = DirtyLevel.DEFAULT,
        /** What they are to her (changes apply from the next reply). */
        val relationship: ChatRelationship = you.relationship,
        /** How many messages they have sent already (0: this is the very first reply: the neutral opening). */
        val exchanges: Int = 0,
    )

    /**
     * The user message of one turn, whole: [context] and [tail] together (the shape the prompt had before the context moved into the
     * system message; kept for the tests and for anyone who asks for one message).
     */
    fun user(lore: Lore, t: Turn): String = context(lore, t.me, t.you, t.place, t.relationship) + "\n" + tail(lore, t)

    /**
     * What changes from turn to turn, and nothing else: how at ease they are, the opening stance for the first exchanges, her
     * wariness, the desire notes when the talk goes there, the steers, what they said and the task. Kept short (about 60 tokens
     * in an ordinary turn): it is the only part of the prompt the model has to read anew each turn.
     */
    fun tail(lore: Lore, t: Turn): String {
        val b = StringBuilder()
        b.append("You and ${t.you.name} are ${t.band.label} with each other.\n")
        val opening = t.exchanges < NEUTRAL_EXCHANGES
        if (opening) b.append(neutralStance(t.relationship)).append('\n')
        if (t.exchanges == 0 && t.heard != null) b.append("This is the first thing ${t.you.name} says to you in this meeting: answer it as the neutral first greeting it is, without assuming more than is said.\n")
        wariness(t.me, t.you, lore, t.band, t.relationship, mildOnly = opening)?.let { b.append(it).append('\n') }
        if (t.desireTopic && t.band.bodyTalk) {
            lore.body(t.you.raceId)?.let { b.append("Their body, for you to draw on as you like: ").append(it).append('\n') }
        }
        if (t.desireTopic) {
            lore.kinkGuide(t.band.key)?.let { b.append("How open you are now: ").append(it).append('\n') }
            b.append(DirtyTalk.register(t.dirty)).append('\n')
            b.append("In bed you are ${t.me.disposition.style}.\n")
            kinkNotes(lore, t)?.let { b.append(it) }
        }
        for (s in t.steers) b.append(s.hint()).append('\n')
        if (t.desireTopic) b.append("As your nature and your ease with them allow, you may talk of desire, bodies, memories and what you would like to do with someone; that is talk only.\n")
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
            listOfNotNull(lore.className(me.classId), lore.className(t.you.classId)).map { it.replace(" ", "").lowercase() }, stage = "flirt", limit = KINK_SNIPPETS, level = t.dirty)
        val sb = StringBuilder()
        val snippets = k.snippets(q)
        if (snippets.isNotEmpty()) sb.append("Ideas you may draw on, in your own words: ").append(snippets.joinToString(" ")).append('\n')
        val lines = k.dirty()?.examples(t.dirty, "chat", 4, me.female, (t.heard?.length ?: 0))
        if (!lines.isNullOrEmpty()) sb.append(DirtyTalk.examplesBlock(lines, "Lines in this register, for the feel (never copy them, say something new):"))
        else k.examples(q, 1).firstOrNull()?.let { sb.append("A sample of the register (do not copy it):\n").append(it.render()).append('\n') }
        return sb.takeIf { it.isNotEmpty() }?.toString()
    }

    /**
     * The request for a turn: its own state in the runtime ("chat:" + the NPC's id), selected priority, the length's token cap and
     * sentence stop, partial text passed on, and [guard] (the streamed-text check that ends a wrong answer early).
     */
    fun request(system: String, history: List<DialogueTurn>, user: String, npcId: String, length: ChatLength, onPartial: ((String) -> Unit)?,
        dirty: DirtyLevel = DirtyLevel.DEFAULT, guard: ((String) -> Boolean)? = null): DialogueRequest =
        DialogueRequest(npcId = CHAT_PREFIX + npcId, system = system, history = history, user = user, maxTokens = length.tokens,
            temperature = dirty.temperature, topP = dirty.topP, repeatPenalty = dirty.repeatPenalty, priority = DialoguePriority.SELECTED, onPartial = onPartial,
            tag = "chat", guard = guard, sentences = length.sentences)

    /** The kink pack's snippets a flirting turn draws on. */
    const val KINK_SNIPPETS = 2

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
