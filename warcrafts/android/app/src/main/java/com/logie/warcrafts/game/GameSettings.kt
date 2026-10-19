// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.game

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import com.logie.warcrafts.R
import com.logie.warcrafts.RtsFiles
import com.logie.warcrafts.library.Install
import com.logie.warcrafts.library.Screen
import com.logie.warcrafts.library.Wc3Arguments

/** The games' settings chosen in the launcher (Graphics). They apply the next time a game starts. */
object GameSettings {
    /**
     * The games' resolution: the screen's own pixels, or fewer lines for speed. 1080 lines unless
     * changed: the Fold's outside screen's own, and on its inside (1968 lines) a quarter of the
     * pixels to draw, which Box64 and the GPU feel more than the eye does at that size.
     */
    enum class Resolution(val lines: Int?, @StringRes val label: Int, @StringRes val summary: Int) {
        SCREEN(null, R.string.rts_graphics_screen, R.string.rts_graphics_screen_summary),
        HIGH(1080, R.string.rts_graphics_high, R.string.rts_graphics_high_summary),
        BALANCED(720, R.string.rts_graphics_balanced, R.string.rts_graphics_balanced_summary),
    }

    /** How Warcraft III draws: Direct3D (its default) or OpenGL ([Wc3Arguments.OPENGL]). */
    enum class Renderer(@StringRes val label: Int, @StringRes val summary: Int) {
        DIRECT3D(R.string.rts_renderer_d3d, R.string.rts_renderer_d3d_summary),
        OPENGL(R.string.rts_renderer_gl, R.string.rts_renderer_gl_summary),
    }

    private const val PREFS = "game"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun resolution(context: Context): Resolution =
        Resolution.entries.firstOrNull { it.name == prefs(context).getString("resolution", null) } ?: Resolution.HIGH

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

    /**
     * What a game's program is started with: the user's (Game files), and for Warcraft III,
     * borderless and its renderer ([Wc3Arguments]).
     */
    fun arguments(context: Context, install: Install): String {
        val own = RtsFiles.arguments(context, install)
        return if (install == Install.WC3) Wc3Arguments.of(own, renderer(context) == Renderer.OPENGL) else own
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
