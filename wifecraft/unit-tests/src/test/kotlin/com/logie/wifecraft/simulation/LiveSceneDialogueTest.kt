package com.logie.wifecraft.simulation

import com.logie.wifecraft.dialogue.DialogueDirector
import com.logie.wifecraft.dialogue.DialogueEngine
import com.logie.wifecraft.dialogue.Kit
import com.logie.wifecraft.dialogue.PlaceView
import com.logie.wifecraft.dialogue.ScriptedEngine
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The dialogue layer in a live scene: its lines replace the scripted talk; with no model, or switched off, the scripted talk is as before. */
class LiveSceneDialogueTest {
    private val speakers = mapOf("a" to Speaker("Jaina Proudmoore", 1, true), "b" to Speaker("Varimathras", 5, false))

    private fun run(engine: DialogueEngine?, cinematic: Boolean = false, seconds: Int = 90): List<Pair<Int, String>> {
        val live = LiveScene(listOf("a", "b"), emptySet(), emptyMap(), CameraPose(Vec3(0f, -5f, 2f), Vec3(0f, 0f, 1f), 50f),
            0f, 0f, 0f, 0f, Random(4), wanderOnly = true, chatter = Chatter(speakers, "Brill"), chatterChance = 1f)
        val cast = mapOf("a" to Kit.npc("a", 1, true, 2, name = "Jaina Proudmoore"), "b" to Kit.npc("b", 5, false, 3, name = "Varimathras"))
        val director = DialogueDirector({ engine }, Kit.lore, live.speechSink(), random = Random(2))
        live.dialogue = LiveScene.DialogueFeed(director, cast, PlaceView("A House in Brill, Tirisfal"), { cinematic })
        live.running = true
        val said = ArrayList<Pair<Int, String>>()
        val sink = object : LiveScene.Sink {
            override fun camera(eyeX: Float, eyeY: Float, eyeZ: Float, atX: Float, atY: Float, atZ: Float, fov: Float) {}
            override fun place(index: Int, x: Float, y: Float, z: Float, facing: Float, furniture: SceneObject?, sx: Float, sy: Float, sz: Float) {}
            override fun activity(index: Int, activity: Activity) {}
            override fun speech(index: Int, text: String?, id: Long) { if (text != null) said += index to text }
        }
        var now = 0L
        repeat(60 * seconds) { now += 16_666_667L; live.frame(now, sink) {} }
        return said
    }

    @Test fun theModelsLinesAreTheOnlyWordsSpoken() {
        val engine = ScriptedEngine { "Aye, the night is long." }
        val said = run(engine)
        assertTrue(said.size >= 2, "$said"); assertTrue(said.all { it.second == "Aye, the night is long." }, "no scripted lines: $said")
        assertTrue(said.any { it.first == 0 } && said.any { it.first == 1 }, "both speak")
        assertTrue(engine.calls.all { "Brill" in it.user && "Varimathras" in it.user + it.system || "Jaina" in it.user })
    }

    @Test fun withNoModelTheScriptedTalkIsAsBefore() {
        val said = run(null)
        assertTrue(said.size >= 4, "$said"); assertTrue(said.none { it.second == "Aye, the night is long." })
    }

    @Test fun cinematicModeSilencesTheLayerEntirely() {
        val engine = ScriptedEngine { "Aye." }
        assertTrue(run(engine, cinematic = true).isEmpty()); assertTrue(engine.calls.isEmpty())
    }

    @Test fun theSimulationsBubblesAreItsOwnCall() {
        val sim = SceneSimulation(listOf("a"), emptySet(), Random(1), wanderOnly = true)
        assertEquals(true, sim.say("a", "Aye.")); assertEquals("Aye.", sim.speech("a"))
    }
}
