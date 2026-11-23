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
    private val turns = ArrayDeque<DialogueTurn>()
    private var system: String = ChatPrompt.system(npc, lore, dragon)
    private var systemEra: String? = lore.kit?.eraKey

    /** An era change (the scene menu) changes the persona: her system prompt is made again, from the next reply. */
    private fun refreshSystem() {
        val k = lore.kit?.eraKey
        if (k != systemEra) { systemEra = k; system = ChatPrompt.system(npc, lore, dragon) }
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

    /** The system prompt in use (stable all chat long). */
    fun systemPrompt(): String { refreshSystem(); return system }

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
        typing = true; quiet = false; partial = ""
        if (said != null) synchronized(lines) { lines += Line(true, said) }
        try {
            val reply = ask(said, engine)
            if (reply == null) { quiet = true; return null }
            synchronized(lines) { lines += Line(false, reply) }
            if (said != null) { remember(DialogueTurn.Role.USER, said); afterExchange(said); exchanges++ }
            remember(DialogueTurn.Role.ASSISTANT, reply)
            return reply
        } finally { typing = false; partial = "" }
    }

    private suspend fun ask(said: String?, engine: DialogueEngine): String? {
        val desire = (said != null && ChatPrompt.raisesDesire(said)) || steers.forPair(npc.id, ChatCharacter.ID).any { it.text == ChatTopics.DESIRE } ||
            (exchanges >= ChatPrompt.NEUTRAL_EXCHANGES + 1 && band >= ComfortBand.FAMILIAR && random.nextFloat() < npc.disposition.desireTalk * 0.5f)
        var note: String? = null
        repeat(2) { attempt ->
            val turn = ChatPrompt.Turn(npc, you, place, band, steers.hints(npc.id, ChatCharacter.ID), said, desire, length, kink, note, dirty, relationship, exchanges)
            refreshSystem()
            val user = (lore.kit?.turnNotes(npc, emptyList(), said.orEmpty())?.let { "$it\n" } ?: "") + ChatPrompt.user(lore, turn)
            val req = ChatPrompt.request(system, turns.toList(), user, npc.id, length, { p -> partial = ChatClean.preview(p, npc.name, length) }, if (desire) dirty else DirtyLevel.DEFAULT)
            val raw = engine.complete(req)
            if (raw == null) return null   // no answer at all: asking again would not help
            val line = ChatClean.clean(raw, npc.name, length)
            val problem = line?.let { problemWith(it) }
            if (line != null && problem == null) return line
            if (line != null) {
                // a live vocalisation is rewritten away when something worth saying is left; the others are asked again once
                if (problem == LIVE) LiveFilter.rewrite(line)?.let { return it }
                note = when (problem) {
                    LIVE -> "Speak of it only in words; make none of the sounds of the act itself."
                    WALL -> "Speak only as a person of Azeroth in the room with them would; mention nothing beyond this world."
                    else -> "Speak as people of Azeroth do; do not use the word \"$problem\" or anything like it."
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

    private fun remember(role: DialogueTurn.Role, text: String) {
        turns.addLast(DialogueTurn(role, text))
        while (turns.size > HISTORY) turns.removeFirst()
    }

    /** What the model is sent as history (the rolling talk), for tests and the screen. */
    fun history(): List<DialogueTurn> = turns.toList()

    /** The talk forgotten: the log, the history, the ease and the steers (leaving the chat, a reloaded scene). */
    fun reset() {
        synchronized(lines) { lines.clear() }
        turns.clear(); comfort.clear(); steers.clearAll(); exchanges = 0; relationship = you.relationship
        partial = ""; typing = false; quiet = false
    }

    companion object {
        const val HISTORY = 14
        private const val WALL = "\u0000wall"
        private const val LIVE = "\u0000live"
    }
}
