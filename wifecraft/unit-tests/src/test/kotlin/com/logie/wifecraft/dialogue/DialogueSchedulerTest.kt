package com.logie.wifecraft.dialogue

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A backend that answers "<npc>: reply" in two pieces, can be held (gate) per NPC, and honours cancel(). */
class FakeBackend : DialogueBackend {
    @Volatile var isLoaded = true
    override val loaded: Boolean get() = isLoaded
    val started = CopyOnWriteArrayList<BackendJob>()
    val cancelled = ConcurrentHashMap.newKeySet<Long>()
    val gates = ConcurrentHashMap<String, CountDownLatch>()
    val running = ConcurrentHashMap<String, CountDownLatch>()      // counted down when the NPC's job has started
    val resets = CopyOnWriteArrayList<String>()
    @Volatile var flushed = 0
    @Volatile var closed = false
    @Volatile var finish = DialogueFinish.EOS
    @Volatile var reply: (BackendJob) -> String = { "${it.npcId}: reply" }

    fun hold(npc: String): CountDownLatch = CountDownLatch(1).also { gates[npc] = it }
    fun started(npc: String): CountDownLatch = CountDownLatch(1).also { running[npc] = it }

    override fun generate(job: BackendJob): BackendResult {
        started.add(job)
        running[job.npcId]?.countDown()
        gates[job.npcId]?.let { g ->
            val end = System.nanoTime() + 5_000_000_000L
            while (g.count > 0 && job.ticket !in cancelled && System.nanoTime() < end) Thread.sleep(2)
        }
        if (job.ticket in cancelled) return BackendResult(DialogueFinish.CANCELLED, promptTokens = 10)
        val text = reply(job)
        val half = text.length / 2
        for (p in listOf(text.substring(0, half), text.substring(half))) {
            if (job.ticket in cancelled || !job.sink(p)) return BackendResult(DialogueFinish.CANCELLED, generated = 1)
        }
        return BackendResult(finish, if (finish == DialogueFinish.ERROR) "boom" else null, promptTokens = 100, reusedTokens = 60, generated = 8, prefillMs = 10.0, decodeMs = 100.0)
    }

    override fun cancel(ticket: Long) { cancelled.add(ticket) }
    override fun reset(npcId: String) { resets.add(npcId) }
    override fun resetAll() { resets.add("*") }
    override fun flush() { flushed++ }
    override fun close() { closed = true }
}

class DialogueSchedulerTest {
    private val backend = FakeBackend()
    private val clock = AtomicLong(1_000_000L)
    private var level = LoadLevel.OK
    private val schedulers = ArrayList<DialogueScheduler>()

    private fun scheduler(budget: TokenBudget = TokenBudget(60_000, 100_000) { clock.get() }, maxAmbient: Int = 6) =
        DialogueScheduler(backend, ChatTemplate.CHATML, budget, { level }, { clock.get() }, intArrayOf(0, 0), maxAmbient).also { schedulers.add(it) }

    @AfterTest fun cleanUp() { schedulers.forEach { it.close() } }

    private fun req(npc: String, p: DialoguePriority = DialoguePriority.AMBIENT, user: String = "hello", maxTokens: Int = 50) =
        DialogueRequest(npc, "You are $npc.", user = user, priority = p, maxTokens = maxTokens)

