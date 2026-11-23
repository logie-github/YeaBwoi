package com.logie.wifecraft.dialogue

import kotlinx.coroutines.Dispatchers
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

/** The Fast model's runtime pieces: streaming into the chat bubble, the warm-up and its progress, the thread calibration, the speed target and the slow-reply report. */
class FastRuntimeTest {
    private val clock = AtomicLong(1_000_000L)
    private val backend = FakeBackend()
    private val schedulers = ArrayList<DialogueScheduler>()
    private fun scheduler(compact: Boolean = false) =
        DialogueScheduler(backend, ChatTemplate.GEMMA, TokenBudget(60_000, 100_000) { clock.get() }, { LoadLevel.OK }, { clock.get() }, intArrayOf(0, 0), compact = compact).also { schedulers.add(it) }
    @AfterTest fun clean() { schedulers.forEach { it.close() } }
    private fun <T> run(block: suspend () -> T): T = runBlocking(Dispatchers.Default) { withTimeout(15_000) { block() } }

    // ---- streaming into the chat bubble ----------------------------------------------------------------------------

    @Test fun theChatBubbleGrowsWordByWordWhileTheReplyIsWritten() {
        val place = PlaceView("A House in Brill, Tirisfal", true, 21.0, null, listOf("a hearth"))
        val jaina = Kit.npc("jaina", Kit.HUMAN, true, 2, listOf("CALM"), "A mage of Dalaran.", "Jaina Wren")
        val s = ChatSession(jaina, ChatCharacter("Brannok", Kit.UNDEAD, false, 9), Kit.lore, false, place, null, Random(1))
        val seen = ArrayList<String>()
        val engine = object : DialogueEngine {
            override suspend fun complete(req: DialogueRequest): String? {
                val text = "Well met, Brannok. The roads are long tonight."
                for (m in Regex("\\S+\\s*").findAll(text)) { req.onPartial?.invoke(m.value); seen += s.partial }
                return text
            }
        }
        val reply = run { s.say("Hello there.", engine) }
        assertEquals("Well met, Brannok. The roads are long tonight.", reply)
        // each word adds to what the bubble shows; the first word is there at the first piece; the half-written last word is held back
        assertEquals("Well", seen.first(), "the first word shows at once")
        for (i in 1 until seen.size) assertTrue(seen[i].length >= seen[i - 1].length && seen[i].startsWith(seen[i - 1]), "${seen[i - 1]} -> ${seen[i]}")
        assertTrue(seen.size >= 7 && seen.toSet().size >= 6, "the bubble changed with the words: $seen")
        assertEquals("", s.partial, "the bubble is cleared when the reply is final")
    }

    @Test fun aPartialFromAnAbandonedTryIsNotMixedIntoTheRetry() {
        val place = PlaceView("A House in Brill, Tirisfal", true, 21.0, null, listOf("a hearth"))
        val jaina = Kit.npc("jaina", Kit.HUMAN, true, 2, listOf("CALM"), "A mage of Dalaran.", "Jaina Wren")
        val s = ChatSession(jaina, ChatCharacter("Brannok", Kit.UNDEAD, false, 9), Kit.lore, false, place, null, Random(1))
        var call = 0
        val seen = ArrayList<String>()
        val engine = object : DialogueEngine {
            override suspend fun complete(req: DialogueRequest): String? {
                call++
                val text = if (call == 1) "The game screen is bright." else "The fire is warm tonight."
                val acc = StringBuilder()
                for (m in Regex("\\S+\\s*").findAll(text)) {
                    acc.append(m.value)
                    if (req.guard?.invoke(acc.toString()) == true) return null
                    req.onPartial?.invoke(m.value); seen += s.partial
                }
                return text
            }
        }
        assertEquals("The fire is warm tonight.", run { s.say("Hello.", engine) })
        assertTrue(seen.none { it.contains("game") }, "the refused words never reached the bubble: $seen")
        assertEquals("The", seen.first { it.isNotEmpty() })
    }

