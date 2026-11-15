package com.logie.wifecraft.creator

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic zips built here; the user's own were not available. */
class ImportStoreTest {
    private val names = MapNames(mapOf(
        "character/human/female/humanfemaleskin00_03.blp" to 100,
        "character/human/female/humanfemaleskin00_03_hd.blp" to 101,
        "character/human/female/humanfemalenakedpelvisskin00_03.blp" to 102,
        "character/human/male/humanmaleskin00_00.blp" to 103,
    ))

    private fun tmp(): File = Files.createTempDirectory("imports").toFile()
    private fun zip(vararg e: Pair<String, ByteArray>): ByteArray {
        val b = ByteArrayOutputStream()
        ZipOutputStream(b).use { z -> for ((n, d) in e) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return b.toByteArray()
    }
    private fun ByteArray.stream() = ByteArrayInputStream(this)
    private fun sha(d: ByteArray) = MessageDigest.getInstance("SHA-256").digest(d).joinToString("") { "%02x".format(it) }

    @Test fun raliaTexturesGoUnderSyntheticIdsAndReplaceRetail() {
        val root = tmp(); val store = ImportStore(root, names)
        val z = zip("Character/Human/Female/humanfemaleskin00_03_hd.blp" to byteArrayOf(1, 2, 3),
            "Character/Human/Male/humanmaleskin00_00.blp" to byteArrayOf(9), "readme.txt" to byteArrayOf(0),
            "Character/Human/Female/unknownthing.blp" to byteArrayOf(5))
        val st = store.importZip(ImportKind.RALIA, z.stream(), "ralia.zip")
        assertTrue(st.installed); assertEquals(2, st.files); assertEquals(1, st.unmatched); assertTrue(st.active)
        val female = store.texture(100, true); val hd = store.texture(101, true)
        assertEquals(female, hd)                                   // the file stands for both of the game's names
        assertTrue(female >= ImportStore.FIRST_ID)
        assertEquals(listOf<Byte>(1, 2, 3), File(root, "game/$female").readBytes().toList())
        assertNotEquals(103, store.texture(103, false))
        assertEquals(555, store.texture(555, true))                // not replaced: itself
        assertTrue(store.hasRalia()); assertFalse(store.hasBody())
        assertTrue(File(root, "imports/ralia/meta.json").isFile)
    }

    @Test fun idsAreStableAcrossImportsAndInstances() {
        val root = tmp()
        val z = zip("Character/Human/Female/humanfemaleskin00_03_hd.blp" to byteArrayOf(1))
        val a = ImportStore(root, names); a.importZip(ImportKind.RALIA, z.stream(), "a")
        val id = a.texture(100, true)
        a.importZip(ImportKind.RALIA, zip("Character/Human/Female/humanfemaleskin00_03_hd.blp" to byteArrayOf(7)).stream(), "b")
        assertEquals(id, a.texture(100, true))
        assertEquals(7, File(root, "game/$id").readBytes()[0].toInt())
        assertEquals(id, ImportStore(root, names).texture(100, true))   // read back from disk
    }

    @Test fun alternateTakesWomenOnlyAndOneSetIsActive() {
        val root = tmp(); val store = ImportStore(root, names)
        store.importZip(ImportKind.RALIA, zip("Character/Human/Male/humanmaleskin00_00.blp" to byteArrayOf(1)).stream(), "r")
        val st = store.importZip(ImportKind.ALTERNATE, zip(
            "Character/Human/Female/humanfemaleskin00_03.blp" to byteArrayOf(2),
            "Character/Human/Male/humanmaleskin00_00.blp" to byteArrayOf(3)).stream(), "a")
        assertEquals(1, st.files)
        assertEquals(ImportKind.ALTERNATE, store.activeTextures)
        assertEquals(103, store.texture(103, false))               // the men are retail while the women's set is on
        assertNotEquals(100, store.texture(100, true))
        store.setActive(ImportKind.RALIA)
        assertEquals(100, store.texture(100, true))
        assertNotEquals(103, store.texture(103, false))
        assertTrue(store.status(ImportKind.ALTERNATE).installed && !store.status(ImportKind.ALTERNATE).active)
    }

    @Test fun badZipsAreRefusedAndLeaveWhatWasThere() {
        val root = tmp(); val store = ImportStore(root, names)
        store.importZip(ImportKind.RALIA, zip("Character/Human/Female/humanfemaleskin00_03.blp" to byteArrayOf(1)).stream(), "good")
        val id = store.texture(100, true)
        assertFailsWith<ImportException> { store.importZip(ImportKind.RALIA, zip("notes.txt" to byteArrayOf(1)).stream(), "x") }
        assertFailsWith<ImportException> { store.importZip(ImportKind.RALIA, zip("Character/Human/Female/zzz.blp" to byteArrayOf(1)).stream(), "x") }
        assertFailsWith<ImportException> { store.importZip(ImportKind.RALIA, zip("../evil.blp" to byteArrayOf(1)).stream(), "x") }
        assertFailsWith<ImportException> { store.importZip(ImportKind.RALIA, zip("Character/../../evil.blp" to byteArrayOf(1)).stream(), "x") }
        assertFailsWith<ImportException> { store.importZip(ImportKind.ALTERNATE, zip("Character/Human/Male/humanmaleskin00_00.blp" to byteArrayOf(1)).stream(), "x") }
        assertEquals(id, store.texture(100, true))
        assertEquals(1, File(root, "game/$id").readBytes()[0].toInt())
        assertFalse(File(root, "imports/ralia.new").exists())
        assertFalse(File(root.parentFile, "evil.blp").exists())
    }

    @Test fun namesNotLoadedRefusesTextures() {
        val store = ImportStore(tmp(), MapNames(emptyMap()))
        assertFailsWith<ImportException> { store.importZip(ImportKind.RALIA, zip("Character/a/b.blp" to byteArrayOf(1)).stream(), "x") }
    }

    @Test fun removeTakesFilesOffAndStopsLookingUntilPicked() {
        val root = tmp(); val store = ImportStore(root, names)
        val file = File(root, "dl/ralia.zip").apply { parentFile.mkdirs(); writeBytes(zip("Character/Human/Female/humanfemaleskin00_03.blp" to byteArrayOf(1))) }
        val first = store.lookFor(ImportKind.RALIA, file)
        assertTrue(first!!.installed)
        val id = store.texture(100, true)
        assertNull(store.lookFor(ImportKind.RALIA, file))          // nothing changed: not taken again
        store.remove(ImportKind.RALIA)
        assertFalse(File(root, "game/$id").exists())
        assertEquals(100, store.texture(100, true)); assertFalse(store.hasRalia())
        assertTrue(store.status(ImportKind.RALIA).removed)
        assertNull(store.lookFor(ImportKind.RALIA, file))          // not after Remove
        store.importZip(ImportKind.RALIA, file.inputStream(), "picked")   // picked by hand: looked for again
        assertFalse(store.status(ImportKind.RALIA).removed)
    }

    @Test fun lookForTakesAChangedFileAgainButNotOverAHandPickedZip() {
        val root = tmp(); val store = ImportStore(root, names)
        val file = File(root, "ralia.zip").apply { writeBytes(zip("Character/Human/Female/humanfemaleskin00_03.blp" to byteArrayOf(1))) }
        store.lookFor(ImportKind.RALIA, file)
        file.writeBytes(zip("Character/Human/Female/humanfemaleskin00_03.blp" to byteArrayOf(1, 2, 3, 4)))
        file.setLastModified(file.lastModified() + 5000)
        assertTrue(store.lookFor(ImportKind.RALIA, file) != null)
        store.importZip(ImportKind.RALIA, zip("Character/Human/Female/humanfemaleskin00_03.blp" to byteArrayOf(8)).stream(), "by hand")
        file.setLastModified(file.lastModified() + 5000)
        assertNull(store.lookFor(ImportKind.RALIA, file))  // a hand-picked zip has no mark: the found file is left alone
    }

    // ---- the body package

    private fun entry(name: String, d: ByteArray) = JSONObject().put("file", name).put("sha256", sha(d)).put("size", d.size)

    private fun bodyZip(corrupt: Boolean = false): ByteArray {
        val patch1 = byteArrayOf(1, 1); val patch2 = byteArrayOf(2, 2); val skin = byteArrayOf(3); val d11 = byteArrayOf(4); val none = byteArrayOf(5)
        val list = JSONObject().put("format", 2).put("kind", "wrathpp-body")
            .put("races", JSONArray().put(JSONObject().put("race", "Human").put("race_id", 1).put("folder", "Character/Human/Female").put("faction", "alliance")))
            .put("models", JSONArray()
                .put(JSONObject().put("race", "Human").put("shape", 0).put("patch", entry("Human/0.patch", patch1)).put("skin_file", entry("Human/0.skin", skin)))
                .put(JSONObject().put("race", "Human").put("shape", 2).put("patch", entry("Human/2.patch", patch2)).put("skin_file", entry("Human/shapes.skin", skin))))
            .put("styles", JSONObject().put("alliance", JSONArray().put("Rose").put("Vines")).put("horde", JSONArray()))
            .put("textures", JSONArray()
                .put(entry("designs/Rose_1.blp", d11).put("design", "Rose").put("density", 1))
                .put(entry("designs/none.blp", none).put("design", "").put("density", 0)))
        return zip("wrathpp-body.json" to list.toString().toByteArray(), "Human/0.patch" to patch1, "Human/2.patch" to (if (corrupt) byteArrayOf(9) else patch2),
            "Human/0.skin" to skin, "Human/shapes.skin" to skin, "designs/Rose_1.blp" to d11, "designs/none.blp" to none)
    }

    @Test fun bodyPackageGivesModelsAndLayers() {
        val root = tmp(); val store = ImportStore(root, names)
        val st = store.importZip(ImportKind.BODY, bodyZip().stream(), "wrathpp-body.zip")
        assertTrue(st.installed && store.hasBody() && !store.hasRalia())
        assertNull(store.bodyModel(1, 0)); assertNull(store.bodyModel(1, 1)); assertNull(store.bodyModel(4, 2))
        val m = store.bodyModel(1, 2)!!
        assertTrue(m >= ImportStore.FIRST_ID)
        assertEquals(listOf<Byte>(2, 2), File(root, "game/$m").readBytes().toList())
        assertNotNull(store.bodySkin(1, 2))
        val l = store.bodyLayer(1, 1, 1)!!
        assertEquals(4, File(root, "game/$l").readBytes()[0].toInt())
        assertNull(store.bodyLayer(1, 1, 2)); assertNull(store.bodyLayer(1, 2, 1)); assertNull(store.bodyLayer(1, 17, 1)); assertNull(store.bodyLayer(2, 1, 1))
        store.remove(ImportKind.BODY)
        assertFalse(File(root, "game/$m").exists()); assertNull(store.bodyModel(1, 2)); assertFalse(store.hasBody())
        assertEquals(m, ImportStore(root, names).also { it.importZip(ImportKind.BODY, bodyZip().stream(), "again") }.bodyModel(1, 2))  // ids stable
    }

    @Test fun bodyPackageThatDoesNotMatchItsListIsRefused() {
        val store = ImportStore(tmp(), names)
        assertFailsWith<ImportException> { store.importZip(ImportKind.BODY, bodyZip(corrupt = true).stream(), "x") }
        assertFailsWith<ImportException> { store.importZip(ImportKind.BODY, zip("a.txt" to byteArrayOf(1)).stream(), "x") }
        assertFailsWith<ImportException> { store.importZip(ImportKind.BODY, zip("wrathpp-body.json" to "{\"kind\":\"wrathpp-body\",\"format\":1}".toByteArray()).stream(), "x") }
        assertFalse(store.hasBody())
    }

    @Test fun idsStayInRangeAndDoNotCollide() {
        val root = tmp(); val store = ImportStore(root, MapNames((0 until 300).associate { "character/x/f$it.blp" to 1000 + it }))
        val z = zip(*(0 until 300).map { "Character/X/F$it.blp" to byteArrayOf(it.toByte()) }.toTypedArray())
        store.importZip(ImportKind.RALIA, z.stream(), "many")
        val ids = (0 until 300).map { store.texture(1000 + it, true) }
        assertEquals(300, ids.toSet().size)
        assertTrue(ids.all { it >= ImportStore.FIRST_ID && it < Int.MAX_VALUE })
    }

    private fun assertNotNull(v: Any?) = assertTrue(v != null)
}