    private fun <T> run(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T = runBlocking(Dispatchers.Default) { withTimeout(15_000) { block() } }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!cond()) { if (System.nanoTime() > end) throw AssertionError("timed out: $what"); Thread.sleep(2) }
    }

    @Test fun answersARequest() = run {
        val s = scheduler()
        assertEquals("thalia: reply", s.complete(req("thalia")))
        assertTrue(backend.started[0].prompt.startsWith("<|im_start|>system\nYou are thalia.<|im_end|>"))
        assertTrue(backend.started[0].prompt.endsWith("<|im_start|>assistant\n"))
        assertEquals(1L, s.stats.value.answers)
        assertEquals(8L, s.stats.value.tokens)
    }

    @Test fun servesTheMostUrgentFirst() = run {
        val s = scheduler()
        val gate = backend.hold("blocker")
        val begun = backend.started("blocker")
        val blocker = async { s.complete(req("blocker", DialoguePriority.SELECTED)) }
        assertTrue(begun.await(5, TimeUnit.SECONDS))
        val order = listOf("amb" to DialoguePriority.AMBIENT, "near" to DialoguePriority.NEARBY, "pair" to DialoguePriority.PAIR, "sel" to DialoguePriority.SELECTED)
        val jobs = order.map { (n, p) -> async(start = CoroutineStart.UNDISPATCHED) { s.complete(req(n, p)) } }
        waitUntil("queued") { s.stats.value.queued == 4 }
        gate.countDown()
        blocker.await(); jobs.forEach { it.await() }
        assertEquals(listOf("blocker", "sel", "pair", "near", "amb"), backend.started.map { it.npcId })
    }

    @Test fun focusPromotesAnNpcsRequests() = run {
        val s = scheduler()
        val gate = backend.hold("blocker")
        val begun = backend.started("blocker")
        val blocker = async { s.complete(req("blocker", DialoguePriority.SELECTED)) }
        assertTrue(begun.await(5, TimeUnit.SECONDS))
        val a = async(start = CoroutineStart.UNDISPATCHED) { s.complete(req("a", DialoguePriority.NEARBY)) }
        val b = async(start = CoroutineStart.UNDISPATCHED) { s.complete(req("b", DialoguePriority.AMBIENT)) }
        val c = async(start = CoroutineStart.UNDISPATCHED) { s.complete(req("c", DialoguePriority.AMBIENT)) }
        waitUntil("queued") { s.stats.value.queued == 3 }
        s.setFocus(selected = "c", pair = setOf("b"))
        gate.countDown()
        blocker.await(); a.await(); b.await(); c.await()
        assertEquals(listOf("blocker", "c", "b", "a"), backend.started.map { it.npcId })
    }

    @Test fun anUrgentRequestPreemptsBackgroundTalkWhichThenFinishes() = run {
        val s = scheduler()
        val gate = backend.hold("bg")
        val begun = backend.started("bg")
        val bg = async { s.complete(req("bg", DialoguePriority.AMBIENT)) }
        assertTrue(begun.await(5, TimeUnit.SECONDS))
        val sel = async { s.complete(req("sel", DialoguePriority.SELECTED)) }
        assertEquals("sel: reply", sel.await())
        gate.countDown()
        assertEquals("bg: reply", bg.await())
        // bg started, was cancelled for sel, then ran again after it
        assertEquals(listOf("bg", "sel", "bg"), backend.started.map { it.npcId })
    }

    @Test fun cancellingTheCallerCancelsAQueuedRequest() = run {
        val s = scheduler()
        val gate = backend.hold("blocker")
        val begun = backend.started("blocker")
        val blocker = async { s.complete(req("blocker", DialoguePriority.SELECTED)) }
        assertTrue(begun.await(5, TimeUnit.SECONDS))
        val queued = launch(start = CoroutineStart.UNDISPATCHED) { s.complete(req("never", DialoguePriority.NEARBY)) }
        waitUntil("queued") { s.stats.value.queued == 1 }
        queued.cancelAndJoin()
        assertEquals(0, s.stats.value.queued)
        gate.countDown()
        blocker.await()
        assertTrue(backend.started.none { it.npcId == "never" })
    }

    @Test fun cancellingTheCallerStopsARunningRequest() = run {
        val s = scheduler()
        backend.hold("busy")
        val begun = backend.started("busy")
        val job = launch { s.complete(req("busy", DialoguePriority.SELECTED)) }
        assertTrue(begun.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        waitUntil("backend told") { backend.cancelled.isNotEmpty() }
        waitUntil("worker free") { s.stats.value.runningNpc == null }
        assertEquals("next: reply", s.complete(req("next")))
    }

    @Test fun theBudgetHoldsBackUnpromptedTalkButNotTheSelectedNpc() = run {
        val budget = TokenBudget(perMinute = 600, capacity = 100) { clock.get() }
        val s = scheduler(budget)
        budget.charge(100.0)                    // empty
        assertEquals(0, budget.available())
        val amb = async { s.complete(req("amb", DialoguePriority.AMBIENT, maxTokens = 20)) }
        Thread.sleep(400)
        assertEquals(0, backend.started.size, "ambient waits for budget")
        assertEquals("sel: reply", s.complete(req("sel", DialoguePriority.SELECTED)))
        assertEquals(1, backend.started.size, "selected is never held")
        clock.addAndGet(10_000)                 // 10 s at 600 a minute: 100 tokens
        assertEquals("amb: reply", amb.await())
    }

    @Test fun theBudgetRefillsAndIsCharged() {
        val b = TokenBudget(perMinute = 600, capacity = 200) { clock.get() }
        assertEquals(200, b.available())
        b.charge(150.0)
        assertEquals(50, b.available())
        clock.addAndGet(6_000)                  // 60 tokens
        assertEquals(110, b.available())
        clock.addAndGet(600_000)
        assertEquals(200, b.available(), "never above the capacity")
        assertTrue(!TokenBudget(0, 100) { clock.get() }.canStart(10), "zero a minute: no unprompted talk")
    }

    @Test fun aStrugglingRendererOnlyAllowsTheSelectedNpc() = run {
        val s = scheduler()
        level = LoadLevel.STRUGGLING
        val amb = async { s.complete(req("amb", DialoguePriority.AMBIENT)) }
        val pair = async { s.complete(req("pair", DialoguePriority.PAIR)) }
        Thread.sleep(400)
        assertEquals(0, backend.started.size)
        assertEquals("sel: reply", s.complete(req("sel", DialoguePriority.SELECTED)))
        assertEquals(20, backend.started[0].chunkDelayMs, "the selected NPC's prompt is read gently")
        level = LoadLevel.BUSY
        assertEquals("pair: reply", pair.await())
        assertEquals("amb: reply", amb.await())
        assertEquals(listOf("sel", "pair", "amb"), backend.started.map { it.npcId })
        assertEquals(30, backend.started.last().chunkDelayMs, "unprompted talk reads its prompt slowly while the renderer is busy")
    }

    @Test fun waitingTooLongGivesARequestUp() = run {
        val s = scheduler()
        level = LoadLevel.STRUGGLING
        val amb = async { s.complete(req("amb", DialoguePriority.AMBIENT)) }
        Thread.sleep(100)
        clock.addAndGet(41_000)                  // past the ambient wait of 40 s (the 12B model is slow: the waits are twice the 4B model's)
        assertNull(amb.await())
        assertEquals(1L, s.stats.value.dropped)
        assertEquals(0, backend.started.size)
    }

    @Test fun theSlowModelsWaitsAreLongerThanTheOldOnes() = run {
        val s = scheduler()
        level = LoadLevel.STRUGGLING
        val amb = async { s.complete(req("amb", DialoguePriority.AMBIENT)) }
        Thread.sleep(100)
        clock.addAndGet(30_000)                  // the old ambient wait was 20 s: now it is still waiting
        Thread.sleep(400)
        assertEquals(0L, s.stats.value.dropped, "30 s is inside the 40 s ambient wait")
        clock.addAndGet(11_000)
        assertNull(amb.await())
    }

    @Test fun aRequestAsksForNoMoreRepeatPenaltyThanTheModelLikes() = run {
        val s = scheduler()
        assertEquals("a: reply", s.complete(req("a", DialoguePriority.SELECTED).copy(repeatPenalty = 1.3f)))
        assertTrue(backend.started.last().repeatPenalty <= 1.08f)
        assertTrue(backend.started.last().stop.containsAll(listOf("<|im_start|>", "<|im_end|>")), "leaked turn marks end an answer")
    }

    @Test fun aNewerUnpromptedLineReplacesTheOlderOfTheSameNpc() = run {
        val s = scheduler()
        val gate = backend.hold("blocker")
        val begun = backend.started("blocker")
        val blocker = async { s.complete(req("blocker", DialoguePriority.SELECTED)) }
        assertTrue(begun.await(5, TimeUnit.SECONDS))
        val old = async(start = CoroutineStart.UNDISPATCHED) { s.complete(req("npc", DialoguePriority.NEARBY, user = "old")) }
        val new = async(start = CoroutineStart.UNDISPATCHED) { s.complete(req("npc", DialoguePriority.NEARBY, user = "new")) }
        assertNull(old.await())
        gate.countDown()
        assertEquals("npc: reply", new.await()); blocker.await()
        assertTrue(backend.started.last().prompt.contains("new"))
    }

    @Test fun theBackgroundQueueIsShort() = run {
        val s = scheduler(maxAmbient = 2)
        val gate = backend.hold("blocker")
        val begun = backend.started("blocker")
        val blocker = async { s.complete(req("blocker", DialoguePriority.SELECTED)) }
        assertTrue(begun.await(5, TimeUnit.SECONDS))
        val jobs = (1..4).map { i -> async(start = CoroutineStart.UNDISPATCHED) { s.complete(req("n$i", DialoguePriority.AMBIENT)) } }
        assertNull(jobs[0].await()); assertNull(jobs[1].await())
        gate.countDown()
        assertEquals("n3: reply", jobs[2].await()); assertEquals("n4: reply", jobs[3].await()); blocker.await()
    }

    @Test fun forgetRunsOnTheWorkerAfterTheRunningAnswer() = run {
        val s = scheduler()
        s.forget("thalia")
        waitUntil("reset") { backend.resets == listOf("thalia") }
        s.forgetAll()
        waitUntil("reset all") { backend.resets.last() == "*" }
        s.flush()
        waitUntil("flush") { backend.flushed == 1 }
    }

    @Test fun noModelMeansNoAnswerAndNoWaiting() = run {
        backend.isLoaded = false
        val s = scheduler()
        assertTrue(!s.ready)
        assertNull(s.complete(req("thalia")))
        assertEquals(0, backend.started.size)
        assertNull(NoDialogueEngine.complete(req("thalia")))
    }

    @Test fun aFailedOrCutOffAnswerIsNullAndRecorded() = run {
        val s = scheduler()
        backend.finish = DialogueFinish.ERROR
        assertNull(s.complete(req("thalia")))
        assertEquals("boom", s.stats.value.lastError)
        backend.finish = DialogueFinish.LENGTH
        assertEquals("thalia: reply", s.complete(req("thalia")), "an answer cut at the token limit is still an answer")
    }

    @Test fun thinkingAndControlMarksAreTakenOutOfTheAnswer() = run {
        val s = scheduler()
        backend.reply = { "<think>hmm</think>\n\nHello there.<|im_end|>" }
        assertEquals("Hello there.", s.complete(req("thalia")))
    }

    @Test fun prefillOnlyReadsThePromptAndAnswersNothing() = run {
        val s = scheduler()
        assertNull(s.complete(req("thalia").copy(prefillOnly = true)))
        assertEquals(1, backend.started[0].maxTokens)
    }

    @Test fun streamingEmitsThePiecesAsTheyComeAndCompletes() = run {
        val s = scheduler()
        val pieces = s.stream(req("thalia")).toList()
        assertEquals("thalia: reply", pieces.joinToString(""))
        assertTrue(pieces.size >= 2)
    }

    @Test fun partialTextGoesToTheRequestsCallback() = run {
        val s = scheduler()
        val got = StringBuilder()
        s.complete(req("thalia").copy(onPartial = { got.append(it) }))
        assertEquals("thalia: reply", got.toString())
    }

    @Test fun closeAnswersEverythingWaitingWithNothing() = run {
        val s = scheduler()
        val gate = backend.hold("blocker")
        val begun = backend.started("blocker")
        val blocker = async { s.complete(req("blocker", DialoguePriority.SELECTED)) }
        assertTrue(begun.await(5, TimeUnit.SECONDS))
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { s.complete(req("w", DialoguePriority.NEARBY)) }
        waitUntil("queued") { s.stats.value.queued == 1 }
        s.close()
        assertNull(waiting.await())
        assertNull(blocker.await())
        assertTrue(backend.closed)
        gate.countDown()
        assertNull(s.complete(req("late")))
    }

    @Test fun nativeResultLinesParse() {
        val r = NativeBackend.parseResult("stop\t150\t100\t20\t10\t42\t12.5\t840.0\t")
        assertEquals(DialogueFinish.STOP, r.finish)
        assertEquals(20, r.readTokens)
        assertEquals(42, r.generated)
        assertNull(r.error)
        val e = NativeBackend.parseResult("error\t0\t0\t0\t0\t0\t0.0\t0.0\tthe context is full")
        assertEquals(DialogueFinish.ERROR, e.finish)
        assertEquals("the context is full", e.error)
        assertEquals(9, ModelInfo.parse("qwen3 4B\t2497281632\t4000000000\t262144\t6144\t6\t2\t900\t3300000000")?.slots?.let { 9 })
    }
}
