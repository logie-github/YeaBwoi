package com.logie.warcrafts.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OfflineTest {
    @Test
    fun `private looks names up in the hosts file alone, with no name server`() {
        assertEquals("hosts: files\n", Offline.nsswitch(private = true))
    }

    @Test
    fun `not private asks the name server after the hosts file`() {
        assertEquals("hosts: files dns\n", Offline.nsswitch(private = false))
    }

    @Test
    fun `the hosts file names only this phone, so nothing else resolves while private`() {
        val names = Offline.hosts().lines().filter { it.isNotBlank() }.map { it.substringAfter('\t') }.toSet()
        assertEquals(setOf("localhost"), names)
        assertFalse("battle.net" in Offline.hosts())
    }
}
