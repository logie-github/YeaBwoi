// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.content.Context
import org.dolphinemu.dolphinemu.NativeLibrary

/**
 * Pinch zoom for the 3D camera (Free Look's field of view, see NativeLibrary.SetCameraZoom). Only
 * zooms in from the game's own view: the games only draw what their camera sees, so zooming out
 * past it would show missing scenery at the edges. Remembered between sessions.
 */
object XdCameraZoom {
    const val MIN = 1.0f
    const val MAX = 2.0f

    /** Multiplies the zoom by [scale]. Main thread, while a game runs. */
    fun zoomBy(context: Context, scale: Float) {
        val config = XdConfig(context)
        val zoom = (config.cameraZoom * scale).coerceIn(MIN, MAX)
        if (zoom != config.cameraZoom) {
            config.cameraZoom = zoom
            NativeLibrary.SetCameraZoom(zoom)
        }
    }

    /** Sets the zoom (clamped to [MIN]..[MAX]). Main thread, while a game runs. */
    fun set(context: Context, zoom: Float) {
        val clamped = zoom.coerceIn(MIN, MAX)
        XdConfig(context).cameraZoom = clamped
        NativeLibrary.SetCameraZoom(clamped)
    }

    fun get(context: Context): Float = XdConfig(context).cameraZoom

    fun reset(context: Context) = set(context, MIN)

    /** Puts the remembered zoom back, e.g. when a game starts. */
    fun apply(context: Context) = NativeLibrary.SetCameraZoom(XdConfig(context).cameraZoom)
}
