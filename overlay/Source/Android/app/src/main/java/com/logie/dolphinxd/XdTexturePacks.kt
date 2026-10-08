// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.features.settings.model.BooleanSetting
import org.dolphinemu.dolphinemu.features.settings.model.NativeConfig
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Installs Dolphin texture packs for Pokémon XD.
 *
 * Each pack gets its own folder in Load/Textures. Dolphin uses every top-level folder there
 * that contains a "<game ID>.txt" file for that game (see GetTextureDirectoriesWithGameId), so a
 * "GXX.txt" marker enables a pack for all regions of Pokémon XD and Pokémon XG, and renaming
 * the marker turns the pack off without deleting it.
 */
object XdTexturePacks {
    private const val FOLDER_PREFIX = "xd-"
    private const val MARKER = "GXX.txt"
    private const val MARKER_OFF = "GXX.txt.off"
    private const val INFO = "xd-pack-info.txt"
    private const val STAGING = ".xd-import"
    private const val XD_ID_PREFIX = "GXX"
    private val TEXTURE_EXTENSIONS = setOf("png", "dds")
    private val GAME_ID = Regex("^[A-Z][A-Z0-9]{2}[A-Z][A-Z0-9]{2}$")

    data class Pack(
        val folder: File,
        val name: String,
        val enabled: Boolean,
        val textureCount: Int,
        val hasDds: Boolean,
    )

    private val texturesDirectory: File
        get() = File(XdFiles.userDirectory, "Load/Textures")

    var loadCustomTextures: Boolean
        get() = BooleanSetting.GFX_HIRES_TEXTURES.boolean
        set(value) {
            BooleanSetting.GFX_HIRES_TEXTURES.setBoolean(NativeConfig.LAYER_BASE, value)
            NativeConfig.save(NativeConfig.LAYER_BASE)
        }

    var prefetchCustomTextures: Boolean
        get() = BooleanSetting.GFX_CACHE_HIRES_TEXTURES.boolean
        set(value) {
            BooleanSetting.GFX_CACHE_HIRES_TEXTURES.setBoolean(NativeConfig.LAYER_BASE, value)
            NativeConfig.save(NativeConfig.LAYER_BASE)
        }

    fun list(): List<Pack> {
        val folders = texturesDirectory.listFiles { file ->
            file.isDirectory && file.name.startsWith(FOLDER_PREFIX)
        } ?: return emptyList()
        return folders.map { folder ->
            val info = File(folder, INFO).takeIf { it.exists() }?.readLines() ?: emptyList()
            Pack(
                folder = folder,
                name = info.getOrNull(0)?.takeIf { it.isNotBlank() } ?: folder.name.removePrefix(FOLDER_PREFIX),
                enabled = File(folder, MARKER).exists(),
                textureCount = info.getOrNull(1)?.toIntOrNull() ?: 0,
                hasDds = info.getOrNull(2) == "dds",
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun setEnabled(pack: Pack, enabled: Boolean) {
        val on = File(pack.folder, MARKER)
        val off = File(pack.folder, MARKER_OFF)
        if (enabled && !on.exists()) {
            if (!off.renameTo(on)) on.createNewFile()
        } else if (!enabled && on.exists()) {
            off.delete()
            on.renameTo(off)
        }
    }

    fun delete(pack: Pack) {
        pack.folder.deleteRecursively()
    }

    /** Imports a pack .zip: a Dolphin texture pack (GXX folder) or a resource pack (textures/). */
    fun importZip(context: Context, uri: Uri, progress: XdTasks.Progress): String {
        val name = (XdFiles.displayName(context, uri) ?: "Texture pack")
            .replace(Regex("(?i)\\.zip$"), "")
        val total = XdFiles.size(context, uri)
        if (total > 0) {
            XdFiles.requireSpace(context, texturesDirectory, total + total / 10)
        }

        val stream = context.contentResolver.openInputStream(uri)
            ?: throw XdTaskException(context.getString(R.string.xd_error_unreadable_bios))
        val counting = XdFiles.CountingInputStream(stream)
        val message = context.getString(R.string.xd_textures_reading)
        return install(context, name) { importer ->
            try {
                ZipInputStream(counting.buffered(256 * 1024)).use { zip ->
                    while (true) {
                        progress.throwIfCancelled()
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) {
                            importer.add(entry.name.replace('\\', '/').split('/'), zip)
                        }
                        if (total > 0) {
                            progress.update(message, counting.count.toFloat() / total)
                        } else {
                            progress.update(message)
                        }
                    }
                }
            } catch (_: ZipException) {
                throw XdTaskException(context.getString(R.string.xd_textures_bad_zip))
            }
        }
    }

    /** Imports a pack that's already been extracted, e.g. the GXX folder from the zip. */
    fun importFolder(context: Context, treeUri: Uri, progress: XdTasks.Progress): String {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = queryName(context, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId))
            ?: "Texture pack"

        // List every file first so the progress bar has a total.
        val files = ArrayList<Pair<String, List<String>>>()
        val pending = ArrayDeque(listOf(rootId to listOf(rootName)))
        progress.update(context.getString(R.string.xd_textures_reading))
        while (pending.isNotEmpty()) {
            progress.throwIfCancelled()
            val (documentId, path) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            resolver.query(
                children,
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0)
                    val childPath = path + cursor.getString(1)
                    if (cursor.getString(2) == Document.MIME_TYPE_DIR) {
                        pending.add(childId to childPath)
                    } else if (isTextureFile(childPath.last())) {
                        files.add(childId to childPath)
                    }
                }
            }
        }

