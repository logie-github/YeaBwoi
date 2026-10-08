// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.features.settings.model.BooleanSetting
import org.dolphinemu.dolphinemu.features.settings.model.IntSetting
import org.dolphinemu.dolphinemu.features.settings.model.NativeConfig
import org.dolphinemu.dolphinemu.utils.ThemeHelper

/**
 * Dolphin settings that make the app behave like a standalone game. Each step is applied once, so
 * anything the player changes later in Dolphin's settings sticks, and a later step never resets the
 * settings an earlier one made.
 */
object XdDefaults {
    private const val VERSION = 2

    /** SerialInterface::SIDEVICE_GC_CONTROLLER, the emulated "Standard Controller". */
    private const val SI_STANDARD_CONTROLLER = 6

    /** AspectMode::ForceStandard, "Force 4:3". */
    private const val ASPECT_FORCE_STANDARD = 2

    /** Must run after directory initialization. */
    fun applyIfNeeded(context: Context) {
        val config = XdConfig(context)
        val applied = config.defaultsVersion
        if (applied >= VERSION) {
            return
        }

        val layer = NativeConfig.LAYER_BASE
        if (applied < 1) {
            applyFirstRun(context, layer)
        }
        if (applied < 2) {
            // Always 4:3, the games' own shape. "Auto" keeps switching between 4:3 and a letterboxed
            // 16:9 picture as Dolphin guesses the aspect ratio scene by scene.
            IntSetting.GFX_ASPECT_RATIO.setInt(layer, ASPECT_FORCE_STANDARD)
        }

        NativeConfig.save(layer)
        config.defaultsVersion = VERSION
    }

    private fun applyFirstRun(context: Context, layer: Int) {
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
        // Light or dark to match the game's theme (MODE_NIGHT_YES for XD, MODE_NIGHT_NO for
        // Colosseum's desert colors).
        val themeMode = context.resources.getInteger(R.integer.xd_theme_mode)
        IntSetting.MAIN_INTERFACE_THEME_MODE.setInt(layer, themeMode)
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext).edit {
            putInt(ThemeHelper.CURRENT_THEME_MODE, themeMode)
        }
    }
}
