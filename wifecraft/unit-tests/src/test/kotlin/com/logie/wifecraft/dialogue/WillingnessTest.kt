package com.logie.wifecraft.dialogue

import com.logie.wifecraft.dialogue.Kit.BLOODELF
import com.logie.wifecraft.dialogue.Kit.HUMAN
import com.logie.wifecraft.dialogue.Kit.NIGHTELF
import com.logie.wifecraft.dialogue.Kit.ORC
import com.logie.wifecraft.dialogue.Kit.TAUREN
import com.logie.wifecraft.dialogue.Kit.UNDEAD
import com.logie.wifecraft.dialogue.Kit.lore
import com.logie.wifecraft.dialogue.Kit.npc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WillingnessTest {
    private fun prior(a: NpcProfile, b: NpcProfile, rel: Relation = Relation(), band: ComfortBand = ComfortBand.STRANGERS) = WillingnessModel.prior(lore, a, b, rel, band)

    @Test fun mostPairsAreWillingAndTheFactionWarMakesHesitation() {
        val human = npc("h", HUMAN); val human2 = npc("h2", HUMAN, false); val orc = npc("o", ORC); val undead = npc("u", UNDEAD); val belf = npc("b", BLOODELF)
        assertEquals(Willing.WILLING, WillingnessModel.classify(prior(human, human2)))
        assertEquals(Willing.WILLING, WillingnessModel.classify(prior(orc, undead)), "the Horde take the Forsaken in")
        assertEquals(Willing.WILLING, WillingnessModel.classify(prior(orc, belf)))
        for ((x, y) in listOf(human to undead, human to orc, orc to human, undead to human))
            assertEquals(Willing.HESITANT, WillingnessModel.classify(prior(x, y)), "${x.id} to ${y.id}")
        // Alliance toward the Forsaken is the highest, then other Horde; symmetric the other way round but lower
        assertTrue(prior(human, undead) > prior(human, orc))
        assertTrue(prior(human, orc) > prior(human, human2))
        assertTrue(prior(orc, human) > prior(orc, undead))
        assertTrue(prior(undead, human) > prior(undead, orc))
        assertTrue(prior(npc("n", NIGHTELF), undead) > prior(human, undead), "a night elf, after the burning")
        assertTrue(prior(belf, undead) > prior(orc, undead), "a blood elf is fastidious")
        assertTrue(prior(npc("t", TAUREN), undead) > prior(orc, undead))
    }

    @Test fun natureAndRelationshipTuneIt() {
        val undead = npc("u", UNDEAD)
        val bold = npc("a", HUMAN, traits = listOf("BRAVE", "MISCHIEVOUS")); val curious = npc("c", HUMAN, traits = listOf("BOOKISH", "PLAYFUL"))
        val devout = npc("d", HUMAN, traits = listOf("ASCETIC", "LONER"), bio = "A devout, prejudiced woman.")
        val plain = npc("p", HUMAN)
        assertTrue(prior(devout, undead) > prior(plain, undead) && prior(plain, undead) > prior(bold, undead))
        assertTrue(prior(curious, undead) < prior(plain, undead))
        assertTrue(prior(devout, undead) >= WillingnessModel.REFUSE_AT - 0.2f)
        // trust: friendship and romance, familiarity (the band), hostility
        val friend = prior(plain, undead, Relation(60f, 0f)); val lover = prior(plain, undead, Relation(60f, 50f))
        assertTrue(friend < prior(plain, undead) && lover < friend)
        assertEquals(Willing.WILLING, WillingnessModel.classify(lover), "lovers don't hesitate")
        assertTrue(prior(plain, undead, band = ComfortBand.VERY_COMFORTABLE) < prior(plain, undead))
        assertEquals(Willing.REFUSED, WillingnessModel.classify(prior(devout, undead, Relation(-60f, 0f))), "a hated one is refused")
        // shy and devout hesitate even with their own
        assertEquals(Willing.HESITANT, WillingnessModel.classify(prior(devout, npc("x", HUMAN))))
    }

    @Test fun thresholds() {
        assertEquals(Willing.WILLING, WillingnessModel.classify(0.2499f)); assertEquals(Willing.HESITANT, WillingnessModel.classify(0.25f))
        assertEquals(Willing.HESITANT, WillingnessModel.classify(0.7199f)); assertEquals(Willing.REFUSED, WillingnessModel.classify(0.72f))
    }

    @Test fun theModelMovesItOnlyWithinBounds() {
        val no = ModelVerdict(ModelVerdict.Decision.NO, null); val yes = ModelVerdict(ModelVerdict.Decision.YES, null)
        assertEquals(0.35f, WillingnessModel.applyModel(0.10f, no), 1e-5f, "a no cannot refuse the willing outright")
        assertEquals(0.25f, WillingnessModel.applyModel(0.50f, yes), 1e-5f)
        assertEquals(0.85f, WillingnessModel.applyModel(0.60f, no), 1e-5f)
        assertEquals(0.3f, WillingnessModel.applyModel(0.3f, null))
        assertEquals(0.1f, WillingnessModel.applyModel(0.1f, yes), 1e-5f)
    }

    @Test fun theAnswerIsParsedRobustly() {
        WillingnessModel.parse("{\"willing\":\"yes\",\"reason\":\"Come here, you.\"}")!!.let { assertEquals(ModelVerdict.Decision.YES, it.decision); assertEquals("Come here, you.", it.line) }
        WillingnessModel.parse("Sure! {\"decision\": \"maybe-later\", \"reason\": \"Not tonight.\"} thanks")!!.let { assertEquals(ModelVerdict.Decision.LATER, it.decision); assertEquals("Not tonight.", it.line) }
        assertEquals(ModelVerdict.Decision.HESITANT, WillingnessModel.parse("{\"willing\":\"hesitant\",\"reason\":\"You are so cold.\"}")!!.decision)
        assertEquals(ModelVerdict.Decision.NO, WillingnessModel.parse("{\"willing\":false,\"reason\":\"No.\"}")!!.decision.let { ModelVerdict.Decision.NO })
        assertEquals(ModelVerdict.Decision.YES, WillingnessModel.parse("{\"willing\":true}")!!.decision)
        assertEquals(ModelVerdict.Decision.YES, WillingnessModel.parse("Yes, gladly. Come in.")!!.decision)
        assertEquals(ModelVerdict.Decision.NO, WillingnessModel.parse("No. Leave me be.")!!.decision)
        assertEquals(ModelVerdict.Decision.HESITANT, WillingnessModel.parse("I am afraid, truly.")!!.decision)
        assertNull(WillingnessModel.parse("the weather is fine")); assertNull(WillingnessModel.parse("")); assertNull(WillingnessModel.parse(null)); assertNull(WillingnessModel.parse("{broken"))
        assertNotNull(WillingnessModel.parse("{\"willing\":\"yes\"}"))
    }

    @Test fun anUnreadableAnswerLeavesThePriorWhichMostlySaysYes() {
        val p = prior(npc("a", HUMAN), npc("b", HUMAN, false))
        assertEquals(Willing.WILLING, WillingnessModel.classify(WillingnessModel.applyModel(p, WillingnessModel.parse("???"))))
    }

    @Test fun mostPairsOfTypicalPeopleAreWilling() {
        val traitSets = listOf(emptyList(), listOf("CALM"), listOf("PLAYFUL", "ROMANTIC"), listOf("BRAVE"), listOf("GREGARIOUS"), listOf("BOOKISH"), listOf("MISCHIEVOUS"), listOf("GLUTTON"), listOf("NIGHT_OWL", "CALM"), listOf("LONER", "BOOKISH"))
        val races = listOf(HUMAN, 3, 7, 32, 29, 1) // Alliance people, among themselves
        var willing = 0; var n = 0
        for (r1 in races) for (r2 in races) for (t in traitSets) { n++; if (WillingnessModel.classify(prior(npc("x", r1, traits = t), npc("y", r2), Relation(10f, 0f))) == Willing.WILLING) willing++ }
        assertTrue(willing.toFloat() / n >= 0.88f, "$willing of $n")
    }

    @Test fun mostHesitantNpcsAreWonRoundInOneToThreeAttempts() {
        val plain = npc("p", HUMAN); val undead = npc("u", UNDEAD)
        var h = Hesitation("p", "u", prior(plain, undead), plain.disposition, WillingnessModel.fear(lore, plain, undead))
        assertEquals(Fear.PREJUDICE, h.fear)
        val tries = listOf(Approach.PATIENT, Approach.KINDNESS, Approach.REASSURE)
        var out = Hesitation.Outcome.STILL_HESITANT
        for (a in tries) { out = h.persuade(a); if (out != Hesitation.Outcome.STILL_HESITANT) break }
        assertEquals(Hesitation.Outcome.AGREED, out); assertTrue(h.attempts in 1..3)
        // the best approach for the person wins sooner than a poor one
        val devout = npc("d", HUMAN, traits = listOf("ASCETIC", "LONER"), bio = "devout"); val d = devout.disposition
        val fear = WillingnessModel.fear(lore, devout, undead)
        assertTrue(WillingnessModel.fit(Approach.REASSURE, d, fear) > WillingnessModel.fit(Approach.JOKE, d, fear))
        assertEquals(Approach.REASSURE, WillingnessModel.bestApproach(d, fear).let { if (it == Approach.PATIENT) Approach.REASSURE else it })
        // curiosity wins the curious
        val curious = npc("c", HUMAN, traits = listOf("BOOKISH", "MISCHIEVOUS")).disposition
        assertTrue(WillingnessModel.fit(Approach.CURIOSITY, curious, Fear.PREJUDICE) > WillingnessModel.fit(Approach.CURIOSITY, d, Fear.PREJUDICE))
        // each attempt lowers the resistance when it fits
        h = Hesitation("p", "u", 0.5f, plain.disposition, Fear.GENERAL)
        val before = h.resistance; h.persuade(Approach.KINDNESS); assertTrue(h.resistance < before)
    }

    @Test fun poorAttemptsEndInRefusalForNow() {
        val d = Disposition(caution = 0f, devout = 1f, prejudice = 0.6f)
        val h = Hesitation("d", "u", 0.52f, d, Fear.PREJUDICE)
        assertEquals(Hesitation.Outcome.STILL_HESITANT, h.persuade(Approach.JOKE)); assertEquals(Hesitation.Outcome.STILL_HESITANT, h.persuade(Approach.JOKE))
        assertEquals(Hesitation.Outcome.REFUSED, h.persuade(Approach.JOKE))
        assertTrue(h.resistance >= WillingnessModel.CONVINCED_BELOW)
    }

    @Test fun theModelsJudgementShiftsAnAttemptWithinBounds() {
        val d = Disposition()
        val calmer = Hesitation("a", "b", 0.5f, d, Fear.GENERAL).also { it.persuade(Approach.PATIENT, Reaction.CALMER) }
        val same = Hesitation("a", "b", 0.5f, d, Fear.GENERAL).also { it.persuade(Approach.PATIENT, Reaction.SAME) }
        val warier = Hesitation("a", "b", 0.5f, d, Fear.GENERAL).also { it.persuade(Approach.PATIENT, Reaction.WARIER) }
        val plain = Hesitation("a", "b", 0.5f, d, Fear.GENERAL).also { it.persuade(Approach.PATIENT) }
        assertTrue(calmer.resistance < plain.resistance && plain.resistance < same.resistance && same.resistance < warier.resistance)
        assertTrue(Math.abs(calmer.resistance - plain.resistance) <= 0.15f + 1e-4f)
    }
}
