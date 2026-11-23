package com.logie.wifecraft.dialogue

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume

/*
 * The request queue in front of the one shared model: who is answered first (the selected NPC, a talking pair, then the
 * rest), how much unprompted talk the phone may pay for (a token budget), and how the model yields to the renderer (a load
 * signal that holds back and slows everything but the selected NPC). Pure Kotlin on the JVM, tested with a fake backend
 * (DialogueSchedulerTest); the backend is NativeDialogue's llama.cpp core on the phone.
 *
 * One worker thread, at the lowest priority, runs one generation at a time: there is a single model and a single context,
 * and a phone's cores are better spent on one answer quickly than on four slowly. Everything that touches the backend
 * (generate, forget, flush) happens on that thread, so the backend needs no locks of its own; only cancel() is called from
 * elsewhere.
 */

enum class DialogueFinish { EOS, LENGTH, STOP, CANCELLED, ERROR }

/** What the renderer wants of the CPU: [OK] nothing, [BUSY] a little (the governor has stepped quality down), [STRUGGLING] it is dropping frames. */
enum class LoadLevel { OK, BUSY, STRUGGLING }

class BackendJob(
    val ticket: Long,
    val npcId: String,
    val prompt: String,
    val maxTokens: Int,
    val temperature: Float,
    val topP: Float,
    val repeatPenalty: Float,
    val seed: Int,
    val stop: List<String>,
    /** A pause after each chunk of the prompt that is read (the load's way of slowing a long read). */
    val chunkDelayMs: Int,
    /** Gets each piece of the answer; false ends the answer there. */
    val sink: (String) -> Boolean,
)

class BackendResult(
    val finish: DialogueFinish,
    val error: String? = null,
    val promptTokens: Int = 0,
    val reusedTokens: Int = 0,
    val sharedTokens: Int = 0,
    val restoredTokens: Int = 0,
    val generated: Int = 0,
    val prefillMs: Double = 0.0,
    val decodeMs: Double = 0.0,
    val firstTokenMs: Double = 0.0,
    val kvTokens: Int = 0,
    val threads: Int = 0,
    val threadsBatch: Int = 0,
) {
    /** Tokens of the prompt that had to be read this time. */
    val readTokens: Int get() = (promptTokens - reusedTokens - sharedTokens - restoredTokens).coerceAtLeast(0)
}

interface DialogueBackend {
    val loaded: Boolean
    /** Blocking; called only from the scheduler's worker thread. */
    fun generate(job: BackendJob): BackendResult
    /** Any thread: ends the running generation with this ticket at the next token. */
    fun cancel(ticket: Long)
    fun reset(npcId: String)
    fun resetAll()
    /** Saves every NPC's state to disk (the app is leaving the foreground). */
    fun flush()
    /** New thread counts for the next generations: writing, reading the prompt (Options -> Dialogue -> Speed). Worker thread, between requests. */
    fun setThreads(decode: Int, prefill: Int) {}
    fun close()
}

/**
 * Tokens that unprompted talk may use, refilled steadily: [perMinute] a minute, up to [capacity] saved up. NEARBY and AMBIENT
 * requests start only with some in hand and pay what they use; the selected NPC and a pair never wait for it but still pay
 * (so the background goes quiet while the player is chatting).
 */
class TokenBudget(var perMinute: Int = 300, val capacity: Int = 400, private val clock: () -> Long = System::currentTimeMillis) {
    private var tokens = capacity.toDouble()
    private var at = clock()

    @Synchronized private fun refill() {
        val now = clock()
        if (now > at) tokens = minOf(capacity.toDouble(), tokens + (now - at) * perMinute / 60_000.0)
        at = now
    }

    @Synchronized fun available(): Int { refill(); return tokens.toInt() }
    /** Room to start a request that will want about [want] tokens. */
    @Synchronized fun canStart(want: Int): Boolean { refill(); return perMinute > 0 && tokens >= minOf(want, 40) }
    @Synchronized fun charge(n: Double) { refill(); tokens = maxOf(-capacity.toDouble(), tokens - n) }
}

