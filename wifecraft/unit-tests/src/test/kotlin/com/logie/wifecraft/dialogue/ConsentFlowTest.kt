package com.logie.wifecraft.dialogue

import com.logie.wifecraft.dialogue.Kit.HUMAN
import com.logie.wifecraft.dialogue.Kit.NIGHTELF
import com.logie.wifecraft.dialogue.Kit.UNDEAD
import com.logie.wifecraft.dialogue.Kit.lore
import com.logie.wifecraft.dialogue.Kit.npc
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsentFlowTest {
    private val human = npc("h", HUMAN, true, 2, name = "Jaina")
    private val undead = npc("u", UNDEAD, false, 3, name = "Varimathras")
    private val elf = npc("e", NIGHTELF, true, 11, name = "Tyrande")
    private val v = { x: NpcProfile, y: NpcProfile -> Kit.view(Kit.person(x), Kit.person(y)) }

    private fun engineFor(ask: String, react: String = "{\"reaction\":\"calmer\",\"line\":\"Perhaps. Stay a moment.\"}", close: String = "Very well, then.", partner: String = "Easy now. I mean no harm.") =
        ScriptedEngine { r -> when {
            "wants to lie with you" in r.user -> ask
            "tries to calm you" in r.user -> react
            "Your way of calming them" in r.user -> partner
            "You have been calmed" in r.user -> close
            "not persuaded" in r.user -> "Not tonight."
            else -> null } }

    private fun setup(engine: DialogueEngine, x: NpcProfile, y: NpcProfile, rel: Relation = Relation()): Pair<DialogueDirector, Said> {
        val said = Said(); val d = DialogueDirector(engine, lore, said.sink, random = Random(1))
        d.update(0.1f, Kit.view(Kit.person(x), Kit.person(y), relation = { _, _ -> rel }))
        return d to said
    }
    private fun pump(d: DialogueDirector, x: NpcProfile, y: NpcProfile, seconds: Float = 0.5f, rel: Relation = Relation()) =
        Kit.run(d, Kit.view(Kit.person(x), Kit.person(y), relation = { _, _ -> rel }), seconds)

    @Test fun aWillingPairIsAskedOnceThenGoesOnWithTheirLine() {
        val (d, said) = setup(engineFor("{\"willing\":\"yes\",\"reason\":\"Come here, you.\"}"), human, human.copy(id = "h2", name = "Kinndy"))
        assertEquals(ConsentGate.WAIT, d.consent("h", "h2", true, "Passionate Missionary"))
        pump(d, human, human.copy(id = "h2", name = "Kinndy"))
        assertEquals(ConsentGate.PROCEED, d.consent("h", "h2", true, "Passionate Missionary"))
    }

    @Test fun noAnswerFromTheModelLeavesThePriorWhichIsWillingSilently() {
        val (d, said) = setup(ScriptedEngine { null }, human, human.copy(id = "h2", name = "Kinndy"))
        d.consent("h", "h2", false, "Passionate Missionary"); pump(d, human, human.copy(id = "h2", name = "Kinndy"))
        assertEquals(ConsentGate.PROCEED, d.consent("h", "h2", false, "Passionate Missionary"))
        assertTrue(said.lines.isEmpty(), "no model line, no canned line")
    }

    @Test fun anAllianceHumanFearsTheForsakenAndCanBeCalmedByThePlayer() {
        val (d, said) = setup(engineFor("{\"willing\":\"hesitant\",\"reason\":\"You are so cold... I am afraid.\"}"), human, undead)
        assertEquals(ConsentGate.WAIT, d.consent("u", "h", false, "Passionate Missionary"))
        pump(d, human, undead)
        assertEquals("h" to "You are so cold... I am afraid.", said.lines.single(), "the hesitation is voiced by the one who hesitates")
        assertEquals(ConsentGate.WAIT, d.consent("u", "h", false, "Passionate Missionary"), "the stage pauses before starting")
        val hes = d.hesitant().single(); assertEquals("h", hes.npcId); assertEquals("Varimathras", hes.otherName)
        assertTrue(d.persuade("h", Approach.KINDNESS)); pump(d, human, undead)
        assertTrue(said.lines.any { it.second.startsWith("Very well") }, "agrees in character: ${said.lines}")
        assertEquals(ConsentGate.PROCEED, d.consent("u", "h", false, "Passionate Missionary"))
        assertTrue(d.hesitant().isEmpty())
    }

    @Test fun theHesitantAreWonRoundByTheirPartnerWhenNobodyElseDoes() {
        val (d, said) = setup(engineFor("{\"willing\":\"hesitant\",\"reason\":\"I... am afraid.\"}"), human, undead)
        d.consent("u", "h", false, "Passionate Missionary")
        var gate = ConsentGate.WAIT; var t = 0f
        while (gate == ConsentGate.WAIT && t < 70f) { pump(d, human, undead, 1f); t += 1f; gate = d.consent("u", "h", false, "Passionate Missionary") }
        assertEquals(ConsentGate.PROCEED, gate, "mostly willing: convinced within a try or three")
        assertTrue(said.lines.any { it.first == "u" && it.second.startsWith("Easy now") }, "the partner's own words: ${said.lines}")
        assertTrue(t < 40f)
    }

    @Test fun aNightElfRefusesForNowAndTheInvitationEndsWithACooldown() {
        val engine = engineFor("{\"willing\":\"no\",\"reason\":\"Never. You people burned my home.\"}")
        val (d, said) = setup(engine, elf, undead)
        d.consent("u", "e", false, "Passionate Missionary"); pump(d, elf, undead)
        assertEquals("e" to "Never. You people burned my home.", said.lines.single())
        assertEquals(ConsentGate.REFUSED, d.consent("u", "e", false, "Passionate Missionary"))
        val calls = engine.calls.size
        assertEquals(ConsentGate.REFUSED, d.consent("u", "e", false, "Passionate Missionary"), "the cooldown holds")
        assertEquals(calls, engine.calls.size, "and asks nothing more")
        assertTrue(d.comfort.score("u", "e") == 0f && d.comfort.stages("u", "e") == 0)
    }

    @Test fun maybeLaterIsARefusalForNowWithACooldown() {
        val (d, _) = setup(engineFor("{\"willing\":\"maybe-later\",\"reason\":\"Not yet.\"}"), elf, undead)
        d.consent("u", "e", false, "Passionate Missionary"); pump(d, elf, undead)
        assertEquals(ConsentGate.REFUSED, d.consent("u", "e", false, "Passionate Missionary"))
    }

    @Test fun theModelCannotRefuseAWillingPairOutright() {
        val (d, _) = setup(engineFor("{\"willing\":\"no\",\"reason\":\"No.\"}"), human, human.copy(id = "h2", name = "Kinndy"))
        d.consent("h", "h2", false, "Passionate Missionary"); pump(d, human, human.copy(id = "h2", name = "Kinndy"))
        assertEquals(ConsentGate.WAIT, d.consent("h", "h2", false, "Passionate Missionary"), "bounded to a hesitation")
        assertEquals(1, d.hesitant().size)
    }

    @Test fun theRelationshipDecidesWhoHesitates() {
        val lovers = Relation(70f, 70f)
        val (d, _) = setup(engineFor("{\"willing\":\"yes\"}"), human, undead, lovers)
        d.consent("u", "h", false, "Passionate Missionary"); pump(d, human, undead, rel = lovers)
        assertEquals(ConsentGate.PROCEED, d.consent("u", "h", false, "Passionate Missionary"))
    }

    @Test fun aPlayerOrderAsksBothAndAnUnansweredHesitationEndsGracefully() {
        val engine = engineFor("{\"willing\":\"hesitant\",\"reason\":\"I cannot.\"}", react = "{\"reaction\":\"warier\",\"line\":\"No.\"}", partner = "Please.")
        val (d, _) = setup(engine, human, undead)
        d.consent("u", "h", true, "Passionate Missionary"); pump(d, human, undead)
        assertEquals(2, d.hesitant().size, "ordered by the player: the initiator is asked as well (the Forsaken toward the living)")
        var gate = ConsentGate.WAIT; var t = 0f
        while (gate == ConsentGate.WAIT && t < 90f) { pump(d, human, undead, 1f); t += 1f; gate = d.consent("u", "h", true, "Passionate Missionary") }
        assertTrue(gate != ConsentGate.WAIT, "never waits forever")
    }
}
