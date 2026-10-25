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
 * - [UNDEAD_PALADINS]: undead paladins at character creation: the pair added to the game's race
 *   and class table (hotfixes.char_base_info, sent as a hotfix), the undead warrior's start and the
 *   blood elf paladin's action bars copied for it ([sql], at every start of the realm); its outfit
 *   is another race's paladin's, and the priest trainers teach them a paladin trainer's spells
 *   (patch 0010, reading App.UndeadPaladins).
 */
enum class FelsongGameOptions(val key: String, val conf: String, val default: Boolean) {
    UNLOCK_ALL_CLASSES("unlock_all_classes", "App.UnlockAllClasses", false),
    UNLOCK_ALLIED_RACES("unlock_allied_races", "App.UnlockAlliedRaces", false),
    NO_UNDERWEAR("no_underwear", "App.NoUnderwear", false),
    UNDEAD_PALADINS("undead_paladins", "App.UndeadPaladins", false);

    companion object {
        /** The options the game itself keeps a copy of (its Cache of the hotfixes it was sent). */
        val CLIENT_CACHED = setOf(NO_UNDERWEAR, UNDEAD_PALADINS)

        /** CharBaseInfo.db2's table hash (7.3.5, 26972) and the row the realm adds to it. */
        const val CHAR_BASE_INFO_HASH = 0x3067A8F8L
        const val UNDEAD_PALADIN_ROW = 1000
        const val UNDEAD_PALADIN_HOTFIX = 2_000_000_001L
        private const val UNDEAD = 5
        private const val PALADIN = 2
        private const val WARRIOR = 1
        private const val BLOOD_ELF = 10

        /**
         * The databases' side of the options [on], run as the realm starts (before its world server):
         * each option's rows put in, or taken out when it's off.
         */
        fun sql(on: Set<FelsongGameOptions>): String = buildString {
            append("DELETE FROM hotfixes.hotfix_data WHERE Id = $UNDEAD_PALADIN_HOTFIX;\n")
            append("DELETE FROM hotfixes.char_base_info WHERE ID = $UNDEAD_PALADIN_ROW;\n")
            append("DELETE FROM world.playercreateinfo WHERE race = $UNDEAD AND class = $PALADIN;\n")
            append("DELETE FROM world.playercreateinfo_action WHERE race = $UNDEAD AND class = $PALADIN;\n")
            if (UNDEAD_PALADINS in on) {
                append("INSERT INTO hotfixes.char_base_info (ID, RaceID, ClassID) VALUES ($UNDEAD_PALADIN_ROW, $UNDEAD, $PALADIN);\n")
                append("INSERT INTO hotfixes.hotfix_data (Id, TableHash, RecordId, Deleted) VALUES ($UNDEAD_PALADIN_HOTFIX, $CHAR_BASE_INFO_HASH, $UNDEAD_PALADIN_ROW, 0);\n")
                copy("world.playercreateinfo", "race = $UNDEAD AND class = $WARRIOR", "class = $PALADIN")
                copy("world.playercreateinfo_action", "race = $BLOOD_ELF AND class = $PALADIN", "race = $UNDEAD")
            }
        }

        /** Rows of [table] matching [where] copied with [change] (whatever the table's columns). */
        private fun StringBuilder.copy(table: String, where: String, change: String) {
            append("DROP TEMPORARY TABLE IF EXISTS app_copy;\n")
            append("CREATE TEMPORARY TABLE app_copy SELECT * FROM $table WHERE $where;\n")
            append("UPDATE app_copy SET $change;\n")
            append("INSERT INTO $table SELECT * FROM app_copy;\n")
            append("DROP TEMPORARY TABLE app_copy;\n")
        }

        /** worldserver.conf's lines for the options [on]. */
        fun conf(on: Set<FelsongGameOptions>): Map<String, String> = entries.associate { it.conf to if (it in on) "1" else "0" }
    }
}
