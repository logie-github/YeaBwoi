// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import org.dolphinemu.dolphinemu.features.settings.model.NativeConfig
import org.dolphinemu.dolphinemu.features.settings.model.Settings
import java.io.File

/**
 * Keeps saves apart between the original game and each mod. Pokémon XG uses the same game ID
 * as Pokémon XD (GXXE01), so without this both would share one memory card save and one set of
 * save states, and loading one game's save state in the other corrupts it.
 *
 * The original game keeps Dolphin's default save locations. A mod's memory card lives in
 * XD/Saves/<mod id>/ (set through Dolphin's memory card path settings), and save states are moved
 * out of and back into Dolphin's StateSaves folder when switching versions.
 */
object XdSaveProfiles {
    private const val KEY_GCI_FOLDER_A = "GCIFolderAPath"
    private const val KEY_MEMCARD_A = "MemcardAPath"

    /** Must run on the main thread while no game is running. */
    fun switch(fromId: String, fromGameId: String?, toId: String) {
        if (fromId == toId) return
        val stateSaves = File(XdFiles.userDirectory, "StateSaves")

        // Park the outgoing version's save states...
        if (fromGameId != null) {
            val parked = File(profile(fromId), "StateSaves")
            stateSaves.listFiles { file -> file.isFile && file.name.startsWith("$fromGameId.") }
                ?.forEach { file -> moveInto(file, parked) }
        }
        // ...and bring back the incoming version's.
        File(profile(toId), "StateSaves").listFiles()?.forEach { file -> moveInto(file, stateSaves) }

        // Dolphin expects ".../<region>" and ".../<name>.<region>.raw" and swaps in the game's region.
        val gciFolder = if (toId == XdConfig.ORIGINAL) "" else File(profile(toId), "Card A/USA").path
        val memcard = if (toId == XdConfig.ORIGINAL) "" else File(profile(toId), "MemoryCardA.USA.raw").path
        setCoreString(KEY_GCI_FOLDER_A, gciFolder)
        setCoreString(KEY_MEMCARD_A, memcard)
        NativeConfig.save(NativeConfig.LAYER_BASE)
    }

    private fun profile(versionId: String) = File(XdFiles.xdDirectory, "Saves/$versionId")

    private fun moveInto(file: File, directory: File) {
        directory.mkdirs()
        var target = File(directory, file.name)
        if (target.exists()) {
            // Never overwrite a save state; keep both.
            target = File(directory, "${file.name}.${System.currentTimeMillis()}.bak")
        }
        if (!file.renameTo(target)) {
            file.copyTo(target)
            file.delete()
        }
    }

    private fun setCoreString(key: String, value: String) {
        NativeConfig.setString(
            NativeConfig.LAYER_BASE,
            Settings.FILE_DOLPHIN,
            Settings.SECTION_INI_CORE,
            key,
            value
        )
    }
}
