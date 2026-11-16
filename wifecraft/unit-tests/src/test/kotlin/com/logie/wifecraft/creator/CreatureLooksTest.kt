package com.logie.wifecraft.creator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A creature's and an NPC's look from its display (CreatureLooks) and the renderer's call for it, on made-up rows: the
 * creator's fixtures (resources/creator: a human woman, ChrModel 2, display 802, model file 4001, layout 104) with the
 * creatures' tables laid over them.
 */
class CreatureLooksTest {
    private val creator = CsvTables(File(javaClass.getResource("/creator/ChrModel.csv")!!.toURI()).parentFile)

    private val extra = mapOf(
        "CreatureDisplayInfo" to """
            ID,ModelID,CreatureModelScale,ExtendedDisplayInfoID,TextureVariationFileDataID_0,TextureVariationFileDataID_1,TextureVariationFileDataID_2,TextureVariationFileDataID_3
            801,900,1,0,0,0,0,0
            802,901,1.25,0,0,0,0,0
            9000,902,1.5,0,7001,7002,0,7004
            9001,902,0,0,7001,0,0,0
            9002,903,1,0,0,0,0,0
            9003,905,0.6,0,0,0,0,0
            9004,906,1,0,0,0,0,0
            9500,901,1,7,0,0,0,0
            9501,901,1,8,0,0,0,0
            9502,901,1,99,0,0,0,0
            9600,904,1,10,0,0,0,0
        """,
        "CreatureModelData" to """
            ID,FileDataID,ModelScale
            900,4000,1
            901,4001,1
            902,4002,2
            903,0,1
            904,4004,1
            905,4005,0
            906,4006,0.15
        """,
        "CreatureDisplayInfoGeosetData" to """
            ID,GeosetIndex,GeosetValue,CreatureDisplayInfoID
            1,0,2,9000
            2,2,1,9000
        """,
        "CreatureDisplayInfoExtra" to """
            ID,DisplayRaceID,DisplaySexID,DisplayClassID,Flags,BakeMaterialResourcesID,HDBakeMaterialResourcesID
            7,1,1,0,0,0,600
            8,1,1,0,0,0,0
            10,52,0,0,0,0,600
        """,
        "CreatureDisplayInfoOption" to """
            ID,ChrCustomizationOptionID,ChrCustomizationChoiceID,CreatureDisplayInfoExtraID
            1,10,101,7
            2,10,101,8
            3,900,9001,10
        """,
        "NPCModelItemSlotDisplayInfo" to """
            ID,ItemDisplayInfoID,ItemSlot,NpcModelID
            1,70,0,7
            2,71,1,7
            3,72,6,7
            4,73,10,7
            5,74,3,7
        """,
        "ItemDisplayInfo" to """
            ID,ModelResourcesID_0,ModelResourcesID_1,ModelMaterialResourcesID_0,ModelMaterialResourcesID_1,ModelType_0,ModelType_1,GeosetGroup_0,GeosetGroup_1,GeosetGroup_2,GeosetGroup_3,GeosetGroup_4,AttachmentGeosetGroup_0,AttachmentGeosetGroup_1,AttachmentGeosetGroup_2,AttachmentGeosetGroup_3,AttachmentGeosetGroup_4,HelmetGeosetVis_0,HelmetGeosetVis_1
            70,80,0,81,0,0,0,0,0,0,0,0,0,0,0,0,0,0,9
            71,82,82,84,84,0,0,0,0,0,0,0,0,0,0,0,0,0,0
            72,0,0,0,0,-1,0,2,0,0,0,0,0,0,0,0,0,0,0
            73,0,0,83,0,5,0,1,0,0,0,0,0,0,0,0,0,0,0
            74,85,0,86,0,1,0,1,0,0,0,0,0,0,0,0,0,0,0
        """,
        "ModelFileData" to """
            FileDataID,ModelResourcesID
            8002,80
            8001,80
            8201,82
            8202,82
            8501,85
        """,
        "ComponentModelFileData" to """
            ID,GenderIndex,ClassID,RaceID,PositionIndex
            8001,1,0,1,-1
            8002,0,0,2,-1
            8201,2,0,0,0
            8202,2,0,0,1
            8501,1,0,1,-1
        """,
        "TextureFileData" to """
            FileDataID,UsageType,MaterialResourcesID
            3000,0,500
            2999,2,500
            3001,0,501
            3010,0,510
            3011,0,511
            3012,0,512
            3041,0,541
            3600,0,600
            8101,0,81
            8301,0,83
            8401,0,84
            8601,0,86
        """,
        "HelmetGeosetData" to """
            ID,RaceID,HideGeosetGroup,HelmetGeosetVisDataID
            1,1,0,9
            2,2,7,9
        """,
        "ChrModelMaterial" to """
            CharComponentTextureLayoutsID,TextureType,Width,Height
            104,1,2048,1024
        """,
    ).mapValues { (_, csv) -> CsvTables.parse(csv.trimIndent() + "\n") } + mapOf(
        // a model the creator's catalogue doesn't have (ChrModel 90, a form like the Dracthyr's dragon: sex 3), its options
        "ChrModel" to listOf("90,3,9600,103"),
        "ChrCustomizationOption" to listOf("Horns,900,90,0,5,0", "Spikes,901,90,1,5,0"),
        "ChrCustomizationChoice" to listOf(",9000,900,0,0,0,0,0,0", ",9001,900,0,1,1,0,0,0", ",9010,901,0,0,0,0,0,0"),
        "ChrCustomizationElement" to listOf("900,9001,0,2,0,0,0,0,0,0,0,0,0,0", "901,9010,0,3,0,0,0,0,0,0,0,0,0,0"),
        "ChrCustomizationGeoset" to listOf("3,5,4,0"),
    ).mapValues { (name, lines) -> CsvTables.parse(text(name) + lines.joinToString("\n") + "\n") }

