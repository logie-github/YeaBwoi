package com.logie.wifecraft.dialogue

import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Why replies were slow, in tests: the model reads the whole prompt after the last token it already holds for that NPC, so the
 * prompt must be laid out so that everything stable comes first and the new words last, and it must stay small. The real token
 * counts (Mistral Nemo's tokenizer) were measured with the dump tool (PromptDumpTest and the README's "Why replies are slow"); here
 * the same properties are pinned on the text, where a character is about 3.9 tokens' worth of 1 (measured: 3.9 characters a token).
 */
class PromptCacheTest {
    private val lore = Kit.lore
    private val kink = KinkPack.load { File(System.getProperty("wifecraft.assets"), "dialogue/kink/$it").takeIf(File::exists)?.readText() }
    private val place = PlaceView("A House in Brill, Tirisfal", true, 21.0, null, listOf("a hearth"))
    private val jaina = Kit.npc("jaina", Kit.HUMAN, true, 2, listOf("CALM", "BOOKISH"), "A mage of Dalaran, patient with fools and not with ignorance.", "Jaina Wren")
    private val you = ChatCharacter("Brannok", Kit.UNDEAD, false, 9)

    private fun <T> run(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    private fun lcp(a: String, b: String): Int { var i = 0; while (i < a.length && i < b.length && a[i] == b[i]) i++; return i }
    private fun render(r: DialogueRequest) = ChatTemplate.CHATML.render(r.system, r.history, r.user)

    /** A chat of [n] plain messages, then the requests it made. */
    private fun chat(vararg said: String): List<DialogueRequest> {
        val engine = FakeDialogueEngine("Well met, Brannok. The roads are long and the ale is thin tonight, but there is a seat by the fire.", "I have read a little of your people, and I would rather hear it from you.")
        val s = ChatSession(jaina, you, lore, false, place, kink, Random(1))
        run { for (m in said) s.say(m, engine) }
        return engine.requests
    }

    @Test fun theSystemPromptIsTheSameBytesEveryTurn() {
        val reqs = chat("Hello there.", "What do you do here?", "I have walked a long way.", "Do you know the roads north?")
        assertEquals(1, reqs.map { it.system }.toSet().size, "one system prompt for the whole talk: nothing in it moves from turn to turn")
    }

    @Test fun eachTurnStartsWithEverythingThePreviousTurnSent() {
        val reqs = chat("Hello there.", "What do you do here?", "I have walked a long way.", "Do you know the roads north?", "Thank you, that helps.")
        val prompts = reqs.map(::render)
        for (i in 1 until prompts.size) {
            // what the model already holds is the previous prompt up to where its generated reply starts; the reply is cleaned, and the new
            // turn then follows it: the previous prompt minus its ending "<|im_start|>assistant\n" is a prefix of the next
            val held = prompts[i - 1].removeSuffix("<|im_start|>assistant\n")
            val shared = lcp(held, prompts[i])
            assertEquals(held.length, shared, "turn ${i + 1}: the whole previous prompt is a prefix of this one (shared $shared of ${held.length})")
        }
        // so what is new each turn is the reply, the new message and the closing mark: a small part of the prompt (measured with the real tokenizer: 7%)
        for (i in 3 until prompts.size) {   // from the fourth turn: the opening stance (three turns) is over
            val newPart = prompts[i].length - prompts[i - 1].removeSuffix("<|im_start|>assistant\n").length
            assertTrue(newPart * 100 <= prompts[i].length * 12, "turn ${i + 1}: new ${newPart} of ${prompts[i].length} characters is at most an eighth")
        }
    }

    @Test fun theSharedPrefixOfTwoConsecutivePromptsIsAtLeastNinetyPercent() {
        val prompts = chat("Hello there.", "What do you do here?", "I have walked a long way.", "Do you know the roads north?", "Thank you, that helps.", "What do you make of the Horde?").map(::render)
        for (i in 2 until prompts.size) {
            val frac = lcp(prompts[i - 1], prompts[i]).toDouble() / prompts[i - 1].length
            assertTrue(frac >= 0.9, "turn ${i + 1}: ${(frac * 100).toInt()}% of the previous prompt is shared")
        }
    }

    @Test fun theHistoryIsDroppedInBlocksNotOneTurnAtATime() {
        val engine = FakeDialogueEngine("Aye, well met.")
        val s = ChatSession(jaina, you, lore, false, place, null, Random(1))
        val sizes = ArrayList<Int>()
        run { repeat(12) { s.say("again $it", engine); sizes += s.history().size } }
        assertTrue(sizes.all { it <= ChatSession.HISTORY }, "$sizes")
        // the history only ever grows by two until it is cut to the keep size in one step: a prefix the model has read stays put in between
        for (i in 1 until sizes.size) assertTrue(sizes[i] == sizes[i - 1] + 2 || sizes[i] <= ChatSession.HISTORY_KEEP + 1, "$sizes")
        val reqs = engine.requests
        val cutAt = (1 until reqs.size).filter { reqs[it].history.size < reqs[it - 1].history.size }
        assertTrue(cutAt.size in 1..3, "twelve turns cut the history ${cutAt.size} times, not every turn: $cutAt")
        assertTrue(reqs.all { it.history.isEmpty() || it.history.first().role == DialogueTurn.Role.USER }, "the history always starts with what they said")
    }

    @Test fun whenTheHistoryIsCutTheMessagesThatStayGoBackToWhatWasSaid() {
        val engine = FakeDialogueEngine("Aye, well met.")
        val s = ChatSession(jaina, you, lore, false, place, null, Random(1))
        val said = (1..9).map { "line $it" }
        run { for (m in said) s.say(m, engine) }
        val reqs = engine.requests
        val cut = (1 until reqs.size).first { reqs[it].history.size < reqs[it - 1].history.size }
        val after = reqs[cut].history
        assertTrue(after.filter { it.role == DialogueTurn.Role.USER }.all { u -> said.any { it == u.text } }, "short form after the cut: ${after.map { it.text.take(20) }}")
        // and from the next turn on the new messages are repeated as they were sent again
        val next = reqs[cut + 1].history
        assertTrue(next.last { it.role == DialogueTurn.Role.USER }.text.length > 60, "the turn sent after the cut is kept as sent")
        assertTrue(render(reqs[cut + 1]).startsWith(render(reqs[cut]).removeSuffix("<|im_start|>assistant\n")), "and the cache holds from there")
    }

    @Test fun aFlirtingTurnIsKeptSmallInTheHistory() {
        val reqs = chat("Hello there.", "What do you do here?", "I have walked a long way.", "Tell me what you want, I want you in my bed.", "And what else?")
        val last = reqs.last()
        // the desire turn's message (the kink notes, the register) is big: it is in the history as what the player said, not as sent
        assertTrue(reqs[3].user.length > ChatSession.FAITHFUL_CHARS, "that turn's message was big (${reqs[3].user.length} characters)")
        assertTrue(last.history.none { it.text.length > ChatSession.FAITHFUL_CHARS }, "no big message in the history")
        assertTrue(last.history.any { it.text == "Tell me what you want, I want you in my bed." })
    }

    @Test fun theSharedWorldAndRulesComeFirstInEveryPersonaSoANewNpcBorrowsThem() {
        val a = PersonaPrompt.system(Kit.npc("sylvanas", Kit.UNDEAD, true, 3, listOf("BRAVE")), lore)
        val b = PersonaPrompt.system(Kit.npc("thrall", Kit.ORC, false, 7, listOf("CALM")), lore)
        val c = ChatPrompt.system(Kit.npc("jaina", Kit.HUMAN, true, 2), lore, false)
        val d = ChatPrompt.system(Kit.npc("sindragosa", Kit.HUMAN, true, 0), lore, true)
        for (p in listOf(a, b, c, d)) assertTrue(p.startsWith(PersonaPrompt.shared(lore) + "\n\n"), "starts with the shared preamble")
        assertTrue(lcp(a, b) >= PersonaPrompt.shared(lore).length, "two NPCs share at least the preamble")
        assertFalse(a.startsWith("You are Sylvanas"), "no NPC's own name opens the prompt")
    }

    @Test fun anNpcsChatPersonaStartsWithItsAmbientPersona() {
        val npc = Kit.npc("jaina", Kit.HUMAN, true, 2, listOf("CALM"), "A mage of Dalaran.", "Jaina Wren")
        val ambient = PersonaPrompt.persona(npc, lore)
        assertTrue(PersonaPrompt.system(npc, lore).startsWith(ambient), "the ambient system is the persona and a closing line")
        assertTrue(ChatPrompt.system(npc, lore, false).startsWith(ambient), "so what the model read for her ambient talk it borrows for her chat, and the other way")
    }

    @Test fun theChatContextIsPartOfTheSystemPromptAndChangesOnlyWithWhatTheyAreToHer() {
        val s = ChatSession(jaina, you, lore, false, place, null, Random(1))
        val before = s.systemPrompt()
        assertTrue(before.startsWith(s.personaPrompt()))
        assertTrue("Brannok" in before && "A House in Brill" in before, "who stands before her and where")
        assertEquals(before, s.systemPrompt(), "stable")
        s.changeRelationship(ChatRelationship(ChatRelationship.Kind.FRIEND))
        assertFalse(before == s.systemPrompt(), "a friend is told differently: the system prompt is built again, once")
        assertTrue(s.systemPrompt().startsWith(s.personaPrompt()))
    }

    // ---- the budget: sizes of what a request carries ------------------------------------------------------------------------

    private fun tokens(text: String) = text.length / 3.9   // measured over this app's prompts with Mistral Nemo's tokenizer

    @Test fun anOrdinaryChatTurnAddsLittleToWhatTheModelHolds() {
        val reqs = chat("Hello there.", "What do you do here?", "I have walked a long way.", "Do you know the roads north?", "Thank you, that helps.")
        // tokens the model reads for turn 5 beyond the previous prompt: her reply, the new message
        val prev = render(reqs[3]).removeSuffix("<|im_start|>assistant\n")
        val now = render(reqs[4])
        assertTrue(tokens(now.length.let { now.substring(prev.length) }) <= 150, "a later turn reads fewer than 150 new tokens")
        assertTrue(reqs[4].user.length <= 480, "an ordinary turn's message is short: ${reqs[4].user.length} characters")
    }

    @Test fun theChatContextCarriesAtMostTwoLoreSnippets() {
        val s = ChatSession(jaina, you, lore, false, place, null, Random(1))
        val know = s.systemPrompt().lines().firstOrNull { it.startsWith("What you know: ") }.orEmpty()
        assertTrue(know.length <= "What you know: ".length + ChatPrompt.KNOW_CHARS + 120, "${know.length} characters")
        assertTrue(Regex("Where you are:|How your kind looks on theirs:|Another here is|Their calling").findAll(know).count() <= ChatPrompt.KNOW_ITEMS, know)
    }

    @Test fun anUnpromptedLinesMessageIsCappedToo() {
        val a = Kit.npc("sylvanas", Kit.UNDEAD, true, 3, listOf("BRAVE"), "Bitter and proud.")
        val b = Kit.npc("thrall", Kit.ORC, false, 7, listOf("CALM"))
        val c = Kit.npc("d", Kit.HUMAN, false, 1, name = "Dolan"); val d = Kit.npc("e", Kit.DWARF, false, 1, name = "Eitri"); val e = Kit.npc("f", Kit.GNOME, false, 8, name = "Fizz")
        val v = Kit.view(Kit.person(a, "talking", b.id, conversation = true, leads = true), Kit.person(b, "talking", a.id, conversation = true),
            Kit.person(c, "by the fire", dist = 2f), Kit.person(d, "at the bar", dist = 3f), Kit.person(e, "at the door", dist = 4f))
        val engine = ScriptedEngine { "Hm." }
        Kit.run(DialogueDirector(engine, lore, Said().sink, DialogueSettings(), Random(2), kink), v, 4f)
        val u = engine.calls.first().user
        assertTrue(u.length <= 1100, "an ambient message is at most about 280 tokens even with five people about: ${u.length} characters")
        assertTrue(u.lines().count { it.startsWith("- ") } <= TurnPrompt.PRESENT_MAX, "at most ${TurnPrompt.PRESENT_MAX} others listed")
    }

    @Test fun theLengthSettingCapsTheTokensAndTheSentences() {
        assertEquals(listOf(45, 80, 140), ChatLength.entries.map { it.tokens }, "Short, Normal, Long")
        assertEquals(listOf(1, 2, 3), ChatLength.entries.map { it.sentences })
        for (l in ChatLength.entries) {
            val r = ChatPrompt.request("sys", emptyList(), "hi", "jaina", l, null)
            assertEquals(l.tokens, r.maxTokens); assertEquals(l.sentences, r.sentences); assertEquals("chat", r.tag); assertEquals(DialoguePriority.SELECTED, r.priority)
            // the cap leaves room for the words the length asks for (about 1.4 tokens a word with the punctuation)
            assertTrue(l.tokens >= (l.words * 1.4).toInt(), "${l.label}: ${l.tokens} tokens for ${l.words} words")
        }
    }

    @Test fun anUnpromptedLineIsCappedAtFortyTokensAndTwoSentencesAndOnlyAPairOrTheWatchedOneRunsOn() {
        val a = Kit.npc("sylvanas", Kit.UNDEAD, true, 3, listOf("BRAVE")); val b = Kit.npc("thrall", Kit.ORC, false, 7)
        // alone and idle: a remark to the room, the lowest urgency
        val alone = Kit.view(Kit.person(a, "standing about", dist = 30f))
        val e1 = ScriptedEngine { "Hm." }
        Kit.run(DialogueDirector(e1, lore, Said().sink, DialogueSettings(talk = Talkativeness.CHATTY), Random(2)), alone, 600f)
        val q = e1.calls.first()
        assertEquals(40, q.maxTokens); assertEquals(2, q.sentences); assertEquals("speech", q.tag)
        // a pair, or the one the player has selected: a little more room
        val pair = Kit.view(Kit.person(a, "talking", b.id, conversation = true, leads = true, dist = 30f), Kit.person(b, "talking", a.id, conversation = true, dist = 30f))
        val e2 = ScriptedEngine { "Hm." }
        Kit.run(DialogueDirector(e2, lore, Said().sink, DialogueSettings(), Random(2)), pair, 4f)
        assertEquals(56, e2.calls.first().maxTokens)
        val near = Kit.view(Kit.person(a, "talking", b.id, conversation = true, leads = true, selected = true), Kit.person(b, "talking", a.id, conversation = true))
        val e3 = ScriptedEngine { "Hm." }
        Kit.run(DialogueDirector(e3, lore, Said().sink, DialogueSettings(), Random(2)), near, 4f)
        assertEquals(56, e3.calls.first().maxTokens, "the one the player watches may run on a little")
    }
}
