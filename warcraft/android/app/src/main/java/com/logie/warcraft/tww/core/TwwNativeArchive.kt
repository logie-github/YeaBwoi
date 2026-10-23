// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww.core

import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 7-Zip handles BCJ2 and RAR. Listing is validated before any archive path is extracted. */
internal object TwwNativeArchive {
    private const val MAX_FILES = 2_000_000
    private const val MAX_BYTES = 2_000_000_000_000L

    /**
     * Whether a `7zz l -slt` entry is a folder. 7z archives have no `Folder` line, only
     * `Attributes`: its Windows letters come first (`D` for a folder, among `R`, `H`, `S`, `A`...),
     * then a space and any Unix mode. A hidden folder reads `HD`, so `D` can be anywhere in them.
     */
    internal fun isDirectory(block: Map<String, String>): Boolean =
        block["Folder"] == "+" || 'D' in block["Attributes"].orEmpty().substringBefore(' ')

    fun extract(executable: File, archive: File, staging: File, cancelled: () -> Boolean, progress: (Long, String) -> Unit) {
        if (!executable.isFile || !executable.canExecute()) throw TwwArchive.ArchiveException("The archive extractor is missing. Update or reinstall the app; your downloaded files will be retained.")
        val log = File.createTempFile("tww-extract-", ".log", staging.parentFile)
        fun command(arguments: List<String>, extracting: Boolean) {
            val process = ProcessBuilder(listOf(executable.path) + arguments).redirectErrorStream(true).redirectOutput(log).start()
            process.outputStream.close() // No password or interactive prompt may wait on the foreground job.
            try {
                var observed = -1L
                val deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(6)
                while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    if (cancelled() || Thread.currentThread().isInterrupted) throw TwwArchive.ArchiveException("Extraction cancelled")
                    if (System.nanoTime() > deadline) throw TwwArchive.ArchiveException("Archive extraction timed out")
                    if (log.length() > 256L * 1024 * 1024) throw TwwArchive.ArchiveException("Archive listing exceeds the supported size")
                    if (staging.usableSpace < 8L * 1024 * 1024) throw TwwArchive.ArchiveException("Not enough free space while extracting")
                    if (extracting && log.length() != observed) {
                        observed = log.length()
                        progress(0, "Unpacking archive")
                    }
                }
                if (cancelled()) throw TwwArchive.ArchiveException("Extraction cancelled")
                if (process.exitValue() != 0) {
                    val detail = log.inputStream().use { input ->
                        if (log.length() > 12000) input.skip(log.length() - 12000)
                        input.bufferedReader().readText()
                    }
                    throw TwwArchive.ArchiveException("7-Zip could not extract the archive (code ${process.exitValue()}). $detail")
                }
            } finally {
                if (process.isAlive) { process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly() }
            }
        }
        try {
            command(listOf("l", "-slt", "-ba", "-sccUTF-8", "--", archive.path), false)
            val entries = linkedMapOf<String, Pair<String, Long?>>()
            val spellings = hashMapOf<String, String>()
            var block = linkedMapOf<String, String>()
            var total = 0L
            fun entry() {
                if (block.isEmpty()) return
                val name = block["Path"] ?: throw TwwArchive.ArchiveException("Archive entry has no path")
                if (block.any { (key, value) -> key in setOf("Symbolic Link", "Hard Link", "Alternate Stream", "Reparse Point") && value.isNotEmpty() } ||
                    block["Encrypted"] == "+" || block["Attributes"].orEmpty().split(' ').any { it.startsWith('l') } ||
                    block["Anti"] == "+") throw TwwArchive.ArchiveException("Archive links, encrypted or special entries are unsupported: $name")
                val directory = isDirectory(block)
                val path = TwwArchive.safeName(name, directory)
                path.split('/').runningReduce { a, b -> "$a/$b" }.forEach { prefix ->
                    val previous = spellings.putIfAbsent(prefix.lowercase(Locale.ROOT), prefix)
                    if (previous != null && previous != prefix) throw TwwArchive.ArchiveException("Archive paths differ only by Windows letter case: $previous, $prefix")
                }
                val bytes = if (directory) null else (block["Size"]?.toLongOrNull()?.takeIf { it >= 0 }
                    ?: throw TwwArchive.ArchiveException("Archive has an invalid entry size: $name"))
                if (entries.put(path.lowercase(Locale.ROOT), path to bytes) != null) throw TwwArchive.ArchiveException("Duplicate archive path: $name")
                if (entries.size > MAX_FILES || (bytes ?: 0) > MAX_BYTES - total) throw TwwArchive.ArchiveException("Archive exceeds installation limits")
                total += bytes ?: 0
                block = linkedMapOf()
            }
            log.useLines { lines -> lines.forEach { line ->
                if (cancelled()) throw TwwArchive.ArchiveException("Extraction cancelled")
                if (line.isBlank()) entry() else {
                    val at = line.indexOf(" = ")
                    if (at >= 0 && block.put(line.substring(0, at), line.substring(at + 3)) != null) throw TwwArchive.ArchiveException("Ambiguous archive entry metadata")
                }
            } }; entry()
            if (entries.isEmpty() || total == 0L) throw TwwArchive.ArchiveException("Archive contains no files")
            if (staging.usableSpace < total + 256L * 1024 * 1024) throw TwwArchive.ArchiveException("Insufficient installation space: need $total bytes plus 256 MiB")
            command(listOf("x", "-y", "-bd", "-bb1", "-sccUTF-8", "-o${staging.path}", "--", archive.path), true)
            var written = 0L
            staging.walkTopDown().onEnter { dir ->
                if (Files.isSymbolicLink(dir.toPath())) throw TwwArchive.ArchiveException("Extracted directory is a symbolic link")
                true
            }.forEach { file ->
                if (cancelled()) throw TwwArchive.ArchiveException("Extraction cancelled")
                if (Files.isSymbolicLink(file.toPath())) throw TwwArchive.ArchiveException("Extracted symbolic link")
                if (file.isFile) {
                    val path = file.relativeTo(staging).invariantSeparatorsPath
                    val expected = entries[path.lowercase(Locale.ROOT)]
                    if (expected == null || expected.first != path || expected.second != file.length()) throw TwwArchive.ArchiveException("Unexpected or incomplete archive file: $path")
                    written += file.length(); progress(written, path)
                }
            }
            for ((path, bytes) in entries.values) {
                val file = File(staging, path)
                if (bytes == null && !file.isDirectory || bytes != null && (!file.isFile || file.length() != bytes)) throw TwwArchive.ArchiveException("Missing archive entry: $path")
            }
        } finally { log.delete() }
    }
}
