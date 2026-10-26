// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File

/**
 * Classic+ (https://kenshin.pl/classicplus/, by Piotr "Kenshin" Bednarczuk): new models and textures
 * for Warcraft III's units, buildings, trees and effects in the game's own style, made for 1.32.6
 * and later. It comes as a 7-Zip archive of loose files (its `Classic+` folder), which the game
 * reads in place of its own when the registry's `Allow Local Files` is 1.
 *
 * Warcraft III 1.27 reads the same files once converted ([install]):
 * - **Models**: most are MDX 800, 1.27's own; the rest (missiles, effects, a few buildings) are
 *   saved as 1100 without Reforged's HD art, and [Mdx.toV800] rewrites them.
 * - **Textures**: DDS (DXT1, DXT5), which 1.27 can't read, become BLP1 of the same name ([Blp]);
 *   the BLPs are 1.27's format already.
 * - **Sounds** (FLAC, Reforged's copies of the game's own WAVs, the same length and rate) and the
 *   pictures that come with it are left out: 1.27 has those sounds.
 *
 * The files go into the game's folder at their paths, folders matched without regard to case (the
 * game's lookups ignore it; the first spelling is kept), with the list of what was written
 * ([LIST]) to take them out again ([remove]). Turned off ([OFF]), they stay but the game is told
 * not to read loose files.
 */
object ClassicPlus {
    /** The mod's page, which links its download. */
    const val PAGE = "https://kenshin.pl/classicplus/"
    /** The download linked from [PAGE] when this was made (v1.01, kenshin_lab_v1.01.7z, 394 MB). */
    const val DRIVE_ID = "1vndUBXEFwlMNBUWhUUphSoCr3WhEPE2h"

    /** In the game's folder: every file Classic+ put there, one path a line. */
    const val LIST = "classicplus-files.txt"
    /** In the game's folder while Classic+ is turned off. */
    const val OFF = "classicplus.off"

    private const val FOLDER = "classic+"

    /** The Google Drive file [PAGE] links to, or null. */
    fun driveId(page: String): String? =
        Regex("""drive\.google\.com/file/d/([A-Za-z0-9_-]{10,})""").find(page)?.groupValues?.get(1)

    /** Google Drive's address for downloading [id]. */
    fun driveUrl(id: String) = "https://drive.usercontent.google.com/download?id=$id&export=download"

