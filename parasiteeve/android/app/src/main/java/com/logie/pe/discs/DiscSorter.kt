// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.pe.discs

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Tells which of the files a player picked are which disc of a two-disc game, and what to start
 * each disc from. Plain files only, so it runs in the JVM unit tests too.
 */
object DiscSorter {
    /** Disc image formats the core opens, in the order to prefer them as the file to start. */
    val START_ORDER = listOf("cue", "toc", "chd", "pbp", "cbn", "iso", "img", "mdf", "bin")

    const val DISCS = 2

    class SortException(val reason: Reason, val detail: String = "") : IOException(reason.name) {
        enum class Reason { NO_DISC, MISSING_TRACKS, TOO_MANY_DISCS }
    }

    /** The file to start the disc in [dir] from, or null if there's none. */
    fun startFile(dir: File): File? {
        val files = dir.listFiles()?.filter { it.isFile } ?: return null
        return START_ORDER.firstNotNullOfOrNull { extension ->
            files.firstOrNull { it.extension.equals(extension, ignoreCase = true) }
        }
    }

    /**
     * Sorts the files in [dir] into a folder per disc in it (disc1/, disc2/), each holding a disc
     * the core can start, with a .cue's tracks all there (their names' upper and lower case matched
     * to what the .cue says). The files go to [disc] if given; otherwise to the discs their names
     * say ("Disc 1", "CD2", ...), in name order if they don't. Returns each disc's start file.
     */
    fun sort(dir: File, disc: Int?): Map<Int, File> {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        val tracksOf = HashMap<File, List<String>>()
        for (cue in files.filter { it.extension.equals("cue", ignoreCase = true) }) {
            tracksOf[cue] = cueTracks(cue)
        }
        // With a .cue, the .bin files are its tracks, not discs of their own.
        val starts = files.filter { file ->
            val extension = file.extension.lowercase()
            extension in START_ORDER && !(extension == "bin" && tracksOf.isNotEmpty())
        }.sortedBy { it.name.lowercase() }
        if (starts.isEmpty()) throw SortException(SortException.Reason.NO_DISC)
        if (starts.size > DISCS || (disc != null && starts.size > 1)) {
            throw SortException(SortException.Reason.TOO_MANY_DISCS)
        }

        val numbered = starts.associateWith { discNumber(it.name) }
        val assigned: Map<Int, File> = when {
            disc != null -> mapOf(disc to starts.single())
            starts.size == 1 -> mapOf((numbered[starts[0]] ?: 1) to starts[0])
            numbered.values.toSet() == setOf(1, 2) -> numbered.entries.associate { (file, number) -> number!! to file }
            else -> mapOf(1 to starts[0], 2 to starts[1])
        }

        val missing = ArrayList<String>()
        val moves = ArrayList<Pair<File, File>>()
        for ((number, start) in assigned) {
            val folder = File(dir, "disc$number")
            moves.add(start to File(folder, start.name))
            for (track in tracksOf[start].orEmpty()) {
                val file = files.firstOrNull { it.name == track } ?: files.firstOrNull { it.name.equals(track, ignoreCase = true) }
                if (file == null) missing.add(track) else moves.add(file to File(folder, track))
            }
            // Subchannel data (.sbi) goes with the disc of the same name.
            files.filter {
                it.extension.equals("sbi", ignoreCase = true) && it.nameWithoutExtension.equals(start.nameWithoutExtension, ignoreCase = true)
            }.forEach { moves.add(it to File(folder, it.name)) }
        }
        if (missing.isNotEmpty()) throw SortException(SortException.Reason.MISSING_TRACKS, missing.joinToString(", "))

        for ((from, to) in moves) {
            if (!from.exists()) continue
            to.parentFile?.mkdirs()
            if (!from.renameTo(to)) throw IOException("Can't sort ${from.name}")
        }
        return assigned.mapValues { (number, start) -> File(File(dir, "disc$number"), start.name) }
    }

    /** The track files a .cue names, without folders. */
    fun cueTracks(cue: File): List<String> {
        val tracks = Regex("""^\s*FILE\s+"?(.+?)"?\s+\S+\s*$""", RegexOption.IGNORE_CASE)
        return cue.readLines().mapNotNull { line ->
            tracks.find(line)?.groupValues?.get(1)?.substringAfterLast('/')?.substringAfterLast('\\')
        }
    }

    /** The disc a file's name says it is: "(Disc 2)", "CD1", "disk_2", ... */
    fun discNumber(name: String): Int? =
        Regex("""(?i)(?:disc|disk|cd)[\s_-]*0*([12])(?!\d)""").find(name)?.groupValues?.get(1)?.toInt()

    /** A PSP .pbp made from more than one disc: its disc data starts with PSTITLEIMG, not PSISOIMG. */
    fun isMultiDiscPbp(file: File): Boolean {
        if (!file.extension.equals("pbp", ignoreCase = true)) return false
        return runCatching {
            RandomAccessFile(file, "r").use { pbp ->
                val header = ByteArray(0x28)
                pbp.readFully(header)
                if (header[0] != 0.toByte() || header[1] != 'P'.code.toByte()) return false
                val psar = (header[0x24].toLong() and 0xff) or ((header[0x25].toLong() and 0xff) shl 8) or
                    ((header[0x26].toLong() and 0xff) shl 16) or ((header[0x27].toLong() and 0xff) shl 24)
                pbp.seek(psar)
                val magic = ByteArray(10)
                pbp.readFully(magic)
                String(magic, Charsets.US_ASCII) == "PSTITLEIMG"
            }
        }.getOrDefault(false)
    }
}
