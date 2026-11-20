package com.logie.wifecraft.characters

import com.logie.wifecraft.bodyparts.ModelParts
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.Body
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.creator.CreatureLooks
import com.logie.wifecraft.creator.CsvTables
import com.logie.wifecraft.creator.LookResolver
import com.logie.wifecraft.creator.NamedLook
import com.logie.wifecraft.creator.Overrides
import com.logie.wifecraft.creator.ResolvedLook
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.creator.Tables
import com.logie.wifecraft.creator.TextureLayer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The one step that puts the user's imports on a look (ImportedLooks), on the creator's made-up rows (CharacterLooksTest's:
 * a human woman, Skin Color 101 = base 3001 and underwear 3002; display 9500 a human woman NPC with a baked skin, 9600 a
 * model of its own) and a synthetic import (the user's Ralia package is never here): 3001 and 3002 replaced by 2000000001
 * and 2000000002. Each dressing path's look (the live scene's and creator's, a named character's display, body, bare, nude
 * and partial choices, the pictures' and cards') is ImportedLooks.of / ofDisplay; Dresser and Thumbs call them (Android:
 * compiled by CI only).
 */
class ImportedLooksTest {
    private val creator = CsvTables(File(javaClass.getResource("/creator/ChrModel.csv")!!.toURI()).parentFile)
    private fun text(name: String) = File(javaClass.getResource("/creator/$name.csv")!!.toURI()).readText().let { if (it.endsWith("\n")) it else it + "\n" }

    private val extra: Map<String, List<Map<String, String>>> = mapOf(
        "CreatureDisplayInfo" to """
            ID,ModelID,CreatureModelScale,ExtendedDisplayInfoID
            9500,901,1,7
            9600,902,1,0
            9700,901,1,8
        """,
        "CreatureModelData" to """
            ID,FileDataID,ModelScale
            901,4001,1
            902,4002,1
        """,
        "CreatureDisplayInfoExtra" to """
            ID,DisplayRaceID,DisplaySexID,DisplayClassID,Flags,BakeMaterialResourcesID,HDBakeMaterialResourcesID
            7,1,1,0,0,0,600
            8,1,1,0,0,0,600
        """,
        "CreatureDisplayInfoOption" to """
            ID,ChrCustomizationOptionID,ChrCustomizationChoiceID,CreatureDisplayInfoExtraID
            1,10,101,7
            2,10,100,8
        """,
        "NPCModelItemSlotDisplayInfo" to """
            ID,ItemDisplayInfoID,ItemSlot,NpcModelID
            1,70,0,7
        """,
        "ChrModelMaterial" to """
            CharComponentTextureLayoutsID,TextureType,Width,Height
            104,1,2048,1024
        """,
        "CharComponentTextureSections" to """
            ID,CharComponentTextureLayoutID,SectionType,X,Y,Width,Height
            1,104,4,0,0,256,128
        """,
    ).mapValues { (_, csv) -> CsvTables.parse(csv.trimIndent() + "\n") } + mapOf(
        "ChrCustomizationElement" to listOf("20,101,0,0,0,1002,0,0,0,0,0,0,0,0"),
        "ChrCustomizationMaterial" to listOf("1002,6,502"),
        "ChrModelTextureLayer" to listOf("9,1,2,0,1,16,-1,6,0,104"),
        // the NPC's baked skin (resource 600)
        "TextureFileData" to listOf("3002,0,502", "3600,0,600"),
    ).mapValues { (name, lines) -> CsvTables.parse(text(name) + lines.joinToString("\n") + "\n") }

    private val tables = object : Tables {
        override fun rows(name: String): List<Map<String, String>> = extra[name] ?: creator.rows(name)
    }
    private val catalog = Catalog.build(tables)

    /** A synthetic import: [map] retail -> imported; [womenOnly] as Alternate Female Options; [body] the body options' package. */
    private class Imports(val map: Map<Int, Int>, val womenOnly: Boolean = false, val body: Boolean = false) : Overrides {
        override fun texture(retailFileId: Int, female: Boolean) = if (womenOnly && !female) retailFileId else map[retailFileId] ?: retailFileId
        override fun bodyModel(raceId: Int, shape: Int): Int? = if (body && shape > 0) 2_000_000_900 else null
        override fun bodyLayer(raceId: Int, style: Int, density: Int): Int? = if (body) 2_000_000_901 else null
        override fun hasRalia() = map.isNotEmpty()
        override fun hasBody() = body
        override fun signature() = map.toString() + body
    }

    private val ralia = Imports(mapOf(3001 to 2_000_000_001, 3002 to 2_000_000_002))
    private val none = Overrides.None

    private fun files(l: ResolvedLook) = l.layers.map { it.fileId }.toSet()

