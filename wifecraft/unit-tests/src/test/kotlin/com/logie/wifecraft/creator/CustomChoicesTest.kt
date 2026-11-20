package com.logie.wifecraft.creator

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wifecraft's own choices (CustomChoices) on the creator's fixtures (resources/creator: a human woman, race 1; Skin Color
 * 10 with 100 and 101, whose material 1001 lays file 3001; Face 110's row hanging on 101 lays 3011). Invented ids only.
 */
class CustomChoicesTest {
    private val tables = CsvTables(File(javaClass.getResource("/creator/ChrModel.csv")!!.toURI()).parentFile)
    private val custom = CustomChoice(-9001, 1, Sex.FEMALE, "Skin Color", "Gold", 0xAA8035, base = 101, tint1 = 0xFFFFE7, tint2 = 0x392A1E)
    private val catalog = Catalog.build(tables, listOf(custom))

    @Test fun appendedToItsOptionWithItsSwatch() {
        val skin = catalog.model(1, Sex.FEMALE)!!.options.first { it.id == 10 }
        assertEquals(listOf(100, 101, -9001), skin.choices.map { it.id })
        assertEquals("Gold", skin.choices.last().name)
        assertEquals(0xAA8035, skin.choices.last().swatch1)
        assertEquals(10, catalog.optionOf(-9001))
        assertFalse(catalog.isNone(-9001))
        // not on another sex's or race's option, nor where its base isn't one of the option's choices
        assertTrue(catalog.model(1, Sex.MALE)!!.options.flatMap { o -> o.choices.map { it.id } }.none { it < 0 })
        val elsewhere = Catalog.build(tables, listOf(custom.copy(base = 999)))
        assertTrue(elsewhere.model(1, Sex.FEMALE)!!.options.flatMap { o -> o.choices.map { it.id } }.none { it < 0 })
        // the game's own catalogue has nothing of these fixtures' races
        assertTrue(Catalog.build(tables).model(1, Sex.FEMALE)!!.options.flatMap { o -> o.choices.map { it.id } }.none { it < 0 })
    }

    @Test fun drawnAsItsBaseTinted() {
        val base = LookResolver.resolve(catalog, tables, Appearance(1, Sex.FEMALE, mapOf(10 to 101, 11 to 110)))
        val mine = LookResolver.resolve(catalog, tables, Appearance(1, Sex.FEMALE, mapOf(10 to -9001, 11 to 110)))
        assertEquals(base.layers.map { it.fileId }, mine.layers.map { it.fileId })
        assertTrue(base.layers.all { it.tint1 == null && it.tint2 == null })
        // the skin's own texture and the face's that hangs on the base, tinted; nothing else
        for (f in listOf(3001, 3011)) {
            val l = mine.layers.first { it.fileId == f }
            assertEquals(0xFFFFE7, l.tint1)
            assertEquals(0x392A1E, l.tint2)
        }
        assertTrue(mine.layers.filter { it.fileId !in setOf(3001, 3011) }.all { it.tint1 == null })
        // as the renderer takes it (Dressing.call's tints: 0xAARRGGBB)
        assertEquals(0xFFFFFFE7.toInt(), Dressing.tint(mine.layers.first { it.fileId == 3001 }.tint1))
    }

    @Test fun pickedInTheCreatorAndSavedLikeAnyChoice() {
        val m = CreatorModel(catalog, Appearance(1, Sex.FEMALE))
        m.choose(10, -9001)
        assertEquals(-9001, m.appearance.picks[10])
        val back = Appearance.fromJson(JSONObject(m.appearance.toJson().toString()))
        assertEquals(m.appearance, back)
        // an old save (no custom choice) is read as before
        val old = Appearance.fromJson(JSONObject("""{"race":1,"sex":"FEMALE","picks":{"10":100}}"""))
        assertEquals(100, old.picks[10])
        assertNull(old.picks.values.firstOrNull { it < 0 })
        // a custom id where the catalogue doesn't have it is the option's first choice
        val r = LookResolver.resolve(Catalog.build(tables), tables, Appearance(1, Sex.FEMALE, mapOf(10 to -9001)))
        assertTrue(r.layers.none { it.tint1 != null })
    }
}
