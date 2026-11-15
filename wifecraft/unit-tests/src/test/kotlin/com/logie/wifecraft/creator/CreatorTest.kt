package com.logie.wifecraft.creator

import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The creator's data core on small tables of our own (src/test/resources/creator: invented ids, nothing of the game's). */
class CreatorTest {
    private val tables = CsvTables(File(javaClass.getResource("/creator/ChrModel.csv")!!.toURI()).parentFile)
    private val catalog = Catalog.build(tables)
    private val humanWoman = Appearance(1, Sex.FEMALE)

    @Test fun csvQuoting() {
        val rows = CsvTables.parse("a,b,c\n\"x, y\",\"say \"\"hi\"\"\",\"two\nlines\"\r\n1,,\n")
        assertEquals(listOf("x, y", "say \"hi\"", "two\nlines"), listOf(rows[0]["a"], rows[0]["b"], rows[0]["c"]))
        assertEquals("", rows[1]["b"])
        assertNull(rows[1]["missing"])
        assertEquals(2, rows.size)
    }

    @Test fun races() {
        // the Alliance's, the Horde's, then the allied; the Pandaren once (the Alliance's of the two), Dracthyr as its
        // visage's sexes, no NPC race
        assertEquals(listOf(1, 25, 52, 2, 29), catalog.races.map { it.id })
        assertEquals(setOf(Sex.MALE, Sex.FEMALE), catalog.race(52)!!.sexes)
        assertEquals(128, catalog.model(52, Sex.FEMALE)!!.chrModelId)
        assertTrue(catalog.race(29)!!.alliance)
        assertFalse(catalog.race(2)!!.alliance)
        assertEquals("Void Elf", catalog.race(29)!!.name)
    }

    @Test fun categoriesAndOptions() {
        val e = catalog.model(1, Sex.FEMALE)!!
        assertEquals(listOf("Face", "Hair", "Accessories", "Body", "Markings"), e.categories.map { it.name })
        // nameless, single-choice and druid-form options aren't rows
        assertEquals(listOf(10, 11, 12, 13, 14, 15, 16, 19), e.options.map { it.id })
        // choices in the creator's order (UiOrderIndex), swatches as 0xRRGGBB
        assertEquals(listOf("Bun", "Braids"), e.options.first { it.id == 12 }.choices.map { it.name })                                                                // the barbershop's Transmog (ReqType 4) is no look
        val colour = e.options.first { it.id == 14 }.choices.first { it.id == 141 }
        assertEquals(0xFF0000, colour.swatch1)
        assertEquals(0x0000FF, colour.swatch2)
        assertTrue(e.options.first { it.id == 16 }.isSwitch)
        assertEquals(20f, e.categories.first { it.name == "Body" }.zoom)
    }

    @Test fun defaultLook() {
        val r = LookResolver.resolve(catalog, tables, humanWoman)
        assertEquals(4001, r.model)
        assertEquals(1.25f, r.scale)
        assertEquals(1, r.geosetGroups[0])                      // Braids: its OrderIndex is the first
        assertEquals(3000, r.textures[1])                       // the skin's colour file (UsageType 0)
        assertEquals(3080, r.textures[19])                      // the hidden single-choice Eye Color is still drawn
        assertEquals(listOf(6001), r.boneFiles)                  // Face 1's bones, for this model
        assertTrue(r.layers.any { it.fileId == 3010 && it.region == 1024 && it.blend == 15 && it.layer == 3 })
        for (g in listOf(4, 5, 8, 13)) assertEquals(1, r.geosetGroups[g])
        assertTrue(r.needsFiles.containsAll(listOf(4001, 3000, 3010, 3080, 6001)))
        assertEquals(emptyList(), r.diagnostics)
    }

    @Test fun rowsAndHiddenColour() {
        val m = CreatorModel(catalog, humanWoman)
        assertEquals(listOf("Face", "Hair", "Accessories", "Markings", "Body"), m.categories().map { it.name })
        // Tattoo Color hidden while Tattoo is None; Gear last
        assertEquals(listOf("Tattoo", "Horns", "Gear"), m.rows(5).map { it.option.name })
        m.choose(13, 131)
        assertEquals(listOf("Tattoo", "Tattoo Color", "Horns", "Gear"), m.rows(5).map { it.option.name })
        assertEquals(141, m.appearance.picks[14])               // a colour that paints the pattern
        val r = m.resolved(tables)
        assertTrue(r.layers.any { it.fileId == 3041 && it.region == 4096 && it.blend == 9 })
        m.choose(14, 142)
        m.choose(13, 132)                                       // 142 doesn't paint Owl: the colour moves
        assertEquals(141, m.appearance.picks[14])
        assertTrue(m.resolved(tables).layers.any { it.fileId == 3042 })
    }

    @Test fun relatedChoicesKeptRight() {
        val m = CreatorModel(catalog, humanWoman)
        m.choose(11, 111)                                       // Face 2 is only for the second skin: the skin moves
        assertEquals(101, m.appearance.picks[10])
        assertEquals(3012, m.resolved(tables).layers.first { it.layer == 3 }.fileId)
        m.choose(10, 100)                                       // and back: the face moves
        assertEquals(110, m.appearance.picks[11])
        assertEquals(emptyList(), m.resolved(tables).diagnostics)
    }

    @Test fun stepWrapsAndSwitches() {
        val m = CreatorModel(catalog, humanWoman)
        m.step(12, 1)                                           // Bun, Braids: Braids on, next round to Bun
        assertEquals(121, m.appearance.picks[12])
        m.step(12, -1)
        assertEquals(120, m.appearance.picks[12])
        m.step(16, 1)
        assertEquals(4002, m.resolved(tables).model)            // Upright: its own model
        m.step(CreatorModel.GEAR, 1)
        assertFalse(m.appearance.gear)
        assertEquals(0, m.rows(2).last().index)
    }

