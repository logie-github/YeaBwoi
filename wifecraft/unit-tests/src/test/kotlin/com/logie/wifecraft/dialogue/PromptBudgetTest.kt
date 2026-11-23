package com.logie.wifecraft.dialogue

import org.json.JSONObject
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The compact prompts of the Fast model and what they cost (README, "Why replies are slow"). The budgets are in [Tokens.approx] units, which
 * run about 5 to 8 % above the Qwen3 tokenizer's real count (measured here: the compact JOI prefix 343 real against 362 approximate, the compact
 * chat persona 294 against 314, the full JOI prefix 716 against 773): a stable prefix of 400 approximate tokens is about 350 real ones.
 */
class PromptBudgetTest {
    private val assets = File(System.getProperty("wifecraft.assets"))
    private val pack = KinkPack.load { n -> File(assets, "dialogue/kink/$n").takeIf { it.exists() }?.readText() }
    private val velira = JoiPersona("Velira", listOf("draenei"), listOf("paladin"), setOf("teasing"), "dom", false, "A paladin of the Exodar who keeps her own counsel.", female = true)
    private val stages = listOf("setup", "warmup", "build", "edge", "hold", "countdown", "permit", "afterglow")

    private fun req(level: DirtyLevel, stage: String, compact: Boolean, recent: List<String> = listOf("Slower now. Let me watch you.", "Good. Keep that pace."), steer: Set<JoiSteer> = emptySet(),
        action: String? = "your hand is stroking them, slowly") =
        JoiLineRequest(velira, stage, Band.INTIMATE, steer, recent, if (stage == "countdown") 5 else null, npcId = "velira", action = action, level = level, compact = compact)

    private fun prefix(q: DialogueRequest) = ChatTemplate.GEMMA.render(q.system, q.history, "")
    private fun whole(q: DialogueRequest) = ChatTemplate.GEMMA.render(q.system, q.history, q.user)

    @Test fun theCompactJoiPrefixIsTheSameThroughEveryStageAndEveryLine() {
        for (level in DirtyLevel.entries) {
            val first = JoiPrompt.request(req(level, "setup", true, emptyList(), emptySet(), null), pack)
            for (stage in stages) for (extra in listOf(emptySet(), setOf(JoiSteer.TEASE, JoiSteer.SLOWER))) {
                val q = JoiPrompt.request(req(level, stage, true, steer = extra), pack)
                assertEquals(first.system, q.system, "$level/$stage system")
                assertEquals(first.history, q.history, "$level/$stage examples")
                assertEquals(JoiPrompt.engineKey(req(level, "setup", true)), q.npcId)
            }
        }
    }

    @Test fun theFullJoiPrefixStillMovesWithTheStageAsBefore() {
        val a = JoiPrompt.request(req(DirtyLevel.HOT, "build", false), pack); val b = JoiPrompt.request(req(DirtyLevel.HOT, "permit", false), pack)
        assertNotEquals(a.history, b.history)
        assertEquals(JoiPrompt.EXAMPLES, a.history.size / 2)
    }

    @Test fun theCompactJoiPrefixIsAboutHalfTheFullOneAndUnderTheBudget() {
        for (level in DirtyLevel.entries) for (stage in stages) {
            val compact = Tokens.approx(prefix(JoiPrompt.request(req(level, stage, true), pack)))
            val full = Tokens.approx(prefix(JoiPrompt.request(req(level, stage, false), pack)))
            assertTrue(compact <= PREFIX_BUDGET, "$level/$stage: compact prefix $compact")
            assertTrue(compact * 100 <= full * 62, "$level/$stage: compact $compact against full $full")
        }
    }

    @Test fun aCompactJoiLineReadsAtMostAboutEightyNewTokens() {
        var worst = 0
        for (level in DirtyLevel.entries) for (stage in stages) for (steer in listOf(emptySet(), setOf(JoiSteer.DESCRIBE))) {
            val q = JoiPrompt.request(req(level, stage, true, steer = steer), pack)
            val fresh = Tokens.approx(whole(q)) - Tokens.approx(prefix(q))
            worst = maxOf(worst, fresh)
            assertTrue(fresh <= NEW_BUDGET + if (steer.isEmpty()) 0 else 22, "$level/$stage/$steer: $fresh new tokens\n${q.user}")
        }
        assertTrue(worst > 40, "the measure sees the last message ($worst)")
    }

    @Test fun theLastMessageCarriesTheStageNotThePrefix() {
        val q = JoiPrompt.request(req(DirtyLevel.HOT, "permit", true), pack)
        assertTrue(q.user.contains("lets them cum"), q.user)
        assertFalse(q.system.contains("permit"))
        val c = JoiPrompt.request(req(DirtyLevel.HOT, "countdown", true), pack)
        assertTrue(c.user.contains("count down from 5"), c.user)
    }

