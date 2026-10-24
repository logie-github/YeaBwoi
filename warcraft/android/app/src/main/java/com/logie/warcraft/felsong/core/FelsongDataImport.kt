// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.core

/**
 * The server's data made on a computer from the user's own client (LegionCore's own extractors of
 * the same version) and imported as a zip: which files of the zip are taken and where they go, and
 * whether each is LegionCore's own format.
 *
 * The folders the world server reads are taken (dbc with its locale folders of .db2 files, maps,
 * vmaps, mmaps, cameras, gt), at the top of the zip or inside another folder; any one of them is
 * enough (a zip of just mmaps adds pathfinding to data the phone made). Every map, vmap and mmap
 * file is checked by its header, so a file of another core's or another version's tools is refused
 * before anything is replaced: the world server would ignore it, or stop on it.
 */
object FelsongDataImport {
    val FOLDERS = listOf("dbc", "maps", "vmaps", "mmaps", "cameras", "gt")

    /** The folders the world server can't start without. */
    val REQUIRED = listOf("dbc", "maps")

    /** LegionCore's MapDefines.cpp and map_extractor/System.cpp: MapMagic, MapVersionMagic. */
    private val MAP_MAGIC = "MAPS".toByteArray()
    private val MAP_VERSION = "v1.9".toByteArray()

    /** src/common/collision/VMapDefinitions.h: VMAP_MAGIC, at the start of every vmtree, vmtile and vmo. */
    private val VMAP_MAGIC = "VMAP_4.8".toByteArray()
    private const val GAMEOBJECT_MODELS = "GameObjectModels.dtree"

    /** src/common/collision/Maps/MapDefines.h: MMAP_MAGIC ('MMAP', little-endian) and MMAP_VERSION. */
    const val MMAP_MAGIC = 0x4d4d4150
    const val MMAP_VERSION = 9

    /** How many bytes of a file [problem] needs to see. */
    const val HEAD = 16

    private val MAP_NAME = Regex("""\d{4}_\d{2}_\d{2}\.map""")
    private val MMAP_NAME = Regex("""\d{4}\.mmap""")
    private val MMTILE_NAME = Regex("""\d{8}\.mmtile""")
    private val LOCALE = Regex("[a-z]{2}[A-Z]{2}")

    /**
     * Where a file of the zip goes, relative to the data folder (`maps/0000_31_32.map`,
     * `dbc/enUS/Spell.db2`), or null if it isn't one of the server's data files: not in one of
     * [FOLDERS], a folder's own entry, or a name that would lead anywhere else.
     */
    fun target(entryName: String): String? {
        val name = entryName.replace('\\', '/')
        if (name.startsWith("/") || name.endsWith("/")) return null
        val parts = name.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.contains(':') }) return null
        val at = parts.indexOfFirst { part -> FOLDERS.any { it.equals(part, ignoreCase = true) } }
        if (at < 0) return null
        val folder = FOLDERS.first { it.equals(parts[at], ignoreCase = true) }
        val file = parts.last()
        if (file.startsWith(".")) return null
        return when {
            // The DB2 files are in a locale folder (dbc/enUS/...), as the world server reads them.
            folder == "dbc" -> if (at == parts.size - 3 && LOCALE.matches(parts[at + 1])) "dbc/${parts[at + 1]}/$file" else null
            // Directly in the folder: the world server reads no subfolders of the others.
            at != parts.size - 2 -> null
            else -> "$folder/$file"
        }
    }

    /**
     * What's wrong with a file the zip has for [target] (from [target]), given its first bytes
     * ([head], up to [HEAD] of them), or null if it's LegionCore's own format.
     */
    fun problem(target: String, head: ByteArray): Problem? {
        val folder = target.substringBefore('/')
        val file = target.substringAfterLast('/')
        return when (folder) {
            "dbc" -> when {
                !file.endsWith(".db2", ignoreCase = true) -> Problem.NOT_DATA
                // WDC1 (7.3's DB2 format), WDC2, or an older DB2 (WDB2 to WDB6): never WDBC, a DBC.
                !(head.startsWith("WDC".toByteArray()) || (head.startsWith("WDB".toByteArray()) && head.size > 3 && head[3].toInt().toChar().isDigit())) -> Problem.FOREIGN
                else -> null
            }
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
            // The flyby cameras (M2 models) and the game tables (text): no header check.
            "cameras" -> if (file.endsWith(".m2", ignoreCase = true)) null else Problem.NOT_DATA
            "gt" -> if (file.endsWith(".txt", ignoreCase = true)) null else Problem.NOT_DATA
            else -> Problem.NOT_DATA
        }
    }

    enum class Problem {
        /** Not a file the world server reads (a note beside the data, say): left out. */
        NOT_DATA,

        /**
         * One of the server's data files, but not in this realm's format: made by another core's
         * tools or another version of LegionCore's. The world server would ignore it or stop on it,
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
