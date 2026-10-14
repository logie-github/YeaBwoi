// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.logie.warcraft.WowFiles
import com.logie.warcraft.turtle.core.ClientManifest
import com.logie.warcraft.turtle.core.DbcManifest
import com.logie.warcraft.turtle.core.InstallState
import com.logie.warcraft.turtle.core.SafeFs
import com.logie.warcraft.turtle.core.TurtleLayout
import java.io.File

/**
 * Turtle WoW's storage on the phone: [TurtleLayout] under the app's files folder, every change to
 * it through [fs] (which refuses anything outside `files/turtle` or in the other games' folders),
 * and its installation manifest ([state]).
 */
object TurtleFiles {
    fun layout(context: Context) = TurtleLayout(context.filesDir, context.dataDir)

    /** Turtle's folder, guarded: nothing outside it, nothing of the other games. */
    fun fs(context: Context): SafeFs {
        val layout = layout(context)
        return SafeFs(layout.root, layout.protectedFolders)
    }

    /** The folder the server's programs see Turtle's at (as they were built for it). */
    fun serverRoot(context: Context): String =
        if (WowFiles.isStandardPath(context)) TurtleLayout.STANDARD_ROOT else layout(context).root.path

    fun state(context: Context): InstallState =
        InstallState.parse(runCatching { layout(context).stateFile.readText() }.getOrNull())

    @Synchronized
    fun updateState(context: Context, change: (InstallState) -> InstallState): InstallState {
        val layout = layout(context)
        val next = change(state(context)).copy(updatedAt = System.currentTimeMillis())
        val fs = fs(context)
        fs.mkdirs(layout.root)
        fs.writeAtomically(layout.stateFile, next.toJson())
        return next
    }

    /** The client manifest and DBC checksums in the APK (scripts/turtle-manifests.py). */
    fun clientManifest(context: Context): ClientManifest =
        context.assets.open("turtle/client-manifest.tsv").use { ClientManifest.parse(it.reader().readText()) }

    fun dbcManifest(context: Context): DbcManifest =
        context.assets.open("turtle/dbc-sha256.tsv").use { DbcManifest.parse(it.reader().readText()) }

    fun hasClient(context: Context): Boolean {
        val client = layout(context).client
        return ClientManifest.find(client, ClientManifest.EXE)?.isFile == true && ClientManifest.find(client, "Data")?.isDirectory == true
    }

    /** The server's data folders there, with files in them. */
    fun dataParts(context: Context): Set<String> {
        val data = layout(context).data
        return listOf("dbc", "maps", "vmaps", "mmaps").filter { File(data, it).list()?.isNotEmpty() == true }.toSet()
    }

    /** The world server has what it can't start without. */
    fun hasData(context: Context): Boolean = dataParts(context).containsAll(listOf("dbc", "maps"))

    fun hasDatabases(context: Context): Boolean = File(layout(context).databases, "mysql").isDirectory

    fun freeSpace(context: Context): Long = StatFs(context.filesDir.path).availableBytes

    /** Turtle's folder, all of it, in bytes. */
    fun used(context: Context): Long = WowFiles.sizeOf(layout(context).root)

    /**
     * Why Turtle WoW can't run on this phone at all, or null: the server's programs are built for
     * arm64 and for the app's folder of the phone's main user.
     */
    fun blocker(context: Context): String? = when {
        Build.SUPPORTED_ABIS.none { it == "arm64-v8a" } -> "arm64"
        !WowFiles.isStandardPath(context) -> "path"
        else -> null
    }
}
