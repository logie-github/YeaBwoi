package com.logie.warcraft.client

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomRaceListTest {
    @Test
    fun aPackagesRacesAreItsCustomOnes() {
        val text = """
            1 alliance Human
            2 horde Orc
            9 horde Goblin
            10 horde Blood Elf
            11 alliance Draenei
            12 alliance Worgen
            16 alliance Dark Iron Dwarf
            20 horde Pandaren
        """.trimIndent()
        assertEquals(
            listOf(
                CustomRaceList.Race(9, "Goblin", alliance = false),
                CustomRaceList.Race(12, "Worgen", alliance = true),
                CustomRaceList.Race(16, "Dark Iron Dwarf", alliance = true),
                CustomRaceList.Race(20, "Pandaren", alliance = false),
            ),
            CustomRaceList.parse(text),
        )
    }

    /** The list matches the package's manifest: its races, in order, with the ids it gives them. */
    @Test
    fun theListIsTheManifests() {
        val manifest = File("../races/manifest").readLines()
        val ids = manifest.mapNotNull { line ->
            if (!line.startsWith("F-")) return@mapNotNull null
            Regex("#\\s*(\\d+)\\s*$").find(line)?.groupValues?.get(1)?.toInt()
        }
        assertEquals(CustomRaceList.RACES.map { it.id }, ids)
        assertTrue(CustomRaceList.RACES.none { it.id in CustomRaceList.ORIGINAL_IDS })
        assertEquals(setOf(9, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21), CustomRaceList.RACES.map { it.id }.toSet())
    }
}
