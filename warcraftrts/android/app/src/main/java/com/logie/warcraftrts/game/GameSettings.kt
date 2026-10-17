// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraftrts.game

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import com.logie.warcraftrts.R
import com.logie.warcraftrts.RtsFiles
import com.logie.warcraftrts.library.Install
import com.logie.warcraftrts.library.Screen

/** The games' settings chosen in the launcher (Graphics). They apply the next time a game starts. */
object GameSettings {
    /** The games' resolution: the screen's own pixels, or fewer lines for speed. */
    enum class Resolution(val lines: Int?, @StringRes val label: Int, @StringRes val summary: Int) {
        SCREEN(null, R.string.rts_graphics_screen, R.string.rts_graphics_screen_summary),
        HIGH(1080, R.string.rts_graphics_high, R.string.rts_graphics_high_summary),
        BALANCED(720, R.string.rts_graphics_balanced, R.string.rts_graphics_balanced_summary),
    }

    /** How Warcraft III draws: Direct3D (its default) or OpenGL (`-graphicsapi OpenGL2`). */
    enum class Renderer(val argument: String, @StringRes val label: Int, @StringRes val summary: Int) {
        DIRECT3D("", R.string.rts_renderer_d3d, R.string.rts_renderer_d3d_summary),
        OPENGL("-graphicsapi OpenGL2", R.string.rts_renderer_gl, R.string.rts_renderer_gl_summary),
    }

    private const val PREFS = "game"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun resolution(context: Context): Resolution =
        Resolution.entries.firstOrNull { it.name == prefs(context).getString("resolution", null) } ?: Resolution.SCREEN

    fun setResolution(context: Context, resolution: Resolution) {
        prefs(context).edit().putString("resolution", resolution.name).apply()
    }

    fun renderer(context: Context): Renderer =
        Renderer.entries.firstOrNull { it.name == prefs(context).getString("renderer", null) } ?: Renderer.DIRECT3D

    fun setRenderer(context: Context, renderer: Renderer) {
        prefs(context).edit().putString("renderer", renderer.name).apply()
    }

    /** Vibrate when a hold is recognised. */
    fun vibration(context: Context): Boolean = prefs(context).getBoolean("vibration", true)

    fun setVibration(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("vibration", on).apply()
    }

    /** What a game's program is started with: the user's (Game files), and Warcraft III's renderer. */
    fun arguments(context: Context, install: Install): String {
        val own = RtsFiles.arguments(context, install)
        val renderer = if (install == Install.WC3) renderer(context).argument else ""
        return listOf(own, renderer).filter { it.isNotBlank() }.joinToString(" ")
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

    fun gameSize(context: Context, resolution: Resolution = resolution(context)): Pair<Int, Int> {
        val (width, height) = screenSize(context)
        return Screen.resolutionFor(width, height, resolution.lines)
    }
}
