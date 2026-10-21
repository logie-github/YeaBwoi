// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.tww

import android.content.Context
import com.logie.warcraft.turtle.core.SafeFs
import com.logie.warcraft.tww.core.TwwInstall
import java.io.File

/** Managed storage for Draconic. No path here overlaps another expansion's installation. */
object TwwFiles {
    data class Layout(val root: File) {
        val client = File(root, "client")
        val server = File(root, "server")
        val maps = File(root, "maps")
        val downloads = File(root, "downloads")
        val staging = File(root, "staging")
        val logs = File(root, "logs")
        val receipts = File(root, "receipts")
    }

    private const val PREFS = "tww-installation"
    private const val EXTERNAL = "external-storage"

    fun storageExternal(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(EXTERNAL, false)

    fun layout(context: Context): Layout {
        val parent = if (storageExternal(context)) {
            context.getExternalFilesDir(null) ?: throw IllegalStateException("The selected external installation directory is unavailable. Reconnect its storage before continuing.")
        } else context.filesDir
        return Layout(File(parent, "tww"))
    }

    fun fs(context: Context): SafeFs = SafeFs(layout(context).root)

    fun report(context: Context): TwwInstall.Report {
        val layout = layout(context)
        return TwwInstall.inspect(layout.client, layout.server, layout.maps)
    }

    /** Choose before installing. Existing installations are never silently moved or hidden. */
    @Synchronized
    fun setExternal(context: Context, external: Boolean): Boolean {
        if (external == storageExternal(context)) return true
        if (TwwJobs.busy || TwwServer.isBusy) return false
        val current = layout(context)
        if (listOf(current.client, current.server, current.maps).any { it.list()?.isNotEmpty() == true }) return false
        if (external && context.getExternalFilesDir(null) == null) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(EXTERNAL, external).commit()
    }
}
