// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.game

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import com.logie.warcrafts.R
import com.logie.warcrafts.library.Install
import com.logie.warcrafts.library.Screen

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

    /**
     * How Warcraft III draws. OpenGL (`-opengl`) is the default: the one seen drawing the game on the
     * phone (build 25, its main menu; build 27's Direct3D through DXVK gave a black screen). Direct3D
     * through Wine (wined3d, which draws with OpenGL itself) and through DXVK (Vulkan) can be chosen.
     */
    enum class Renderer(@StringRes val label: Int, @StringRes val summary: Int) {
        OPENGL(R.string.rts_renderer_gl, R.string.rts_renderer_gl_summary),
        WINED3D(R.string.rts_renderer_wined3d, R.string.rts_renderer_wined3d_summary),
        DIRECT3D(R.string.rts_renderer_d3d, R.string.rts_renderer_d3d_summary),
    }

    private const val PREFS = "game"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun resolution(context: Context): Resolution =
        Resolution.entries.firstOrNull { it.name == prefs(context).getString("resolution", null) } ?: Resolution.HIGH

    fun setResolution(context: Context, resolution: Resolution) {
        prefs(context).edit().putString("resolution", resolution.name).apply()
    }

    fun renderer(context: Context): Renderer =
        Renderer.entries.firstOrNull { it.name == prefs(context).getString("renderer", null) } ?: Renderer.OPENGL

    fun setRenderer(context: Context, renderer: Renderer) {
        prefs(context).edit().putString("renderer", renderer.name).apply()
    }

    /** Vibrate when a hold is recognised. */
    fun vibration(context: Context): Boolean = prefs(context).getBoolean("vibration", true)

    fun setVibration(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("vibration", on).apply()
    }

    /** What Warcraft III's program is started with: `-opengl` for OpenGL, nothing for either Direct3D. */
    fun arguments(context: Context, install: Install): String =
        if (install == Install.WC3 && renderer(context) == Renderer.OPENGL) "-opengl" else ""

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
