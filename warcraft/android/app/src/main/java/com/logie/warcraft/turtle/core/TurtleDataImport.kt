// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.core

/**
 * The server's data made on a computer from the user's own client (scripts/make-turtle-server-
 * data.sh, or any Tortoise-WoW tools of the same version) and imported as a zip: which files of
 * the zip are taken and where they go, and whether each is Tortoise's own format.
 *
 * Only the four folders the world server reads are taken (dbc, maps, vmaps, mmaps), at the top of
 * the zip or inside another folder; any one of them is enough (a zip of just mmaps adds
 * pathfinding to data the phone made). Every map, vmap and mmap file is checked by its header,
 * so a file of another core's or another version's tools is refused before anything is replaced:
 * the world server would ignore it, or stop on it.
 */
object TurtleDataImport {
    val FOLDERS = listOf("dbc", "maps", "vmaps", "mmaps")

    /** The folders the world server can't start without. */
    val REQUIRED = listOf("dbc", "maps")

    /** Tortoise's src/game/Maps/GridMap.cpp: MAP_MAGIC, MAP_VERSION_MAGIC. */
    private val MAP_MAGIC = "MAPS".toByteArray()
    private val MAP_VERSION = "z1.4".toByteArray()

    /** src/game/vmap/VMapDefinitions.h: VMAP_MAGIC, at the start of every vmtree, vmtile and vmo. */
    private val VMAP_MAGIC = "VMAP_6.0".toByteArray()
    private const val GAMEOBJECT_MODELS = "temp_gameobject_models"

    /** src/game/MoveMapSharedDefines.h: MMAP_MAGIC ('MMAP', little-endian) and MMAP_VERSION. */
    const val MMAP_MAGIC = 0x4d4d4150
    const val MMAP_VERSION = 6

    /** How many bytes of a file [problem] needs to see. */
    const val HEAD = 16

    private val MAP_NAME = Regex("""\d{7}\.map""")
    private val MMAP_NAME = Regex("""\d{3}\.mmap""")
    private val MMTILE_NAME = Regex("""\d{7}\.mmtile""")

    /**
     * Where a file of the zip goes, relative to the data folder (`mmaps/0013231.mmtile`), or null
     * if it isn't one of the server's data files: not in one of [FOLDERS], a folder's own entry, or
     * a name that would lead anywhere else.
     */
    fun target(entryName: String): String? {
        val name = entryName.replace('\\', '/')
        if (name.startsWith("/") || name.endsWith("/")) return null
        val parts = name.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.contains(':') }) return null
        val at = parts.indexOfFirst { part -> FOLDERS.any { it.equals(part, ignoreCase = true) } }
        // Directly in the folder: the world server reads no subfolders of them.
        if (at < 0 || at != parts.size - 2) return null
        val folder = FOLDERS.first { it.equals(parts[at], ignoreCase = true) }
        val file = parts.last()
        if (file.startsWith(".")) return null
        return "$folder/$file"
    }

    /**
     * What's wrong with a file the zip has for [target] (from [target]), given its first bytes
     * ([head], up to [HEAD] of them), or null if it's Tortoise's own format. dbc files are checked
     * against Turtle's checksums once they're all in instead.
     */
    fun problem(target: String, head: ByteArray): Problem? {
        val folder = target.substringBefore('/')
        val file = target.substringAfter('/')
        return when (folder) {
            "dbc" -> if (file.endsWith(".dbc", ignoreCase = true)) null else Problem.NOT_DATA
            "maps" -> when {
                !MAP_NAME.matches(file) -> Problem.NOT_DATA
                !head.startsWith(MAP_MAGIC) || !head.startsWith(MAP_VERSION, at = 4) -> Problem.FOREIGN
                else -> null
            }
            "vmaps" -> when {
                // The game objects' models list (TileAssembler::exportGameobjectModels): no header.
                file == GAMEOBJECT_MODELS -> null
                !(file.endsWith(".vmtree") || file.endsWith(".vmtile") || file.endsWith(".vmo")) -> Problem.NOT_DATA
                !head.startsWith(VMAP_MAGIC) -> Problem.FOREIGN
                else -> null
            }
            "mmaps" -> when {
                // A map's navmesh parameters: no header of their own.
                MMAP_NAME.matches(file) -> null
                !MMTILE_NAME.matches(file) -> Problem.NOT_DATA
                head.size < 12 || head.int(0) != MMAP_MAGIC || head.int(8) != MMAP_VERSION -> Problem.FOREIGN
                else -> null
            }
            else -> Problem.NOT_DATA
        }
    }

    enum class Problem {
        /** Not a file the world server reads (a note beside the data, say): left out. */
        NOT_DATA,

        /**
         * One of the server's data files, but not in this realm's format: made by another core's
         * tools or another version of Tortoise's. The world server would ignore it or stop on it,
         * so the import is refused.
         */
        FOREIGN,
    }

    /**
     * Whether importing [imported] folders leaves the world server what it needs, given the
     * folders [existing] already has: an import of mmaps alone needs dbc and maps already there.
     */
    fun complete(imported: Set<String>, existing: Set<String>): Boolean = REQUIRED.all { it in imported || it in existing }

    private fun ByteArray.startsWith(prefix: ByteArray, at: Int = 0): Boolean =
        size >= at + prefix.size && prefix.indices.all { this[at + it] == prefix[it] }

    private fun ByteArray.int(at: Int): Int =
        (this[at].toInt() and 0xff) or
            ((this[at + 1].toInt() and 0xff) shl 8) or
            ((this[at + 2].toInt() and 0xff) shl 16) or
            ((this[at + 3].toInt() and 0xff) shl 24)
}
