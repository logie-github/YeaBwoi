package com.logie.wifecraft.dialogue

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Retries, early stops and warm-up as the chat and the director use them (JVM, fake engines). */
class ChatSpeedTest {
    private val lore = Kit.lore
    private val place = PlaceView("A House in Brill, Tirisfal", true, 21.0, null, listOf("a hearth"))
    private val jaina = Kit.npc("jaina", Kit.HUMAN, true, 2, listOf("CALM"), "A mage of Dalaran.", "Jaina Wren")
    private val you = ChatCharacter("Brannok", Kit.UNDEAD, false, 9)
    private fun session() = ChatSession(jaina, you, lore, false, place, null, Random(1))

    private fun <T> run(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    @Test fun aWrongWordIsCaughtWhileTheReplyIsStillBeingWrittenAndAskedAgainOnce() {
        val s = session()
        val answers = ArrayDeque(listOf("I will check my phone for you, traveller, and then more words never written.", "Aye, I will look at the ledger for you."))
        val engine = FakeDialogueEngine { answers.removeFirst() }
        val reply = run { s.say("Hello.", engine) }
        assertEquals("Aye, I will look at the ledger for you.", reply)
        assertEquals(2, engine.requests.size)
        assertTrue("do not use the word \"phone\"" in engine.requests[1].user, "the retry says what was wrong")
        assertFalse("phone" in engine.requests[1].user.substringBefore("do not use"), "and the note comes once")
        assertTrue(s.log.none { "phone" in it.text })
    }

    @Test fun aWrongWordTwiceMeansNoReplyAndNoThirdTry() {
        val s = session()
        val engine = FakeDialogueEngine { "I will check my phone and my computer." }
        assertNull(run { s.say("Hello.", engine) })
        assertEquals(2, engine.requests.size, "the retry is capped at one")
        assertTrue(s.quiet)
    }

    @Test fun aReplyThatBreaksTheFourthWallIsAlsoStoppedEarly() {
        val s = session()
        val answers = ArrayDeque(listOf("Do not worry, this is only a game, friend.", "Do not worry, friend."))
        val engine = FakeDialogueEngine { answers.removeFirst() }
        assertEquals("Do not worry, friend.", run { s.say("Hello.", engine) })
        assertTrue("beyond this world" in engine.requests[1].user)
    }

    @Test fun theRetryIsTheOnlyOneEvenWhenTheFinishedReplyIsWrong() {
        // the guard does not see it (it is an anachronism of a kind only the whole line shows: a live vocalisation): rewritten, not asked again
        val s = session()
        val engine = FakeDialogueEngine("Well met, Brannok. Oh yes, harder, oh yes.")
        run { s.say("Hello.", engine) }
        assertEquals(1, engine.requests.size)
    }

    @Test fun herPersonaIsReadAheadWhenTheChatSheetOpens() {
        val s = session()
        val engine = FakeDialogueEngine("x")
        s.warmUp(engine)
        assertEquals(listOf(Triple("chat:jaina", s.personaPrompt(), true)), engine.warmed)
        engine.ready = false
        s.warmUp(engine)
        assertEquals(1, engine.warmed.size, "a model that is not loaded is not asked")
        s.warmUp(null)
        // once the chat has started the context is read as well: the same bytes the requests send
        val e3 = FakeDialogueEngine("x")
        s.warmUp(e3, withContext = true)
        assertEquals(listOf(Triple("chat:jaina", s.systemPrompt(), true)), e3.warmed)
        // the very text the chat's own requests start with
        val e2 = FakeDialogueEngine("Aye.")
        run { s.say("Hi", e2) }
        assertTrue(e2.requests[0].system.startsWith(s.personaPrompt()))
    }

    @Test fun theWaitIsToldHonestly() {
        assertEquals("Jaina is answering.", ChatWait.thinking("Jaina", 1200, 1))
        assertEquals("Jaina is answering...", ChatWait.thinking("Jaina", 2999, 3))
        assertEquals("Jaina is thinking.. 3 s", ChatWait.thinking("Jaina", 3000, 2))
        assertEquals("Jaina is thinking. 12 s", ChatWait.thinking("Jaina", 12_400, 1))
        assertEquals("Loading the model... 42%", ChatWait.loading(0.42f))
        assertEquals("Loading the model...", ChatWait.loading(-1f))
    }

    // ---- the director -----------------------------------------------------------------------------------------------------

    private val a = Kit.npc("sylvanas", Kit.UNDEAD, true, 3, listOf("BRAVE"))
    private val b = Kit.npc("thrall", Kit.ORC, false, 7)
    private val c = Kit.npc("jaina", Kit.HUMAN, true, 2)

    @Test fun theCastIsReadAheadTheSelectedFirstAndTheNearestFewOnce() {
        val engine = FakeDialogueEngine("x")
        val d = DialogueDirector(engine, lore, Said().sink, DialogueSettings(), Random(2))
        val v = Kit.view(Kit.person(a, dist = 9f), Kit.person(b, dist = 2f), Kit.person(c, dist = 5f, selected = true),
            Kit.person(Kit.npc("d", Kit.DWARF), dist = 12f), Kit.person(Kit.npc("e", Kit.GNOME), dist = 15f), Kit.person(Kit.npc("f", Kit.TROLL), dist = 20f))
        Kit.run(d, v, 0.5f)
        val w = engine.warmed
        assertEquals("jaina", w[0].first, "the selected one first")
        assertTrue(w[0].third && w.drop(1).none { it.third }, "urgent for the selected, behind everything else for the rest")
        assertEquals(DialogueDirector.WARM_MAX, w.size)
        assertEquals(listOf("jaina", "thrall", "sylvanas", "d"), w.map { it.first }, "then by distance from the camera")
        assertEquals(PersonaPrompt.system(c, lore), w[0].second, "the very text its requests send")
        Kit.run(d, v, 3f)
        assertEquals(DialogueDirector.WARM_MAX, engine.warmed.size, "each is asked for once")
    }

    @Test fun theCastIsReadAgainWhenTheModelComesBack() {
        val engine = FakeDialogueEngine("x")
        val d = DialogueDirector(engine, lore, Said().sink, DialogueSettings(), Random(2))
        val v = Kit.view(Kit.person(a), Kit.person(b))
        engine.ready = false
        Kit.run(d, v, 1f)
        assertTrue(engine.warmed.isEmpty(), "no model, nothing to warm")
        engine.ready = true
        Kit.run(d, v, 1f)
        assertEquals(2, engine.warmed.size)
        engine.ready = false; Kit.run(d, v, 1f)
        engine.ready = true; Kit.run(d, v, 1f)
        assertEquals(4, engine.warmed.size, "a reloaded model has read nothing")
    }

    @Test fun nothingIsWarmedWithTheLayerSwitchedOff() {
        val engine = FakeDialogueEngine("x")
        val d = DialogueDirector(engine, lore, Said().sink, DialogueSettings(enabled = false), Random(2))
        Kit.run(d, Kit.view(Kit.person(a), Kit.person(b)), 2f)
        assertTrue(engine.warmed.isEmpty())
    }

    @Test fun aWrongLineOfAnUnpromptedNpcIsDroppedNotAskedAgain() {
        val engine = FakeDialogueEngine { "I left my phone at the inn, friend." }
        val said = Said()
        val d = DialogueDirector(engine, lore, said.sink, DialogueSettings(talk = Talkativeness.CHATTY), Random(2))
        Kit.run(d, Kit.view(Kit.person(a, "standing about", dist = 30f)), 300f)
        assertTrue(engine.requests.isNotEmpty())
        assertTrue(said.lines.isEmpty())
        // every wrong line was one request: nobody asked twice for the same line (a retry would carry the note)
        assertTrue(engine.requests.none { "do not use the word" in it.user }, "no retry for talk nobody asked for")
    }

    @Test fun aWrongLineOfAPairIsAskedAgainOnce() {
        val answers = ArrayDeque(listOf("I left my phone at the inn, friend.", "I left my purse at the inn, friend."))
        val engine = FakeDialogueEngine { answers.removeFirstOrNull() ?: "Hm." }
        val said = Said()
        val d = DialogueDirector(engine, lore, said.sink, DialogueSettings(), Random(2))
        Kit.run(d, Kit.pairInConversation(a, b), 6f)
        assertTrue(engine.requests.size >= 2 && "do not use the word \"phone\"" in engine.requests[1].user, "the pair's line was asked again with the note")
        assertTrue(said.lines.first().second.contains("purse"))
    }
}
