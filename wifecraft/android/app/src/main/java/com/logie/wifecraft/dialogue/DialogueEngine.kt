package com.logie.wifecraft.dialogue

/**
 * The on-device language model as the dialogue layer sees it: one call, one answer. The runtime (llama.cpp, one shared model)
 * implements it; the layer above ([DialogueDirector], [ConsentCoordinator]) is written against this alone and is tested with
 * a scripted one. This file is the single source of truth for the interface: the runtime merges onto it. Pure JVM.
 */
data class DialogueTurn(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }
}

/**
 * One request. [system] is the NPC's persona prompt (its first part is stable per NPC so the runtime can cache its prefix),
 * [history] the short rolling talk so far, [user] what to answer now, [maxTokens] the most it may write.
 */
data class DialogueRequest(
    val npcId: String,
    val system: String,
    val history: List<DialogueTurn> = emptyList(),
    val user: String,
    val maxTokens: Int = 60,
    val temperature: Float = 0.8f,
    val stop: List<String> = emptyList(),
    // ---- added by the runtime (DialogueScheduler); all optional, so the layer above is unchanged ----
    val topP: Float = 0.92f,
    val repeatPenalty: Float = 1.08f,
    /** 0: a different answer every time; any other number repeats the answer. */
    val seed: Int = 0,
    /** Who is waiting, most urgent first. The scheduler also promotes the selected NPC and a talking pair on its own (setFocus). */
    val priority: DialoguePriority = DialoguePriority.AMBIENT,
    /** How long the request may wait in the queue (null: by priority; 0 or less: for ever). */
    val maxWaitMs: Long? = null,
    /** True: read the prompt into the NPC's state and answer nothing (warms an NPC as it is loaded). */
    val prefillOnly: Boolean = false,
    /** Called on the model's thread with each piece of the answer as it is made (for a speech bubble that types itself out). */
    val onPartial: ((String) -> Unit)? = null,
    // ---- speed (the runtime's timings and early stops) ----
    /** What the request is for ("chat", "joi", "speech", "warm", "speedtest"): shown beside its timings in Options -> Dialogue. */
    val tag: String = "",
    /**
     * Looks at the answer as far as it has come (on the model's thread, before each piece is handed to [onPartial]); true ends the
     * answer there and the request answers null. The caller keeps what it found: a wrong word seen after ten tokens costs ten
     * tokens, not the whole answer plus the retry. Not called for the held-back end of a word still being written.
     */
    val guard: ((String) -> Boolean)? = null,
    /** More than 0: the answer ends once this many whole sentences are written (a chat reply is cut to its length anyway: the tokens after it are never read). */
    val sentences: Int = 0,
    /**
     * False for an answer that is not speech (a JSON decision): the Gemma template opens a spoken answer with a double quote and ends it at the
     * next, which would cut a JSON answer at its first quotation mark ([ChatTemplate.speechQuote]). The other templates ignore it.
     */
    val spoken: Boolean = true,
)

interface DialogueEngine {
    /** The model's answer, or null when it can't answer now (not loaded, busy, failed): the caller then stays silent. */
    suspend fun complete(req: DialogueRequest): String?

    // ---- added by the runtime; defaults so a scripted engine needs only complete() ----

    /** True when a model is loaded and requests are being answered. */
    val ready: Boolean get() = true

    /** Gives an NPC's earlier state up (a new persona, a retold story): its next request reads the whole prompt again. */
    fun forget(npcId: String) {}

    /**
     * Reads [system] (an NPC's persona prompt, byte for byte what its requests will send) into the NPC's place in the model before
     * the first request needs it, so the first answer pays for the new words only. [urgent]: the player has just opened a chat or
     * chosen the NPC (it goes before the queue); otherwise it waits behind everything. Asking again for the same persona is free.
     * [history] are the turns that follow the system prompt in every request of this NPC (JOI's few-shot examples): they are read
     * too, so the first real request pays for its own last message only.
     */
    fun warm(npcId: String, system: String, urgent: Boolean = false, history: List<DialogueTurn> = emptyList()) {}

    /** How far the warm-up of [npcId] has got, 0 to 1 (an estimate while it reads; 1: it is finished or none was asked for: the NPC is ready). */
    fun warmProgress(npcId: String): Float = 1f

    /**
     * True when the loaded model is a small, fast one (the Fast model): prompts are then built in their compact form (a short
     * persona, two or three examples), which a big model does not need and a small one reads in a fraction of the time.
     */
    val compactPrompts: Boolean get() = false

    /** Who the player is looking at, and the pair that is talking: their requests go first (see [DialoguePriority]). */
    fun setFocus(selected: String?, pair: Set<String> = emptySet()) {}
}

/** Cancelling the calling coroutine cancels the request, running or queued; a request given up in the queue answers null. */
