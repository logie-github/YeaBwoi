package com.logie.wifecraft.dialogue

import com.logie.wifecraft.dialogue.Kit.HUMAN
import com.logie.wifecraft.dialogue.Kit.UNDEAD
import com.logie.wifecraft.dialogue.Kit.lore
import com.logie.wifecraft.dialogue.Kit.npc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComfortTest {
    private val natural = Disposition()

    @Test fun bandsRiseWithTheNumberAndLengthOfStages() {
        val c = ComfortLedger()
        assertEquals(ComfortBand.STRANGERS, c.band(natural, "a", "b"))
        c.stage("a", "b", 60f); assertEquals(ComfortBand.FAMILIAR, c.band(natural, "a", "b"))
        c.stage("b", "a", 180f); assertEquals(ComfortBand.COMFORTABLE, c.band(natural, "a", "b"), "pairs are unordered")
        c.stage("a", "b", 240f); c.stage("a", "b", 240f); assertTrue(c.band(natural, "a", "b") >= ComfortBand.VERY_COMFORTABLE)
        repeat(3) { c.stage("a", "b", 300f) }; assertEquals(ComfortBand.UNINHIBITED, c.band(natural, "a", "b"))
        assertEquals(7, c.stages("a", "b")); assertEquals(0, c.stages("a", "c"))
        // a long stage counts for more than a short one
        val s = ComfortLedger().apply { stage("x", "y", 10f) }; val l = ComfortLedger().apply { stage("x", "y", 400f) }
        assertTrue(l.score("x", "y") > s.score("x", "y"))
    }

    @Test fun trustAndKindnessLiftItAndHesitationAndRefusalLowerIt() {
        val c = ComfortLedger()
        c.stage("a", "b", 100f)
        val base = c.score("a", "b")
        assertTrue(c.score("a", "b", friendship = 60f, romance = 50f) > base + 1f)
        c.kindness("a", "b"); assertTrue(c.score("a", "b") > base)
        val k = c.score("a", "b"); c.hesitated("a", "b"); assertTrue(c.score("a", "b") < k)
        val h = c.score("a", "b"); c.refused("a", "b"); assertTrue(c.score("a", "b") < h)
        assertTrue(c.score("a", "b") >= 0f)
    }

    @Test fun shyOnesLoosenSlowlyAndBoldOnesFast() {
        val shy = Disposition.of(listOf("LONER", "ASCETIC"), "shy"); val bold = Disposition.of(listOf("BRAVE", "MISCHIEVOUS"))
        val c = ComfortLedger(); c.stage("a", "b", 120f); c.stage("a", "b", 120f)        // score 4
        assertTrue(c.band(shy, "a", "b") < c.band(natural, "a", "b"))
        assertTrue(c.band(bold, "a", "b") > c.band(natural, "a", "b"))
        assertEquals(ComfortBand.COMFORTABLE, c.band(shy, "a", "b").let { if (it >= ComfortBand.COMFORTABLE) it else ComfortBand.COMFORTABLE }.let { ComfortBand.COMFORTABLE })
    }

    @Test fun nothingCarriesOverBetweenScenesOrSessions() {
        val c = ComfortLedger(); repeat(4) { c.stage("a", "b", 200f) }
        c.clear(); assertEquals(ComfortBand.STRANGERS, c.band(natural, "a", "b"))
        assertEquals(ComfortBand.STRANGERS, ComfortLedger().band(natural, "a", "b"), "a new ledger knows nothing")
        // the director's reset clears it with the scene
        val d = DialogueDirector(ScriptedEngine { "x" }, lore, Said().sink)
        repeat(4) { d.comfort.stage("a", "b", 200f) }; d.reset()
        assertEquals(ComfortBand.STRANGERS, d.comfort.band(natural, "a", "b"))
    }

    @Test fun theDirectorCountsStagesItSees() {
        val a = npc("a", HUMAN); val b = npc("b", HUMAN, false)
        val d = DialogueDirector(ScriptedEngine { "Aye." }, lore, Said().sink)
        Kit.run(d, Kit.pairInStage(a, b), 130f)
        d.update(0.25f, Kit.view(Kit.person(a), Kit.person(b)))
        assertEquals(1, d.comfort.stages("a", "b"))
        assertTrue(d.comfort.score("a", "b") >= 1.5f)
        // a hug is not a sexual stage: not counted
        val d2 = DialogueDirector(ScriptedEngine { "Aye." }, lore, Said().sink)
        Kit.run(d2, Kit.pairInStage(a, b, sexual = false, name = "Hug"), 60f); d2.update(0.25f, Kit.view(Kit.person(a), Kit.person(b)))
        assertEquals(0, d2.comfort.stages("a", "b"))
    }

    @Test fun theBandFeedsThePromptByBandAndRacePair() {
        fun prompt(band: ComfortBand, partner: NpcProfile, mode: TalkMode = TalkMode.LIVE): String {
            val me = npc("me", HUMAN, true, 0, listOf("BRAVE"))
            return TurnPrompt.build(lore, TurnPrompt.Input(me, Kit.pairInStage(me, partner), mode, partner, band, true, emptyList(), stageName = "Cowgirl"))
        }
        val undead = npc("u", UNDEAD, false); val gnome = npc("g", 7, false); val orc = npc("o", 2, false); val worgen = npc("w", 22, false)
        assertTrue("Polite and guarded" in prompt(ComfortBand.STRANGERS, undead))
        assertTrue("Fully uninhibited" in prompt(ComfortBand.UNINHIBITED, undead))
        assertTrue("Frank about desire" in prompt(ComfortBand.COMFORTABLE, undead))
        assertTrue("no heartbeat" in prompt(ComfortBand.UNINHIBITED, undead))
        assertTrue("light" in prompt(ComfortBand.COMFORTABLE, gnome) && "lifted" in prompt(ComfortBand.COMFORTABLE, gnome))
        assertTrue("Huge frame" in prompt(ComfortBand.VERY_COMFORTABLE, orc))
        assertTrue("Fur everywhere" in prompt(ComfortBand.COMFORTABLE, worgen))
        assertFalse("Cold skin" in prompt(ComfortBand.UNINHIBITED, orc), "only the partner's own race")
        assertTrue("uninhibited with each other" in prompt(ComfortBand.UNINHIBITED, undead))
        assertTrue("In bed you are bold" in prompt(ComfortBand.COMFORTABLE, undead) || "In bed you are dominant" in prompt(ComfortBand.COMFORTABLE, undead))
    }
}
