// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.client

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * The character textures of the launcher's Game options (the realm's mod-ralia): [RaliaFiles] (Ralia's
 * mod) and [AlternateFemaleFiles] (Alternate Female Options), one on at a time. Each set's textures, from
 * the zip the user picks, are kept in the app's files (`<folder>/files`, the zip's `Character` folder),
 * and `<folder>/enabled` while it's on. The world server makes the game's patch from the one that's on
 * as it starts (`Data/<locale>/patch-<locale>-Z.MPQ`, with a `.ralia` file beside it) and takes it out
 * while neither is; the app takes it out at once when one is turned off.
 */
open class TextureSet(private val folderName: String) {
    class NoTexturesException : IOException("no Character folder of textures in the zip")

    companion object {
        val ALL: List<TextureSet> by lazy { listOf(RaliaFiles, AlternateFemaleFiles) }
    }

    fun folder(root: File) = File(root, folderName)
    fun files(root: File) = File(folder(root), "files")
    internal fun marker(root: File) = File(folder(root), "enabled")

    fun isOn(root: File): Boolean = marker(root).isFile

    fun hasFiles(root: File): Boolean =
        files(root).walkTopDown().any { it.isFile && it.name.endsWith(".blp", ignoreCase = true) }

    /**
     * On (only with the textures there; the other set goes off, its patch made again in this one's place
     * at the realm's next start) or off; off takes the game's patch out of [client] too.
     */
    fun setOn(root: File, client: File, on: Boolean) {
        if (on) {
            if (!hasFiles(root)) throw NoTexturesException()
            for (other in ALL) if (other !== this) other.marker(root).delete()
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
                File(locale, "patch-${locale.name}-Z.MPQ.files").delete()
                removed = true
            }
        }
        return removed
    }
}

/** Ralia's mod: Ralia's patches' textures (`files/ralia`). */
object RaliaFiles : TextureSet("ralia")

/** Alternate Female Options: another set of textures, the women's (`files/alternate-female`). */
object AlternateFemaleFiles : TextureSet("alternate-female")
