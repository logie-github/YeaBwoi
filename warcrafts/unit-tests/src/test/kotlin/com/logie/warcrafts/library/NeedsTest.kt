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
    fun `the Remastered games need their program, data and build info`() {
        assertEquals(
            listOf(Needs.Item.PROGRAM, Needs.Item.DATA, Needs.Item.BUILD_INFO),
            Needs.of(Install.WC2).map { it.item },
        )
        assertEquals(listOf("x86/Warcraft II.exe"), Needs.of(Install.WC2).first().paths)
        assertEquals(listOf("x86/Warcraft.exe"), Needs.of(Install.WC1).first().paths)
        assertFalse(Needs.ready(Needs.check(Install.WC1, null, hasKeys = false) { false }))
    }
}
