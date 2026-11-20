package com.logie.wifecraft.location

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The app's assets/locations.json as the loader reads it, and a WMO root's files as PropLoader fetches them. */
class LocationsTest {
    private val json = File(System.getProperty("wifecraft.assets"), "locations.json").readText()
    private val locations = LocationInfo.parseAll(json)

    @Test fun theEmbassyInteractionReferencesItsExistingDoodads() {
        val embassy = locations.single { it.id == "embassy" }
        val table = embassy.interactions.single()
        assertEquals("TABLE", table.item)
        assertEquals(listOf(936473, 936380, 936382), table.models.map { it.id })
        val doodads = org.json.JSONObject(json).getJSONArray("locations").getJSONObject(0).getJSONArray("doodads")
        for (reference in table.models) {
            assertTrue((0 until doodads.length()).any { index ->
                val doodad = doodads.getJSONObject(index)
                val position = doodad.getJSONArray("pos")
                doodad.getInt("model") == reference.id && position.getDouble(0).toFloat() == reference.position.x &&
                    position.getDouble(1).toFloat() == reference.position.y && position.getDouble(2).toFloat() == reference.position.z
            }, "missing doodad ${reference.id} at ${reference.position}")
        }
    }

    @Test fun theEmbassyHasBothViewsWithACameraAndASpawn() {
        val e = locations.first { it.id == "embassy" }
        assertEquals("Stormwind Embassy", e.name)
        for (v in listOf("inside", "outside")) {
            val view = assertNotNull(e.views[v], "no view $v")
            assertNotNull(view.camera, "$v has no camera")
            assertNotNull(view.spawn, "$v has no spawn")
        }
        // the inside spawn faces the room's camera (down -x), the outside one out of the door (+x)
        assertEquals(3.1416f, e.views.getValue("inside").facing, 1e-3f)
        assertEquals(9171, e.areaId)
    }

    @Test fun theEmbassysFilesAreThePacksWithoutRepeats() {
        val e = locations.first { it.id == "embassy" }
        val all = e.files()
        assertEquals(all.size, all.map { it.first }.toSet().size)
        assertTrue(all.size > 200, "only ${all.size} files")
        assertTrue(e.packs.any { it.name == LocationInfo.SOUND })
        assertTrue(e.files(sound = false).size < all.size)
        // the WMO root and both its groups, and the ground's ADT files, are among them
        val ids = all.map { it.first }.toSet()
        for (id in listOf(1725413, 1725414, 1725417, 777622, 777625)) assertTrue(id in ids, "file $id isn't fetched")
        assertTrue(all.all { it.second > 0 }, "a file without its size")
    }

    @Test fun anEntryAloneWithItsCameraInsideAnObjectAndASpawnArray() {
        val one = LocationInfo.parseAll(
            """{"id":"x","wmo":{"root":5},"views":{"v":{"camera":{"eye":[1,2,3],"target":[4,5,6],"fov":40},"spawn":[7,8,9,1.5]}},
               "files":{"packs":[{"name":"a","files":[[5,10],[6,20]]},{"name":"b","files":[[6,20],[7,5]]}]}}""",
        ).single()
        val v = one.views.getValue("v")
        assertEquals(40f, v.camera!!.fov)
        assertEquals(6f, v.camera!!.at.z)
        assertEquals(9f, v.spawn!!.z)
        assertEquals(1.5f, v.facing)
        assertEquals(listOf(5, 6, 7), one.files().map { it.first })
        assertEquals(35L, one.bytes)
    }

    // ---- every location (the Embassy and the bedrooms: tools/locations.py)

    private val entries = org.json.JSONObject(json).getJSONArray("locations").let { a -> List(a.length()) { a.getJSONObject(it) } }

