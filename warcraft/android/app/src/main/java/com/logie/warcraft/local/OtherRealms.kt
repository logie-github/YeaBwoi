// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.local

import android.content.Context

/** What a local realm's start checks of Android (it runs apart from the phone's other realms). */
object OtherRealms {
    /**
     * Android restricts the app in the background (its battery setting "Restricted"): it ends the
     * realm's foreground service as soon as the app leaves the screen, and the realm with it.
     */
    fun backgroundRestricted(context: Context): Boolean =
        runCatching { context.getSystemService(android.app.ActivityManager::class.java)?.isBackgroundRestricted == true }.getOrDefault(false)

    /** The app's settings page (its Battery setting is there). */
    fun openAppSettings(context: Context) {
        runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:" + context.packageName))) }
    }

}
