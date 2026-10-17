// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import java.io.File

/**
 * The phone's two realms, one auth server and one set of accounts between them:
 *
 * - **ChromieCraft** (realm 1, port 8085): the progressive ChromieCraft realm, as it was.
 * - **Wrath Remix** (realm 2, port 8086): a Legion Remix-style replay of Northrend from level 10,
 *   run by the `mod-wrath-remix` module with configs and databases of its own (`files/etc-remix`,
 *   `acore_world_remix`, `acore_characters_remix`, `acore_playerbots_remix`). Its characters,
 *   world and settings are apart from ChromieCraft's.
 *
 * One world server runs at a time ([Server] starts the chosen one); the game logs in to it by name.
 */
object Realms {
    enum class Realm(
        /** The realm's name for `ctl.sh` (its configs and databases). */
        val key: String,
        val id: Int,
        /** As the realm list shows it, and `Config.wtf`'s realmName. */
        val displayName: String,
        val port: Int,
        val charactersDatabase: String,
        val label: Int,
        val summary: Int,
    ) {
        CHROMIECRAFT("main", 1, "ChromieCraft", 8085, "acore_characters", R.string.wow_realm_chromiecraft, R.string.wow_realm_chromiecraft_summary),
        REMIX("remix", 2, "Wrath Remix", 8086, "acore_characters_remix", R.string.wow_realm_remix, R.string.wow_realm_remix_summary),
    }

    private const val PREFS = "realms"
    private const val KEY = "realm"

    // (in a file, read each time: the app's process and the realm's ([RealmLink]) both read and
    // write it, and preferences are cached per process)
    private fun file(context: Context) = File(WowFiles.run(context), "realm")

    /** The realm Press start starts. */
    fun current(context: Context): Realm {
        val key = runCatching { file(context).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
            ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return Realm.entries.firstOrNull { it.key == key } ?: Realm.CHROMIECRAFT
    }

    fun set(context: Context, realm: Realm) {
        runCatching { file(context).apply { parentFile?.mkdirs() }.writeText(realm.key) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, realm.key).apply()
    }
}
