// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong

import android.content.Context
import com.logie.warcraft.felsong.core.FelsongLegacy
import com.logie.warcraft.turtle.core.SafeFs
import com.winlator.container.ContainerManager
import com.winlator.xenvironment.RootFS
import java.io.File

/**
 * The Legion this app had before (Single Player Project's AshamaneCore, a Windows server repack run
 * under Wine) is gone, at the user's request: Legion is LegionCore now, a clean install with the
 * stock client (LEGION.md). What the old one left on the phone is removed once, the first time this
 * build runs: its folder `spp-legion` (in the app's files, or in the external app storage when that
 * was chosen: the repack, its databases and accounts, the client and its server maps, tens of
 * gigabytes), its saved logins and settings, and its Windows (the Winlator container it had). Each
 * deletion goes through [SafeFs] scoped to the one place it is in, with the other games' containers
 * protected. Nothing else is touched, Legion's own folder `felsong` included.
 */
object LegacySpp {
    private const val PREFS = "legacy"
    private const val KEY = "spp-removed"
    const val CONTAINER = "Single Player Project — Legion 7.3.5"
    private val PREFERENCES = listOf("spp-installation", "spp-login", "spp")

    /** Removes the old Legion if this build hasn't yet; where something couldn't go, it tries again next start. */
    fun removeOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) return
        if (remove(context)) prefs.edit().putBoolean(KEY, true).apply()
    }

    /** True when nothing of it is left. */
    fun remove(context: Context): Boolean {
        var clean = FelsongLegacy.removeSppFolders(listOfNotNull(context.filesDir, runCatching { context.getExternalFilesDir(null) }.getOrNull()))
        for (name in PREFERENCES) runCatching { context.deleteSharedPreferences(name) }
        clean = removeWindows(context) && clean
        return clean
    }

    /** The old Legion's Windows (its Winlator container's folder), and no other game's. */
    private fun removeWindows(context: Context): Boolean = runCatching {
        val rootFs = RootFS.find(context)
        if (!rootFs.isValid) return true
        val home = File(rootFs.rootDir, "home")
        val all = ContainerManager(context).containers
        val ours = all.filter { it.name == CONTAINER }
        if (ours.isEmpty()) return true
        val others = all.filter { it.name != CONTAINER }.map { File(home, "${RootFS.USER}-${it.id}") }
        val guard = SafeFs(home, others)
        for (container in ours) {
            val name = "${RootFS.USER}-${container.id}"
            val current = File(home, RootFS.USER).toPath()
            if (java.nio.file.Files.isSymbolicLink(current) && java.nio.file.Files.readSymbolicLink(current).toString().trimEnd('/').endsWith(name)) {
                guard.delete(current.toFile())
            }
            val folder = File(home, name)
            if (folder.exists()) guard.delete(folder)
        }
        true
    }.getOrDefault(false)
}
