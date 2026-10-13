// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.client

import java.io.InputStream

/**
 * The version of a Windows program, read from its VS_FIXEDFILEINFO (the version resource Windows
 * shows in a file's properties). For a 3.3.5a client, `Wow.exe` is 3.3.5.12340.
 */
data class ClientBuild(val major: Int, val minor: Int, val patch: Int, val build: Int) {
    override fun toString() = "$major.$minor.$patch.$build"

    /** The client this app's server speaks to: Wrath of the Lich King 3.3.5a, build 12340. */
    val isSupported: Boolean get() = major == 3 && minor == 3 && patch == 5 && build == SUPPORTED_BUILD

    companion object {
        const val SUPPORTED_BUILD = 12340

        // VS_FIXEDFILEINFO starts with the signature 0xFEEF04BD, then the structure version, then
        // the file version as two 32-bit numbers (major.minor, patch.build), all little-endian.
        private val SIGNATURE = byteArrayOf(0xBD.toByte(), 0x04, 0xEF.toByte(), 0xFE.toByte())

        /** Reads the version from a program's bytes, or null if it has no version resource. */
        fun read(input: InputStream): ClientBuild? {
            val buffer = ByteArray(1 shl 16)
            val window = ByteArray(16)
            var filled = 0 // bytes of the signature matched so far, then of the fields after it
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return null
                for (i in 0 until read) {
                    val b = buffer[i]
                    if (filled < 4) {
                        filled = when {
                            b == SIGNATURE[filled] -> filled + 1
                            b == SIGNATURE[0] -> 1
                            else -> 0
                        }
                        if (filled == 4) window.fill(0)
                    } else {
                        window[filled - 4] = b
                        filled++
                        if (filled == 16) {
                            val ms = int(window, 4)
                            val ls = int(window, 8)
                            return ClientBuild(ms ushr 16, ms and 0xFFFF, ls ushr 16, ls and 0xFFFF)
                        }
                    }
                }
            }
        }

        private fun int(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)
    }
}
