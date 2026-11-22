package com.logie.wifecraft.dialogue

import com.logie.wifecraft.dialogue.Kit.HUMAN
import com.logie.wifecraft.dialogue.Kit.UNDEAD
import com.logie.wifecraft.dialogue.Kit.lore
import com.logie.wifecraft.dialogue.Kit.npc
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The layer against the runtime's contract: focus, priorities, forgetting, and an engine that is not ready or answers nothing. */
class RuntimeWiringTest {
    private val a = npc("a", HUMAN, true, 2, listOf("CALM"), name = "Jaina Proudmoore")
    private val b = npc("b", UNDEAD, false, 0, listOf("BRAVE"), name = "Varimathras")
    private fun director(e: DialogueEngine, said: Said = Said()) = DialogueDirector(e, lore, said.sink, random = Random(3))

    @Test fun theRuntimeIsToldWhoIsSelectedAndWhoIsTalking() {
        val e = FakeDialogueEngine("Aye."); val d = director(e)
        val v = Kit.view(Kit.person(a, "talking", "b", conversation = true, leads = true, selected = true), Kit.person(b, "talking", "a", conversation = true))
        d.update(0.25f, v)
        assertEquals("a", e.focusSelected); assertEquals(setOf("a", "b"), e.focusPair)
        // nothing selected, a sexual stage: that pair; and it follows a change
        d.update(0.25f, Kit.pairInStage(a, b))
        assertEquals(null, e.focusSelected); assertEquals(setOf("a", "b"), e.focusPair)
        d.update(0.25f, Kit.view(Kit.person(a), Kit.person(b)))
        assertEquals(emptySet(), e.focusPair)
    }

    @Test fun priorityFollowsTheSelectionTheStageAndTheTalk() {
        fun first(v: SceneView): DialoguePriority { val e = FakeDialogueEngine("Aye."); Kit.run(director(e), v, 3f); return e.requests.first().priority }
        assertEquals(DialoguePriority.SELECTED, first(Kit.view(Kit.person(a, "t", "b", conversation = true, leads = true, selected = true), Kit.person(b, "t", "a", conversation = true))))
        assertEquals(DialoguePriority.SELECTED, first(Kit.pairInStage(a, b)))
        assertEquals(DialoguePriority.PAIR, first(Kit.pairInConversation(a, b)))
        // an NPC alone who is near is NEARBY, far is AMBIENT
        val near = FakeDialogueEngine("Aye."); val far = FakeDialogueEngine("Aye.")
        repeat(40) { s -> Kit.run(DialogueDirector(near, lore, Said().sink, random = Random(s)), Kit.view(Kit.person(a, dist = 1f)), 40f, 1f) }
        repeat(40) { s -> Kit.run(DialogueDirector(far, lore, Said().sink, random = Random(s)), Kit.view(Kit.person(a, dist = 19f)), 40f, 1f) }
        assertTrue(near.requests.isNotEmpty() && near.requests.all { it.priority == DialoguePriority.NEARBY })
        assertTrue(far.requests.isNotEmpty() && far.requests.all { it.priority == DialoguePriority.AMBIENT })
    }

    @Test fun willingnessIsUrgent() {
        val e = FakeDialogueEngine { r -> if ("wants to lie with you" in r.user) "{\"willing\":\"yes\"}" else null }; val d = director(e)
        d.update(0.1f, Kit.view(Kit.person(a), Kit.person(b)))
        d.consent("b", "a", false, "Passionate Missionary"); d.update(0.1f, Kit.view(Kit.person(a), Kit.person(b)))
        assertTrue(e.requests.any { "wants to lie with you" in it.user && it.priority == DialoguePriority.SELECTED })
    }

    @Test fun anNpcWhoseNatureChangedOrWhoLeftIsForgotten() {
        val e = FakeDialogueEngine("Aye."); val d = director(e)
        d.update(0.25f, Kit.view(Kit.person(a), Kit.person(b)))
        assertTrue(e.forgotten.isEmpty())
        d.update(0.25f, Kit.view(Kit.person(a.copy(traits = listOf("BRAVE"), disposition = Disposition.of(listOf("BRAVE")))), Kit.person(b)))
        assertEquals(listOf("a"), e.forgotten)
        d.update(0.25f, Kit.view(Kit.person(a.copy(traits = listOf("BRAVE"), disposition = Disposition.of(listOf("BRAVE"))))))
        assertEquals(listOf("a", "b"), e.forgotten, "b left")
        d.reset(); assertTrue(e.forgotten.contains("a"), "the scene's end gives them all up")
    }

    @Test fun aModelThatIsNotReadyMakesTheLayerInactiveAndAsksNothing() {
        val e = FakeDialogueEngine("Aye."); e.ready = false; val said = Said(); val d = director(e, said)
        Kit.run(d, Kit.pairInConversation(a, b), 30f)
        assertFalse(d.active); assertTrue(e.requests.isEmpty() && said.lines.isEmpty())
        assertEquals(ConsentGate.PROCEED, d.consent("a", "b", true, "Passionate Missionary"), "no question before a stage")
        assertTrue(d.takesOverChat("a", "b").not(), "scripted talk stays")
        e.ready = true; Kit.run(d, Kit.pairInConversation(a, b), 6f)
        assertTrue(d.active); assertTrue(said.lines.isNotEmpty(), "and it starts when the model is ready")
    }

    @Test fun nullAnswersBackOffInsteadOfSpinning() {
        val e = FakeDialogueEngine { null }; val said = Said(); val d = director(e, said)
        Kit.run(d, Kit.pairInConversation(a, b), 120f)
        assertTrue(said.lines.isEmpty()); assertTrue(e.requests.size in 1..14, "${e.requests.size} requests in two minutes")
        // a stage with the model busy does not ask consent for ever either
        val d2 = director(FakeDialogueEngine { null })
        val c = npc("c", HUMAN, false)
        val v = Kit.view(Kit.person(a), Kit.person(c))
        d2.update(0.1f, v); d2.consent("c", "a", false, "Passionate Missionary"); d2.update(0.1f, v)
        assertEquals(ConsentGate.PROCEED, d2.consent("c", "a", false, "Passionate Missionary"), "no answer: the prior, which says yes")
    }

    @Test fun aLateModelIsUsedByARunningScene() {
        var engine: DialogueEngine? = null
        val said = Said(); val d = DialogueDirector({ engine }, lore, said.sink, random = Random(3))
        Kit.run(d, Kit.pairInConversation(a, b), 5f); assertTrue(said.lines.isEmpty())
        val f = FakeDialogueEngine("Aye, well met."); engine = f
        Kit.run(d, Kit.pairInConversation(a, b), 8f)
        assertTrue(said.lines.isNotEmpty()); assertTrue(f.focusSelected == null && f.focusPair == setOf("a", "b"))
    }
}
