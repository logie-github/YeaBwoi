// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.content.Context
import android.net.Uri
import org.dolphinemu.dolphinemu.features.settings.model.BooleanSetting
import org.dolphinemu.dolphinemu.features.settings.model.NativeConfig
import org.dolphinemu.dolphinemu.model.GameFile
import org.dolphinemu.dolphinemu.utils.DirectoryInitialization
import java.io.File
import java.io.IOException

/** Checks and installs the files the player hands to Dolphin XD during setup. */
object XdGameFiles {
    /** Pokémon XD: Gale of Darkness is GXXE01 (USA), GXXP01 (Europe) and GXXJ01 (Japan). */
    private const val POKEMON_XD_ID_PREFIX = "GXX"
    private const val PLATFORM_GAMECUBE = 0

    /** GameCube IPL dumps are exactly 2 MiB. */
    private const val IPL_SIZE = 2 * 1024 * 1024L
    private const val IPL_HEADER_SIZE = 0x100

    sealed class RomCheck {
        data class PokemonXd(val title: String, val gameId: String) : RomCheck()
        data class OtherGame(val title: String, val gameId: String) : RomCheck()
        object Unreadable : RomCheck()
    }

    sealed class BiosCheck {
        data class Ok(val region: String) : BiosCheck()
        object NotABios : BiosCheck()
        object Unreadable : BiosCheck()
    }

    enum class BiosInstall { NOTHING_TO_INSTALL, INSTALLED, REGION_MISMATCH, FAILED }

    /** Must run after directory initialization, off the main thread. */
    fun checkRom(path: String): RomCheck {
        val game = GameFile.parse(path) ?: return RomCheck.Unreadable
        val title = game.getTitle()
        val gameId = game.getGameId()
        return if (game.getPlatform() == PLATFORM_GAMECUBE && gameId.startsWith(POKEMON_XD_ID_PREFIX)) {
            RomCheck.PokemonXd(title, gameId)
        } else {
            RomCheck.OtherGame(title, gameId)
        }
    }

    fun canRead(context: Context, path: String): Boolean = try {
        if (path.startsWith("content://")) {
            context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { true } ?: false
        } else {
            File(path).canRead()
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Copies a GameCube BIOS (IPL.bin) into app storage. It is installed once the game file is
     * known, because Dolphin looks for it in a folder named after the game's region.
     */
    fun stageBios(context: Context, uri: Uri): BiosCheck {
        val staged = stagedBiosFile(context)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            } ?: return BiosCheck.Unreadable
        } catch (_: IOException) {
            staged.delete()
            return BiosCheck.Unreadable
        } catch (_: SecurityException) {
            staged.delete()
            return BiosCheck.Unreadable
        }

        // The first 0x100 bytes of an IPL dump are a plain-text copyright and version string:
        // "(C) 1999-2001 Nintendo. ... PAL  Revision 1.0" for PAL, and either no revision or an
        // "NTSC"/"MPAL" one for the 60 Hz regions.
        val region = if (staged.length() == IPL_SIZE) {
            val header = staged.inputStream().use { it.readNBytesCompat(IPL_HEADER_SIZE) }
                .toString(Charsets.ISO_8859_1)
            when {
                !header.contains("Nintendo") -> null
                header.contains("PAL") && !header.contains("MPAL") -> REGION_PAL
                else -> REGION_NTSC
            }
        } else {
            null
        }

        if (region == null) {
            staged.delete()
            return BiosCheck.NotABios
        }
        return BiosCheck.Ok(region)
    }

    fun discardStagedBios(context: Context) {
        stagedBiosFile(context).delete()
    }

    /**
     * Moves a staged BIOS to User/GC/<region>/IPL.bin and turns on the GameCube boot animation.
     * Must run after directory initialization.
     */
    fun installStagedBios(context: Context, biosRegion: String?, gameId: String): BiosInstall {
        val staged = stagedBiosFile(context)
        if (biosRegion == null || !staged.exists()) {
            return BiosInstall.NOTHING_TO_INSTALL
        }

        val gameIsPal = gameId.getOrNull(3) == 'P'
        if (gameIsPal != (biosRegion == REGION_PAL)) {
            staged.delete()
            return BiosInstall.REGION_MISMATCH
        }

        val regionDir = regionDirectory(gameId)
        val target = File(DirectoryInitialization.getUserDirectory(), "GC/$regionDir/IPL.bin")
        return try {
            target.parentFile?.mkdirs()
            staged.copyTo(target, overwrite = true)
            staged.delete()
            BooleanSetting.MAIN_SKIP_IPL.setBoolean(NativeConfig.LAYER_BASE, false)
            NativeConfig.save(NativeConfig.LAYER_BASE)
            XdConfig(context).installedBiosRegionDir = regionDir
            BiosInstall.INSTALLED
        } catch (_: IOException) {
            BiosInstall.FAILED
        }
    }

    private fun regionDirectory(gameId: String): String = when (gameId.getOrNull(3)) {
        'P' -> "EUR"
        'J' -> "JAP"
        else -> "USA"
    }

    private fun stagedBiosFile(context: Context) = File(context.filesDir, "xd_staged_ipl.bin")

    private fun java.io.InputStream.readNBytesCompat(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(buffer, read, count - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    const val REGION_NTSC = "NTSC"
    const val REGION_PAL = "PAL"
}
