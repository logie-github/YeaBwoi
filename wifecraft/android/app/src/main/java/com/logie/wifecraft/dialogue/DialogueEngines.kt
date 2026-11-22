package com.logie.wifecraft.dialogue

/*
 * What the runtime adds beside the contract in DialogueEngine.kt: the message shape of a flat chat, the priorities, an engine
 * for "no model" and a scripted one for tests. Pure JVM, no dependencies.
 */

enum class DialogueRole { SYSTEM, USER, ASSISTANT }

/** A flat chat message ([DialogueModel.generate] takes a list of them and splits it into a [DialogueRequest]). */
data class DialogueMessage(val role: DialogueRole, val content: String) {
    companion object {
        fun system(text: String) = DialogueMessage(DialogueRole.SYSTEM, text)
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
    /** Two NPCs talking to each other in front of the player, or a pair at a partner animation. */
    PAIR,
    /** An NPC on screen, speaking unprompted. */
    NEARBY,
    /** Background chatter and decisions of NPCs nobody is watching: dropped first, paced by the token budget. */
    AMBIENT,
}

/** The engine used while no model is loaded: every request is answered with nothing, so the game goes on silent. */
object NoDialogueEngine : DialogueEngine {
    override suspend fun complete(req: DialogueRequest): String? = null
    override val ready: Boolean get() = false
}

/**
 * A scripted engine for tests: [reply] answers each request (null: no answer), every request is kept in [requests], and the
 * answer is handed to the request's [DialogueRequest.onPartial] a word at a time first.
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
