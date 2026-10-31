// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.client

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Ralia's mod (the launcher's Game options; the realm's mod-ralia): the textures of the user's zip of
 * Ralia's patches, kept in the app's files (`ralia/files`, the zip's `Character` folder), and whether
 * the option is on (`ralia/enabled`). The world server makes the game's patch from them as it starts
 * (`Data/<locale>/patch-<locale>-Z.MPQ`, with a `.ralia` file beside it) and takes it out while the
 * option is off; the app takes it out at once when the option is turned off.
 */
object RaliaFiles {
    class NoTexturesException : IOException("no Character folder of textures in the zip")

    fun folder(root: File) = File(root, "ralia")
    fun files(root: File) = File(folder(root), "files")
    private fun marker(root: File) = File(folder(root), "enabled")

    fun isOn(root: File): Boolean = marker(root).isFile

    fun hasFiles(root: File): Boolean =
        files(root).walkTopDown().any { it.isFile && it.name.endsWith(".blp", ignoreCase = true) }

    /** On (only with the textures there) or off; off takes the game's patch out of [client] too. */
    fun setOn(root: File, client: File, on: Boolean) {
        if (on) {
            if (!hasFiles(root)) throw NoTexturesException()
            folder(root).mkdirs()
            marker(root).writeText("on\n")
        } else {
            marker(root).delete()
            removePatch(client)
        }
    }

    /**
     * Where a zip entry goes in `ralia/files`: a texture (.blp) of its `Character` folder (at any depth
     * in the zip), from that folder on; null for anything else, or a name that climbs out of it.
     */
    fun entryPath(name: String): String? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        val at = parts.indexOfFirst { it.equals("Character", ignoreCase = true) }
        if (at < 0 || at >= parts.size - 1) return null
        if (!parts.last().endsWith(".blp", ignoreCase = true)) return null
        return parts.drop(at).joinToString("/")
    }

    /**
     * The textures of the zip read from [zip] in place of those kept (written beside them first, so a
     * zip that fails leaves the old ones). Returns how many; [NoTexturesException] when there are none.
     */
    fun unpack(root: File, zip: InputStream): Int {
        val staging = File(folder(root), "files.new")
        staging.deleteRecursively()
        staging.mkdirs()
        var count = 0
        try {
            ZipInputStream(zip.buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val path = if (entry.isDirectory) null else entryPath(entry.name)
                    if (path != null) {
                        val target = File(staging, path)
                        if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) continue
                        target.parentFile?.mkdirs()
                        target.outputStream().use { input.copyTo(it) }
                        count++
                    }
                    input.closeEntry()
                }
            }
            if (count == 0) throw NoTexturesException()
            val files = files(root)
            val old = File(folder(root), "files.old")
            old.deleteRecursively()
            if (files.exists() && !files.renameTo(old)) throw IOException("couldn't move $files aside")
            if (!staging.renameTo(files)) {
                old.renameTo(files)
                throw IOException("couldn't put $files in place")
            }
            old.deleteRecursively()
            return count
        } finally {
            staging.deleteRecursively()
        }
    }

    /** The realm's patch for the game in [client] (`Data/<locale>/patch-<locale>-Z.MPQ`), taken out if it's the mod's. */
    fun removePatch(client: File): Boolean {
        var removed = false
        File(client, "Data").listFiles()?.filter { it.isDirectory && it.name.length == 4 }?.forEach { locale ->
            val patch = File(locale, "patch-${locale.name}-Z.MPQ")
            val mark = File(locale, "patch-${locale.name}-Z.MPQ.ralia")
            if (mark.isFile) {
                patch.delete()
                mark.delete()
                removed = true
            }
        }
        return removed
    }
}
