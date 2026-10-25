// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.felsong.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FelsongSppTest {
    @Test
    fun findsSppsClientAndServerData() {
        val root = createTempDirectory("spp").toFile()
        try {
            // (SPP's download: the client with its patched program only, the server data under Server/Data)
            val client = File(root, "client/SSP Legion 7.3.5/SingleCore_Legion/WoW Legion")
            File(client, "Data/data").mkdirs()
            File(client, "SPP_Wow-64.exe").writeText("x")
            File(client, "Wow.exe").writeText("x")
            assertEquals(client, FelsongSpp.findClient(File(root, "client")))
            val data = File(root, "server/spp-legion/Server/Data")
            File(data, "dbc/enUS").mkdirs()
            File(data, "dbc/enUS/Map.db2").writeText("x")
            File(data, "Maps").mkdirs()
            File(data, "Maps/0000_31_32.map").writeText("x")
            assertEquals(data, FelsongSpp.findData(File(root, "server")))
            assertEquals("Maps", FelsongSpp.child(data, "maps")?.name)
            // (a data folder without maps isn't the server's; no client without its Data)
            File(data, "Maps/0000_31_32.map").delete()
            assertNull(FelsongSpp.findData(File(root, "server")))
            File(client, "Data/data").delete(); File(client, "Data").delete()
            assertNull(FelsongSpp.findClient(File(root, "client")))
            assertNull(FelsongSpp.findClient(File(root, "nothing")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun theClientsProgramIsWow64OrItsPatchedOneAlone() {
        val dir = createTempDirectory("client").toFile()
        try {
            assertNull(FelsongClient.program(dir))
            File(dir, FelsongClient.PATCHED_EXE).writeText("x")
            assertEquals(FelsongClient.PATCHED_EXE, FelsongClient.program(dir)?.name)
            File(dir, FelsongClient.EXE).writeText("x")
            assertEquals(FelsongClient.EXE, FelsongClient.program(dir)?.name)
        } finally {
            dir.deleteRecursively()
        }
    }
}

class FelsongGameOptionsTest {
    @Test
    fun theOptionsAreTheRealmsSettings() {
        val conf = FelsongGameOptions.conf(setOf(FelsongGameOptions.UNLOCK_ALLIED_RACES, FelsongGameOptions.NO_UNDERWEAR))
        assertEquals("1", conf["App.UnlockAlliedRaces"])
        assertEquals("1", conf["App.NoUnderwear"])
        // (one switched off is written off, so the realm's file says it)
        assertEquals("0", conf["App.UnlockAllClasses"])
        assertEquals(FelsongGameOptions.entries.size, conf.size)
        // (the realm's patch reads these names)
        val patch = java.io.File("../felsong/patches/0009-App-game-options.patch").readText()
        // (Undead paladins is rows in the databases, FelsongGameOptions.sql, not a setting the patch reads)
        for (key in conf.keys - "App.UndeadPaladins") kotlin.test.assertTrue("\"$key\"" in patch, "the realm's patch doesn't read $key")
    }
}

class FelsongUndeadPaladinsTest {
    @Test
    fun theRowsAreTheOnesTheRealmsTestRuns() {
        val on = FelsongGameOptions.sql(setOf(FelsongGameOptions.UNDEAD_PALADINS)).trim().lines()
        val off = FelsongGameOptions.sql(emptySet()).trim().lines()
        // (off: only the rows taken out; on: the same, then put in)
        assertEquals(4, off.size)
        assertEquals(off, on.take(4))
        kotlin.test.assertTrue(on.any { "VALUES (2000000001, ${FelsongGameOptions.CHAR_BASE_INFO_HASH}, 1000, 0)" in it })
        // (the realm's CI test runs these very statements against the real databases)
        val script = java.io.File("../../scripts/test-felsong-server.sh").readText()
        val tested = script.substringAfter("<<'SQL'\n").substringBefore("\nSQL\n").lines()
        assertEquals(on, tested)
    }
}
