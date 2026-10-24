// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.core

/**
 * Felsong's realm on the phone (LegionCore 7.3.5): its ports, its programs' log lines the app
 * watches, and the settings the launcher offers, all as LegionCore defines them
 * (warcraft/felsong/server/ctl.sh sets the same ports).
 */
object FelsongRealm {
    /** The client build the realm is set up for: 7.3.5's last. */
    const val CLIENT_BUILD = 26972

    /** Felsong's MariaDB: not ChromieCraft's 3310 or Turtle's 3311. Only on 127.0.0.1. */
    const val DB_PORT = 3312

    /** Battle.net's port, which Legion's client connects to first (TLS): the client's own, fixed. */
    const val BNET_PORT = 1119

    /** The Battle.net server's REST login (the client's login form posts to it). */
    const val REST_PORT = 8081

    /** The world and instance ports (ChromieCraft's are 8085 and 8086, Wrath Remix's 8086, Turtle's 8090). */
    const val WORLD_PORT = 8095
    const val INSTANCE_PORT = 8096

    /** The ports the other games' servers use (warcraft/server/ctl.sh, warcraft/turtle/server/ctl.sh). */
    val OTHER_PORTS = mapOf(
        3310 to "ChromieCraft's database", 3724 to "ChromieCraft's login server", 8085 to "ChromieCraft's world server",
        8086 to "Wrath Remix's world server", 3311 to "Turtle WoW's database", 8090 to "Turtle WoW's world server",
    )

    /** The ports Felsong's realm listens on. */
    val PORTS = listOf(DB_PORT, BNET_PORT, REST_PORT, WORLD_PORT, INSTANCE_PORT)

    /** Of [PORTS], the ones another game's server also needs: none, so Felsong's realm can run beside them. */
    val SHARED_PORTS: List<Int> get() = PORTS.filter { it in OTHER_PORTS }

    /** The world server's line once the world is loaded (worldserver/Main.cpp). */
    val READY = Regex("\\(worldserver-daemon\\) ready\\.\\.\\.|World initialized in")

    /** Lines that mean the world server won't come up, and why (the app stops waiting). */
    enum class Fatal(val pattern: Regex) {
        /** The server's data is missing or from another client (DB2Stores.cpp). */
        DBC(Regex("required \\*\\.db2 files|not found or not compatible|Incorrect DataDir value")),

        /** Map files missing or of another version (Map.cpp, MapManager). */
        DATA(Regex("Map file '[^']*' is from an incompatible|map file .* does not exist|Correct \\*\\.map files not found|vmap file .* is missing")),

        /** A database update failed (DBUpdater.cpp, DatabaseLoader.cpp). */
        UPDATE(Regex("Applying of file .* failed|Could not update the .* database|Could not populate the .* database")),

        /** It couldn't reach its databases. */
        DATABASE(Regex("DatabasePool .* NOT opened|not specified in configuration file|Cannot connect to")),
        ;

        companion object {
            fun of(line: String): Fatal? = entries.firstOrNull { it.pattern.containsMatchIn(line) }
        }
    }

    /** OpenSSL's modules in the realm package (its legacy one, RC4: Legion's world connection), relative to its server folder. */
    const val OPENSSL_MODULES = "lib/ossl-modules"

    /** The realm's name, as the client's realm list shows it (auth.realmlist). */
    const val REALM_NAME = "Legion"

    /** A setting in worldserver.conf the launcher offers, with the app's default for a realm on a phone. */
    data class Setting(val key: String, val default: String, val choices: List<String>, val group: Group)

    enum class Group { RATES, WORLD, SOLO }

    /**
     * What the launcher offers (each key checked against LegionCore's worldserver.conf.dist). The
     * rates default to Felsong's own (×1.5 XP on its first realm; Felsong+ is ×10 XP, ×2 reputation).
     */
    val SETTINGS = listOf(
        Setting("Rate.XP.Kill", "1.5", listOf("1", "1.5", "2", "3", "5", "10"), Group.RATES),
        Setting("Rate.XP.Quest", "1.5", listOf("1", "1.5", "2", "3", "5", "10"), Group.RATES),
        Setting("Rate.XP.Explore", "1.5", listOf("1", "1.5", "2", "3", "5", "10"), Group.RATES),
        Setting("Rate.Drop.Money", "1", listOf("1", "1.5", "2", "3"), Group.RATES),
        Setting("Rate.Reputation.Gain", "1", listOf("1", "1.5", "2", "3"), Group.RATES),
        Setting("Rate.Honor", "1", listOf("1", "1.5", "2", "3"), Group.RATES),
        Setting("StartPlayerLevel", "1", listOf("1", "10", "20", "60", "90", "98", "100"), Group.WORLD),
        Setting("StartPlayerMoney", "0", listOf("0", "10000", "1000000", "100000000"), Group.WORLD),
        Setting("Solocraft.Enable", "1", listOf("0", "1"), Group.SOLO),
    )

    /** worldserver.conf keys set at every start whatever the user chose: one player on a phone. */
    val FIXED_SETTINGS = mapOf(
        "GameType" to "0",
        "DungeonFinder.DebugJoin" to "1",
        "Warden.Enabled" to "0",
    )

    /** The keys the server's data allows: line of sight and heights need vmaps, pathfinding mmaps. */
    fun dataSettings(parts: Set<String>): Map<String, String> = mapOf(
        "vmap.enableLOS" to if ("vmaps" in parts) "1" else "0",
        "vmap.enableHeight" to if ("vmaps" in parts) "1" else "0",
        "vmap.enableIndoorCheck" to if ("vmaps" in parts) "1" else "0",
        "mmap.enablePathFinding" to if ("mmaps" in parts) "1" else "0",
    )
}
