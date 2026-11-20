package com.logie.wifecraft.characters

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.creator.ClothingPiece
import com.logie.wifecraft.creator.ClothingSet
import com.logie.wifecraft.creator.ClothingSlot
import com.logie.wifecraft.creator.CreatorModel
import com.logie.wifecraft.creator.CsvTables
import com.logie.wifecraft.creator.LookPreset
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.creator.TransmogCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The named characters' creator presets (CreatorPresets, characters.json's "presets") on the creator's fixtures
 * (resources/creator: a human woman, race 1; Skin Color 10: 100, 101; Face 11: 110, 111; Hair Style 12: 120, 121).
 * Invented ids only.
 */
class CreatorPresetsTest {
    private val tables = CsvTables(File(javaClass.getResource("/creator/ChrModel.csv")!!.toURI()).parentFile)
    private val catalog = Catalog.build(tables)

    private val json = """
        {"characters":[{"id":"a","name":"A","variants":[],"presets":[
          {"key":"p","label":"Preset A","kind":"outfit","race":1,"sex":"FEMALE","picks":{"10":101,"11":111,"12":121,"999":5},
           "body":{"shape":0,"style":0,"density":0},"equipment":{"5":31,"10":41},"sourceDisplay":9500}
        ]}]}
    """.trimIndent()

    @Test fun aPresetIsANamedOutfitWithItsLook() {
        val roster = CharacterRoster.parse(json)
        assertEquals(1, roster.single().presets.size)
        assertTrue(roster.single().variants.isEmpty(), "a preset is not a variant")
        // round trip
        assertEquals(roster, CharacterRoster.parse(CharacterRoster.toJson(roster).toString()))
        val set = CreatorPresets.outfits(roster).single()
        assertEquals(-1, set.id)
        assertEquals("Preset A", set.name)
        assertEquals(mapOf(5 to 31, 10 to 41), set.displays)
        assertEquals(LookPreset(1, Sex.FEMALE, mapOf(10 to 101, 11 to 111, 12 to 121, 999 to 5)), set.look)
        assertTrue(CreatorPresets.outfits("not json").isEmpty())
        assertTrue(CreatorPresets.outfits(null as String?).isEmpty())
    }

    @Test fun namedOutfitsComeFirstWithTheirTorsosIcon() {
        val catalog = TransmogCatalog(
            listOf(ClothingPiece(1, 31, "Robe", ClothingSlot.TORSO, icon = 77), ClothingPiece(2, 41, "Gloves", ClothingSlot.GLOVES, icon = 78)),
            listOf(ClothingSet(7, "Silk", mapOf(5 to 31, 10 to 41), 77)),
        )
        val named = catalog.withNamed(CreatorPresets.outfits(CharacterRoster.parse(json)))
        assertEquals(listOf(-1, 7), named.outfits.map { it.id })
        assertEquals(77, named.outfits.first().icon)
        assertEquals(catalog.pieces, named.pieces)
        assertEquals(catalog, catalog.withNamed(emptyList()))
    }

    @Test fun theLookIsPutOnOnlyForItsRaceAndSex() {
        val set = CreatorPresets.outfits(CharacterRoster.parse(json)).single()
        val m = CreatorModel(catalog, Appearance(1, Sex.FEMALE))
        m.setClothing(set.displays)
        assertTrue(m.applyLook(assertNotNull(set.look)))
        assertEquals(101, m.appearance.picks[10])
        assertEquals(111, m.appearance.picks[11])
        assertEquals(121, m.appearance.picks[12])
        assertNull(m.appearance.picks[999], "an option the creator doesn't show is left out")
        assertEquals(mapOf(5 to 31, 10 to 41), m.appearance.clothing)
        assertTrue(m.appearance.gear)

        val man = CreatorModel(catalog, Appearance(1, Sex.MALE))
        val before = man.appearance
        assertFalse(man.applyLook(set.look!!))
        assertEquals(before, man.appearance)
        // a choice not of its option is left out
        val other = CreatorModel(catalog, Appearance(1, Sex.FEMALE))
        assertFalse(other.applyLook(LookPreset(1, Sex.FEMALE, mapOf(10 to 121))))
    }
}
