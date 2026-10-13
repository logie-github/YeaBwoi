package com.logie.warcraft.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WowExePatchTest {
    /** A program shaped like Wow.exe where the edits go: MZ, a PE header, the original bytes. */
    private fun exe(): ByteArray {
        val exe = ByteArray(0x416000)
        exe[0] = 'M'.code.toByte(); exe[1] = 'Z'.code.toByte()
        exe[0x3C] = 0x10; exe[0x3D] = 0x01 // the PE header at 0x110, as in Wow.exe
        "PE".forEachIndexed { i, c -> exe[0x110 + i] = c.code.toByte() }
        exe[0x110 + 22] = 0x03; exe[0x110 + 23] = 0x01 // characteristics 0x0103
        for (edit in WowExePatch.SIGNATURE_EDITS) edit.before.copyInto(exe, edit.offset)
        return exe
    }

    @Test
    fun turnsTheChecksOffAndAllows4Gb() {
        val exe = exe()
        assertFalse(WowExePatch.isPatched(exe))
        assertEquals(WowExePatch.Result.Done, WowExePatch.patch(exe))
        assertTrue(WowExePatch.isPatched(exe))
        assertEquals(0x23, exe[0x110 + 22].toInt())
        for (edit in WowExePatch.SIGNATURE_EDITS) {
            assertTrue(edit.after.indices.all { exe[edit.offset + it] == edit.after[it] })
        }
        // Again: nothing changes.
        val once = exe.copyOf()
        assertEquals(WowExePatch.Result.Done, WowExePatch.patch(exe))
        assertTrue(once.contentEquals(exe))
    }

    @Test
    fun onlyMemoryWhenTheChecksStay() {
        val exe = exe()
        assertEquals(WowExePatch.Result.Done, WowExePatch.patch(exe, signatures = false))
        assertTrue(WowExePatch.isPatched(exe, signatures = false))
        assertFalse(WowExePatch.isPatched(exe))
    }

    @Test
    fun anotherProgramIsLeftAlone() {
        val other = exe()
        other[WowExePatch.SIGNATURE_EDITS[3].offset] = 0x55
        val before = other.copyOf()
        assertIs<WowExePatch.Result.Unknown>(WowExePatch.patch(other))
        assertTrue(before.contentEquals(other))
        assertIs<WowExePatch.Result.Unknown>(WowExePatch.patch(ByteArray(100)))
    }
}
