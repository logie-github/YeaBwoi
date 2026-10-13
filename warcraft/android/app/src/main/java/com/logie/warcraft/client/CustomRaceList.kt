// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.client

/**
 * The custom races (warcraft/races): their ids, which are theirs for good (a character keeps its
 * race's id), and where their files go in the client.
 */
object CustomRaceList {
    data class Race(val id: Int, val name: String, val alliance: Boolean)

    /** What the package has (warcraft/races/manifest), for before it's downloaded. */
    val RACES = listOf(
        Race(9, "Goblin", alliance = false),
        Race(12, "Worgen", alliance = true),
        Race(13, "High Elf", alliance = true),
        Race(14, "Mag'har Orc", alliance = false),
        Race(15, "Ogre", alliance = false),
        Race(16, "Dark Iron Dwarf", alliance = true),
        Race(17, "Zandalari Troll", alliance = false),
        Race(18, "Vulpera", alliance = false),
        Race(19, "Pandaren", alliance = true),
        Race(20, "Pandaren", alliance = false),
        Race(21, "Broken", alliance = true),
    )

    /** The game's own races: random bots keep to these. */
    val ORIGINAL_IDS = setOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 11)

    /**
     * The client's patch folder for them, in `Data`: the game loads a folder named like a patch as
     * one, and the last letter goes over every other patch (the HD looks' among them).
     */
    const val PATCH_FOLDER = "patch-Z.MPQ"

    /**
     * The custom races in a package's races.txt ("<id> <alliance|horde> <name>" a line, the game's
     * own races among them).
     */
    fun parse(text: String): List<Race> = text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(' ', limit = 3)
        val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        if (parts.size < 3 || id in ORIGINAL_IDS) return@mapNotNull null
        Race(id, parts[2], alliance = parts[1] == "alliance")
    }.toList()
}
