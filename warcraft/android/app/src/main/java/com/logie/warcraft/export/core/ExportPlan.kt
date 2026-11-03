// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.export.core

import java.io.IOException

/** One file to copy: [path] relative to `files/`. */
data class PlannedFile(val path: String, val size: Long, val modified: Long) {
    val topLevel: String get() = path.substringBefore('/')
}

/** A left-out entry: [path] relative to `files/` and why. */
data class Excluded(val path: String, val reason: String)

/** What a scan of `files/` found. */
class ExportPlan(
    /** Sorted by path. */
    val files: List<PlannedFile>,
    val excluded: List<Excluded>,
    /** Links and special files that were skipped (they hold no data of their own). */
    val skipped: Int,
) {
    val totalBytes: Long = files.sumOf { it.size }

    /** The top-level folders that have files in the export, sorted. */
    val folders: List<String> get() = files.map { it.topLevel }.filter { it != "" }.distinct().sorted()

    /** Exclusions of whole top-level entries, as export.json's `excluded` lists them (names, sorted). */
    val excludedTopLevel: List<String> get() = excluded.filter { '/' !in it.path }.map { it.path }.distinct().sorted()

    /** Exclusions inside exported folders (`turtle/downloads`...), sorted. */
    val excludedInside: List<String> get() = excluded.filter { '/' in it.path }.map { it.path }.distinct().sorted()

    /** Files and bytes per environment, for the screen. */
    fun byGroup(): Map<ExportRules.Group, Pair<Int, Long>> =
        files.groupBy { ExportRules.groupOf(it.topLevel) }.mapValues { (_, list) -> list.size to list.sumOf { it.size } }

    /** The plan without the files of the environments not in [groups]. */
    fun only(groups: Set<ExportRules.Group>): ExportPlan = ExportPlan(
        files.filter { ExportRules.groupOf(it.topLevel) in groups },
        excluded,
        skipped,
    )
}

object ExportPlanner {
    /**
     * Walks the whole of `files/` and decides what is exported, by [ExportRules]. A top-level entry
     * that is a file (not a folder) is exported unless a rule excludes it. Empty folders hold no
     * data and aren't recorded (the reader makes the folders its files need).
     */
    fun scan(source: SourceFs, isCancelled: () -> Boolean = { false }): ExportPlan {
        val files = ArrayList<PlannedFile>()
        val excluded = ArrayList<Excluded>()
        var skipped = 0

        fun walk(dir: String) {
            if (isCancelled()) throw IOException("cancelled")
            for (entry in source.list(dir)) {
                val path = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
                when {
                    entry.directory -> {
                        val reason = ExportRules.directoryReason(path)
                        if (reason != null) excluded += Excluded(path, reason) else walk(path)
                    }
                    entry.regular -> {
                        val reason = ExportRules.fileReason(path)
                        if (reason != null) {
                            // (a single file left out by name only matters at the top; elsewhere they are many and plain)
                            if ('/' !in path) excluded += Excluded(path, reason)
                        } else {
                            // (checksums.tsv is tab separated, a line per file: a name with either can't be listed)
                            if (path.any { it == '\t' || it == '\n' || it == '\r' }) throw IOException("A file name with a tab or line break can't be exported: $path")
                            files += PlannedFile(path, entry.size, entry.modified)
                        }
                    }
                    else -> skipped++
                }
            }
        }
        walk("")
        return ExportPlan(files.sortedBy { it.path }, excluded.sortedBy { it.path }, skipped)
    }

    /**
     * What still has to be written to [sink] for [plan]: the files it doesn't have complete, by the
     * same rule a run uses to skip a file (listed in `checksums.partial` with this size and modified
     * time, and there with this size). The free space needed at the destination.
     */
    fun remainingBytes(plan: ExportPlan, sink: ExportSink): Long {
        val done = sink.readText(Checksums.PARTIAL)?.let { Checksums.parsePartial(it) } ?: emptyMap()
        return plan.files.sumOf { file ->
            val known = done[file.path]
            val have = known != null && known.size == file.size && known.modified == file.modified && sink.size(Exporter.filePath(file.path)) == file.size
            if (have) 0L else file.size
        }
    }

    /** [needed] bytes fit where [free] are left (null: not known, so it's tried), with a [margin] for the manifest and folders. */
    fun fits(needed: Long, free: Long?, margin: Long = 64L shl 20): Boolean = free == null || free >= needed + margin
}
