package com.logie.wifecraft.catalog

import com.logie.wifecraft.creator.CsvTables
import com.logie.wifecraft.creator.Tables
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogTest {
    private val tsv = """
        #wifecraft-catalog	x
        #cats	Lighting	Furniture	Nature	Buildings	Other	Beast
        #styles	Any	Human	Forsaken
        #sizes	Tiny	Small	Medium	Large	Huge
        100	Duskwood lamppost 01	0	1	3	0	0
        101	Forsaken brazier 02	0	2	2	0	0	Brazier
        102	Wooden chair 01	1	1	1	0	0	Chair
        103	Duskwoodtree 05	2	1	4	0	0
        104	Gilneas castle	3	1	4	1	0
        105	Wolf	5	0	2	2	9001
        106	Pinetree 01	2	0	-1	0	0
    """.trimIndent()

    private fun lib() = Catalog(Catalog.parseTsv(tsv))

    @Test fun parses() {
        val a = Catalog.parseTsv(tsv)
        assertEquals(7, a.size)
        val wolf = a.first { it.fileId == 105 }
        assertEquals(AssetKind.CREATURE_MODEL, wolf.kind); assertEquals(9001, wolf.display); assertEquals("Beast", wolf.category)
        assertEquals("m:105", wolf.key)
        assertEquals(AssetKind.BUILDING, a.first { it.fileId == 104 }.kind)
        assertEquals("Brazier", a.first { it.fileId == 101 }.alias)
        assertEquals("Forsaken", a.first { it.fileId == 101 }.style)
        assertEquals(-1, a.first { it.fileId == 106 }.size)
    }

    @Test fun searchesWordsAliasesAndGluedNames() {
        val c = lib()
        assertEquals(listOf(103, 106), c.search("tree").items.map { it.fileId }.sorted())   // inside glued names
        assertEquals(listOf(102), c.search("chair").items.map { it.fileId })
        assertEquals(listOf(101), c.search("braz").items.map { it.fileId })               // alias/word prefix
        assertEquals(listOf(103, 100), c.search("duskwood").items.map { it.fileId })      // both begin with it: shorter first
        assertEquals(setOf(100, 101), c.search("", category = "Lighting").items.map { it.fileId }.toSet())
        assertEquals(listOf(101), c.search("", style = "Forsaken").items.map { it.fileId })
        assertEquals(listOf(104), c.search("castle", kinds = setOf(AssetKind.BUILDING)).items.map { it.fileId })
        assertEquals(0, c.search("castle", kinds = setOf(AssetKind.PROP)).total)
        assertEquals(listOf(103, 100), c.search("human duskwood").items.map { it.fileId })  // style words count
    }

    @Test fun pages() {
        val c = lib()
        val all = c.search("")
        assertEquals(7, all.total)
        val p1 = c.search("", offset = 0, limit = 3)
        val p2 = c.search("", offset = 3, limit = 3)
        val p3 = c.search("", offset = 6, limit = 3)
        assertEquals(all.items, p1.items + p2.items + p3.items)
        assertEquals(1, p3.items.size)
        assertEquals(0, c.search("", offset = 50).items.size)
    }

    @Test fun ownPeopleAndStarterPacks() {
        val c = lib().plus(listOf(Catalog.ownNpc("a1", "Lady Vashj")))
        assertEquals("n:a1", c.search("vashj").items.single().key)
        assertEquals(AssetKind.OWN_NPC, c["n:a1"]!!.kind)
        assertTrue(c.categories().any { it.first == Catalog.MY_PEOPLE })
        val pack = c.starterPack("Human")
        assertEquals(setOf(100, 102, 103), pack.map { it.fileId }.toSet())
    }

    @Test fun creaturesFromTheTables() {
        val t = object : Tables {
            val d = mapOf(
                "CreatureType" to listOf(mapOf("ID" to "1", "Name_lang" to "Beast"), mapOf("ID" to "7", "Name_lang" to "Humanoid")),
                "CreatureFamily" to listOf(mapOf("ID" to "1", "Name_lang" to "Wolf")),
                "CreatureModelData" to listOf(mapOf("ID" to "10", "FileDataID" to "5000"), mapOf("ID" to "11", "FileDataID" to "0")),
                "CreatureDisplayInfo" to listOf(
                    mapOf("ID" to "20", "ModelID" to "10", "CreatureModelScale" to "1.5", "ExtendedDisplayInfoID" to "0"),
                    mapOf("ID" to "21", "ModelID" to "10", "CreatureModelScale" to "1", "ExtendedDisplayInfoID" to "77"),
                    mapOf("ID" to "22", "ModelID" to "11", "CreatureModelScale" to "1", "ExtendedDisplayInfoID" to "0")),
                "Creature" to listOf(
                    mapOf("ID" to "1", "Name_lang" to "Grey Wolf", "CreatureType" to "1", "CreatureFamily" to "1", "DisplayID_0" to "20", "Title_lang" to ""),
                    mapOf("ID" to "2", "Name_lang" to "Archmage Khadgar", "CreatureType" to "7", "CreatureFamily" to "0", "DisplayID_0" to "0", "DisplayID_1" to "21", "Title_lang" to "Archmage"),
                    mapOf("ID" to "3", "Name_lang" to "Archmage Khadgar", "CreatureType" to "7", "CreatureFamily" to "0", "DisplayID_0" to "21"),
                    mapOf("ID" to "4", "Name_lang" to "Spell Bunny", "CreatureType" to "10", "DisplayID_0" to "20"),
                    mapOf("ID" to "5", "Name_lang" to "No Model", "CreatureType" to "1", "DisplayID_0" to "22")),
            )
            override fun rows(name: String) = d[name].orEmpty()
        }
        val cs = Catalog.creatures(t)
        assertEquals(listOf("c:1", "c:2"), cs.map { it.key })
        val wolf = cs[0]
        assertEquals(5000, wolf.fileId); assertEquals(20, wolf.display); assertEquals(1.5f, wolf.scale)
        assertEquals("Beast", wolf.category); assertEquals("Wolf", wolf.style)
        assertEquals(77, cs[1].extra); assertEquals("Archmage", cs[1].alias)
        assertEquals("c:2", Catalog(cs).search("khad").items.single().key)
    }

    /** The app's catalog.tsv (tools/catalog.py). */
    @Test fun theAppsFile() {
        val f = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets", "catalog.tsv")
        val a = Catalog.parseTsv(f.readText())
        assertTrue(a.size > 60000, "${a.size}")
        assertEquals(a.size, a.map { it.key }.toSet().size)
        val c = Catalog(a)
        for (k in AssetKind.entries.take(3)) assertTrue(a.any { it.kind == k }, "$k")
        for (q in listOf("barrel", "chair", "lamp", "tree", "banner", "tent", "anvil", "boat"))
            assertTrue(c.search(q, kinds = setOf(AssetKind.PROP)).total > 20, q)
        assertTrue(c.search("", category = "Furniture", style = "Forsaken").total > 0)
        assertTrue(c.starterPack("Human").size >= 30)
    }

    /** With the game's tables (WIFECRAFT_CATALOG_TABLES: Creature, CreatureDisplayInfo, CreatureModelData, CreatureType). */
    @Test fun realCreatures() {
        val dir = System.getenv("WIFECRAFT_CATALOG_TABLES")?.let(::File)?.takeIf { File(it, "Creature.csv").isFile }
        assumeTrue(dir != null, "WIFECRAFT_CATALOG_TABLES isn't set")
        val cs = Catalog.creatures(CsvTables(dir!!))
        assertTrue(cs.size > 10000, "${cs.size}")
        assertTrue(Catalog(cs).search("khadgar").total > 0)
    }
}
