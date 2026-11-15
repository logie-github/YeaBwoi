package com.logie.wifecraft.editor

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.story.Command
import com.logie.wifecraft.story.Script
import com.logie.wifecraft.story.ScriptException
import com.logie.wifecraft.story.Step
import com.logie.wifecraft.story.StoryRunner
import com.logie.wifecraft.story.StoryState
import com.logie.wifecraft.ui.BeatKind
import com.logie.wifecraft.ui.EditorState
import com.logie.wifecraft.ui.Staging
import com.logie.wifecraft.ui.describe
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Pose beat (a library animation held by an actor): saves, compile, validation, the script command, the editor's state. */
class PoseBeatTest {
    private val look = Appearance(4, Sex.FEMALE, mapOf(1 to 2))
    private fun scene(vararg b: Beat) = Scene("a", "A", Stage("st", "S", "studio", "day"), b.toList())
    private fun project(vararg b: Beat) = Project("p", "T", listOf(Actor("mira", "Mira", look), Actor("bob", "Bob", look)), listOf(scene(*b)), "a")

    @Test fun jsonRoundTripAndOldSavesStillLoad() {
        val p = project(Beat.Show("mira", "left", "bust"), Beat.Pose("mira", "ab12cd"), Beat.Say("mira", "anim:ab12cd", "Hi."))
        assertEquals(p, Project.fromJson(JSONObject(p.toJson().toString())))
        assertEquals("pose", p.toJson().getJSONArray("scenes").getJSONObject(0).getJSONArray("beats").getJSONObject(1).getString("type"))
        // a save from before poses (no pose beat anywhere) reads as it did
        val old = """{"id":"p","title":"T","start":"a","cast":[],"scenes":[{"id":"a","name":"A","stage":{"id":"s","name":"S","place":"studio","light":"day"},
            "beats":[{"type":"say","who":null,"emote":"talk","text":"x"}]}]}"""
        assertEquals(Beat.Say(null, "talk", "x"), Project.fromJson(JSONObject(old)).scenes[0].beats[0])
    }

    @Test fun compilesToAPoseCommandThatParsesAndPlaysAsStaging() {
        val p = project(Beat.Show("mira", "left", "bust"), Beat.Pose("mira", "ab12"), Beat.Say("mira", "anim:cd34", "Hi."), Beat.Say("mira", "laugh", "Ha."))
        val text = Compile.toScript(p)
        assertTrue("pose p_mira ab12\n" in text, text)
        assertTrue("pose p_mira cd34\np_mira: Hi.\n" in text, text)
        assertTrue("p_mira [laugh]: Ha." in text)
        val cmds = Script.parse(text).commands.filterIsInstance<Command.Pose>()
        assertEquals(listOf("ab12", "cd34"), cmds.map { it.animId })
        assertEquals(listOf("p_mira", "p_mira"), cmds.map { it.who })
        val r = StoryRunner(Script.parse(text), StoryState())
        val seen = mutableListOf<Command>()
        var guard = 0
        while (guard++ < 50) {
            val s = r.next()
            if (s is Step.Stage) seen += s.command
            if (s === Step.Finished) break
        }
        assertEquals(2, seen.count { it is Command.Pose })
        assertEquals(2, r.stageCommands().count { it is Command.Pose })   // the stage restores them (a loaded game, a back-step)
    }

    @Test fun poseScriptLineNeedsWhoAndId() {
        assertIs<Command.Pose>(Script.parse("pose p_a x1").commands[0])
        assertFailsWith<ScriptException> { Script.parse("pose p_a") }
        assertFailsWith<ScriptException> { Script.parse("pose p_a one two") }
    }

    @Test fun validationNamesAMissingAnimation() {
        val p = project(Beat.Pose("mira", "gone"), Beat.Pose("ghost", "here"), Beat.Pose("mira", ""), Beat.Say(null, "anim:here", "x"), Beat.Say("bob", "anim:gone", "x"))
        val e = Compile.validate(p, setOf("here"))
        assertTrue(e.any { "step 1" in it && "\"gone\" isn't in the library" in it }, e.toString())
        assertTrue(e.any { "ghost" in it })
        assertTrue(e.any { "step 3" in it && "no animation is picked" in it })
        assertTrue(e.any { "step 4" in it && "narration" in it })
        assertTrue(e.any { "step 5" in it && "gone" in it })
        assertTrue(e.none { "step 2" in it && "library" in it })
        // without a library to check against, only the blank and the shape are problems
        val e2 = Compile.validate(p)
        assertTrue(e2.none { "isn't in the library" in it })
        assertTrue(e2.any { "no animation is picked" in it })
        assertTrue(Compile.validate(project(Beat.Pose("mira", "has space")), setOf("has space")).any { "space" in it })
        assertEquals(emptyList(), Compile.validate(project(Beat.Pose("mira", "here")), setOf("here")))
    }

    @Test fun editorStateKnowsPoses() {
        val s = EditorState(project(Beat.Pose("mira", "a1"), Beat.Say("mira", "anim:b2", "x"), Beat.Hide("bob")))
        assertEquals(BeatKind.POSE, BeatKind.of(Beat.Pose("m", "a")))
        assertEquals(Beat.Pose("mira", ""), s.newBeat(BeatKind.POSE))
        assertEquals(2, s.actorUses("mira"))   // the pose and the say
        val names = { id: String -> if (id == "a1") "Sitting" else id }
        assertEquals("Pose mira: Sitting", describe(Beat.Pose("mira", "a1"), { it }, names))
        assertEquals("Pose mira: (none)", describe(Beat.Pose("mira", ""), { it }, names))
        assertEquals("mira (pose Sitting): x", describe(Beat.Say("mira", "anim:a1", "x"), { it }, names))
        // a validator given to the state is what Check and Play use
        val strict = EditorState(project(Beat.Pose("mira", "zz")), validator = { Compile.validate(it, emptySet()) })
        assertTrue(strict.problems().any { "zz" in it })
    }

    @Test fun deletingAnActorLetsGoOfTheirPoses() {
        val s = EditorState(project(Beat.Pose("mira", "a1"), Beat.Say("mira", "anim:a1", "x"), Beat.Pose("bob", "a2")))
        s.deleteActor("mira")
        val beats = s.sceneOf("a")!!.beats
        assertEquals(listOf<Beat>(Beat.Say(null, "talk", "x"), Beat.Pose("bob", "a2")), beats)
    }

    @Test fun previewHoldsThePoseUntilItIsReplacedOrTheyLeave() {
        val sc = scene(
            Beat.Show("mira", "left", ""), Beat.Pose("mira", "a1"), Beat.Say("bob", "anim:b2", "x"),
            Beat.Say("mira", "laugh", "ha"), Beat.Pose("mira", "a3"), Beat.Hide("mira"),
        )
        assertEquals(emptyMap(), Staging.poses(sc, 1))
        assertEquals(mapOf("mira" to "a1"), Staging.poses(sc, 2))
        assertEquals(mapOf("mira" to "a1", "bob" to "b2"), Staging.poses(sc, 3))
        assertEquals(mapOf("bob" to "b2"), Staging.poses(sc, 4))      // a game emote ends it
        assertEquals(mapOf("bob" to "b2", "mira" to "a3"), Staging.poses(sc, 5))
        assertEquals(mapOf("bob" to "b2"), Staging.poses(sc))          // hidden
        assertNull(Staging.poses(sc, 0)["mira"])
    }
}
