// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.logie.dolphinxd.patch.UpsPatcher
import org.dolphinemu.dolphinemu.NativeLibrary
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.model.GameFile
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Installs mods of the app's game. A mod patch is applied the way mod authors ask for: to a
 * clean copy of the player's original disc image (never changing the original), producing a
 * separate ISO that becomes another selectable game version. Add-on patches that are meant to
 * go on top of a mod (like Grand Colosseum's Nuzlocke patch) update that mod's ISO instead.
 */
object XdMods {
    private const val PLATFORM_GAMECUBE = 0
    private const val BLOB_TYPE_PLAIN = 0
    private const val BLOB_TYPE_SPLIT_PLAIN = 11
    private const val FORMAT_ISO = 0
    private const val SPACE_MARGIN = 64L * 1024 * 1024

    private val modsDirectory: File
        get() = File(XdFiles.xdDirectory, "Mods")

    private val tempDirectory: File
        get() = File(XdFiles.xdDirectory, "Temp")

    /** Mods a patch can be applied on top of: ISOs Dolphin XD made itself. */
    fun patchableMods(context: Context): List<XdMod> = XdConfig(context).mods.filter { it.owned }

    /**
     * Applies a UPS patch (such as Pokémon XG: Next Gen or Pokémon Grand Colosseum) to the
     * original game, or to the mod [baseId] for add-on patches. Background thread.
     */
    fun installUps(context: Context, patchUri: Uri, baseId: String, progress: XdTasks.Progress): String {
        val config = XdConfig(context)
        val patchName = (XdFiles.displayName(context, patchUri) ?: "Mod").substringBeforeLast('.')
        val baseMod = config.mods.find { it.id == baseId && it.owned }
        val basePath = baseMod?.path ?: config.romUri
            ?: throw XdTaskException(context.getString(R.string.xd_mods_need_game))

        tempDirectory.deleteRecursively()
        tempDirectory.mkdirs()
        // An add-on updates its mod in place; anything else becomes a new mod.
        val modDirectory = if (baseMod != null) File(baseMod.path).parentFile!!
        else XdFiles.uniqueChild(modsDirectory, "", patchName)
        val partial = File(modDirectory, "game.iso.part")
        var succeeded = false
        try {
            // 1. The patch: read straight from where it is if its size is known (patches can be
            // hundreds of MB), otherwise copy it first.
            progress.update(context.getString(R.string.xd_mods_reading_patch))
            var patchSize = XdFiles.size(context, patchUri)
            var patchFile: File? = null
            if (patchSize <= 0) {
                val copy = File(tempDirectory, "patch.ups")
                openUri(context, patchUri).use { input -> copy.outputStream().use { input.copyTo(it, 1 shl 20) } }
                patchFile = copy
                patchSize = copy.length()
            }
            val openPatch = { patchFile?.inputStream() ?: openUri(context, patchUri) }
            val header = try {
                openPatch().use { UpsPatcher.readHeader(it, patchSize) }
            } catch (_: UpsPatcher.UpsException) {
                throw XdTaskException(context.getString(R.string.xd_mods_not_ups))
            }

            // 2. The base game as a plain ISO. Mods already are; the original may need converting.
            val game = GameFile.parse(basePath)
                ?: throw XdTaskException(context.getString(R.string.xd_error_unreadable_rom))
            val gameLabel = "${baseMod?.name ?: game.getTitle()}, ${game.getGameId()}"
            if (game.isNKit()) {
                throw XdTaskException(context.getString(R.string.xd_mods_nkit, gameLabel))
            }
            val isPlain = game.getBlobType() == BLOB_TYPE_PLAIN || game.getBlobType() == BLOB_TYPE_SPLIT_PLAIN
            val needed = header.targetSize + (if (isPlain) 0 else header.sourceSize) + SPACE_MARGIN
            XdFiles.requireSpace(context, XdFiles.xdDirectory, needed)

            val sourcePath = if (isPlain) {
                basePath
            } else {
                val iso = File(tempDirectory, "original.iso")
                val converting = context.getString(R.string.xd_mods_converting)
                progress.update(converting, 0f)
                val converted = NativeLibrary.ConvertDiscImage(
                    basePath, iso.path, game.getPlatform(), FORMAT_ISO, 0, 0, 0, false
                ) { _, completion ->
                    progress.update(converting, completion)
                    !progress.isCancelled
                }
                progress.throwIfCancelled()
                if (!converted) {
                    throw XdTaskException(context.getString(R.string.xd_mods_convert_failed))
                }
                iso.path
            }
            val sourceSize = if (isPlain) game.getFileSize() else File(sourcePath).length()

            // 3. Patch into the mod's folder.
            modDirectory.mkdirs()
            val patching = context.getString(R.string.xd_mods_patching)
            try {
                XdFiles.openGame(context, sourcePath).use { source ->
                    openPatch().use { patch ->
                        partial.outputStream().use { target ->
                            UpsPatcher.apply(
                                source, patch, patchSize, target,
                                sourceSize = sourceSize,
                                onProgress = { done, total -> progress.update(patching, done.toFloat() / total) },
                                isCancelled = { progress.isCancelled }
                            )
                        }
                    }
                }
            } catch (_: UpsPatcher.SourceMismatchException) {
                throw XdTaskException(
                    context.getString(R.string.xd_mods_wrong_source, gameLabel,
                        XdFiles.formatSize(context, sourceSize), header.sourceSize.toString())
                )
            } catch (e: UpsPatcher.CorruptPatchException) {
                throw XdTaskException(context.getString(R.string.xd_mods_corrupt, e.message))
            } catch (_: UpsPatcher.CancelledException) {
                throw XdTaskCancelledException()
            }

            // 4. Check that Dolphin can read the result before it replaces anything.
            progress.update(context.getString(R.string.xd_mods_checking))
            val patched = GameFile.parse(partial.path)
                ?: throw XdTaskException(context.getString(R.string.xd_mods_unreadable_result))
            val iso = File(modDirectory, "game.iso")
            if (!partial.renameTo(iso)) {
                throw XdTaskException(context.getString(R.string.xd_mods_unreadable_result))
            }
            succeeded = true

            val mod = if (baseMod != null) {
                baseMod.copy(name = "${baseMod.name} + $patchName", gameId = patched.getGameId())
            } else {
                XdMod(modDirectory.name, patchName, iso.path, patched.getGameId(), owned = true)
            }
            config.mods = config.mods.filter { it.id != mod.id } + mod
            XdUiThread.runBlocking { select(context, mod.id) }
            return context.getString(R.string.xd_mods_installed_message, mod.name)
        } finally {
            partial.delete()
            if (!succeeded && baseMod == null) {
                modDirectory.deleteRecursively()
            }
            tempDirectory.deleteRecursively()
        }
    }

    /** Adds a game the player patched elsewhere. Background thread. */
    fun addPatchedGame(context: Context, uri: Uri, progress: XdTasks.Progress): String {
        progress.update(context.getString(R.string.xd_checking))
        val path = uri.toString()
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        val game = GameFile.parse(path)
        if (game == null) {
            releasePermission(context, path)
            throw XdTaskException(context.getString(R.string.xd_error_unreadable_rom))
        }
        if (game.getPlatform() != PLATFORM_GAMECUBE || !XdGame.isThisGame(context, game.getGameId())) {
            releasePermission(context, path)
            throw XdTaskException(context.getString(R.string.xd_error_wrong_game, game.getTitle(), game.getGameId()))
        }

        val name = (XdFiles.displayName(context, uri) ?: game.getTitle()).substringBeforeLast('.')
        val config = XdConfig(context)
        val mod = XdMod(UUID.randomUUID().toString(), name, path, game.getGameId(), owned = false)
        config.mods = config.mods + mod
        XdUiThread.runBlocking { select(context, mod.id) }
        return context.getString(R.string.xd_mods_added_message, name)
    }

    /** Makes [versionId] what Play starts, switching saves over. Main thread, no game running. */
    fun select(context: Context, versionId: String) {
        val config = XdConfig(context)
        val from = config.activeVersionId
        if (from == versionId) return
        XdSaveProfiles.switch(from, config.gameIdOf(from), versionId)
        config.activeVersionId = versionId
    }

    /** Main thread, no game running. */
    fun delete(context: Context, mod: XdMod) {
        val config = XdConfig(context)
        if (config.activeVersionId == mod.id) {
            select(context, XdConfig.ORIGINAL)
        }
        config.mods = config.mods.filter { it.id != mod.id }
        if (mod.owned) {
            File(mod.path).parentFile?.deleteRecursively()
        } else {
            releasePermission(context, mod.path)
        }
    }

    private fun openUri(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw XdTaskException(context.getString(R.string.xd_error_unreadable_bios))

    private fun releasePermission(context: Context, path: String) {
        if (!path.startsWith("content://")) return
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(path), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
    }
}
