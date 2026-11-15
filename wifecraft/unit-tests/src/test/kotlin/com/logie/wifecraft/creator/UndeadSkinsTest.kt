package com.logie.wifecraft.creator

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wrath++'s undead skins: synthetic packages laid out as undead_pack.py makes them (manifest.json, format 1, and
 * palettized BLP2 textures under client/ at the paths it names); the tables are the creator tests' own small ones.
 * The user's own package is checked by RealImportsTest when WIFECRAFT_UNDEAD_ZIP is given.
 */
class UndeadSkinsTest {
    private val names = MapNames(emptyMap())
    private val tables = CsvTables(File(javaClass.getResource("/creator/ChrModel.csv")!!.toURI()).parentFile)
    private val catalog = Catalog.build(tables)
    private fun tmp(): File = Files.createTempDirectory("undead").toFile()

    /** A palettized BLP2 of [w] x [h] whose index at (row, col) is row * w + col (mod 256), alpha 8 bits when [alpha]. */
    private fun blp(w: Int, h: Int, alpha: Boolean = false): ByteArray {
        val n = w * h
        val b = ByteBuffer.allocate(1172 + n * if (alpha) 2 else 1).order(ByteOrder.LITTLE_ENDIAN)
        b.put("BLP2".toByteArray()); b.putInt(1); b.put(1); b.put(if (alpha) 8 else 0); b.put(0); b.put(1)
        b.putInt(w); b.putInt(h); b.putInt(1172); repeat(15) { b.putInt(0) }; b.putInt(n * if (alpha) 2 else 1); repeat(15) { b.putInt(0) }
        for (i in 0 until 256) b.putInt(i * 0x010101)
        for (i in 0 until n) b.put(i.toByte())
        if (alpha) for (i in 0 until n) b.put((255 - i).toByte())
        return b.array()
    }

