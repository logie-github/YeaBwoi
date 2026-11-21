package com.logie.wifecraft.characters

import com.logie.wifecraft.creator.Body
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.creator.CsvTables
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.creator.Tables
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The named characters' variants (CharacterRoster, CharacterLooks) on made-up rows: the creator's fixtures
 * (resources/creator: a human woman, race 1, ChrModel 2, layout 104; Skin Color option 10 with choices 100 and 101) with
 * an NPC display, its choices and items, and an underwear layer for skin colour 101 laid over them. Invented ids only.
 */
class CharacterLooksTest {
    private val creator = CsvTables(File(javaClass.getResource("/creator/ChrModel.csv")!!.toURI()).parentFile)

    private fun text(name: String) = File(javaClass.getResource("/creator/$name.csv")!!.toURI()).readText().let { if (it.endsWith("\n")) it else it + "\n" }

    private val extra: Map<String, List<Map<String, String>>> = mapOf(
        "CreatureDisplayInfo" to """
            ID,ModelID,CreatureModelScale,ExtendedDisplayInfoID
            801,900,1,0
            802,901,1,0
            9500,901,1,7
            9600,902,1,0
        """,
        "CreatureModelData" to """
            ID,FileDataID,ModelScale
            900,4000,1
            901,4001,1
            902,4002,1
        """,
        "CreatureDisplayInfoExtra" to """
            ID,DisplayRaceID,DisplaySexID,DisplayClassID,Flags,BakeMaterialResourcesID,HDBakeMaterialResourcesID
            7,1,1,0,0,0,600
        """,
        "CreatureDisplayInfoOption" to """
            ID,ChrCustomizationOptionID,ChrCustomizationChoiceID,CreatureDisplayInfoExtraID
            1,10,101,7
            2,11,111,7
        """,
        "NPCModelItemSlotDisplayInfo" to """
            ID,ItemDisplayInfoID,ItemSlot,NpcModelID
            1,70,0,7
            2,71,1,7
            3,72,6,7
            4,0,7,7
            5,74,10,7
        """,
        "ChrModelMaterial" to """
            CharComponentTextureLayoutsID,TextureType,Width,Height
            104,1,2048,1024
        """,
        "CharComponentTextureSections" to """
            ID,CharComponentTextureLayoutID,SectionType,X,Y,Width,Height
            1,104,4,0,0,256,128
            2,104,10,256,0,256,128
            3,104,12,512,0,256,128
        """,
    ).mapValues { (_, csv) -> CsvTables.parse(csv.trimIndent() + "\n") } + mapOf(
        // skin colour 101's underwear: a material on target 6, laid on the body's texture over section 4 only
        "ChrCustomizationElement" to listOf("20,101,0,0,0,1002,0,0,0,0,0,0,0,0"),
        "ChrCustomizationMaterial" to listOf("1002,6,502"),
        "ChrModelTextureLayer" to listOf("9,1,2,0,1,16,-1,6,0,104"),
        "TextureFileData" to listOf("3002,0,502"),
    ).mapValues { (name, lines) -> CsvTables.parse(text(name) + lines.joinToString("\n") + "\n") }

    private val tables = object : Tables {
        override fun rows(name: String): List<Map<String, String>> = extra[name] ?: creator.rows(name)
    }
    private val catalog = Catalog.build(tables)

    private fun variant(kind: VariantKind, equipment: Map<Int, Int> = emptyMap()) =
        CharacterVariant("v", "V", kind, raceId = 1, sex = Sex.FEMALE, picks = mapOf(10 to 101), equipment = equipment)

    @Test fun aHumanoidDisplayIsItsRaceItsChoicesAndItsItems() {
        val p = assertNotNull(CharacterLooks.parts(tables, 9500))
        assertEquals(1, p.raceId)
        assertEquals(Sex.FEMALE, p.sex)
        assertEquals(mapOf(10 to 101, 11 to 111), p.picks)
        // NPC slots 0 head, 1 shoulders, 6 feet, 10 cape -> equipment slots 1, 3, 8, 15; an empty item is no item
        assertEquals(mapOf(1 to 70, 3 to 71, 8 to 72, 15 to 74), p.equipment)
        assertNull(CharacterLooks.parts(tables, 801), "a display of a model of its own has no parts")
    }

