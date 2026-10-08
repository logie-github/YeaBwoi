// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** A modded version of the game: a patched ISO made by Dolphin XD, or one the player added. */
data class XdMod(
    val id: String,
    val name: String,
    /** File path (patched by us) or content URI (added by the player). */
    val path: String,
    val gameId: String,
    /** True if Dolphin XD made the file and should delete it with the mod. */
    val owned: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("path", path)
        .put("gameId", gameId)
        .put("owned", owned)

    companion object {
        fun fromJson(json: JSONObject) = XdMod(
            id = json.getString("id"),
            name = json.getString("name"),
            path = json.getString("path"),
            gameId = json.getString("gameId"),
            owned = json.getBoolean("owned"),
        )
    }
}

/** Dolphin XD's own state: the game file, installed mods, and how far setup got. */
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

    /** The player's original Pokémon XD disc image. Mods are always patched from this. */
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

    var defaultsVersion: Int
        get() = prefs.getInt(KEY_DEFAULTS_VERSION, 0)
        set(value) = prefs.edit { putInt(KEY_DEFAULTS_VERSION, value) }

    /** Play at 60 FPS (see [XdFrameRate]). On unless the player turned it off. */
    var sixtyFps: Boolean
        get() = prefs.getBoolean(KEY_SIXTY_FPS, true)
        set(value) = prefs.edit { putBoolean(KEY_SIXTY_FPS, value) }

    var controlHintsShown: Int
        get() = prefs.getInt(KEY_CONTROL_HINTS_SHOWN, 0)
        set(value) = prefs.edit { putInt(KEY_CONTROL_HINTS_SHOWN, value) }

    var mods: List<XdMod>
        get() {
            val json = prefs.getString(KEY_MODS, null) ?: return emptyList()
            return try {
                val array = JSONArray(json)
                List(array.length()) { XdMod.fromJson(array.getJSONObject(it)) }
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(value) = prefs.edit(commit = true) {
            putString(KEY_MODS, JSONArray(value.map { it.toJson() }).toString())
        }

    /** [ORIGINAL] or the id of the mod that Play starts. */
    var activeVersionId: String
        get() = prefs.getString(KEY_ACTIVE_VERSION, ORIGINAL) ?: ORIGINAL
        set(value) = prefs.edit(commit = true) { putString(KEY_ACTIVE_VERSION, value) }

    val activeMod: XdMod?
        get() = activeVersionId.takeIf { it != ORIGINAL }?.let { id -> mods.find { it.id == id } }

    /** What Play starts. */
    val activeGamePath: String?
        get() = activeMod?.path ?: romUri

    val activeGameId: String?
        get() = activeMod?.gameId ?: romGameId

    fun gameIdOf(versionId: String): String? =
        if (versionId == ORIGINAL) romGameId else mods.find { it.id == versionId }?.gameId

    /** The memory card's contents (see [XdSaveStates]) when save state [slot] was made. */
    fun stateCardFingerprint(versionId: String, slot: Int): String? =
        prefs.getString("$KEY_STATE_CARD_PREFIX$versionId.$slot", null)

    fun setStateCardFingerprint(versionId: String, slot: Int, fingerprint: String) =
        prefs.edit(commit = true) { putString("$KEY_STATE_CARD_PREFIX$versionId.$slot", fingerprint) }

    companion object {
        const val ORIGINAL = "original"

        private const val KEY_SETUP_FILES_DONE = "setup_files_done"
        private const val KEY_STAGED_BIOS_REGION = "staged_bios_region"
        private const val KEY_INSTALLED_BIOS_DIR = "installed_bios_dir"
        private const val KEY_ROM_URI = "rom_uri"
        private const val KEY_ROM_TITLE = "rom_title"
        private const val KEY_ROM_GAME_ID = "rom_game_id"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_DEFAULTS_VERSION = "defaults_version"
        private const val KEY_CONTROL_HINTS_SHOWN = "control_hints_shown"
        private const val KEY_SIXTY_FPS = "sixty_fps"
        private const val KEY_MODS = "mods"
        private const val KEY_ACTIVE_VERSION = "active_version"
        private const val KEY_STATE_CARD_PREFIX = "state_card."
    }
}
