package com.logie.warcraft.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ConfFileTest {
    private val text = """
        |###############
        |# worldserver.conf
        |#
        |#    RealmID
        |#        Description: ID of the Realm using this config.
        |#        Default:     1
        |
        |RealmID = 1
        |
        |DataDir = "."
        |vmap.enableLOS    = 1
        |Rate.XP.Kill      = 1
        |Rate.XP.KillElite = 1
        |""".trimMargin()

    @Test
    fun readsValues() {
        val conf = ConfFile(text)
        assertEquals("1", conf["RealmID"])
        assertEquals(".", conf["DataDir"])
        assertEquals("1", conf["vmap.enableLOS"])
        assertNull(conf["Missing"])
        // A key that starts like another isn't it.
        assertEquals("1", conf["Rate.XP.Kill"])
    }

    @Test
    fun changesOnlyTheLine() {
        val conf = ConfFile(text)
        conf["Rate.XP.Kill"] = "3"
        conf.setText("DataDir", "/data/data/com.logie.warcraft/files/data")
        conf.setSwitch("vmap.enableLOS", false)
        val out = conf.toString()
        assertEquals(text.lines().size, out.lines().size)
        assertEquals("3", ConfFile(out)["Rate.XP.Kill"])
        assertEquals("1", ConfFile(out)["Rate.XP.KillElite"])
        assertEquals("/data/data/com.logie.warcraft/files/data", ConfFile(out)["DataDir"])
        assertEquals("0", ConfFile(out)["vmap.enableLOS"])
        assertEquals(text.lines().take(8), out.lines().take(8))
    }

    /** A Windows repack's worldserver.conf (CRLF), its console turned off (WindowsRealmServer.headlessWorld). */
    @Test
    fun switchesInWindowsFiles() {
        val windows = "# Console\r\nConsole.Enable = 1\r\nConsole.EnableHistory = 1\r\nRa.Enable = 0\r\n"
        val conf = ConfFile(windows)
        assertEquals("1", conf["Console.Enable"]?.trim())
        conf.setSwitch("Console.Enable", false)
        val out = ConfFile(conf.toString())
        assertEquals("0", out["Console.Enable"]?.trim())
        assertEquals("1", out["Console.EnableHistory"]?.trim())
        assertEquals(windows.lines().size, conf.toString().lines().size)
    }

    @Test
    fun addsMissingKeys() {
        val conf = ConfFile(text)
        conf["Playerbots.MinRandomBots"] = "50"
        assertEquals("50", ConfFile(conf.toString())["Playerbots.MinRandomBots"])
    }

    @Test
    fun textCantBreakTheFile() {
        assertFailsWith<IllegalArgumentException> { ConfFile(text).setText("DataDir", "a\"b") }
        assertFailsWith<IllegalArgumentException> { ConfFile(text).setText("DataDir", "a\nb") }
    }
}
