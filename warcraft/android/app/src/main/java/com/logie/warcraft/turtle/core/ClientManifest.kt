// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * What the app knows of a client's files without a list of them: where the client is in a folder or
 * a zip, which of its files the game and the map tools can't do without, safe paths, and file
 * hashes. The OctoWoW client's own list, with every piece's checksum, is its torrent
 * ([ClientTorrent]); [ClientSync] checks a client against it.
 */
object ClientManifest {
    /** The program the game runs (OctoWoW's WoW.exe: 1.18.1, build 7272, as Turtle WoW's last). */
    const val EXE = "WoW.exe"

    /** What the game and the map tools can't do without. */
    val REQUIRED = listOf(EXE, "Data/dbc.MPQ", "Data/terrain.MPQ", "Data/patch.MPQ")

    /** The [REQUIRED] files not in [folder]. */
    fun missingRequired(folder: File): List<String> = REQUIRED.filter { find(folder, it)?.isFile != true }

    /** A relative path with no way out of the folder it's in. */
    fun isSafePath(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith("/") && !path.startsWith("\\") && ':' !in path &&
            path.split('/', '\\').none { it.isEmpty() || it == "." || it == ".." }

    /** [path] (with `/`) in [folder], each part matched without regard to case. */
    fun find(folder: File, path: String): File? {
        var current = folder
        for (part in path.split('/')) {
            val exact = File(current, part)
            current = if (exact.exists()) exact
            else current.listFiles()?.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
        }
        return current
    }

    /** The SHA-256 of [input], in lowercase hex; [onRead] gets the bytes read so far. */
    fun sha256(input: InputStream, onRead: (Long) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        var total = 0L
        var sinceReport = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            total += n
            sinceReport += n
            if (sinceReport >= 8L shl 20) {
                onRead(total)
                sinceReport = 0
            }
        }
        onRead(total)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Finds the client's folder among [paths] (relative, with `/`, as a picked folder or a zip
     * lists them): the one with WoW.exe and a Data folder, however deep it's nested. "" is
     * the top. Null if there's none.
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
}

/**
 * The checksums of the DBC files Tortoise-WoW's realm is made for (`assets/turtle/dbc-sha256.tsv`,
 * from Tortoise's dbc_verifier.py: Turtle WoW's final 1.18.1 client), and the tables OctoWoW's
 * client has its own versions of ([own], `assets/turtle/octowow-dbc.txt`: its patch-5.mpq's, and
 * three 1.12 tables it has as the 1.12 client left them). The server's data made from OctoWoW's
 * client differs from Turtle's in those and should match it in every other one.
 */
class DbcManifest(val hashes: Map<String, String>, val own: Set<String> = emptySet()) {
    data class Report(val checked: Int, val missing: List<String>, val different: List<String>, val own: Set<String> = emptySet()) {
        /** Tables that differ from Turtle's for a reason the app doesn't know of. */
        val unexpected: List<String> get() = (missing + different).filter { it !in own }

        /** As Turtle's 1.18.1, apart from OctoWoW's own tables. */
        val exact: Boolean get() = unexpected.isEmpty()

        /** OctoWoW's own tables among those that differ. */
        val ownDifferent: Int get() = (missing + different).count { it in own }
    }

    fun verify(dbcFolder: File): Report {
        val missing = mutableListOf<String>()
        val different = mutableListOf<String>()
        for ((name, expected) in hashes) {
            val file = ClientManifest.find(dbcFolder, name)
            if (file == null || !file.isFile) {
                missing += name
                continue
            }
            val hash = file.inputStream().use { ClientManifest.sha256(it) }
            if (!hash.equals(expected, ignoreCase = true)) different += name
        }
        return Report(hashes.size, missing, different, own)
    }

    companion object {
        fun parse(text: String, own: String = ""): DbcManifest = DbcManifest(
            text.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line ->
                    val parts = line.split('\t')
                    require(parts.size == 2 && '/' !in parts[0]) { "Bad DBC line: $line" }
                    parts[0] to parts[1].lowercase()
                },
            own.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet(),
        )
    }
}