    // the named character: an outfit on the body, the undressed kinds, a nude, a display of her own model, a partial
    private val body = CharacterVariant("underwear", "Underwear", VariantKind.UNDERWEAR, raceId = 1, picks = mapOf(10 to 101))
    private val heroine = Character("h", "Heroine", listOf(
        CharacterVariant("npc", "Her model", VariantKind.OUTFIT, display = 9600),
        CharacterVariant("human", "Her humanoid display", VariantKind.OUTFIT, display = 9500),
        CharacterVariant("robe", "Robe", VariantKind.OUTFIT, raceId = 1, picks = mapOf(10 to 101), equipment = mapOf(1 to 70)),
        body,
        body.copy(key = "bare", label = "Bare", kind = VariantKind.BARE),
        CharacterVariant("robe_nude", "Robe, nude", VariantKind.NUDE, raceId = 1, picks = mapOf(10 to 101), equipment = mapOf(1 to 70)),
    ))
    private val set = ModelParts.PartSet("h_npc", "h", "npc", 9600, 4002, 4001, "bare", listOf("head"), emptyList(), true)
    private val cast = NamedCast(listOf(heroine), ModelParts.Manifest(listOf(set), setOf("head")))
    private fun named(key: String) = cast.appearance(heroine, assertNotNull(cast.choice(heroine, key)).also { assertEquals(key, it.key) })

    @Test fun aCreatedNpcIsTheCreatorsLookUnchanged() {
        // the regression: a created NPC, a starter (Mira) or an older project's NPC is drawn exactly as the creator resolves it
        val a = Appearance(1, Sex.FEMALE, mapOf(10 to 101), gear = false)
        for (o in listOf(none, ralia)) {
            val plan = ImportedLooks.of(catalog, tables, a, cast, o)
            assertEquals(LookResolver.resolve(catalog, tables, a, o), plan.look)
            assertSame(a, plan.drawn)
            assertTrue(plan.character)
            assertNull(plan.set)
        }
        assertTrue(2_000_000_001 in files(ImportedLooks.of(catalog, tables, a, null, ralia).look), "Ralia's skin on a created NPC")
        assertEquals(setOf(3001, 3002), files(ImportedLooks.of(catalog, tables, a, null, none).look) intersect setOf(3001, 3002, 2_000_000_001))
    }

    @Test fun everyBodyChoiceOfANamedCharacterWearsTheImports() {
        for (key in listOf("robe", "underwear", "bare", "robe_nude", "npc_partial")) {
            val plan = ImportedLooks.of(catalog, tables, named(key), cast, ralia)
            val f = files(plan.look)
            assertTrue(2_000_000_001 in f, "$key: the imported skin ($f)")
            assertFalse(3001 in f, "$key: not the game's skin")
            assertNotNull(plan.drawn, key)
            // with nothing imported: the game's own
            val plain = ImportedLooks.of(catalog, tables, named(key), cast, none)
            assertTrue(3001 in files(plain.look) && 2_000_000_001 !in files(plain.look), key)
        }
        assertEquals(set, ImportedLooks.of(catalog, tables, named("npc_partial"), cast, ralia).set, "the partial's stand-in keeps its set")
    }

    @Test fun bareNudeAndPartialLeaveOutTheImportedUnderwearToo() {
        assertTrue(2_000_000_002 in files(ImportedLooks.of(catalog, tables, named("underwear"), cast, ralia).look), "underwear: the import's")
        for (key in listOf("bare", "robe_nude", "npc_partial")) {
            val f = files(ImportedLooks.of(catalog, tables, named(key), cast, ralia).look)
            assertFalse(2_000_000_002 in f || 3002 in f, "$key has no underwear: $f")
            assertTrue(2_000_000_001 in f, "$key keeps the imported skin")
        }
        // the same through CharacterLooks (the variant's own look)
        val bare = assertNotNull(CharacterLooks.resolve(catalog, tables, heroine.variant("bare")!!, ralia))
        assertEquals(setOf(2_000_000_001), files(bare) intersect setOf(2_000_000_001, 2_000_000_002, 3001, 3002))
    }

    @Test fun aHumanoidDisplayIsDrawnAsItsBodyWhenTheImportsChangeIt() {
        val plain = ImportedLooks.ofDisplay(catalog, tables, 9500, none)
        assertNull(plain.drawn, "nothing imported: the game's display as it is")
        assertEquals(3600, plain.look.textures[1], "its baked skin")
        assertEquals(CreatureLooks.resolve(tables, 9500), plain.look)

        val withRalia = ImportedLooks.ofDisplay(catalog, tables, 9500, ralia)
        val a = assertNotNull(withRalia.drawn)
        assertEquals(1, a.raceId); assertEquals(Sex.FEMALE, a.sex)
        assertEquals(mapOf(10 to 101), a.picks, "the display's own choices")
        assertEquals(mapOf(1 to 70), a.clothing, "the display's own items")
        assertEquals(9500, withRalia.display)
        assertTrue(2_000_000_001 in files(withRalia.look))
        assertNotEquals(3600, withRalia.look.textures[1], "not the baked skin the import can't reach")
        assertTrue(withRalia.character)
        // the named character's choice of that display, the same
        val chosen = ImportedLooks.of(catalog, tables, named("human"), cast, ralia)
        assertEquals(withRalia.look, chosen.look)
        assertEquals(a, chosen.drawn)
    }

