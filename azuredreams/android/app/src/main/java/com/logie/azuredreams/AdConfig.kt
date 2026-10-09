// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.azuredreams

import android.content.Context

/** The app's settings. */
class AdConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("azuredreams", Context.MODE_PRIVATE)

    /** A short vibration when a hold gesture is recognized. */
    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    /** How the picture is drawn: one of the PICTURE_ values. */
    var picture: Int
        get() = prefs.getInt(KEY_PICTURE, PICTURE_SHARP)
        set(value) = prefs.edit().putInt(KEY_PICTURE, value).apply()

    /** The 2D-HD look, in place of [picture] while it's on (the in-game menu's 2D-HD page). */
    var hd2d: Boolean
        get() = prefs.getBoolean(KEY_HD2D, false)
        set(value) = prefs.edit().putBoolean(KEY_HD2D, value).apply()

    /** 2D-HD: how strong the depth of field is, one of the DEPTH_ values. */
    var hd2dDepth: Int
        get() = prefs.getInt(KEY_HD2D_DEPTH, DEPTH_LIGHT)
        set(value) = prefs.edit().putInt(KEY_HD2D_DEPTH, value).apply()

    /** 2D-HD: bright parts glow. */
    var hd2dBloom: Boolean
        get() = prefs.getBoolean(KEY_HD2D_BLOOM, true)
        set(value) = prefs.edit().putBoolean(KEY_HD2D_BLOOM, value).apply()

    /** 2D-HD: specks of light drifting over the game. */
    var hd2dMotes: Boolean
        get() = prefs.getBoolean(KEY_HD2D_MOTES, true)
        set(value) = prefs.edit().putBoolean(KEY_HD2D_MOTES, value).apply()

    /** How many times the PlayStation's resolution the core draws at: one of [RESOLUTIONS]. */
    var resolution: Int
        get() = prefs.getInt(KEY_RESOLUTION, DEFAULT_RESOLUTION).takeIf { it in RESOLUTIONS } ?: DEFAULT_RESOLUTION
        set(value) = prefs.edit().putInt(KEY_RESOLUTION, value).apply()

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

        const val DEPTH_OFF = 0
        const val DEPTH_LIGHT = 1
        const val DEPTH_STRONG = 2

        const val MAX_SPEED = 3

        val RESOLUTIONS = listOf(1, 2, 3, 4, 5, 6, 8)
        const val DEFAULT_RESOLUTION = 4

        private const val KEY_HAPTICS = "haptics"
        private const val KEY_PICTURE = "picture"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_STEADY_3D = "steady_3d"
        private const val KEY_HD2D = "hd2d"
        private const val KEY_HD2D_DEPTH = "hd2d_depth"
        private const val KEY_HD2D_BLOOM = "hd2d_bloom"
        private const val KEY_HD2D_MOTES = "hd2d_motes"
        private const val KEY_SPEED = "speed"
    }
}
