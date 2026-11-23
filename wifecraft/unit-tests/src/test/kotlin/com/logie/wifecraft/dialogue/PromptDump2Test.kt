package com.logie.wifecraft.dialogue

import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.random.Random
import kotlin.test.Test

/**
 * A developer tool, not a check: with WC_DUMP_PROMPTS2=<dir> it writes the cold JOI and Chat prompts (stable prefix, then the part
 * every request adds) as the model reads them, one file each for the engine-test's tokenizer (`tok MODEL FILES...`), for the
 * README's "Why replies are slow" table. [compact] false is the Quality model's prompt, true the Fast model's.
 */
class PromptDump2Test {
    private fun <T> run(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return out!!.getOrThrow()
    }

    @Test fun dump() {
        val dir = System.getenv("WC_DUMP_PROMPTS2")?.let(::File) ?: return
        dir.mkdirs()
        val compact = System.getenv("WC_COMPACT") == "1"
        val pack = KinkPack.load { File(System.getProperty("wifecraft.assets"), "dialogue/kink/$it").takeIf(File::exists)?.readText() }
        val velira = JoiPersona("Velira", listOf("draenei"), listOf("paladin"), setOf("teasing"), "dom", false, "A paladin of the Exodar who keeps her own counsel.", female = true)
        for (level in listOf(DirtyLevel.HOT, DirtyLevel.NASTY, DirtyLevel.WTF)) for (stage in listOf("build", "edge", "permit")) {
            val r = JoiLineRequest(velira, stage, Band.INTIMATE, emptySet(), listOf("Slower now. Let me watch you.", "Good. Keep that pace."), null, npcId = "velira", action = "your hand is stroking them, slowly", level = level, compact = compact)
            val q = JoiPrompt.request(r, pack)
            File(dir, "joi-${level.key}-$stage.txt").writeText(ChatTemplate.GEMMA.render(q.system, q.history, q.user))
            File(dir, "joi-${level.key}-$stage.prefix.txt").writeText(ChatTemplate.GEMMA.render(q.system, q.history, ""))
        }
        val approx = StringBuilder()
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".txt") && it.name != "approx.txt" }.sortedBy { it.name }) approx.append(f.name).append('\t').append(Tokens.approx(f.readText())).append('\n')
        val lore = Kit.lore
        val place = PlaceView("A House in Brill, Tirisfal", true, 21.0, null, listOf("a hearth"))
        val jaina = Kit.npc("jaina", Kit.HUMAN, true, 2, listOf("CALM", "BOOKISH"), "A mage of Dalaran, patient with fools and not with ignorance.", "Jaina Wren")
        val you = ChatCharacter("Brannok", Kit.UNDEAD, false, 9)
        val engine = FakeDialogueEngine("Well met, Brannok. The roads are long and the ale is thin tonight, but I have a seat by the fire.")
        engine.compact = compact
        val s = ChatSession(jaina, you, lore, false, place, pack, Random(1))
        run { s.say("Hello there.", engine) }
        val q = engine.requests.first()
        File(dir, "chat-first.txt").writeText(ChatTemplate.GEMMA.render(q.system, q.history, q.user))
        File(dir, "chat-persona.txt").writeText(ChatTemplate.GEMMA.render(s.personaPrompt(), emptyList(), "").substringBeforeLast("<|im_start|>assistant"))
        File(dir, "chat-prefix.txt").writeText(ChatTemplate.GEMMA.render(q.system, emptyList(), "").substringBeforeLast("<|im_start|>assistant"))
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".txt") && it.name != "approx.txt" }.sortedBy { it.name }) approx.append(f.name).append('\t').append(Tokens.approx(f.readText())).append('\n')
        File(dir, "approx.txt").writeText(approx.toString().lines().distinct().joinToString("\n"))
    }
}
