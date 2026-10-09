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

    /** 2D-HD picture: how strong the depth of field is, one of the DEPTH_ values. */
    var hd2dDepth: Int
        get() = prefs.getInt(KEY_HD2D_DEPTH, DEPTH_LIGHT)
        set(value) = prefs.edit().putInt(KEY_HD2D_DEPTH, value).apply()

    /** 2D-HD picture: bright parts glow. */
    var hd2dBloom: Boolean
        get() = prefs.getBoolean(KEY_HD2D_BLOOM, true)
        set(value) = prefs.edit().putBoolean(KEY_HD2D_BLOOM, value).apply()

    /** 2D-HD picture: specks of light drifting over the game. */
    var hd2dMotes: Boolean
        get() = prefs.getBoolean(KEY_HD2D_MOTES, true)
        set(value) = prefs.edit().putBoolean(KEY_HD2D_MOTES, value).apply()

    /** The core draws 3D at twice the PlayStation's resolution. */
    var sharper3d: Boolean
        get() = prefs.getBoolean(KEY_SHARPER_3D, false)
        set(value) = prefs.edit().putBoolean(KEY_SHARPER_3D, value).apply()

    /** How many frames the game runs per screen refresh: 1 (normal), 2 or 3 (fast forward). */
    var speed: Int
        get() = prefs.getInt(KEY_SPEED, 1).coerceIn(1, MAX_SPEED)
        set(value) = prefs.edit().putInt(KEY_SPEED, value.coerceIn(1, MAX_SPEED)).apply()

    /** Starting the game picks up where it was left (the resume state), instead of booting it. */
    var resumeOnStart: Boolean
        get() = prefs.getBoolean(KEY_RESUME, true)
        set(value) = prefs.edit().putBoolean(KEY_RESUME, value).apply()

    companion object {
        const val PICTURE_SHARP = 0
        const val PICTURE_SMOOTH = 1
        const val PICTURE_CRT = 2
        const val PICTURE_UPSCALED = 3
        const val PICTURE_HD2D = 4

        const val DEPTH_OFF = 0
        const val DEPTH_LIGHT = 1
        const val DEPTH_STRONG = 2

        const val MAX_SPEED = 3

        private const val KEY_HAPTICS = "haptics"
        private const val KEY_PICTURE = "picture"
        private const val KEY_SHARPER_3D = "sharper_3d"
        private const val KEY_HD2D_DEPTH = "hd2d_depth"
        private const val KEY_HD2D_BLOOM = "hd2d_bloom"
        private const val KEY_HD2D_MOTES = "hd2d_motes"
        private const val KEY_SPEED = "speed"
        private const val KEY_RESUME = "resume_on_start"
    }
}
