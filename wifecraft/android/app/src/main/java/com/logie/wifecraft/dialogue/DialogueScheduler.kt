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
    fun close()
}

/**
 * Tokens that unprompted talk may use, refilled steadily: [perMinute] a minute, up to [capacity] saved up. NEARBY and AMBIENT
 * requests start only with some in hand and pay what they use; the selected NPC and a pair never wait for it but still pay
 * (so the background goes quiet while the player is chatting).
 */
class TokenBudget(var perMinute: Int = 900, val capacity: Int = 600, private val clock: () -> Long = System::currentTimeMillis) {
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
) : DialogueEngine {

    private class Job(val id: Long, val req: DialogueRequest, val queuedAt: Long, val finish: (String?) -> Unit) {
        @Volatile var cancelled = false
        @Volatile var preempted = false
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

    private val _stats = MutableStateFlow(DialogueStats())
    val stats: StateFlow<DialogueStats> get() = _stats

    private val worker = Thread(null, { loop() }, "wc-dialogue").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }

    override val ready: Boolean get() = backend.loaded && !closed

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

    override fun forget(npcId: String) = command { backend.reset(npcId) }

    fun forgetAll() = command { backend.resetAll() }

    /** The app leaves the foreground: every NPC's place is saved so it is read back, not read again. */
    fun flush() = command { backend.flush() }

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
            val amb = queue.filter { it.req.priority == DialoguePriority.AMBIENT }
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

    private fun maxWait(j: Job): Long = j.req.maxWaitMs ?: when (effective(j)) {
        DialoguePriority.SELECTED -> 120_000L
        DialoguePriority.PAIR -> 60_000L
        DialoguePriority.NEARBY -> 30_000L
        DialoguePriority.AMBIENT -> 20_000L
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
            if (p >= DialoguePriority.NEARBY && !budget.canStart(j.req.maxTokens)) continue
            if (best == null || p < bestP) { best = j; bestP = p }   // equal priority: the earlier one stays (FIFO)
        }
        if (best != null) queue.remove(best)
        return best
    }

    private fun run(job: Job) {
        val req = job.req
        val level0 = load()
        val sb = StringBuilder()
        val urgent = effective(job) <= DialoguePriority.PAIR
        val prompt = template.render(req.system, req.history, req.user)
        val bj = BackendJob(
            ticket = job.ticket, npcId = req.npcId, prompt = prompt,
            maxTokens = if (req.prefillOnly) 1 else req.maxTokens.coerceAtLeast(1),
            temperature = req.temperature, topP = req.topP, repeatPenalty = req.repeatPenalty, seed = req.seed, stop = req.stop,
            chunkDelayMs = when (level0) { LoadLevel.OK -> 0; LoadLevel.BUSY -> if (urgent) 0 else 30; LoadLevel.STRUGGLING -> if (urgent) 20 else 100 },
            sink = { piece ->
                sb.append(piece)
                if (!req.prefillOnly) req.onPartial?.let { runCatching { it(piece) } }
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
        // what the answer cost
        budget.charge(result.generated + result.readTokens / 8.0)
        val requeue: Boolean
        lock.withLock {
            current = null
            requeue = job.preempted && !job.cancelled && !closed && job.requeues < 1 && result.finish == DialogueFinish.CANCELLED
            if (requeue) { job.preempted = false; job.requeues++; queue.add(0, job) }
            publish(null)
        }
        if (requeue) { return }
        val ok = result.finish == DialogueFinish.EOS || result.finish == DialogueFinish.LENGTH || result.finish == DialogueFinish.STOP
        val text = if (ok && !req.prefillOnly) ChatTemplate.tidy(sb.toString()).ifEmpty { null } else null
        count(answers = if (ok) 1 else 0, dropped = if (ok) 0 else 1, tokens = result.generated, result = result, error = result.error)
        job.end(text)
    }
}
