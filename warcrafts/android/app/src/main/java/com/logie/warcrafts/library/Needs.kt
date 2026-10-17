// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcrafts.library

/**
 * What each game needs from the user's files, item by item, for the launcher's checklist: the
 * program, the game's data, and for Warcraft III its maps and CD keys. Each item is checked in the
 * game's folder in the app. Plain Kotlin, unit-tested.
 *
 * The Remastered games' items are those of a Battle.net install (the program in `x86`, the game's
 * data in `Data` with `.build.info` beside it, as every Battle.net game keeps it), from what players
 * report of their folders; no install of them has been looked at here.
 */
object Needs {
    enum class Item {
        /** The program that starts the game. */
        PROGRAM,
        /** `Data/data`, `Data/indices`, `Data/config`: the game's data (Blizzard's CASC storage). */
        DATA,
        /** `.build.info`, beside `Data`: which version of the data it is. */
        BUILD_INFO,
        /** `Maps/`: skirmish and custom maps (Warcraft III). */
        MAPS,
        /** The CD keys Warcraft III's first start asks for: in a text file beside the program, or the user's own. */
        CD_KEYS,
    }

    data class Need(val item: Item, val paths: List<String>, val required: Boolean)

    fun of(install: Install): List<Need> = buildList {
        add(Need(Item.PROGRAM, install.mainNames.take(1), required = true))
        add(Need(Item.DATA, listOf("Data/data", "Data/indices", "Data/config"), required = true))
        add(Need(Item.BUILD_INFO, listOf(".build.info"), required = true))
        if (install == Install.WC3) {
            add(Need(Item.MAPS, listOf("Maps"), required = false))
            add(Need(Item.CD_KEYS, listOf("_SERIAL_KEYS.txt"), required = false))
        }
    }

    /**
     * Each need of [install] and whether it's met, given what's in the game's folder ([exists]:
     * a path relative to it, case-insensitive on the caller's side), the program the app found
     * ([program], for a program in another place than the usual one) and whether CD keys were
     * found ([hasKeys]).
     */
    fun check(install: Install, program: String?, hasKeys: Boolean, exists: (String) -> Boolean): List<Pair<Need, Boolean>> =
        of(install).map { need ->
            need to when (need.item) {
                Item.PROGRAM -> program != null
                Item.CD_KEYS -> hasKeys
                else -> need.paths.all(exists)
            }
        }

    /** Whether every required item is there. */
    fun ready(checked: List<Pair<Need, Boolean>>): Boolean = checked.all { (need, met) -> met || !need.required }
}
