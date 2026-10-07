// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.dolphinemu.dolphinemu.features.settings.model.BooleanSetting
import org.dolphinemu.dolphinemu.features.settings.model.IntSetting
import org.dolphinemu.dolphinemu.features.settings.model.NativeConfig
import org.dolphinemu.dolphinemu.utils.ThemeHelper

/**
 * Dolphin settings that make the app behave like a standalone game. Applied once per
 * [VERSION], so anything the player changes later in Dolphin's settings sticks.
 */
object XdDefaults {
    private const val VERSION = 1

    /** SerialInterface::SIDEVICE_GC_CONTROLLER, the emulated "Standard Controller". */
    private const val SI_STANDARD_CONTROLLER = 6

    /** Must run after directory initialization. */
    fun applyIfNeeded(context: Context) {
        val config = XdConfig(context)
        if (config.defaultsVersion >= VERSION) {
            return
        }

        val layer = NativeConfig.LAYER_BASE

        // No first-run analytics prompt.
        BooleanSetting.MAIN_ANALYTICS_PERMISSION_ASKED.setBoolean(layer, true)
        BooleanSetting.MAIN_ANALYTICS_ENABLED.setBoolean(layer, false)

        // Gestures replace the on-screen controller, which needs port 1 to be an emulated pad.
        BooleanSetting.MAIN_SHOW_INPUT_OVERLAY.setBoolean(layer, false)
        IntSetting.MAIN_SI_DEVICE_0.setInt(layer, SI_STANDARD_CONTROLLER)

        // Look like a game rather than an emulator.
        BooleanSetting.MAIN_OSD_MESSAGES.setBoolean(layer, false)
        IntSetting.MAIN_EMULATION_ORIENTATION.setInt(
            layer,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        )
        IntSetting.MAIN_INTERFACE_THEME_MODE.setInt(layer, AppCompatDelegate.MODE_NIGHT_YES)
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext).edit {
            putInt(ThemeHelper.CURRENT_THEME_MODE, AppCompatDelegate.MODE_NIGHT_YES)
        }

        NativeConfig.save(layer)
        config.defaultsVersion = VERSION
    }
}
