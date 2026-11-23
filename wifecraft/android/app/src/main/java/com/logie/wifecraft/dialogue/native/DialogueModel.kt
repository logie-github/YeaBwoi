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
    /** What the phone's CPU is (read once). */
    val cpu: CpuProfile by lazy { CpuProfile.read() }
    /** What every request cost, the last 100: it outlives a model reload so the page keeps its numbers. */
    val timings = TimingLog()
    /** The thread plan in force (Speed in Options: Auto, or a number by hand). */
    @Volatile var plan: ThreadPlan = ThreadPlan.choose(CpuProfile())
        private set
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
        override fun warm(npcId: String, system: String, urgent: Boolean, history: List<DialogueTurn>) { scheduler?.warm(npcId, system, urgent, history) }
        override fun warmProgress(npcId: String): Float = scheduler?.warmProgress(npcId) ?: 1f
        override val compactPrompts: Boolean get() = scheduler?.compactPrompts == true
    }

    val isLoaded: Boolean get() = scheduler?.ready == true

    /**
     * Loads [file] as [spec] (blocking, reading a 7.5 GB file takes tens of seconds: call it off the main thread, see [loadProgress]). Returns null when it worked, else why not
     * (in words for the Options page). Replaces a model already loaded.
     */
    fun load(spec: DialogueModelSpec, file: File, manualThreads: Int = 0, ctx: Int = 6144, slots: Int = 6, ambientTokensPerMinute: Int = 300, calibratedThreads: Int = 0): String? {
        unload()
        val stateDir = stateRoot?.let { File(it, spec.stateName) }
        val b = NativeBackend(stateDir)
        val p = ThreadPlan.choose(cpu, manualThreads, calibratedThreads)
        plan = p
        val t0 = System.nanoTime()
        val err = b.load(file, p, ctx, slots)
        loadWallMs = (System.nanoTime() - t0) / 1_000_000
        if (err != null) return err
        synchronized(lock) {
            backend = b
            this.spec = spec
            scheduler = DialogueScheduler(b, spec.template, TokenBudget(ambientTokensPerMinute), loadSignal, timings = timings, compact = spec.compact)
        }
        DialogueRuntime.engine = engine   // the scenes' directors read it each call
        return null
    }

    /** While [load] runs on another thread: how much of the model file has been read, 0 to 1 (-1 when the library is not there). */
    fun loadProgress(): Float = if (NativeDialogue.available) NativeDialogue.nativeLoadProgress() else -1f

    /** How long the last [load] took end to end (reading the file included), milliseconds. */
    @Volatile var loadWallMs: Long = 0
        private set

    /** Speed in Options: Auto (0) or 3 to 6 threads; applies to the next answer, no reload. */
    fun setSpeed(manualThreads: Int, calibratedThreads: Int = 0) {
        val p = ThreadPlan.choose(cpu, manualThreads, calibratedThreads)
        plan = p
        scheduler?.setThreads(p.decode, p.prefill)
    }

    /**
     * The text "Copy timings" puts on the clipboard (and a developer reads real phone numbers from): the model, how long it took to
     * load, the CPU's features and cores, the kernels llama.cpp reports, the thread plan, then the last 100 requests, one line each.
     */
    fun report(): String = timings.toText(header())

    /** "Report slow replies": [header] and the replies slower than 5 s with their phases ([TimingLog.slowReport]). */
    fun slowReport(): String = timings.slowReport(header())

    private fun header(): String {
        val i = info()
        return buildString {
            append("Wifecraft dialogue timings\n")
            append("model: ").append(spec?.id ?: "none").append(if (i != null) " (${i.description}, ${i.sizeBytes / 1_000_000} MB)" else "").append('\n')
            if (i != null) append("load: ").append(loadWallMs).append(" ms in all (model ").append(i.loadMs).append(" ms, context ").append(i.contextMs).append(" ms); context ")
                .append(i.context).append(" tokens, ").append(i.slots).append(" places, ").append(i.liveSlots).append(" in use holding ").append(i.liveTokens).append(" tokens; resident ").append(i.residentBytes / 1_000_000).append(" MB\n")
            append(cpu.describe()).append('\n')
            append("threads: ").append(plan.note).append('\n')
            if (i != null && i.system.isNotBlank()) append("llama.cpp: ").append(i.system).append('\n')
            append("prompts: ").append(if (spec?.compact == true) "compact" else "full").append("; speed target: a line in ").append(SpeedTarget.LINE_SECONDS.toInt()).append(" s\n")
        }
    }

    /**
     * Runs a 64-token answer and reports how fast this phone does it (Options -> Dialogue -> Speed test): the first token's time,
     * how fast it writes and, when it had to read anything, how fast it reads. Null when no model is loaded or the answer failed.
     */
    suspend fun speedTest(): SpeedResult? {
        val s = scheduler ?: return null
        val req = DialogueRequest("speedtest", PersonaPrompt.shared(null, spec?.compact == true), emptyList(), "Tell me, in your own words, about a long road you once walked.",
            maxTokens = 64, temperature = 0.7f, priority = DialoguePriority.SELECTED, seed = 7, tag = "speedtest", maxWaitMs = 120_000L)
        val before = timings.added
        val text = s.complete(req)
        s.forget("speedtest")
        val t = timings.all().lastOrNull { it.tag == "speedtest" } ?: return null
        if (text == null || timings.added == before) return null
        return SpeedResult(t.firstWordMs / 1000.0, t.decodeTokensPerSecond, t.generated, t.prefillTokensPerSecond, SpeedHint.of(t.decodeTokensPerSecond),
            SpeedTarget.estimate(t.prefillTokensPerSecond, t.decodeTokensPerSecond))
    }

    /**
     * The first-run calibration ([ThreadCalibration]): the model writes [ThreadCalibration.TRIAL_TOKENS] tokens with each candidate thread count
     * (about three seconds each with the Fast model) and the fastest count is returned and put in force. Null when no model is loaded or
     * nothing could be measured; the thread plan is restored to what it was then. Call from a coroutine, once, soon after the model loads
     * (it holds the model while it runs).
     */
    suspend fun calibrate(): List<ThreadCalibration.Trial>? {
        val s = scheduler ?: return null
        val before = plan
        val trials = ArrayList<ThreadCalibration.Trial>()
        try {
            for (t in ThreadCalibration.candidates(cpu)) {
                s.setThreads(t, before.prefill)
                val mark = timings.added
                val req = DialogueRequest("calibrate", PersonaPrompt.shared(null, spec?.compact == true), emptyList(), "Tell me, in your own words, about a long road you once walked.",
                    maxTokens = ThreadCalibration.TRIAL_TOKENS, temperature = 0.7f, priority = DialoguePriority.SELECTED, seed = 11, tag = "calibrate", maxWaitMs = 120_000L)
                s.complete(req)
                s.forget("calibrate")
                val row = timings.all().lastOrNull { it.tag == "calibrate" }
                if (timings.added == mark || row == null) continue
                trials += ThreadCalibration.Trial(t, row.decodeTokensPerSecond)
            }
        } finally { s.setThreads(before.decode, before.prefill) }
        return trials.takeIf { it.isNotEmpty() }
    }

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

/** What the Speed test found. */
data class SpeedResult(val firstWordSeconds: Double, val tokensPerSecond: Double, val tokens: Int, val prefillTokensPerSecond: Double, val hint: String?,
    /** What the two speeds mean for a JOI line or a chat reply against the 5 s target ([SpeedTarget]); null when a speed is unknown. */
    val estimate: SpeedTarget.Estimate? = null) {
    fun text(): String = String.format(java.util.Locale.ROOT, "First word after %.1f s, then %.1f tokens a second (%d tokens written)%s.", firstWordSeconds, tokensPerSecond, tokens,
        if (prefillTokensPerSecond > 0) String.format(java.util.Locale.ROOT, "; it reads a prompt at %.0f tokens a second", prefillTokensPerSecond) else "") +
        (estimate?.let { " " + it.text() } ?: "")
}
