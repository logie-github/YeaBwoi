package com.logie.wifecraft.creator

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The glue between the look and the renderer (Dressing, M2Files) and the tables' fetch (GameTables), on made-up rows. */
class DressingTest {
    private class MapTables(private val t: Map<String, List<Map<String, String>>>) : Tables {
        override fun rows(name: String) = t[name].orEmpty()
    }

    private fun row(vararg kv: Pair<String, Any>) = kv.associate { it.first to it.second.toString() }

    private val tables = MapTables(mapOf(
        "ChrModel" to listOf(row("ID" to 8, "CharComponentTextureLayoutID" to 2)),
        "ChrModelMaterial" to listOf(
            row("CharComponentTextureLayoutsID" to 2, "TextureType" to 1, "Width" to 2048, "Height" to 1024),
            row("CharComponentTextureLayoutsID" to 2, "TextureType" to 6, "Width" to 512, "Height" to 256),
        ),
        "CharComponentTextureSections" to listOf(
            row("CharComponentTextureLayoutID" to 2, "SectionType" to 0, "X" to 0, "Y" to 0, "Width" to 512, "Height" to 256),
            row("CharComponentTextureLayoutID" to 2, "SectionType" to 9, "X" to 1024, "Y" to 0, "Width" to 1024, "Height" to 1024),
            row("CharComponentTextureLayoutID" to 2, "SectionType" to 10, "X" to 1024, "Y" to 0, "Width" to 1024, "Height" to 1024),
            row("CharComponentTextureLayoutID" to 1, "SectionType" to 9, "X" to 7, "Y" to 7, "Width" to 7, "Height" to 7),
        ),
    ))

    private val look = ResolvedLook(
        model = 921844, scale = 1.1f, geosetGroups = mapOf(0 to 4, 7 to 1), textures = mapOf(1 to 100),
        layers = listOf(
            TextureLayer(100, -1, 0, 0, null, null, 1),                 // the whole skin
            TextureLayer(101, (1 shl 9) or (1 shl 10), 15, 6, 0x112233, 0x445566, 1),  // the first section in the mask: type 9
            TextureLayer(102, -1, 1, 1, null, null, 6),                 // the hair's own material
            TextureLayer(103, 1 shl 4, 1, 2, null, null, 1),            // no section 4 in this layout: left out
            TextureLayer(104, -1, 1, 1, null, null, 19),                // no material of type 19: left out
        ),
        extraModels = listOf(ExtraModel(-1, 7760204, 0, 2401), ExtraModel(-1, 7760205, 0, 702), ExtraModel(11, 5000, 6000)),
        needsFiles = emptySet(),
    )

    @Test fun layersBecomeRectanglesTintsAndModels() {
        val d = Dressing.call(tables, 2, look)
        assertEquals(921844, d.model)
        assertEquals(1.1f, d.scale)
        assertContentEquals(intArrayOf(100, 101, 102), d.layerFiles)
        assertContentEquals(intArrayOf(
            1, 0, 0, 0, 0, 2048, 1024, 2048, 1024,
            1, 6, 15, 1024, 0, 1024, 1024, 2048, 1024,
            6, 1, 1, 0, 0, 512, 256, 512, 256,
        ), d.layerMeta)
        assertContentEquals(intArrayOf(0, 0, 0xFF112233.toInt(), 0xFF445566.toInt(), 0, 0), d.tintColours)
        assertEquals(2, d.skipped.size)
        assertContentEquals(intArrayOf(-1, 7760204, 0, 2401, -1, 7760205, 0, 702, 11, 5000, 6000, 0), d.extraModels)
        // a skinned model's geoset joins the groups; a group the look sets keeps its value
        assertContentEquals(intArrayOf(0, 7, 24), d.geosetKeys)
        assertContentEquals(intArrayOf(4, 1, 1), d.geosetValues)
        assertEquals(listOf(921844, 7760204, 7760205, 5000), Dressing.models(look))
        assertEquals(Dressing.META * d.layerFiles.size, d.layerMeta.size)
        assertEquals(Dressing.TINTS * d.layerFiles.size, d.tintColours.size)
    }

    @Test fun tintsAreOpaqueColoursOrNone() {
        assertEquals(0, Dressing.tint(null))
        assertEquals(0xFFABCDEF.toInt(), Dressing.tint(0x12ABCDEF))
    }

