// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.os.Handler
import android.os.Looper
import org.dolphinemu.dolphinemu.features.settings.model.NativeConfig
import org.dolphinemu.dolphinemu.features.settings.model.Settings

/**
 * Game speed for the gesture controls, with audio kept at normal speed and pitch
 * (Core/AudioRealTime, see the mixer patch in patches/):
 * - fast-forward at [SPEED] while it's held ([set]),
 * - a short burst at full speed after a tap ([boost]), so text that's still printing fills in. The
 *   games have no way to skip text, and the app can't see when a line is printing.
 *
 * Everything goes in the CurrentRun config layer, which is never saved, so the player's own
 * emulation speed setting comes back afterwards and a crash can't leave it on. Main thread.
 */
object XdFastForward {
    const val SPEED = 2.0f

    /** Dolphin's "no limit": as fast as the phone can run the game. */
    private const val BOOST_SPEED = 0.0f

    private const val KEY_SPEED = "EmulationSpeed"
    private const val KEY_AUDIO_REAL_TIME = "AudioRealTime"

    private val handler = Handler(Looper.getMainLooper())
    private var held = false
    private var boosting = false
    private var applied: Float? = null
    private val endBoost = Runnable {
        boosting = false
        apply()
    }

    /** Fast-forward on or off (one finger held, another swiped). */
    fun set(enabled: Boolean) {
        held = enabled
        apply()
    }

    /** Runs at full speed for [durationMs], or longer if another boost follows. */
    fun boost(durationMs: Long) {
        boosting = true
        handler.removeCallbacks(endBoost)
        handler.postDelayed(endBoost, durationMs)
        apply()
    }

    /** Back to normal speed, e.g. when a game starts. */
    fun reset() {
        held = false
        boosting = false
        handler.removeCallbacks(endBoost)
        applied = null
        write(null)
    }

    private fun apply() {
        val speed = when {
            boosting -> BOOST_SPEED
            held -> SPEED
            else -> null
        }
        if (speed != applied) {
            applied = speed
            write(speed)
        }
    }

    private fun write(speed: Float?) {
        val layer = NativeConfig.LAYER_CURRENT
        val file = Settings.FILE_DOLPHIN
        val section = Settings.SECTION_INI_CORE
        if (speed != null) {
            // Audio first, so the mixer never plays a moment of sped-up sound.
            NativeConfig.setBoolean(layer, file, section, KEY_AUDIO_REAL_TIME, true)
            NativeConfig.setFloat(layer, file, section, KEY_SPEED, speed)
        } else {
            NativeConfig.deleteKey(layer, file, section, KEY_SPEED)
            NativeConfig.deleteKey(layer, file, section, KEY_AUDIO_REAL_TIME)
        }
    }
}
