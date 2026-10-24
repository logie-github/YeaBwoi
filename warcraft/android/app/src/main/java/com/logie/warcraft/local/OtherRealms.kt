// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.local

import android.content.Context
import com.logie.warcraft.Server
import com.logie.warcraft.turtle.TurtleServer

/**
 * The phone's other realms (ChromieCraft's or Wrath Remix's, with their bots; Turtle WoW's) beside a
 * local realm's start: both would share the phone's memory and processors with a Windows realm and
 * its game under Box64, so the launcher asks to stop them first.
 */
object OtherRealms {
    /** The names of the other realms running now. */
    fun running(): List<String> = buildList {
        if (Server.status.value.phase.let { it == Server.Phase.STARTING || it == Server.Phase.RUNNING || it == Server.Phase.STOPPING })
            add("World of Warcraft 3.3.5a's realm")
        if (TurtleServer.isBusy) add("Turtle WoW's realm")
    }

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

    /** Stops them, then [then] (on a background thread). */
    fun stop(context: Context, then: () -> Unit) {
        val wrath = Server.status.value.phase.let { it == Server.Phase.STARTING || it == Server.Phase.RUNNING || it == Server.Phase.STOPPING }
        val turtle = TurtleServer.isBusy
        when {
            wrath && turtle -> Server.stop(context) { TurtleServer.stop(context, then) }
            wrath -> Server.stop(context, then)
            turtle -> TurtleServer.stop(context, then)
            else -> then()
        }
    }
}
