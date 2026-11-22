package com.logie.wifecraft.dialogue

import com.logie.wifecraft.dialogue.Kit.HUMAN
import com.logie.wifecraft.dialogue.Kit.UNDEAD
import com.logie.wifecraft.dialogue.Kit.lore
import com.logie.wifecraft.dialogue.Kit.npc
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DirectorTest {
    private val a = npc("a", HUMAN, true, 2, listOf("CALM"), name = "Jaina Proudmoore")
    private val b = npc("b", UNDEAD, false, 0, listOf("BRAVE"), name = "Varimathras")
    private fun director(engine: DialogueEngine?, said: Said, settings: DialogueSettings = DialogueSettings(), seed: Int = 3) =
        DialogueDirector(engine, lore, said.sink, settings, Random(seed))

    @Test fun pairsAlternateAAndBForAFewTurnsOnATopicSeed() {
        val said = Said(); val engine = ScriptedEngine { "Aye, the road was long." }
        val d = director(engine, said)
        Kit.run(d, Kit.pairInConversation(a, b), 25f)
        val who = said.lines.map { it.first }
        assertTrue(who.size in 2..6, "a few turns: $who")
        assertEquals("a", who.first(), "the one who came over speaks first")
        for (i in 1 until who.size) assertNotEquals(who[i - 1], who[i], "alternate: $who")
        // the first request carries a topic seed; the answers carry what was just heard
        assertTrue("A thing on your mind" in engine.calls.first().user)
        assertTrue(engine.calls.drop(1).all { "They just said" in it.user })
        assertTrue(engine.calls.all { "Reply with the spoken words only" in it.user })
    }

    @Test fun oneModelCallAtATimeAndNothingQueuesUp() {
        val said = Said(); val engine = ManualEngine(); val d = director(engine, said)
        val v = Kit.pairInConversation(a, b)
        Kit.run(d, v, 20f)
        assertEquals(1, engine.calls.size, "one call in flight; the director waits for it")
        assertEquals(1, engine.maxRunning)
        engine.finish("Well met."); Kit.run(d, v, 6f)
        assertEquals(listOf("a" to "Well met."), said.lines)
        assertEquals(2, engine.calls.size); assertEquals(1, engine.maxRunning)
    }

    @Test fun theSelectedAndTheNearestSpeakFirst() {
        val c = npc("c", HUMAN, false); val e = npc("e", HUMAN, true)
        val said = Said(); val d = director(ScriptedEngine { "Hm." }, said)
        val v = Kit.view(
            Kit.person(a, "talking", b.id, conversation = true, leads = true, dist = 18f), Kit.person(b, "talking", a.id, conversation = true, dist = 18f),
            Kit.person(c, "talking", e.id, conversation = true, leads = true, dist = 2f, selected = true), Kit.person(e, "talking", c.id, conversation = true, dist = 2f))
        Kit.run(d, v, 3f)
        assertEquals("c", said.lines.first().first)
    }

    @Test fun lengthIsKeptToAnOrTwoSentencesAndCutCleanly() {
        val said = Said(); val d = director(ScriptedEngine { "\"One. Two. Three. Four.\"" }, said)
        Kit.run(d, Kit.pairInConversation(a, b), 4f)
        assertEquals("One. Two.", said.lines.first().second)
    }

    @Test fun anachronismsAreRegeneratedOnceThenDropped() {
        var n = 0
        val said = Said(); val engine = ScriptedEngine { if (n++ == 0) "Okay, cool, guys." else "Aye, well met." }
        Kit.run(director(engine, said), Kit.pairInConversation(a, b), 4f)
        assertEquals("Aye, well met.", said.lines.first().second)
        assertTrue("do not use the word" in engine.calls[1].user)
        val said2 = Said(); val e2 = ScriptedEngine { "Okay, cool." }
        Kit.run(director(e2, said2), Kit.pairInConversation(a, b), 2f)
        assertTrue(said2.lines.isEmpty(), "still modern: nothing is said"); assertTrue(e2.calls.size >= 2 && e2.calls.size % 2 == 0, "each turn asked twice, no more")
    }

    @Test fun noModelMeansSilenceAndNoChange() {
        val said = Said(); val d = director(null, said)
        assertFalse(d.active)
        Kit.run(d, Kit.pairInConversation(a, b), 30f)
        assertTrue(said.lines.isEmpty())
        assertEquals(ConsentGate.PROCEED, d.consent("a", "b", true, "Passionate Missionary"))
        val off = director(ScriptedEngine { "x." }, said, DialogueSettings(enabled = false))
        assertFalse(off.active); Kit.run(off, Kit.pairInConversation(a, b), 10f); assertTrue(said.lines.isEmpty())
        assertFalse(director(ScriptedEngine { "Aye." }, said).takesOverChat("a", "b").not())
        assertFalse(off.takesOverChat("a", "b"))
    }

    @Test fun cinematicModeIdlesTheDirectorEntirely() {
        val said = Said(); val engine = ScriptedEngine { "Aye." }; val d = director(engine, said)
        Kit.run(d, Kit.pairInConversation(a, b, cinematic = true), 30f)
        Kit.run(d, Kit.pairInStage(a, b, cinematic = true), 30f)
        assertTrue(said.lines.isEmpty()); assertTrue(engine.calls.isEmpty(), "no model call either")
        // and a line that arrives after cinematic turned on is not shown
        val m = ManualEngine(); val d2 = director(m, said)
        Kit.run(d2, Kit.pairInConversation(a, b), 3f); assertEquals(1, m.calls.size)
        d2.update(0.25f, Kit.pairInConversation(a, b, cinematic = true)); m.finish("Aye."); d2.update(0.25f, Kit.pairInConversation(a, b, cinematic = true))
        assertTrue(said.lines.isEmpty())
        // consent is not asked in cinematic
        assertEquals(ConsentGate.PROCEED, d2.consent("a", "b", false, "Passionate Missionary"))
    }

    @Test fun liveVocalisationOnlyDuringASexualStage() {
        val said = Said(); val engine = ScriptedEngine { "Oh yeah, baby, harder, don't stop!" }
        Kit.run(director(engine, said), Kit.pairInConversation(a, b), 20f)
        assertTrue(said.lines.isEmpty(), "dropped outside a sexual stage: ${said.lines}")
        val live = Said(); val e2 = ScriptedEngine { "Oh yeah, baby, harder, don't stop!" }
        Kit.run(director(e2, live), Kit.pairInStage(a, b), 20f)
        assertTrue(live.lines.isNotEmpty() && live.lines.first().second.startsWith("Oh yeah"), "kept during one")
        // a hug is not a sexual stage
        val hug = Said(); Kit.run(director(ScriptedEngine { "Oh yeah, baby!" }, hug), Kit.pairInStage(a, b, sexual = false, name = "Hug"), 20f)
        assertTrue(hug.lines.isEmpty())
        // desire as a topic is allowed in ordinary talk
        val topic = Said(); Kit.run(director(ScriptedEngine { "I have wanted to feel your hands on me since the harvest." }, topic), Kit.pairInConversation(a, b), 6f)
        assertEquals("I have wanted to feel your hands on me since the harvest.", topic.lines.first().second)
    }

    @Test fun liveTurnsAlternateInTheStageAndUseTheLiveMode() {
        val said = Said(); val engine = ScriptedEngine { "Aye, there." }
        Kit.run(director(engine, said), Kit.pairInStage(a, b), 30f)
        val who = said.lines.map { it.first }
        assertTrue(who.size >= 3); for (i in 1 until who.size) assertNotEquals(who[i - 1], who[i])
        assertTrue(engine.calls.all { "middle of Passionate Missionary" in it.user })
    }

    @Test fun theRequestCarriesTheSceneAndTheOthersByNameWithTheirRace() {
        val c = npc("c", 10, false, 8, name = "Kael Sunstrider")
        val said = Said(); val engine = ScriptedEngine { "Hm." }
        val v = Kit.view(Kit.person(a, "talking", b.id, conversation = true, leads = true), Kit.person(b, "talking", a.id, conversation = true),
            Kit.person(c, "sitting on the couch", dist = 3f))
        Kit.run(director(engine, said), v, 3f)
        val u = engine.calls.first().user
        for (w in listOf("A House in Brill", "indoors", "night", "light rain", "a double bed", "Kael Sunstrider", "blood elf", "mage", "sitting on the couch", "Horde")) assertTrue(w in u, "'$w' in the prompt")
        assertTrue("How your kind looks on theirs" in u, "the stance of the speaker's people on the others")
        assertEquals(PersonaPrompt.system(a, lore), engine.calls.first().system)
    }

    @Test fun eventsAreNoticedAndAnNpcCanReferToOthersByName() {
        val c = npc("c", HUMAN, false, name = "Anduin Wrynn")
        val view = Kit.view(Kit.person(a), Kit.person(b), Kit.person(c))
        val ev = listOf(SceneEvent(SceneEvent.Kind.ARRIVED, "c"), SceneEvent(SceneEvent.Kind.STAGE_STARTED, "a", "b", "passionate missionary"))
        val s = SceneContext.events("c", ev, view)!!
        assertTrue("Jaina has just begun" !in s)
        assertTrue("Jaina and Varimathras have just begun passionate missionary" in s)
        assertFalse("Anduin" in s, "not about oneself")
        assertEquals(null, SceneContext.events("a", listOf(SceneEvent(SceneEvent.Kind.ARRIVED, "a")), view))
        assertTrue("Anduin Wrynn, a human (Alliance), standing about" in SceneContext.people("a", view, lore))
    }

    @Test fun historyIsShortInMemoryAndClearedWithTheScene() {
        val said = Said(); val engine = ScriptedEngine { "Aye." }; val d = director(engine, said)
        Kit.run(d, Kit.pairInConversation(a, b), 40f)
        assertTrue(d.historyOf("a").isNotEmpty() && d.historyOf("b").isNotEmpty())
        assertTrue(d.historyOf("a").size <= DialogueDirector.HISTORY)
        assertTrue(d.historyOf("b").first().role == DialogueTurn.Role.USER && d.historyOf("a").first().role == DialogueTurn.Role.ASSISTANT)
        assertTrue(engine.calls.last().history.isNotEmpty(), "the rolling history goes to the model")
        d.reset(); assertTrue(d.historyOf("a").isEmpty())
        val d2 = director(engine, Said()); assertTrue(d2.historyOf("a").isEmpty(), "nothing between sessions")
    }

    @Test fun talkativenessScalesHowOften() {
        fun lines(t: Talkativeness): Int { val said = Said(); val d = director(ScriptedEngine { "Aye." }, said, DialogueSettings(true, t))
            Kit.run(d, Kit.pairInStage(a, b), 120f); return said.lines.size }
        assertTrue(lines(Talkativeness.CHATTY) > lines(Talkativeness.QUIET))
    }

    @Test fun steersAreSoftHintsPerNpcPairOrScene() {
        val said = Said(); val engine = ScriptedEngine { "Hm." }; val d = director(engine, said)
        d.steers.addScene(Steer("the Scourge", SteerStrength.FIRM)); d.steers.addNpc("b", Steer("old grudges"))
        Kit.run(d, Kit.pairInConversation(a, b), 3f)
        val first = engine.calls.first().user   // a speaks
        assertTrue("A firm nudge" in first && "the Scourge" in first, first); assertFalse("old grudges" in first, "only b's own")
        d.steers.addPair("a", "b", Steer("gossip about Sylvanas")); engine.calls.clear()
        Kit.run(d, Kit.pairInConversation(a, b), 6f)
        val bs = engine.calls.last { it.npcId == "b" }.user
        assertTrue("A gentle nudge" in bs && "old grudges" in bs && "the Scourge" in bs && "gossip about Sylvanas" in bs, bs)
        d.steers.remove("the Scourge"); assertTrue(d.steers.hints("a", "b").none { "Scourge" in it.text })
        d.steers.clearAll(); assertTrue(d.steers.isEmpty); assertTrue(d.steers.hints("a", "b").isEmpty())
    }

    @Test fun everyLineIsTheModelsAndTheRequestIsStatedInTheNpcsOwnTerms() {
        val engine = ScriptedEngine { null }; val said = Said()
        Kit.run(director(engine, said), Kit.pairInConversation(a, b), 30f)
        assertTrue(said.lines.isEmpty(), "the model said nothing: nobody speaks, no canned line")
        val u = engine.calls.first().user.lowercase()
        for (w in FOURTH_WALL) assertFalse(w in u.replace("simulation", ""), "'$w' in a request")
    }
}