    @Test fun everyLocationParsesWithAUniqueIdAndTheEmbassyFirst() {
        assertEquals(entries.size, locations.size)
        assertEquals(locations.size, locations.map { it.id }.toSet().size, "an id is used twice")
        assertEquals("embassy", locations.first().id)
        assertTrue(locations.size >= 9, "only ${locations.size} locations")
        for (l in locations) {
            assertTrue(l.name.isNotBlank() && l.name != l.id, "${l.id} has no name")
            assertTrue(l.kind in setOf("interior+exterior", "interior", "composed"), "${l.id}: kind ${l.kind}")
            assertTrue(l.areaId > 0, "${l.id} has no area")
        }
    }

    @Test fun everyLocationHasAnInsideViewWithACameraAndASpawn() {
        // the scene list shows only locations with an inside camera (MainActivity's stages); the live scene starts there
        for (l in locations) {
            val inside = assertNotNull(l.views["inside"], "${l.id} has no inside view")
            assertNotNull(inside.camera, "${l.id}'s inside view has no camera")
            assertNotNull(inside.spawn, "${l.id}'s inside view has no spawn")
            assertNotNull(l.views["outside"]?.camera, "${l.id} has no outside camera")
        }
    }

    @Test fun everyLocationFetchesItsBuildingItsPropsAndItsGround() {
        for ((l, e) in locations.zip(entries)) {
            val ids = l.files().map { it.first }.toSet()
            assertEquals(l.files().size, ids.size, "${l.id} lists a file twice")
            assertTrue(l.files().all { it.second > 0 }, "${l.id}: a file without its size")
            val wmo = e.getJSONObject("wmo")
            assertTrue(wmo.getInt("root") in ids, "${l.id} doesn't fetch its building")
            val groups = wmo.getJSONArray("groups")
            for (i in 0 until groups.length()) assertTrue(groups.getInt(i) in ids, "${l.id} doesn't fetch group ${groups.getInt(i)}")
            val doodads = e.getJSONArray("doodads")
            for (i in 0 until doodads.length()) {
                val model = doodads.getJSONObject(i).getInt("model")
                assertTrue(model in ids, "${l.id} draws model $model without fetching it")
            }
            e.optJSONObject("terrain")?.let { t -> for (k in listOf("root", "tex0")) assertTrue(t.getInt(k) in ids, "${l.id}: ground $k") }
            assertTrue(e.getJSONObject("files").getJSONArray("missing").length() == 0, "${l.id} has files wago.tools didn't have")
        }
    }

    @Test fun everyBedroomHasABedAndItsSpotsAreInsideTheBuilding() {
        for ((l, e) in locations.zip(entries)) {
            if (l.id == "embassy") continue
            assertTrue(l.interactions.any { it.item == "BED" }, "${l.id} has no bed")
            assertTrue(l.people.isNotBlank(), "${l.id}: whose home is it?")
            val b = e.getJSONObject("wmo").getJSONArray("bounds")
            fun inside(p: com.logie.wifecraft.story.Vec3) =
                p.x >= b.getDouble(0) && p.y >= b.getDouble(1) && p.z >= b.getDouble(2) &&
                    p.x <= b.getDouble(3) && p.y <= b.getDouble(4) && p.z <= b.getDouble(5)
            val camera = l.views.getValue("inside").camera!!
            assertTrue(inside(camera.eye) && inside(camera.at), "${l.id}'s camera is outside its building")
            for (spot in l.interactions) {
                assertTrue(spot.item in setOf("BED", "CHAIR", "TABLE", "COUCH"), "${l.id}: ${spot.item}")
                assertTrue(inside(spot.entry) && inside(spot.pose), "${l.id}'s ${spot.item} is outside its building")
                assertTrue(spot.models.isNotEmpty(), "${l.id}'s ${spot.item} names no model")
                if (spot.item == "BED") assertTrue(spot.pose.z > spot.entry.z, "${l.id}: one lies on the bed, not under it")
                // the furniture is one of the location's own placements, where the entry says
                val doodads = e.getJSONArray("doodads")
                for (m in spot.models) assertTrue((0 until doodads.length()).any { i ->
                    val d = doodads.getJSONObject(i)
                    val p = d.getJSONArray("pos")
                    d.getInt("model") == m.id && p.getDouble(0).toFloat() == m.position.x &&
                        p.getDouble(1).toFloat() == m.position.y && p.getDouble(2).toFloat() == m.position.z
                }, "${l.id}: ${spot.item}'s model ${m.id} at ${m.position} isn't placed")
            }
        }
    }

