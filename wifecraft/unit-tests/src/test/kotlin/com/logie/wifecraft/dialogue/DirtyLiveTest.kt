package com.logie.wifecraft.dialogue

import com.logie.wifecraft.dialogue.Kit.lore
import com.logie.wifecraft.dialogue.Kit.npc
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.random.Random
import kotlin.test.Test

/**
 * Runs the exact prompts of the Dirty talk levels on a real model, for reading (the register of each level can only be judged by
 * eye). Does nothing unless DIRTY_GEN is set, so the ordinary test run skips it:
 *
 *   DIRTY_GEN=/path/gen2 DIRTY_MODEL=/path/model.gguf DIRTY_OUT=/path/out.txt [DIRTY_LEVELS=cute,spicy,...] [DIRTY_N=3] [DIRTY_THINKOFF=1] \
 *     [DIRTY_PARTS=joi,live,chat] bash android/gradlew -p unit-tests test --tests '*DirtyLiveTest*'
 *
 * gen2 is a small program that keeps the dialogue core (engine-test/dialogue_build.sh's library, README "The dialogue model") loaded and
 * answers one prompt a line: stdin "temperature TAB topP TAB repeat TAB maxTokens TAB prompt", stdout the answer, both with \n, \t and \\ escaped.
 * The prompt is rendered by the app's own [ChatTemplate]; the lines go through [JoiPrompt.line] with its retries.
 */
