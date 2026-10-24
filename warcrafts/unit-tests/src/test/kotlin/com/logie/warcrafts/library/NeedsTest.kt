package com.logie.warcrafts.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NeedsTest {
    private val installed = setOf("warcraft iii.exe", "frozen throne.exe", "war3.mpq", "war3x.mpq", "war3xlocal.mpq", "war3patch.mpq", "maps")

    @Test
    fun `an installed 1_27 has everything Warcraft III needs`() {
        val checked = Needs.check(Install.WC3, "Warcraft III.exe") { it.lowercase() in installed }
        assertTrue(checked.all { it.second }, checked.toString())
        assertTrue(Needs.ready(checked))
    }

    @Test
    fun `without the expansion or the patch Warcraft III isn't ready, without maps it still is`() {
        assertFalse(Needs.ready(Needs.check(Install.WC3, "Warcraft III.exe") { it.lowercase() in installed - "war3xlocal.mpq" }))
        assertFalse(Needs.ready(Needs.check(Install.WC3, "Warcraft III.exe") { it.lowercase() in installed - "war3patch.mpq" }))
        val noMaps = Needs.check(Install.WC3, "Warcraft III.exe") { it.lowercase() in installed - "maps" }
        assertTrue(Needs.ready(noMaps))
        assertEquals(listOf(Needs.Item.MAPS), noMaps.filter { !it.second }.map { it.first.item })
    }

    @Test
    fun `GOG Warcraft I needs DATA_WAR, then the conversion`() {
        val gog = setOf("data/data.war")
        val imported = Needs.check(Install.WC1, null, converted = false) { it.lowercase() in gog }
        assertTrue(Needs.filesIn(imported))
        assertFalse(Needs.ready(imported))
        assertTrue(Needs.ready(Needs.check(Install.WC1, null, converted = true) { it.lowercase() in gog }))
    }

    @Test
    fun `GOG Warcraft II needs the four tomes and Install_mpq`() {
        val gog = (1..4).map { "support/tomes/tome.$it" }.toSet() + "install.mpq"
        assertTrue(Needs.filesIn(Needs.check(Install.WC2, null) { it.lowercase() in gog }))
        assertFalse(Needs.filesIn(Needs.check(Install.WC2, null) { it.lowercase() in gog - "support/tomes/tome.3" }))
        assertFalse(Needs.filesIn(Needs.check(Install.WC2, null) { it.lowercase() in gog - "install.mpq" }))
    }
}
