// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.spp

import com.logie.warcraft.spp.core.SppInstall
import com.logie.warcraft.spp.core.SppRealm
import com.logie.warcraft.tww.core.LocalGameWindow
import com.logie.warcraft.tww.core.TwwInstall
import com.logie.warcraft.tww.core.TwwSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.*

class SppInstallTest {
    private fun text(root: File, path: String, value: String = "test") = File(root, path).apply { parentFile.mkdirs(); writeText(value) }
    private fun exe(root: File, path: String, build: Int = 26972) {
        val bytes = ByteArray(256)
        bytes[0] = 0x4d; bytes[1] = 0x5a
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x3c, 64); putInt(64, 0x00004550); putShort(68, 0x8664.toShort())
            putInt(128, 0xfeef04bd.toInt()); putInt(132, 0x10000)
            putInt(136, (7 shl 16) or 3); putInt(140, (5 shl 16) or build)
        }
        File(root, path).apply { parentFile.mkdirs(); writeBytes(bytes) }
    }
    private inline fun temporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("spp-test-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun server(root: File): File {
        val core = File(root, "wrapper/spp-legion")
        for (folder in listOf("Bin", "bin64")) {
            exe(core, "Server/$folder/bnetserver.exe"); exe(core, "Server/$folder/worldserver.exe")
        }
        for (tool in listOf("mysql", "mysqld", "mysqladmin")) exe(core, "Server/Database/bin/$tool.exe")
        text(core, "Server/Database/my-large.ini", "[mysqld]\nport=3310\n")
        text(core, "Server/Database/bin/my.ini", "[mysqld]\nport=3310\n")
        for (schema in listOf("mysql", "realmd", "characters", "world", "hotfixes")) text(core, "Server/Database/data/$schema/table.frm")
        text(core, "Realms/1/Settings/login.conf", "LoginDatabaseInfo=\"127.0.0.1;3310;root;test;realmd\"\nBattlenetPort=1119\nLoginREST.Port=8081\n")
        val world = "RealmID=1\nDataDir=\"../../Server/Data\"\nLoginDatabaseInfo=\"127.0.0.1;3310;root;test;realmd\"\nWorldDatabaseInfo=\"127.0.0.1;3310;root;test;world\"\nCharacterDatabaseInfo=\"127.0.0.1;3310;root;test;characters\"\nHotfixDatabaseInfo=\"127.0.0.1;3310;root;test;hotfixes\"\nWorldServerPort=8085\n"
        text(core, "Realms/1/Settings/world.conf", world)
        text(core, "Realms/2/Settings/world.conf", world.replace("RealmID=1", "RealmID=2"))
        text(core, "Launcher.bat", "cd Realms/1\nstart ../../Server/bin64/bnetserver.exe\nstart ../../Server/bin64/worldserver.exe\n")
        text(core, "Server/Data/maps/0000.map", "MAPSformat-version")
        text(core, "Server/Data/vmaps/0000.vmtree", "VMAP_4.0contents")
        text(core, "Server/Data/mmaps/0000.mmap", "x".repeat(28))
        text(core, "Server/Data/mmaps/00000000.mmtile", "PAMM" + "x".repeat(32))
        text(core, "Server/Data/dbc/Spell.db2", "WDB5data")
        return core
    }
    @Test fun choosesOriginalSppRootAndIts64BitRealmInsteadOfBestCoreOrTemplates() = temporary { root ->
        val core = server(root)
        text(root, "wrapper/spp-bestcore/Realms/1/Settings/world.conf", "Other distribution")
        assertEquals(core, SppInstall.serverRoot(root))
        val report = SppInstall.inspect(File(root, "client"), core)
        val detected = assertNotNull(report.detected)
        assertEquals(File(core, "Server/bin64/worldserver.exe"), detected.worldExecutable)
        assertEquals(File(core, "Realms/1/Settings/world.conf"), detected.worldConfig)
        assertEquals(File(core, "Server/Database/my-large.ini"), detected.databaseConfig)
        assertEquals(File(core, "Server/Database/data").canonicalFile, detected.databaseProfile?.dataDirectory)
        assertEquals(File(core, "Server/Data").canonicalFile, detected.dataDirectory)
        assertEquals(TwwInstall.Status.INSTALLED, report.database.status)
        assertEquals(TwwInstall.Status.INSTALLED, report.maps.status)
        val command = detected.commands.getValue(TwwInstall.Role.WORLD)
        assertEquals(listOf("-c", detected.worldConfig!!.path), command.arguments)
        assertEquals(File(core, "Realms/1").canonicalFile, command.workingDirectory)
        assertTrue(TwwSession.launch(command, core, "world").contains("S:\\Realms\\1\\Settings\\world.conf"))
    }
    @Test fun unrecognizedExecutablesCannotAttestToThePinnedSppBuild() = temporary { root ->
        val core = server(root)
        text(core, SppRealm.SPEC.proofFile, "build=26972\nsource=inspected-server-release\n")
        val report = SppInstall.inspect(File(root, "client"), core)
        assertFalse(report.canBootstrap)
        assertEquals(TwwInstall.Status.UNVERIFIED, report.core.status)
        assertTrue(report.core.details.any { "checksums differ" in it })
    }
    @Test fun single64BitClientIsChosenWithoutConfusingIts32BitAndDownloadAlternatives() = temporary { root ->
        val folder = File(root, "wow 26972")
        exe(folder, "Wow-64.exe"); exe(folder, "Wow.exe"); exe(folder, "WoW TEST-Fstorm Download.exe")
        text(folder, "Data/data/000.idx"); text(folder, "Data/data/data.000")
        assertEquals(folder, SppInstall.clientRoot(root))
        val report = SppInstall.inspect(folder, File(root, "server"))
        assertEquals(TwwInstall.Status.INSTALLED, report.client.status)
        assertEquals(26972, report.clientBuild?.build)
        exe(folder, "Wow-64.exe", 26365)
        assertEquals(TwwInstall.Status.INCOMPATIBLE, SppInstall.inspect(folder, File(root, "server")).client.status)
    }
    @Test fun unknownMiniClientCannotClaimThatStreamingOrOfflineAssetsAreInstalled() = temporary { root ->
        exe(root, "Wow-64.exe")
        text(root, "cdns"); text(root, "versions")
        val report = SppInstall.inspect(root, File(root, "server"))
        assertEquals(TwwInstall.Status.INCOMPLETE, report.client.status)
        assertFalse(report.streamingClient)
    }
    @Test fun gameAutomationRejectsServerAndRuntimeConsoles() {
        for (name in listOf("cmd.exe", "ConsoleWindowClass", "worldserver.exe", "bnetserver.exe", "explorer.exe"))
            assertFalse(LocalGameWindow.isClient(name), name)
        for (name in listOf("GxWindowClass", "SPP_Wow-64.exe", "Wow.exe")) assertTrue(LocalGameWindow.isClient(name), name)
    }
}