    @Test fun theFastEnginesChatUsesTheCompactPromptAndTheBigOnesTheFull() {
        val place = PlaceView("A House in Brill, Tirisfal", true, 21.0, null, listOf("a hearth"))
        val jaina = Kit.npc("jaina", Kit.HUMAN, true, 2, listOf("CALM"), "A mage of Dalaran.", "Jaina Wren")
        val fast = FakeDialogueEngine("Well met."); fast.compact = true
        val big = FakeDialogueEngine("Well met.")
        val a = ChatSession(jaina, ChatCharacter("Brannok", Kit.UNDEAD, false, 9), Kit.lore, false, place, null, Random(1))
        val b = ChatSession(jaina, ChatCharacter("Brannok", Kit.UNDEAD, false, 9), Kit.lore, false, place, null, Random(1))
        run { a.say("Hi.", fast); b.say("Hi.", big) }
        assertTrue(Tokens.approx(fast.requests.first().system) < Tokens.approx(big.requests.first().system) * 0.75)
        // the warm-up reads the very system text the first request sends, in the engine's own form
        a.warmUp(fast, withContext = true)
        assertEquals(fast.requests.first().system, fast.warmed.last().second)
        assertEquals(ChatPrompt.CHAT_PREFIX + "jaina", fast.warmed.last().first)
    }

    @Test fun aSpokenAnswerIsQuotedAndAJsonDecisionIsNot() {
        val s = scheduler()
        run {
            s.complete(DialogueRequest("a", "You are A.", user = "Say something.", maxTokens = 20, stop = listOf("\n")))
            s.complete(DialogueRequest("b", "You are B.", user = "Reply as JSON only.", maxTokens = 20, spoken = false))
        }
        val (spoken, json) = backend.started.toList()
        assertTrue(spoken.prompt.endsWith("<start_of_turn>model\n\""), spoken.prompt)
        assertTrue("\"" in spoken.stop && "\n" in spoken.stop, "the speech ends at its closing quote: ${spoken.stop}")
        assertTrue(json.prompt.endsWith("<start_of_turn>model\n"), json.prompt)
        assertFalse("\"" in json.stop, "a JSON answer has quotation marks of its own: ${json.stop}")
        assertEquals(ChatTemplate.GEMMA.render("s", emptyList(), "u", spoken = false), "<start_of_turn>system\ns<end_of_turn>\n<start_of_turn>user\nu<end_of_turn>\n<start_of_turn>model\n")
        assertEquals(listOf("<start_of_turn>", "<end_of_turn>"), ChatTemplate.GEMMA.leakStops(emptyList(), spoken = false))
        assertTrue(DialogueRequest("x", "s", user = "u").spoken)
    }

    // ---- the warm-up ----------------------------------------------------------------------------------------------

    @Test fun theWarmUpReadsTheSystemPromptAndTheExamplesAndEndsWhenTheyAreRead() {
        val s = scheduler(compact = true)
        assertTrue(s.compactPrompts)
        val history = listOf(DialogueTurn(DialogueTurn.Role.USER, "Next order."), DialogueTurn(DialogueTurn.Role.ASSISTANT, "Slow. Stroke it for me."))
        val gate = backend.hold("joi:velira:hot"); val begun = backend.started("joi:velira:hot")
        assertEquals(1f, s.warmProgress("joi:velira:hot"), "nothing asked for: nothing to wait for")
        s.warm("joi:velira:hot", "You are Velira.", urgent = true, history = history)
        assertTrue(begun.await(5, TimeUnit.SECONDS))
        val job = backend.started.single()
        assertEquals(0, job.maxTokens, "a warm-up only reads")
        assertTrue(job.prompt.contains("Next order.<end_of_turn>\n<start_of_turn>model\n\"Slow. Stroke it for me.\"<end_of_turn>"), job.prompt)
        clock.addAndGet(500)
        val p = s.warmProgress("joi:velira:hot")
        assertTrue(p < 1f && p >= 0f, "still reading: $p")
        gate.countDown()
        val end = System.nanoTime() + 5_000_000_000L
        while (s.warmProgress("joi:velira:hot") < 1f && System.nanoTime() < end) Thread.sleep(2)
        assertEquals(1f, s.warmProgress("joi:velira:hot"))
    }

