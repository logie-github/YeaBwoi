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
 * - Warcraft III 1.27, set up in the app: Blizzard's classic installers downloaded, then installed
 *   (the game's programs and `War3.mpq`, `War3x.mpq` in the app's Warcraft III folder).
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
        /** Blizzard's Warcraft III installers, downloaded into the app. */
        WC3_DOWNLOADED,
        /** Warcraft III installed (Reign of Chaos and The Frozen Throne) in the app. */
        WC3_INSTALLED,
        /** The player's CD keys written into the game ([Wc3Keys]). */
        WC3_KEYS,
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
            Need(Item.WC3_DOWNLOADED, emptyList(), required = true),
            Need(Item.WC3_INSTALLED, listOf("Warcraft III.exe", "Frozen Throne.exe", "War3.mpq", "War3x.mpq"), required = true),
            Need(Item.WC3_KEYS, listOf(Wc3Keys.SAVED), required = true),
        )
    }

    /**
     * Each need of [install] and whether it's met: [exists] answers for a path in the game's folder
     * (case-insensitive on the caller's side), [converted] whether Stratagus's data is made,
     * [downloaded] whether Warcraft III's installers are in the app (an installed game counts).
     */
    fun check(
        install: Install,
        converted: Boolean = false,
        downloaded: Boolean = false,
        exists: (String) -> Boolean,
    ): List<Pair<Need, Boolean>> =
        of(install).map { need ->
            need to when (need.item) {
                Item.WC3_DOWNLOADED -> downloaded || need(install, Item.WC3_INSTALLED).paths.all(exists)
                Item.CONVERTED -> converted
                else -> need.paths.all(exists)
            }
        }

    private fun need(install: Install, item: Item) = of(install).first { it.item == item }

    /** Whether every required item is there. */
    fun ready(checked: List<Pair<Need, Boolean>>): Boolean = checked.all { (need, met) -> met || !need.required }

    /** Whether the user's files are all in (everything but the conversion, which the app does). */
    fun filesIn(checked: List<Pair<Need, Boolean>>): Boolean =
        checked.all { (need, met) -> met || !need.required || need.item == Item.CONVERTED }
}
