// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp

import android.content.Context
import java.util.Locale

/** Uses the existing app's private-login convention, isolated from every other realm. */
object SppLogin {
    data class Login(val name: String, val password: String)
    @Volatile private var temporary: Login? = null
    private fun prefs(context: Context) = context.getSharedPreferences("spp-login", Context.MODE_PRIVATE)
    fun selectedName(context: Context): String? = prefs(context).getString("name", null)
    fun get(context: Context): Login? = temporary ?: prefs(context).let { preferences ->
        val name = preferences.getString("name", null) ?: return@let null
        val password = preferences.getString("password", null) ?: return@let null
        Login(name, password)
    }
    fun save(context: Context, name: String, password: String, remember: Boolean = true) {
        val normalized = name.uppercase(Locale.ROOT)
        temporary = Login(normalized, password)
        prefs(context).edit().putString("name", normalized).apply {
            if (remember) putString("password", password) else remove("password")
        }.apply()
    }
    fun forget(context: Context) { temporary = null; prefs(context).edit().clear().apply() }
}
