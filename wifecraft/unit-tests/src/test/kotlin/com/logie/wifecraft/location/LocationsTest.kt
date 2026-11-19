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
