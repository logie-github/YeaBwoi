// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp

import android.content.Context
import com.logie.warcraft.local.LocalInstallation
import com.logie.warcraft.spp.core.SppInstall
import com.logie.warcraft.turtle.core.SafeFs
import java.io.File

object SppFiles {
    private const val PREFS = "spp-installation"
    fun external(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("external", false)
    fun layout(context: Context): LocalInstallation {
        val parent = if (external(context)) context.getExternalFilesDir(null)
            ?: error("The selected SPP storage is unavailable. Reconnect it before continuing.") else context.filesDir
        return LocalInstallation(File(parent, "spp-legion"))
    }
    fun fs(context: Context) = SafeFs(layout(context).root)
    fun report(context: Context) = layout(context).let { layout ->
        val report = SppInstall.inspect(layout.client, layout.server, layout.maps)
        val local = report.localConnectionConfigured && SppGame.localConfigured(context)
        report.copy(localConnectionConfigured = local,
            blockers = if (local) report.blockers else (report.blockers + "Finish SPP local-client setup before starting the game.").distinct())
    }
    @Synchronized fun setExternal(context: Context, value: Boolean): Boolean {
        if (value == external(context)) return true
        if (SppJobs.busy || SppServer.isBusy) return false
        val layout = layout(context)
        if (listOf(layout.client, layout.server, layout.maps).any { it.list()?.isNotEmpty() == true }) return false
        if (value && context.getExternalFilesDir(null) == null) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("external", value).commit()
    }
}
