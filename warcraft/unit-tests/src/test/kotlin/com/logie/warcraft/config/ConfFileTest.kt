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