    private fun text(name: String) = File(javaClass.getResource("/creator/$name.csv")!!.toURI()).readText().let { if (it.endsWith("\n")) it else it + "\n" }

    private val tables = object : Tables {
        val read = LinkedHashSet<String>()
        override fun rows(name: String): List<Map<String, String>> { read += name; return extra[name] ?: creator.rows(name) }
    }

    @Test fun aCreatureIsItsModelItsSkinsAndItsGeosets() {
        val look = assertNotNull(CreatureLooks.resolve(tables, 9000))
        assertEquals(4002, look.model)
        assertEquals(3.0f, look.scale)                                   // the display's 1.5 times the model data's 2
        assertEquals(mapOf(11 to 7001, 12 to 7002, 5 to 7004), look.textures)
        assertEquals(mapOf(1 to 2, 3 to 1), look.geosetGroups)          // geoset group = GeosetIndex + 1
        assertTrue(look.layers.isEmpty() && look.extraModels.isEmpty())
        assertEquals(setOf(4002, 7001, 7002, 7004), look.needsFiles)
        // a scale of 0 is 1
        assertEquals(2.0f, CreatureLooks.resolve(tables, 9001)!!.scale)
    }

    /**
     * A look's size is the game's: the display's CreatureModelScale times the model data's ModelScale on the model's own
     * yards, nothing added for a creature (CreatureLooksRealTest measures the real ones). A boar at 0.6 is 0.6, a model
     * data of 0 is 1, a pet's model data at 0.15 makes its display's 1 a hatchling's 0.15; the call carries it as it is.
     */
    @Test fun aCreaturesSizeIsItsDisplaysTimesItsModelDatas() {
        assertEquals(0.6f, CreatureLooks.resolve(tables, 9003)!!.scale)
        assertEquals(0.15f, CreatureLooks.resolve(tables, 9004)!!.scale)
        assertEquals(1.25f, CreatureLooks.resolve(tables, 802)!!.scale)
        for (d in listOf(9000, 9003, 9004)) {
            val look = CreatureLooks.resolve(tables, d)!!
            assertEquals(look.scale, CreatureLooks.call(tables, look).scale)
        }
    }

    @Test fun theCallBindsEachSkinWholeAndShowsTheDefaultGeosets() {
        val call = CreatureLooks.call(tables, CreatureLooks.resolve(tables, 9001)!!)
        assertEquals(4002, call.model)
        assertEquals(listOf(7001), call.layerFiles.toList())
        // [type, layer, blend, x, y, w, h, matW, matH]: a whole texture, which the renderer binds to the type as it is
        assertEquals(listOf(11, 0, 0, 0, 0, 0, 0, 0, 0), call.layerMeta.toList())
        assertEquals(listOf(0, 0), call.tintColours.toList())
        // no geoset data: group 0 at 0 (no change), so the renderer shows each group's default rather than all at once
        assertEquals(listOf(0), call.geosetKeys.toList())
        assertEquals(listOf(0), call.geosetValues.toList())
        val withGroups = CreatureLooks.call(tables, CreatureLooks.resolve(tables, 9000)!!)
        assertEquals(listOf(1, 3), withGroups.geosetKeys.toList())
        assertEquals(listOf(2, 1), withGroups.geosetValues.toList())
    }

