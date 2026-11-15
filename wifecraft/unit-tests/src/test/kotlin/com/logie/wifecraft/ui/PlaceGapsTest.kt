package com.logie.wifecraft.ui

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.editor.Actor
import com.logie.wifecraft.editor.Beat
import com.logie.wifecraft.editor.Compile
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.editor.Placement
import com.logie.wifecraft.editor.Project
import com.logie.wifecraft.editor.Scene
import com.logie.wifecraft.editor.Stage
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.story.Script
import com.logie.wifecraft.story.Vec3
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Hide, Lock, the location stage Play opens, and the prop Sound label. */
class PlaceGapsTest {
    private val look = Appearance(4, Sex.FEMALE, mapOf())
    private fun prop() = Placement("tree", PlaceKind.PROP, 99, null, "Tree", 1f, 2f, 3f, 0f, 1f)

    private fun fresh(): Triple<EditorState, String, String> {
        val s = EditorState(Project("p", "T", listOf(Actor("mira", "Mira", look)), emptyList(), null), onChange = {})
        val sc = s.addScene("One")
        return Triple(s, sc, s.addPlacement(sc, prop())!!)
    }

    @Test fun hiddenAndLockedAreSavedAndAbsentMeansFalse() {
        val p = prop().copy(hidden = true, locked = true)
        val back = Placement.fromJson(JSONObject(p.toJson().toString()))
        assertEquals(p, back)
        val old = JSONObject(prop().toJson().toString()).also { it.remove("hidden"); it.remove("locked") }
        assertFalse(Placement.fromJson(old).hidden); assertFalse(Placement.fromJson(old).locked)
    }

    @Test fun aLockedPlacementIsNotMovedTurnedOrResized() {
        val (s, sc, id) = fresh()
        s.setPlacementLocked(sc, id, true)
        val before = s.placementOf(sc, id)!!
        s.movePlacement(sc, id, 9f, 9f, 9f); s.nudgePlacement(sc, id, 1f, 1f, 1f)
        s.rotatePlacement(sc, id, 1f); s.setPlacementYaw(sc, id, 2f)
        s.scalePlacement(sc, id, 2f); s.setPlacementScale(sc, id, 3f)
        assertEquals(before, s.placementOf(sc, id))
        s.setPlacementLocked(sc, id, false)
        s.nudgePlacement(sc, id, 1f, 0f, 0f)
        assertEquals(2f, s.placementOf(sc, id)!!.x)
        // a copy of a locked one is free
        s.setPlacementLocked(sc, id, true)
        assertFalse(s.placementOf(sc, s.duplicatePlacement(sc, id)!!)!!.locked)
        // hiding is one undo step
        s.setPlacementHidden(sc, id, true); assertTrue(s.placementOf(sc, id)!!.hidden)
        s.undo(); assertFalse(s.placementOf(sc, id)!!.hidden)
    }

    @Test fun aHiddenPlacementIsLeftOutOfThePlayScript() {
        val hid = Placement("rock", PlaceKind.CREATURE, 5, null, "Rock", 0f, 0f, 0f, 0f, 1f, anim = 3, hidden = true)
        val p = Project("p", "T", emptyList(), listOf(Scene("one", "One", Stage("st", "S", "studio", "day"),
            listOf(Beat.Anim("rock", 3, true), Beat.End("x")), "studio", listOf(prop(), hid))), "one")
        val text = Compile.toScript(p)
        assertTrue(text.contains("place tree prop 99"))
        assertFalse(text.contains("place rock"))
        assertFalse(text.contains("anim rock"))
        assertTrue(text.contains("# hidden, not placed: rock"))
        assertNotNull(Script.parse(text))   // the comments parse
        assertTrue(Compile.validate(p).isEmpty())
    }

    @Test fun aLocationSceneTakesTheViewsCameraAndName() {
        val json = """{"locations":[{"id":"embassy","name":"Stormwind Embassy","kind":"interior","views":{
            "inside":{"camera":{"eye":[1,2,3],"at":[4,5,6],"fov":55},"spawn":{"pos":[7,8,9],"facing":1.5}},
            "outside":{"spawn":[10,0,0,0]}}}]}"""
        val locs = LocationInfo.parseAll(json)
        val inside = StageChoices.locationStage("loc:embassy:inside", locs)!!
        assertEquals("Stormwind Embassy: Inside", inside.name)
        assertEquals(Vec3(1f, 2f, 3f), inside.camera!!.eye); assertEquals(55f, inside.camera!!.fov)
        assertEquals(Vec3(7f, 8f, 9f), inside.spawn)
        // a view without its own camera looks at its spawn
        val outside = StageChoices.locationStage("loc:embassy:outside", locs)!!
        assertNotNull(outside.camera); assertEquals(Vec3(10f, 0f, 1f), outside.camera!!.at)
        assertNull(StageChoices.locationStage("loc:nowhere:inside", locs))
        assertNull(StageChoices.locationStage("brill", locs))
    }

    @Test fun propsSayTheirSoundIsNotThereYet() {
        assertEquals("Sound (none for props yet)", PlaceLabels.sound(PlaceKind.PROP))
        assertEquals("Sound", PlaceLabels.sound(PlaceKind.CREATURE))
        assertEquals("Sound", PlaceLabels.sound(PlaceKind.NPC))
    }
}
