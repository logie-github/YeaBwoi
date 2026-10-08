// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.app.Activity
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.dolphinemu.dolphinemu.R

/** Small bits of Dolphin XD UI shown during emulation. */
object XdUi {
    private const val MENU_HINT_LAUNCHES = 3

    fun showControls(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.xd_controls_title)
            .setMessage(R.string.xd_controls_body)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /**
     * 60 FPS or the original 30. From the in-game menu ([inGame]) the change applies the next time
     * the game starts, and a stuck cutscene (a known 60 FPS issue) can be fixed from here.
     */
    fun showFrameRate(activity: Activity, inGame: Boolean) {
        val config = XdConfig(activity)
        val choices = arrayOf(
            activity.getString(R.string.xd_frame_rate_60),
            activity.getString(R.string.xd_frame_rate_30)
        )
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.xd_frame_rate_title)
            .setSingleChoiceItems(choices, if (config.sixtyFps) 0 else 1) { _, which ->
                val sixty = which == 0
                if (sixty != config.sixtyFps) {
                    config.sixtyFps = sixty
                    if (inGame) {
                        Toast.makeText(activity, R.string.xd_frame_rate_next_start, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setPositiveButton(R.string.ok, null)
        if (inGame && XdFrameRate.activeThisRun) {
            builder.setNeutralButton(R.string.xd_frame_rate_unstick) { _, _ ->
                XdFrameRate.unstickCutscene()
                Toast.makeText(activity, R.string.xd_frame_rate_unsticking, Toast.LENGTH_LONG).show()
            }
        }
        builder.show()
    }

    /** Reminds new players how to reach the menu, since there is no on-screen button for it. */
    fun showMenuHintIfNeeded(activity: Activity) {
        val config = XdConfig(activity)
        if (config.controlHintsShown < MENU_HINT_LAUNCHES) {
            Toast.makeText(activity, R.string.xd_menu_hint, Toast.LENGTH_LONG).show()
            config.controlHintsShown = config.controlHintsShown + 1
        }
    }
}
