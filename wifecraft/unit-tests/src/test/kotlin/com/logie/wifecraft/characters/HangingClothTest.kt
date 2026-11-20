package com.logie.wifecraft.characters

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.creator.CsvTables
import com.logie.wifecraft.creator.Dressing
import com.logie.wifecraft.creator.LookResolver
import com.logie.wifecraft.creator.Overrides
import com.logie.wifecraft.creator.ResolvedLook
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.creator.Tables
import com.logie.wifecraft.creator.TextureLayer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The tabard-only looks' tabard hung as cloth (LookResolver.hangingCloth, ImportedLooks.ofBody): its texture layers moved to
 * the cloth's own layers (LookResolver.CLOTH_LAYER: engine/skin.h kClothLayer, composed into the flap's texture masked to
 * the flap's rectangle, never onto the chest), on synthetic tables, through the renderer's arrays and the imports; and on
 * the game's tables (WIFECRAFT_TABLES; skipped without them) Whitemane's two tabard-only looks.
 */
class HangingClothTest {
    /** Item display 692029 (the Tabard of the Scarlet Crusade): its torso materials 501 (upper) and 502 (lower). */
    private val tables = object : Tables {
        val data = mapOf(
            "ItemDisplayInfo" to listOf(mapOf("ID" to "692029", "ModelResourcesID_0" to "0", "ModelResourcesID_1" to "0",
                "ModelMaterialResourcesID_0" to "0", "ModelMaterialResourcesID_1" to "0")),
            "ItemDisplayInfoMaterialRes" to listOf(
                mapOf("ItemDisplayInfoID" to "692029", "ComponentSection" to "3", "MaterialResourcesID" to "501"),
                mapOf("ItemDisplayInfoID" to "692029", "ComponentSection" to "4", "MaterialResourcesID" to "502")),
            "TextureFileData" to listOf(
                mapOf("MaterialResourcesID" to "501", "FileDataID" to "165297", "UsageType" to "0"),
                mapOf("MaterialResourcesID" to "502", "FileDataID" to "162603", "UsageType" to "0")),
            "ChrModelMaterial" to listOf(mapOf("CharComponentTextureLayoutsID" to "1", "TextureType" to "1", "Width" to "2048", "Height" to "1024")),
            "CharComponentTextureSections" to listOf(
                mapOf("CharComponentTextureLayoutID" to "1", "SectionType" to "3", "X" to "512", "Y" to "0", "Width" to "512", "Height" to "256"),
                mapOf("CharComponentTextureLayoutID" to "1", "SectionType" to "4", "X" to "512", "Y" to "256", "Width" to "512", "Height" to "128"),
                mapOf("CharComponentTextureLayoutID" to "1", "SectionType" to "7", "X" to "512", "Y" to "896", "Width" to "512", "Height" to "128")),
        )
        override fun rows(name: String) = data[name].orEmpty()
    }

    private val skin = TextureLayer(1002493, -1, 1, 0, null, null, 1)
    private val tabardUpper = TextureLayer(165297, 1 shl 3, 15, 123, null, null, 1)
    private val tabardLower = TextureLayer(162603, 1 shl 4, 15, 123, null, null, 1)
    private val boots = TextureLayer(152713, 1 shl 7, 15, 125, null, null, 1)
    private val hair = TextureLayer(165297, -1, 1, 0, null, null, 6)   // (a tabard file on another texture type stays)
    private val look = ResolvedLook(1000764, 1f, mapOf(12 to 2), mapOf(1 to 1002493), listOf(skin, tabardUpper, tabardLower, boots, hair),
        emptyList(), emptySet())

    private fun wearing(vararg items: Pair<Int, Int>, gear: Boolean = true) =
        Appearance(1, Sex.FEMALE, gear = gear, clothing = mapOf(*items), starterClothing = false)

    @Test fun aTabardOverNothingHangsAsClothAndPaintsNothingOnTheBody() {
        val out = LookResolver.hangingCloth(tables, wearing(19 to 692029, 8 to 15988, 10 to 15989), look)
        val byFile = out.layers.groupBy { it.fileId to it.textureType }
        // the tabard's torso layers are the cloth's own (200 + its clothing layer's place 23), its other layers stay
        assertEquals(listOf(LookResolver.CLOTH_LAYER + 23), byFile[165297 to 1]!!.map { it.layer })
        assertEquals(listOf(LookResolver.CLOTH_LAYER + 23), byFile[162603 to 1]!!.map { it.layer })
        assertEquals(listOf(0), byFile[1002493 to 1]!!.map { it.layer })
        assertEquals(listOf(125), byFile[152713 to 1]!!.map { it.layer })
        assertEquals(listOf(0), byFile[165297 to 6]!!.map { it.layer })
        // nothing of the tabard below the cloth's layers: the skin under the chest is bare
        assertTrue(out.layers.none { it.textureType == 1 && it.fileId in setOf(165297, 162603) && it.layer < LookResolver.CLOTH_LAYER })
        assertEquals(200, LookResolver.CLOTH_LAYER)
    }

