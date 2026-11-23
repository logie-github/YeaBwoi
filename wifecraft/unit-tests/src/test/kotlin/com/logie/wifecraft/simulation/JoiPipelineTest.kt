package com.logie.wifecraft.simulation

import com.logie.wifecraft.dialogue.DialogueEngine
import com.logie.wifecraft.dialogue.DialogueRequest
import com.logie.wifecraft.dialogue.DialogueTurn
import com.logie.wifecraft.dialogue.DirtyLevel
import com.logie.wifecraft.dialogue.JoiPersona
import com.logie.wifecraft.dialogue.JoiPrompt
import com.logie.wifecraft.dialogue.KinkPack
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the screen's runner does with the engine: the warm-up before the first line, the words reaching the mode as they come, the lookahead and "Getting ready...". */
class JoiPipelineTest {
    private val dir = File(System.getProperty("wifecraft.assets"), "dialogue/kink")
    private val pack = KinkPack.load { n -> File(dir, n).takeIf { it.exists() }?.readText() }
    private val paces = pack.joiStages.associate { it.id to it.pace }
    private val prior = JoiPrior(cinematic = true)
    private val velira = JoiPersona("Velira", listOf("draenei"), listOf("paladin"), setOf("teasing"), "dom", false, "", female = true)

    private fun mode() = JoiMode({ b, s -> pack.joiPlan(b, s) }, { b, s -> pack.countdown(b, s) }, paces, 7L)

