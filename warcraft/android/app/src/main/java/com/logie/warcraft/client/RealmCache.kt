// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.client

/**
 * The client's cache (`Cache/WDB`) keeps what a realm told it about quests, creatures and items,
 * by id. Wrath Remix gives some of the game's own quests and items other levels and stats than
 * ChromieCraft, so the cache goes whenever the game starts on another realm than the last time.
 */
object RealmCache {
    /** The realm a client had played on before the app had a record of it: ChromieCraft's. */
    const val FIRST_REALM = "main"

    /** Whether the cache goes before playing on [now], having last played on [last] (null: no record). */
    fun mustClear(last: String?, now: String): Boolean = (last?.trim()?.ifEmpty { null } ?: FIRST_REALM) != now
}