    /** Only a playable race's model has a soft body: a creature's never (Dresser.dressLook, Native.character). */
    @Test fun onlyAPlayableRacesModelIsACharacter() {
        assertTrue(!CreatureLooks.isCharacter(tables, CreatureLooks.resolve(tables, 9000)!!))     // a creature's model
        assertTrue(CreatureLooks.isCharacter(tables, CreatureLooks.resolve(tables, 9500)!!))      // a human woman NPC
    }

    @Test fun whatHasNoLookSaysWhy() {
        assertNull(CreatureLooks.resolve(tables, 1234))
        assertEquals("display not in CreatureDisplayInfo", CreatureLooks.check(tables, 1234).why)
        assertTrue(CreatureLooks.check(tables, 9002).why!!.contains("no model file"))
        assertTrue(CreatureLooks.check(tables, 9502).why!!.contains("not in CreatureDisplayInfoExtra"))
    }

    @Test fun anNpcIsTheRaceWithItsChoicesItsBakedSkinAndItsGear() {
        val look = assertNotNull(CreatureLooks.resolve(tables, 9500))
        assertEquals(4001, look.model)
        // the baked skin in place of the composed one: no layer of type 1 is left, the bake is the body's texture
        assertEquals(3600, look.textures[1])
        assertTrue(look.layers.none { it.textureType == 1 })
        // the cape's texture
        assertEquals(8301, look.textures[2])
        // the helm for a human woman (not the orc man's), with its texture; the shoulders' left (6) and right (5)
        assertTrue(ExtraModel(11, 8001, 8101) in look.extraModels, look.extraModels.toString())
        assertTrue(ExtraModel(6, 8201, 8401) in look.extraModels)
        assertTrue(ExtraModel(5, 8202, 8401) in look.extraModels)
        // the chest's armour collection, skinned to the body, with the chest's groups at the item's attachment values
        assertTrue(ExtraModel(-1, 8501, 8601, 801) in look.extraModels)
        assertTrue(ExtraModel(-1, 8501, 8601, 1301) in look.extraModels)
        // geosets: the boots (group 5 = 1 + 2), the cape (15 = 1 + 1), the chest's sleeves (8 = 1 + 1); the helm hides
        // the human's hair (group 0) and not the orc's group 7
        assertEquals(3, look.geosetGroups[5])
        assertEquals(2, look.geosetGroups[15])
        assertEquals(2, look.geosetGroups[8])
        assertEquals(0, look.geosetGroups[0])
        assertTrue(7 !in look.geosetGroups)
        assertTrue(listOf(4001, 3600, 8301, 8001, 8101, 8201, 8202, 8401, 8501, 8601).all { it in look.needsFiles })
    }

    @Test fun anNpcCallComposesItsLayersAndBindsTheBake() {
        val look = CreatureLooks.resolve(tables, 9500)!!
        val call = CreatureLooks.call(tables, look)
        val types = (0 until call.layerFiles.size).map { call.layerMeta[it * Dressing.META] }
        // the bake and the cape as whole textures of their own types, once each
        assertEquals(1, types.count { it == 1 })
        assertEquals(3600, call.layerFiles[types.indexOf(1)])
        assertEquals(8301, call.layerFiles[types.indexOf(2)])
        assertTrue(call.extraModels.isNotEmpty())
    }

    @Test fun anNpcWithoutABakeKeepsTheComposedSkinAndSaysSo() {
        val look = CreatureLooks.resolve(tables, 9501)!!
        assertTrue(look.layers.any { it.textureType == 1 })
        assertTrue(look.diagnostics.any { it.contains("no baked skin") })
    }

    @Test fun anNpcOfAModelTheCreatorHasntReadsItsChoicesDirectly() {
        val look = assertNotNull(CreatureLooks.resolve(tables, 9600))
        assertEquals(4004, look.model)
        // the display's choice (9001: geoset group 0 at 2) and the other option's first choice (9010: group 5 at 4)
        assertEquals(2, look.geosetGroups[0])
        assertEquals(4, look.geosetGroups[5])
        assertEquals(3600, look.textures[1])
        assertTrue(look.diagnostics.isEmpty(), look.diagnostics.toString())
    }

    @Test fun everyTableReadIsFetched() {
        tables.read.clear()
        for (d in listOf(9000, 9500, 9501, 9600)) CreatureLooks.call(tables, CreatureLooks.resolve(tables, d)!!)
        assertEquals(emptySet(), tables.read - GameTables.NAMES.toSet())
    }
}
