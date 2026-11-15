package com.logie.wifecraft.creator

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportNamesTest {
    private val csv = "1;interface/cinematics/logo_800.avi\n117125;character/bloodelf/female/bloodelffemalenakedpelvisskin00_03.blp\nbad line\n" +
        "119870;Character/Human/Female/HumanFemaleNakedPelvisSkin00_03.blp\nx;character/y.blp\n"

    @Test fun parseKeepsCharacterLinesLowerCased() {
        val t = ListfileNames.parse(ByteArrayInputStream(csv.toByteArray()))
        assertEquals(2, t.size)
        assertEquals(119870, t["character/human/female/humanfemalenakedpelvisskin00_03.blp"])
    }

    @Test fun gamePathFindsTheCharacterFolderAnywhere() {
        assertEquals("character/human/female/a.blp", ImportNames.gamePath("Character\\Human\\Female\\A.blp"))
        assertEquals("character/human/female/a.blp", ImportNames.gamePath("Ralia/Data/Character/Human/Female/a.blp"))
        assertNull(ImportNames.gamePath("Interface/a.blp"))
        assertNull(ImportNames.gamePath("mycharacter/a.blp"))
    }

    @Test fun retailIdsTryTheHdTwin() {
        val n = MapNames(mapOf("character/a/s_hd.blp" to 1, "character/a/s.blp" to 2, "character/a/t.blp" to 3))
        assertEquals(listOf(1, 2), ImportNames.retailIds(n, "character/a/s_hd.blp"))
        assertEquals(listOf(2, 1), ImportNames.retailIds(n, "character/a/s.blp"))
        assertEquals(listOf(3), ImportNames.retailIds(n, "character/a/t.blp"))
        assertTrue(ImportNames.retailIds(n, "character/a/u.blp").isEmpty())
    }

    @Test fun refreshCachesAndAFreshInstanceReadsTheCache() {
        val dir = Files.createTempDirectory("names").toFile(); val cache = File(dir, "imports/names.csv")
        val a = ListfileNames(cache) { ByteArrayInputStream(csv.toByteArray()) }
        assertFalse(a.available())
        assertEquals(2, a.refresh())
        assertTrue(a.available())
        val b = ListfileNames(cache) { error("no network") }
        assertEquals(119870, b.id("character/human/female/humanfemalenakedpelvisskin00_03.blp"))
        assertFalse(File(cache.path + ".part").exists())
    }

    @Test fun refreshThatFindsNothingKeepsTheOldCache() {
        val dir = Files.createTempDirectory("names").toFile(); val cache = File(dir, "names.csv")
        ListfileNames(cache) { ByteArrayInputStream(csv.toByteArray()) }.refresh()
        val bad = ListfileNames(cache) { ByteArrayInputStream("<html>".toByteArray()) }
        assertFailsWith<java.io.IOException> { bad.refresh() }
        assertTrue(bad.available())
    }
}