    @Test fun anOutfitWearsItsItemsAndTheUndressedKindsWearNone() {
        val outfit = assertNotNull(CharacterLooks.appearance(variant(VariantKind.OUTFIT, mapOf(5 to 900))))
        assertTrue(outfit.gear)
        assertEquals(mapOf(5 to 900), outfit.clothing)
        assertFalse(outfit.starterClothing)
        for (kind in listOf(VariantKind.UNDERWEAR, VariantKind.BARE)) {
            val a = assertNotNull(CharacterLooks.appearance(variant(kind, mapOf(5 to 900))))
            assertFalse(a.gear, "$kind is the creator's No Gear")
            assertTrue(a.clothing.isEmpty())
            assertEquals(mapOf(10 to 101), a.picks)
        }
        assertNull(CharacterLooks.appearance(CharacterVariant("d", "D", VariantKind.OUTFIT, display = 801)))
    }

    @Test fun underwearKeepsTheSkinColoursUnderwearAndBareLeavesItOut() {
        val under = assertNotNull(CharacterLooks.resolve(catalog, tables, variant(VariantKind.UNDERWEAR)))
        assertTrue(under.layers.any { it.fileId == 3002 }, "underwear layer drawn: ${under.layers}")
        assertTrue(under.layers.any { it.fileId == 3001 }, "the skin colour's base drawn")
        val bare = assertNotNull(CharacterLooks.resolve(catalog, tables, variant(VariantKind.BARE)))
        assertFalse(bare.layers.any { it.fileId == 3002 }, "bare has no underwear: ${bare.layers}")
        assertTrue(bare.layers.any { it.fileId == 3001 && it.region == -1 }, "the skin colour's base stays")
        assertEquals(under.model, bare.model)
        assertEquals(under.geosetGroups, bare.geosetGroups)
        assertEquals(setOf(3000, 2999, 3001, 3002), CharacterLooks.skinColorFiles(catalog, tables, CharacterLooks.appearance(variant(VariantKind.BARE))!!))
        // the renderer's call for both: the body model and its layers
        val call = CharacterLooks.call(catalog, tables, variant(VariantKind.BARE), bare)
        assertEquals(bare.model, call.model)
    }

    @Test fun aDisplayVariantIsTheGamesNpcAsItIs() {
        val v = CharacterVariant("d", "D", VariantKind.OUTFIT, display = 9600)
        assertTrue(v.isDisplay)
        val look = assertNotNull(CharacterLooks.resolve(catalog, tables, v))
        assertEquals(4002, look.model)
        assertEquals(4002, CharacterLooks.call(catalog, tables, v, look).model)
        assertNull(CharacterLooks.resolve(catalog, tables, CharacterVariant("x", "X", VariantKind.OUTFIT, display = 12345)))
        assertNotNull(CharacterLooks.why(catalog, tables, CharacterVariant("x", "X", VariantKind.OUTFIT, display = 12345)))
    }

    @Test fun theRosterRoundTrips() {
        val roster = listOf(
            Character("a", "A", listOf(
                CharacterVariant("npc", "NPC", VariantKind.OUTFIT, display = 9600, sourceDisplay = 9600, sourceCreatures = listOf(1, 2), era = "Shadowlands"),
                CharacterVariant("outfit", "Outfit", VariantKind.OUTFIT, raceId = 1, picks = mapOf(10 to 101), body = Body(1, 2, 3),
                    equipment = mapOf(5 to 900), sourceDisplay = 9500),
                CharacterVariant("bare", "Bare", VariantKind.BARE, raceId = 1, picks = mapOf(10 to 101), note = "stand-in"),
            )),
        )
        val json = CharacterRoster.toJson(roster)
        assertEquals("nude", json.getJSONArray("characters").getJSONObject(0).getJSONArray("variants").getJSONObject(2).getString("equipment"))
        assertEquals(roster, CharacterRoster.parse(json.toString()))
    }

