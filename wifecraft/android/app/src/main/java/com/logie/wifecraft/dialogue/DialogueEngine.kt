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
)

interface DialogueEngine {
    /** The model's answer, or null when it can't answer now (not loaded, busy, failed): the caller then stays silent. */
    suspend fun complete(req: DialogueRequest): String?
}
