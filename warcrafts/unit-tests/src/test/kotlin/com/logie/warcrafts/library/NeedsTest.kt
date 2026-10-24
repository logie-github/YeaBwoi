package com.logie.warcrafts.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NeedsTest {
    private val repack = setOf("warcraft iii.exe", "data/data", "data/indices", "data/config", ".build.info", "maps", "_serial_keys.txt")

    @Test
    fun `the 1_30 repack has everything Warcraft III needs`() {
        val checked = Needs.check(Install.WC3, "Warcraft III.exe", hasKeys = true) { it.lowercase() in repack }
        assertTrue(checked.all { it.second }, checked.toString())
        assertTrue(Needs.ready(checked))
    }

    @Test
    fun `without its data Warcraft III isn't ready, without maps or keys it still is`() {
        val noData = Needs.check(Install.WC3, "Warcraft III.exe", hasKeys = false) { it.lowercase() in repack - "data/indices" }
        assertFalse(Needs.ready(noData))
        val noExtras = Needs.check(Install.WC3, "Warcraft III.exe", hasKeys = false) { it.lowercase() in repack - "maps" }
        assertTrue(Needs.ready(noExtras))
        assertEquals(listOf(Needs.Item.MAPS, Needs.Item.CD_KEYS), noExtras.filter { !it.second }.map { it.first.item })
    }

    @Test
    fun `GOG Warcraft I needs DATA_WAR, then the conversion`() {
        val gog = setOf("data/data.war")
        val imported = Needs.check(Install.WC1, null, false, converted = false) { it.lowercase() in gog }
        assertTrue(Needs.filesIn(imported))
        assertFalse(Needs.ready(imported))
        assertTrue(Needs.ready(Needs.check(Install.WC1, null, false, converted = true) { it.lowercase() in gog }))
    }

    @Test
    fun `GOG Warcraft II needs the four tomes and Install_mpq`() {
        val gog = (1..4).map { "support/tomes/tome.$it" }.toSet() + "install.mpq"
        assertTrue(Needs.filesIn(Needs.check(Install.WC2, null, false) { it.lowercase() in gog }))
        assertFalse(Needs.filesIn(Needs.check(Install.WC2, null, false) { it.lowercase() in gog - "support/tomes/tome.3" }))
        assertFalse(Needs.filesIn(Needs.check(Install.WC2, null, false) { it.lowercase() in gog - "install.mpq" }))
    }
}
