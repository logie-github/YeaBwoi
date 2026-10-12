// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.client

/**
 * A copy of the game's `Wow.exe` (build 12340) for client mods:
 *
 * - **Interface files from patches**: the game checks the signature of its own interface files
 *   (GlueXML, FrameXML) and stops at "Your login interface files are corrupt" when a patch changes
 *   them, as the custom races' character creation does. Six edits turn the check off, the same
 *   ones the community's 3.3.5a HD client ships with (each found once there, at these offsets).
 * - **4 GB of memory** (large address aware): HD textures need more than the 2 GB a 32-bit
 *   program gets otherwise.
 *
 * Every edit checks the bytes it replaces first; an executable that has something else there isn't
 * the one these edits are for, and is left alone.
 */
object WowExePatch {
    class Edit(val offset: Int, before: String, after: String) {
        val before = hex(before)
        val after = hex(after)
    }

    val SIGNATURE_EDITS = listOf(
        Edit(0x1F41BC, "04 85 C0 74 39 56", "04 85 C0 EB 39 56"),
        Edit(0x415A21, "C0 FF 85 C0 75 05 5E 8B", "C0 FF 85 C0 EB 05 5E 8B"),
        Edit(0x415A3B, "B6 C0 FF B8 01 00 00 00", "B6 C0 FF B8 03 00 00 00"),
        Edit(0x415A91, "C0 FF 5F B8 01 00 00 00", "C0 FF 5F B8 03 00 00 00"),
        Edit(0x415B41, "B8 01 00 00 00 7F 12 83 C8 FF F7", "B8 01 00 00 00 EB 12 83 C8 FF F7"),
        Edit(0x415B5D, "C0 5F 83 C0 03 5E 8B E5 5D C3 CC", "C0 5F B8 03 00 00 00 EB ED C3 CC"),
    )

    /** The PE header's flag for a 32-bit program that can use 4 GB. */
    private const val LARGE_ADDRESS_AWARE = 0x20

    sealed interface Result {
        /** [exe] patched in place. */
        data object Done : Result
        data class Unknown(val reason: String) : Result
    }

    /** Patches [exe] in place: the signature edits if [signatures], and 4 GB. */
    fun patch(exe: ByteArray, signatures: Boolean = true): Result {
        val characteristics = characteristicsOffset(exe) ?: return Result.Unknown("not a Windows program")
        if (signatures) {
            for (edit in SIGNATURE_EDITS) {
                if (!matches(exe, edit.offset, edit.before) && !matches(exe, edit.offset, edit.after)) {
                    return Result.Unknown("unexpected bytes at 0x%X".format(edit.offset))
                }
            }
            for (edit in SIGNATURE_EDITS) edit.after.copyInto(exe, edit.offset)
        }
        val flags = (exe[characteristics].toInt() and 0xFF) or LARGE_ADDRESS_AWARE
        exe[characteristics] = flags.toByte()
        return Result.Done
    }

    /** Whether [exe] has the signature edits and 4 GB already. */
    fun isPatched(exe: ByteArray, signatures: Boolean = true): Boolean {
        val characteristics = characteristicsOffset(exe) ?: return false
        if (exe[characteristics].toInt() and LARGE_ADDRESS_AWARE == 0) return false
        return !signatures || SIGNATURE_EDITS.all { matches(exe, it.offset, it.after) }
    }

    /** Where the PE header's characteristics are (their low byte), null if [exe] isn't a PE program. */
    private fun characteristicsOffset(exe: ByteArray): Int? {
        if (exe.size < 0x40 || exe[0] != 'M'.code.toByte() || exe[1] != 'Z'.code.toByte()) return null
        val pe = (exe[0x3C].toInt() and 0xFF) or ((exe[0x3D].toInt() and 0xFF) shl 8) or
            ((exe[0x3E].toInt() and 0xFF) shl 16) or ((exe[0x3F].toInt() and 0xFF) shl 24)
        if (pe < 0 || pe + 24 > exe.size) return null
        if (exe[pe] != 'P'.code.toByte() || exe[pe + 1] != 'E'.code.toByte() || exe[pe + 2] != 0.toByte() || exe[pe + 3] != 0.toByte()) return null
        return pe + 22
    }

    private fun matches(exe: ByteArray, offset: Int, bytes: ByteArray): Boolean =
        offset >= 0 && offset + bytes.size <= exe.size && bytes.indices.all { exe[offset + it] == bytes[it] }

    private fun hex(text: String): ByteArray =
        text.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
}
