package com.logie.warcraft.client

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientTest {
    /** A fake program: noise, then a VS_FIXEDFILEINFO with the given version, then more noise. */
    private fun exe(major: Int, minor: Int, patch: Int, build: Int, padding: Int = 100_000): ByteArray {
        fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        val noise = ByteArray(padding) { (it * 31 % 251).toByte() }
        // A near miss of the signature first.
        val nearMiss = byteArrayOf(0xBD.toByte(), 0x04, 0xEF.toByte(), 0x00)
        val info = byteArrayOf(0xBD.toByte(), 0x04, 0xEF.toByte(), 0xFE.toByte()) + le(0x00010000) +
            le((major shl 16) or minor) + le((patch shl 16) or build) + ByteArray(40)
        return noise + nearMiss + noise + info + noise
    }

    @Test
    fun readsTheVersion() {
        val build = ClientBuild.read(ByteArrayInputStream(exe(3, 3, 5, 12340)))
        assertEquals(ClientBuild(3, 3, 5, 12340), build)
        assertTrue(build!!.isSupported)
        assertEquals("3.3.5.12340", build.toString())
    }

    @Test
    fun otherVersionsAreNotSupported() {
        assertFalse(ClientBuild.read(ByteArrayInputStream(exe(3, 3, 5, 12213)))!!.isSupported)
        assertFalse(ClientBuild.read(ByteArrayInputStream(exe(4, 3, 4, 15595)))!!.isSupported)
    }

    @Test
    fun signatureAcrossReads() {
        // The signature straddles the 64 KiB read buffer.
        val build = ClientBuild.read(ByteArrayInputStream(exe(3, 3, 5, 12340, padding = 32_766)))
        assertEquals(12340, build?.build)
    }

    @Test
    fun noVersion() {
        assertNull(ClientBuild.read(ByteArrayInputStream(ByteArray(5000) { 7 })))
    }

    @Test
    fun serverDataInZips() {
        assertEquals("dbc/Spell.dbc", DataLayout.target("dbc/Spell.dbc"))
        assertEquals("maps/0004331.map", DataLayout.target("server-data/maps/0004331.map"))
        assertEquals("mmaps/000.mmap", DataLayout.target("C:\\out\\MMAPS\\000.mmap"))
        assertEquals("Cameras/FlyByDeathKnight.m2", DataLayout.target("x/cameras/FlyByDeathKnight.m2"))
        assertNull(DataLayout.target("readme.txt"))
        assertNull(DataLayout.target("maps/"))
        assertNull(DataLayout.target("maps/../../etc/passwd"))
    }

    @Test
    fun findsTheClient() {
        val top = listOf("Wow.exe", "Data/common.MPQ", "Data/enUS/realmlist.wtf")
        assertEquals("", ClientLayout.findRoot(top))
        val nested = listOf("WoW 3.3.5a/Wow.exe", "WoW 3.3.5a/Data/common.MPQ", "WoW 3.3.5a/Data/lichking.MPQ", "readme.txt")
        assertEquals("WoW 3.3.5a", ClientLayout.findRoot(nested))
        assertEquals("a/b", ClientLayout.findRoot(listOf("a/b/WOW.EXE", "a/b/data/COMMON.mpq")))
        assertNull(ClientLayout.findRoot(listOf("Wow.exe", "Data/lichking.MPQ")))
        assertNull(ClientLayout.findRoot(listOf("Data/common.MPQ")))
        assertFalse(ClientLayout.isSafe("../x"))
        assertFalse(ClientLayout.isSafe("/abs"))
        assertTrue(ClientLayout.isSafe("Data/enUS/patch-enUS.MPQ"))
    }
}