data class DialogueStats(
    val queued: Int = 0,
    val runningNpc: String? = null,
    val answers: Long = 0,
    val dropped: Long = 0,
    val tokens: Long = 0,
    /** The last answer's speed, tokens a second. */
    val lastTokensPerSecond: Double = 0.0,
    val lastReadTokens: Int = 0,
    val lastReusedTokens: Int = 0,
    /** The last real answer's wait in the queue and time to its first word, milliseconds. */
    val lastQueueWaitMs: Long = 0,
    val lastFirstWordMs: Long = 0,
    val budget: Int = 0,
    val lastError: String? = null,
)

class DialogueScheduler(
    private val backend: DialogueBackend,
    private val template: ChatTemplate = ChatTemplate.CHATML,
    val budget: TokenBudget = TokenBudget(),
    /** How the renderer is doing, asked often (a field read in the app: Governor's level). */
    var load: () -> LoadLevel = { LoadLevel.OK },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Pause after each piece an unprompted answer streams, by load (milliseconds): [BUSY, STRUGGLING]. The selected NPC's is a third. */
    private val pieceDelayMs: IntArray = intArrayOf(25, 120),
    private val maxQueuedAmbient: Int = 6,
    /** The model's own advice on repetition: a request asks for at most this much penalty (Mag-Mell's card says to avoid heavy penalty samplers). */
    private val maxRepeatPenalty: Float = 1.08f,
    /** What each request cost (the last 100), for Options -> Dialogue. */
    val timings: TimingLog = TimingLog(),
    /** Java priority of the worker while it answers the selected NPC or a pair (the default is the normal one: the thread's nice value is the one the model's own threads inherit) and while it works for the background. */
    private val urgentPriority: Int = Thread.NORM_PRIORITY,
    private val backgroundPriority: Int = 3,
    /** The loaded model is a small one that reads compact prompts ([DialogueEngine.compactPrompts]). */
    private val compact: Boolean = false,
) : DialogueEngine {

    private class Job(val id: Long, val req: DialogueRequest, val queuedAt: Long, val finish: (String?) -> Unit) {
        @Volatile var cancelled = false
        @Volatile var preempted = false
        @Volatile var softStopped = false
        @Volatile var guarded = false
        var requeues = 0
        val finished = AtomicBoolean(false)
        var ticket = 0L
        fun end(text: String?) { if (finished.compareAndSet(false, true)) finish(text) }
    }

    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private val queue = ArrayList<Job>()
    private val commands = ArrayList<() -> Unit>()
    private var current: Job? = null
    private var nextId = 1L
    private var nextTicket = 1L
    private var selected: String? = null
    private var pair: Set<String> = emptySet()
    @Volatile private var closed = false
    /** The persona each NPC was last asked to be warmed with (a hash of the text): the same again is free; [forget] clears it. */
    private val warmed = HashMap<String, Int>()

    private val _stats = MutableStateFlow(DialogueStats())
    val stats: StateFlow<DialogueStats> get() = _stats

    private val worker = Thread(null, { loop() }, "wc-dialogue").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }

    override val ready: Boolean get() = backend.loaded && !closed

    override val compactPrompts: Boolean get() = compact

    /** A warm-up that has been asked for and has not finished: when it was asked and how many tokens it has to read (an estimate, [Tokens.approx]). */
    private class WarmWait(val sinceMs: Long, val tokens: Int)
    private val warming = HashMap<String, WarmWait>()

    override fun warmProgress(npcId: String): Float {
        val w = lock.withLock { warming[npcId] } ?: return 1f
        return WarmEstimate.progress(clock() - w.sinceMs, w.tokens, WarmEstimate.rateFrom(timings.all()))
    }

    // ---- the engine --------------------------------------------------------------------------------------------------

    override suspend fun complete(req: DialogueRequest): String? {
        if (!ready) return null
        return suspendCancellableCoroutine { cont ->
            val job = submit(req) { text -> if (cont.isActive) cont.resume(text) }
            if (job == null) { if (cont.isActive) cont.resume(null) } else cont.invokeOnCancellation { cancel(job) }
        }
    }

    /** The answer as it is made, piece by piece; completes when it ends (nothing is emitted for a request that is dropped). */
    fun stream(req: DialogueRequest): Flow<String> = callbackFlow {
        val job = if (!ready) null else submit(req.copy(onPartial = { p -> req.onPartial?.invoke(p); trySend(p) })) { close() }
        if (job == null) close()
        awaitClose { job?.let { cancel(it) } }
    }

    override fun forget(npcId: String) {
        lock.withLock { warmed.remove(npcId); warming.remove(npcId) }
        command { backend.reset(npcId) }
    }

    fun forgetAll() {
        lock.withLock { warmed.clear(); warming.clear() }
        command { backend.resetAll() }
    }

    /**
     * Reads [system] into the NPC's place ahead of its first request: a request that only reads (prefillOnly), behind everything
     * else, or first when [urgent]. A persona already asked for is not read again; waiting warm-ups of one NPC are replaced by the
     * newest; they are never charged to the unprompted-talk budget and an urgent request cancels a running one (it carries on
     * from the tokens it had read). The caller must give the very text its requests will send as their system prompt: the prompt of
     * the warm-up and of the request then share every token of it.
     */
    override fun warm(npcId: String, system: String, urgent: Boolean, history: List<DialogueTurn>) {
        if (!ready || system.isEmpty()) return
        val h = 31 * system.hashCode() + history.hashCode()
        val req = DialogueRequest(npcId, system, history, "", maxTokens = 1, prefillOnly = true, tag = "warm",
            priority = if (urgent) DialoguePriority.SELECTED else DialoguePriority.AMBIENT, maxWaitMs = if (urgent) 120_000L else 300_000L)
        var ended: Job? = null
        lock.withLock {
            if (closed) return
            // a persona already read (or being read): nothing to do, unless the urgent one finds it only queued behind everything
            if (warmed[npcId] == h) {
                val queued = queue.firstOrNull { it.req.npcId == npcId && it.req.prefillOnly }
                if (queued == null || !urgent || queued.req.priority == DialoguePriority.SELECTED) return
            }
            warmed[npcId] = h
            warming[npcId] = WarmWait(clock(), Tokens.approx(system) + history.sumOf { Tokens.approx(it.text) + 4 })
            ended = queue.firstOrNull { it.req.npcId == npcId && it.req.prefillOnly }?.also { queue.remove(it) }
            queue.add(Job(nextId++, req, clock()) { })
            // warm-ups of other NPCs are kept short: only the newest few
            val warms = queue.filter { it.req.prefillOnly && it.req.priority != DialoguePriority.SELECTED }
            if (warms.size > MAX_QUEUED_WARM) warms.take(warms.size - MAX_QUEUED_WARM).forEach { w -> queue.remove(w); warmed.remove(w.req.npcId) }
            maybePreempt()
            wake.signalAll()
            publish()
        }
        ended?.end(null)
    }

    /** The app leaves the foreground: every NPC's place is saved so it is read back, not read again. */
    fun flush() = command { backend.flush() }

    /** The player changed Speed: the next generations use these thread counts (applied between requests, on the worker). */
    fun setThreads(decode: Int, prefill: Int) = command { backend.setThreads(decode, prefill) }

    override fun setFocus(selected: String?, pair: Set<String>) {
        lock.withLock {
            this.selected = selected
            this.pair = pair
            maybePreempt()
            wake.signalAll()
        }
    }

    /** Ends the worker and answers everything still waiting with nothing. */
    fun close() {
        val waiting: List<Job>
        lock.withLock {
            closed = true
            waiting = queue.toList()
            queue.clear()
            current?.let { it.cancelled = true; backend.cancel(it.ticket) }
            wake.signalAll()
        }
        waiting.forEach { it.end(null) }
        worker.join(5000)
        backend.close()
    }

    // ---- queue -------------------------------------------------------------------------------------------------------

    private fun effective(job: Job): DialoguePriority = when {
        job.req.npcId == selected -> DialoguePriority.SELECTED
        job.req.npcId in pair -> minOf(job.req.priority, DialoguePriority.PAIR)
        else -> job.req.priority
    }

    private fun submit(req: DialogueRequest, finish: (String?) -> Unit): Any? {
        var dropped: List<Job> = emptyList()
        val job = lock.withLock {
            if (closed) return null
            val j = Job(nextId++, req, clock(), finish)
            val drop = ArrayList<Job>()
            // a newer unprompted line of the same NPC replaces an older one that has not started
            if (req.priority >= DialoguePriority.NEARBY && !req.prefillOnly) {
                queue.filter { it.req.npcId == req.npcId && it.req.priority >= DialoguePriority.NEARBY && !it.req.prefillOnly }.forEach { queue.remove(it); drop.add(it) }
            }
            queue.add(j)
            // the background queue is short: the oldest are given up
            val amb = queue.filter { it.req.priority == DialoguePriority.AMBIENT && !it.req.prefillOnly }
            if (amb.size > maxQueuedAmbient) amb.take(amb.size - maxQueuedAmbient).forEach { queue.remove(it); drop.add(it) }
            dropped = drop
            maybePreempt()
            wake.signalAll()
            publish()
            j
        }
        dropped.forEach { count(dropped = 1); it.end(null) }
        return job
    }

    private fun cancel(any: Any) {
        val job = any as Job
        job.cancelled = true
        lock.withLock {
            if (queue.remove(job)) { publish(); job.end(null) }
            else if (current === job) backend.cancel(job.ticket)
        }
    }

    // Holds the lock. A running unprompted answer gives way to an urgent request that is waiting.
    private fun maybePreempt() {
        val cur = current ?: return
        if (effective(cur) < DialoguePriority.NEARBY || cur.preempted) return
        if (queue.any { effective(it) <= DialoguePriority.PAIR }) {
            cur.preempted = true
            backend.cancel(cur.ticket)
        }
    }

    private fun command(c: () -> Unit) {
        lock.withLock { if (!closed) { commands.add(c); wake.signalAll() } }
    }

    // The 12B model makes about 4 to 6 tokens a second: a 100-token answer takes 15 to 25 s and a persona read for the first time
    // (roughly 15 to 30 tokens a second without i8mm) 20 to 40 s, so a request waits twice as long as it did with the 4B model.
    private fun maxWait(j: Job): Long = j.req.maxWaitMs ?: when (effective(j)) {
        DialoguePriority.SELECTED -> 240_000L
        DialoguePriority.PAIR -> 120_000L
        DialoguePriority.NEARBY -> 60_000L
        DialoguePriority.AMBIENT -> 40_000L
    }

    private companion object {
        const val MAX_QUEUED_WARM = 8
    }

    private fun publish(running: String? = current?.req?.npcId) {
        _stats.value = _stats.value.copy(queued = queue.size, runningNpc = running, budget = budget.available())
    }

    private fun count(dropped: Int = 0, answers: Int = 0, tokens: Int = 0, result: BackendResult? = null, error: String? = null) {
        val s = _stats.value
        _stats.value = s.copy(
            dropped = s.dropped + dropped, answers = s.answers + answers, tokens = s.tokens + tokens,
            lastTokensPerSecond = if (result != null && result.decodeMs > 0 && result.generated > 2) result.generated * 1000.0 / result.decodeMs else s.lastTokensPerSecond,
            lastReadTokens = result?.readTokens ?: s.lastReadTokens,
            lastReusedTokens = result?.let { it.reusedTokens + it.sharedTokens + it.restoredTokens } ?: s.lastReusedTokens,
            lastError = error ?: s.lastError, budget = budget.available(),
        )
    }

    // ---- the worker --------------------------------------------------------------------------------------------------

    private fun loop() {
        while (true) {
            var job: Job? = null
            var cmds: List<() -> Unit> = emptyList()
            val expired = ArrayList<Job>()
            lock.withLock {
                while (!closed) {
                    val now = clock()
                    queue.filter { it.cancelled || (maxWait(it) > 0 && now - it.queuedAt > maxWait(it)) }.forEach { queue.remove(it); expired.add(it) }
                    if (commands.isNotEmpty()) { cmds = commands.toList(); commands.clear(); break }
                    job = pick()
                    if (job != null) { current = job; job!!.ticket = nextTicket++; publish(job!!.req.npcId); break }
                    if (expired.isNotEmpty()) break
                    wake.await(250, TimeUnit.MILLISECONDS)   // the load and the budget change with time, not with a signal
                }
                if (closed) { current = null; return }
            }
            expired.forEach { count(dropped = 1); it.end(null) }
            cmds.forEach { runCatching { it() } }
            job?.let { run(it) }
        }
    }

    // Holds the lock. The most urgent request that may start now.
    private fun pick(): Job? {
        val level = load()
        var best: Job? = null
        var bestP = DialoguePriority.AMBIENT
        for (j in queue) {
            val p = effective(j)
            if (level == LoadLevel.STRUGGLING && p != DialoguePriority.SELECTED) continue
            if (p >= DialoguePriority.NEARBY && !j.req.prefillOnly && !budget.canStart(j.req.maxTokens)) continue
            // equal priority: the earlier one stays (FIFO), but a warm-up gives way to a line someone will hear
            if (best == null || p < bestP || (p == bestP && best.req.prefillOnly && !j.req.prefillOnly)) { best = j; bestP = p }
        }
        if (best != null) queue.remove(best)
        return best
    }

    private fun run(job: Job) {
        val req = job.req
        val level0 = load()
        val sb = StringBuilder()
        val urgent = effective(job) <= DialoguePriority.PAIR
        // the model's own threads are made by this thread and inherit its niceness: the selected NPC is answered at the normal one
        // (this worker used to sit at the lowest, where the phone's scheduler keeps a thread on the slow cores)
        runCatching { Thread.currentThread().priority = if (urgent) urgentPriority else backgroundPriority }
        val startedAt = clock()
        var firstPieceAt = 0L
        val prompt = template.render(req.system, req.history, req.user, req.spoken)
        val bj = BackendJob(
            ticket = job.ticket, npcId = req.npcId, prompt = prompt,
            maxTokens = if (req.prefillOnly) 0 else req.maxTokens.coerceAtLeast(1),
            temperature = req.temperature, topP = req.topP, repeatPenalty = minOf(req.repeatPenalty, maxRepeatPenalty), seed = req.seed, stop = req.stop + template.leakStops(req.stop, req.spoken),
            chunkDelayMs = when (level0) { LoadLevel.OK -> 0; LoadLevel.BUSY -> if (urgent) 0 else 30; LoadLevel.STRUGGLING -> if (urgent) 20 else 100 },
            sink = sink@{ piece ->
                if (firstPieceAt == 0L && piece.isNotBlank()) firstPieceAt = clock()
                val before = sb.length
                sb.append(piece)
                var forward = piece
                if (!req.prefillOnly) {
                    // the answer is judged as it grows: a wrong word ends it now, a long enough answer ends at its last sentence
                    if (req.guard?.let { g -> runCatching { g(sb.toString()) }.getOrDefault(false) } == true) { job.guarded = true; return@sink false }
                    if (req.sentences > 0) {
                        val cut = SentenceStop.cutAfter(sb, req.sentences, before)
                        if (cut >= 0) {
                            forward = sb.substring(before, cut)
                            sb.setLength(cut)
                            job.softStopped = true
                        }
                    }
                    if (forward.isNotEmpty()) req.onPartial?.let { runCatching { it(forward) } }
                    if (job.softStopped) return@sink false
                }
                val l = load()
                if (l != LoadLevel.OK) {
                    val d = pieceDelayMs[if (l == LoadLevel.BUSY) 0 else 1]
                    val ms = if (effective(job) == DialoguePriority.SELECTED) d / 3 else d
                    if (ms > 0) try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) { }
                }
                !job.cancelled && !job.preempted && !closed
            },
        )
        var result: BackendResult
        try {
            result = backend.generate(bj)
        } catch (t: Throwable) {
            result = BackendResult(DialogueFinish.ERROR, error = t.message ?: t.javaClass.simpleName)
        }
        // what the answer cost (a warm-up is the system's, not the unprompted talk's)
        if (!req.prefillOnly) budget.charge(result.generated + result.readTokens / 8.0)
        val endedAt = clock()
        timings.add(ReplyTiming(
            atMs = endedAt, npcId = req.npcId, tag = req.tag, priority = effective(job), queueWaitMs = (startedAt - job.queuedAt).coerceAtLeast(0),
            firstWordMs = if (firstPieceAt > 0) (firstPieceAt - job.queuedAt).coerceAtLeast(0) else 0, totalMs = (endedAt - job.queuedAt).coerceAtLeast(0),
            finish = result.finish, softStopped = job.softStopped, guarded = job.guarded, attempt = job.requeues, maxTokens = req.maxTokens,
            promptTokens = result.promptTokens, readTokens = result.readTokens, reusedTokens = result.reusedTokens, sharedTokens = result.sharedTokens,
            restoredTokens = result.restoredTokens, prefillMs = result.prefillMs, generated = result.generated, decodeMs = result.decodeMs,
            firstTokenMs = result.firstTokenMs, kvTokens = result.kvTokens, threads = result.threads, threadsBatch = result.threadsBatch, prefillOnly = req.prefillOnly,
        ))
        val requeue: Boolean
        lock.withLock {
            current = null
            requeue = job.preempted && !job.cancelled && !closed && job.requeues < 1 && result.finish == DialogueFinish.CANCELLED && !job.softStopped && !job.guarded
            if (requeue) { job.preempted = false; job.requeues++; queue.add(0, job) }
            publish(null)
        }
        if (requeue) { return }
        // a sentence stop and a guard stop end the answer by cancelling the model's turn: the first is a good answer, the second none
        val ok = result.finish == DialogueFinish.EOS || result.finish == DialogueFinish.LENGTH || result.finish == DialogueFinish.STOP || (job.softStopped && result.finish == DialogueFinish.CANCELLED)
        val text = if (ok && !req.prefillOnly && !job.guarded) ChatTemplate.tidy(sb.toString()).ifEmpty { null } else null
        if (job.guarded) { count(tokens = result.generated, result = result); job.end(null); return }
        count(answers = if (ok) 1 else 0, dropped = if (ok) 0 else 1, tokens = result.generated, result = result, error = result.error)
        if (ok && !req.prefillOnly) _stats.value = _stats.value.copy(lastQueueWaitMs = (startedAt - job.queuedAt).coerceAtLeast(0), lastFirstWordMs = if (firstPieceAt > 0) (firstPieceAt - job.queuedAt).coerceAtLeast(0) else 0)
        // a warm-up that was cancelled or failed is asked for again next time; one that finished is done for the progress bar
        if (req.prefillOnly && !ok) lock.withLock { warmed.remove(req.npcId); warming.remove(req.npcId) }
        else if (req.prefillOnly) lock.withLock { if (queue.none { it.req.npcId == req.npcId && it.req.prefillOnly }) warming.remove(req.npcId) }
        job.end(text)
    }
}