    @Test fun aModelOfItsOwnIsNotAPlayerBody() {
        // Sylvanas', Jaina's, Alleria's displays: no race, no player skin: the imports have nothing of it
        val plan = ImportedLooks.ofDisplay(catalog, tables, 9600, ralia)
        assertNull(plan.drawn)
        assertEquals(CreatureLooks.resolve(tables, 9600), plan.look)
        assertNull(ImportedLooks.composed(catalog, tables, 9600, ralia))
        assertEquals(plan.look, ImportedLooks.of(catalog, tables, named("npc"), cast, ralia).look)
    }

    @Test fun whereTheImportsHaveNothingForTheRaceTheGamesFileIsDrawn() {
        val other = Imports(mapOf(9999 to 2_000_000_099))     // another race's skin only
        assertNull(ImportedLooks.composed(catalog, tables, 9500, other), "nothing of hers replaced: her display as the game has it")
        assertEquals(CreatureLooks.resolve(tables, 9500), ImportedLooks.ofDisplay(catalog, tables, 9500, other).look)
        for (key in listOf("robe", "bare")) assertEquals(ImportedLooks.of(catalog, tables, named(key), cast, none).look,
            ImportedLooks.of(catalog, tables, named(key), cast, other).look, key)
        // a display whose skin colour the import hasn't (choice 100: files 3000, 2999) stays the game's
        assertNull(ImportedLooks.composed(catalog, tables, 9700, ralia))
    }

    @Test fun theSwitchesAreHonoured() {
        // Alternate Female Options: the women's files only
        val alternate = Imports(mapOf(3001 to 2_000_000_001), womenOnly = true)
        val look = ResolvedLook(4001, 1f, emptyMap(), mapOf(1 to 3001), listOf(TextureLayer(3001, -1, 1, 0, null, null)), emptyList(), setOf(4001, 3001))
        assertEquals(2_000_000_001, ImportedLooks.apply(look, true, alternate).layers[0].fileId)
        assertSame(look, ImportedLooks.apply(look, false, alternate), "a man: the game's own")
        // nothing on: nothing changes; applied twice: the same
        assertSame(look, ImportedLooks.apply(look, true, none))
        val once = ImportedLooks.apply(look, true, ralia)
        assertEquals(once, ImportedLooks.apply(once, true, ralia))
        assertEquals(setOf(4001, 2_000_000_001), once.needsFiles)
        assertEquals(2_000_000_001, once.textures[1])
    }

    @Test fun anNpcsOwnBodyOptionsAreHonoured() {
        val withBody = Imports(emptyMap(), body = true)
        // a named character's body choice with her own Shape, Style and Density (as a created NPC's)
        val shaped = named("underwear").copy(body = Body(2, 3, 1))
        val plan = ImportedLooks.of(catalog, tables, shaped, cast, withBody)
        assertEquals(Body(2, 3, 1), plan.drawn?.body)
        assertTrue(2_000_000_901 in files(plan.look), "the design layer")
        // her variant's own (none) while the NPC has none
        assertEquals(Body(), ImportedLooks.of(catalog, tables, named("underwear"), cast, withBody).drawn?.body)
        // a humanoid display with Body options on is drawn as its body, shaped
        val display = ImportedLooks.ofDisplay(catalog, tables, 9500, withBody, Body(1, 0, 0))
        assertEquals(Body(1, 0, 0), display.drawn?.body)
        assertNull(ImportedLooks.ofDisplay(catalog, tables, 9500, withBody).drawn, "no Body options: the game's display")
        // the Undead switch carried over the same way
        val undead = ImportedLooks.ownOptions(CharacterLooks.appearance(body)!!, named("underwear").copy(undead = true, undeadSkin = 2))
        assertTrue(undead.undead); assertEquals(2, undead.undeadSkin)
    }

    @Test fun thePicturesAndCardsTakeTheSameStep() {
        // Thumbs.look (portraits, the library's pictures, the placing ghost) and Thumbs.card (the Transmog cards) are
        // ImportedLooks.of for an NPC and ofDisplay for a creature: a card's look is a created appearance wearing pieces
        val card = Appearance(1, Sex.FEMALE, mapOf(10 to 101), gear = true, clothing = mapOf(1 to 70), starterClothing = false)
        val plan = ImportedLooks.of(catalog, tables, card, null, ralia)
        assertTrue(2_000_000_001 in files(plan.look))
        assertEquals(LookResolver.resolve(catalog, tables, card, ralia), plan.look)
        // a portrait of a named character is of her chosen look (no longer her stand-in's body as it is)
        val portrait = ImportedLooks.of(catalog, tables, named("bare").copy(named = NamedLook("h", "bare")), cast, ralia)
        assertFalse(2_000_000_002 in files(portrait.look))
    }
}
