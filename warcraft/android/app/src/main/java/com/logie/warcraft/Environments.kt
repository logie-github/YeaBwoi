// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The games the launcher manages, each a world of its own on the phone:
 *
 * - [Environment.WRATH]: Wrath of the Lich King 3.3.5a with its ChromieCraft realm (AzerothCore),
 *   as the app has always been: `files/server`, `files/client`, `files/mysql`...
 * - [Environment.TURTLE]: Turtle WoW (1.18.1, "Vanilla+") with its Tortoise-WoW realm, all of it in
 *   `files/turtle` (com.logie.warcraft.turtle).
 * - [Environment.FELSONG]: Felsong (Legion 7.3.5), com.logie.warcraft.felsong: its look and a page
 *   that says where it stands; its realm (LegionCore) isn't on the phone yet (FELSONG.md).
 *
 * Which one the launcher shows, and themes itself for, is remembered here; choosing one changes
 * nothing of the other's.
 */
object Environments {
    enum class Environment(val key: String) { WRATH("wrath"), TURTLE("turtle"), FELSONG("felsong") }

    private const val PREFS = "environments"
    private const val KEY = "selected"

    private val _selected = MutableStateFlow<Environment?>(null)

    /** The game the launcher shows. */
    fun selected(context: Context): StateFlow<Environment?> {
        if (_selected.value == null) _selected.value = read(context)
        return _selected
    }

    fun current(context: Context): Environment = _selected.value ?: read(context).also { _selected.value = it }

    fun select(context: Context, environment: Environment) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, environment.key).apply()
        _selected.value = environment
    }

    private fun read(context: Context): Environment {
        val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return Environment.entries.firstOrNull { it.key == key } ?: Environment.WRATH
    }
}