/** Finding where a streamed answer has written enough whole sentences. */
object SentenceStop {
    private val ACTION = Regex("\\*[^*]*\\*|\\([^)]*\\)|\\[[^\\]]*]")

    /**
     * The index in [text] just after the end of its [n]th sentence, once that end is certain (the character after it has arrived),
     * and only if it lies at or after [from]; else -1. A sentence ends at a lone ".", "!" or "?" (or a run of them with one of
     * these in it) followed by a space or a quote; an ellipsis ("..." or "…") is a pause, not an end, and an action in asterisks or
     * brackets has no sentences. Never ends an answer before ChatClean, which cuts at the same marks, would.
     */
    fun cutAfter(text: CharSequence, n: Int, from: Int = 0): Int {
        if (n <= 0 || text.isEmpty()) return -1
        val s = text.toString()
        // positions inside actions don't count: blank them out, keeping the indexes
        val masked = StringBuilder(s)
        for (m in ACTION.findAll(s)) for (i in m.range) masked.setCharAt(i, ' ')
        var count = 0
        var i = 0
        while (i < masked.length) {
            val ch = masked[i]
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\u2026') {
                var j = i
                while (j < masked.length && (masked[j] == '.' || masked[j] == '!' || masked[j] == '?' || masked[j] == '\u2026')) j++
                if (j >= masked.length) return -1   // the run may still grow: wait for the next character
                val run = masked.substring(i, j)
                val real = run.any { it == '!' || it == '?' } || (run.length == 1 && run[0] == '.')
                val closing = masked[j] == '"' || masked[j] == '\u201d' || masked[j] == '\''
                val next = if (closing) j + 1 else j
                if (real && (masked[j].isWhitespace() || closing)) {
                    count++
                    if (count >= n) return if (next >= from) next else -1
                }
                i = j
            } else i++
        }
        return -1
    }
}
