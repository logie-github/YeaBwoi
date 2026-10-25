// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.core

/**
 * Legion's game options (the launcher's **Game options**): small changes to the game, each on or
 * off, more than an add-on can do and much less than a module. Each is a setting of the realm's
 * (`App.*` in worldserver.conf, read by the app's patch of LegionCore:
 * warcraft/felsong/patches/0009-App-game-options.patch), written at every start of the realm, so
 * a switch takes effect at the realm's next start. Nothing of the client's files changes.
 *
 * - [UNLOCK_ALL_CLASSES]: death knights and demon hunters from the start (the client otherwise asks
 *   for a character of 55, or of 70, on the account first).
 * - [UNLOCK_ALLIED_RACES]: the allied races without their reputation or quest (their achievement
 *   and their expansion taken as had).
 * - [NO_UNDERWEAR]: the underwear sections of the character table sent to the game without their
 *   textures (as hotfixes of their rows), so the skin shows where nothing is worn.
 */
enum class FelsongGameOptions(val key: String, val conf: String, val default: Boolean) {
    UNLOCK_ALL_CLASSES("unlock_all_classes", "App.UnlockAllClasses", false),
    UNLOCK_ALLIED_RACES("unlock_allied_races", "App.UnlockAlliedRaces", false),
    NO_UNDERWEAR("no_underwear", "App.NoUnderwear", false);

    companion object {
        /** worldserver.conf's lines for the options [on]. */
        fun conf(on: Set<FelsongGameOptions>): Map<String, String> = entries.associate { it.conf to if (it in on) "1" else "0" }
    }
}
