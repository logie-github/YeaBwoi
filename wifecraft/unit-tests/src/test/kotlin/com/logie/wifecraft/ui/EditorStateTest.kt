package com.logie.wifecraft.ui

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Race
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.editor.Beat
import com.logie.wifecraft.editor.Project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorStateTest {
    private val look = Appearance(4, Sex.FEMALE, mapOf(1 to 2, 3 to 4))
    private fun empty() = Project("p", "Project", emptyList(), emptyList(), null)
    private fun state(p: Project = empty(), changes: MutableList<Project>? = null) =
        EditorState(p, onChange = { changes?.add(it) })

    @Test fun idsAreSlugsAndUnique() {
        assertEquals("night_elf", freshId("Night Elf!", emptyList()))
        assertEquals("a_2", freshId("a", listOf("a")))
        assertEquals("a_3", freshId("a", listOf("a", "a_2")))
        assertEquals("x", freshId("***", emptyList()))
    }

    @Test fun castAddEditDuplicateDelete() {
        val s = state()
        val a = s.addActor("Lyra", look)
        val b = s.addActor("Lyra", look)
        assertEquals(listOf("lyra", "lyra_2"), s.project.cast.map { it.id })
        assertEquals(a, "lyra"); assertEquals(b, "lyra_2")
        s.updateActor(a, "  Lyra Dawn ", look.copy(picks = emptyMap()))
        assertEquals("Lyra Dawn", s.nameOf(a))
        assertTrue(s.actorOf(a)!!.appearance.picks.isEmpty())
        val c = s.duplicateActor(a)!!
        assertEquals(listOf("lyra", c, "lyra_2"), s.project.cast.map { it.id })
        assertEquals("Lyra Dawn (copy)", s.nameOf(c))
        assertNull(s.duplicateActor("nobody"))
        s.deleteActor(c)
        assertNull(s.actorOf(c))
    }

    @Test fun deletingAnActorCleansTheBeats() {
        val s = state()
        val a = s.addActor("Ann", look)
        val sc = s.addScene("One")
        s.addBeat(sc, Beat.Show(a, "left", "bust"))
        s.addBeat(sc, Beat.Say(a, "talk", "Hi"))
        s.addBeat(sc, Beat.Hide(a))
        assertEquals(3, s.actorUses(a))
        s.deleteActor(a)
        assertEquals(listOf<Beat>(Beat.Say(null, "talk", "Hi")), s.sceneOf(sc)!!.beats)
    }

    @Test fun scenesAddMoveDeleteAndStart() {
        val changes = mutableListOf<Project>()
        val s = state(changes = changes)
        val one = s.addScene("One"); val two = s.addScene("Two"); val three = s.addScene("")
        assertEquals(one, s.project.start)
        assertEquals("Scene 3", s.sceneOf(three)!!.name)
        assertTrue(s.moveScene(three, -1))
        assertEquals(listOf(one, three, two), s.project.scenes.map { it.id })
        assertTrue(!s.moveScene(one, -1) && !s.moveScene(two, 1))
        s.addBeat(one, Beat.Goto(two))
        s.addBeat(one, Beat.Choice("?", listOf(Beat.Choice.Option("a", two), Beat.Choice.Option("b", three))))
        s.addBeat(three, Beat.Choice("?", listOf(Beat.Choice.Option("a", two))))
        s.setStart(two)
        s.deleteScene(two)
        assertEquals(one, s.project.start)
        assertEquals(listOf<Beat>(Beat.Choice("?", listOf(Beat.Choice.Option("b", three)))), s.sceneOf(one)!!.beats)
        assertTrue(s.sceneOf(three)!!.beats.isEmpty())
        s.deleteScene(one); s.deleteScene(three)
        assertNull(s.project.start)
        assertTrue(changes.size > 5)
    }

    @Test fun stageAndRename() {
        val s = state()
        val sc = s.addScene("One")
        s.setStage(sc, "street", "dusk", "Dalaran's streets")
        assertEquals("street", s.sceneOf(sc)!!.stage.place)
        assertEquals("dusk", s.sceneOf(sc)!!.stage.light)
        s.renameScene(sc, "  "); assertEquals("One", s.sceneOf(sc)!!.name)
        s.renameScene(sc, "Arrival"); assertEquals("Arrival", s.sceneOf(sc)!!.name)
        s.rename("Tale"); s.rename(" "); assertEquals("Tale", s.project.title)
    }

    @Test fun beatsAddReplaceMoveDuplicateRemove() {
        val s = state()
        val a = s.addActor("Ann", look)
        val sc = s.addScene("One")
        val say = s.newBeat(BeatKind.SAY, sc)
        assertEquals(Beat.Say(a, "talk", ""), say)
        assertEquals(0, s.addBeat(sc, say))
        assertEquals(1, s.addBeat(sc, Beat.End("Fin")))
        assertEquals(1, s.addBeat(sc, Beat.Camera("wide"), 1))
        assertEquals(listOf("Say", "Camera", "End"), s.sceneOf(sc)!!.beats.map { BeatKind.of(it).label })
        s.replaceBeat(sc, 0, Beat.Say(a, "laugh", "Ha"))
        assertEquals(2, s.moveBeat(sc, 0, 2))
        assertEquals(2, s.moveBeat(sc, 2, 1))   // already last
        assertEquals(0, s.moveBeat(sc, 0, -1))
        assertEquals(1, s.duplicateBeat(sc, 0))
        assertEquals(4, s.sceneOf(sc)!!.beats.size)
        s.removeBeat(sc, 1); s.removeBeat(sc, 9)
        assertEquals(3, s.sceneOf(sc)!!.beats.size)
        assertEquals(-1, s.addBeat("nope", say))
    }

    @Test fun newBeatsPointAtTheProject() {
        val s = state()
        val a = s.addActor("Ann", look)
        val one = s.addScene("One"); val two = s.addScene("Two")
        assertEquals(Beat.Goto(two), s.newBeat(BeatKind.GOTO, one))
        assertEquals(Beat.Show(a, "center", "bust"), s.newBeat(BeatKind.SHOW, one))
        assertEquals(2, (s.newBeat(BeatKind.CHOICE, one) as Beat.Choice).options.size)
        assertEquals(10, BeatKind.values().size)   // the original eight and Emote and Animation of a placed thing
    }

    @Test fun checksUseCompileValidate() {
        val s = state()
        assertTrue(s.problems().any { "no scenes" in it })
        val a = s.addActor("Ann", look)
        val sc = s.addScene("One")
        assertTrue(s.problems().isEmpty(), s.problems().toString())
        s.addBeat(sc, Beat.Say("ghost", "talk", ""))
        s.addBeat(sc, Beat.Show(a, "up", "bust"))
        s.addBeat(sc, Beat.Goto("gone"))
        assertEquals(4, s.problems().size, s.problems().toString())
        // What the editor offers is always something Compile accepts.
        val ok = state()
        val x = ok.addActor("Ann", look); val y = ok.addActor("Bo", look)
        val one = ok.addScene("One"); val two = ok.addScene("Two")
        val views = listOf("plaza")
        for ((shot, _) in EditorChoices.shots(listOf(x to "Ann", y to "Bo"), views, shown = x)) ok.addBeat(one, Beat.Show(x, "left", shot))
        for ((shot, _) in EditorChoices.shots(listOf(x to "Ann", y to "Bo"), views)) ok.addBeat(one, Beat.Camera(shot))
        ok.addBeat(one, Beat.Say(x, "laugh", "Ha"))
        ok.addBeat(one, Beat.Choice("Where?", listOf(Beat.Choice.Option("There", two))))
        ok.addBeat(two, Beat.End("The End"))
        assertEquals(emptyList(), ok.problems())
        com.logie.wifecraft.editor.Compile.toScript(ok.project).also { com.logie.wifecraft.story.Script.parse(it) }
    }

    @Test fun customValidatorIsUsed() {
        val s = EditorState(empty(), validator = { listOf("x") })
        assertEquals(listOf("x"), s.problems())
    }

    @Test fun describeAndSummary() {
        val n = { id: String -> id.uppercase() }
        assertEquals("A (laugh): Hi", describe(Beat.Say("a", "laugh", "Hi"), n))
        assertEquals("Narration: Hi", describe(Beat.Say(null, "talk", "Hi"), n))
        assertEquals("Show A left, bust", describe(Beat.Show("a", "left", "bust"), n))
        val race = Race(4, "nightelf", "Night Elf", true, emptySet())
        assertEquals("Night Elf woman, 2 choices", lookSummary(race, look))
        assertEquals("Race 9 man", lookSummary(null, Appearance(9, Sex.MALE)))
    }

    private val places = PlaceInfo.parseAll(
        """{"places":{"plaza":{"name":"Plaza","marks":{"a":[10,0,5],"b":[10,4,5]},"views":{"plaza":{"eye":[0,2,7],"at":[10,2,5],"fov":40}},"twoSide":1}}}""",
    )

    @Test fun placesParseAndStudioComesFirst() {
        assertEquals(listOf("studio", "plaza"), places.map { it.id })
        assertEquals("Plaza", places[1].name)
        assertEquals(40f, places[1].views.getValue("plaza").fov)
    }

    @Test fun previewFollowsTheBeats() {
        val sc = com.logie.wifecraft.editor.Scene("s", "S", com.logie.wifecraft.editor.Stage("st", "x", "studio", "day"), listOf(
            Beat.Show("a", "left", "bust"), Beat.Show("b", "right", "two a"), Beat.Say("a", "talk", "x"), Beat.Hide("b"), Beat.Camera("wide"),
        ))
        assertEquals(emptyList(), Staging.preview(sc, 0).shown)
        assertEquals(listOf("wide"), Staging.preview(sc, 0).shot)
        assertEquals(listOf("a", "b"), Staging.preview(sc, 2).shown.map { it.actor })
        assertEquals(listOf("two", "b", "a"), Staging.preview(sc, 2).shot)
        assertEquals(listOf("a"), Staging.preview(sc, 4).shown.map { it.actor })
        assertEquals(listOf("wide"), Staging.preview(sc).shot)
        // A second NPC in a taken spot replaces the first; a Show with no shot keeps the camera.
        val sc2 = sc.copy(beats = listOf(Beat.Show("a", "left", "bust"), Beat.Show("b", "left", "")))
        assertEquals(listOf("b"), Staging.preview(sc2).shown.map { it.actor })
        assertEquals(listOf("bust", "a"), Staging.preview(sc2).shot)
        assertEquals(null, Staging.shotWords("zoom", null))
        assertEquals(null, Staging.shotWords("bust", null))
    }

    @Test fun spotsAreLeftToRightAsTheCameraSees() {
        // Looking along +x from the origin, the viewer's right is -y.
        val p = PlaceInfo("t", "T", mapOf("m" to com.logie.wifecraft.story.Vec3(10f, 0f, 0f)),
            mapOf("v" to com.logie.wifecraft.story.CameraPose(com.logie.wifecraft.story.Vec3(0f, 0f, 2f), com.logie.wifecraft.story.Vec3(10f, 0f, 1f), 40f)))
        val l = Staging.standing(p, "left"); val c = Staging.standing(p, "center"); val r = Staging.standing(p, "right")
        assertTrue(l.pos.y > c.pos.y && c.pos.y > r.pos.y)
        assertEquals(10f, c.pos.x)
        assertEquals(Math.PI.toFloat(), Math.abs(c.facing), 1e-3f)   // faces back at the camera
    }

    @Test fun cameraShots() {
        val p = places[0]
        val l = Preview.Shown("a", "left"); val r = Preview.Shown("b", "right")
        assertEquals(32f, Staging.pose(p, Preview(listOf(l), listOf("bust", "a"))).fov)
        assertEquals(40f, Staging.pose(p, Preview(listOf(l, r), listOf("two", "a", "b"))).fov)
        assertEquals(32f, Staging.pose(p, Preview(listOf(l), listOf("two", "a", "b"))).fov)   // one of them absent: the other's bust
        assertEquals(42f, Staging.pose(p, Preview(listOf(l), listOf("wide"))).fov)
        assertEquals(42f, Staging.pose(p, Preview(listOf(l), listOf("view", "studio"))).fov)
        assertEquals(42f, Staging.pose(p, Preview(emptyList(), listOf("bust", "a"))).fov)   // nobody to film: wide
        assertNotNull(Staging.pose(places[1], Preview(listOf(l), listOf("view", "plaza"))))
        assertEquals(10f, Staging.anchor(places[1]).x)
        assertEquals(2f, Staging.anchor(places[1]).y)
    }
}
