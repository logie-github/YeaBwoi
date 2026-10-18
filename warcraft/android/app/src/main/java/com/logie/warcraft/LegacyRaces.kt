// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import com.logie.warcraft.client.HdPatches
import java.io.File

/**
 * What's left of the custom races on a phone that had them (versions 1.0.44 to 1.0.48 had a Races
 * switch; it's gone): their folder in the client (`Data/patch-Z.MPQ`, or set aside, or half
 * downloaded), the HD look's files they set aside, the patched executable they needed, and the
 * switch's settings. The realm's part (their SQL in the world database, their files in
 * `files/races`) goes at the realm's next start (`ctl.sh races`, [Server]). Characters made with
 * them stay in the characters database, left out of the character list (the realm lists only the
 * game's own races).
 */
object LegacyRaces {
    private const val FOLDER = "patch-Z.MPQ"
    private const val PREFS = "mods"
    private val KEYS = listOf("races", "races_installed", "races_client", "races_server", "races_part", "races_keep")

    /** The realm still has their files (taken out at its next start). */
    fun inRealm(context: Context): Boolean =
        WowFiles.races(context).exists() || File(WowFiles.root(context), "data-races").exists()

    /** Takes their part of the client out, once. Background thread. */
    fun cleanClient(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (KEYS.none { prefs.contains(it) }) return
        val data = File(WowFiles.client(context), "Data")
        // The folder by that name is theirs once the package was put in; a folder of the user's own
        // by that name was moved aside (.user) and comes back.
        // (Now the name of the Skyborne's patch, made by the realm: that one stays.)
        if (prefs.contains("races_client") && !File(data, "$FOLDER.skyborne").exists()) File(data, FOLDER).deleteRecursively()
        File(data, "$FOLDER.off").deleteRecursively()
        File(data, "$FOLDER.part").deleteRecursively()
        val user = File(data, "$FOLDER.user")
        if (user.isDirectory && !File(data, FOLDER).exists()) user.renameTo(File(data, FOLDER))
        ClientMods.bringBackSetAside(context)
        // The patched executable was theirs too while no HD look was in.
        if (ClientMods.hdInstalled(context) == HdPatches.Choice.OFF) File(WowFiles.client(context), ClientMods.PATCHED_EXE).delete()
        prefs.edit().apply { KEYS.forEach { remove(it) } }.apply()
    }
}
