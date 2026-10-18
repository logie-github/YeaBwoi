// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

/**
 * What Warcraft III is started with, beside the user's own arguments (Game files): fullscreen at
 * the screen's size, and OpenGL when chosen in Graphics. Each in 1.30's words and in the ones patch
 * 1.31 replaced them with (the game passes over the ones it doesn't know). Plain Kotlin,
 * unit-tested.
 */
object Wc3Arguments {
    /**
     * Fullscreen: 1.30's `-nativefullscr`, 1.31's `-windowmode fullscreen`. (Windowed fullscreen,
     * if wanted instead, is `-fullscreen` in 1.30, `-windowmode windowedfullscreen` in 1.31: typed
     * in Game files, it takes this one's place.)
     */
    const val FULLSCREEN = "-windowmode fullscreen -nativefullscr"

    /** OpenGL in place of Direct3D: 1.30's `-opengl`, 1.31's `-graphicsapi OpenGL`. */
    const val OPENGL = "-opengl -graphicsapi OpenGL"

    private val WINDOW = Regex("(?i)(^|\\s)-(window|windowmode|fullscreen|nativefullscr)(\\s|$)")
    private val RENDERER = Regex("(?i)(^|\\s)-(opengl|d3d|graphicsapi)(\\s|$)")

    /** [own] with the window mode and renderer added, unless [own] already sets them. */
    fun of(own: String, openGl: Boolean): String {
        val parts = mutableListOf(own.trim())
        if (!WINDOW.containsMatchIn(own)) parts += FULLSCREEN
        if (openGl && !RENDERER.containsMatchIn(own)) parts += OPENGL
        return parts.filter { it.isNotEmpty() }.joinToString(" ")
    }
}
