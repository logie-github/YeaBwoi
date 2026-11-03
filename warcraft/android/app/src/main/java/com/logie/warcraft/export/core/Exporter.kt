// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.export.core

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class ExportCancelled : IOException("Stopped before the end.")

/** How far a run is: by files and by bytes. [skipped] files were already at the destination. */
data class ExportProgress(
    val files: Int,
    val totalFiles: Int,
    val bytes: Long,
    val totalBytes: Long,
    val skipped: Int,
    val current: String?,
)

/** What the export knows of the app, for export.json. */
class ExportContext(
    val appVersion: String,
    val sourceFilesDir: String,
    val createdAt: String,
    /** The preferences' names and their `prefs/<name>.json` text. */
    val prefs: Map<String, String>,
)

/**
 * Copies the planned files to the [ExportSink], resumably and verifiably:
 *
 * - The SHA-256 is computed on the stream as it is copied (nothing is read twice).
 * - `checksums.partial` at the destination lists the files copied completely, with the size and the
 *   source's modified time. A run that was interrupted (or run again later) skips a file that is
 *   listed with the same size and modified time and is at the destination with that size; a file
 *   that changed, or whose copy was cut off (it isn't listed), is copied again from its start.
 * - `export.json` is deleted first and written last: its presence means the export finished.
 */
object Exporter {
    private const val BUFFER = 1 shl 20
    private const val FLUSH_EVERY_MS = 10_000L
    private const val FLUSH_EVERY_FILES = 500

    fun filePath(path: String) = "files/$path"

    fun run(
        source: SourceFs,
        plan: ExportPlan,
        sink: ExportSink,
        context: ExportContext,
        onProgress: (ExportProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ExportManifest {
        // A finished export being made again isn't finished until this run is.
        sink.delete(ExportManifest.FILE)

        val done = HashMap<String, Checksums.Done>(
            sink.readText(Checksums.PARTIAL)?.let { Checksums.parsePartial(it) } ?: emptyMap(),
        )
        val planned = plan.files.map { it.path }.toHashSet()
        done.keys.retainAll(planned)

        var doneFiles = 0
        var doneBytes = 0L
        var skipped = 0
        var lastFlush = System.currentTimeMillis()
        var sinceFlush = 0
        fun flush() {
            sink.writeText(Checksums.PARTIAL, Checksums.writePartial(done))
            lastFlush = System.currentTimeMillis()
            sinceFlush = 0
        }
        fun progress(current: String?) = onProgress(ExportProgress(doneFiles, plan.files.size, doneBytes, plan.totalBytes, skipped, current))

        try {
            progress(null)
            for (file in plan.files) {
                if (isCancelled()) throw ExportCancelled()
                val known = done[file.path]
                val there = sink.size(filePath(file.path))
                if (known != null && known.size == file.size && known.modified == file.modified && there == file.size) {
                    skipped++
                    doneFiles++
                    doneBytes += file.size
                    progress(file.path)
                    continue
                }
                // (not listed until it's whole)
                done.remove(file.path)
                val result = copy(source, file, sink) { read ->
                    if (isCancelled()) throw ExportCancelled()
                    doneBytes += read
                    progress(file.path)
                }
                done[file.path] = Checksums.Done(result.first, result.second, file.modified)
                doneFiles++
                progress(file.path)
                sinceFlush++
                if (sinceFlush >= FLUSH_EVERY_FILES || System.currentTimeMillis() - lastFlush >= FLUSH_EVERY_MS || file.size >= (64L shl 20)) flush()
            }
        } catch (e: Throwable) {
            // What was copied so far is kept in the working file: running again carries on from there.
            runCatching { flush() }
            throw e
        }
        flush()

        for ((name, text) in context.prefs) sink.writeText(PrefsDump.path(name), text)

        val lines = plan.files.map { f -> done.getValue(f.path).let { ChecksumLine(it.sha256, it.size, f.path) } }
        sink.writeText(Checksums.FILE, Checksums.write(lines))

        val manifest = ExportManifest(
            format = ExportManifest.FORMAT,
            app = ExportManifest.APP,
            appVersion = context.appVersion,
            createdAt = context.createdAt,
            sourceFilesDir = context.sourceFilesDir,
            files = lines.size,
            bytes = lines.sumOf { it.size },
            folders = plan.folders,
            excluded = plan.excludedTopLevel,
            checksums = Checksums.FILE,
            prefs = context.prefs.keys.sorted(),
            excludedInside = plan.excludedInside,
        )
        sink.writeText(ExportManifest.FILE, manifest.toJson())
        return manifest
    }

    /** Copies one file, hashing as it goes. Returns the SHA-256 and the number of bytes written. */
    private fun copy(source: SourceFs, file: PlannedFile, sink: ExportSink, onRead: (Int) -> Unit): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        source.open(file.path).use { input ->
            sink.write(filePath(file.path)).use { output ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                    total += n
                    onRead(n)
                }
            }
        }
        return hex(digest.digest()) to total
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun sha256(input: InputStream, onRead: (Int) -> Unit = {}): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        var total = 0L
        input.use {
            while (true) {
                val n = it.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                total += n
                onRead(n)
            }
        }
        return hex(digest.digest()) to total
    }
}

