// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Dolphin XD's own state: which game file to boot and how far the one-time setup got. */
class XdConfig(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("dolphin_xd", Context.MODE_PRIVATE)

    /** The setup files step was finished, either by adding a BIOS or by skipping it. */
    var setupFilesDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_FILES_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_SETUP_FILES_DONE, value) }

    /** Region of a BIOS that was picked but not installed yet ("NTSC" or "PAL"). */
    var stagedBiosRegion: String?
        get() = prefs.getString(KEY_STAGED_BIOS_REGION, null)
        set(value) = prefs.edit { putString(KEY_STAGED_BIOS_REGION, value) }

    /** Region folder (USA/EUR/JAP) a BIOS was installed into. */
    var installedBiosRegionDir: String?
        get() = prefs.getString(KEY_INSTALLED_BIOS_DIR, null)
        set(value) = prefs.edit { putString(KEY_INSTALLED_BIOS_DIR, value) }

    val romUri: String?
        get() = prefs.getString(KEY_ROM_URI, null)

    val romTitle: String?
        get() = prefs.getString(KEY_ROM_TITLE, null)

    val romGameId: String?
        get() = prefs.getString(KEY_ROM_GAME_ID, null)

    fun setRom(uri: String, title: String, gameId: String) = prefs.edit {
        putString(KEY_ROM_URI, uri)
        putString(KEY_ROM_TITLE, title)
        putString(KEY_ROM_GAME_ID, gameId)
    }

    /** Setup is done; from now on the app boots straight into the game. */
    var setupComplete: Boolean
        get() = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
        set(value) = prefs.edit { putBoolean(KEY_SETUP_COMPLETE, value) }

    /** The player asked (from the in-game menu) to pick a different game file. */
    var reconfigureRequested: Boolean
        get() = prefs.getBoolean(KEY_RECONFIGURE, false)
        set(value) = prefs.edit(commit = true) { putBoolean(KEY_RECONFIGURE, value) }

    var defaultsVersion: Int
        get() = prefs.getInt(KEY_DEFAULTS_VERSION, 0)
        set(value) = prefs.edit { putInt(KEY_DEFAULTS_VERSION, value) }

    var controlHintsShown: Int
        get() = prefs.getInt(KEY_CONTROL_HINTS_SHOWN, 0)
        set(value) = prefs.edit { putInt(KEY_CONTROL_HINTS_SHOWN, value) }

    companion object {
        private const val KEY_SETUP_FILES_DONE = "setup_files_done"
        private const val KEY_STAGED_BIOS_REGION = "staged_bios_region"
        private const val KEY_INSTALLED_BIOS_DIR = "installed_bios_dir"
        private const val KEY_ROM_URI = "rom_uri"
        private const val KEY_ROM_TITLE = "rom_title"
        private const val KEY_ROM_GAME_ID = "rom_game_id"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_RECONFIGURE = "reconfigure_requested"
        private const val KEY_DEFAULTS_VERSION = "defaults_version"
        private const val KEY_CONTROL_HINTS_SHOWN = "control_hints_shown"
    }
}
