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

    private companion object {
        const val KEY_GESTURES = "gestures_enabled"
        const val KEY_HAPTICS = "haptics"
        const val KEY_BLUR_FIX = "blur_fix"
        const val KEY_SIXTY_FPS = "sixty_fps"
        const val KEY_SPEED_FIX = "speed_fix"
        const val KEY_SPEED_FIX_CODE = "speed_fix_code"
    }
}
