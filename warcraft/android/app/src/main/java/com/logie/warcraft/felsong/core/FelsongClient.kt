// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong.core

import java.io.File
import java.io.InputStream

/**
 * The Legion client the realm is made for: World of Warcraft 7.3.5, build 26972, 64-bit
 * (`Wow-64.exe`; the 32-bit 7.3 client never ran under Wine). The app never downloads it: Blizzard
 * serves no 7.x client and no copy found is Blizzard's, so the user brings their own, and this says
 * whether it's that one, from what the client says about itself:
 *
 * - `.build.info` at its top (the Battle.net launcher's record of what's installed, a `|`-separated
 *   table with a `Version` column such as `7.3.5.26972`);
 * - `Wow-64.exe` itself, which carries its version as `<Version>…` text the way LegionCore's
 *   connection patcher reads it (connection_patcher/Helper.cpp).
 *
 * No file list with checksums exists for 26972 that the app could trust, so no file-by-file check:
 * the client is the user's, as it is.
 */
object FelsongClient {
    /** The program the game runs; the connection patcher writes its patched copy beside it. */
    const val EXE = "Wow-64.exe"
    const val PATCHED_EXE = "Wow-64_Patched.exe"
    const val BUILD_INFO = ".build.info"
    const val BUILD = FelsongRealm.CLIENT_BUILD

    /** The version the client's files say, or null where they say nothing. */
    data class Version(val text: String, val build: Int) {
        val isExpected: Boolean get() = build == BUILD
    }

    /**
     * Finds the client's folder among [paths] (relative, with `/`, as a picked folder or a zip
     * lists them): the one with Wow-64.exe and a Data folder, however deep. "" is the top.
     */
    fun findRoot(paths: List<String>): String? {
        val exes = paths.filter { it.substringAfterLast('/').equals(EXE, ignoreCase = true) }
        return exes.map { it.substringBeforeLast('/', "") }
            .sortedBy { it.length }
            .firstOrNull { root ->
                val prefix = if (root.isEmpty()) "" else "$root/"
                paths.any { it.startsWith(prefix, ignoreCase = true) && it.substring(prefix.length).startsWith("Data/", ignoreCase = true) }
            }
    }

    /**
     * The files a copy of the client must have for the game and the server's data tools: the
     * program, the launcher's record and the CASC storage (`Data/data/*.idx`, `Data/data/data.NNN`).
     */
    fun missing(paths: List<String>, root: String): List<String> {
        val prefix = if (root.isEmpty()) "" else "$root/"
        val inside = paths.filter { it.startsWith(prefix, ignoreCase = true) }.map { it.substring(prefix.length) }
        val missing = mutableListOf<String>()
        if (inside.none { it.equals(EXE, ignoreCase = true) }) missing += EXE
        if (inside.none { it.startsWith("Data/data/", ignoreCase = true) && it.endsWith(".idx", ignoreCase = true) }) missing += "Data/data/*.idx"
        if (inside.none { it.startsWith("Data/data/data.", ignoreCase = true) }) missing += "Data/data/data.000"
        return missing
    }

    /** The version in a `.build.info` (its `Version` column, the first row that has one). */
    fun versionFromBuildInfo(text: String): Version? {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return null
        val header = lines[0].split('|').map { it.substringBefore('!').trim() }
        val at = header.indexOfFirst { it.equals("Version", ignoreCase = true) }
        if (at < 0) return null
        for (line in lines.drop(1)) {
            val cells = line.split('|')
            val cell = cells.getOrNull(at)?.trim() ?: continue
            VERSION.matchEntire(cell)?.let { return Version(cell, it.groupValues[1].toInt()) }
        }
        return null
    }

    /**
     * The version in the program: the text after `<Version>` (LegionCore's connection patcher reads
     * the build there), found in [input] without holding the whole 50 MB program in memory.
     */
    fun versionFromExe(input: InputStream): Version? {
        val marker = "<Version>".toByteArray()
        val buffer = ByteArray(1 shl 16)
        var carry = ByteArray(0)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            val window = carry + buffer.copyOf(n)
            var at = indexOf(window, marker, 0)
            while (at >= 0) {
                val end = minOf(window.size, at + marker.size + 40)
                val after = String(window, at + marker.size, end - at - marker.size, Charsets.ISO_8859_1)
                VERSION_IN_TEXT.find(after)?.let { return Version(it.value, it.groupValues[1].toInt()) }
                if (end == window.size) break
                at = indexOf(window, marker, at + 1)
            }
            carry = window.copyOfRange(maxOf(0, window.size - 64), window.size)
        }
        return null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        var i = from
        while (i <= haystack.size - needle.size) {
            if (haystack[i] == needle[0] && needle.indices.all { haystack[i + it] == needle[it] }) return i
            i++
        }
        return -1
    }

    /** `7.3.5.26972` */
    private val VERSION = Regex("""\d+\.\d+\.\d+\.(\d{5})""")
    private val VERSION_IN_TEXT = Regex("""\d+\.\d+\.\d+\.(\d{5})""")

    /** The client in [folder]: its version from `.build.info` first, else from the program. */
    fun version(folder: File): Version? {
        val info = find(folder, BUILD_INFO)
        if (info != null && info.isFile) runCatching { versionFromBuildInfo(info.readText()) }.getOrNull()?.let { return it }
        val exe = find(folder, EXE) ?: return null
        return runCatching { exe.inputStream().buffered().use { versionFromExe(it) } }.getOrNull()
    }

    /** [path] (with `/`) in [folder], each part matched without regard to case (Windows names). */
    fun find(folder: File, path: String): File? {
        var current = folder
        for (part in path.split('/')) {
            val exact = File(current, part)
            current = if (exact.exists()) exact
            else current.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
        }
        return current
    }

    /** A relative path with no way out of the folder it's in. */
    fun isSafePath(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith("/") && !path.startsWith("\\") && ':' !in path &&
            path.split('/', '\\').none { it.isEmpty() || it == "." || it == ".." }
}
