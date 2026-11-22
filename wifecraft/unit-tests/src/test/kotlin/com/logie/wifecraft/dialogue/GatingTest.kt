package com.logie.wifecraft.dialogue

import com.logie.wifecraft.dialogue.Kit.BLOODELF
import com.logie.wifecraft.dialogue.Kit.HUMAN
import com.logie.wifecraft.dialogue.Kit.UNDEAD
import com.logie.wifecraft.dialogue.Kit.lore
import com.logie.wifecraft.dialogue.Kit.npc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatingTest {
    private val a = npc("a", HUMAN, true, 0, listOf("BRAVE"))
    private val b = npc("b", UNDEAD, false, 0, listOf("CALM"))

    private fun prompt(mode: TalkMode, band: ComfortBand = ComfortBand.UNINHIBITED, desire: Boolean = true) = TurnPrompt.build(lore,
        TurnPrompt.Input(a, Kit.pairInConversation(a, b), mode, b, band, desire, emptyList(), stageName = "Cowgirl"))

    @Test fun theTwoModesAreSeparate() {
        val world = prompt(TalkMode.WORLD)
        assertTrue("Nothing intimate is happening this moment" in world)
        assertTrue("Do not make the sounds of lovemaking" in world)
        assertTrue("desire" in world, "talk of desire is allowed in ordinary talk")
        val live = prompt(TalkMode.LIVE)
        assertTrue("middle of Cowgirl" in live)
        assertTrue("sounds of it" in live)
        assertFalse("Do not make the sounds" in live)
    }

    @Test fun ordinaryTalkOnlyOpensToDesireWhenTheRollAllowsIt() {
        val quiet = prompt(TalkMode.WORLD, desire = false)
        assertFalse("desire" in quiet); assertFalse("How open you are now" in quiet); assertFalse("Their body" in quiet)
        assertTrue("How open you are now" in prompt(TalkMode.WORLD, desire = true))
    }

    @Test fun theBodyOfARaceIsOnlyOfferedFromComfortableUpOrInTheMoment() {
        assertFalse("Their body" in prompt(TalkMode.WORLD, ComfortBand.FAMILIAR, desire = true))
        assertFalse("Their body" in prompt(TalkMode.WORLD, ComfortBand.STRANGERS, desire = true))
        assertTrue("Cold skin" in prompt(TalkMode.WORLD, ComfortBand.COMFORTABLE, desire = true))
        assertTrue("Cold skin" in prompt(TalkMode.LIVE, ComfortBand.STRANGERS), "during a stage it is always on offer")
    }

    @Test fun theLiveFilterDropsMoansAndLiveNarrationOutsideAStage() {
        for (l in listOf("Oh yeah, baby, harder!", "Don't stop, don't stop!", "Ahh... right there.", "I'm coming!", "You're inside me, Light, oh yes.", "Mmm, faster!"))
            assertTrue(LiveFilter.isLive(l), l)
        assertNull(LiveFilter.rewrite("Oh yeah, baby, harder!"))
        // desire as a topic passes untouched
        for (l in listOf("I have wanted to feel your hands on me since the harvest.", "Tell me what you would do if we were alone.", "I like it when you hold my wrists."))
            assertFalse(LiveFilter.isLive(l), l)
        // a line that mixes keeps its words
        assertEquals("I missed you all day, you rogue.", LiveFilter.rewrite("Oh yeah, I missed you all day, you rogue."))
    }

    @Test fun anachronismsAreFound() {
        for (l in listOf("Okay, fine.", "That is so cool.", "Hey guys.", "Send me an email.", "Cool.", "Whatever, dude.", "Yep, sure.", "The player is watching.", "Great weekend, no?"))
            assertTrue(Anachronisms.find(l) != null, l)
        for (l in listOf("Aye, well met.", "The night air is cool.", "A chill wind off the sea.", "Fresh game for the pot.", "Another mug of ale, innkeeper."))
            assertNull(Anachronisms.find(l), l)
    }

    @Test fun linesAreCutCleanly() {
        assertEquals("Aye, well met.", LineCleaner.clean("\"Aye, well met.\"", "Jaina Proudmoore"))
        assertEquals("Aye.", LineCleaner.clean("Jaina: *smiles* Aye.", "Jaina Proudmoore"))
        assertEquals("One. Two.", LineCleaner.clean("One. Two. Three. Four."))
        val long = "I walked for days along the old road, past the farms and the burnt mill, through the rain and the mud, until at last I saw the lights of the town ahead of me and my legs gave out beneath me in the cold and the dark and I wept."
        val c = LineCleaner.clean(long)!!
        assertTrue(c.length <= LineCleaner.MAX_CHARS + 3 && c.endsWith("..."), c)
        assertNull(LineCleaner.clean("   ")); assertNull(LineCleaner.clean(null))
    }

    @Test fun theSexualStageClassificationIsTheAppsOwn() {
        assertTrue(DialogueGate.isSexual("Passionate Missionary")); assertTrue(DialogueGate.isSexual("Doggy Pleasure"))
        for (n in listOf("Hug", "Slow Dance", "Kiss", "Cuddle", "Play", "Handshake")) assertFalse(DialogueGate.isSexual(n), n)
        assertFalse(DialogueGate.isSexual("Mystery", "CALM")); assertFalse(DialogueGate.isSexual("Mystery", emote = 76))
        assertTrue(DialogueGate.isSexual("Mystery", classify = { _, _ -> true }), "the arousal map decides when the screen has it")
    }
}
