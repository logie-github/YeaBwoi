package com.logie.wifecraft.dialogue

/*
 * THE CONTRACT between the NPCs' dialogue layer (personas, prompts, what an NPC says and decides) and the model that
 * answers it. Pure Kotlin with no dependencies (not even coroutines): the persona code is written against [DialogueEngine]
 * and tested with [FakeDialogueEngine]; the app wires the real one (DialogueModel: one shared llama.cpp model, a KV
 * sequence per NPC) and, until a model is downloaded and loaded, [NoDialogueEngine], which answers null so the game goes
 * on silent.
 */

enum class DialogueRole { SYSTEM, USER, ASSISTANT }

data class DialogueMessage(val role: DialogueRole, val content: String) {
    companion object {
        fun user(text: String) = DialogueMessage(DialogueRole.USER, text)
        fun assistant(text: String) = DialogueMessage(DialogueRole.ASSISTANT, text)
    }
}

/**
 * Who is waiting for the answer, most urgent first. The queue serves the lowest ordinal first, and the scheduler promotes a
 * request on its own when its NPC is the selected one or half of a talking pair (DialogueScheduler.setFocus).
 */
enum class DialoguePriority {
    /** The NPC the player has selected, or is talking to: answered before anything else, never held back by the budget. */
    SELECTED,
    /** Two NPCs talking to each other in front of the player (both halves), or a pair at a partner animation. */
    PAIR,
    /** An NPC on screen, speaking unprompted. */
    NEARBY,
    /** Background chatter and decisions of NPCs nobody is watching: dropped first, paced by the token budget. */
    AMBIENT,
}

/**
 * One request for one NPC's next words.
 *
 * The prompt is [system] + [history] + [user], in that order. For the model to skip re-reading what it has already read,
 * keep the start of the prompt IDENTICAL from turn to turn: put what never changes (the world, the persona) in [system],
 * append to [history] and never edit or reorder what is already in it (trim only from the front, all at once, when the
 * budget is full: the whole prefix is read again then). The text of a request that begins with the same words as another
 * NPC's (a shared world preamble) is read once for both.
 */
data class DialogueRequest(
    /** The NPC's stable id (the npc roster's): its conversation state is kept under this. */
    val npcId: String,
    val system: String,
    val history: List<DialogueMessage> = emptyList(),
    /** What the NPC is being told or asked now (a line of the player's, what another NPC said, the situation); may be empty. */
    val user: String = "",
    /** Most tokens of the answer. A line or two is 40 to 100; the scheduler never allows less than 1. */
    val maxTokens: Int = 120,
    /** Text at which generation ends, the stop text itself not included. The turn's own end is always a stop. */
    val stop: List<String> = emptyList(),
    val temperature: Float = 0.8f,
    val topP: Float = 0.92f,
    val repeatPenalty: Float = 1.08f,
    /** 0: a different answer every time; any other number repeats the answer. */
    val seed: Int = 0,
    val priority: DialoguePriority = DialoguePriority.AMBIENT,
    /** How long the request may wait in the queue before it is given up (null: by priority; 0 or less: for ever). */
    val maxWaitMs: Long? = null,
    /** True: read the prompt into the NPC's state and answer nothing (warms an NPC as it is loaded). */
    val prefillOnly: Boolean = false,
    /** Called on the model's thread with each piece of the answer as it is made (for a speech bubble that types itself out). */
    val onPartial: ((String) -> Unit)? = null,
)

interface DialogueEngine {
    /**
     * The NPC's next words, or null when there are none: no model on the phone (or not loaded), the request cancelled, given
     * up in the queue, or failed. Never throws for those: the game goes on without the line. Cancelling the calling
     * coroutine cancels the request, running or queued.
     */
    suspend fun complete(req: DialogueRequest): String?

    /** True when a model is loaded and requests are being answered. */
    val ready: Boolean get() = true

    /** Gives an NPC's earlier state up (a new persona, a retold story): its next request reads the whole prompt again. */
    fun forget(npcId: String) {}

    /** Who the player is looking at, and the pair that is talking: their requests go first (see [DialoguePriority]). */
    fun setFocus(selected: String?, pair: Set<String> = emptySet()) {}
}

/** The engine used while no model is loaded: every request is answered with nothing. */
object NoDialogueEngine : DialogueEngine {
    override suspend fun complete(req: DialogueRequest): String? = null
    override val ready: Boolean get() = false
}

/**
 * A scripted engine for the persona code's tests: [reply] answers each request (null: no answer), every request is kept in
 * [requests], and the answer is handed to the request's [DialogueRequest.onPartial] a word at a time first.
 */
class FakeDialogueEngine(private val reply: (DialogueRequest) -> String?) : DialogueEngine {
    /** The scripted answers in order (the last one repeats), whoever asks; with none, "...". */
    constructor(vararg answers: String) : this(scripted(answers.toList()))

    private companion object {
        fun scripted(answers: List<String>): (DialogueRequest) -> String? {
            var i = 0
            return { _ -> if (answers.isEmpty()) "..." else answers[minOf(i++, answers.size - 1)] }
        }
    }

    private val seen = ArrayList<DialogueRequest>()
    val requests: List<DialogueRequest> get() = synchronized(seen) { seen.toList() }
    val forgotten = ArrayList<String>()
    var focusSelected: String? = null
        private set
    var focusPair: Set<String> = emptySet()
        private set
    override var ready: Boolean = true

    override suspend fun complete(req: DialogueRequest): String? {
        synchronized(seen) { seen.add(req) }
        if (!ready || req.prefillOnly) return null
        val text = reply(req) ?: return null
        req.onPartial?.let { cb -> Regex("\\S+\\s*").findAll(text).forEach { cb(it.value) } }
        return text
    }

    override fun forget(npcId: String) { forgotten.add(npcId) }

    override fun setFocus(selected: String?, pair: Set<String>) { focusSelected = selected; focusPair = pair }
}
