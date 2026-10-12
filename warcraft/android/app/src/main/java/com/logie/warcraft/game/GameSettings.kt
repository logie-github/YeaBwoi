// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.game

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import com.logie.warcraft.R
import com.logie.warcraft.client.ClientConfig

/** The game's settings chosen in the launcher (Graphics). They apply the next time the game starts. */
object GameSettings {
    /** The game's resolution: the screen's own pixels, or fewer lines for speed. */
    enum class Resolution(val lines: Int?, @StringRes val label: Int, @StringRes val summary: Int) {
        SCREEN(null, R.string.wow_graphics_screen, R.string.wow_graphics_screen_summary),
        HIGH(1080, R.string.wow_graphics_high, R.string.wow_graphics_high_summary),
        BALANCED(720, R.string.wow_graphics_balanced, R.string.wow_graphics_balanced_summary),
    }

    private const val PREFS = "game"
    private const val KEY_RESOLUTION = "resolution"

    fun resolution(context: Context): Resolution {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RESOLUTION, null)
        return Resolution.entries.firstOrNull { it.name == name } ?: Resolution.SCREEN
    }

    fun setResolution(context: Context, resolution: Resolution) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RESOLUTION, resolution.name).apply()
    }

    /** The screen the launcher is on (a Fold's inside or outside), in pixels. */
    fun screenSize(context: Context): Pair<Int, Int> =
        if (context is Activity && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = context.windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = context.resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }

    /** The game's width and height for [resolution] on the screen the launcher is on. */
    fun gameSize(context: Context, resolution: Resolution = resolution(context)): Pair<Int, Int> {
        val (width, height) = screenSize(context)
        return ClientConfig.resolutionFor(width, height, resolution.lines)
    }
}
