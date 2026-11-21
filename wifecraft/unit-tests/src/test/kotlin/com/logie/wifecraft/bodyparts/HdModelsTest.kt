package com.logie.wifecraft.bodyparts

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Sex
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HD Models' app side (bodyparts/HdModels.kt) on SYNTHETIC .whd sets (the layout tools/hd/whd_convert.py writes: a triangle
 * body, outfits by class): the header's reading and checks, the import runner (zips and loose sets, one per race and sex,
 * a failed import keeping the old sets, Remove per race), the options' save, and the decision every drawing path takes.
 */
class HdModelsTest {
    private fun tmp(): File = Files.createTempDirectory("hdmodels").toFile()

    /** A minimal .whd: one body triangle, [outfits] (label to classes), as the converter lays it out. */
    fun whd(race: Int, sex: Int, label: String = "Set $race/$sex", outfits: List<Pair<String, List<Int>>> = listOf(
        "Swimsuit" to listOf(0), "Druid" to listOf(11, 7), "Plate" to listOf(1, 2, 6)), body: Boolean = true, version: Int = 1): ByteArray {
        val vb = ByteBuffer.allocate(3 * 40).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 3) {
            vb.putFloat(i * 0.1f); vb.putFloat(0f); vb.putFloat(1f)
            vb.putFloat(1f); vb.putFloat(0f); vb.putFloat(0f)
            vb.putFloat(0f); vb.putFloat(0f)
            vb.put(0); vb.put(0); vb.put(0); vb.put(0)
            vb.put(255.toByte()); vb.put(0); vb.put(0); vb.put(0)
        }
        val ib = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(1).putInt(2)
        val blobs = listOf(vb.array(), ib.array())
        val meta = JSONObject().put("format", "whd").put("version", version).put("id", "test:$race").put("label", label)
            .put("race", race).put("sex", sex).put("bones", JSONArray(listOf("Pelvis")))
            .put("joints", JSONObject().put("Pelvis", JSONArray(listOf(0, 0, 1))).put("Neck1", JSONArray(listOf(0, 0, 1.5))))
            .put("textures", JSONArray())
            .put("meshes", JSONArray().put(JSONObject().put("id", "body").put("kind", if (body) "body" else "hair").put("vertices", 3)
                .put("triangles", 1).put("vertexBlob", 0).put("indexBlob", 1).put("parts", JSONArray())))
            .put("hair", JSONArray().put(JSONObject().put("id", "h1").put("label", "Swept").put("meshes", JSONArray())))
            .put("outfits", JSONArray(outfits.map { (l, c) -> JSONObject().put("id", l.lowercase()).put("label", l).put("classes", JSONArray(c)).put("meshes", JSONArray()) }))
            .put("overlays", JSONArray().put(JSONObject().put("id", "f1").put("label", "Mark").put("slot", "face").put("onto", 0).put("blob", 0)))
            .put("tints", JSONArray().put(JSONObject().put("id", "own").put("label", "Her own").put("rgb", "FFFFFF")))
            .put("triangles", JSONObject().put("body", 1).put("max", 1)).put("gpuBytes", 1000)
            .put("credit", "Model by Someone (example.org)")
        val js = meta.toString().toByteArray()
        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        head.put("WHD1".toByteArray()); head.putInt(version); head.putInt(js.size); head.putInt(blobs.size)
        out.write(head.array()); out.write(js)
        while (out.size() % 16 != 0) out.write(0)
        val table = out.size()
        var off = (table + 16 * blobs.size + 15) and 15.inv()
        val offs = blobs.map { b -> off.also { off = (off + b.size + 15) and 15.inv() } }
        for ((i, b) in blobs.withIndex()) out.write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(offs[i].toLong()).putLong(b.size.toLong()).array())
        for ((i, b) in blobs.withIndex()) { while (out.size() < offs[i]) out.write(0); out.write(b) }
        return out.toByteArray()
    }

    private fun zip(f: File, vararg entries: Pair<String, ByteArray>): File {
        ZipOutputStream(f.outputStream()).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return f
    }

    @Test
    fun readsAndChecksTheHeader() {
        val d = tmp()
        val f = File(d, "a.whd").apply { writeBytes(whd(4, 1, "Night elf woman (HD)")) }
        val info = HdSetInfo.read(f)
        assertEquals(4, info.race); assertEquals(1, info.sex); assertEquals(Sex.FEMALE, info.sexEnum); assertEquals(41, info.key)
        assertEquals("Night elf woman (HD)", info.label)
        assertEquals(listOf("Swept"), info.hair)
        assertEquals(listOf("Swimsuit", "Druid", "Plate"), info.outfits.map { it.label })
        assertEquals(listOf("Mark"), info.faceMarks)
        assertFalse(info.bodyMarks)
        assertEquals("Model by Someone (example.org)", info.credit)
        // not a set, cut short, newer, no body
        assertFailsWith<BodyPartFormatException> { HdSetInfo.read(File(d, "x.whd").apply { writeBytes(ByteArray(40)) }) }
        assertFailsWith<BodyPartFormatException> { HdSetInfo.read(File(d, "y.whd").apply { writeBytes(whd(4, 1).copyOf(200)) }) }
        val newer = assertFailsWith<BodyPartFormatException> { HdSetInfo.read(File(d, "z.whd").apply { writeBytes(whd(4, 1, version = 2)) }) }
        assertTrue(newer.message!!.contains("newer"))
        assertFailsWith<BodyPartFormatException> { HdSetInfo.read(File(d, "w.whd").apply { writeBytes(whd(4, 1, body = false)) }) }
    }

    @Test
    fun importsZipsAndSetsReplacesPerRaceAndKeepsOldOnFailure() {
        val d = tmp()
        val store = HdStore(File(d, "bodyparts"))
        assertFalse(store.installed()); assertEquals("Not imported", store.summary())
        val runner = HdImportRunner(store)
        val seen = ArrayList<HdImportRunner.State>()
        runner.listen { seen += it }
        val pick = File(d, "pick").apply { mkdirs() }
        val z = zip(File(pick, "Wifecraft-HD-NightElf.zip"), "NightElf_F.whd" to whd(4, 1, "NE"), "Human_F.whd" to whd(1, 1, "HU"), "readme.txt" to "x".toByteArray())
        runner.run(File(d, "work")) { w -> listOf(z.copyTo(File(w, z.name))) }
        val done = assertIs<HdImportRunner.State.Done>(runner.state, (runner.state as? HdImportRunner.State.Failed)?.message)
        assertTrue(done.message.contains("NE") && done.message.contains("HU"))
        assertTrue(seen.any { it is HdImportRunner.State.Running && it.line.startsWith("Reading") })
        assertEquals(listOf("1_1.whd", "4_1.whd"), store.sets().map { it.file.name })
        assertEquals("NE", store.forRace(4, Sex.FEMALE)?.label)
        assertNull(store.forRace(4, Sex.MALE))
        val sig = store.signature()
        // a loose set of the same race replaces that race's alone
        val loose = File(pick, "ne2.whd").apply { writeBytes(whd(4, 1, "NE2")) }
        runner.run(File(d, "work")) { w -> listOf(loose.copyTo(File(w, loose.name))) }
        assertIs<HdImportRunner.State.Done>(runner.state)
        assertEquals("NE2", store.forRace(4, Sex.FEMALE)?.label)
        assertEquals("HU", store.forRace(1, Sex.FEMALE)?.label)
        assertNotEquals(sig, store.signature())
        // a damaged set: nothing changes, the reason in words
        val bad = File(pick, "bad.whd").apply { writeBytes(ByteArray(64)) }
        runner.run(File(d, "work")) { w -> listOf(bad.copyTo(File(w, bad.name))) }
        assertIs<HdImportRunner.State.Failed>(runner.state)
        assertEquals(listOf("NE2", "HU").sorted(), store.sets().map { it.label }.sorted())
        // a zip with no set
        val none = zip(File(pick, "none.zip"), "a.txt" to "x".toByteArray())
        runner.run(File(d, "work")) { w -> listOf(none.copyTo(File(w, none.name))) }
        val f = assertIs<HdImportRunner.State.Failed>(runner.state)
        assertTrue(f.message.contains("No HD model set"))
        assertEquals(2, store.sets().size)
        // Remove one race, then all
        assertTrue(runner.remove(1, 1))
        assertEquals(listOf("NE2"), store.sets().map { it.label })
        assertTrue(runner.remove())
        assertFalse(store.installed())
        assertFalse(File(d, "work").exists())
    }

    @Test
    fun optionsSaveWithTheLookAndOlderSavesStayTheSame() {
        val o = HdOptions(hair = 2, outfit = -2, eyes = 1, faceMark = 3, bodyMarks = true, tint = 1, hairTint = 4, jewelry = listOf(0, 2))
        assertEquals(o, HdOptions.fromJson(o.toJson()))
        assertEquals(HdOptions(), HdOptions.fromJson(null))
        assertTrue(HdOptions().isDefault)
        val a = Appearance(4, Sex.FEMALE, hd = o)
        assertEquals(o, Appearance.fromJson(a.toJson()).hd)
        val old = Appearance(4, Sex.FEMALE)
        assertFalse(old.toJson().has("hd"))
        assertNull(Appearance.fromJson(old.toJson()).hd)
    }

    private fun ctx(on: Boolean = true): HdContext {
        val d = tmp()
        val sets = listOf(whd(4, 1, "NE"), whd(1, 1, "HU", outfits = emptyList())).mapIndexed { i, b -> HdSetInfo.read(File(d, "$i.whd").apply { writeBytes(b) }) }
        return HdContext(on, sets.associateBy { it.key }, "sig")
    }

    @Test
    fun theDecisionTable() {
        val c = ctx()
        val ne = Appearance(4, Sex.FEMALE)
        // off, no set, no look, a display
        assertFalse(HdDress.decide(ne, ne, ctx(on = false)).on)
        assertFalse(HdDress.decide(ne, ne, null).on)
        assertFalse(HdDress.decide(Appearance(4, Sex.MALE), Appearance(4, Sex.MALE), c).on)
        assertFalse(HdDress.decide(Appearance(2, Sex.FEMALE), Appearance(2, Sex.FEMALE), c).on)
        assertFalse(HdDress.decide(ne, null, c).on)
        assertFalse(HdDress.decide(null, ne, c).on)
        // always on, Gear on and off: Gear off is bare
        val bare = HdDress.decide(ne, ne.copy(gear = false), c)
        assertTrue(bare.on); assertEquals(-1, bare.outfit)
        // Gear on: the class's outfit; Civilian's; a class the set lacks takes the first non-civilian, with a note
        assertEquals(1, HdDress.decide(ne, ne.copy(classId = 11), c).outfit)
        assertNull(HdDress.decide(ne, ne.copy(classId = 11), c).note)
        assertEquals(2, HdDress.decide(ne, ne.copy(classId = 2), c).outfit)
        assertEquals(0, HdDress.decide(ne, ne.copy(classId = 0), c).outfit)
        val other = HdDress.decide(ne, ne.copy(classId = 8), c)
        assertEquals(1, other.outfit); assertNotNull(other.note)
        // her own outfit, and bare whatever Gear says
        assertEquals(2, HdDress.decide(ne.copy(hd = HdOptions(outfit = 2)), ne.copy(classId = 11), c).outfit)
        assertEquals(-1, HdDress.decide(ne.copy(hd = HdOptions(outfit = -2)), ne.copy(classId = 11), c).outfit)
        // a set without clothing: bare, said
        val hu = Appearance(1, Sex.FEMALE, classId = 1)
        val d = HdDress.decide(hu, hu, c)
        assertTrue(d.on); assertEquals(-1, d.outfit); assertNotNull(d.note)
        // the pictures' mark: none without a set, changes with her options
        assertEquals("", HdDress.cacheMark(c, Appearance(2, Sex.FEMALE)))
        assertEquals("", HdDress.cacheMark(ctx(on = false), ne))
        val m1 = HdDress.cacheMark(c, ne)
        assertTrue(m1.startsWith("_h"))
        assertNotEquals(m1, HdDress.cacheMark(c, ne.copy(hd = HdOptions(hair = 3))))
        assertNotEquals(m1, HdDress.cacheMark(c, ne.copy(gear = false)))
    }
}
