package com.logie.wifecraft.creator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransmogTest {
    private fun tables(vararg entries: Pair<String, String>) = object : Tables {
        private val parsed = entries.associate { it.first to CsvTables.parse(it.second.trimIndent()) }
        override fun rows(name: String) = parsed[name].orEmpty()
    }

    @Test fun outfitsUseNamedItemsAndNormalizeRobesToTorso() {
        val catalog = TransmogCatalog.load(tables(
            "ItemAppearance" to "ID,ItemDisplayInfoID\n3,31\n4,41\n5,51",
            "ItemModifiedAppearance" to """
                ItemID,ItemAppearanceID,ItemAppearanceModifierID
                1,5,2
                1,3,0
                2,4,0
            """,
            "ItemSparse" to "ID,InventoryType,Display_lang\n1,20,Silk Robe\n2,10,Silk Gloves",
            "ItemSet" to "ID,Name_lang,ItemID_0,ItemID_1\n7,Silk Outfit,1,2",
        ))
        assertEquals(mapOf(5 to 31, 10 to 41), catalog.outfits.single().displays)
        assertEquals(ClothingSlot.TORSO, catalog.pieces.single { it.item == 1 }.slot)
        val appearance = Appearance(1, Sex.FEMALE, clothing = catalog.outfits.single().displays)
        assertEquals(appearance, Appearance.fromJson(appearance.toJson()))
        assertTrue(Appearance.fromJson(Appearance(1, Sex.MALE).toJson()).clothing.isEmpty())
    }

    @Test fun clothingPaintsTheBodySectionAndIsDiscoveredForDownload() {
        val game = tables(
            "ItemDisplayInfo" to "ID,GeosetGroup_0,GeosetGroup_1\n41,2,1",
            "ItemDisplayInfoMaterialRes" to "ItemDisplayInfoID,MaterialResourcesID,ComponentSection\n41,55,2",
            "TextureFileData" to "FileDataID,MaterialResourcesID,UsageType\n601,55,0",
        )
        val bare = ResolvedLook(2, 1f, emptyMap(), emptyMap(), emptyList(), emptyList(), setOf(2))
        val dressed = CreatureLooks.transmog(game, Appearance(1, Sex.MALE, clothing = mapOf(10 to 41)), bare)
        assertEquals(3, dressed.geosetGroups[4])
        assertEquals(601, dressed.layers.single().fileId)
        assertEquals(1 shl 2, dressed.layers.single().region)
        assertEquals(setOf(2, 601), dressed.needsFiles)
        assertEquals(setOf(601), CreatureLooks.clothingFiles(game, setOf(41)).second)
    }

    @Test fun creatorUsesTheOriginalRaceAssetsAndTableEntries() {
        val game = tables("ChrRaces" to "ID,CreateScreenFileDataID\n86,7446723")
        assertEquals(132035, RaceBackdrop.file(game, 5))
        assertEquals(131921, RaceBackdrop.file(game, 10))
        assertEquals(313254, RaceBackdrop.file(game, 22))
        assertEquals(7446723, RaceBackdrop.file(game, 86))
        kotlin.test.assertNull(RaceBackdrop.file(game, 999))
    }
}
