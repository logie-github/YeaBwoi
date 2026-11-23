package com.logie.wifecraft.dialogue

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The scheduler's speed work: timings, warm-up, early stops, preemption (fake backend, JVM). */
class SchedulerSpeedTest {
    private val backend = FakeBackend()
    private val clock = AtomicLong(1_000_000L)
    private val schedulers = ArrayList<DialogueScheduler>()

    private fun scheduler(budget: TokenBudget = TokenBudget(60_000, 100_000) { clock.get() }) =
        DialogueScheduler(backend, ChatTemplate.CHATML, budget, { LoadLevel.OK }, { clock.get() }, intArrayOf(0, 0), 6).also { schedulers.add(it) }

    @AfterTest fun cleanUp() { schedulers.forEach { it.close() } }

    private fun req(npc: String, p: DialoguePriority = DialoguePriority.AMBIENT, user: String = "hello", tag: String = "") =
        DialogueRequest(npc, "You are $npc.", user = user, priority = p, maxTokens = 50, tag = tag)

    private fun <T> run(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T = runBlocking(Dispatchers.Default) { withTimeout(15_000) { block() } }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!cond()) { if (System.nanoTime() > end) throw AssertionError("timed out: $what"); Thread.sleep(2) }
    }

    // ---- timings ----------------------------------------------------------------------------------------------------------

    @Test fun everyRequestLeavesATimingWithWhatItCost() = run {
        val s = scheduler()
        backend.reply = { clock.addAndGet(2500); "a long enough line" }   // 2.5 s pass before the first piece
        assertNotNull(s.complete(req("thalia", DialoguePriority.SELECTED, tag = "chat")))
        val t = s.timings.all().single()
        assertEquals("chat", t.tag); assertEquals("thalia", t.npcId); assertEquals(DialoguePriority.SELECTED, t.priority)
        assertEquals(100, t.promptTokens); assertEquals(60, t.reusedTokens); assertEquals(40, t.readTokens)
        assertEquals(8, t.generated); assertEquals(10.0, t.prefillMs); assertEquals(100.0, t.decodeMs)
        assertEquals(2500L, t.firstWordMs, "from being asked to the first piece: the number the player feels")
        assertTrue(t.totalMs >= t.firstWordMs)
        assertEquals("hit", t.slot)
        assertEquals(1, s.timings.summary().replies)
        assertEquals(2500L, s.stats.value.lastFirstWordMs)
    }

    @Test fun theTimeInTheQueueIsKeptApartFromTheTimeRunning() = run {
        val s = scheduler()
        val gate = backend.hold("blocker"); val begun = backend.started("blocker")
        val blocker = async { s.complete(req("blocker", DialoguePriority.SELECTED)) }
        assertTrue(begun.await(5, TimeUnit.SECONDS))
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { s.complete(req("waiter", DialoguePriority.SELECTED, tag = "chat")) }
        waitUntil("queued") { s.stats.value.queued == 1 }
        clock.addAndGet(7000)   // seven seconds behind the blocker
        gate.countDown()
        blocker.await(); waiting.await()
        val t = s.timings.all().first { it.npcId == "waiter" }
        assertTrue(t.queueWaitMs >= 7000, "waited ${t.queueWaitMs} ms")
        assertTrue(t.firstWordMs >= t.queueWaitMs)
    }

    @Test fun theNativeResultsFieldsReachTheTiming() = run {
        val s = scheduler()
        val fb = object : DialogueBackend by backend {
            override fun generate(job: BackendJob): BackendResult { job.sink("a long enough line"); return BackendResult(DialogueFinish.EOS, promptTokens = 300, reusedTokens = 0, sharedTokens = 165, restoredTokens = 0, generated = 20, prefillMs = 5000.0, decodeMs = 4000.0, firstTokenMs = 5100.0, kvTokens = 320, threads = 4, threadsBatch = 6) }
        }
        val s2 = DialogueScheduler(fb, ChatTemplate.CHATML, TokenBudget(60_000, 100_000) { clock.get() }, { LoadLevel.OK }, { clock.get() }, intArrayOf(0, 0), 6).also { schedulers.add(it) }
        s2.complete(req("a", DialoguePriority.SELECTED))
        val t = s2.timings.all().single()
        assertEquals(5100.0, t.firstTokenMs); assertEquals(320, t.kvTokens); assertEquals(4, t.threads); assertEquals(6, t.threadsBatch)
        assertEquals("borrowed", t.slot); assertEquals(135, t.readTokens); assertEquals(5.0, t.decodeTokensPerSecond, 1e-9)
    }

    // ---- warm-up ----------------------------------------------------------------------------------------------------------

    @Test fun aWarmUpOnlyReadsThePromptAndCostsTheBudgetNothing() = run {
        val budget = TokenBudget(60_000, 100_000) { clock.get() }
        val before = budget.available()
        val s = scheduler(budget)
        s.warm("thalia", "You are thalia. Persona text.", urgent = false)
        waitUntil("read") { backend.started.size == 1 }
        waitUntil("recorded") { s.timings.size == 1 }
        val j = backend.started[0]
        assertEquals(0, j.maxTokens, "nothing is sampled")
        assertTrue(j.prompt.contains("You are thalia. Persona text."))
        assertEquals(before, budget.available(), "the warm-up is the system's, not the unprompted talk's")
        assertEquals("warm", s.timings.all().single().tag)
        assertTrue(s.timings.all().single().prefillOnly)
        assertEquals(0, s.timings.summary().replies, "a warm-up is no reply")
    }

    @Test fun theSamePersonaIsNotReadTwiceUntilTheNpcIsForgotten() = run {
        val s = scheduler()
        s.warm("thalia", "persona A"); waitUntil("first") { backend.started.size == 1 }
        s.warm("thalia", "persona A"); s.warm("thalia", "persona A", urgent = true)
        Thread.sleep(150)
        assertEquals(1, backend.started.size, "asking again for the same text is free")
        s.warm("thalia", "persona B"); waitUntil("changed") { backend.started.size == 2 }
        s.forget("thalia"); Thread.sleep(50)
        s.warm("thalia", "persona B"); waitUntil("after forget") { backend.started.size == 3 }
    }

    @Test fun anUrgentWarmUpGoesBeforeEverythingAndAnOrdinaryOneBehindRealTalk() = run {
        val s = scheduler()
        val gate = backend.hold("blocker"); val begun = backend.started("blocker")
        val blocker = async { s.complete(req("blocker", DialoguePriority.SELECTED)) }
        assertTrue(begun.await(5, TimeUnit.SECONDS))
        s.warm("far", "persona far", urgent = false)                                        // queued first
        val talk = async(start = CoroutineStart.UNDISPATCHED) { s.complete(req("talker", DialoguePriority.AMBIENT)) }   // a line someone will hear, same priority
        s.warm("chat:jaina", "persona jaina", urgent = true)                               // the player opened a chat
        waitUntil("queued") { s.stats.value.queued == 3 }
        gate.countDown()
        blocker.await(); talk.await()
        waitUntil("all read") { backend.started.size == 4 }
        assertEquals(listOf("blocker", "chat:jaina", "talker", "far"), backend.started.map { it.npcId }, "urgent warm-up first, the ordinary one after real talk")
    }

    @Test fun anOrdinaryWarmUpIsNotHeldBackByAnEmptyBudget() = run {
        val s = scheduler(TokenBudget(0, 100) { clock.get() })   // no unprompted talk may start
        s.warm("thalia", "persona")
        waitUntil("read") { backend.started.size == 1 }
        val amb = async(start = CoroutineStart.UNDISPATCHED) { s.complete(req("other", DialoguePriority.AMBIENT)) }
        Thread.sleep(100)
        assertEquals(1, backend.started.size, "talk waits for the budget, the warm-up did not")
        amb.cancel()
    }

    @Test fun waitingWarmUpsAreFewAndANewerOneForTheSameNpcReplacesTheOlder() = run {
        val s = scheduler()
        val gate = backend.hold("blocker"); val begun = backend.started("blocker")
        val blocker = async { s.complete(req("blocker", DialoguePriority.SELECTED)) }
        assertTrue(begun.await(5, TimeUnit.SECONDS))
        for (i in 1..12) s.warm("n$i", "persona $i")
        s.warm("n12", "persona 12 changed")
        waitUntil("queued") { s.stats.value.queued <= 8 }
        assertEquals(8, s.stats.value.queued, "only the newest eight wait")
        gate.countDown(); blocker.await()
        waitUntil("read") { backend.started.size == 9 }
        assertTrue(backend.started.drop(1).any { it.prompt.contains("persona 12 changed") } && backend.started.none { it.prompt.contains("persona 12<") })
    }

    @Test fun aRunningWarmUpGivesWayToAnUrgentRequestAndCarriesOnAfterIt() = run {
        val s = scheduler()
        val gate = backend.hold("far"); val begun = backend.started("far")
        s.warm("far", "persona far")
        assertTrue(begun.await(5, TimeUnit.SECONDS))
        val sel = async { s.complete(req("sel", DialoguePriority.SELECTED)) }
        waitUntil("the warm-up was cancelled") { backend.cancelled.isNotEmpty() }
        assertEquals("sel: reply", sel.await(), "the selected NPC is not kept waiting for a warm-up")
        gate.countDown()
        waitUntil("the warm-up ran again") { backend.started.count { it.npcId == "far" } == 2 }
    }

    @Test fun theEngineDefaultForWarmDoesNothingSoOtherEnginesNeedNoChange() = run {
        val e = ScriptedEngine { "x" }
        e.warm("a", "b", true)
        assertEquals("x", e.complete(DialogueRequest("a", "s", user = "u")))
    }

    // ---- preemption --------------------------------------------------------------------------------------------------------

    @Test fun aSelectedRequestCancelsARunningAmbientAnswerAtOnce() = run {
        val s = scheduler()
        val gate = backend.hold("amb"); val begun = backend.started("amb")
        val amb = async { s.complete(req("amb", DialoguePriority.AMBIENT)) }
        assertTrue(begun.await(5, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        val sel = async { s.complete(req("sel", DialoguePriority.SELECTED)) }
        waitUntil("the ambient decode was cancelled") { backend.cancelled.isNotEmpty() }
        assertTrue((System.nanoTime() - t0) < 1_000_000_000L, "cancelled within a second, not at the end of its answer")
        assertEquals("sel: reply", sel.await())
        gate.countDown()
        assertEquals("amb: reply", amb.await(), "and the ambient line is run again afterwards, once")
        assertEquals(listOf("amb", "sel", "amb"), backend.started.map { it.npcId })
    }

    // ---- early stops -------------------------------------------------------------------------------------------------------

    @Test fun aSentenceStopEndsTheAnswerAndItStillCountsAsAnAnswer() = run {
        val s = scheduler()
        backend.reply = { "Well met, traveller. The road north is long. Mind the wolves." }
        val shown = StringBuilder()
        val text = s.complete(req("thalia", DialoguePriority.SELECTED).copy(sentences = 2, onPartial = { shown.append(it) }))
        assertEquals("Well met, traveller. The road north is long.", text, "two sentences, nothing after")
        assertEquals(text, shown.toString().trim(), "and nothing past them was shown")
        val t = s.timings.all().single()
        assertTrue(t.softStopped && t.isReply)
        assertEquals(1L, s.stats.value.answers)
        assertEquals(1, backend.started.size, "not run again as if it had been cancelled")
    }

    @Test fun anAnswerThatEndsBeforeItsSentenceLimitIsLeftAlone() = run {
        val s = scheduler()
        backend.reply = { "Aye." }
        assertEquals("Aye.", s.complete(req("thalia", DialoguePriority.SELECTED).copy(sentences = 2)))
        assertFalse(s.timings.all().single().softStopped)
    }

    @Test fun theStreamGuardEndsAWrongAnswerAtOnceAndTheAskerLearnsWhy() = run {
        val s = scheduler()
        backend.reply = { "I would use my phone for that, and then more words that are never written." }
        var seen: String? = null
        val shown = StringBuilder()
        val text = s.complete(req("thalia", DialoguePriority.SELECTED).copy(guard = { t -> StreamGuard.problem(t)?.also { seen = it } != null }, onPartial = { shown.append(it) }))
        assertNull(text, "a guarded answer is no answer")
        assertEquals("phone", seen)
        assertFalse("phone" in shown.toString(), "the wrong word is not shown")
        val t = s.timings.all().single()
        assertTrue(t.guarded && !t.isReply)
        assertEquals(0L, s.stats.value.dropped, "a stopped answer is not a failure of the model")
    }

    @Test fun aGuardStopIsNotRequeuedLikeAPreemption() = run {
        val s = scheduler()
        backend.reply = { "My phone is dead." }
        s.complete(req("thalia", DialoguePriority.AMBIENT).copy(guard = { true }))
        Thread.sleep(100)
        assertEquals(1, backend.started.size)
    }

    // ---- the guard and the sentence counter as functions --------------------------------------------------------------------

    @Test fun theGuardWaitsForAWordToBeFinishedAndIgnoresActions() {
        assertNull(StreamGuard.problem("I will ok"), "'ok' may still become 'okay'... a word under way is not judged")
        assertEquals("okay", StreamGuard.problem("I will okay "))
        assertNull(StreamGuard.problem("*checks her phone* Well met"), "an action is no speech")
        assertNull(StreamGuard.problem("Well met, (looks at the menu"), "an open action is not read")
        assertEquals(StreamGuard.WALL, StreamGuard.problem("The game is over, "))
        assertNull(StreamGuard.problem(""))
        assertNull(StreamGuard.problem("Well met, traveller. The road is "))
        assertTrue("phone" in StreamGuard.note("phone") && "beyond this world" in StreamGuard.note(StreamGuard.WALL))
    }

    @Test fun sentenceEndsAreFoundOnlyOnceTheyAreCertain() {
        assertEquals(-1, SentenceStop.cutAfter("Well met.", 1), "the run may still grow into an ellipsis")
        assertEquals(9, SentenceStop.cutAfter("Well met. I", 1))
        assertEquals(-1, SentenceStop.cutAfter("Well... I suppose", 1), "an ellipsis is a pause")
        assertEquals(-1, SentenceStop.cutAfter("Well\u2026 I suppose", 1))
        assertEquals(18, SentenceStop.cutAfter("Well... I suppose. Aye", 1), "the pause is passed over, the real end is found")
        assertEquals(9, SentenceStop.cutAfter("What now? I", 1))
        assertEquals(-1, SentenceStop.cutAfter("One. Two", 2), "the second has not ended")
        assertEquals(9, SentenceStop.cutAfter("One. Two. T", 2))
        assertEquals(6, SentenceStop.cutAfter("\"Aye.\" Then", 1), "a closing quote goes with its sentence")
        assertEquals(-1, SentenceStop.cutAfter("*smiles. widely* Well", 1), "an action has no sentences")
        assertEquals(-1, SentenceStop.cutAfter("Aye. Then", 0), "no limit asked")
    }
}
