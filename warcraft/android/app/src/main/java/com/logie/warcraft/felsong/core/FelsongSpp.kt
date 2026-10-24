// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.core

import java.io.File
import java.nio.file.Files

/**
 * The Legion files the Single Player Project download left on the phone (`files/spp-legion`), which
 * LegionCore's game takes instead of a second copy (the user: "I don't need the spp"):
 *
 * - its 7.3.5 (26972) client, with SPP's patched program ([SPP_EXE], checked against [SPP_EXE_SHA256]),
 *   made for the TrinityCore Battle.net key LegionCore's login server has too; it becomes the
 *   client's patched program, and the certificate bundle it trusts is the app's (`spp/web_cert_bundle`);
 * - its server data (`Server/Data`: dbc, maps, vmaps, mmaps, cameras, gt), made by AshamaneCore's
 *   extractors in the formats LegionCore reads (maps `MAPS v1.9` with the same 44-byte header,
 *   `VMAP_4.8` with spawn indices, mmaps version 9 on Detour 7, the client's own WDC1 DB2 files).
 */
object FelsongSpp {
    const val SPP_EXE = "SPP_Wow-64.exe"
    const val SPP_EXE_SHA256 = "be484c9a3bd8e5de173e9a8e185a9b6636406d69a9122987f6f2c1d512b2bec5"

    /** The client's folder under [root]: the one with a Data folder and Wow-64.exe or SPP's program, the shallowest. */
    fun findClient(root: File): File? = folders(root, 8).filter { folder ->
        child(folder, "Data")?.isDirectory == true &&
            (child(folder, FelsongClient.EXE)?.isFile == true || child(folder, SPP_EXE)?.isFile == true)
    }.minByOrNull { it.path.length }

    /** The server's data folder under [root]: the one with dbc and maps folders that have files, the shallowest. */
    fun findData(root: File): File? = folders(root, 8).filter { folder ->
        FelsongDataImport.REQUIRED.all { child(folder, it)?.list()?.isNotEmpty() == true }
    }.minByOrNull { it.path.length }

    /** [name] in [folder], matched without regard to case (Windows names). */
    fun child(folder: File, name: String): File? =
        File(folder, name).takeIf { it.exists() } ?: folder.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun folders(root: File, depth: Int): Sequence<File> =
        if (!root.isDirectory) emptySequence()
        else root.walkTopDown().maxDepth(depth).onEnter { !Files.isSymbolicLink(it.toPath()) }.filter { it.isDirectory }
}
