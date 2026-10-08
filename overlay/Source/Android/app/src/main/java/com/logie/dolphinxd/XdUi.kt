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

    /** Reminds new players how to reach the menu, since there is no on-screen button for it. */
    fun showMenuHintIfNeeded(activity: Activity) {
        val config = XdConfig(activity)
        if (config.controlHintsShown < MENU_HINT_LAUNCHES) {
            Toast.makeText(activity, R.string.xd_menu_hint, Toast.LENGTH_LONG).show()
            config.controlHintsShown = config.controlHintsShown + 1
        }
    }
}
