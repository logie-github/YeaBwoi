package com.logie.wifecraft.simulation

import com.logie.wifecraft.dialogue.Band
import com.logie.wifecraft.dialogue.DialogueRequest
import com.logie.wifecraft.dialogue.FakeDialogueEngine
import com.logie.wifecraft.dialogue.JoiLineRequest
import com.logie.wifecraft.dialogue.JoiPersona
import com.logie.wifecraft.dialogue.JoiPrompt
import com.logie.wifecraft.dialogue.JoiSteer
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

class JoiModeTest {
    private val dir = File(System.getProperty("wifecraft.assets"), "dialogue/kink")
    private val pack = KinkPack.load { n -> File(dir, n).takeIf { it.exists() }?.readText() }
    private val paces = pack.joiStages.associate { it.id to it.pace }

    private fun mode(seed: Long = 7L) = JoiMode({ b, s -> pack.joiPlan(b, s) }, { b, s -> pack.countdown(b, s) }, paces, seed)
    private val prior = JoiPrior(cinematic = true, cameraMode = "ORBIT", cameraView = 2)

    /** Runs a suspend block to completion (the fake engine never suspends). */
    private fun <T> run(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    /** Plays the whole mode with an engine that answers [say] and returns the events in order. */
    private fun playAll(m: JoiMode, limitSeconds: Float = 2000f, say: (JoiEvent.NeedLine) -> String?): List<JoiEvent> {
        val all = ArrayList<JoiEvent>()
        var t = 0f
        while (m.active && t < limitSeconds) {
            m.update(0.1f); t += 0.1f
            for (e in m.drain()) {
                all += e
                if (e is JoiEvent.NeedLine) m.offerLine(e.serial, say(e))
            }
        }
        return all
    }

    @Test fun entersWithTheModelAndStartsAtSetup() {
        val m = mode()
        assertFalse(m.on)
        m.enter("npc1", false, prior, modelReady = true)
        assertEquals(JoiPhase.RUNNING, m.phase)
        assertEquals("setup", m.stage)
        assertEquals(m.stageCount, pack.joiPlan(Band.INTIMATE, 7L).size)
        assertTrue(m.drain().any { it is JoiEvent.StageStarted && it.stage == "setup" })
    }

    @Test fun withoutAModelTheModeWaitsAndSaysSo() {
        val m = mode()
        m.enter("npc1", false, prior, modelReady = false)
        assertEquals(JoiPhase.NEEDS_MODEL, m.phase)
        m.update(5f)
        assertTrue(m.drain().isEmpty(), "no line is asked for without a model")
        assertTrue(m.subtitles.idle)
        m.modelReady()
        assertEquals(JoiPhase.RUNNING, m.phase)
        assertEquals("setup", m.stage)
    }

    @Test fun leavingGivesBackWhatWasOn() {
        val m = mode()
        m.enter("npc1", false, prior, true)
        assertEquals(prior, m.leave())
        assertFalse(m.on)
        assertNull(m.stage)
    }

    @Test fun theStagesRunInThePlansOrderAndFinish() {
        val m = mode()
        m.enter("npc1", false, prior, true)
        val ev = playAll(m) { "Slower now." }
        val order = ev.filterIsInstance<JoiEvent.StageStarted>().map { it.stage }
        val plan = pack.joiPlan(Band.INTIMATE, 7L)
        assertEquals(plan, order)
        assertTrue(ev.last() is JoiEvent.Finished)
        assertEquals(plan.first(), "setup")
        assertEquals("afterglow", plan.last())
    }

    @Test fun everyStageGetsLines() {
        val m = mode()
        m.enter("npc1", false, prior, true)
        val ev = playAll(m) { "Keep going, nice and slow, and look at me." }
        val asked = ev.filterIsInstance<JoiEvent.NeedLine>().map { it.stage }.toSet()
        for (s in setOf("setup", "warmup", "build", "edge", "countdown", "permit", "afterglow")) assertTrue(s in asked, "no line for $s")
    }

    @Test fun theCountdownSpeaksEveryNumberDownToOneAtItsBeat() {
        val m = mode()
        m.enter("npc1", false, prior, true)
        val (from, ms) = pack.countdown(Band.INTIMATE, 7L)
        val ev = playAll(m) { "Here we go." }
        val beats = ev.filterIsInstance<JoiEvent.CountdownBeat>().map { it.n }
        assertEquals((from downTo 1).toList(), beats)
        val ask = ev.filterIsInstance<JoiEvent.NeedLine>().first { it.stage == "countdown" }
        assertEquals(from, ask.countdownFrom)
        assertTrue(ms in 1500..3000)
    }

    @Test fun countdownBeatsAreSpacedByTheBeatLength() {
        val m = mode()
        m.enter("npc1", false, prior, true)
        val (_, ms) = pack.countdown(Band.INTIMATE, 7L)
        var t = 0f
        val at = ArrayList<Float>()
        while (m.active && t < 2000f) {
            m.update(0.05f); t += 0.05f
            for (e in m.drain()) {
                if (e is JoiEvent.NeedLine) m.offerLine(e.serial, "Slow.")
                if (e is JoiEvent.CountdownBeat) at += t
            }
        }
        for (i in 1 until at.size) assertEquals(ms / 1000f, at[i] - at[i - 1], 0.15f)
    }

    @Test fun subtitlePacingGrowsWithLengthAndStageAndIsBounded() {
        val short = JoiSubtitles.hold("Good.", "steady")
        val long = JoiSubtitles.hold("Slower now, keep your eyes on me and let your breath go long and low.", "steady")
        assertTrue(long > short)
        assertTrue(JoiSubtitles.hold("Slower now, easy.", "slow") > JoiSubtitles.hold("Slower now, easy.", "steady"))
        assertEquals(JoiSubtitles.MIN, JoiSubtitles.hold("Ok.", "steady"), 1e-6f)
        assertEquals(JoiSubtitles.MAX, JoiSubtitles.hold("word ".repeat(60), "slow"), 1e-6f)
        assertTrue(JoiSubtitles.gap("hold") > JoiSubtitles.gap("steady"))
    }

    @Test fun aSubtitleShowsForItsTimeThenTheNextAfterItsGap() {
        val s = JoiSubtitles()
        s.push(JoiSubtitles.Cue("one", 2f, gapAfter = 1f))
        s.push(JoiSubtitles.Cue("two", 2f))
        s.advance(0.1f)
        assertEquals("one", s.text)
        s.advance(2.0f)
        assertNull(s.text)
        s.advance(0.5f); assertNull(s.text)
        s.advance(0.6f); assertEquals("two", s.text)
    }

    @Test fun theNextLineIsAskedForBeforeTheShownOneEnds() {
        val m = mode()
        m.enter("npc1", false, prior, true)
        m.drain()
        m.update(0.1f)
        val first = m.drain().filterIsInstance<JoiEvent.NeedLine>().single()
        m.offerLine(first.serial, "Take your time, and tell me when you are ready.")
        var t = 0f
        var asked: JoiEvent.NeedLine? = null
        while (asked == null && t < 30f) {
            m.update(0.1f); t += 0.1f
            asked = m.drain().filterIsInstance<JoiEvent.NeedLine>().firstOrNull()
        }
        assertNotNull(asked)
        assertTrue(m.subtitles.remaining <= JoiMode.LEAD + 0.2f)
    }

    @Test fun skipDropsALateAnswerAndMovesOn() {
        val m = mode()
        m.enter("npc1", false, prior, true)
        m.update(0.1f)
        val ask = m.drain().filterIsInstance<JoiEvent.NeedLine>().single()
        m.skip()
        m.offerLine(ask.serial, "late")
        assertTrue(m.subtitles.idle)
        assertEquals("warmup", m.stage)
    }

    @Test fun pauseStopsTheClockAndResumeContinues() {
        val m = mode()
        m.enter("npc1", false, prior, true)
        m.update(0.1f); m.drain()
        m.pause()
        val c = m.stageClock
        repeat(50) { m.update(0.1f) }
        assertEquals(c, m.stageClock)
        assertTrue(m.drain().isEmpty())
        m.resume()
        m.update(0.1f)
        assertTrue(m.stageClock > c)
    }

    @Test fun threeMissedAnswersWaitForTheModel() {
        val m = mode()
        m.enter("npc1", false, prior, true)
        var lost = false
        var t = 0f
        while (!lost && t < 200f) {
            m.update(0.1f); t += 0.1f
            for (e in m.drain()) { if (e is JoiEvent.NeedLine) m.offerLine(e.serial, null); if (e is JoiEvent.ModelLost) lost = true }
        }
        assertTrue(lost)
        assertEquals(JoiPhase.NEEDS_MODEL, m.phase)
    }

    @Test fun theBandChangesThePlanAndKeepsTheStage() {
        val m = mode()
        m.enter("npc1", false, prior, true)
        repeat(2) { m.skip() }
        assertEquals("build", m.stage)
        m.setBand(Band.UNINHIBITED)
        assertEquals(Band.UNINHIBITED, m.band)
        assertEquals("build", m.stage)
        assertEquals(pack.joiPlan(Band.UNINHIBITED, 7L).size, m.stageCount)
    }

    @Test fun belowIntimateIsNotOffered() {
        assertEquals(listOf(Band.INTIMATE, Band.UNINHIBITED), JoiMode.BANDS)
        val m = mode()
        m.enter("npc1", false, prior, true)
        m.setBand(Band.STRANGERS)
        assertEquals(Band.INTIMATE, m.band)
    }

    @Test fun animationsAreDueByTheStagesEnergyAndSteerChipsToggle() {
        val m = mode()
        m.enter("npc1", false, prior, true)
        val ev = playAll(m) { "Mm." }
        val due = ev.filterIsInstance<JoiEvent.AnimationDue>()
        assertTrue(due.size >= 4, "animations were due ${due.size} times")
        assertTrue(due.all { it.energy in 0f..1f })
        m.steer.clear(); m.toggleSteer(JoiSteer.TEASE); m.toggleSteer(JoiSteer.SOFT); m.toggleSteer(JoiSteer.TEASE)
        assertEquals(setOf(JoiSteer.SOFT), m.steer)
    }

    @Test fun theSameSeedGivesTheSameFilm() {
        fun film(): List<String> {
            val m = mode(99L); m.enter("a", false, prior, true)
            return playAll(m) { "Good." }.map { it.toString() }
        }
        assertEquals(film(), film())
    }

    // ---- the engine: no model, the fake, the prompt

    private val persona = JoiPersona("Anarcis", listOf("bloodelf"), listOf("warlock"), setOf("teasing"))

    @Test fun noModelAnswerGivesNoLineNotAHardCodedOne() {
        val engine = FakeDialogueEngine(ready = false)
        val r = JoiLineRequest(persona, "warmup", Band.INTIMATE)
        assertNull(run { JoiPrompt.line(engine, r, pack) })
        assertEquals(2, engine.requests.size)
    }

    @Test fun theFakeEnginesReplyBecomesTheLineCleaned() {
        val engine = FakeDialogueEngine(listOf("*smiles* \"Slower now. Good.\" (soft)"))
        val r = JoiLineRequest(persona, "build", Band.INTIMATE, setOf(JoiSteer.TEASE))
        assertEquals("Slower now. Good.", run { JoiPrompt.line(engine, r, pack) })
        val req = engine.requests.single()
        assertTrue("joi:build" == req.tag)
        assertTrue(JoiSteer.TEASE.instruction in req.system)
    }

    @Test fun aReplyThatBreaksTheWorldIsRefusedAndRetried() {
        val engine = FakeDialogueEngine(listOf("The player is watching the screen.", "Keep your eyes on me."))
        val r = JoiLineRequest(persona, "build", Band.INTIMATE)
        assertEquals("Keep your eyes on me.", run { JoiPrompt.line(engine, r, pack) })
    }

    @Test fun thePromptNeverNamesAPlayerOrAGameAndTheDragonIsDragonVoiced() {
        val human = JoiPrompt.system(JoiLineRequest(persona, "edge", Band.INTIMATE), pack)
        assertFalse(JoiPrompt.breaksWorld(human.substringBefore("Rules:")), "the persona text names the outside")
        val dragon = JoiPrompt.system(JoiLineRequest(JoiPersona("Sindragosa", dragon = true, personalities = setOf("dominant")), "edge", Band.UNINHIBITED), pack)
        assertTrue("frost dragon" in dragon && "imperious" in dragon)
        assertFalse("frost dragon" in human)
        assertTrue("you" in human.lowercase())
    }

    @Test fun cleanedLinesAreShortAndNeverRepeat() {
        assertNull(JoiPrompt.clean(null))
        assertNull(JoiPrompt.clean("   "))
        val long = JoiPrompt.clean("Good. ".repeat(80))!!
        assertTrue(long.length <= JoiPrompt.MAX_CHARS + 1)
        assertEquals("Slow.", JoiPrompt.clean("Anarcis: Slow.", "Anarcis"))
        val engine = FakeDialogueEngine(listOf("Again.", "Again.", "Again."))
        val r = JoiLineRequest(persona, "build", Band.INTIMATE, recent = listOf("Again."))
        assertNull(run { JoiPrompt.line(engine, r, pack) })
    }
}
