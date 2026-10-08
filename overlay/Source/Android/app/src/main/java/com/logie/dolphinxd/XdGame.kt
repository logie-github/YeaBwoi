// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.content.Context
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import org.dolphinemu.dolphinemu.R

/**
 * What differs between the games this code is built for (Dolphin XD, Dolphin Colosseum, ...).
 * The values come from the game's resources in games/<game>/.
 */
object XdGame {
    /** First three characters of the game's IDs in every region, e.g. "GXX" for GXXE01. */
    fun idPrefix(context: Context): String = context.getString(R.string.xd_game_id_prefix)

    fun isThisGame(context: Context, gameId: String): Boolean = gameId.startsWith(idPrefix(context))

    fun appName(context: Context): String = context.getString(R.string.xd_app_name)

    /** Edge-to-edge with status bar icons that match the game's light or dark theme. */
    fun enableEdgeToEdge(activity: ComponentActivity) {
        val style = if (activity.resources.getBoolean(R.bool.xd_light_theme)) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
