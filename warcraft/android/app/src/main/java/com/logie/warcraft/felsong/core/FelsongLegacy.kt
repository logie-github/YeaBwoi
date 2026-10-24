// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.core

import com.logie.warcraft.turtle.core.SafeFs
import java.io.File
import java.nio.file.Files

/**
 * What the Legion this app had before (Single Player Project's AshamaneCore repack, run under Wine)
 * left in storage: its folder, which is removed once ([com.logie.warcraft.felsong.LegacySpp]).
 */
object FelsongLegacy {
    /** The old Legion's folder, in the app's files or in the external app storage. */
    const val SPP_FOLDER = "spp-legion"

    /**
     * Deletes `spp-legion` from each of [parents] (the app's files folder, the external app storage),
     * through a guard scoped to that parent: a link in it is removed as a link and never followed,
     * and nothing else in the parent is touched. True when none is left.
     */
    fun removeSppFolders(parents: List<File>): Boolean {
        var clean = true
        for (parent in parents) {
            val folder = File(parent, SPP_FOLDER)
            if (!folder.exists() && !Files.isSymbolicLink(folder.toPath())) continue
            clean = runCatching { SafeFs(parent).delete(folder) }.isSuccess && clean
            if (folder.exists() || Files.isSymbolicLink(folder.toPath())) clean = false
        }
        return clean
    }
}
