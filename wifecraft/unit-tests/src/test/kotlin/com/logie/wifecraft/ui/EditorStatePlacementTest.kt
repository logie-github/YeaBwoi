package com.logie.wifecraft.ui

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.editor.Beat
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement
import com.logie.wifecraft.editor.Project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorStatePlacementTest {
    private val look = Appearance(4, Sex.FEMALE, mapOf())
    private fun prop(name: String = "Tree") = Placement("", PlaceKind.PROP, 99, null, name, 0f, 0f, 0f, 0f, 1f)
    private fun fresh(): Triple<EditorState, String, MutableList<Project>> {
        val changes = mutableListOf<Project>()
        val s = EditorState(Project("p", "T", emptyList(), emptyList(), null), onChange = { changes += it })
        return Triple(s, s.addScene("One"), changes)
    }

    @Test fun addUpdateDuplicateRemove() {
        val (s, sc, _) = fresh()
        val a = s.addPlacement(sc, prop())!!
        val b = s.addPlacement(sc, prop())!!
        assertEquals(listOf("tree", "tree_2"), listOf(a, b))
        s.renamePlacement(sc, a, " Old oak ")
        assertEquals("Old oak", s.placementOf(sc, a)!!.name)
        s.renamePlacement(sc, a, "  "); assertEquals("Old oak", s.placementOf(sc, a)!!.name)
        val c = s.duplicatePlacement(sc, a)!!
        assertEquals(1f, s.placementOf(sc, c)!!.x)
        assertEquals("Old oak (copy)", s.placementOf(sc, c)!!.name)
        s.removePlacement(sc, b)
        assertEquals(listOf(a, c), s.sceneOf(sc)!!.placements.map { it.id })
        assertNull(s.addPlacement("nope", prop())); assertNull(s.duplicatePlacement(sc, "nope"))
    }

    @Test fun npcsNeedACastMember() {
        val (s, sc, _) = fresh()
        val npc = Placement("", PlaceKind.NPC, 0, "mira", "Mira", 0f, 0f, 0f, 0f, 1f)
        assertNull(s.addPlacement(sc, npc))
        val mira = s.addActor("Mira", look)
        val id = s.addPlacement(sc, npc.copy(actorId = mira))!!
        s.addBeat(sc, Beat.Emote(id, 5)); s.addBeat(sc, Beat.Anim(id, 6)); s.addBeat(sc, Beat.End("x"))
        assertEquals(1, s.actorUses(mira))
        s.deleteActor(mira)   // the placement and the beats that played on it go
        assertTrue(s.sceneOf(sc)!!.placements.isEmpty())
        assertEquals(listOf(Beat.End("x")), s.sceneOf(sc)!!.beats)
    }

    @Test fun removingAPlacementDropsItsBeats() {
        val (s, sc, _) = fresh()
        val a = s.addPlacement(sc, prop())!!
        s.addBeat(sc, Beat.Anim(a, 1)); s.addBeat(sc, Beat.Emote(a, 2)); s.addBeat(sc, Beat.End("x"))
        s.removePlacement(sc, a)
        assertEquals(1, s.sceneOf(sc)!!.beats.size)
    }

    @Test fun clampsAndSnapping() {
        val (s, sc, _) = fresh()
        val a = s.addPlacement(sc, prop().copy(scale = 500f, x = 1e9f, y = Float.NaN, yaw = 7f))!!
        val p = s.placementOf(sc, a)!!
        assertEquals(PlaceMath.MAX_SCALE, p.scale); assertEquals(PlaceMath.LIMIT, p.x); assertEquals(0f, p.y)
        assertTrue(p.yaw in -Math.PI.toFloat()..Math.PI.toFloat())
        s.setPlacementScale(sc, a, 0f); assertEquals(PlaceMath.MIN_SCALE, s.placementOf(sc, a)!!.scale)
        s.setPlacementScale(sc, a, -3f); assertEquals(PlaceMath.MIN_SCALE, s.placementOf(sc, a)!!.scale)
        s.scalePlacement(sc, a, 2f); assertEquals(PlaceMath.MIN_SCALE * 2, s.placementOf(sc, a)!!.scale)
        s.movePlacement(sc, a, 3.4f, -7.6f, 1f, snap = 0.5f)
        assertEquals(3.5f, s.placementOf(sc, a)!!.x); assertEquals(-7.5f, s.placementOf(sc, a)!!.y)
        s.nudgePlacement(sc, a, 0.1f, 0f, 100000f); assertEquals(PlaceMath.MAX_Z, s.placementOf(sc, a)!!.z)
        assertEquals(0f, PlaceMath.snap(0.2f, 0.5f)); assertEquals(0.7f, PlaceMath.snap(0.7f, 0f))
        val step = (Math.PI / 4).toFloat()
        assertEquals(step, PlaceMath.snapYaw(step + 0.1f, step), 1e-5f)
        assertEquals(-Math.PI.toFloat() / 2, PlaceMath.snapYaw(3f * Math.PI.toFloat() / 2, 0f), 1e-4f)
        s.rotatePlacement(sc, a, 10f); assertTrue(s.placementOf(sc, a)!!.yaw in -Math.PI.toFloat()..Math.PI.toFloat())
    }

    @Test fun animAndSwitches() {
        val (s, sc, _) = fresh()
        val a = s.addPlacement(sc, prop())!!
        s.setPlacementAnim(sc, a, 70, emoteId = 12, loop = false)
        val p = s.placementOf(sc, a)!!
        assertEquals(70, p.anim); assertEquals(12, p.emoteId); assertTrue(!p.loop)
        s.setPlacementAnim(sc, a, null, emoteId = 12)
        assertNull(s.placementOf(sc, a)!!.emoteId)
        s.setPlacementSound(sc, a, false); assertTrue(!s.placementOf(sc, a)!!.sound)
    }

    @Test fun undoRedoWalkTheHistory() {
        val (s, sc, changes) = fresh()
        assertTrue(s.canUndo)   // the scene was added
        val before = s.project
        val a = s.addPlacement(sc, prop())!!
        s.renamePlacement(sc, a, "Oak")
        assertTrue(s.undo()); assertEquals("Tree", s.placementOf(sc, a)!!.name)
        assertTrue(s.undo()); assertTrue(s.sceneOf(sc)!!.placements.isEmpty()); assertEquals(before, s.project)
        assertTrue(s.redo()); assertTrue(s.redo())
        assertEquals("Oak", s.placementOf(sc, a)!!.name)
        assertTrue(!s.redo() && s.canUndo && !s.canRedo)
        assertEquals(s.project, changes.last())   // a redo is told to the screens
        s.undo(); s.renamePlacement(sc, a, "Elm")   // a new change drops the redo
        assertTrue(!s.canRedo)
    }

    @Test fun aDragIsOneStepUntilItEnds() {
        val (s, sc, _) = fresh()
        val a = s.addPlacement(sc, prop())!!
        val b = s.addPlacement(sc, prop())!!
        for (i in 1..30) s.movePlacement(sc, a, i.toFloat(), 0f, 0f)
        s.endGesture()
        for (i in 1..30) s.movePlacement(sc, a, 100f + i, 0f, 0f)
        s.movePlacement(sc, b, 5f, 5f, 0f)   // another thing: a step of its own
        s.undo(); assertEquals(5f * 0, s.placementOf(sc, b)!!.x)
        assertEquals(130f, s.placementOf(sc, a)!!.x)
        s.undo(); assertEquals(30f, s.placementOf(sc, a)!!.x)
        s.undo(); assertEquals(0f, s.placementOf(sc, a)!!.x)
        s.redo(); assertEquals(30f, s.placementOf(sc, a)!!.x)
    }

    @Test fun historyIsBoundedToAHundredSteps() {
        val (s, sc, _) = fresh()
        val a = s.addPlacement(sc, prop())!!
        for (i in 1..250) s.nudgePlacement(sc, a, 1f, 0f, 0f)
        var n = 0
        while (s.undo()) n++
        assertEquals(EditorState.HISTORY, n)
        assertEquals(150f, s.placementOf(sc, a)!!.x)
    }

    @Test fun stageIdFollowsThePlace() {
        val (s, sc, _) = fresh()
        s.setStageId(sc, "loc:embassy:outside", "Embassy")
        assertEquals("loc:embassy:outside", s.sceneOf(sc)!!.stageId)
        assertEquals("studio", s.sceneOf(sc)!!.stage.place)
        s.setStage(sc, "brill", "night")
        assertEquals("brill", s.sceneOf(sc)!!.stageId)
        assertEquals("Emote Tree: #4", describe(Beat.Emote("t", 4), { it }, placedName = { "Tree" }))
    }
}
