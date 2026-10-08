// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import org.dolphinemu.dolphinemu.features.settings.model.NativeConfig
import org.dolphinemu.dolphinemu.features.settings.model.Settings

/**
 * Fast-forward for the gesture controls. Runs the game at [SPEED] while keeping audio at normal
 * speed and pitch (Core/AudioRealTime, see the mixer patch in patches/).
 *
 * Both values go in the CurrentRun config layer, which is never saved, so the player's own
 * emulation speed setting comes back when fast-forward ends and a crash can't leave it on.
 */
object XdFastForward {
    const val SPEED = 2.0f

    private const val KEY_SPEED = "EmulationSpeed"
    private const val KEY_AUDIO_REAL_TIME = "AudioRealTime"

    fun set(enabled: Boolean) {
        val layer = NativeConfig.LAYER_CURRENT
        val file = Settings.FILE_DOLPHIN
        val section = Settings.SECTION_INI_CORE
        if (enabled) {
            // Audio first, so the mixer never plays a moment of sped-up sound.
            NativeConfig.setBoolean(layer, file, section, KEY_AUDIO_REAL_TIME, true)
            NativeConfig.setFloat(layer, file, section, KEY_SPEED, SPEED)
        } else {
            NativeConfig.deleteKey(layer, file, section, KEY_SPEED)
            NativeConfig.deleteKey(layer, file, section, KEY_AUDIO_REAL_TIME)
        }
    }
}
