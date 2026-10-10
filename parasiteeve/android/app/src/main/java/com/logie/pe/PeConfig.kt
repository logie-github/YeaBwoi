// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe

import android.content.Context

/** The app's settings. */
class PeConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("parasiteeve", Context.MODE_PRIVATE)

    /** A short vibration when a hold gesture is recognized. */
    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    /** How the picture is drawn: one of the PICTURE_ values. */
    var picture: Int
        get() = prefs.getInt(KEY_PICTURE, PICTURE_SHARP)
        set(value) = prefs.edit().putInt(KEY_PICTURE, value).apply()

    /**
     * How many times the PlayStation's resolution the core draws at: one of [RESOLUTIONS]. A scale
     * the core no longer offers (3x, 5x, 6x before Beetle) reads as the next one down.
     */
    var resolution: Int
        get() {
            val stored = prefs.getInt(KEY_RESOLUTION, DEFAULT_RESOLUTION)
            return RESOLUTIONS.lastOrNull { it <= stored } ?: DEFAULT_RESOLUTION
        }
        set(value) = prefs.edit().putInt(KEY_RESOLUTION, value).apply()

    /** The HD texture pack is drawn in place of the game's own textures (once one is installed). */
    var hdTextures: Boolean
        get() = prefs.getBoolean(KEY_HD_TEXTURES, true)
        set(value) = prefs.edit().putBoolean(KEY_HD_TEXTURES, value).apply()

    /** PGXP: 3D drawn from precise coordinates, so it doesn't wobble and textures don't warp. */
    var steady3d: Boolean
        get() = prefs.getBoolean(KEY_STEADY_3D, true)
        set(value) = prefs.edit().putBoolean(KEY_STEADY_3D, value).apply()

    /** How many frames the game runs per screen refresh: 1 (normal), 2 or 3 (fast forward). */
    var speed: Int
        get() = prefs.getInt(KEY_SPEED, 1).coerceIn(1, MAX_SPEED)
        set(value) = prefs.edit().putInt(KEY_SPEED, value.coerceIn(1, MAX_SPEED)).apply()

    companion object {
        const val PICTURE_SHARP = 0
        const val PICTURE_SMOOTH = 1
        const val PICTURE_CRT = 2
        const val PICTURE_UPSCALED = 3

        const val MAX_SPEED = 3

        val RESOLUTIONS = listOf(1, 2, 4, 8)
        const val DEFAULT_RESOLUTION = 4

        private const val KEY_HAPTICS = "haptics"
        private const val KEY_PICTURE = "picture"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_STEADY_3D = "steady_3d"
        private const val KEY_HD_TEXTURES = "hd_textures"
        private const val KEY_SPEED = "speed"
    }
}
