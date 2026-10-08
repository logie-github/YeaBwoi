// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
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

    /** Camera zoom from the in-game menu: the same zoom as pinching, as a slider. */
    fun showCameraZoom(activity: Activity) {
        val density = activity.resources.displayMetrics.density
        val label = TextView(activity).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        }
        val slider = Slider(activity).apply {
            valueFrom = XdCameraZoom.MIN
            valueTo = XdCameraZoom.MAX
            stepSize = ZOOM_STEP
            value = snapZoom(XdCameraZoom.get(activity))
            setLabelFormatter { zoomText(activity, it) }
        }
        fun show(zoom: Float) {
            label.text = activity.getString(R.string.xd_zoom_value, zoomText(activity, zoom))
        }
        show(slider.value)
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) XdCameraZoom.set(activity, value)
            show(value)
        }
        val padding = (24 * density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(label)
            addView(slider)
            addView(TextView(activity).apply {
                setText(R.string.xd_zoom_hint)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            })
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.xd_zoom_title)
            .setView(content)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.xd_zoom_reset, null)
            .show()
            .apply {
                // Reset without closing the dialog.
                getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                    XdCameraZoom.reset(activity)
                    slider.value = XdCameraZoom.MIN
                }
            }
    }

    private const val ZOOM_STEP = 0.1f

    /** The slider only takes multiples of its step; pinching can leave anything in between. */
    private fun snapZoom(zoom: Float): Float =
        (Math.round((zoom - XdCameraZoom.MIN) / ZOOM_STEP) * ZOOM_STEP + XdCameraZoom.MIN)
            .coerceIn(XdCameraZoom.MIN, XdCameraZoom.MAX)

    private fun zoomText(activity: Activity, zoom: Float) =
        activity.getString(R.string.xd_zoom_factor, zoom)

    /** Reminds new players how to reach the menu, since there is no on-screen button for it. */
    fun showMenuHintIfNeeded(activity: Activity) {
        val config = XdConfig(activity)
        if (config.controlHintsShown < MENU_HINT_LAUNCHES) {
            Toast.makeText(activity, R.string.xd_menu_hint, Toast.LENGTH_LONG).show()
            config.controlHintsShown = config.controlHintsShown + 1
        }
    }
}
