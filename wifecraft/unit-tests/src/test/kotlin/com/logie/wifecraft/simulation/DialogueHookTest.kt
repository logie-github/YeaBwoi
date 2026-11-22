package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.dialogue.ConsentGate
import com.logie.wifecraft.dialogue.DialogueHook
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The dialogue layer's hook in the simulation: absent or inactive, nothing changes; a sexual stage waits for the answer and a refusal ends the invitation. */
class DialogueHookTest {
    private val step = 1f / 120f
    private fun anim(id: String) = Anim(id, id, "Test", 2f, false, emptyList(), "test:$id", 0L)
    private fun pair(id: String) = PartnerPair(id, "Pair $id", anim("$id-a"), anim("$id-b"), PartnerSlot(-0.4f, 0f, 0f, 0f), PartnerSlot(0.4f, 0f, 0f, 180f), false)

    private class Hook(var answer: ConsentGate, override var active: Boolean = true) : DialogueHook {
        override var cinematic = false
        val asked = ArrayList<String>()
        override fun takesOverChat(a: String, b: String) = active
        override fun consent(initiator: String, target: String, directed: Boolean, stageName: String): ConsentGate { asked += stageName; return answer }
    }

    private fun sim(hook: DialogueHook?, p: PartnerPair): SceneSimulation = SceneSimulation(listOf("a", "b", "c"), emptySet(), Random(7), pairs = listOf(p)).also { it.dialogue = hook }

    @Test fun withNoLayerOrAnInactiveOneASexualStageStartsAsBefore() {
        for (hook in listOf(null, Hook(ConsentGate.REFUSED, active = false))) {
            val s = sim(hook, pair("x"))
            assertTrue(s.partner("a", "c")); assertEquals("APPROACH", s.partnerOf("a")?.stage)
            (hook as Hook?)?.let { assertTrue(it.asked.isEmpty()) }
        }
    }

    @Test fun aWillingAnswerStartsItAtOnceAndAMildStageIsNeverAsked() {
        val ok = Hook(ConsentGate.PROCEED); val s = sim(ok, pair("x"))
        assertTrue(s.partner("a", "c")); assertEquals("APPROACH", s.partnerOf("a")?.stage); assertEquals(1, ok.asked.size)
        val refuses = Hook(ConsentGate.REFUSED); val mild = sim(refuses, pair("hug"))
        assertTrue(mild.partner("a", "c")); assertNotNull(mild.partnerOf("a")); assertTrue(refuses.asked.isEmpty(), "a hug is not a sexual stage")
    }

    @Test fun aRefusalEndsTheInvitationWithNoStage() {
        val h = Hook(ConsentGate.REFUSED); val s = sim(h, pair("x"))
        assertTrue(s.partner("a", "c"), "handled")
        assertNull(s.partnerOf("a")); assertNull(s.partnerOf("c"))
        repeat(1200) { s.tick(step) }
        assertNull(s.partnerOf("a"))
    }

    @Test fun waitingHoldsThemThenTheAnswerLetsItStartWithTheSameChoices() {
        val h = Hook(ConsentGate.WAIT); val s = sim(h, pair("x"))
        assertTrue(s.partner("a", "c")); assertNull(s.partnerOf("a"))
        repeat(600) { s.tick(step) }
        assertNull(s.partnerOf("a"), "still waiting after five seconds"); assertTrue(h.asked.size > 10, "asked again every step")
        h.answer = ConsentGate.PROCEED; s.tick(step)
        assertEquals("APPROACH", s.partnerOf("a")?.stage); assertEquals("Pair x", s.partnerOf("a")?.name)
    }

    @Test fun aWaitThatNeverEndsGoesOnAsIfWillingAndALayerThatTurnsOffLetsItThrough() {
        val h = Hook(ConsentGate.WAIT); val s = sim(h, pair("x"))
        s.partner("a", "c"); h.active = false; s.tick(step)
        assertEquals("APPROACH", s.partnerOf("a")?.stage)
        val h2 = Hook(ConsentGate.WAIT); val s2 = sim(h2, pair("x")); s2.partner("a", "c")
        var steps = 0; while (s2.partnerOf("a") == null && steps++ < (SceneSimulation.CONSENT_MAX_WAIT * 120 + 600).toInt()) s2.tick(step)
        assertNotNull(s2.partnerOf("a"), "after the longest wait it goes on")
    }

    @Test fun theSexualClassificationIsPluggable() {
        val h = Hook(ConsentGate.PROCEED); val s = sim(h, pair("hug")); s.sexualStage = { _, _ -> true }
        s.partner("a", "c"); assertEquals(1, h.asked.size)
        s.tick(step)
    }
}
