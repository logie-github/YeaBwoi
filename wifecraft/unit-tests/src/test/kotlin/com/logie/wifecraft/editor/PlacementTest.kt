package com.logie.wifecraft.editor

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.play.PlaceDirector
import com.logie.wifecraft.play.PlaceHost
import com.logie.wifecraft.story.Command
import com.logie.wifecraft.story.Script
import com.logie.wifecraft.story.Step
import com.logie.wifecraft.story.StoryRunner
import com.logie.wifecraft.story.StoryState
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacementTest {
    private val look = Appearance(4, Sex.FEMALE, mapOf(1 to 2))
    private val tree = Placement("tree1", PlaceKind.PROP, 1234, null, "Tree", 1.5f, -2f, 0.25f, 0.5f, 2f)
    private val wolf = Placement("wolf1", PlaceKind.CREATURE, 77, null, "Wolf", 3f, 4f, 0f, 3.1f, 1f, anim = 26, loop = false, sound = false)
    private val mira = Placement("mira1", PlaceKind.NPC, 0, "mira", "Mira", 0f, 0f, 0f, 0f, 1f, anim = 70, emoteId = 12)

    private fun project(vararg beats: Beat, stageId: String = "loc:embassy:inside") = Project(
        "p", "T", listOf(Actor("mira", "Mira", look)),
        listOf(Scene("one", "One", Stage("st", "Embassy", "studio", "dusk"), beats.toList(), stageId, listOf(tree, wolf, mira))), "one",
    )

    @Test fun placementsRoundTripThroughJson() {
        val p = project(Beat.Emote("mira1", 12), Beat.Anim("wolf1", 26, true), Beat.End("x"))
        val back = Project.fromJson(JSONObject(p.toJson().toString()))
        assertEquals(p, back)
        assertEquals("loc:embassy:inside", back.scenes[0].stageId)
        assertNull(back.scenes[0].placements[0].anim)
        assertEquals(12, back.scenes[0].placements[2].emoteId)
    }

    @Test fun anOldSaveLoads() {
        val old = """{"id":"p","title":"T","start":"a","cast":[],"scenes":[{"id":"a","name":"A",
            "stage":{"id":"s","name":"S","place":"brill","light":"night"},
            "beats":[{"type":"say","who":null,"emote":"talk","text":"Hi"}]}]}"""
        val p = Project.fromJson(JSONObject(old))
        assertEquals("brill", p.scenes[0].stageId)
        assertTrue(p.scenes[0].placements.isEmpty())
        assertTrue(Compile.toScript(p).contains("scene brill night"))
        assertTrue(Compile.validate(p).isEmpty())
    }

    @Test fun compileEmitsTheSceneAndPlaceLinesAsPinned() {
        val text = Compile.toScript(project(Beat.Emote("mira1", 12), Beat.Anim("wolf1", 26, true), Beat.End("x")))
        val lines = text.lines()
        val at = lines.indexOfFirst { it.startsWith("scene ") }
        assertEquals("scene loc:embassy:inside dusk", lines[at])
        assertEquals("place tree1 prop 1234 - 1.5 -2.0 0.25 0.5 2.0 - 1 1", lines[at + 1])
        assertEquals("place wolf1 creature 77 - 3.0 4.0 0.0 3.1 1.0 26 0 0", lines[at + 2])
        assertEquals("place mira1 npc 0 p_mira 0.0 0.0 0.0 0.0 1.0 70 1 1", lines[at + 3])
        assertTrue("emote mira1 12" in lines)
        assertTrue("anim wolf1 26 1" in lines)
    }

    @Test fun scriptParsesPlaceEmoteAndAnim() {
        val c = Script.parse(Compile.toScript(project(Beat.Emote("mira1", 12), Beat.Anim("wolf1", 26, true), Beat.End("x")))).commands
        assertEquals(Command.Scene("loc:embassy:inside", "dusk", c.filterIsInstance<Command.Scene>().single().line), c.filterIsInstance<Command.Scene>().single())
        val places = c.filterIsInstance<Command.Place>()
        assertEquals(listOf("tree1", "wolf1", "mira1"), places.map { it.id })
        assertEquals(listOf("prop", "creature", "npc"), places.map { it.kind })
        assertEquals("p_mira", places[2].actor); assertNull(places[0].actor)
        assertEquals(26, places[1].anim); assertNull(places[0].anim)
        assertTrue(!places[1].loop && !places[1].sound && places[0].loop)
        assertEquals(1.5f, places[0].x); assertEquals(-2f, places[0].y); assertEquals(2f, places[0].scale)
        assertEquals(Command.Emote("mira1", "12", false, c.filterIsInstance<Command.Emote>().single().line), c.filterIsInstance<Command.Emote>().single())
        assertEquals(26, c.filterIsInstance<Command.PlaceAnim>().single().anim)
        assertTrue(c.filterIsInstance<Command.PlaceAnim>().single().loop)
    }

    @Test fun badPlaceLinesAreRefused() {
        for (bad in listOf("place a prop 1 - 0 0 0 0 1 - 1", "place a rock 1 - 0 0 0 0 1 - 1 1", "place a prop 1 - x 0 0 0 1 - 1 1", "place a prop 1 - 0 0 0 0 1 - 2 1", "anim a 1")) {
            val r = runCatching { Script.parse("scene studio day\n$bad\nfinish") }
            assertTrue(r.isFailure, bad)
        }
    }

    /** A host that writes down what it was asked. */
    private class Fake : PlaceHost {
        val calls = mutableListOf<String>()
        val spawned = mutableListOf<Placement>()
        override fun spawn(p: Placement) { spawned += p; calls += "spawn ${p.id}" }
        override fun clear() { calls += "clear" }
        override fun playAnim(id: String, anim: Int, loop: Boolean) { calls += "anim $id $anim $loop" }
        override fun playEmote(id: String, emoteId: Int) { calls += "emote $id $emoteId" }
        override fun enterLocation(locationId: String, view: String, light: String) { calls += "loc $locationId $view $light" }
    }

    @Test fun threePlacementsPlayThroughRunnerAndDirector() {
        val p = project(Beat.Say(null, "talk", "Hello."), Beat.Emote("mira1", 12), Beat.Anim("wolf1", 26, true), Beat.End("The End"))
        val runner = StoryRunner(Script.parse(Compile.toScript(p)), StoryState())
        val host = Fake()
        val dir = PlaceDirector(host)
        var guard = 0
        loop@ while (guard++ < 100) {
            when (val s = runner.next()) {
                is Step.Stage -> {
                    val c = s.command
                    if (c is Command.Scene) {
                        dir.clear()
                        c.place.split(":").takeIf { it.size == 3 }?.let { dir.enterLocation(it[1], it[2], c.light) }
                    } else dir.handle(c, false)
                }
                is Step.Ending, Step.Finished -> break@loop
                else -> Unit
            }
        }
        assertEquals(listOf("clear", "loc embassy inside dusk", "spawn tree1", "spawn wolf1", "spawn mira1", "emote mira1 12", "anim wolf1 26 true"), host.calls)
        assertEquals(mira.copy(emoteId = null, actorId = "p_mira"), host.spawned[2].copy(name = "Mira"))   // the actor is the script name
        assertEquals(PlaceKind.CREATURE, host.spawned[1].kind)
        assertEquals(3.1f, host.spawned[1].yaw)
    }

    @Test fun anEmoteOnAnActorStaysAnActorsEmote() {
        val dir = PlaceDirector(Fake())
        assertTrue(!dir.handle(Command.Emote("p_mira", "laugh", false, 1), false))
        dir.handle(Command.Place("t", "prop", 5, null, 0f, 0f, 0f, 0f, 1f, null, true, true, 1), false)
        assertTrue(dir.handle(Command.Emote("t", "4", false, 2), false))
    }

    @Test fun stageRepeatsPlacementsWhenTheStageIsSetAgain() {
        val runner = StoryRunner(Script.parse(Compile.toScript(project(Beat.Anim("wolf1", 26, true), Beat.End("x")))), StoryState())
        repeat(6) { runner.next() }
        val kinds = runner.stageCommands().map { it::class.simpleName }
        assertEquals(listOf("Scene", "Place", "Place", "Place", "PlaceAnim"), kinds)
    }

    private fun errors(p: Project, stages: Set<String>? = null) = Compile.validate(p, null, stages)

    @Test fun validationCatchesBadStagesActorsNumbersAndSizes() {
        assertEquals(emptyList(), errors(project(Beat.Emote("mira1", 1)).let { it }, setOf("studio", "loc:embassy:inside")).filterNot { "start" in it })
        assertTrue(errors(project(), setOf("studio")).any { "loc:embassy:inside" in it })
        assertTrue(errors(project(stageId = "loc:embassy")).any { "loc:<location>:<view>" in it })
        val s = project().scenes[0]
        fun with(pl: Placement) = project().copy(scenes = listOf(s.copy(placements = listOf(pl))))
        assertTrue(errors(with(mira.copy(actorId = "ghost"))).any { "ghost" in it })
        assertTrue(errors(with(mira.copy(actorId = null))).any { "cast" in it })
        assertTrue(errors(with(tree.copy(x = Float.NaN))).any { "isn't a number" in it })
        assertTrue(errors(with(tree.copy(z = Float.POSITIVE_INFINITY))).any { "isn't a number" in it })
        assertTrue(errors(with(tree.copy(scale = 0f))).any { "more than 0" in it })
        assertTrue(errors(with(tree.copy(scale = -1f))).any { "more than 0" in it })
        assertTrue(errors(with(tree.copy(asset = 0))).any { "model" in it })
        assertTrue(errors(project().copy(scenes = listOf(s.copy(placements = listOf(tree, tree))))).any { "share the id" in it })
        assertTrue(errors(project(Beat.Emote("nope", 1))).any { "nope" in it })
        assertTrue(errors(project(Beat.Anim("nope", 1))).any { "nope" in it })
        assertTrue(errors(project(Beat.Anim("tree1", 1))).none { "nope" in it })
    }

    @Test fun unsafePlacementIdsBecomeOneWord() {
        val s = project().scenes[0].copy(placements = listOf(tree.copy(id = "a b"), tree.copy(id = "a_b"), tree.copy(id = "ok-1")), beats = listOf(Beat.Anim("a b", 1)))
        val text = Compile.toScript(project().copy(scenes = listOf(s)))
        assertTrue("anim a_b 1 0" in text.lines())
        val ids = Script.parse(text).commands.filterIsInstance<Command.Place>().map { it.id }
        assertEquals(listOf("a_b", "a_b_2", "ok-1"), ids)
    }
}
