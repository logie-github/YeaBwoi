// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.v3gold

import android.content.Context

/** V3 Gold's own settings (Vita3K's are in its config.yml). */
class GoldConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("v3gold", Context.MODE_PRIVATE)

    /** Gesture controls instead of Vita3K's on-screen buttons. */
    var gesturesEnabled: Boolean
        get() = prefs.getBoolean(KEY_GESTURES, true)
        set(value) = prefs.edit().putBoolean(KEY_GESTURES, value).apply()

    /** A short vibration when a hold gesture is recognized. */
    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    /** How far a short swipe tilts the left stick, for walking (longer swipes run). */
    var walkTilt: Float
        get() = prefs.getFloat(KEY_WALK_TILT, DEFAULT_WALK_TILT)
        set(value) = prefs.edit().putFloat(KEY_WALK_TILT, value).apply()

    /** How long a swipe (in dp) has to be to push the left stick all the way, to run. */
    var runDistanceDp: Float
        get() = prefs.getFloat(KEY_RUN_DISTANCE, DEFAULT_RUN_DISTANCE_DP)
        set(value) = prefs.edit().putFloat(KEY_RUN_DISTANCE, value).apply()

    /** Swipes also hold the D-pad, for menus that ignore the stick. */
    var swipeDpad: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_DPAD, false)
        set(value) = prefs.edit().putBoolean(KEY_SWIPE_DPAD, value).apply()

    /** How the game's picture fills the screen: one of the SCREEN_SHAPE_ values. */
    var screenShape: Int
        get() = prefs.getInt(KEY_SCREEN_SHAPE, SCREEN_SHAPE_ORIGINAL)
        set(value) = prefs.edit().putInt(KEY_SCREEN_SHAPE, value).apply()

    /** Experiment: skip the game's motion blur (ghosting) pass. */
    var blurFix: Boolean
        get() = prefs.getBoolean(KEY_BLUR_FIX, true)
        set(value) = prefs.edit().putBoolean(KEY_BLUR_FIX, value).apply()

    /** Experiment: 60 FPS through Vita3K's FPS hack. */
    var sixtyFps: Boolean
        get() = prefs.getBoolean(KEY_SIXTY_FPS, false)
        set(value) = prefs.edit().putBoolean(KEY_SIXTY_FPS, value).apply()

    /**
     * Which of the game's 1/30 s values to change to 1/60 s at 60 FPS, so it keeps its normal speed:
     * [GoldLab.SPEED_FIX_OFF], [GoldLab.SPEED_FIX_ALL] or the number of one value (1, 2, ...).
     */
    var speedFix: Int
        get() = prefs.getInt(KEY_SPEED_FIX, GoldLab.SPEED_FIX_ALL)
        set(value) = prefs.edit().putInt(KEY_SPEED_FIX, value).apply()

    /** Also change 1/30 values in the game's code, through a Vita3K patch file (next start). */
    var speedFixCode: Boolean
        get() = prefs.getBoolean(KEY_SPEED_FIX_CODE, false)
        set(value) = prefs.edit().putBoolean(KEY_SPEED_FIX_CODE, value).apply()

    companion object {
        const val DEFAULT_WALK_TILT = 0.4f
        const val DEFAULT_RUN_DISTANCE_DP = 48f
        /** Swipes up to this fraction of the run distance walk. */
        const val WALK_FRACTION = 0.6f

        /** The game's own 16:9 picture, as big as fits. */
        const val SCREEN_SHAPE_ORIGINAL = 0
        /** 4:3, the middle of the picture (the sides are cut off). */
        const val SCREEN_SHAPE_CROP_4_3 = 1
        /** 4:3, the whole picture squeezed in. */
        const val SCREEN_SHAPE_SQUEEZE_4_3 = 2
        /** The whole screen, the whole picture stretched over it. */
        const val SCREEN_SHAPE_FILL = 3

        private const val KEY_GESTURES = "gestures_enabled"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_WALK_TILT = "walk_tilt"
        private const val KEY_RUN_DISTANCE = "run_distance_dp"
        private const val KEY_SWIPE_DPAD = "swipe_dpad"
        private const val KEY_SCREEN_SHAPE = "screen_shape"
        private const val KEY_BLUR_FIX = "blur_fix"
        private const val KEY_SIXTY_FPS = "sixty_fps"
        private const val KEY_SPEED_FIX = "speed_fix"
        private const val KEY_SPEED_FIX_CODE = "speed_fix_code"
    }
}