    private fun <T> run(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    /** An engine that logs warm-ups and requests in the order they arrive, writes each answer a word at a time and says what the mode shows after every word. */
    private class LoggingEngine(val compact: Boolean, private val answer: String = "Slow. Stroke it for me. Good.") : DialogueEngine {
        val log = ArrayList<String>()
        val warms = ArrayList<Triple<String, String, List<DialogueTurn>>>()
        val requests = ArrayList<DialogueRequest>()
        var progress = 1f
        var onWord: (() -> Unit)? = null
        override val compactPrompts: Boolean get() = compact
        override fun warmProgress(npcId: String): Float = progress
        override fun warm(npcId: String, system: String, urgent: Boolean, history: List<DialogueTurn>) { log += "warm $npcId"; warms += Triple(npcId, system, history) }
        override suspend fun complete(req: DialogueRequest): String? {
            log += "complete ${req.npcId}"; requests += req
            for (m in Regex("\\S+\\s*").findAll(answer)) { req.onPartial?.invoke(m.value); onWord?.invoke() }
            return answer
        }
    }

    @Test fun theFirstLineIsAskedForAfterThePersonaWasHandedToTheEngineToRead() {
        val m = mode(); val eng = LoggingEngine(compact = true)
        val d = JoiDriver(m, pack)
        m.enter("velira", false, prior, true)
        d.warmUp(eng, velira)
        m.update(0.1f)
        val ask = m.drain().filterIsInstance<JoiEvent.NeedLine>().single()
        run { d.fetch(eng, velira, ask) }
        assertEquals(listOf("warm joi:velira:hot", "complete joi:velira:hot"), eng.log)
        // the warm-up reads exactly what the request sends first: the system prompt and the examples, byte for byte
        val (_, system, history) = eng.warms.single()
        assertEquals(eng.requests.single().system, system)
        assertEquals(eng.requests.single().history, history)
        assertEquals(6, history.size, "three examples: six turns")
    }

    @Test fun theCompactPrefixIsWarmedOnceAndTheSameForTheNextStages() {
        val m = mode(); val eng = LoggingEngine(compact = true)
        val d = JoiDriver(m, pack)
        m.enter("velira", false, prior, true)
        d.warmUp(eng, velira)
        val first = eng.warms.single()
        // a stage later the engine would be asked for the same system and examples: the persona read for the first line serves them all
        var t = 0f; var lines = 0
        while (m.active && t < 600f && lines < 12) {
            m.update(0.1f); t += 0.1f
            for (e in m.drain()) if (e is JoiEvent.NeedLine) { run { d.fetch(eng, velira, e) }; lines++ }
        }
        assertTrue(lines >= 3, "lines $lines")
        assertEquals(1, eng.requests.map { it.system }.toSet().size, "one system prompt through every stage"); assertEquals(first.second, eng.requests.first().system)
        assertEquals(setOf(first.third), eng.requests.map { it.history }.toSet(), "one set of examples through every stage")
        assertEquals(setOf("joi:velira:hot"), eng.requests.map { it.npcId }.toSet())
        assertTrue(eng.requests.map { it.user }.toSet().size >= 3, "what changes goes in the last message: ${eng.requests.map { it.user }.toSet().size}")
    }

    @Test fun aNewLevelIsAnotherPlaceAndIsWarmedAtOnce() {
        val m = mode(); val eng = LoggingEngine(compact = true)
        val d = JoiDriver(m, pack)
        m.enter("velira", false, prior, true)
        d.tickReadiness(eng, velira)
        assertEquals(listOf("warm joi:velira:hot"), eng.log)
        d.tickReadiness(eng, velira)
        assertEquals(1, eng.warms.size, "the same level is not warmed again by the driver")
        m.setLevel(DirtyLevel.NASTY)
        d.tickReadiness(eng, velira)
        assertEquals(listOf("warm joi:velira:hot", "warm joi:velira:nasty"), eng.log)
        assertTrue(eng.warms[0].second != eng.warms[1].second)
    }

    @Test fun theWordsReachTheModeAsTheyAreWrittenAndTheLineReplacesThem() {
        val m = mode(); val eng = LoggingEngine(compact = true, answer = "Slow down for me. Good. Now squeeze.")
        val d = JoiDriver(m, pack)
        m.enter("velira", false, prior, true)
        m.update(0.1f)
        val ask = m.drain().filterIsInstance<JoiEvent.NeedLine>().single()
        val seen = ArrayList<String?>()
        eng.onWord = { seen += m.visibleText }
        run { d.fetch(eng, velira, ask) }
        // the first word is on screen before the line is whole: each word adds to what is shown
        assertEquals("Slow", seen.first()?.trim())
        assertTrue(seen.size >= 5)
        for (i in 1 until seen.size) assertTrue(seen[i]!!.length >= seen[i - 1]!!.length && seen[i]!!.startsWith(seen[i - 1]!!.trim()), "$i: ${seen[i - 1]} -> ${seen[i]}")
        // the finished line is a subtitle and the partial is gone
        assertEquals("", m.partial)
        m.update(0.1f)
        assertEquals("Slow down for me. Good. Now squeeze.", m.visibleText)
        assertEquals(listOf("Slow down for me. Good. Now squeeze."), run { d.recent.toList() })
    }

    @Test fun aSubtitleOnShowIsNeverReplacedByTheNextLinesWords() {
        val m = mode()
        m.enter("velira", false, prior, true)
        m.update(0.1f)
        val first = m.drain().filterIsInstance<JoiEvent.NeedLine>().single()
        m.offerLine(first.serial, "First line, shown.")
        m.update(0.1f)
        assertEquals("First line, shown.", m.visibleText)
        m.lead = JoiMode.AHEAD
        m.update(0.1f)
        val second = m.drain().filterIsInstance<JoiEvent.NeedLine>().single()
        m.offerPartial(second.serial, "Next line comin")
        assertEquals("First line, shown.", m.visibleText, "the next line is written behind the one on show")
    }

    @Test fun withTheLookaheadTheNextLineIsAskedForWhileTheCurrentOneIsOnShow() {
        val m = mode(); m.lead = JoiMode.AHEAD
        m.enter("velira", false, prior, true)
        var t = 0f; val askedAt = ArrayList<Pair<Float, Float>>()   // (time, seconds the shown subtitle had left)
        var served = 0
        while (m.active && served < 4 && t < 300f) {
            m.update(0.1f); t += 0.1f
            for (e in m.drain()) if (e is JoiEvent.NeedLine) { askedAt += t to m.subtitles.remaining; m.offerLine(e.serial, "Stroke it slow, and keep your eyes on me."); served++ }
        }
        assertEquals(4, askedAt.size)
        // from the second ask on, a subtitle is on show with most of its time left (the old rule asked only in its last 1.6 s)
        for ((_, left) in askedAt.drop(1)) assertTrue(left > JoiMode.LEAD + 0.5f, "asked with $left s left")
    }

    @Test fun withoutTheLookaheadTheOldRuleStandsAndTheNextLineWaitsForTheLastSecondsOfTheCurrent() {
        val m = mode()
        m.enter("velira", false, prior, true)
        var t = 0f; var served = 0; val lefts = ArrayList<Float>()
        while (m.active && served < 3 && t < 300f) {
            m.update(0.1f); t += 0.1f
            for (e in m.drain()) if (e is JoiEvent.NeedLine) { lefts += m.subtitles.remaining; m.offerLine(e.serial, "Stroke it slow, and keep your eyes on me."); served++ }
        }
        for (l in lefts.drop(1)) assertTrue(l <= JoiMode.LEAD + 0.2f)
    }

    @Test fun whileHerPersonaIsStillBeingReadTheLayerSaysGettingReadyNotNothing() {
        val m = mode(); val eng = LoggingEngine(compact = true)
        val d = JoiDriver(m, pack)
        m.enter("velira", false, prior, true)
        eng.progress = 0.4f
        m.update(0.1f)
        val ask = m.drain().filterIsInstance<JoiEvent.NeedLine>().single()
        d.tickReadiness(eng, velira)
        assertEquals(0.4f, m.readiness, 0.001f)
        assertTrue(m.gettingReady, "awaiting the first line with nothing on screen and the persona not read yet")
        assertNull(m.visibleText)
        // the first words arrive: the bar gives way to them
        m.offerPartial(ask.serial, "Slow")
        assertFalse(m.gettingReady); assertEquals("Slow", m.visibleText)
        eng.progress = 1f
        d.tickReadiness(eng, velira)
        assertFalse(m.gettingReady)
    }

    @Test fun aReadPersonaShowsNoBarAndTheFirstLineIsNotBlocked() {
        val m = mode(); val eng = LoggingEngine(compact = true)
        val d = JoiDriver(m, pack)
        m.enter("velira", false, prior, true)
        d.warmUp(eng, velira)
        m.update(0.1f)
        val ask = m.drain().filterIsInstance<JoiEvent.NeedLine>().single()   // asked in the very first step: nothing waited for the warm-up
        d.tickReadiness(eng, velira)
        assertFalse(m.gettingReady)
        run { d.fetch(eng, velira, ask) }
        assertEquals(1, eng.requests.size)
    }

    @Test fun aStageSkippedOrASteerTypedMeanwhileDropsTheLateWords() {
        val m = mode()
        m.enter("velira", false, prior, true)
        m.update(0.1f)
        val ask = m.drain().filterIsInstance<JoiEvent.NeedLine>().single()
        m.offerPartial(ask.serial, "Slo")
        assertEquals("Slo", m.partial)
        m.skip()
        assertEquals("", m.partial, "the skipped stage's words are gone")
        m.offerPartial(ask.serial, "Late words")
        assertEquals("", m.partial)
        m.offerLine(ask.serial, "Late line")
        assertTrue(m.subtitles.idle)
    }

    @Test fun leavingTheModeClearsWhatWasBeingWritten() {
        val m = mode()
        m.enter("velira", false, prior, true)
        m.update(0.1f)
        val ask = m.drain().filterIsInstance<JoiEvent.NeedLine>().single()
        m.offerPartial(ask.serial, "Hal")
        m.setReadiness(0.2f)
        m.leave()
        assertEquals("", m.partial); assertEquals(1f, m.readiness, 0.001f); assertNull(m.visibleText)
    }

    @Test fun aFullPromptEngineKeepsTheFullPromptAndItsOwnKey() {
        val m = mode(); val eng = LoggingEngine(compact = false)
        val d = JoiDriver(m, pack)
        m.enter("velira", false, prior, true)
        d.warmUp(eng, velira)
        val warm = eng.warms.single()
        assertEquals(JoiPrompt.EXAMPLES * 2, warm.third.size, "the big model keeps its eight examples")
        assertTrue(warm.second.contains("You are giving them a JOI"))
    }
}
