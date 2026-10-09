// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.v3gold

import android.content.Context
import android.util.Log
import org.vita3k.emulator.NativeLib

/**
 * V3 Gold's choices for Vita3K's settings, applied once each (also to existing installs) in
 * numbered steps, so a new step reaches players without redoing the old ones or undoing their own
 * changes after it. Call once Vita3K is initialized, before its settings screen loads them.
 */
object GoldDefaults {
    private const val VERSION = 1
    private const val KEY_VERSION = "defaults_version"

    fun apply(context: Context) {
        val prefs = context.getSharedPreferences("v3gold", Context.MODE_PRIVATE)
        val done = prefs.getInt(KEY_VERSION, 0)
        if (done >= VERSION) return
        runCatching {
            val config = NativeLib.getGlobalConfig()
            if (done < 1) {
                // Step 1: Cubeb plays each of the game's audio channels through its own low-latency
                // stream. SDL lets up to 4 of the phone's audio buffers pile up first, which made
                // voices and music lag behind the game.
                config.audioBackend = "Cubeb"
            }
            NativeLib.saveSettings(null, config)
        }.onSuccess {
            prefs.edit().putInt(KEY_VERSION, VERSION).apply()
        }.onFailure {
            Log.w("V3Gold", "Couldn't apply V3 Gold's default settings", it)
        }
    }
}