        // A folder named after a game ID (GXX) isn't a useful pack name.
        val name = if (rootName.uppercase().startsWith(XD_ID_PREFIX) && rootName.length <= 6) {
            context.getString(R.string.xd_textures_default_name)
        } else {
            rootName
        }
        val message = context.getString(R.string.xd_textures_reading)
        return install(context, name) { importer ->
            files.forEachIndexed { index, (documentId, path) ->
                progress.throwIfCancelled()
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                resolver.openInputStream(documentUri)?.use { input -> importer.add(path, input) }
                progress.update(message, (index + 1f) / files.size)
            }
        }
    }

    private fun queryName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    private fun isTextureFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.startsWith("tex1_") && lower.substringAfterLast('.', "") in TEXTURE_EXTENSIONS
    }

    /** Copies textures into a staging folder, keeping the layout below the game ID folder. */
    private class Importer(private val staging: File) {
        var count = 0
        var hasDds = false
        val otherGames = sortedSetOf<String>()

        fun add(rawPath: List<String>, input: InputStream) {
            // Never trust archive paths: drop empty, "." and ".." parts.
            val path = rawPath.filter { it.isNotEmpty() && it != "." && it != ".." }
            val fileName = path.lastOrNull() ?: return
            if (!isTextureFile(fileName)) return

            var folders = path.dropLast(1)
            // Resource packs keep their textures under textures/<game ID>/.
            if (folders.firstOrNull()?.equals("textures", ignoreCase = true) == true) {
                folders = folders.drop(1)
            }
            val xdFolder = folders.indexOfFirst { it.uppercase().let { id -> id.startsWith(XD_ID_PREFIX) && id.length in setOf(3, 6) } }
            if (xdFolder >= 0) {
                folders = folders.drop(xdFolder + 1)
            } else {
                val other = folders.firstOrNull { GAME_ID.matches(it) }
                if (other != null) {
                    otherGames.add(other)
                    return
                }
            }

            val target = File(staging, (folders + fileName).joinToString(File.separator))
            target.parentFile?.mkdirs()
            target.outputStream().use { input.copyTo(it, 256 * 1024) }
            count++
            if (fileName.endsWith(".dds", ignoreCase = true)) hasDds = true
        }
    }

    private fun install(context: Context, name: String, copy: (Importer) -> Unit): String {
        val staging = File(texturesDirectory, STAGING)
        staging.deleteRecursively()
        staging.mkdirs()
        val importer = Importer(staging)
        try {
            try {
                copy(importer)
            } catch (e: IOException) {
                if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true) {
                    throw XdTaskException(context.getString(R.string.xd_no_space_generic))
                }
                throw e
            }

            if (importer.count == 0) {
                throw XdTaskException(
                    if (importer.otherGames.isNotEmpty()) {
                        context.getString(R.string.xd_textures_wrong_game, importer.otherGames.joinToString())
                    } else {
                        context.getString(R.string.xd_textures_none_found)
                    }
                )
            }

            File(staging, INFO).writeText("$name\n${importer.count}\n${if (importer.hasDds) "dds" else "png"}\n")
            File(staging, MARKER).createNewFile()
            val folder = XdFiles.uniqueChild(texturesDirectory, FOLDER_PREFIX, name)
            if (!staging.renameTo(folder)) {
                throw XdTaskException(context.getString(R.string.xd_task_failed))
            }
        } finally {
            staging.deleteRecursively()
        }

        // Dolphin's config is changed on the main thread, like the settings screens do.
        XdUiThread.runBlocking {
            if (!loadCustomTextures) loadCustomTextures = true
        }

        var message = context.getString(R.string.xd_textures_installed_message, name, importer.count)
        if (importer.hasDds) message += context.getString(R.string.xd_textures_dds_note)
        return message
    }
}
