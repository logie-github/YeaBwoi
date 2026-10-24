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
 * - Warcraft III 1.27 (installed with Blizzard's own classic installers, on a PC): its program,
 *   `War3.mpq` (Reign of Chaos), `War3x.mpq` and `War3xLocal.mpq` (The Frozen Throne),
 *   `War3Patch.mpq`; optional `Maps`. The CD keys are entered in Blizzard's installer, once.
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
        /** `War3.mpq`: Reign of Chaos's data. */
        ROC_DATA,
        /** `War3x.mpq`, `War3xLocal.mpq`: The Frozen Throne's data. */
        TFT_DATA,
        /** `War3Patch.mpq`: the patch's data (1.27). */
        PATCH,
        /** `Maps/`: skirmish and custom maps (Warcraft III). */
        MAPS,
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
            Need(Item.ROC_DATA, listOf("War3.mpq"), required = true),
            Need(Item.TFT_DATA, listOf("War3x.mpq", "War3xLocal.mpq"), required = true),
            Need(Item.PATCH, listOf("War3Patch.mpq"), required = true),
            Need(Item.MAPS, listOf("Maps"), required = false),
        )
    }

    /**
     * Each need of [install] and whether it's met: [exists] answers for a path in the game's folder
     * (case-insensitive on the caller's side), [program] is Warcraft III's program the app found,
     * [converted] whether Stratagus's data is made.
     */
    fun check(
        install: Install,
        program: String?,
        converted: Boolean = false,
        exists: (String) -> Boolean,
    ): List<Pair<Need, Boolean>> =
        of(install).map { need ->
            need to when (need.item) {
                Item.PROGRAM -> program != null
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