class DirtyLiveTest {
    private class GenEngine(gen: String, model: String, private val template: ChatTemplate) : DialogueEngine {
        private val proc = ProcessBuilder(gen, model).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        private val out = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)
        private val inp = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
        var calls = 0
        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
        private fun unesc(s: String): String { val o = StringBuilder(); var i = 0; while (i < s.length) { val c = s[i]; if (c == '\\' && i + 1 < s.length) { i++; o.append(when (s[i]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i] }) } else o.append(c); i++ }; return o.toString() }
        override suspend fun complete(req: DialogueRequest): String? = synchronized(this) {
            calls++
            val prompt = template.render(req.system, req.history, req.user)
            System.getenv("DIRTY_DUMP")?.let { File(it).writeText(prompt) }
            out.write("${req.temperature}\t${req.topP}\t${req.repeatPenalty}\t${req.maxTokens}\t${esc(prompt)}\n"); out.flush()
            val line = inp.readLine() ?: return null
            ChatTemplate.tidy(unesc(line))
        }
        fun close() { runCatching { out.close() }; proc.destroy() }
    }

    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }

    @Test fun realModel() {
        val gen = env("DIRTY_GEN") ?: return
        val model = env("DIRTY_MODEL") ?: error("DIRTY_MODEL")
        val outFile = File(env("DIRTY_OUT") ?: error("DIRTY_OUT"))
        val n = env("DIRTY_N")?.toInt() ?: 3
        val parts = (env("DIRTY_PARTS") ?: "joi,live,chat").split(',').toSet()
        val levels = env("DIRTY_LEVELS")?.split(',')?.map { DirtyLevel.of(it)!! } ?: DirtyLevel.entries
        val only = env("DIRTY_STAGES")?.split(',')?.toSet()
        val engine = GenEngine(gen, model, if (env("DIRTY_THINKOFF") != null) ChatTemplate.CHATML_NO_THINK else ChatTemplate.CHATML)
        val pack = KinkPack.load { File(System.getProperty("wifecraft.assets"), "dialogue/kink/$it").takeIf(File::exists)?.readText() }
        val sb = StringBuilder()
        fun say(s: String) { sb.appendLine(s); outFile.writeText(sb.toString()) }
        try {
            if ("joi" in parts) {
                val personas = listOf(
                    JoiPersona("Velira", listOf("draenei"), listOf("paladin"), setOf("teasing"), "dom", false, "", female = true),
                    JoiPersona("Gorrak", listOf("orc"), listOf("rogue"), setOf("dominant"), "dom", false, "", female = false),
                )
                val stages = listOf("setup", "warmup", "build", "edge", "hold", "countdown", "permit", "afterglow").filter { only == null || it in only }
                for (level in levels) for (p in personas.take(if (env("DIRTY_ONE") != null) 1 else 2)) {
                    say("\n=== JOI ${level.label} ${p.name} (${p.races[0]} ${p.classes[0]}) ===")
                    val recent = ArrayList<String>()
                    for (stage in stages) {
                        val got = ArrayList<String>()
                        for (i in 0 until n) {
                            val before = engine.calls
                            val action = if (stage == "warmup" || stage == "build") "your hand is stroking them, slowly" else null
                            val r = JoiLineRequest(p, stage, level.joiBand, emptySet(), recent.toList(), if (stage == "countdown") 5 else null, p.name, action, level)
                            val line = kotlinx.coroutines.runBlocking { JoiPrompt.line(engine, r, pack) }
                            val tries = engine.calls - before
                            if (line != null) { recent += line; while (recent.size > 6) recent.removeAt(0) }
                            got += (line ?: "(none)") + if (tries > 1) "   [$tries tries]" else ""
                        }
                        say("[$stage]")
                        for (l in got) say("  $l")
                    }
                }
            }
            val steer = env("DIRTY_STEERS")?.split('|').orEmpty()
            for (s in steer) {
                val p = JoiPersona("Velira", listOf("draenei"), listOf("paladin"), setOf("teasing"), "dom", false, "", female = true)
                for (level in listOf(DirtyLevel.HOT, DirtyLevel.NASTY)) {
                    say("\n=== STEER \"$s\" ${level.label} ===")
                    for (i in 0 until n) {
                        val r = JoiLineRequest(p, "build", level.joiBand, emptySet(), emptyList(), null, p.name, "your hand is stroking them, slowly", level, DirtyTalk.cleanSteer(s))
                        say("  " + (kotlinx.coroutines.runBlocking { JoiPrompt.line(engine, r, pack) } ?: "(none)"))
                    }
                }
            }
            val me = npc("velira", 11, true, 2, listOf("PLAYFUL"), name = "Velira")
            val partner = npc("p", Kit.ORC, false, 4, name = "Gorrak")
            if ("live" in parts) for (level in levels) {
                say("\n=== SEX-STAGE BANTER ${level.label} (Velira, in Passionate Missionary) ===")
                for (i in 0 until n * 2) {
                    val v = Kit.pairInStage(me, partner)
                    val user = TurnPrompt.build(lore, TurnPrompt.Input(me, v, TalkMode.LIVE, partner, ComfortBand.VERY_COMFORTABLE, true, emptyList(), stageName = "Passionate Missionary",
                        heard = if (i > 0) "Gorrak: Don't stop." else null, kink = pack, dirty = level))
                    val req = DialogueRequest(me.id, PersonaPrompt.system(me, lore), emptyList(), user, maxTokens = 70, temperature = level.temperature, topP = level.topP, repeatPenalty = level.repeatPenalty)
                    val raw = kotlinx.coroutines.runBlocking { engine.complete(req) }
                    say("  " + (LineCleaner.clean(raw, me.name) ?: "(none)"))
                }
            }
            if ("chat" in parts) for (level in levels) {
                say("\n=== CHAT FLIRT ${level.label} (Velira to a stranger turned friend) ===")
                val s = ChatSession(npc("velira", 11, true, 2, listOf("PLAYFUL"), name = "Velira"), ChatCharacter("Gorrak", 2, false, 4), lore, false, PlaceView("The Rusty Anchor"), pack, Random(5))
                s.dirty = level
                repeat(12) { s.comfort.chatted(s.npc.id, ChatCharacter.ID, 0.3f) }
                for (msg in listOf("You look incredible tonight. Tell me what you're thinking.", "I want you. Tell me what you'd do to me.", "Don't hold back, tell me everything.")) {
                    val reply = kotlinx.coroutines.runBlocking { s.say(msg, engine) }
                    say("  > $msg"); say("  ${reply ?: "(none)"}")
                }
            }
        } finally { engine.close() }
    }
}