    /** The app's own roster (assets/characters.json): every variant is a display or a body; no undressed variant wears anything. */
    @Test fun theAppsRosterIsWellFormed() {
        val file = File(System.getProperty("wifecraft.assets"), "characters.json")
        val roster = CharacterRoster.parse(file.readText())
        assertTrue(roster.size >= 12, "${roster.size} characters")
        for (c in roster) {
            assertTrue(c.variants.any { it.kind == VariantKind.OUTFIT }, "${c.id} has an outfit")
            assertEquals(c.variants.size, c.variants.map { it.key }.toSet().size, "${c.id}: keys are unique")
            for (v in c.variants) {
                assertTrue(v.isDisplay || v.raceId > 0, "${c.id}/${v.key}: a display or a race")
                if (v.kind == VariantKind.UNDERWEAR || v.kind == VariantKind.BARE) {
                    assertTrue(v.equipment.isEmpty() && !v.isDisplay && v.sex == Sex.FEMALE, "${c.id}/${v.key} is undressed")
                }
                if (v.kind == VariantKind.NUDE) {
                    // its outfit's items but the torso, leg and feet pieces (NudeVariantsTest)
                    assertTrue(!v.isDisplay && v.equipment.keys.none { it in CharacterLooks.NUDE_REMOVED }, "${c.id}/${v.key} is nude")
                }
                if (v.kind == VariantKind.TABARD) {
                    // its outfit's items but the shirt, chest and legs, the tabard kept (TabardVariantsTest)
                    assertTrue(!v.isDisplay && v.equipment.keys.none { it in CharacterLooks.TABARD_REMOVED } && CharacterLooks.TABARD_SLOT in v.equipment, "${c.id}/${v.key} wears a tabard")
                }
            }
        }
        // Chromie's only humanoid form is a gnome's: she has no undressed variant
        assertTrue(roster.first { it.id == "chromie" }.variants.all { it.kind == VariantKind.OUTFIT })
        // no model older than Battle for Azeroth where a newer one exists (tools/characters.py): these displays stay out
        val older = setOf(28213, 86014, 67797, 67721, 24952, 78231, 76272, 86013, 114447)
        for (c in roster) for (v in c.variants)
            assertTrue(v.display !in older && v.sourceDisplay !in older, "${c.id}/${v.key} uses an older display")
        assertEquals(setOf("sylvanas", "calia", "lilian", "jaina", "alexstrasza", "chromie", "valeera", "alleria", "whitemane", "xalatath", "tess", "lyssabel"),
            roster.map { it.id }.toSet())
        // Whitemane: her Scarlet Crusade look is the classic display 2043 (npc 3977; a human woman with Scarlet items, a
        // tabard), her death knight look 71257; undressed, the Scarlet display's own choices with the death knight pallor
        // (human Skin Color option 14, choice 97), whose skin carries the underwear layers her display's NPC skin hasn't
        val w = roster.first { it.id == "whitemane" }
        val scarlet = assertNotNull(w.variant("scarlet"))
        assertEquals(2043, scarlet.sourceDisplay)
        assertEquals(VariantKind.OUTFIT, scarlet.kind)
        assertTrue(19 in scarlet.equipment, "the Scarlet tabard")
        assertEquals(71257, w.variant("deathknight")?.sourceDisplay)
        for (k in listOf("underwear", "bare")) {
            val v = assertNotNull(w.variant(k))
            assertEquals(97, v.picks[14], "whitemane/$k skin")
            assertEquals(scarlet.picks - 14, v.picks - 14, "whitemane/$k keeps her face, hair, eyes and markings")
        }
    }
}
