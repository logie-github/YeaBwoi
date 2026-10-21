// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.tww

import com.logie.warcraft.tww.core.TwwInstall
import com.logie.warcraft.tww.core.TwwRealm
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwwInstallTest {
    private fun temporary(block: (File) -> Unit) {
        val folder = Files.createTempDirectory("tww-test-").toFile()
        try { block(folder) } finally { folder.deleteRecursively() }
    }

    private fun file(root: File, name: String, text: String = "data"): File = File(root, name).apply { parentFile.mkdirs(); writeText(text) }

    private fun exe(root: File, name: String, build: Int = 63704): File {
        val bytes = ByteArray(256)
        bytes[0] = 0x4d; bytes[1] = 0x5a
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x3c, 64); putInt(64, 0x00004550); putShort(68, 0x8664.toShort())
            putInt(128, 0xfeef04bd.toInt()); putInt(132, 0x10000)
            putInt(136, (11 shl 16) or 2); putInt(140, (5 shl 16) or build)
        }
        return File(root, name).apply { parentFile.mkdirs(); writeBytes(bytes) }
    }

    private fun client(root: File, build: Int = 63704) {
        exe(root, "_retail_/Wow.exe", build)
        file(root, "Data/data/0000000000.idx")
        file(root, "Data/data/data.000")
        file(root, ".build.info", "Active!DEC:1|Version!STRING:0\n1|11.2.5.63704\n")
    }

    private fun completeServer(server: File) {
        exe(server, "Core/Bnetserver.exe")
        exe(server, "Core/Worldserver.exe")
        exe(server, "MySQL/bin/mysqld.exe")
        file(server, "Core/bnetserver.conf", "LoginDatabaseInfo=\"127.0.0.1;3306;user;p#ss;auth\"\nBattlenetPort=1119\n")
        file(server, "Core/worldserver.conf", "WorldDatabaseInfo=\"127.0.0.1;3306;user;pass;world\"\nCharacterDatabaseInfo=\"127.0.0.1;3306;user;pass;characters\"\nDataDir=\"../Data\"\n")
        file(server, "MySQL/my.ini", "[mysqld]\ndatadir=data\n")
        for (schema in listOf("mysql", "auth", "world", "characters")) file(server, "MySQL/data/$schema/table.ibd")
        file(server, "Data/maps/0000_00_00.map", "MAPSformat-version")
        file(server, "Data/vmaps/0000.vmtree", "VMAP_4.9contents")
        file(server, "Data/mmaps/0000.mmap", "x".repeat(28))
        file(server, "Data/mmaps/00000000.mmtile", "PAMM" + "x".repeat(32))
        file(server, "Data/dbc/Spell.db2", "WDC5data")
    }

    private fun runtimeProof(server: File) {
        fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val properties = Properties().apply {
            setProperty("build", "63704")
            setProperty("source", "authentication-database-build_info")
            setProperty("authenticationSha256", hash(File(server, "Core/Bnetserver.exe")))
            setProperty("worldSha256", hash(File(server, "Core/Worldserver.exe")))
        }
        File(server, ".tww-build-proof.properties").outputStream().use { properties.store(it, "Test runtime build_info result") }
    }

    @Test fun metadataUsesExactExpansionFirstName() {
        assertEquals("The War Within — 11.2.5", TwwRealm.DISPLAY_NAME)
        assertEquals(63704, TwwRealm.CLIENT_BUILD)
    }

    @Test fun executableMismatchCannotBeHiddenByLauncherRecord() = temporary { root ->
        client(File(root, "client"), 64500)
        val report = TwwInstall.inspect(File(root, "client"), File(root, "server"))
        assertEquals(TwwInstall.Status.INCOMPATIBLE, report.client.status)
        assertEquals(64500, report.clientBuild?.build)
        assertFalse(report.ready)
    }

    @Test fun retailImportKeepsSharedCascDataAndDetectsFirestorm() = temporary { root ->
        val client = File(root, "client")
        client(client)
        file(client, "FirestormLauncher.exe")
        val report = TwwInstall.inspect(client, File(root, "server"))
        assertEquals(TwwInstall.Status.INSTALLED, report.client.status)
        assertTrue(report.firestormModified)
        assertEquals(client, TwwInstall.findClientRoot(client))
        assertTrue(report.blockers.any { "Local connection" in it })
    }

    @Test fun activeBuildInfoRowsOnlyAndPeHeaderRequired() = temporary { root ->
        val info = "Active!DEC:1|Version!STRING:0\n0|12.0.0.99999\n1|11.2.5.63704\n"
        assertEquals(63704, TwwInstall.versionFromBuildInfo(info)?.build)
        val invalid = file(root, "Wow.exe", "11.2.5.63704")
        assertNull(TwwInstall.readBuild(invalid))
    }

    @Test fun discoversActualRepackPathsAndReportsMissingDatabase() = temporary { root ->
        val server = File(root, "server")
        exe(server, "Core/Bnetserver.exe")
        exe(server, "Core/Worldserver.exe")
        exe(server, "UniServerZ/core/mysql/bin/mysqld.exe")
        file(server, "Core/bnetserver.conf", "LoginDatabaseInfo = \"127.0.0.1;3306;user;password;draconic_auth\"\nBattlenetPort = 1119\n")
        file(server, "Core/worldserver.conf", "WorldDatabaseInfo = \"127.0.0.1;3306;user;password;draconic_world\"\nCharacterDatabaseInfo = \"127.0.0.1;3306;user;password;draconic_characters\"\nDataDir = \"../Data\"\n")
        file(server, "UniServerZ/core/mysql/my.ini", "[mysqld]\ndatadir=data\nport=3306\n")
        file(server, "README.txt", "Draconic WoW, client build: 63704")
        file(server, "database.sql", "CREATE DATABASE draconic_world;")
        val report = TwwInstall.inspect(File(root, "client"), server)
        val detected = assertNotNull(report.detected)
        assertEquals("Worldserver.exe", detected.worldExecutable?.name)
        assertEquals(63704, detected.documentedBuild)
        assertEquals(File(server, "Data").canonicalFile, detected.dataDirectory)
        assertEquals(3, detected.commands.size)
        assertTrue(detected.commands.getValue(TwwInstall.Role.DATABASE).arguments.single().startsWith("--defaults-file="))
        assertEquals(TwwInstall.Status.INCOMPLETE, report.database.status)
        assertTrue(report.database.missing.any { "draconic_world" in it })
        assertTrue(report.database.details.any { "SQL dump alone" in it })
        assertEquals(server, TwwInstall.findServerRoot(server))
    }

    @Test fun ambiguousServersNeverProduceRunnableProfile() = temporary { root ->
        exe(root, "one/worldserver.exe")
        exe(root, "two/worldserver.exe")
        val detected = assertNotNull(TwwInstall.inspect(File(root, "client"), root).detected)
        assertNull(detected.worldExecutable)
        assertTrue(detected.issues.any { "Ambiguous world executable" in it })
    }

    @Test fun uniformServerRenamedDaemonUsesNormalConfigurationAlongsideRecoveryFiles() = temporary { root ->
        val server = File(root, "server"); completeServer(server)
        val original = File(server, "MySQL/bin/mysqld.exe")
        assertTrue(original.renameTo(File(original.parentFile, "mysqld_z.exe")))
        file(server, "MySQL/my_skip_grant.ini", "[mysqld]\nskip-grant-tables\n")
        val detected = assertNotNull(TwwInstall.inspect(File(root, "client"), server).detected)
        assertEquals("mysqld_z.exe", detected.databaseExecutable?.name)
        assertEquals("my.ini", detected.databaseConfig?.name)
        assertTrue(detected.commands[TwwInstall.Role.DATABASE]?.arguments?.single()?.endsWith("my.ini") == true)
        assertTrue(detected.issues.none { "Ambiguous database" in it })
    }

    @Test fun scriptWorkingDirectoryArgumentsAndWindowsCaseArePreserved() = temporary { root ->
        exe(root, "Core/Worldserver.exe")
        file(root, "Core/worldserver.conf", "WorldDatabaseInfo = \"127.0.0.1;3306;user;password;world\"\nDataDir=\".\"\n")
        file(root, "start.bat", "@echo off\ncd /d \"%~dp0Core\"\nstart \"Draconic World\" worldserver.exe --config worldserver.conf\n")
        val server = assertNotNull(TwwInstall.inspect(File(root, "client"), root).detected)
        val command = assertNotNull(server.commands[TwwInstall.Role.WORLD])
        assertEquals(File(root, "Core").canonicalFile, command.workingDirectory)
        assertEquals(listOf("--config", "worldserver.conf"), command.arguments)
        assertEquals("Worldserver.exe", command.executable.name)
    }

    @Test fun unresolvedScriptVariablesDoNotFallBackToGuessedArguments() = temporary { root ->
        exe(root, "worldserver.exe")
        file(root, "worldserver.conf", "WorldDatabaseInfo=\"127.0.0.1;3306;user;password;world\"\n")
        file(root, "start.bat", "worldserver.exe --config %CUSTOM_CONFIG%\n")
        val server = assertNotNull(TwwInstall.inspect(File(root, "client"), root).detected)
        assertNull(server.commands[TwwInstall.Role.WORLD])
        assertTrue(server.issues.any { "No unambiguous startup command" in it })
    }

    @Test fun partialAdditionalMapsDoNotClaimCompleteInstallation() = temporary { root ->
        file(root, "additional/mmaps/0000.mmap", "parameters")
        val report = TwwInstall.inspect(File(root, "client"), File(root, "server"), File(root, "additional"))
        assertEquals(TwwInstall.Status.INCOMPLETE, report.maps.status)
        assertTrue(report.maps.missing.contains("maps/*.map"))
        assertFalse(report.ready)
    }

    @Test fun corruptImportedMapFormatsAreReported() = temporary { root ->
        val maps = File(root, "data")
        file(maps, "maps/0000_00_00.map", "MAPSformat-version")
        file(maps, "vmaps/0000.vmtree", "VMAP_4.9contents")
        file(maps, "mmaps/0000.mmap", "x".repeat(28))
        file(maps, "mmaps/00000000.mmtile", "PAMM" + "x".repeat(32))
        file(maps, "dbc/Spell.db2", "WDC5data")
        assertEquals(TwwInstall.Status.INSTALLED, TwwInstall.inspect(File(root, "client"), File(root, "server"), maps).maps.status)
        file(maps, "mmaps/00000000.mmtile", "wrong format" + "x".repeat(32))
        val report = TwwInstall.inspect(File(root, "client"), File(root, "server"), maps)
        assertEquals(TwwInstall.Status.INCOMPLETE, report.maps.status)
        assertTrue(report.maps.details.any { "00000000.mmtile" in it })
    }

    @Test fun unknownBuildAllowsOnlyDatabaseVerificationAndProofTracksExecutables() = temporary { root ->
        val client = File(root, "client"); client(client)
        file(client, "_retail_/WTF/Config.wtf", "SET portal \"127.0.0.1\"\n")
        val server = File(root, "server"); completeServer(server)
        val before = TwwInstall.inspect(client, server)
        assertEquals(TwwInstall.Status.UNVERIFIED, before.core.status)
        assertTrue(before.canBootstrap)
        assertTrue(before.canStartServer)
        assertFalse(before.ready)
        runtimeProof(server)
        val proven = TwwInstall.inspect(client, server)
        assertTrue(proven.detected?.buildProof == true)
        assertEquals(TwwInstall.Status.INSTALLED, proven.core.status)
        assertTrue(proven.ready)
        File(server, "Core/Bnetserver.exe").appendText("changed executable")
        val changed = TwwInstall.inspect(client, server)
        assertFalse(changed.detected?.buildProof == true)
        assertFalse(changed.ready)
        assertTrue(changed.canBootstrap)
    }

    @Test fun mismatchAmbiguityAndMissingFilesCannotUseDatabaseBootstrapException() = temporary { root ->
        val client = File(root, "client"); client(client)
        file(client, "_retail_/WTF/Config.wtf", "SET portal \"127.0.0.1\"\n")
        val server = File(root, "server"); completeServer(server)
        runtimeProof(server)
        file(server, "README.txt", "Client build: 64500")
        val mismatch = TwwInstall.inspect(client, server)
        assertFalse(mismatch.canBootstrap)
        assertFalse(mismatch.canStartServer)
        assertFalse(mismatch.ready)
        assertEquals(TwwInstall.Status.INCOMPATIBLE, mismatch.core.status)
        file(server, "README.txt", "Client build: 63704\nClient build: 64500")
        assertFalse(TwwInstall.inspect(client, server).canBootstrap)
        File(server, "README.txt").delete()
        File(server, "Data/maps/0000_00_00.map").delete()
        assertFalse(TwwInstall.inspect(client, server).canBootstrap)
    }
}