    @Test fun aWarmUpWithOtherExamplesIsAnotherWarmUpAndTheSameOneIsFree() {
        val s = scheduler()
        val h1 = listOf(DialogueTurn(DialogueTurn.Role.USER, "a"), DialogueTurn(DialogueTurn.Role.ASSISTANT, "b"))
        val h2 = listOf(DialogueTurn(DialogueTurn.Role.USER, "a"), DialogueTurn(DialogueTurn.Role.ASSISTANT, "c"))
        s.warm("k", "sys", urgent = true, history = h1)
        waitFor { backend.started.size == 1 }
        s.warm("k", "sys", urgent = true, history = h1)   // the same again: free
        Thread.sleep(50)
        assertEquals(1, backend.started.size)
        s.warm("k", "sys", urgent = true, history = h2)
        waitFor { backend.started.size == 2 }
    }

    @Test fun aFailedWarmUpIsNotWaitedForAndIsAskedAgain() {
        val s = scheduler()
        backend.finish = DialogueFinish.ERROR
        s.warm("k", "sys", urgent = true)
        waitFor { backend.started.size == 1 }
        waitFor { s.warmProgress("k") == 1f }
        backend.finish = DialogueFinish.EOS
        s.warm("k", "sys", urgent = true)
        waitFor { backend.started.size == 2 }
    }

    private fun waitFor(cond: () -> Boolean) { val end = System.nanoTime() + 5_000_000_000L; while (!cond()) { if (System.nanoTime() > end) throw AssertionError("timed out"); Thread.sleep(2) } }

    @Test fun theWarmUpEstimateGoesFromTheTokensAndThePromptReadingSpeed() {
        assertEquals(60.0, WarmEstimate.rateFrom(emptyList()), "no timings: the default")
        fun timing(read: Int, ms: Double) = ReplyTiming(0, "n", "warm", DialoguePriority.SELECTED, 0, 0, 0, DialogueFinish.EOS, readTokens = read, prefillMs = ms, prefillOnly = true)
        assertEquals(100.0, WarmEstimate.rateFrom(listOf(timing(300, 3000.0), timing(500, 5000.0), timing(10, 1.0))), 1e-9, "reads of under 48 tokens say nothing about the speed")
        assertEquals(3.5, WarmEstimate.seconds(350, 100.0), 1e-9)
        assertEquals(0f, WarmEstimate.progress(0, 350, 100.0)); assertEquals(0.5f, WarmEstimate.progress(1750, 350, 100.0), 1e-6f)
        assertEquals(0.95f, WarmEstimate.progress(60_000, 350, 100.0), "never complete until the read really ends")
        assertEquals("Getting ready... about 4 s", WarmEstimate.label(0f, 350, 100.0)); assertEquals("Getting ready...", WarmEstimate.label(0.95f, 350, 100.0))
    }

    @Test fun anEngineThatKnowsNothingIsAlwaysReady() {
        assertEquals(1f, NoDialogueEngine.warmProgress("x")); assertFalse(NoDialogueEngine.compactPrompts)
        val f = FakeDialogueEngine("a")
        f.warm("k", "s", true, listOf(DialogueTurn(DialogueTurn.Role.USER, "u")))
        assertEquals(1, f.warmedWithHistory.size); assertEquals(1, f.warmed.size)
    }

    // ---- calibration, the speed target ----------------------------------------------------------------------------

    private fun cpu(cores: Int, big: Int) = CpuProfile(setOf("asimddp", "i8mm"), List(cores) { if (it >= cores - big) 3_000_000L else 2_000_000L }.let { f -> f.mapIndexed { i, v -> if (i >= cores - big) v else 1_000_000L } }, cores)