    @Test fun skinnedModelAndDiagnostics() {
        val m = CreatorModel(catalog, humanWoman)
        m.choose(15, 151)
        assertEquals(listOf(ExtraModel(-1, 5001, 0, 2401)), m.resolved(tables).extraModels)
        m.choose(19, 191)                                       // drawn by a particle colour only: said so
        val d = m.resolved(tables).diagnostics
        assertEquals(1, d.size)
        assertTrue(d[0].startsWith("Glow: Spark draws nothing"), d[0])
    }

    @Test fun raceAndSexCarryOver() {
        val m = CreatorModel(catalog, humanWoman)
        m.choose(10, 101)
        m.setRace(29)                                           // the same place in the void elf's Skin Color
        assertEquals(29, m.appearance.raceId)
        assertEquals(401, m.appearance.picks[40])
        m.setSex(Sex.MALE)
        assertEquals(Sex.MALE, m.appearance.sex)
        assertEquals(57, m.model.chrModelId)
    }

    @Test fun randomizeKeepsChoicesRight() {
        val m = CreatorModel(catalog, humanWoman)
        m.random = kotlin.random.Random(7)
        repeat(50) {
            m.randomize()
            assertEquals(emptyList(), m.resolved(tables).diagnostics.filter { !it.startsWith("Glow") })
        }
    }

    @Test fun bodyRowsGreyedUntilImported() {
        val m = CreatorModel(catalog, humanWoman)
        val rows = m.rows(1)
        assertEquals(listOf("Upright", "Shape", "Style", "Density", "Gear"), rows.map { it.option.name })
        val shape = rows[1]
        assertFalse(shape.enabled)
        assertEquals(BodyOptions.NOT_IMPORTED, shape.why)
        assertEquals(listOf("As drawn", "Petite", "Curvy", "Voluptuous"), shape.option.choices.map { it.name })
        assertEquals(17, rows[2].option.choices.size)
        assertEquals(5, rows[3].option.choices.size)
        m.step(BodyOptions.SHAPE, 1)
        assertEquals(Body(), m.appearance.body)                 // greyed: nothing changes
        m.bodyAvailable = true
        assertTrue(m.rows(1)[1].enabled)
        m.step(BodyOptions.SHAPE, 1)
        m.choose(BodyOptions.STYLE, 3)
        m.step(BodyOptions.DENSITY, -1)                         // round the end: 100%
        assertEquals(Body(1, 3, 4), m.appearance.body)
        // no Body rows for a man, nor for a race the body options don't have
        m.setSex(Sex.MALE)
        assertEquals(Body(), m.appearance.body)
        assertTrue(m.categories().none { it.name == "Body" })
        val voidElf = CreatorModel(catalog, Appearance(29, Sex.FEMALE))
        assertTrue(voidElf.categories().none { it.name == "Body" })
    }

    @Test fun bodyTabOfItsOwn() {
        val m = CreatorModel(catalog, Appearance(2, Sex.FEMALE))       // an orc woman: no Body category in her looks
        val body = m.categories().last()
        assertEquals(BodyOptions.BODY_TAB, body.id)
        assertEquals(listOf("Shape", "Style", "Density", "Gear"), m.rows(body.id).map { it.option.name })
    }

    private class FakeImports(val body: Boolean) : Overrides {
        override fun texture(retailFileId: Int, female: Boolean) = if (female && retailFileId == 3000) 9000 else retailFileId
        override fun bodyModel(raceId: Int, shape: Int): Int? = 7000 + raceId * 10 + shape
        override fun bodyLayer(raceId: Int, style: Int, density: Int): Int? = 8000 + style * 10 + density
        override fun hasRalia() = true
        override fun hasBody() = body
    }

    @Test fun importsReplaceTexturesAndDrawTheBody() {
        val a = humanWoman.copy(body = Body(2, 5, 3))
        val r = LookResolver.resolve(catalog, tables, a, FakeImports(true))
        assertEquals(4001, r.model)                             // the race's own model: a shape is a patch on it (BodyShapes)
        assertEquals(9000, r.textures[1])                       // Ralia's skin in place of the game's
        val layer = r.layers.last { it.textureType == 1 }
        assertEquals(TextureLayer(8053, -1, 1, LookResolver.BODY_LAYER, null, null, 1), layer)
        assertTrue(8053 in r.needsFiles && 7012 !in r.needsFiles && 3000 !in r.needsFiles)
        assertEquals(emptyList(), r.diagnostics)
        // without the package: drawn as the race's own, and said so
        val none = LookResolver.resolve(catalog, tables, a, FakeImports(false))
        assertEquals(4001, none.model)
        assertTrue(none.diagnostics.single().contains(BodyOptions.NOT_IMPORTED))
        // the race's own shape, no style: nothing of the package
        assertEquals(4001, LookResolver.resolve(catalog, tables, humanWoman, FakeImports(true)).model)
    }

    @Test fun appearanceJson() {
        val a = Appearance(4, Sex.FEMALE, mapOf(123 to 456), Body(1, 2, 3), false)
        val o = a.toJson()
        assertTrue(JSONObject("""{"race":4,"sex":"FEMALE","picks":{"123":456},"body":{"shape":1,"style":2,"density":3},"gear":false}""").similar(o))
        assertEquals(a, Appearance.fromJson(JSONObject(o.toString())))
        assertEquals(Appearance(4, Sex.MALE), Appearance.fromJson(JSONObject("""{"race":4,"sex":"MALE"}""")))
    }
}
