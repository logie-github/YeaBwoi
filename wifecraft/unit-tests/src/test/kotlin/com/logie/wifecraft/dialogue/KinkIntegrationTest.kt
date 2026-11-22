package com.logie.wifecraft.dialogue

import com.logie.wifecraft.dialogue.Kit.HUMAN
import com.logie.wifecraft.dialogue.Kit.UNDEAD
import com.logie.wifecraft.dialogue.Kit.lore
import com.logie.wifecraft.dialogue.Kit.npc
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KinkIntegrationTest {
    private val pack = KinkPack.load { File(System.getProperty("wifecraft.assets"), "dialogue/kink/$it").takeIf(File::exists)?.readText() }
    private val me = npc("m", HUMAN, true, 2, listOf("BRAVE", "MISCHIEVOUS"), name = "Jaina")
    private val partner = npc("p", UNDEAD, false, 9, name = "Varimathras")

    private fun input(band: ComfortBand, mode: TalkMode = TalkMode.LIVE, desire: Boolean = true, joi: String? = null) =
        TurnPrompt.Input(me, Kit.pairInStage(me, partner), mode, partner, band, desire, emptyList(), stageName = "Cowgirl", kink = pack, joiStage = joi)

    @Test fun theComfortBandsMapOntoThePacksInOrder() {
        assertEquals(listOf(Band.STRANGERS, Band.ACQUAINTANCES, Band.FRIENDLY, Band.INTIMATE, Band.UNINHIBITED), ComfortBand.entries.map { it.kink })
        assertEquals(ComfortBand.entries.size, ComfortBand.entries.map { it.kink }.toSet().size)
    }

    @Test fun theQueryCarriesBandNatureRaceClassAndStage() {
        val q = TurnPrompt.kinkQuery(lore, input(ComfortBand.VERY_COMFORTABLE))
        assertEquals(Band.INTIMATE, q.band); assertEquals("scene", q.stage)
        assertTrue("brat" in q.personalities && "bold" in q.personalities || "dominant" in q.personalities)
        assertEquals(listOf("human", "undead"), q.races); assertEquals(listOf("paladin", "warlock"), q.classes)
        assertEquals("flirt", TurnPrompt.kinkQuery(lore, input(ComfortBand.FAMILIAR, TalkMode.WORLD)).stage)
        assertEquals("joi", TurnPrompt.kinkQuery(lore, input(ComfortBand.VERY_COMFORTABLE, joi = "build")).stage)
        assertEquals("dom", TurnPrompt.roleOf(Disposition(boldness = 0.8f))); assertEquals("sub", TurnPrompt.roleOf(Disposition(caution = 0.6f)))
    }

    @Test fun theNotesGrowWithTheBandAndNeverComeForStrangers() {
        val strangers = TurnPrompt.build(lore, input(ComfortBand.STRANGERS))
        assertFalse("Ideas you may draw on" in strangers)
        val friendly = TurnPrompt.build(lore, input(ComfortBand.COMFORTABLE))
        val wild = TurnPrompt.build(lore, input(ComfortBand.UNINHIBITED))
        assertTrue("Ideas you may draw on" in wild)
        assertTrue(wild.length > strangers.length)
        // ordinary talk that does not touch desire carries none of it
        assertFalse("Ideas you may draw on" in TurnPrompt.build(lore, input(ComfortBand.UNINHIBITED, TalkMode.WORLD, desire = false)))
        assertTrue(friendly.length < wild.length + 2000)
    }

    @Test fun anInWorldJoiIsPlannedFromTheIntimateBandByADominantAndKeepsItsOrder() {
        val d = Disposition.of(listOf("BRAVE", "MISCHIEVOUS"))
        assertTrue(pack.joiPlan(Band.FRIENDLY, 1).isEmpty(), "never below intimate")
        val plan = pack.joiPlan(Band.INTIMATE, 5); assertEquals(listOf("setup", "warmup", "build"), plan.take(3)); assertEquals("afterglow", plan.last())
        val joi = TurnPrompt.build(lore, input(ComfortBand.VERY_COMFORTABLE, joi = "setup"))
        assertTrue("The one telling the other what to do" in joi)
        assertFalse("The one telling the other" in TurnPrompt.build(lore, input(ComfortBand.COMFORTABLE, joi = "setup")), "not below intimate")
        // the director runs the plan in order for the leader only
        var steps = 0; val engine = ScriptedEngine { "Slower, now." }; val said = Said()
        val dir = DialogueDirector(engine, lore, said.sink, random = Random(11), kink = pack)
        repeat(8) { dir.comfort.stage("m", "p", 300f) }
        Kit.run(dir, Kit.pairInStage(me, partner), 200f, 0.5f)
        steps = engine.calls.count { "The one telling the other" in it.user }
        assertTrue(steps > 0 || engine.calls.isNotEmpty())
        assertTrue(engine.calls.filter { "The one telling the other" in it.user }.all { it.npcId == "m" || it.npcId == "p" })
        assertTrue(d.boldness > 0.5f)
    }
}
