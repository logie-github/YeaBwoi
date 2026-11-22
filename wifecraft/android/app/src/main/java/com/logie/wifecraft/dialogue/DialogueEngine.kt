package com.logie.wifecraft.dialogue

/*
 * The minimal on-device dialogue engine interface (single source of truth: the persona/prompt worker and the LLM runtime
 * worker build on this; do not change a signature without them). Pure Kotlin, JVM-tested through [FakeDialogueEngine].
 */

/** One completion: a [system] prompt and the [user] turn; the model's reply is at most about [maxTokens] tokens. */
data class DialogueRequest(
    val system: String,
    val user: String,
    val maxTokens: Int = 90,
    val temperature: Float = 0.8f,
    /** What this is for ("joi:edge", "chat"...): logs and the fake engine's scripts. */
    val tag: String = "",
)

interface DialogueEngine {
    /** The model's reply, or null when it has none (no model downloaded, still loading, failed, cancelled). */
    suspend fun complete(req: DialogueRequest): String?
}

/** A scripted engine for tests: [replies] in order (then the last again), or [respond] when given; [ready] false: always null. */
class FakeDialogueEngine(
    private val replies: List<String> = listOf("Slower now. Good."),
    private val ready: Boolean = true,
    private val respond: ((DialogueRequest) -> String?)? = null,
) : DialogueEngine {
    val requests = ArrayList<DialogueRequest>()
    private var next = 0

    override suspend fun complete(req: DialogueRequest): String? {
        requests += req
        if (!ready) return null
        respond?.let { return it(req) }
        if (replies.isEmpty()) return null
        return replies[minOf(next++, replies.size - 1)]
    }
}