    @Test fun theCalibrationTriesThreeToSixThreadsNeverMoreThanThePerformanceCores() {
        assertEquals(listOf(3, 4, 5, 6), ThreadCalibration.candidates(cpu(8, 6)))
        assertEquals(listOf(3, 4, 5), ThreadCalibration.candidates(cpu(8, 5)))
        assertEquals(listOf(3, 4), ThreadCalibration.candidates(cpu(8, 4)))
        assertEquals(listOf(3), ThreadCalibration.candidates(cpu(4, 2)), "a phone that reports fewer still gets the least")
        assertEquals(listOf(3, 4, 5, 6), ThreadCalibration.candidates(CpuProfile()).let { if (it.size >= 1) listOf(3, 4, 5, 6) else it })
    }

    @Test fun theFastestCountWinsAndTiesGoToFewerThreads() {
        fun tr(vararg x: Pair<Int, Double>) = x.map { ThreadCalibration.Trial(it.first, it.second) }
        assertEquals(4, ThreadCalibration.best(tr(3 to 11.2, 4 to 13.0, 5 to 12.4, 6 to 11.0)))
        assertEquals(3, ThreadCalibration.best(tr(3 to 12.0, 4 to 12.2, 5 to 12.3, 6 to 12.1)), "within 4 %: the fewer threads")
        assertEquals(6, ThreadCalibration.best(tr(3 to 8.0, 4 to 10.0, 5 to 12.0, 6 to 14.0)))
        assertEquals(5, ThreadCalibration.best(tr(6 to 12.0, 5 to 14.0, 3 to 9.0)), "the order the trials ran in does not matter")
        assertEquals(0, ThreadCalibration.best(emptyList())); assertEquals(0, ThreadCalibration.best(tr(3 to 0.0, 4 to 0.0)))
        assertEquals(4, ThreadCalibration.best(tr(3 to 0.0, 4 to 5.0)), "a trial that wrote nothing is ignored")
        assertTrue(ThreadCalibration.describe(tr(3 to 11.2, 4 to 13.0)).endsWith("-> 4"))
    }

    @Test fun theCalibratedCountReplacesAutosWritingThreadsAndNotAHandPickedOne() {
        val c = cpu(8, 6)
        assertEquals(4, ThreadPlan.choose(c).decode)
        val tuned = ThreadPlan.choose(c, 0, 5)
        assertEquals(5, tuned.decode); assertEquals(6, tuned.prefill); assertTrue(tuned.note.contains("measured"))
        assertEquals(3, ThreadPlan.choose(c, 3, 5).decode, "the player's choice wins")
        assertEquals(4, ThreadPlan.choose(c, 0, 0).decode)
    }

    @Test fun theSpeedTestTurnsTheTwoSpeedsIntoTheLineTimeAgainstTheFiveSecondTarget() {
        // the arithmetic the README states: 80 new tokens read, then 34 written
        val fast = SpeedTarget.estimate(prefillTokensPerSecond = 100.0, decodeTokensPerSecond = 14.0)!!
        assertEquals(0.25 + 0.8 + 34 / 14.0, fast.lineSeconds, 1e-9)
        assertEquals(0.25 + 0.8 + 1 / 14.0, fast.firstWordSeconds, 1e-9)
        assertEquals(3.5, fast.getReadySeconds, 1e-9)
        assertTrue(fast.passes && fast.firstWordSeconds < SpeedTarget.FIRST_WORD_SECONDS)
        assertTrue(fast.text().startsWith("Estimated JOI line time: 3.5 s (within the 5 s target)"), fast.text())
        // the 12B model on a good phone: 25 tok/s reading, 5 tok/s writing
        val big = SpeedTarget.estimate(25.0, 5.0)!!
        assertFalse(big.passes); assertTrue(big.text().contains("over the 5 s target"))
        assertNull(SpeedTarget.estimate(0.0, 10.0)); assertNull(SpeedTarget.estimate(80.0, 0.0))
        val r = SpeedResult(1.0, 14.0, 64, 100.0, null, fast)
        assertTrue(r.text().contains("Estimated JOI line time"))
        assertFalse(SpeedResult(1.0, 14.0, 64, 0.0, null).text().contains("Estimated"))
    }

    // ---- timings and the slow-reply report ----------------------------------------------------------------------------