/** What re-reading the destination found. */
data class VerifyReport(
    val checked: Int,
    val bytes: Long,
    val missing: List<String>,
    val wrongSize: List<String>,
    val wrongChecksum: List<String>,
    /** Why the export can't be checked at all (no export.json, an unreadable checksum list), else null. */
    val problem: String?,
) {
    val ok: Boolean get() = problem == null && missing.isEmpty() && wrongSize.isEmpty() && wrongChecksum.isEmpty()
}

object ExportVerifier {
    /**
     * Re-reads every file under `files/` at the destination and compares it with checksums.tsv: its
     * size and its SHA-256. Reports what is missing or differs rather than stopping at the first,
     * and takes those files off `checksums.partial` so that running the export again repairs them.
     */
    fun verify(
        sink: ExportSink,
        onProgress: (ExportProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): VerifyReport {
        val manifest = sink.readText(ExportManifest.FILE)?.let { runCatching { ExportManifest.parse(it) }.getOrNull() }
            ?: return VerifyReport(0, 0, emptyList(), emptyList(), emptyList(), "export.json is missing or damaged: the export didn't finish.")
        val lines = sink.readText(manifest.checksums)?.let { runCatching { Checksums.parse(it) }.getOrNull() }
            ?: return VerifyReport(0, 0, emptyList(), emptyList(), emptyList(), "${manifest.checksums} is missing or damaged.")
        if (lines.size != manifest.files) {
            return VerifyReport(0, 0, emptyList(), emptyList(), emptyList(), "${manifest.checksums} lists ${lines.size} files, export.json says ${manifest.files}.")
        }

        val total = lines.sumOf { it.size }
        var bytes = 0L
        var files = 0
        val missing = ArrayList<String>()
        val wrongSize = ArrayList<String>()
        val wrongChecksum = ArrayList<String>()
        for (line in lines) {
            if (isCancelled()) throw ExportCancelled()
            val path = Exporter.filePath(line.path)
            val size = sink.size(path)
            val input = if (size == null) null else sink.read(path)
            when {
                input == null -> { missing += line.path; bytes += line.size }
                size != line.size -> { input.close(); wrongSize += line.path; bytes += line.size }
                else -> {
                    val (hash, read) = Exporter.sha256(input) {
                        if (isCancelled()) throw ExportCancelled()
                        bytes += it
                        onProgress(ExportProgress(files, lines.size, bytes, total, 0, line.path))
                    }
                    if (read != line.size) wrongSize += line.path else if (hash != line.sha256) wrongChecksum += line.path
                }
            }
            files++
            onProgress(ExportProgress(files, lines.size, bytes, total, 0, line.path))
        }
        // A file found wrong is forgotten by the working file, so the next export copies it again
        // (otherwise it would be skipped: its size and the source's modified time still match).
        val bad = (missing + wrongSize + wrongChecksum).toSet()
        if (bad.isNotEmpty()) {
            sink.readText(Checksums.PARTIAL)?.let { Checksums.parsePartial(it) }?.let { done ->
                if (done.keys.any { it in bad }) sink.writeText(Checksums.PARTIAL, Checksums.writePartial(done.filterKeys { it !in bad }))
            }
        }
        return VerifyReport(lines.size, total, missing, wrongSize, wrongChecksum, null)
    }
}
