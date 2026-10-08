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

    private companion object {
        const val KEY_GESTURES = "gestures_enabled"
        const val KEY_HAPTICS = "haptics"
    }
}