    @Test fun aShirtChestOrLegsOrGearOffKeepTheTabardOnTheTorso() {
        for (covering in LookResolver.COVERING_SLOTS)
            assertSame(look, LookResolver.hangingCloth(tables, wearing(19 to 692029, covering to 1), look), "slot $covering")
        assertSame(look, LookResolver.hangingCloth(tables, wearing(19 to 692029, gear = false), look))
        assertSame(look, LookResolver.hangingCloth(tables, wearing(8 to 15988), look))
        assertEquals(setOf(4, 5, 7), LookResolver.COVERING_SLOTS)
        assertEquals(CharacterLooks.TABARD_REMOVED, LookResolver.COVERING_SLOTS)
        assertEquals(CharacterLooks.TABARD_SLOT, LookResolver.TABARD_SLOT)
    }

    @Test fun theRenderersArraysCarryTheClothLayerWithItsSection() {
        val out = LookResolver.hangingCloth(tables, wearing(19 to 692029), look)
        val call = Dressing.call(tables, 1, out)
        val metas = (0 until call.layerFiles.size).associate { i -> call.layerFiles[i] to call.layerMeta.copyOfRange(i * Dressing.META, (i + 1) * Dressing.META).toList() }
        assertEquals(listOf(1, 223, 15, 512, 0, 512, 256, 2048, 1024), metas[165297])
        assertEquals(listOf(1, 223, 15, 512, 256, 512, 128, 2048, 1024), metas[162603])
        assertEquals(listOf(1, 125, 15, 512, 896, 512, 128, 2048, 1024), metas[152713])
    }

    @Test fun theImportsKeepTheClothLayer() {
        val out = LookResolver.hangingCloth(tables, wearing(19 to 692029), look)
        val ralia = object : Overrides {
            override fun texture(retailFileId: Int, female: Boolean) = if (retailFileId == 1002493 || retailFileId == 165297) 9_000_000 + retailFileId else retailFileId
            override fun bodyModel(raceId: Int, shape: Int): Int? = null
            override fun bodyLayer(raceId: Int, style: Int, density: Int): Int? = null
            override fun hasRalia() = true
            override fun hasBody() = false
        }
        val imported = ImportedLooks.apply(out, true, ralia)
        val upper = assertNotNull(imported.layers.firstOrNull { it.fileId == 9_165_297 && it.textureType == 1 })
        assertEquals(LookResolver.CLOTH_LAYER + 23, upper.layer)
        assertEquals(0, imported.layers.first { it.fileId == 10_002_493 }.layer)
    }

    @Test fun whitemanesTabardOnlyLooksOnTheGamesTables() {
        val dir = System.getenv("WIFECRAFT_TABLES")?.let(::File)?.takeIf { File(it, "Creature.csv").isFile }
        assumeTrue(dir != null, "WIFECRAFT_TABLES isn't set: the game's tables aren't here")
        val t = CsvTables(dir!!)
        val c = Catalog.build(t)
        val roster = CharacterRoster.parse(File(System.getProperty("wifecraft.assets"), "characters.json").readText())
        val w = roster.first { it.id == "whitemane" }
        for ((key, tabard) in listOf("scarlet_tabard" to 15983, "scarlet_tabard_crusade" to 692029)) {
            val v = assertNotNull(w.variant(key))
            val look = assertNotNull(CharacterLooks.resolve(c, t, v), key)
            val files = com.logie.wifecraft.creator.CreatureLooks.clothingFiles(t, setOf(tabard)).second
            val type1 = look.layers.filter { it.textureType == 1 && it.fileId in files }
            println("$key: tabard layers ${type1.map { "${it.fileId}@${it.layer}" }}")
            assertTrue(type1.isNotEmpty(), "$key: the tabard has torso layers")
            assertTrue(type1.all { it.layer >= LookResolver.CLOTH_LAYER }, "$key: every tabard layer is the cloth's")
            val call = CharacterLooks.call(c, t, v, look)
            val clothMetas = (0 until call.layerFiles.size).map { call.layerMeta[it * Dressing.META + 1] }.filter { it >= LookResolver.CLOTH_LAYER }
            assertEquals(type1.size, clothMetas.size, "$key: the renderer's arrays carry them")
        }
        // her full Scarlet outfit (shirt, chest and legs on) keeps its tabard on the torso
        val outfit = assertNotNull(CharacterLooks.resolve(c, t, assertNotNull(w.variant("scarlet"))))
        assertTrue(outfit.layers.none { it.layer >= LookResolver.CLOTH_LAYER }, "the outfit has no cloth layer")
    }
}
