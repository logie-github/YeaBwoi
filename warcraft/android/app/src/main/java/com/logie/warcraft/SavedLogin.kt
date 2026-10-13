// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context

/**
 * The account the game logs in with by itself, and its password: kept in the app's own storage on
 * the phone (nowhere else: not in the app, not on GitHub), saved from Accounts or the game's
 * password prompt once the realm has checked it. The game types it in at the login screen.
 */
object SavedLogin {
    data class Login(val name: String, val password: String)

    private const val PREFS = "login"
    private const val KEY_NAME = "name"
    private const val KEY_PASSWORD = "password"

    fun get(context: Context): Login? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        return Login(name, password)
    }

    fun save(context: Context, name: String, password: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NAME, name.uppercase()).putString(KEY_PASSWORD, password).apply()
    }

    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** The saved login is [name]'s. */
    fun isFor(context: Context, name: String) = get(context)?.name.equals(name, ignoreCase = true)
}
