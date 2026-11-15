package com.logie.wifecraft.creator

import java.io.File

/**
 * A zip of yours looked for by its name where downloads usually are (Download, Downloads, Documents and the top of the
 * phone's shared storage), as Wrath++'s finder does. Pure; which folders the app may read is the caller's
 * (`ui/CreatorImports.kt` asks Android for the storage root and whether it may look).
 */
object ImportFinder {
    fun folders(root: File): List<File> = listOf(File(root, "Download"), File(root, "Downloads"), File(root, "Documents"), root)

    /** The first of [folders] holding a file named one of [names] (whatever the letters' case), the newest of them. */
    fun find(folders: List<File>, names: List<String>): File? {
        val wanted = names.map { it.lowercase() }.toSet()
        for (folder in folders) {
            val found = runCatching { folder.listFiles { f -> f.isFile && f.name.lowercase() in wanted } }.getOrNull()?.maxByOrNull { it.lastModified() }
            if (found != null) return found
        }
        return null
    }
}