    /**
     * Google Drive's "can't scan this file for viruses" page (files over 100 MB) carries a form
     * whose fields make the download: the address it sends, or null if [html] isn't that page.
     */
    fun confirmUrl(html: String): String? {
        val form = Regex("""<form[^>]*id="download-form"[^>]*action="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return null
        val fields = Regex("""<input[^>]*type="hidden"[^>]*name="([^"]+)"[^>]*value="([^"]*)"""").findAll(html)
            .joinToString("&") { "${it.groupValues[1]}=${it.groupValues[2]}" }
        return if (fields.isEmpty()) null else "$form?$fields"
    }

    fun installed(game: File) = File(game, LIST).isFile

    fun enabled(game: File) = installed(game) && !File(game, OFF).exists()

    fun setEnabled(game: File, on: Boolean) {
        val off = File(game, OFF)
        if (on) off.delete() else off.writeText("")
    }

    /** What [install] did. */
    data class Summary(val models: Int, val modelsConverted: Int, val textures: Int, val texturesConverted: Int, val left: Int, val failed: List<String>)

    /**
     * Classic+ from [archive] into the game's folder [game]: what was there from an earlier install
     * taken out first. [progress] hears how many of the archive's files are done, of how many, and
     * the one at hand; [cancelled] stops it between files (what was written stays listed).
     */
    fun install(archive: File, game: File, progress: (Int, Int, String) -> Unit, cancelled: () -> Boolean = { false }): Summary {
        remove(game)
        val partial = File(game, "$LIST.part")
        val written = ArrayList<String>()
        val folders = Folders(game)
        var models = 0
        var modelsConverted = 0
        var textures = 0
        var texturesConverted = 0
        var left = 0
        val failed = ArrayList<String>()
        SevenZFile.builder().setFile(archive).get().use { zip ->
            val total = zip.entries.count { !it.isDirectory }
            var done = 0
            partial.bufferedWriter().use { list ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (cancelled()) throw InterruptedException("stopped")
                    done++
                    val path = inMod(entry.name)
                    if (path == null) {
                        left++
                        continue
                    }
                    progress(done, total, path)
                    val extension = path.substringAfterLast('.', "").lowercase()
                    val (target, data) = try {
                        when (extension) {
                            "mdx" -> {
                                models++
                                val bytes = zip.readAll(entry.size)
                                val converted = Mdx.toV800(bytes)
                                if (converted != null) modelsConverted++
                                path to (converted ?: bytes)
                            }
                            "dds" -> {
                                textures++
                                texturesConverted++
                                path.dropLast(3) + "blp" to Blp.fromDds(zip.readAll(entry.size))
                            }
                            "blp" -> {
                                textures++
                                path to zip.readAll(entry.size)
                            }
                            else -> {
                                left++
                                continue
                            }
                        }
                    } catch (e: Exception) {
                        failed += "$path: ${e.message ?: e.javaClass.simpleName}"
                        continue
                    }
                    val file = folders.file(target) ?: continue
                    file.writeBytes(data)
                    val relative = file.relativeTo(game).path
                    written += relative
                    list.write(relative)
                    list.newLine()
                }
            }
        }
        partial.renameTo(File(game, LIST))
        return Summary(models, modelsConverted, textures, texturesConverted, left, failed)
    }

    /** Takes out every file Classic+ put in [game] (its list, or the list of an install that stopped) and the folders left empty. */
    fun remove(game: File) {
        for (list in listOf(File(game, LIST), File(game, "$LIST.part"))) {
            if (!list.isFile) continue
            val folders = HashSet<File>()
            list.forEachLine { line ->
                if (!GameLayout.isSafe(line)) return@forEachLine
                val file = File(game, line)
                file.delete()
                var parent = file.parentFile
                while (parent != null && parent != game) {
                    folders += parent
                    parent = parent.parentFile
                }
            }
            // Deepest first: a folder is empty once what's under it is gone.
            for (folder in folders.sortedByDescending { it.path.length }) {
                if (folder.list()?.isEmpty() == true) folder.delete()
            }
            list.delete()
        }
        File(game, OFF).delete()
    }

    /** [name]'s path inside the mod's `Classic+` folder (slashes, no leading one), or null if it isn't in it or isn't safe. */
    fun inMod(name: String): String? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        val at = parts.indexOfFirst { it.lowercase() == FOLDER }
        if (at < 0 || at == parts.size - 1) return null
        val path = parts.drop(at + 1).joinToString("/")
        return path.takeIf { GameLayout.isSafe(it) }
    }

    /** The whole of the entry just reached, [size] bytes. */
    private fun SevenZFile.readAll(size: Long): ByteArray {
        require(size in 0..MAX_FILE) { "too big ($size bytes)" }
        val out = ByteArray(size.toInt())
        var at = 0
        while (at < out.size) {
            val n = read(out, at, out.size - at)
            if (n < 0) break
            at += n
        }
        check(at == out.size) { "cut short" }
        return out
    }

    private const val MAX_FILE = 64L * 1024 * 1024

    /**
     * Folders under the game's, matched without regard to case: a path's folders take the spelling
     * already there (or the first one written), as the game finds them either way. A file that is
     * the game's own (not written by Classic+) is never written over: null.
     */
    private class Folders(private val game: File) {
        private val known = HashMap<String, File>()
        private val files = HashSet<String>()

        fun file(path: String): File? {
            val parts = path.split('/')
            var dir = game
            var key = ""
            for (part in parts.dropLast(1)) {
                key += "/" + part.lowercase()
                dir = known.getOrPut(key) {
                    val existing = dir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(part, ignoreCase = true) }
                    (existing ?: File(dir, part)).also { it.mkdirs() }
                }
            }
            val name = parts.last()
            val fileKey = key + "/" + name.lowercase()
            val existing = dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
            if (existing != null && fileKey !in files) return null
            files += fileKey
            return existing ?: File(dir, name)
        }
    }
}
