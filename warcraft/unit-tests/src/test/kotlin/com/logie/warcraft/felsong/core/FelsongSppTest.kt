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
        assertEquals(3, conf.size)
        // (the realm's patch reads these names)
        val patch = java.io.File("../felsong/patches/0009-App-game-options.patch").readText()
        for (key in conf.keys) kotlin.test.assertTrue("\"$key\"" in patch, "the realm's patch doesn't read $key")
    }
}
