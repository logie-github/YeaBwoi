// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp.core

import com.logie.warcraft.tww.core.LocalRealmSpec
import com.logie.warcraft.tww.core.LocalServerLayout

/** The inspected original SPP AshamaneCore release; BestCore is a different distribution. */
object SppRealm {
    const val ID = "spp-legion"
    const val DISPLAY_NAME = "Legion — 7.3.5"
    const val DISTRIBUTION = "Single Player Project · AshamaneCore"
    const val BUILD = 26972
    const val CLIENT_URL = "http://ashamanecore.com/dicpics/LegionClient.zip"
    const val CLIENT_SIZE = 42_288_827_614L
    // No publisher archive digest was supplied. Length, ZIP CRCs and build are checked.
    val CLIENT_SHA256: String? = null
    const val SERVER_URL = "https://drive.google.com/file/d/1AUYiD-2NpamLFzxJDBNVRcO7FBg9YwIi/view"
    const val SERVER_SIZE = 5_444_369_208L
    const val SERVER_SHA256 = "c161a7289b907ba86471374253f7b7844a6f49377722ca706da3b40f90d077e0"
    const val CLIENT_EXE = "SPP_Wow-64.exe"
    const val CLIENT_EXE_SHA256 = "be484c9a3bd8e5de173e9a8e185a9b6636406d69a9122987f6f2c1d512b2bec5"
    const val DATABASE_PORT = 3312
    const val WORLD_PORT = 8095
    /**
     * The world server's second port: the repack's is 8086, which Wrath Remix's world server
     * listens on (switching to Legion leaves it running), so the world would load and then fail
     * to open its ports. Beside the world's own, as Felsong had them.
     */
    const val INSTANCE_PORT = 8096

    /**
     * The game's graphics on a phone to start with (Legion's lowest preset, no anti-aliasing, 30
     * frames a second, 5 behind other apps), written only where Config.wtf has none yet.
     */
    val CLIENT_GRAPHICS = mapOf(
        "graphicsQuality" to "1",
        "RAIDgraphicsQuality" to "1",
        "MSAAQuality" to "0",
        "ffxAntiAliasingMode" to "0",
        "maxFPS" to "60",
        "maxFPSBk" to "5",
    )

    /** The allied races 7.3.5 has: Nightborne, Highmountain Tauren, Void Elf, Lightforged Draenei. */
    val ALLIED_RACES = listOf(27, 28, 29, 30)

    /**
     * Every race and class from the start, at the user's choice, set in world.conf at every start:
     * Demon Hunters without first having a level-70 character (and up to 16, the most there is), and
     * nothing that would stop or move a new character (all off in the repack).
     */
    val WORLD_SETTINGS = mapOf(
        "CharacterCreating.MinLevelForDemonHunter" to "0",
        "DemonHuntersPerRealm" to "16",
        "CharacterCreating.Disabled" to "0",
        "CharacterCreating.Disabled.RaceMask" to "0",
        "CharacterCreating.Disabled.ClassMask" to "0",
        "Force.Starting.Level" to "0",
        "Force.Teleport" to "0",
        "InstanceServerPort" to INSTANCE_PORT.toString(),
    )

    /**
     * The allied races' rows as character creation wants them (no achievement asked first), at every
     * start before the world server loads them: as the repack has them, so it only guards against an
     * install that doesn't. Their expansion (Battle for Azeroth) the accounts have ([LOGIN_STARTUP_SQL]).
     */
    val WORLD_STARTUP_SQL =
        "INSERT IGNORE INTO race_unlock_requirement (raceID, expansion, achievementId) VALUES " +
            ALLIED_RACES.joinToString(",") { "($it,${SppAccountRules.ACCOUNT_EXPANSION},0)" } + ";\n" +
            "UPDATE race_unlock_requirement SET achievementId = 0 WHERE raceID IN (${ALLIED_RACES.joinToString(",")}) AND achievementId <> 0;\n"

    /**
     * Every account with the allied races: SPP's core lets an account create them only with Battle for
     * Azeroth's expansion ([SppAccountRules.ACCOUNT_EXPANSION]); accounts the app made before had Legion's.
     * On the authentication database at every start.
     */
    val LOGIN_STARTUP_SQL =
        "UPDATE account SET expansion = ${SppAccountRules.ACCOUNT_EXPANSION} WHERE expansion < ${SppAccountRules.ACCOUNT_EXPANSION};\n"

    val SPEC = LocalRealmSpec(7, 3, 5, BUILD,
        minimumCascBytes = 40_000_000_000L,
        executableNames = setOf(CLIENT_EXE.lowercase()),
        db2Prefixes = listOf("WDB", "WDC"),
        authenticationSha256 = "8bb9eea52f8f7421b75f9fd92311bea74ef3f6d001a1986dd0cfee96449675c3",
        worldSha256 = "d54437a169b9bc00d56e809ace78629e78cbf2fdb7008c5feca9374df25b7cf5",
        serverLayout = LocalServerLayout(
            authenticationExecutable = "Server/bin64/bnetserver.exe",
            worldExecutable = "Server/bin64/worldserver.exe",
            authenticationConfig = "Realms/1/Settings/login.conf",
            worldConfig = "Realms/1/Settings/world.conf",
            workingDirectory = "Realms/1",
            databaseExecutable = "Server/Database/bin/mysqld.exe",
            databaseConfig = "Server/Database/my-large.ini",
            databaseData = "Server/Database/data",
            launcher = "Launcher.bat",
        ))
}