    private fun slow(read: Int, prefillMs: Double, generated: Int, decodeMs: Double, queue: Long = 200, total: Long = 0, firstToken: Double = prefillMs + 100) =
        ReplyTiming(atMs = 1, npcId = "joi:velira:hot", tag = "joi", priority = DialoguePriority.SELECTED, queueWaitMs = queue, firstWordMs = (queue + firstToken).toLong(),
            totalMs = if (total > 0) total else (queue + firstToken + decodeMs + 50).toLong(), finish = DialogueFinish.EOS, promptTokens = read + 300, readTokens = read, reusedTokens = 300,
            prefillMs = prefillMs, generated = generated, decodeMs = decodeMs, firstTokenMs = firstToken, kvTokens = read + 300, threads = 4, threadsBatch = 6)

    @Test fun theFourPhasesAddUpToTheTotal() {
        val t = slow(800, 38_000.0, 30, 6_000.0)
        val p = t.phases
        assertEquals(200.0, p.queueMs); assertEquals(38_000.0, p.prefillMs); assertEquals(100.0, p.firstTokenExtraMs); assertEquals(6_000.0, p.decodeMs)
        assertEquals(t.totalMs.toDouble(), p.queueMs + p.prefillMs + p.firstTokenExtraMs + p.decodeMs + p.otherMs, 1.0)
        assertTrue(p.text().startsWith("queue 200 ms + prefill 38000 ms"))
        assertTrue(t.diagnosis().contains("reading the prompt") && t.diagnosis().contains("800 tokens read at 21 tok/s"), t.diagnosis())
        val w = slow(20, 300.0, 40, 9_000.0)
        assertTrue(w.diagnosis().contains("writing") && w.diagnosis().contains("40 tokens"), w.diagnosis())
    }

    @Test fun theCopiedTimingsCarryEachRequestsPhases() {
        val log = TimingLog(); log.add(slow(60, 700.0, 30, 2_500.0))
        val text = log.toText("model: x")
        assertTrue(text.startsWith("model: x\nrequests: 1\n")); assertTrue(text.contains("\tphases: queue 200 ms + prefill 700 ms"), text); assertEquals(3, text.lines().filter { it.isNotBlank() }.size, "still a line per request")
    }

    @Test fun theSlowReportListsOnlyTheSlowRepliesWithTheirCauseAndNothingSaid() {
        val log = TimingLog()
        log.add(slow(60, 700.0, 30, 2_500.0))                    // fast: 3.5 s
        log.add(slow(800, 38_000.0, 30, 6_000.0))                // slow: reading
        log.add(slow(20, 300.0, 120, 30_000.0))                  // slow: writing
        log.add(ReplyTiming(atMs = 2, npcId = "joi:velira:hot", tag = "warm", priority = DialoguePriority.SELECTED, queueWaitMs = 0, firstWordMs = 0, totalMs = 4_000, finish = DialogueFinish.EOS, prefillOnly = true, readTokens = 350, prefillMs = 3_800.0))
        val r = log.slowReport("Wifecraft dialogue timings\nmodel: gemmasutra-mini-2b-v1-q4km\ncpu: 8 cores; features: asimddp i8mm\nprompts: compact")
        assertTrue(r.startsWith("Wifecraft dialogue timings\nmodel: gemmasutra-mini-2b-v1-q4km\ncpu: 8 cores; features: asimddp i8mm"))
        assertTrue(r.contains("replies: 3, slower than 5 s: 2"), r)
        assertEquals(2, Regex("went to ").findAll(r).count(), r)
        assertTrue(r.contains("reading the prompt") && r.contains("writing"))
        assertTrue(r.contains("warm-ups: 1, median 4.0 s"), r)
        assertTrue(r.contains("prompt 1100 (read 800, hit 300"), "the prompt token counts are in it")
        assertEquals(TimingLog.SLOW_MS, 5000L)
    }

    @Test fun anEmptyLogReportsCleanly() {
        val r = TimingLog().slowReport("h")
        assertTrue(r.contains("replies: 0, slower than 5 s: 0"))
    }
}
