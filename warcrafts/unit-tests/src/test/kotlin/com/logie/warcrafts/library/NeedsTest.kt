package com.logie.warcrafts.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NeedsTest {
    private val installed = setOf("warcraft iii.exe", "frozen throne.exe", "war3.mpq", "war3x.mpq", "war3xlocal.mpq", "maps")

    @Test
    fun `Warcraft III is downloaded first, then installed, then its keys typed`() {
        val nothing = Needs.check(Install.WC3) { false }
        assertEquals(listOf(Needs.Item.WC3_DOWNLOADED, Needs.Item.WC3_INSTALLED, Needs.Item.WC3_KEYS), nothing.filter { !it.second }.map { it.first.item })
        val downloaded = Needs.check(Install.WC3, downloaded = true) { false }
        assertEquals(listOf(Needs.Item.WC3_INSTALLED, Needs.Item.WC3_KEYS), downloaded.filter { !it.second }.map { it.first.item })
        assertFalse(Needs.ready(downloaded))
        val noKeys = Needs.check(Install.WC3) { it.lowercase() in installed }
        assertEquals(listOf(Needs.Item.WC3_KEYS), noKeys.filter { !it.second }.map { it.first.item })
    }

    @Test
    fun `an installed Warcraft III with its keys is ready, its installers gone or not`() {
        val withKeys = installed + Wc3Keys.SAVED
        assertTrue(Needs.ready(Needs.check(Install.WC3) { it.lowercase() in withKeys }))
        assertFalse(Needs.ready(Needs.check(Install.WC3, downloaded = true) { it.lowercase() in withKeys - "war3x.mpq" }))
    }

    @Test
    fun `GOG Warcraft I needs DATA_WAR, then the conversion`() {
        val gog = setOf("data/data.war")
        val imported = Needs.check(Install.WC1, converted = false) { it.lowercase() in gog }
        assertTrue(Needs.filesIn(imported))
        assertFalse(Needs.ready(imported))
        assertTrue(Needs.ready(Needs.check(Install.WC1, converted = true) { it.lowercase() in gog }))
    }

    @Test
    fun `GOG Warcraft II needs the four tomes and Install_mpq`() {
        val gog = (1..4).map { "support/tomes/tome.$it" }.toSet() + "install.mpq"
        assertTrue(Needs.filesIn(Needs.check(Install.WC2) { it.lowercase() in gog }))
        assertFalse(Needs.filesIn(Needs.check(Install.WC2) { it.lowercase() in gog - "support/tomes/tome.3" }))
        assertFalse(Needs.filesIn(Needs.check(Install.WC2) { it.lowercase() in gog - "install.mpq" }))
    }
}
