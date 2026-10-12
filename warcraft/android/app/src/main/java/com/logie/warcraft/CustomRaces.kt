// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context

/** The custom races switch: whether the user wants them, and whether they're in. */
object CustomRaces {
    private const val PREFS = "mods"
    private const val KEY_WANTED = "races"
    private const val KEY_INSTALLED = "races_installed"

    fun isWanted(context: Context): Boolean = prefs(context).getBoolean(KEY_WANTED, false)

    fun isInstalled(context: Context): Boolean = prefs(context).getBoolean(KEY_INSTALLED, false)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
