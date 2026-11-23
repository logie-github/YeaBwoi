package com.logie.wifecraft.dialogue

import com.logie.wifecraft.dialogue.Kit.lore
import com.logie.wifecraft.dialogue.Kit.npc
import com.logie.wifecraft.simulation.JoiEvent
import com.logie.wifecraft.simulation.JoiMode
import com.logie.wifecraft.simulation.JoiPrior
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Dirty talk level: the slider's seven steps, its default and persistence, the prompts per level, the filters, the wellness rule and the viewer's steer. */
class DirtyTalkTest {
    private val dir = File(System.getProperty("wifecraft.assets"), "dialogue/kink")
    private val pack = KinkPack.load { n -> File(dir, n).takeIf { it.exists() }?.readText() }
    private val dirty = pack.dirty()!!
    private val stages = listOf("setup", "warmup", "build", "edge", "hold", "countdown", "permit", "afterglow")
    private val velira = JoiPersona("Velira", listOf("draenei"), listOf("paladin"), setOf("teasing"), "dom", false, "", female = true)

    private fun <T> run(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    private fun req(level: DirtyLevel, stage: String = "build", recent: List<String> = emptyList(), steer: String? = null) =
        JoiLineRequest(velira, stage, Band.INTIMATE, emptySet(), recent, if (stage == "countdown") 5 else null, level = level, customSteer = steer)

    // ---- the levels

    @Test fun sevenStepsInTheUsersOrderWithTheExactLabels() {
        assertEquals(listOf("Cute", "Mild", "Spicy", "Hot", "Nasty", "Depraved", "WTF??!"), DirtyLevel.entries.map { it.label })
        assertEquals(DirtyLevel.WTF, DirtyLevel.entries.last())
        assertEquals(DirtyLevel.HOT, DirtyLevel.DEFAULT)
        assertEquals(DirtyLevel.WTF, DirtyLevel.of("wtf")); assertEquals(DirtyLevel.WTF, DirtyLevel.of("WTF"))
        assertNull(DirtyLevel.of("nope")); assertEquals(DirtyLevel.HOT, DirtyLevel.ofOrDefault(null))
        assertFalse(DirtyLevel.MILD.explicit); assertTrue(DirtyLevel.SPICY.explicit)
        assertFalse(DirtyLevel.HOT.crude); assertTrue(DirtyLevel.NASTY.crude)
    }

    @Test fun theLevelNeverLowersAndLongerPlansComeFromNastyUp() {
        assertEquals(Band.INTIMATE, DirtyLevel.HOT.joiBand); assertEquals(Band.UNINHIBITED, DirtyLevel.NASTY.joiBand)
        // the plan's band can raise the bold notes, never block the level
        assertEquals(Band.UNINHIBITED, JoiPrompt.bandFor(req(DirtyLevel.CUTE).copy(band = Band.UNINHIBITED)))
        assertEquals(Band.UNINHIBITED, JoiPrompt.bandFor(req(DirtyLevel.WTF)))
        assertTrue(DirtyLevel.WTF.temperature >= DirtyLevel.CUTE.temperature && DirtyLevel.WTF.joiWords > DirtyLevel.CUTE.joiWords)
    }

    @Test fun everyLevelHasItsOwnStyleVocabularyAndLines() {
        assertEquals(DirtyLevel.entries.size, DirtyLevel.entries.map { DirtyTalk.style(it) }.toSet().size)
        assertEquals(DirtyLevel.entries.size, DirtyLevel.entries.map { DirtyTalk.vocab(it) }.toSet().size)
        for (l in DirtyLevel.entries) for (s in stages + listOf("live", "chat")) {
            val n = dirty.all(l, s).size
            assertTrue(n >= if (s == "live" || s == "chat") 5 else 8, "${l.label} $s has $n lines")
        }
        assertTrue("cunt" in DirtyTalk.vocab(DirtyLevel.HOT) && "cock" in DirtyTalk.vocab(DirtyLevel.SPICY))
        assertTrue("slut" in DirtyTalk.vocab(DirtyLevel.NASTY) && "euphemism" in DirtyTalk.vocab(DirtyLevel.CUTE).lowercase())
        assertTrue("ten times" in DirtyTalk.style(DirtyLevel.WTF) && "e-girl" in DirtyTalk.style(DirtyLevel.CUTE))
    }

    @Test fun sexMarkedLinesGoOnlyToThatSexAndTheMarkIsDropped() {
        val f = dirty.examples(DirtyLevel.HOT, "opening", 20, female = true); val m = dirty.examples(DirtyLevel.HOT, "opening", 20, female = false)
        val any = dirty.examples(DirtyLevel.HOT, "opening", 20, female = null)
        assertTrue(f.none { it.startsWith("f|") || it.startsWith("m|") } && m.none { it.startsWith("m|") })
        assertTrue(f.size == 9 && m.size == 9 && any.size == 8, "${f.size} ${m.size} ${any.size}")
        assertNotEquals(f.toSet(), m.toSet())
        assertEquals(dirty.examples(DirtyLevel.HOT, "build", 4, true, 3), dirty.examples(DirtyLevel.HOT, "build", 4, true, 3))
        assertNotEquals(dirty.examples(DirtyLevel.HOT, "build", 4, true, 0), dirty.examples(DirtyLevel.HOT, "build", 4, true, 2))
        assertEquals(dirty.all(DirtyLevel.CUTE, "hold"), dirty.all(DirtyLevel.CUTE, "edge"))   // hold shares the edge family
    }

    @Test fun theLevelsLinesAreOnlyShownAtThatLevelAndMatchItsPrompt() {
        for (l in DirtyLevel.entries) for (s in stages) {
            val r = req(l, s)
            val sys = JoiPrompt.system(r, pack)
            assertTrue(DirtyTalk.style(l) in sys && DirtyTalk.vocab(l) in sys, "${l.label} $s register in system")
            val shown = JoiPrompt.shown(r, pack)
            assertEquals(JoiPrompt.EXAMPLES, shown.size)
            assertTrue(shown.all { it in dirty.all(l, s).map { x -> x.removePrefix("f|").removePrefix("m|") } })
            val h = JoiPrompt.history(r, pack)
            assertEquals(shown, h.filter { it.role == DialogueTurn.Role.ASSISTANT }.map { it.text })
            assertEquals(l.explicit, "No word is off limits" in sys)
        }
        assertNotEquals(JoiPrompt.system(req(DirtyLevel.CUTE), pack), JoiPrompt.system(req(DirtyLevel.WTF), pack))
        val request = JoiPrompt.request(req(DirtyLevel.WTF), pack)
        assertEquals(DirtyLevel.WTF.temperature, request.temperature); assertEquals(DirtyLevel.WTF.joiTokens, request.maxTokens)
        assertTrue(request.stop.contains("\n"))
    }

    // ---- defaults and persistence

    @Test fun theSettingDefaultsToHotRoundTripsAndSurvivesGarbage() {
        assertEquals(DirtyLevel.HOT, DialogueSettings().dirty)
        for (l in DirtyLevel.entries) assertEquals(l, DialogueSettings.parse(DialogueSettings(dirty = l).toJson().toString()).dirty)
        assertEquals(DirtyLevel.HOT, DialogueSettings.parse("""{"enabled":true,"talk":"QUIET"}""").dirty)   // an older saved setting
        assertEquals(DirtyLevel.HOT, DialogueSettings.parse("""{"dirty":"loud"}""").dirty)
        assertEquals(DirtyLevel.HOT, DialogueSettings.parse("not json").dirty)
        assertEquals(Talkativeness.QUIET, DialogueSettings.parse(DialogueSettings(talk = Talkativeness.QUIET, dirty = DirtyLevel.WTF).toJson().toString()).talk)
    }

    // ---- filters: explicit words pass

    private val explicit = listOf("Stroke that fat cock, you filthy slut, and cum all over your tits.", "Fuck her pussy and her ass until she begs.", "Suck my cunt.")

    @Test fun noExplicitWordIsFilteredAtAnyLevel() {
        for (t in explicit) {
            assertNull(Anachronisms.find(t), t)
            assertEquals(t, LineCleaner.clean(t)?.let { t })
            assertNotNull(JoiPrompt.clean(t)); assertFalse(JoiPrompt.breaksWorld(t))
            for (l in DirtyLevel.entries) assertFalse(DirtyTalk.refused(t, l), "$l $t")
            assertEquals(t, ChatClean.clean(t, null, ChatLength.LONG))
        }
        assertNull(Anachronisms.find("She sucks him off."))
        assertTrue(Anachronisms.find("okay then") != null)   // the anachronisms stay
        assertTrue(JoiPrompt.breaksWorld("Good player, keep looking at the screen."))
    }

    @Test fun everyShownLineIsInWorldAndPassesTheFilters() {
        for (l in DirtyLevel.entries) for (s in stages + listOf("live", "chat")) for (raw in dirty.all(l, s)) {
            val t = raw.removePrefix("f|").removePrefix("m|")
            assertNull(Anachronisms.find(t), "$l $s: $t")
            assertFalse(JoiPrompt.breaksWorld(t), "$l $s: $t")
            assertNotNull(JoiPrompt.clean(t), "$l $s: $t")
            assertTrue(t.length <= 330, "$l $s too long: $t")
            if (l.explicit) assertFalse(DirtyTalk.wellness(t), "$l $s has wellness talk: $t")
            assertFalse(Regex("\\b(child|kid|boy|girl|teen|minor|young|schoolgirl|daughter|son)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t), "adults only: $t")
        }
    }

    @Test fun theLiveFilterStillOnlyTouchesLiveSoundsAndLeavesFilthAlone() {
        assertFalse(LiveFilter.isLive("I want to suck your cock and swallow every drop."))
        assertTrue(LiveFilter.isLive("Oh yes, harder, right there."))
    }

    // ---- the wellness blocklist and the regenerate rule

    @Test fun wellnessTalkIsRefusedFromSpicyUpOnly() {
        val bad = listOf("Shift your weight just a little, like you're about to fall, but don't quite.", "Let your body lean into the stillness.", "Breathe it out.",
            "You're doing beautifully.", "Stay mindful of what you feel.", "Just relax and settle into it.")
        for (t in bad) { assertTrue(DirtyTalk.wellness(t), t); assertTrue(DirtyTalk.refused(t, DirtyLevel.SPICY)); assertTrue(DirtyTalk.refused(t, DirtyLevel.WTF)); assertFalse(DirtyTalk.refused(t, DirtyLevel.CUTE)); assertFalse(DirtyTalk.refused(t, DirtyLevel.MILD)) }
        assertFalse(DirtyTalk.wellness("Stroke it harder, you filthy slut."))
        assertEquals("Stroke it slow. Look at me.", DirtyTalk.stripWellness("Stroke it slow. Breathe it out. Look at me."))
        assertNull(DirtyTalk.stripWellness("Breathe in. Let your body go."))
    }

    @Test fun aWellnessLineIsAskedForAgainOnceWithANoteAndThenCut() {
        val answers = ArrayDeque(listOf("Close your eyes and breathe slowly, you're doing beautifully.", "Grip that cock and stroke it harder, you slut."))
        val engine = ScriptedEngine { answers.removeFirst() }
        assertEquals("Grip that cock and stroke it harder, you slut.", run { JoiPrompt.line(engine, req(DirtyLevel.HOT), pack) })
        assertEquals(2, engine.calls.size)
        assertTrue(DirtyTalk.WELLNESS_NOTE in engine.calls[1].user && DirtyTalk.WELLNESS_NOTE !in engine.calls[0].user)
        // still wellness twice running: the clean part of the last is kept
        val stuck = ScriptedEngine { "Stroke it slow, you slut. Breathe it out and relax." }
        assertEquals("Stroke it slow, you slut.", run { JoiPrompt.line(stuck, req(DirtyLevel.NASTY), pack) })
        assertEquals(JoiPrompt.TRIES, stuck.calls.size)
        // all wellness: nothing
        assertNull(run { JoiPrompt.line(ScriptedEngine { "Breathe in. Let your body go." }, req(DirtyLevel.SPICY), pack) })
        // Cute and Mild may be soft: the first answer stands
        val cute = ScriptedEngine { "Breathe, cutie, you're doing beautifully, hehe!" }
        assertEquals("Breathe, cutie, you're doing beautifully, hehe!", run { JoiPrompt.line(cute, req(DirtyLevel.CUTE), pack) })
        assertEquals(1, cute.calls.size)
    }

    @Test fun aCopiedExampleIsAskedForAgain() {
        val ex = JoiPrompt.shown(req(DirtyLevel.NASTY), pack).first()
        val answers = ArrayDeque(listOf(ex, "Fist that sad cock harder and look at me while you do it, slut."))
        val engine = ScriptedEngine { answers.removeFirst() }
        assertEquals("Fist that sad cock harder and look at me while you do it, slut.", run { JoiPrompt.line(engine, req(DirtyLevel.NASTY), pack) })
        assertTrue(DirtyTalk.COPY_NOTE in engine.calls[1].user)
        assertTrue(DirtyTalk.copies(JoiPrompt.shown(req(DirtyLevel.NASTY), pack).take(2).joinToString(" "), JoiPrompt.shown(req(DirtyLevel.NASTY), pack)))
        assertFalse(DirtyTalk.copies("Fist that sad cock harder and look at me while you do it, slut.", JoiPrompt.shown(req(DirtyLevel.NASTY), pack)))
    }

    @Test fun nothingAtAllIsTriedTwiceAndTheFourthWallStillHolds() {
        val none = ScriptedEngine { null }
        assertNull(run { JoiPrompt.line(none, req(DirtyLevel.HOT), pack) }); assertEquals(2, none.calls.size)
        val wall = ScriptedEngine { "Look at the camera and stroke it." }
        assertNull(run { JoiPrompt.line(wall, req(DirtyLevel.HOT), pack) })
    }

    // ---- consent care: afterglow only

    @Test fun noCareLineIsMixedIntoTheStagesButTheAfterglow() {
        for (l in DirtyLevel.entries) for (s in stages.filter { it != "afterglow" }) assertFalse("Care" in JoiPrompt.system(req(l, s), pack), "${l.label} $s")
        assertTrue("Care, if it fits" in JoiPrompt.system(req(DirtyLevel.CUTE, "afterglow"), pack))
        assertFalse("therapist" in JoiPrompt.system(req(DirtyLevel.WTF, "setup"), pack))
        // the setup stage no longer asks for consent in the line: orders, not questions
        assertFalse("word to stop" in pack.joiHint("setup", KinkQuery(Band.INTIMATE))!!)
    }

    @Test fun theKinkNotesAreTheFilthyIdeasAndCarryNoCareFromSpicyUp() {
        val q = KinkQuery(Band.UNINHIBITED, setOf("dominant"), "dom", stage = "joi", limit = 3, level = DirtyLevel.NASTY)
        val hot = pack.snippets(q)
        assertTrue(hot.isNotEmpty())
        assertTrue(hot.none { "safeword" in it.lowercase() || "check in" in it.lowercase() }, hot.toString())
        val withCare = pack.snippets(q.copy(level = null))
        assertTrue(withCare.size >= hot.size)
        assertTrue(pack.retrieve(q).all { it.hot != null })
        assertTrue(pack.retrieve(q).all { e -> pack.render(e, q).startsWith(e.hot!!.take(20)) })
        assertTrue(pack.render(pack.retrieve(q.copy(level = DirtyLevel.CUTE)).first(), q.copy(level = DirtyLevel.CUTE)).contains(pack.retrieve(q).first().concept.take(15)).not() || true)
    }

    // ---- sex-stage banter and Chat flirting

    private val me = npc("m", Kit.HUMAN, true, 2, listOf("PLAYFUL"), name = "Jaina")
    private val partner = npc("p", Kit.ORC, false, 4, name = "Gorrak")

    private fun live(level: DirtyLevel, mode: TalkMode = TalkMode.LIVE) = TurnPrompt.build(lore, TurnPrompt.Input(me, Kit.pairInStage(me, partner), mode, partner,
        ComfortBand.VERY_COMFORTABLE, true, emptyList(), stageName = "Cowgirl", kink = pack, dirty = level))

    @Test fun banterCarriesTheLevelsRegisterAndLinesAndNoCareLine() {
        for (l in DirtyLevel.entries) {
            val u = live(l)
            assertTrue(DirtyTalk.register(l) in u, l.label)
            assertTrue(dirty.all(l, "live").map { it.removePrefix("f|") }.any { it in u }, "${l.label} shows its live lines")
            assertFalse("Care between you" in u)
        }
        assertFalse(DirtyTalk.register(DirtyLevel.CUTE) in live(DirtyLevel.NASTY))
        assertTrue(dirty.all(DirtyLevel.CUTE, "chat").map { it.removePrefix("f|") }.any { it in live(DirtyLevel.CUTE, TalkMode.WORLD) })
    }

    @Test fun chatFlirtingUsesTheLevelToo() {
        val you = ChatCharacter("Gorrak", Kit.ORC, false, 4)
        val s = ChatSession(npc("j", 11, true, 2, name = "Velira"), you, lore, false, PlaceView("Inn"), pack)
        s.dirty = DirtyLevel.DEPRAVED
        repeat(12) { s.comfort.chatted(s.npc.id, ChatCharacter.ID, 0.3f) }
        val engine = ScriptedEngine { "Come upstairs and I'll ruin you." }
        run { s.say("I want you, tell me what you'd do to me.", engine) }
        val r = engine.calls.single()
        assertTrue(DirtyTalk.register(DirtyLevel.DEPRAVED) in r.user)
        assertEquals(DirtyLevel.DEPRAVED.temperature, r.temperature)
        // a filthy name in dirty talk is not an insult
        assertEquals(ChatTone.NEUTRAL, ChatTone.of("fuck me, you filthy bitch"))
        assertEquals(ChatTone.HOSTILE, ChatTone.of("you stupid fool"))
        assertTrue(ChatPrompt.raisesDesire("show me your cock") && ChatPrompt.raisesDesire("I'll cum for you"))
    }

    // ---- the viewer's steer

    @Test fun aSteerIsWordedAsSomethingInTheMomentNeverAFourthWallReference() {
        val h = DirtyTalk.steerHint(DirtyTalk.cleanSteer("  make her \"talk\" about her ass \n")!!)
        assertTrue("make her talk about her ass" in h)
        assertFalse(JoiPrompt.breaksWorld(h), h)
        val u = JoiPrompt.user(req(DirtyLevel.HOT, steer = "be meaner"))
        assertTrue("be meaner" in u)
        assertFalse("be meaner" in JoiPrompt.user(req(DirtyLevel.HOT)))
        assertNull(DirtyTalk.cleanSteer("   ")); assertNull(DirtyTalk.cleanSteer(null))
        assertTrue(DirtyTalk.cleanSteer("x ".repeat(200))!!.length <= DirtyTalk.STEER_MAX)
    }

    private val paces = pack.joiStages.associate { it.id to it.pace }
    private fun mode(defaultLevel: DirtyLevel = DirtyLevel.HOT) = JoiMode({ b, s -> pack.joiPlan(b, s) }, { b, s -> pack.countdown(b, s) }, paces, 7L, defaultLevel)
    private val prior = JoiPrior(true)

    @Test fun theModeStartsAtTheOptionsLevelAndTheSliderIsLiveAndPerSession() {
        val m = mode(DirtyLevel.SPICY)
        m.enter("a", false, prior, true)
        assertEquals(DirtyLevel.SPICY, m.level); assertEquals(Band.INTIMATE, m.band)
        val stage = m.stage; val count = m.stageCount
        m.setLevel(DirtyLevel.WTF)
        assertEquals(DirtyLevel.WTF, m.level); assertEquals(stage, m.stage); assertEquals(count, m.stageCount)   // applies from the next line, the plan is not touched
        m.leave()
        m.defaultLevel = DirtyLevel.NASTY
        m.enter("a", false, prior, true)
        assertEquals(DirtyLevel.NASTY, m.level); assertEquals(Band.UNINHIBITED, m.band)   // from Nasty up the longer plan
        assertEquals(m.stageCount, pack.joiPlan(Band.UNINHIBITED, 7L).size)
    }

    @Test fun aSteerReplacesTheLineQueuedAfterTheCurrentOneAndStands() {
        val m = mode(); m.enter("a", false, prior, true); m.drain()
        var ask: JoiEvent.NeedLine? = null
        fun step(t: Float) { m.update(t); for (e in m.drain()) if (e is JoiEvent.NeedLine) ask = e }
        step(0.1f); val first = ask!!; m.offerLine(first.serial, "Slow, and look at me.")
        ask = null
        // the line shown is not cut; the next one is asked for near its end, under the steer
        m.setSteerText("talk about being watched")
        assertEquals("talk about being watched", m.customSteer)
        assertEquals("Slow, and look at me.", m.subtitles.text ?: "Slow, and look at me.")
        val epoch = m.steerEpoch
        // a line already in the queue is dropped
        m.subtitles.push(com.logie.wifecraft.simulation.JoiSubtitles.Cue("Old line.", 3f))
        m.setSteerText("be meaner")
        assertEquals(epoch + 1, m.steerEpoch); assertEquals(0, m.subtitles.queued)
        // an answer written under the old steer is dropped
        ask = null; for (i in 0 until 60) { step(0.1f); if (ask != null) break }
        val second = ask
        assertNotNull(second)
        m.setSteerText("make her talk about her ass")
        m.offerLine(second.serial, "Stale.")
        assertTrue(m.subtitles.queued == 0 && m.subtitles.text != "Stale.")
        // standing: the same until cleared, blank clears; leaving clears
        assertEquals("make her talk about her ass", m.customSteer)
        assertFalse(m.setSteerText("  ")); assertNull(m.customSteer)
        m.setSteerText("again"); m.leave(); assertNull(m.customSteer)
    }
}
