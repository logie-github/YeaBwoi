// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

/**
 * What each game needs from the user's files, item by item, for the launcher's checklist. Each item
 * is checked in the game's folder in the app (or, for [Item.CONVERTED], in the data Stratagus plays
 * from). Plain Kotlin, unit-tested.
 *
 * - Warcraft I (GOG): `DATA\DATA.WAR` and the other `.WAR` files beside it, which War1gus's tool
 *   converts.
 * - Warcraft II Battle.net Edition (GOG): `Support\TOMES\TOME.1`–`TOME.4` and `Install.mpq`, which
 *   Wargus's tool converts.
 * - Warcraft III 1.30: its program, `Data\{data,indices,config}`, `.build.info`; optional `Maps` and
 *   CD keys.
 */
object Needs {
    enum class Item {
        /** Warcraft I's data: `DATA\DATA.WAR` (and the other `.WAR` files in `DATA`). */
        WAR_DATA,
        /** Warcraft II Battle.net Edition's data: the four tomes. */
        TOMES,
        /** Warcraft II Battle.net Edition's sounds, music and videos: `Install.mpq`. */
        INSTALL_MPQ,
        /** The files converted for Stratagus on the phone (done by the app after the import). */
        CONVERTED,
        /** Warcraft III's program. */
        PROGRAM,
        /** `Data/data`, `Data/indices`, `Data/config`: Warcraft III's data (Blizzard's CASC storage). */
        DATA,
        /** `.build.info`, beside `Data`: which version of the data it is. */
        BUILD_INFO,
        /** `Maps/`: skirmish and custom maps (Warcraft III). */
        MAPS,
        /** The CD keys Warcraft III's first start asks for. */
        CD_KEYS,
    }

    data class Need(val item: Item, val paths: List<String>, val required: Boolean)

    fun of(install: Install): List<Need> = when (install) {
        Install.WC1 -> listOf(
            Need(Item.WAR_DATA, listOf("DATA/DATA.WAR"), required = true),
            Need(Item.CONVERTED, emptyList(), required = true),
        )
        Install.WC2 -> listOf(
            Need(Item.TOMES, (1..4).map { "Support/TOMES/TOME.$it" }, required = true),
            Need(Item.INSTALL_MPQ, listOf("Install.mpq"), required = true),
            Need(Item.CONVERTED, emptyList(), required = true),
        )
        Install.WC3 -> listOf(
            Need(Item.PROGRAM, install.mainNames.take(1), required = true),
            Need(Item.DATA, listOf("Data/data", "Data/indices", "Data/config"), required = true),
            Need(Item.BUILD_INFO, listOf(".build.info"), required = true),
            Need(Item.MAPS, listOf("Maps"), required = false),
            Need(Item.CD_KEYS, listOf("_SERIAL_KEYS.txt"), required = false),
        )
    }

    /**
     * Each need of [install] and whether it's met: [exists] answers for a path in the game's folder
     * (case-insensitive on the caller's side), [program] is Warcraft III's program the app found,
     * [hasKeys] whether CD keys were found, [converted] whether Stratagus's data is made.
     */
    fun check(
        install: Install,
        program: String?,
        hasKeys: Boolean,
        converted: Boolean = false,
        exists: (String) -> Boolean,
    ): List<Pair<Need, Boolean>> =
        of(install).map { need ->
            need to when (need.item) {
                Item.PROGRAM -> program != null
                Item.CD_KEYS -> hasKeys
                Item.CONVERTED -> converted
                else -> need.paths.all(exists)
            }
        }

    /** Whether every required item is there. */
    fun ready(checked: List<Pair<Need, Boolean>>): Boolean = checked.all { (need, met) -> met || !need.required }

    /** Whether the user's files are all in (everything but the conversion, which the app does). */
    fun filesIn(checked: List<Pair<Need, Boolean>>): Boolean =
        checked.all { (need, met) -> met || !need.required || need.item == Item.CONVERTED }
}
