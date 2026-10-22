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
    const val CLIENT_URL = "https://drive.google.com/file/d/1tCP3mJUqsDK1H4zug4bQZOUrAzAX30ud/view"
    const val CLIENT_SIZE = 1_124_676_989L
    const val CLIENT_SHA256 = "c6f13b5696c90c0e5055acaa35db286cb339f7fc4733e74b41bea30ab7d0859f"
    const val SERVER_URL = "https://drive.google.com/file/d/1AUYiD-2NpamLFzxJDBNVRcO7FBg9YwIi/view"
    const val SERVER_SIZE = 5_444_369_208L
    const val SERVER_SHA256 = "c161a7289b907ba86471374253f7b7844a6f49377722ca706da3b40f90d077e0"
    const val CLIENT_EXE = "SPP_Wow-64.exe"
    const val CLIENT_EXE_SHA256 = "be484c9a3bd8e5de173e9a8e185a9b6636406d69a9122987f6f2c1d512b2bec5"
    const val STREAMING_EXE_SHA256 = "7f86d310c9db965110c09d322322b6b4593c7dc250afa07278af7fdc0e1eb59a"
    const val DATABASE_PORT = 3312
    const val WORLD_PORT = 8095

    val SPEC = LocalRealmSpec(7, 3, 5, BUILD,
        streamingExecutableHashes = setOf(STREAMING_EXE_SHA256, CLIENT_EXE_SHA256),
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