    @Test fun joiTokenCapsAndSentenceStops() {
        for (level in DirtyLevel.entries) {
            val c = JoiPrompt.request(req(level, "build", true), pack)
            assertEquals(40, c.maxTokens); assertEquals(3, c.sentences)
            assertTrue(c.maxTokens in 30..40, "a JOI line is 30 to 40 tokens")
            val f = JoiPrompt.request(req(level, "build", false), pack)
            assertEquals(level.joiTokens, f.maxTokens); assertEquals(0, f.sentences)
        }
        assertEquals(28, JoiPrompt.request(req(DirtyLevel.HOT, "countdown", true), pack).maxTokens, "the line before a count is short")
        assertEquals(listOf("\n"), JoiPrompt.request(req(DirtyLevel.HOT, "build", true), pack).stop)
    }

    @Test fun chatLengthsCapAt30And60And100Tokens() {
        assertEquals(30, ChatLength.SHORT.tokens); assertEquals(60, ChatLength.NORMAL.tokens); assertEquals(100, ChatLength.LONG.tokens)
        assertTrue(ChatLength.SHORT.tokens in 25..30 && ChatLength.NORMAL.tokens in 45..60 && ChatLength.LONG.tokens <= 100)
        // the words asked for fit the tokens: about 1.4 tokens a word
        for (l in ChatLength.entries) assertTrue(l.words * 1.45 <= l.tokens + 8, "${l.label}: ${l.words} words in ${l.tokens} tokens")
    }

    private val lore get() = Kit.lore
    private val place = PlaceView("A House in Brill, Tirisfal", true, 21.0, null, listOf("a hearth"))
    private val jaina = Kit.npc("jaina", Kit.HUMAN, true, 2, listOf("CALM", "BOOKISH"), "A mage of Dalaran, patient with fools and not with ignorance.", "Jaina Wren")
    private val you = ChatCharacter("Brannok", Kit.UNDEAD, false, 9)

    private fun chatRequests(compact: Boolean, says: List<String>): List<DialogueRequest> {
        val engine = FakeDialogueEngine("Well met, Brannok. The roads are long and the ale is thin tonight.")
        engine.compact = compact
        val s = ChatSession(jaina, you, lore, false, place, pack, Random(1))
        kotlinx.coroutines.runBlocking { for (t in says) s.say(t, engine) }
        return engine.requests
    }

    @Test fun theCompactChatPersonaAndFirstTurnAreUnderTheBudget() {
        val q = chatRequests(true, listOf("Hello there.")).first()
        val persona = Tokens.approx(q.system.substringBefore("You are in "))
        val prefix = Tokens.approx(q.system)
        val first = Tokens.approx(q.user)
        assertTrue(persona <= 330, "persona $persona")
        assertTrue(prefix <= 450, "persona and context $prefix")
        assertTrue(first <= 100, "the first turn's message $first")
        val full = chatRequests(false, listOf("Hello there.")).first()
        assertTrue(prefix * 100 <= Tokens.approx(full.system) * 72, "compact $prefix against full ${Tokens.approx(full.system)}")
        assertTrue(first < Tokens.approx(full.user))
    }

    @Test fun laterCompactChatTurnsReadFewerThanEightyNewTokens() {
        val reqs = chatRequests(true, listOf("Hello there.", "What do you do here?", "I have walked a long way.", "Do you know the roads north?", "Thank you, that helps."))
        for ((i, q) in reqs.withIndex()) {
            assertEquals(reqs.first().system, q.system, "the system prompt is stable through the talk (turn $i)")
            assertTrue(Tokens.approx(q.user) <= if (i == 0) 100 else 90, "turn $i: ${Tokens.approx(q.user)} tokens\n${q.user}")
        }
        assertEquals(ChatLength.NORMAL.tokens, reqs.first().maxTokens)
    }

    @Test fun theCompactPromptFollowsTheEngineAndTheFullOneStaysForTheBigModel() {
        val a = chatRequests(true, listOf("Hi.")).first(); val b = chatRequests(false, listOf("Hi.")).first()
        assertTrue(a.system.contains(PersonaPrompt.RULES_COMPACT)); assertFalse(a.system.contains(PersonaPrompt.RULES_CORE))
        assertTrue(b.system.contains(PersonaPrompt.RULES_CORE)); assertFalse(b.system.contains(PersonaPrompt.RULES_COMPACT))
        assertTrue(Tokens.approx(PersonaPrompt.RULES_COMPACT) * 2 <= Tokens.approx(PersonaPrompt.RULES_CORE) + 10)
    }

    @Test fun aNamedNpcsCompactPersonaStaysUnderTheBudgetInEveryEra() {
        val read = { p: String -> File(assets, p).takeIf { it.isFile }?.readText() }
        val kit = checkNotNull(PersonaKit.load({ d -> File(assets, d).list()?.toList().orEmpty() }, read))
        val l = Lore(read("dialogue/lore.json")!!).also { it.kit = kit }
        var worst = 0
        for (p in kit.profiles.values) for (e in kit.eras) {
            kit.setEra(e.id)
            val npc = NpcProfile(p.id, p.name, Kit.HUMAN, true, 0, named = p.id)
            val t = Tokens.approx(PersonaPrompt.persona(npc, l, compact = true))
            worst = maxOf(worst, t)
            assertTrue(t <= 380, "${p.id}/${e.id}: $t")
        }
        assertTrue(worst > 150)
    }

    private companion object {
        /** Approximate tokens: about 350 real ones (the README's table). */
        const val PREFIX_BUDGET = 400
        const val NEW_BUDGET = 90
    }
}
