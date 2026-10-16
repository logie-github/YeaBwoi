// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.core

/**
 * Turtle WoW's realm on the phone: its ports, its programs' log lines the app watches, and the
 * settings the launcher offers, all as Tortoise-WoW defines them.
 */
object TurtleRealm {
    /** Turtle's MariaDB: not ChromieCraft's 3310. Only on 127.0.0.1. */
    const val DB_PORT = 3311

    /** The game's login port. ChromieCraft's login server uses it too: one realm runs at a time. */
    const val REALMD_PORT = 3724

    /** Tortoise's world port (ChromieCraft's are 8085 and Wrath Remix's 8086). */
    const val WORLD_PORT = 8090

    /** The ports the other games' servers use (warcraft/server/ctl.sh, Server.kt). */
    val OTHER_PORTS = mapOf(3310 to "ChromieCraft's database", 3724 to "ChromieCraft's login server", 8085 to "ChromieCraft's world server", 8086 to "Wrath Remix's world server")

    /** The ports Turtle's realm listens on. */
    val PORTS = listOf(DB_PORT, REALMD_PORT, WORLD_PORT)

    /** Of [PORTS], the ones another game's server also needs, so they can't run at the same time. */
    val SHARED_PORTS: List<Int> get() = PORTS.filter { it in OTHER_PORTS }

    /** mangosd's line once the world is loaded (src/game/World.cpp). */
    val READY = Regex("World server is up and running")

    /** Lines that mean the world server won't come up, and why (the app stops waiting). */
    enum class Fatal(val pattern: Regex) {
        /** The server's data is missing or from another client (World.cpp's check of the start areas). */
        DATA(Regex("Correct \\*\\.map files not found|is non-compatible version|vmap file .* is missing")),

        /** A database update failed (the updater then waits for a key press on its console). */
        UPDATE(Regex("DB AutoUpdater FAILED|Migration .* failed to apply")),

        /** It couldn't reach its databases. */
        DATABASE(Regex("Cannot connect to world database|database not specified")),

        /** The DBC files are missing or don't fit this server. */
        DBC(Regex("required \\*\\.dbc files")),
        ;

        companion object {
            fun of(line: String): Fatal? = entries.firstOrNull { it.pattern.containsMatchIn(line) }
        }
    }

    /**
     * realmd.conf for a realm on the phone, set at each start. Tortoise's login server drops a
     * client that asks for the realm list twice within MinRealmListDelay seconds (1), with no
     * reason given: the client says "Disconnected from server". A guard for a public server; on
     * the phone the answer comes at once and the client asks again within the second.
     */
    val REALMD_SETTINGS = mapOf("MinRealmListDelay" to "0")

    /**
     * Tortoise's anticheat, off. The world server reads it from `anticheat.conf` in the folder it
     * runs in (run/), which the realm package doesn't have, so its own defaults turned it on. On,
     * it sets up Warden for each player who connects: Warden's RC4 needs OpenSSL's legacy module,
     * which nothing told OpenSSL where to find, so the world server crashed as the player came in
     * (SIGSEGV after the tutorials query; "Disconnected from server" on the phone); and with no
     * Warden module to send, it would hold the character list back for good
     * (WardenWin::SetCharEnumPacket). Off, the world server's own addon handler still answers
     * Turtle's signed addons. A realm on the phone for its owner has nobody to catch.
     */
    const val ANTICHEAT_CONF = "[AnticheatConf]\nEnable = 0\n"

    /** OpenSSL's modules in the realm package (its legacy one, RC4), relative to its server folder. */
    const val OPENSSL_MODULES = "lib/ossl-modules"

    /** A setting in mangosd.conf the launcher offers, with Tortoise's default. */
    data class Setting(val key: String, val default: String, val choices: List<String>, val group: Group)

    enum class Group { RATES, WORLD, NETWORK }

    /**
     * What the launcher offers (each key and default checked against Tortoise's
     * src/mangosd/mangosd.conf.dist.in). The bots are TortoiseBots' ([botSettings]).
     */
    val SETTINGS = listOf(
        Setting("Rate.XP.Kill", "1", listOf("1", "1.5", "2", "3", "5"), Group.RATES),
        Setting("Rate.XP.Quest", "1", listOf("1", "1.5", "2", "3", "5"), Group.RATES),
        Setting("Rate.XP.Explore", "1", listOf("1", "1.5", "2", "3", "5"), Group.RATES),
        Setting("Rate.Drop.Money", "1", listOf("1", "1.5", "2", "3"), Group.RATES),
        Setting("Rate.Reputation.Gain", "1", listOf("1", "1.5", "2", "3"), Group.RATES),
        Setting("Rate.Rest.InGame", "1", listOf("1", "2", "3"), Group.RATES),
        Setting("Rate.Rest.Offline.InTavernOrCity", "1", listOf("1", "2", "3"), Group.RATES),
        Setting("Rate.Rest.Offline.InWilderness", "1", listOf("1", "2", "3"), Group.RATES),
        Setting("StartPlayerLevel", "1", listOf("1", "10", "20", "30", "40", "50", "60"), Group.WORLD),
    )

    /**
     * The bots: TortoiseBots, compiled into the world server. Hired companions (at an inn) and your
     * own alts as bots are always there; this is how many bots live in the world on their own (its
     * pool of random bots, which start at level 1 and quest, level, travel and trade). Its template
     * has 500, an average PC's; a phone gets [WORLD_BOTS_DEFAULT]. 0: none in the world.
     */
    const val WORLD_BOTS_DEFAULT = 40
    val WORLD_BOTS_CHOICES = listOf(0, 20, 40, 80, 150)

    /** aiplayerbot.conf's keys (TortoiseBots' template, ai/playerbot/aiplayerbot.conf.dist.in) for [worldBots] world bots. */
    fun botSettings(worldBots: Int): Map<String, String> {
        val count = worldBots.coerceIn(0, WORLD_BOTS_CHOICES.max())
        return linkedMapOf(
            "AiPlayerbot.Enabled" to "1",
            "AiPlayerbot.MinRandomBots" to "$count",
            "AiPlayerbot.MaxRandomBots" to "$count",
            "AiPlayerbot.RandomBotAutologin" to if (count > 0) "1" else "0",
            // Its telemetry (a UDP port) stays off.
            "AiPlayerbot.Observability" to "0",
        )
    }
}
