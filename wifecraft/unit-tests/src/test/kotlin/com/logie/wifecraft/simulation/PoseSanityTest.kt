package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The pose check that keeps a folded or twisted body out of a conversation (the user's two screenshots of Tess and Sally). */
class PoseSanityTest {
    private fun v(x: Float, y: Float, z: Float) = Vec3(x, y, z)
    /** A body standing: pelvis at 1.1, chest at 1.35, head at 1.65. */
    private fun stand() = PoseSample(v(0f, 0f, 1.1f), v(0f, 0f, 1.35f), v(0f, 0f, 1.65f), v(0.1f, 0.2f, 0.9f), v(0.1f, -0.2f, 0.9f))
    private val standing = 0.55f

    @Test fun `a standing body passes`() {
        val verdict = PoseSanity.judge(listOf(stand()), standing)
        assertTrue(verdict.ok, verdict.reasons.toString())
    }

    @Test fun `a body folded forward at the waist fails on lean, bend and head height`() {
        // the head hangs in front of the pelvis, lower than the chest: what the laugh did in the screenshots
        val folded = PoseSample(v(0f, 0f, 1.1f), v(0.18f, 0f, 1.3f), v(0.34f, 0f, 1.22f))
        val verdict = PoseSanity.judge(listOf(stand(), folded), standing)
        assertFalse(verdict.ok)
        assertTrue(verdict.reasons.size >= 3, verdict.reasons.toString())
    }

    @Test fun `a head dropped to the side fails on the head's bend`() {
        val tilted = PoseSample(v(0f, 0f, 1.1f), v(0f, 0f, 1.35f), v(0.2f, 0f, 1.58f))
        assertFalse(PoseSanity.judge(listOf(tilted), standing).ok)
    }

    @Test fun `a small lean and a raised hand are fine`() {
        val lean = PoseSample(v(0f, 0f, 1.1f), v(0.03f, 0f, 1.35f), v(0.07f, 0f, 1.65f), v(0.3f, 0.2f, 1.6f), v(0.1f, -0.2f, 0.9f))
        val verdict = PoseSanity.judge(listOf(lean), standing)
        assertTrue(verdict.ok, verdict.reasons.toString())
    }

    @Test fun `an arm flung far above the head fails`() {
        val cheer = PoseSample(v(0f, 0f, 1.1f), v(0f, 0f, 1.35f), v(0f, 0f, 1.65f), v(0.1f, 0.2f, 2.3f), v(0.1f, -0.2f, 0.9f))
        assertFalse(PoseSanity.judge(listOf(cheer), standing).ok)
    }

    @Test fun `no frames is not a failure`() {
        assertTrue(PoseSanity.judge(emptyList(), standing).ok)
    }

    @Test fun `the measured table passes the talking gestures and fails the ones that fold the body`() {
        for (good in listOf(Gestures.TALK, Gestures.QUESTION, Gestures.WAVE, 186, 208, 1492, 778)) {
            val verdict = assertNotNull(ConversationPoses.verdict(good), "animation $good was not measured")
            assertTrue(verdict.ok, "animation $good: ${verdict.reasons}")
        }
        for (bad in listOf(Gestures.LAUGH, Gestures.EXCLAIM, Gestures.YES, 66, 68, 69, 73, 77, 79, 81)) {
            val verdict = assertNotNull(ConversationPoses.verdict(bad), "animation $bad was not measured")
            assertFalse(verdict.ok, "animation $bad should fail: ${verdict.metrics}")
        }
    }

    @Test fun `a conversation never plays a rejected gesture and says which`() {
        ConversationPoses.resetForTests()
        val said = ArrayList<String>()
        val was = ConversationPoses.log
        ConversationPoses.log = { said += it }
        try {
            assertEquals(Gestures.TALK, ConversationPoses.vet(Gestures.TALK))
            assertEquals(Gestures.STAND, ConversationPoses.vet(Gestures.STAND))
            assertEquals(-1, ConversationPoses.vet(-1))
            assertEquals(Gestures.TALK, ConversationPoses.vet(Gestures.LAUGH))   // a laugh talks instead
            assertEquals(Gestures.TALK, ConversationPoses.vet(68))                // a cheer talks
            assertEquals(Gestures.TALK, ConversationPoses.vet(99999))             // never measured: not vouched for
            assertEquals(Gestures.STAND, ConversationPoses.vet(Gestures.YES, Gestures.STAND))   // the listener's nod stands
            ConversationPoses.vet(Gestures.LAUGH)
            assertEquals(setOf(Gestures.LAUGH, 68, 99999, Gestures.YES), ConversationPoses.rejected())
            assertEquals(4, said.size, "each id is logged once: $said")
            assertTrue(said.first().contains("${Gestures.LAUGH}"))
        } finally { ConversationPoses.log = was; ConversationPoses.resetForTests() }
    }

    @Test fun `a conversation plays talking, a question, a wave and standing as asked, and an exclamation as talking`() {
        val asked = listOf(Gestures.TALK, Gestures.QUESTION, Gestures.WAVE, Gestures.STAND)
        for (g in asked) assertEquals(g, ConversationPoses.vet(g))
        assertEquals(Gestures.TALK, ConversationPoses.vet(Gestures.EXCLAIM))
    }

    @Test fun `a laughing line's speaker talks through the conversation clock, never laughs, and a listener's nod stands`() {
        val c = Conversation(listOf(ChatLine(0, "Ha! Good one.", Gestures.LAUGH), ChatLine(1, "Thank you.", Gestures.TALK)))
        c.start()
        assertEquals(Gestures.TALK, c.gesture(0))
        assertEquals(Gestures.STAND, c.gesture(1))
        c.advance(Conversation.GESTURE_SECONDS + 0.1f)
        assertEquals(Gestures.TALK, c.gesture(0))
        val n = Conversation(listOf(ChatLine(0, "Well, there it is.", Gestures.TALK, nod = true), ChatLine(1, "Quite.", Gestures.TALK)))
        n.start(); n.advance(Reading.seconds("Well, there it is.") - 0.5f)
        assertEquals(Gestures.STAND, n.gesture(1))
    }
}