    @Test fun aSpotWithoutAPoseIsUsedWhereItIsEntered() {
        val one = LocationInfo.parseAll(
            """{"id":"x","wmo":{"root":5},"interactions":[{"object":"CHAIR","entry":[1,2,3],"facing":1,"models":[{"id":9,"position":[1,2,3]}]},
               {"object":"BED","entry":[1,2,3],"pose":[4,5,6],"facing":0,"models":[{"id":8,"position":[4,5,6]}]}],"people":"orc"}""",
        ).single()
        assertEquals(one.interactions[0].entry, one.interactions[0].pose)
        assertEquals(6f, one.interactions[1].pose.z)
        assertEquals("orc", one.people)
    }

    // ---- WmoFiles

    private fun chunk(out: ByteArrayOutputStream, tag: String, body: ByteArray) {
        out.write(tag.reversed().toByteArray(Charsets.ISO_8859_1))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array())
        out.write(body)
    }

    private fun ints(vararg v: Int): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putInt(it) } }.array()

    @Test fun aRootNamesItsGroupsTexturesAndTheChosenSetsModels() {
        val out = ByteArrayOutputStream()
        chunk(out, "MVER", ints(17))
        chunk(out, "MOHD", ints(0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        val momt = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        momt.putInt(12, 100); momt.putInt(24, 101); momt.putInt(64 + 12, 102)
        chunk(out, "MOMT", momt.array())
        val mods = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        mods.putInt(20, 0); mods.putInt(24, 1)          // set 0: doodad 0
        mods.putInt(32 + 20, 1); mods.putInt(32 + 24, 2) // set 1: doodads 1, 2
        chunk(out, "MODS", mods.array())
        chunk(out, "MODI", ints(500, 501, 502))
        val modd = ByteBuffer.allocate(120).order(ByteOrder.LITTLE_ENDIAN)
        modd.putInt(0, 0); modd.putInt(40, 2 or (1 shl 24)); modd.putInt(80, 1)   // (high bits are flags)
        chunk(out, "MODD", modd.array())
        chunk(out, "GFID", ints(200, 201, 202, 203))     // two groups, then their LODs
        val root = out.toByteArray()
        assertTrue(WmoFiles.isWmo(root))
        val d0 = WmoFiles.deps(root)
        assertEquals(listOf(200, 201), d0.groups)
        assertEquals(listOf(100, 101, 102), d0.textures)
        assertEquals(listOf(500), d0.models)
        assertEquals(listOf(500, 502, 501), WmoFiles.deps(root, listOf(1)).models)
        assertFalse(WmoFiles.isWmo("MD21....".toByteArray()))
    }

    @Test fun theEmbassysRealRootWhenItIsHere() {
        // the downloaded root (tests run without game files: skipped then)
        val f = File(System.getProperty("wifecraft.gamefiles") ?: return, "1725413").takeIf { it.isFile } ?: return
        val d = WmoFiles.deps(f.readBytes(), listOf(1))
        assertEquals(listOf(1725414, 1725417), d.groups)
        val ids = locations.first { it.id == "embassy" }.files().map { it.first }.toSet()
        // (its materials name more textures than the groups use: those the entry leaves out aren't drawn)
        for (id in d.groups + d.models) assertTrue(id in ids, "the embassy's root names $id, which the entry doesn't fetch")
        assertTrue(d.models.size > WmoFiles.deps(f.readBytes()).models.size, "set 1 adds no models")
    }
}
