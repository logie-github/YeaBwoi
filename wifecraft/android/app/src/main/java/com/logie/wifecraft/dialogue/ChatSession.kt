package com.logie.wifecraft.dialogue

import kotlin.random.Random

/*
 * One Chat-mode talk: the NPC and the player's character, the rolling history, the ease between them, the steers and the reply being
 * made. In memory only: [reset] (leaving the chat, a reloaded scene) throws all of it away; nothing is saved. The NPC treats the
 * player's character as an ordinary person in the world: her persona, ease, hesitation, steers and the filters (fourth wall,
 * anachronisms, live vocalisation) are the ones of NPC-to-NPC talk. Pure Kotlin, JVM-tested (ChatModeTest).
 */
class ChatSession(
    val npc: NpcProfile,
    val you: ChatCharacter,
    private val lore: Lore,
    /** She is a dragon (Sindragosa): her own voice ([ChatPrompt.system]). */
    val dragon: Boolean = false,
    val place: PlaceView = PlaceView("the place"),
    private val kink: KinkContext? = null,
    private val random: Random = Random.Default,
    /** Called when the relationship is changed during the chat (the screen keeps it as the remembered choice). */
    private val onRelationship: ((ChatRelationship) -> Unit)? = null,
) {
    data class Line(val fromYou: Boolean, val text: String)

    /** Who speaks in the log. */
    val youName: String get() = you.name
    val npcName: String get() = npc.name

    val comfort = ComfortLedger()
    val steers = SteerBoard()
    var length = ChatLength.NORMAL
    /** How filthy her words are when the talk turns to desire (Options' Dirty talk level; the screen sets it from the settings). */
    @Volatile var dirty: DirtyLevel = DirtyLevel.DEFAULT

    private val lines = ArrayList<Line>()
    /** The user message of the attempt whose reply was taken (what the model read last), for the history. */
    private var acceptedTail: String? = null
    private val turns = ArrayDeque<DialogueTurn>()
    /** Beside each of [turns]: what the player said, for their turns (their message in the history may be the whole turn's message as it was sent); null for hers. */
    private val plains = ArrayDeque<String?>()
    /** The stable part of the system prompt: the world's rules and her persona, the chat's frame (the NPC's, for as long as she is in the scene). */
    private var persona: String = ChatPrompt.system(npc, lore, dragon)
    /** The persona and the context of this talk (the place, who stands before her, what they are to her): read once, rebuilt when what they are to her changes. */
    private var systemText: String = ""
    private var systemFor: ChatRelationship? = null
    private var systemEra: String? = lore.kit?.eraKey
    /** The compact prompts (the Fast model) are in force: set from the engine at the warm-up and at each reply. */
    @Volatile private var compact: Boolean = false
    private var systemCompact: Boolean = false

    /** The engine the talk will use: a small model reads the compact prompts; a change makes the persona and the context again. */
    private fun useEngine(engine: DialogueEngine?) { if (engine != null) compact = engine.compactPrompts }

    /** An era change (the scene menu) changes the persona: her system prompt is made again, from the next reply. */
    private fun refreshSystem() {
        val k = lore.kit?.eraKey
        if (k != systemEra || compact != systemCompact) { systemEra = k; systemCompact = compact; persona = ChatPrompt.system(npc, lore, dragon, compact); systemFor = null }
    }

    /** The talk so far (newest last); readable from the screen. */
    val log: List<Line> get() = synchronized(lines) { lines.toList() }

    /** The reply coming in, tidied, while the model writes it (empty when none is). Written on the model's thread. */
    @Volatile var partial: String = ""
        private set
    /** The model is writing a reply (the typing indicator). */
    @Volatile var typing: Boolean = false
        private set
    /** The last reply the model could not give (nothing came back, or every try was refused): the screen says she is quiet. */
    @Volatile var quiet: Boolean = false
        private set

    /** What they are to her: from the setup sheet, changeable during the chat (it applies from the next reply). */
    @Volatile var relationship: ChatRelationship = you.relationship
        private set

    fun changeRelationship(r: ChatRelationship) {
        val c = r.copy(custom = r.text)
        relationship = c
        onRelationship?.invoke(c)
    }

    /** How many messages they have sent in this talk. */
    var exchanges = 0
        private set

    /**
     * The ease between her and the player's character, through her own nature; never under what their relationship starts it at
     * (strangers: nothing, a friend: familiar, a spouse: very comfortable). A fresh session has no leftover comfort.
     */
    val band: ComfortBand get() = maxOf(comfort.band(npc.disposition, npc.id, ChatCharacter.ID), relationship.baseBand)

    /** The system prompt in use: [personaPrompt], then the context (stable all chat long; it changes only with what they are to her). */
    fun systemPrompt(): String {
        refreshSystem()
        val rel = relationship
        if (systemFor != rel) { systemText = persona + "\n\n" + ChatPrompt.context(lore, npc, you, place, rel, compact); systemFor = rel }
        return systemText
    }

    /** The persona alone, the same for every talk with her (and the first bytes of [systemPrompt]): what a warm-up reads ahead of a chat. */
    fun personaPrompt(): String { refreshSystem(); return persona }

    /** When the reply being made was asked for, milliseconds on the clock the screen uses (0: none is being made): the screen says how long she has been thinking. */
    @Volatile var typingSince: Long = 0L
        private set

    /**
     * Reads her persona into the model now, before the first message (the chat's setup sheet is open): the first reply then pays for
     * the new words only. [withContext]: once the chat has started (who stands before her is known), the context too, while the player types.
     */
    fun warmUp(engine: DialogueEngine?, withContext: Boolean = false) {
        useEngine(engine)
        if (engine?.ready == true) engine.warm(engineId, if (withContext) systemPrompt() else personaPrompt(), urgent = true)
    }

    /** How far her persona has been read into [engine], 0 to 1 (1: ready, or no engine): the "Getting ready..." line while a reply is awaited. */
    fun readiness(engine: DialogueEngine?): Float = engine?.warmProgress(engineId) ?: 1f

    /** The persona's id in the runtime for this chat ([ChatPrompt.CHAT_PREFIX] + her id), to be given up when the chat ends. */
    val engineId: String get() = ChatPrompt.CHAT_PREFIX + npc.id

    /** A topic steer on (true) or off, for the chips: a nudge toward the topic, in force until it is toggled off. */
    fun toggleTopic(topic: String): Boolean {
        val on = steers.forPair(npc.id, ChatCharacter.ID).any { it.text == topic }
        if (on) steers.remove(topic) else steers.addPair(npc.id, ChatCharacter.ID, Steer(topic, SteerStrength.FIRM))
        return !on
    }

    fun topicOn(topic: String): Boolean = steers.forPair(npc.id, ChatCharacter.ID).any { it.text == topic }

    /**
     * The player's [text] (null or blank: they stand waiting and she speaks first, as a chip asks) and her answer from [engine]:
     * the reply shown (also added to the log and the history), or null when there is none (no engine, nothing back, every try
     * refused). A message sent while another is being answered is turned away (null). The ease rises with a friendly talk.
     */
    suspend fun say(text: String?, engine: DialogueEngine?): String? {
        if (typing) return null
        val said = text?.trim()?.takeIf { it.isNotEmpty() }
        if (engine == null || !engine.ready) { quiet = true; return null }
        useEngine(engine)
        acceptedTail = null
        typing = true; quiet = false; partial = ""; typingSince = System.currentTimeMillis()
        if (said != null) synchronized(lines) { lines += Line(true, said) }
        try {
            val reply = ask(said, engine)
            if (reply == null) { quiet = true; return null }
            synchronized(lines) { lines += Line(false, reply) }
            // the history repeats what was sent: the model has already read that turn, so the next prompt starts with the whole of this one.
            // A turn whose message grew big (desire notes: the kink pack's lines and the register) is kept as what the player said, so
            // the history stays small; the model then reads that one turn again.
            val sent = acceptedTail
            val asUser = if (sent != null && sent.length <= FAITHFUL_CHARS) sent else said
            if (asUser != null) remember(DialogueTurn.Role.USER, asUser, said)
            if (said != null) { afterExchange(said); exchanges++ }
            remember(DialogueTurn.Role.ASSISTANT, reply)
            return reply
        } finally { typing = false; partial = ""; typingSince = 0L }
    }

    private suspend fun ask(said: String?, engine: DialogueEngine): String? {
        val desire = (said != null && ChatPrompt.raisesDesire(said)) || steers.forPair(npc.id, ChatCharacter.ID).any { it.text == ChatTopics.DESIRE } ||
            (exchanges >= ChatPrompt.NEUTRAL_EXCHANGES + 1 && band >= ComfortBand.FAMILIAR && random.nextFloat() < npc.disposition.desireTalk * 0.5f)
        var note: String? = null
        val system = systemPrompt()
        repeat(2) { attempt ->
            val turn = ChatPrompt.Turn(npc, you, place, band, steers.hints(npc.id, ChatCharacter.ID), said, desire, length, kink, note, dirty, relationship, exchanges, compact)
            val user = (lore.kit?.turnNotes(npc, emptyList(), said.orEmpty(), compact = compact)?.let { "$it\n" } ?: "") + ChatPrompt.tail(lore, turn)
            // a wrong word is seen while the reply is still being written: it is stopped there (nothing of it is shown) and asked again at once
            var caught: String? = null
            val guard: (String) -> Boolean = { text -> StreamGuard.problem(text).also { if (it != null) caught = it } != null }
            // the engine hands over the answer piece by piece: the reply so far is what the bubble shows (word by word, as it is written)
            val written = StringBuilder()
            val req = ChatPrompt.request(system, turns.toList(), user, npc.id, length, { p -> written.append(p); partial = ChatClean.preview(StreamGuard.settled(written.toString()), npc.name, length) },
                if (desire) dirty else DirtyLevel.DEFAULT, guard)
            val raw = engine.complete(req)
            if (raw == null) {
                val what = caught ?: return null   // no answer at all: asking again would not help
                if (attempt == 0) { partial = ""; note = StreamGuard.note(what); return@repeat }
                return null
            }
            val line = ChatClean.clean(raw, npc.name, length)
            val problem = line?.let { problemWith(it) }
            if (line != null && problem == null) { acceptedTail = user; return line }
            if (line != null) {
                // a live vocalisation is rewritten away when something worth saying is left; the others are asked again once
                if (problem == LIVE) LiveFilter.rewrite(line)?.let { acceptedTail = user; return it }
                note = when (problem) {
                    LIVE -> "Speak of it only in words; make none of the sounds of the act itself."
                    else -> StreamGuard.note(if (problem == WALL) StreamGuard.WALL else problem!!)
                }
            }
        }
        return null
    }

    /** What is wrong with [line] for this talk: [WALL] (it names something beyond the world), [LIVE] (live vocalisation: chat is no sexual stage), an anachronism, or null. */
    private fun problemWith(line: String): String? {
        if (JoiPrompt.breaksWorld(line)) return WALL
        Anachronisms.find(line)?.let { return it }
        if (LiveFilter.isLive(line)) return LIVE
        return null
    }

    private fun afterExchange(said: String) {
        when (ChatTone.of(said)) {
            ChatTone.KIND -> comfort.chatted(npc.id, ChatCharacter.ID, 0.3f)
            ChatTone.NEUTRAL -> comfort.chatted(npc.id, ChatCharacter.ID, 0.2f)
            ChatTone.HOSTILE -> comfort.hesitated(npc.id, ChatCharacter.ID)
        }
    }

    private fun remember(role: DialogueTurn.Role, text: String, plain: String? = null) {
        turns.addLast(DialogueTurn(role, text)); plains.addLast(plain)
        // the history is part of the prompt the model has already read: dropping its oldest turn one at a time would change the
        // prompt right after the persona every turn and make the model read it all again. Dropping six at once costs one re-read
        // every three exchanges instead of one every exchange.
        if (turns.size > HISTORY) {
            while (turns.size > HISTORY_KEEP) { turns.removeFirst(); plains.removeFirst() }
            while (turns.firstOrNull()?.role == DialogueTurn.Role.ASSISTANT) { turns.removeFirst(); plains.removeFirst() }   // the talk starts with what they said
            // the model reads the prompt again from here anyway, so the messages that stay go back to the short form (what the player
            // said): a turn she opened herself, with nothing said, is left out. The turns after this one are repeated as sent again.
            val kept = turns.toList(); val shortForms = plains.toList()
            turns.clear(); plains.clear()
            for (i in kept.indices) {
                if (kept[i].role != DialogueTurn.Role.USER) { turns.addLast(kept[i]); plains.addLast(null); continue }
                val said = shortForms[i] ?: continue
                turns.addLast(DialogueTurn(DialogueTurn.Role.USER, said)); plains.addLast(said)
            }
        }
    }

    /** What the model is sent as history (the rolling talk), for tests and the screen. */
    fun history(): List<DialogueTurn> = turns.toList()

    /** The talk forgotten: the log, the history, the ease and the steers (leaving the chat, a reloaded scene). */
    fun reset() {
        synchronized(lines) { lines.clear() }
        turns.clear(); plains.clear(); comfort.clear(); steers.clearAll(); exchanges = 0; relationship = you.relationship
        partial = ""; typing = false; quiet = false
    }

    companion object {
        /** The most messages the rolling history holds; past it the oldest are dropped down to [HISTORY_KEEP] in one step (see [remember]). */
        const val HISTORY = 12
        const val HISTORY_KEEP = 6
        /** A turn's message up to this long is kept in the history as it was sent (about 220 tokens: an opening turn's, not a flirting one's). */
        const val FAITHFUL_CHARS = 900
        private const val WALL = StreamGuard.WALL
        private const val LIVE = "\u0000live"
    }
}

/** What the chat says while she is writing: honest about the wait (a 12B model on a phone takes seconds to begin). */
object ChatWait {
    /** The words after her name while a reply is made and no word of it has come yet: "answering" for the first 3 seconds, then how long. */
    fun thinking(name: String, elapsedMs: Long, dots: Int, readiness: Float = 1f): String {
        val s = (elapsedMs / 1000).coerceAtLeast(0)
        // her persona is still being read into the model: say so, with how far it is, rather than leave a silent wait
        if (readiness < 1f) return "Getting ready... ${(readiness.coerceIn(0f, 1f) * 100).toInt()}%"
        return if (s < 3) "$name is answering" + ".".repeat(dots.coerceIn(1, 3)) else "$name is thinking" + ".".repeat(dots.coerceIn(1, 3)) + " $s s"
    }

    /** While the model is still being read into memory. [progress] 0 to 1, or below 0 when not known. */
    fun loading(progress: Float): String = if (progress in 0f..1f) "Loading the model... ${(progress * 100).toInt()}%" else "Loading the model..."
}
