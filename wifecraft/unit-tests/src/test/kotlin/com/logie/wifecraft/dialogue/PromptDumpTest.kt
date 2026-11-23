package com.logie.wifecraft.dialogue

import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.random.Random
import kotlin.test.Test

/**
 * A developer tool, not a check: with WC_DUMP_PROMPTS=<dir> in the environment it writes the real prompts of typical requests
 * (a chat's first and later turns, a flirting turn, an ambient line, a JOI line) as the model reads them, one file each, with the
 * sections of the system and user messages separated by \u0001 so a tokenizer can count each (the desktop's `tok` tool over the
 * Mistral-Nemo vocabulary: README, "Why replies are slow"). Without the variable it does nothing.
 */
class PromptDumpTest {
    private fun <T> run(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    private fun write(dir: File, name: String, reqs: List<DialogueRequest>) {
        reqs.forEachIndexed { i, r ->
            val sections = ArrayList<String>()
            fun part(label: String, text: String) { text.lines().filter { it.isNotBlank() }.forEach { sections += "$label | ${it.take(70)}\u0002$it" } }
            part("system", r.system)
            r.history.forEachIndexed { k, t -> sections += "history ${k} ${t.role} | ${t.text.take(50)}\u0002${t.text}" }
            part("user", r.user)
            File(dir, "$name-${i + 1}.sections").writeText(sections.joinToString("\u0001") { it.substringAfter('\u0002') })
            File(dir, "$name-${i + 1}.labels").writeText(sections.joinToString("\n") { it.substringBefore('\u0002') })
            File(dir, "$name-${i + 1}.txt").writeText(ChatTemplate.CHATML.render(r.system, r.history, r.user))
        }
    }

    @Test fun dump() {
        val dir = System.getenv("WC_DUMP_PROMPTS")?.let(::File) ?: return
        dir.mkdirs()
        val lore = Kit.lore
        val pack = KinkPack.load { File(System.getProperty("wifecraft.assets"), "dialogue/kink/$it").takeIf(File::exists)?.readText() }
        val place = PlaceView("A House in Brill, Tirisfal", true, 21.0, null, listOf("a hearth"))
        val jaina = Kit.npc("jaina", Kit.HUMAN, true, 2, listOf("CALM", "BOOKISH"), "A mage of Dalaran, patient with fools and not with ignorance.", "Jaina Wren")
        val you = ChatCharacter("Brannok", Kit.UNDEAD, false, 9)
        // chat: three plain turns, then a flirting one
        val engine = FakeDialogueEngine("Well met, Brannok. The roads are long and the ale is thin tonight, but I have a seat by the fire.", "Mm. I have read a little about your people, and I would rather hear it from you.", "Careful. Flatter me again and I may start to believe it.", "Closer, then. Tell me what you would do with me.")
        val s = ChatSession(jaina, you, lore, false, place, pack, Random(1))
        run {
            for (said in listOf("Hello there.", "What do you do here?", "I have walked a long way.", "Do you know the roads north?", "Thank you, that helps.", "What do you make of the Horde?",
                "You are lovely.", "Tell me what you want, I want you in my bed.")) s.say(said, engine)
        }
        write(dir, "chat", engine.requests)
        // ambient: a pair in conversation, then the same pair later
        val a = Kit.npc("sylvanas", Kit.UNDEAD, true, 3, listOf("BRAVE"), "Bitter and proud.")
        val b = Kit.npc("thrall", Kit.ORC, false, 7, listOf("CALM"), "A warchief who listens.")
        val e2 = ScriptedEngine { "The wind is cold off the Sepulcher tonight, but we have borne worse." }
        val said = Said()
        val d = DialogueDirector(e2, lore, said.sink, DialogueSettings(), Random(2), pack)
        Kit.run(d, Kit.pairInConversation(a, b), 40f)
        write(dir, "ambient", e2.calls.take(3))
    }
}