    @Test fun bodyOptionsNeedThePackage() {
        val root = Files.createTempDirectory("body").toFile()
        val a = Appearance(4, Sex.FEMALE, body = Body(1, 2, 3))
        assertSame(BodyCall.NONE, Dressing.body(root, a, imported = false))
        assertSame(BodyCall.NONE, Dressing.body(root, a.copy(sex = Sex.MALE), imported = true))
        // imported but no package files: no shape; the race's frame and the layer's square with a style and density
        val b = Dressing.body(root, a, imported = true)
        assertNull(b.morph)
        BodyShapes.frame(4)?.let { assertEquals(it.k, b.k); assertEquals(it.fold, b.fold) }
        assertEquals(0f, Dressing.body(root, a.copy(body = Body(1, 0, 0)), imported = true).k)
    }

    private fun chunk(out: ByteArrayOutputStream, tag: String, ints: IntArray) {
        val b = ByteBuffer.allocate(8 + ints.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.put(tag.toByteArray(Charsets.ISO_8859_1)); b.putInt(ints.size * 4); ints.forEach { b.putInt(it) }
        out.write(b.array())
    }

    @Test fun aModelsSkinTexturesAndAnimations() {
        val o = ByteArrayOutputStream()
        chunk(o, "MD21", intArrayOf(0x3032444D, 0, 0))
        chunk(o, "SFID", intArrayOf(11, 12))
        chunk(o, "TXID", intArrayOf(0, 21, 22))
        // AFID: (anim u16, variation u16, file u32) pairs
        chunk(o, "AFID", intArrayOf(0 or (0 shl 16), 31, 60 or (0 shl 16), 32, 60 or (1 shl 16), 33, 999 or (0 shl 16), 34, 4, 0))
        val deps = M2Files.deps(o.toByteArray(), setOf(0, 4, 60))
        assertEquals(listOf(11, 21, 22, 31, 32), deps)
        assertEquals(emptyList(), M2Files.deps("MD20xxxx".toByteArray(), setOf(0)))
        assertEquals(emptyList(), M2Files.deps(ByteArray(3), setOf(0)))
    }

    @Test fun tablesAreFetchedWholeAndKept() {
        val dir = Files.createTempDirectory("tables").toFile()
        val asked = ArrayList<String>()
        GameTables.fetch(dir, open = { name -> asked += name; ByteArrayInputStream("ID\n1\n".toByteArray()) })
        assertEquals(GameTables.NAMES, asked)
        assertTrue(GameTables.complete(dir))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
        asked.clear()
        GameTables.fetch(dir, open = { name -> asked += name; ByteArrayInputStream(ByteArray(0)) })
        assertEquals(emptyList(), asked)                                   // kept: nothing fetched again
        assertEquals(listOf(1), CsvTables(dir).rows("ChrModel").map { it.int("ID") })
        assertTrue(GameTables.url("GlobalStrings").endsWith("/GlobalStrings/csv?build=9.2.7.45745"))
        assertEquals("https://wago.tools/db2/ChrModel/csv?build=${GameTables.BUILD}", GameTables.url("ChrModel"))
    }

    @Test fun aTableThatWontComeStopsTheFetch() {
        val dir = Files.createTempDirectory("tables").toFile()
        var n = 0
        assertFailsWith<IOException> {
            GameTables.fetch(dir, pause = {}, open = { n++; if (it == "ChrModel") throw IOException("no") else ByteArrayInputStream("ID\n".toByteArray()) })
        }
        assertTrue(GameTables.has(dir, "ChrRaces") && !GameTables.has(dir, "ChrModel"))
        assertTrue(n > 3)   // tried again before giving up
    }

    /** Every table the creator, the look and the glue read is one the app fetches. */
    @Test fun everyTableReadIsFetched() {
        val real = CsvTables(java.io.File(javaClass.getResource("/creator/ChrModel.csv")!!.toURI()).parentFile)
        val read = LinkedHashSet<String>()
        val spy = object : Tables { override fun rows(name: String) = real.rows(name).also { read += name } }
        val catalog = Catalog.build(spy)
        val entry = catalog.races.first().let { r -> catalog.model(r.id, r.sexes.first())!! }
        val a = Appearance(entry.race.id, entry.sex)
        val l = LookResolver.resolve(catalog, spy, a)
        Dressing.call(spy, Dressing.layout(catalog, spy, a), l)
        assertTrue(read.isNotEmpty())
        assertEquals(emptySet(), read - GameTables.NAMES.toSet())
    }
}
