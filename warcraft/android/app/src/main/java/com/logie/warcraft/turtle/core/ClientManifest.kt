// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The Turtle WoW client Tortoise-WoW is made for: 1.18.1 (build 7272) with its 2026-04-12 hotfixes,
 * Turtle WoW's final client. Its files with their sizes and SHA-256 (`assets/turtle/
 * client-manifest.tsv`, written by scripts/turtle-manifests.py from Tortoise's
 * tools/dbc_verification/manifest.json) and the files the final client no longer has.
 *
 * The app never downloads the client: Turtle WoW was shut down by a court order that also bars
 * distributing its client. The user brings their own copy, and [verify] says whether it's this one.
 */
class ClientManifest(val files: List<Entry>, val gone: List<String>) {
    data class Entry(val path: String, val size: Long, val sha256: String)

    /** How a client folder compares. */
    data class Report(
        val checked: Int,
        val missing: List<String>,
        /** Files there with another size or checksum (another version of the client). */
        val different: List<String>,
        /** Files the final client no longer has (left from older versions: harmless, listed). */
        val leftovers: List<String>,
    ) {
        /** Exactly the client Tortoise-WoW is made for. */
        val exact: Boolean get() = missing.isEmpty() && different.isEmpty()

        /** At least a client: the program and the data the server's data is made from. */
        fun usable(manifest: ClientManifest): Boolean = manifest.required.none { it in missing }
    }

    /** What the game and the map tools can't do without. */
    val required: List<String> = files.map { it.path }.filter { path ->
        path.equals(EXE, ignoreCase = true) || path.equals("Data/dbc.MPQ", ignoreCase = true) ||
            path.equals("Data/terrain.MPQ", ignoreCase = true) || path.equals("Data/patch.MPQ", ignoreCase = true)
    }

    /** All the client's files together, in bytes. */
    val totalSize: Long get() = files.sumOf { it.size }

    /**
     * Compares the client in [folder] (names matched without regard to case, as Windows does).
     * [deep]: checksums too (minutes for 9 GB), else sizes only. [onProgress] gets the bytes read
     * so far and the file being read.
     */
    fun verify(folder: File, deep: Boolean, onProgress: (Long, String) -> Unit = { _, _ -> }): Report {
        val missing = mutableListOf<String>()
        val different = mutableListOf<String>()
        var done = 0L
        for (entry in files) {
            val file = find(folder, entry.path)
            if (file == null || !file.isFile) {
                missing += entry.path
                done += entry.size
                continue
            }
            if (file.length() != entry.size) {
                different += entry.path
                done += entry.size
                onProgress(done, entry.path)
                continue
            }
            if (deep) {
                val base = done
                val hash = file.inputStream().use { sha256(it) { read -> onProgress(base + read, entry.path) } }
                if (!hash.equals(entry.sha256, ignoreCase = true)) different += entry.path
            }
            done += entry.size
            onProgress(done, entry.path)
        }
        val leftovers = gone.filter { find(folder, it)?.exists() == true }
        return Report(files.size, missing, different, leftovers)
    }

    companion object {
        /** The program the game runs (Turtle's own WoW.exe, build 7272). */
        const val EXE = "WoW.exe"

        fun parse(text: String): ClientManifest {
            val files = mutableListOf<Entry>()
            val gone = mutableListOf<String>()
            for (line in text.lineSequence()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val parts = line.split('\t')
                require(parts.size == 4) { "Bad manifest line: $line" }
                val path = parts[1]
                require(isSafePath(path)) { "Unsafe path in the manifest: $path" }
                when (parts[0]) {
                    "file" -> files += Entry(path, parts[2].toLong(), parts[3].lowercase())
                    "gone" -> gone += path
                    else -> error("Bad manifest line: $line")
                }
            }
            return ClientManifest(files, gone)
        }

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
}

/**
 * The checksums of the DBC files extracted from that client (`assets/turtle/dbc-sha256.tsv`, from
 * Tortoise's dbc_verifier.py): the server's data has to come from exactly that client, or the
 * server and the game disagree on spells, items and maps.
 */
class DbcManifest(val hashes: Map<String, String>) {
    data class Report(val checked: Int, val missing: List<String>, val different: List<String>) {
        val exact: Boolean get() = missing.isEmpty() && different.isEmpty()
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
        return Report(hashes.size, missing, different)
    }

    companion object {
        fun parse(text: String): DbcManifest = DbcManifest(
            text.lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line ->
                    val parts = line.split('\t')
                    require(parts.size == 2 && '/' !in parts[0]) { "Bad DBC line: $line" }
                    parts[0] to parts[1].lowercase()
                }
        )
    }
}
