// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.tww.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TwwSessionTest {
    @Test fun `realm row follows explicit case insensitive world configuration`() {
        assertEquals(7, TwwSession.realmId("# example RealmID = 1\n  realmid = 7 # this realm\n"))
        assertEquals(23, TwwSession.realmId("RealmID = \"23\""))
    }

    @Test fun `missing ambiguous or unsafe realm rows cannot update the authentication database`() {
        for (configuration in listOf("", "RealmID=0", "RealmID=-1", "RealmID=2147483648", "RealmID=1; DROP TABLE realmlist", "RealmID=1\nRealmID=2")) {
            assertFailsWith<IOException>(configuration) { TwwSession.realmId(configuration) }
        }
    }

    @Test fun `database host paths become repack drive paths and keep their spaces`() = withFolder { root ->
        val server = File(root, "server files").apply { mkdirs() }
        val executable = File(server, "database/bin/mysqld.exe").apply { parentFile.mkdirs(); writeText("fixture") }
        val configuration = File(server, "database/my.ini").apply { writeText("[mysqld]") }
        val command = TwwInstall.StartupCommand(executable, listOf("--defaults-file=${configuration.path}", "--console"), configuration.parentFile, configuration)
        val script = TwwSession.launch(command, server, "database")
        assertContains(script, "cd /d \"S:\\database\"")
        assertContains(script, "\"S:\\database\\bin\\mysqld.exe\" \"--defaults-file=S:\\database\\my.ini\" \"--console\"")
        assertContains(script, "echo %ERRORLEVEL% >\"R:\\runtime\\database.exit\"")
        // Winlator's Unix TMPDIR must not reach MariaDB (InnoDB stops on it under Box64).
        assertTrue(script.indexOf("set \"TMPDIR=\"") in 0 until script.indexOf("mysqld.exe"))
    }

    @Test fun `paths in a sibling folder cannot enter the Wine session`() = withFolder { root ->
        val server = File(root, "server").apply { mkdirs() }
        val sibling = File(root, "server-other/secret").apply { parentFile.mkdirs(); writeText("fixture") }
        assertFailsWith<IOException> { TwwSession.windows(sibling, server, 'S') }
    }

    @Test fun `startup metacharacters are rejected instead of becoming shell operations`() {
        for (argument in listOf("hello&calc.exe", "%PATH%", "foo|bar", "bad\r\ncommand", "!variable!", "input<file", "escaped^text")) {
            assertFailsWith<IOException>(argument) { TwwSession.argument(argument) }
        }
        assertEquals("\"ordinary value\"", TwwSession.argument("ordinary value"))
    }

    @Test fun `repack cannot reuse an old Windows installation path`() = withFolder { root ->
        val executable = File(root, "mysqld.exe").apply { writeText("fixture") }
        val command = TwwInstall.StartupCommand(executable, listOf("--defaults-file=C:\\UniServerZ\\my.ini"), root, File(root, "startup.bat"))
        assertFailsWith<IOException> { TwwSession.launch(command, root, "database") }
    }

    @Test fun `absolute host arguments outside repack are rejected`() = withFolder { root ->
        val server = File(root, "server").apply { mkdirs() }
        val executable = File(server, "mysqld.exe").apply { writeText("fixture") }
        val command = TwwInstall.StartupCommand(executable, listOf("--defaults-file=${File(root, "elsewhere.ini").path}"), server, File(server, "startup.bat"))
        assertFailsWith<IOException> { TwwSession.launch(command, server, "database") }
    }

    private fun withFolder(block: (File) -> Unit) {
        val root = Files.createTempDirectory("tww-session ").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
