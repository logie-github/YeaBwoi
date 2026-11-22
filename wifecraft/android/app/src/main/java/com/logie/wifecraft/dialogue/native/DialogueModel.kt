package com.logie.wifecraft.dialogue

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * The one shared dialogue model of the app: load(path, threads, ctx), then every NPC talks through it. [engine] is what the
 * persona code takes (DialogueEngine): the real one once a model is loaded, [NoDialogueEngine] before and after, so a phone
 * without the model just stays silent. Each NPC's conversation is its own KV-cache sequence in the native core, so a persona
 * prompt is read once and a turn reads only what is new (see DialogueEngine's note on prompts); the scheduler puts the selected
 * NPC and any talking pair first, limits unprompted talk with a token budget and yields to the renderer (DialogueScheduler).
 */
class DialogueModel(
    /** Where NPCs' saved places go (one folder per model); null: they are not kept between runs. */
    private val stateRoot: File? = null,
    /** How the renderer is doing: Governor's level mapped to [LoadLevel] (see DialogueModel.loadFromGovernor). */
    private val loadSignal: () -> LoadLevel = { LoadLevel.OK },
) {
    private val lock = Any()
    private var backend: NativeBackend? = null
    @Volatile private var scheduler: DialogueScheduler? = null
    @Volatile var spec: DialogueModelSpec? = null
        private set

    /** The engine to hand to the persona layer; stable to call at any time. */
    val engine: DialogueEngine = object : DialogueEngine {
        override suspend fun complete(req: DialogueRequest): String? = scheduler?.complete(req)
        override val ready: Boolean get() = scheduler?.ready == true
        override fun forget(npcId: String) { scheduler?.forget(npcId) }
        override fun setFocus(selected: String?, pair: Set<String>) { scheduler?.setFocus(selected, pair) }
    }

    val isLoaded: Boolean get() = scheduler?.ready == true

    /**
     * Loads [file] as [spec] (blocking, reading a 7.5 GB file takes tens of seconds: call it off the main thread, see [loadProgress]). Returns null when it worked, else why not
     * (in words for the Options page). Replaces a model already loaded.
     */
    fun load(spec: DialogueModelSpec, file: File, threads: Int, ctx: Int = 6144, slots: Int = 6, ambientTokensPerMinute: Int = 300): String? {
        unload()
        val stateDir = stateRoot?.let { File(it, spec.id.replace(Regex("[^A-Za-z0-9._-]"), "_")) }
        val b = NativeBackend(stateDir)
        val err = b.load(file, threads, ctx, slots)
        if (err != null) return err
        synchronized(lock) {
            backend = b
            this.spec = spec
            scheduler = DialogueScheduler(b, spec.template, TokenBudget(ambientTokensPerMinute), loadSignal)
        }
        DialogueRuntime.engine = engine   // the scenes' directors read it each call
        return null
    }

    /** While [load] runs on another thread: how much of the model file has been read, 0 to 1 (-1 when the library is not there). */
    fun loadProgress(): Float = if (NativeDialogue.available) NativeDialogue.nativeLoadProgress() else -1f

    /** Saves the NPCs' places and frees the model's memory. */
    fun unload() {
        val s: DialogueScheduler?
        synchronized(lock) { s = scheduler; scheduler = null; backend = null; spec = null }
        if (DialogueRuntime.engine === engine) DialogueRuntime.engine = null
        s?.let { it.flush(); it.close() }
    }

    /** The app is leaving the foreground: the NPCs' places are written to disk. */
    fun saveState() { scheduler?.flush() }

    fun stats() = scheduler?.stats?.value
    fun info(): ModelInfo? = backend?.info()
    fun tokenCount(text: String): Int = backend?.countTokens(text) ?: -1

    /**
     * Streaming form of one request: [messages] are a system message, the conversation so far and, last, the user's message (the
     * shape [DialogueRequest] takes apart). Emits the answer as it is made; cancelling the collector cancels the request.
     */
    fun generate(
        npcId: String, messages: List<DialogueMessage>, maxTokens: Int = 120, stop: List<String> = emptyList(), temperature: Float = 0.8f,
        priority: DialoguePriority = DialoguePriority.AMBIENT,
    ): Flow<String> {
        val s = scheduler ?: return emptyFlow()
        return s.stream(toRequest(npcId, messages, maxTokens, stop, temperature, priority))
    }

    companion object {
        fun toRequest(npcId: String, messages: List<DialogueMessage>, maxTokens: Int, stop: List<String>, temperature: Float, priority: DialoguePriority): DialogueRequest {
            val system = messages.takeWhile { it.role == DialogueRole.SYSTEM }.joinToString("\n\n") { it.content }
            var rest = messages.drop(messages.takeWhile { it.role == DialogueRole.SYSTEM }.size)
            val user = if (rest.lastOrNull()?.role == DialogueRole.USER) rest.last().content else ""
            if (user.isNotEmpty()) rest = rest.dropLast(1)
            val turns = rest.filter { it.role != DialogueRole.SYSTEM }.map { DialogueTurn(if (it.role == DialogueRole.USER) DialogueTurn.Role.USER else DialogueTurn.Role.ASSISTANT, it.content) }
            return DialogueRequest(npcId, system, turns, user, maxTokens, temperature, stop, priority = priority)
        }

        /** The governor's quality step as a load level: 0 nothing stepped down, 1 a step, 2 or more (or the thermal floor) struggling. */
        fun loadFromGovernor(level: Int): LoadLevel = when { level <= 0 -> LoadLevel.OK; level == 1 -> LoadLevel.BUSY; else -> LoadLevel.STRUGGLING }
    }
}