    private fun zip(vararg e: Pair<String, ByteArray>): ByteArray {
        val b = ByteArrayOutputStream()
        ZipOutputStream(b).use { z -> for ((n, d) in e) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return b.toByteArray()
    }

    private fun skin(folder: String, name: String, k: Int, label: String, swatch: Int) = JSONObject().put("name", label).put("swatch", swatch)
        .put("skin", "$folder\\${name}SkinUndead00_%02d.blp".format(k)).put("face", "$folder\\${name}FaceLowerUndead%02d.blp".format(k))

    /** Human women (head not turned) with two skins, orc women (turned) with one; [missing] leaves a face out. */
    private fun pack(prefix: String = "", missing: Boolean = false, kind: String = "wrathpp-undead-skins"): ByteArray {
        val human = JSONObject().put("race", 1).put("sex", 1).put("name", "HumanFemale").put("underwear", "none")
            .put("skins", JSONArray().put(skin("Character\\Human\\Female", "HumanFemale", 0, "Rot Green", 0x838b6f)).put(skin("Character\\Human\\Female", "HumanFemale", 1, "Grave Blue", 0x737b86)))
        val orc = JSONObject().put("race", 2).put("sex", 1).put("name", "OrcFemale").put("underwear", "race")
            .put("skins", JSONArray().put(skin("Character\\Orc\\Female", "OrcFemale", 0, "Ashen", 0x646162)))
        val manifest = JSONObject().put("format", 1).put("kind", kind).put("build", "9.2.7.45745").put("models", JSONObject().put("1_1", human).put("2_1", orc))
        val files = ArrayList<Pair<String, ByteArray>>()
        files += prefix + "manifest.json" to manifest.toString().toByteArray()
        for (k in 0..1) {
            files += prefix + "client/Character/Human/Female/HumanFemaleSkinUndead00_%02d.blp".format(k) to blp(8, 8)
            if (!(missing && k == 1)) files += prefix + "client/Character/Human/Female/HumanFemaleFaceLowerUndead%02d.blp".format(k) to blp(4, 2)
        }
        files += prefix + "client/Character/Orc/Female/OrcFemaleSkinUndead00_00.blp" to blp(8, 8)
        files += prefix + "client/Character/Orc/Female/OrcFemaleFaceLowerUndead00.blp" to blp(4, 2, alpha = true)
        files += prefix + "readme.txt" to "x".toByteArray()
        return zip(*files.toTypedArray())
    }

    @Test fun aTurnedFaceIsTurnedBackLosslessly() {
        val f = blp(4, 2, alpha = true)
        val r = UndeadSkins.rotateBack(f)
        assertEquals(2 to 4, UndeadSkins.size(r))
        // numpy: F = [[0,1,2,3],[4,5,6,7]]; rot90(F, k=-1) = [[4,0],[5,1],[6,2],[7,3]]
        assertContentEquals(byteArrayOf(4, 0, 5, 1, 6, 2, 7, 3), r.copyOfRange(1172, 1180))
        assertContentEquals(byteArrayOf(251.toByte(), 255.toByte(), 250.toByte(), 254.toByte(), 249.toByte(), 253.toByte(), 248.toByte(), 252.toByte()), r.copyOfRange(1180, 1188))
        assertContentEquals(f.copyOfRange(148, 1172), r.copyOfRange(148, 1172))          // the palette as it was
        assertEquals(8, r[9].toInt())
        // turned back from the turn numpy made: the picture itself
        val back = UndeadSkins.rotateBack(UndeadSkins.rotateBack(UndeadSkins.rotateBack(r)))
        assertContentEquals(f.copyOfRange(1172, 1180), back.copyOfRange(1172, 1180))
    }

    @Test fun thePackageGivesEachRaceAndSexItsSkins() {
        val root = tmp(); val store = ImportStore(root, names)
        assertFalse(store.hasUndead())
        val st = store.importZip(ImportKind.UNDEAD, ByteArrayInputStream(pack()), "wrathpp-undead-skins.zip")
        assertTrue(st.installed); assertEquals(6, st.files)                        // 3 bodies, 3 faces (the orc woman's turned)
        assertTrue(store.hasUndead()); assertFalse(store.hasBody()); assertFalse(store.hasRalia())
        val human = store.undeadSkins(1, Sex.FEMALE)
        assertEquals(listOf("Rot Green", "Grave Blue"), human.map { it.name })
        assertEquals(0x737b86, human[1].swatch)
        assertEquals(human[1].skin, store.undeadSkin(1, Sex.FEMALE, 1)); assertEquals(human[0].face, store.undeadFace(1, Sex.FEMALE, 0))
        assertTrue(human.all { it.skin >= ImportStore.FIRST_ID && File(root, "game/${it.skin}").isFile && File(root, "game/${it.face}").isFile })
        assertEquals(4 to 2, UndeadSkins.size(File(root, "game/${human[0].face}").readBytes()))     // the human woman's head isn't turned
        val orc = store.undeadSkins(2, Sex.FEMALE).single()
        assertEquals(2 to 4, UndeadSkins.size(File(root, "game/${orc.face}").readBytes()))          // the orc woman's is: turned back
        assertEquals("none", store.undeadUnderwear(1, Sex.FEMALE)); assertEquals("race", store.undeadUnderwear(2, Sex.FEMALE))
        assertTrue(store.undeadSkins(1, Sex.MALE).isEmpty()); assertNull(store.undeadSkin(1, Sex.MALE, 0))
        assertEquals(ImportStore.UndeadSummary(listOf("1_1", "2_1"), 3), store.undeadSummary())
        // read back from disk; then taken off
        assertEquals(human, ImportStore(root, names).undeadSkins(1, Sex.FEMALE))
        store.remove(ImportKind.UNDEAD)
        assertFalse(store.hasUndead()); assertFalse(File(root, "game/${human[0].skin}").exists())
    }

    @Test fun aBadPackageIsRefusedAndLeavesWhatWasThere() {
        val root = tmp(); val store = ImportStore(root, names)
        store.importZip(ImportKind.UNDEAD, ByteArrayInputStream(pack()), "a")
        val before = store.undeadSkins(1, Sex.FEMALE)
        assertFailsWith<ImportException> { store.importZip(ImportKind.UNDEAD, ByteArrayInputStream(pack(missing = true)), "b") }
        assertFailsWith<ImportException> { store.importZip(ImportKind.UNDEAD, ByteArrayInputStream(pack(kind = "something")), "c") }
        assertFailsWith<ImportException> { store.importZip(ImportKind.UNDEAD, ByteArrayInputStream(zip("x.blp" to blp(2, 2))), "d") }
        assertEquals(before, store.undeadSkins(1, Sex.FEMALE))
        assertTrue(File(root, "game/${before[0].face}").isFile)
        // zipped again inside a folder: taken in
        assertEquals(3, ImportStore(tmp(), names).also { it.importZip(ImportKind.UNDEAD, ByteArrayInputStream(pack(prefix = "wrathpp-undead/")), "e") }
            .undeadSummary()!!.skins)
    }

    @Test fun anImportedFileDeletedFromGameComesBackFromTheImportsOwnCopy() {
        val root = tmp()
        val store = ImportStore(root, names)
        store.importZip(ImportKind.UNDEAD, ByteArrayInputStream(pack()), "a")
        val face = store.undeadSkins(2, Sex.FEMALE).single().face
        val bytes = File(root, "game/$face").readBytes()
        File(root, "game/$face").delete()
        ImportStore(root, names)
        assertContentEquals(bytes, File(root, "game/$face").readBytes())
    }

    // --- the creator ----------------------------------------------------------------------------------------------

    private fun undeadRow(m: CreatorModel) = m.rows(m.undeadCategory()!!).firstOrNull { it.option.id == CreatorModel.UNDEAD }
    private fun rowNames(m: CreatorModel, cat: String) = m.rows(m.categories().first { it.name == cat }.id).map { it.option.name }

    @Test fun theUndeadSwitchIsUnderAccessoriesAndSaysToImportWithoutThePackage() {
        val m = CreatorModel(catalog, Appearance(1, Sex.FEMALE)).also { it.bodySource = Overrides.None }
        assertEquals(m.categories().first { it.name == "Accessories" }.id, m.undeadCategory())
        val r = assertNotNull(undeadRow(m))
        assertFalse(r.enabled); assertEquals(UndeadSkins.NOT_IMPORTED, r.why)
        m.step(CreatorModel.UNDEAD, 1); assertFalse(m.appearance.undead)
        // order: the category's own options, Undead, Gear last
        assertEquals(listOf("Glow", "Undead", "Gear"), rowNames(m, "Accessories"))
        // not on the Forsaken, nor an allied race
        assertNull(undeadRow(CreatorModel(catalog, Appearance(29, Sex.FEMALE)).also { it.bodySource = Overrides.None }))
    }

    @Test fun undeadOnListsTheSkinsHidesTheFaceAndGoesBackToTheLivingSkin() {
        val root = tmp(); val store = ImportStore(root, names)
        store.importZip(ImportKind.UNDEAD, ByteArrayInputStream(pack()), "wrathpp-undead-skins.zip")
        val m = CreatorModel(catalog, Appearance(1, Sex.FEMALE)).also { it.bodySource = store }
        m.choose(10, 101)                                                             // a living skin colour
        val living = m.appearance.picks
        assertTrue(undeadRow(m)!!.enabled)
        assertEquals(listOf("Skin Color", "Face"), rowNames(m, "Face").take(2))
        m.step(CreatorModel.UNDEAD, 1)
        assertTrue(m.appearance.undead && m.isUndead)
        assertEquals(1, undeadRow(m)!!.index)
        val face = rowNames(m, "Face")
        assertFalse("Face" in face)
        val skinRow = m.rows(m.categories().first { it.name == "Face" }.id).first { it.option.name == "Skin Color" }
        assertEquals(CreatorModel.UNDEAD_SKIN, skinRow.option.id)
        assertEquals(listOf("Rot Green", "Grave Blue"), skinRow.option.choices.map { it.name })
        assertEquals(0x838b6f, skinRow.option.choices[0].swatch1)
        m.step(CreatorModel.UNDEAD_SKIN, 1); assertEquals(1, m.appearance.undeadSkin)
        m.step(CreatorModel.UNDEAD_SKIN, 1); assertEquals(0, m.appearance.undeadSkin)              // round the end
        m.choose(CreatorModel.UNDEAD_SKIN, 1); assertEquals(1, m.appearance.undeadSkin)
        // saved and read back
        val json = m.appearance.toJson()
        assertTrue(json.getBoolean("undead")); assertEquals(1, json.getInt("undeadSkin"))
        assertEquals(m.appearance, Appearance.fromJson(JSONObject(json.toString())))
        // the dice keep Undead on and pick one of its skins
        m.random = kotlin.random.Random(3); m.randomize(); assertTrue(m.appearance.undead)
        // off: the living skin colour as it was
        m.choose(CreatorModel.UNDEAD, 0)
        assertFalse(m.appearance.undead)
        assertTrue(m.appearance.toJson().has("undead").not())
        m.choose(10, 101)
        assertEquals(101, m.appearance.picks[10])
        assertTrue("Face" in rowNames(m, "Face"))
        // to a race the package hasn't: off; back, as it was asked
        m.choose(CreatorModel.UNDEAD, 1)
        m.setRace(25); assertFalse(m.appearance.undead)
        assertTrue(living.isNotEmpty())
    }

    @Test fun theResolverLaysTheUndeadSkinOverTheRacesOwn() {
        val root = tmp(); val store = ImportStore(root, names)
        store.importZip(ImportKind.UNDEAD, ByteArrayInputStream(pack()), "z")
        val skin = store.undeadSkins(1, Sex.FEMALE)[1]
        val living = LookResolver.resolve(catalog, tables, Appearance(1, Sex.FEMALE), store)
        val faceLayer = living.layers.single { it.region == 1024 }                  // the Face option's (target 5)
        val a = Appearance(1, Sex.FEMALE, undead = true, undeadSkin = 1)
        val look = LookResolver.resolve(catalog, tables, a, store)
        assertTrue(look.layers.none { it.fileId == faceLayer.fileId && it.region == 1024 }, "the living face goes")
        val head = UndeadSkins.head(1, Sex.FEMALE)!!
        val base = look.layers.first { it.textureType == 1 }
        assertEquals(TextureLayer(skin.skin, -1, 1, base.layer, null, null, 1, UndeadSkins.BODY), look.layers.single { it.fileId == skin.skin })
        assertEquals(TextureLayer(skin.face, -1, 1, base.layer, null, null, 1, head.rect), look.layers.single { it.fileId == skin.face })
        // after the race's own colour on its layer: the body, then the face; the rest over them
        val idx = look.layers.indexOfFirst { it.fileId == skin.skin }
        assertTrue(look.layers.indexOf(base) < idx && look.layers[idx + 1].fileId == skin.face)
        assertTrue(skin.skin in look.needsFiles && skin.face in look.needsFiles)
        assertEquals(emptyList(), look.diagnostics.filter { it.startsWith("Undead") })
        // the boxes reach the renderer as the material's pixels
        assertContentEquals(intArrayOf(0, 0, 1024, 1024), Dressing.boxOf(UndeadSkins.BODY, 2048, 1024))
        assertContentEquals(intArrayOf(1032, 2, 984, 964), Dressing.boxOf(head.rect, 2048, 1024))
        // without the package: drawn living, and said so
        val none = LookResolver.resolve(catalog, tables, a, Overrides.None)
        assertTrue(none.diagnostics.any { it == "Undead: " + UndeadSkins.NOT_IMPORTED })
        assertEquals(living.layers, none.layers)
    }

    @Test fun everyRaceOfThePackageHasItsHead() {
        for (r in UndeadSkins.RACES) for (s in Sex.values()) {
            val h = assertNotNull(UndeadSkins.head(r, s), "$r $s")
            assertTrue(h.u0 >= 256 && h.u1 <= 512 && h.u0 < h.u1 && h.v0 < h.v1 && h.v1 <= 512)
        }
    }
}
