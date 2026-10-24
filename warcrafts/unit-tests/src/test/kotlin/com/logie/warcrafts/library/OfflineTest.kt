package com.logie.warcrafts.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineTest {
    @Test
    fun `private sends every Blizzard name nowhere and keeps localhost`() {
        val lines = Offline.hosts(blocked = true).lines()
        assertTrue("127.0.0.1\tlocalhost" in lines)
        for (name in listOf("us.actual.battle.net", "uswest.battle.net", "us.patch.battle.net", "level3.blizzard.com", "blzddist1-a.akamaihd.net")) {
            assertTrue("0.0.0.0\t$name" in lines, name)
        }
    }

    @Test
    fun `not private is just localhost`() {
        val text = Offline.hosts(blocked = false)
        assertFalse("battle.net" in text)
        assertTrue("localhost" in text)
    }

    @Test
    fun `names are looked up in the hosts file first`() {
        assertEquals("hosts: files dns\n", Offline.nsswitch())
    }

    @Test
    fun `each name once`() {
        assertEquals(Offline.BLOCKED.size, Offline.BLOCKED.toSet().size)
    }
}
