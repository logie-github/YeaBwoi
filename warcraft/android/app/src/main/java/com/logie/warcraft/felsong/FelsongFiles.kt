// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.logie.warcraft.WowFiles
import com.logie.warcraft.felsong.core.FelsongClient
import com.logie.warcraft.felsong.core.FelsongDataImport
import com.logie.warcraft.felsong.core.FelsongInstallState
import com.logie.warcraft.felsong.core.FelsongLayout
import com.logie.warcraft.turtle.core.SafeFs
import java.io.File

/**
 * Felsong's storage on the phone: [FelsongLayout] under the app's files folder, every change to it
 * through [fs] (the folder guard, which refuses anything outside `files/felsong` or in the other
 * games' folders), and its installation manifest ([state]).
 */
object FelsongFiles {
    fun layout(context: Context) = FelsongLayout(context.filesDir, context.dataDir)

    /** Felsong's folder, guarded: nothing outside it, nothing of the other games. */
    fun fs(context: Context): SafeFs {
        val layout = layout(context)
        return SafeFs(layout.root, layout.protectedFolders)
    }

    /** The folder the server's programs see Felsong's at (as they were built for it). */
    fun serverRoot(context: Context): String =
        if (WowFiles.isStandardPath(context)) FelsongLayout.STANDARD_ROOT else layout(context).root.path

    fun state(context: Context): FelsongInstallState =
        FelsongInstallState.parse(runCatching { layout(context).stateFile.readText() }.getOrNull())

    @Synchronized
    fun updateState(context: Context, change: (FelsongInstallState) -> FelsongInstallState): FelsongInstallState {
        val layout = layout(context)
        val next = change(state(context)).copy(updatedAt = System.currentTimeMillis())
        val fs = fs(context)
        fs.mkdirs(layout.root)
        fs.writeAtomically(layout.stateFile, next.toJson())
        return next
    }

    /** The client's program (or only its patched one: a client taken from SPP's) is there with its data. */
    fun hasClient(context: Context): Boolean {
        val client = layout(context).client
        return FelsongClient.program(client) != null && FelsongClient.find(client, "Data")?.isDirectory == true
    }

    /** The program the connection patcher made, if it's there. */
    fun patchedExe(context: Context): File? = FelsongClient.find(layout(context).client, FelsongClient.PATCHED_EXE)?.takeIf { it.isFile }

    /** The client is patched for the local realm, from the Wow-64.exe that's there now. */
    fun isPatched(context: Context): Boolean {
        val state = state(context)
        return patchedExe(context) != null && state.patchedFrom != null && state.patchedFrom == state.clientExeSha256 && layout(context).certBundle.isFile
    }

    /** The server's data folders there, with files in them. */
    fun dataParts(context: Context): Set<String> {
        val data = layout(context).data
        return FelsongDataImport.FOLDERS.filter { File(data, it).list()?.isNotEmpty() == true }.toSet()
    }

    /** The world server has what it can't start without. */
    fun hasData(context: Context): Boolean = dataParts(context).containsAll(FelsongDataImport.REQUIRED)

    fun hasDatabases(context: Context): Boolean = File(layout(context).databases, "mysql").isDirectory

    fun freeSpace(context: Context): Long = StatFs(context.filesDir.path).availableBytes

    /** Felsong's folder, all of it, in bytes. */
    fun used(context: Context): Long = WowFiles.sizeOf(layout(context).root)

    /**
     * Why Felsong can't run on this phone at all, or null: the server's programs are built for
     * arm64 and for the app's folder of the phone's main user.
     */
    fun blocker(context: Context): String? = when {
        Build.SUPPORTED_ABIS.none { it == "arm64-v8a" } -> "arm64"
        !WowFiles.isStandardPath(context) -> "path"
        else -> null
    }
}
