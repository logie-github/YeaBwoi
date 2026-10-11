// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.client

/**
 * Where the files of the server's data go. The data is five folders, made from the client by
 * AzerothCore's tools: dbc and Cameras (the game's tables and cutscene cameras), maps (terrain),
 * vmaps (buildings, for line of sight) and mmaps (pathfinding, for creatures and bots).
 */
object DataLayout {
    val FOLDERS = listOf("dbc", "Cameras", "maps", "vmaps", "mmaps")

    /** The folders the world server can't start without. */
    val REQUIRED = listOf("dbc", "maps")

    /**
     * Where a file of a zip goes, relative to the data folder: the zip may hold the folders at its
     * top, or inside another folder (`server-data/maps/...`). Null for anything else.
     */
    fun target(entryName: String): String? {
        val parts = entryName.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        val at = parts.indexOfFirst { part -> FOLDERS.any { it.equals(part, ignoreCase = true) } }
        if (at < 0 || at == parts.size - 1) return null
        val folder = FOLDERS.first { it.equals(parts[at], ignoreCase = true) }
        return (listOf(folder) + parts.drop(at + 1)).joinToString("/")
    }
}

/** Finding the client in what the user picked: the folder that holds Wow.exe and Data. */
object ClientLayout {
    /**
     * The folder (relative path, "" for the top) of a client among [paths] (every file's path,
     * relative to what was picked), or null if there's no client in it. A client folder has
     * Wow.exe next to a Data folder with common.MPQ in it.
     */
    fun findRoot(paths: Collection<String>): String? {
        val normalized = paths.map { it.replace('\\', '/').trimStart('/') }
        val lower = normalized.map { it.lowercase() }.toSet()
        return normalized
            .filter { it.substringAfterLast('/').equals("Wow.exe", ignoreCase = true) }
            .map { it.substringBeforeLast('/', "") }
            .filter { dir ->
                val prefix = if (dir.isEmpty()) "" else "${dir.lowercase()}/"
                "${prefix}data/common.mpq" in lower
            }
            .minByOrNull { it.length }
    }

    /** A path is safe to write under the client folder: no way out of it. */
    fun isSafe(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/')
        return parts.none { it == ".." } && !path.startsWith("/")
    }
}
