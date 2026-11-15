package com.logie.wifecraft.editor

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.story.Script
import com.logie.wifecraft.story.Step
import com.logie.wifecraft.story.StoryRunner
import com.logie.wifecraft.story.StoryState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditorTest {
    private fun look(race: Int) = Appearance(race, Sex.FEMALE, mapOf(1 to 2, 3 to 4))

    private fun sample(): Project {
        val st = { id: String -> Stage("st_$id", "Stage", "krasus", "dusk") }
        return Project("p1", "My \"tale\"", listOf(
            Actor("Mira-1", "Mira", look(4), "a ranger"), Actor("bob", "Bob", look(1))), listOf(
            Scene("a", "Start", st("a"), listOf(
                Beat.Show("Mira-1", "left", "bust"),
                Beat.Say(null, "talk", "It begins.\nNewline here."),
                Beat.Say("Mira-1", "laugh", "Hello, {name}."),
                Beat.Camera("wide"),
                Beat.Choice("Where?", listOf(Beat.Choice.Option("Left | path", "b"), Beat.Choice.Option("Right", "c"))),
            )),
            Scene("b", "Left", st("b"), listOf(
                Beat.Show("bob", "right", "two Mira-1"), Beat.Say("bob", "talk", "Hi."), Beat.Hide("bob"), Beat.Goto("c"))),
            Scene("c", "Right", st("c"), listOf(Beat.Say("Mira-1", "bow", "Bye."), Beat.End("The End!"))),
        ), "a")
    }

    @Test fun jsonRoundTrip() {
        val p = sample()
        assertEquals(p, Project.fromJson(org.json.JSONObject(p.toJson().toString())))
    }

    @Test fun storeSavesListsDeletes() {
        val dir = Files.createTempDirectory("wfs").toFile()
        val s = ProjectStore(dir)
        val n = s.newProject("New")
        assertEquals(n, s.load(n.id))
        s.save(sample())
        assertEquals(sample(), s.load("p1"))
        assertEquals(2, s.list().size)
        assertTrue(dir.walkTopDown().none { it.name.endsWith(".tmp") })
        java.io.File(dir, "bad").mkdirs(); java.io.File(dir, "bad/project.json").writeText("{")
        assertEquals(2, s.list().size)
        s.delete("p1")
        assertEquals(null, s.load("p1"))
        assertEquals(1, s.list().size)
        assertTrue(runCatching { s.load("../x") }.isFailure)
    }

    @Test fun validation() {
        assertEquals(emptyList(), Compile.validate(sample()))
        val p = sample()
        val bad = p.copy(start = "zzz", scenes = p.scenes + Scene("d", "D", p.scenes[0].stage, listOf(
            Beat.Say("ghost", "talk", "boo"), Beat.Goto("nowhere"), Beat.Choice("?", emptyList()),
            Beat.Choice("?", listOf(Beat.Choice.Option("", "a"))))))
        val e = Compile.validate(bad)
        assertTrue(e.any { "start scene" in it }, e.toString())
        assertTrue(e.any { "ghost" in it })
        assertTrue(e.any { "nowhere" in it })
        assertTrue(e.any { "no options" in it })
        assertTrue(e.any { "no text" in it })
        assertTrue(Compile.validate(p.copy(scenes = emptyList())).isNotEmpty())
    }

    @Test fun compilesParsesAndRunsToTheEnd() {
        val p = sample()
        val text = Compile.toScript(p)
        val script = Script.parse(text)
        for (pick in 0..1) {
            val r = StoryRunner(script, StoryState(), { "You" })
            var ending: String? = null
            val said = mutableListOf<String>()
            var guard = 0
            while (guard++ < 200) {
                when (val s = r.next()) {
                    is Step.Line -> said += "${s.who}:${s.text}"
                    is Step.Choose -> r.choose(pick)
                    is Step.Ending -> ending = s.id
                    Step.Finished -> break
                    else -> Unit
                }
            }
            assertEquals("the_end_", ending)
            assertTrue(said.first().startsWith("narrator:It begins. Newline here."), said.toString())
            assertTrue("p_mira__:Hello, You." in said)
            assertEquals(pick == 0, said.any { it == "p_bob:Hi." })
            assertEquals("p_mira__:Bye.", said.last())
        }
        assertNotNull(Regex("^cast p_mira__ left face camera$", RegexOption.MULTILINE).find(text))
        assertTrue("  > Left / path | goto s_b" in text)
    }

    @Test fun theCastByScriptNameAndTheEndingsTitle() {
        val p = sample()
        val cast = Compile.cast(p)
        assertEquals(setOf("p_mira__", "p_bob"), cast.keys)
        assertEquals("Mira", cast.getValue("p_mira__").name)
        assertEquals(look(4), cast.getValue("p_mira__").appearance)
        val text = Compile.toScript(p)
        assertTrue("ending the_end_ The End!\nfinish" in text, text)
        // every name the script gives a person is one the cast map has
        val people = Script.parse(text).commands.mapNotNull { (it as? com.logie.wifecraft.story.Command.Say)?.who }.filter { it != "narrator" }
        assertTrue(cast.keys.containsAll(people))
    }

    @Test fun awkwardIdsStayDistinctAndSafe() {
        val a = Actor("scene", "S", look(1)); val b = Actor("Scene", "S2", look(1)); val c = Actor("7", "n", look(1))
        val p = Project("x", "t", listOf(a, b, c), listOf(Scene("1", "one", Stage("s", "s", "studio", "night"),
            listOf(Beat.Show("scene", "center", ""), Beat.Say("Scene", "talk", "x"), Beat.Say("7", "talk", "y")))), "1")
        val script = Script.parse(Compile.toScript(p))   // would throw if "scene:" were read as a command
        assertTrue(script.commands.size > 5)
    }
}
