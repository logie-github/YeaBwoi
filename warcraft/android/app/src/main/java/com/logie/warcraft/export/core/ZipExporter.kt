// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.export.core

import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry

/**
 * "Export everything to Blizzard" as one zip file: the same contract as the folder export (a `files/`
 * folder, `prefs/<name>.json`, `checksums.tsv`, and `export.json` last), written as the entries of a
 * single archive. One file can be put anywhere a file can (the Blizzard app's picker takes it as it
 * is), and a case-twin can't be lost to shared storage, so nothing is renamed.
 *
 * Streamed in one pass: each file's SHA-256 is taken as it is written, so `checksums.tsv` and
 * `export.json` follow the files at the end (the archive is complete only when `export.json` is in it).
 * Entries are stored without compression (the games' files are packed already) with Zip64 headers,
 * which archives of many gigabytes need. Not resumable: a stopped export is made again.
 */
object ZipExporter {
    fun run(
        source: SourceFs,
        plan: ExportPlan,
        out: OutputStream,
        context: ExportContext,
        onProgress: (ExportProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ExportManifest {
        val zip = ZipArchiveOutputStream(BufferedOutputStream(out, 1 shl 20))
        zip.setUseZip64(Zip64Mode.Always)
        zip.setMethod(ZipEntry.DEFLATED)
        zip.setLevel(Deflater.NO_COMPRESSION)

        val lines = ArrayList<ChecksumLine>(plan.files.size)
        var doneFiles = 0
        var doneBytes = 0L
        fun progress(current: String?) = onProgress(ExportProgress(doneFiles, plan.files.size, doneBytes, plan.totalBytes, 0, current))
        progress(null)
        try {
            val buffer = ByteArray(1 shl 20)
            for (file in plan.files) {
                if (isCancelled()) throw ExportCancelled()
                val entry = ZipArchiveEntry(Exporter.filePath(file.path))
                entry.time = file.modified
                zip.putArchiveEntry(entry)
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                source.open(file.path).use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (isCancelled()) throw ExportCancelled()
                        digest.update(buffer, 0, n)
                        zip.write(buffer, 0, n)
                        total += n
                        doneBytes += n
                        progress(file.path)
                    }
                }
                zip.closeArchiveEntry()
                lines += ChecksumLine(Exporter.hex(digest.digest()), total, file.path)
                doneFiles++
                progress(file.path)
            }
            for ((name, text) in context.prefs) text(zip, PrefsDump.path(name), text)
            text(zip, Checksums.FILE, Checksums.write(lines))
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
                renames = emptyMap(),
            )
            text(zip, ExportManifest.FILE, manifest.toJson())
            zip.finish()
            zip.close()
            return manifest
        } catch (e: Throwable) {
            runCatching { zip.close() }
            throw e
        }
    }

    private fun text(zip: ZipArchiveOutputStream, name: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        zip.putArchiveEntry(ZipArchiveEntry(name))
        zip.write(bytes)
        zip.closeArchiveEntry()
    }

    /**
     * Reads the archive once, hashing every file under `files/`, and compares with its `checksums.tsv`
     * (the same checks as the folder export's Verify: missing, wrong size, wrong checksum).
     */
    fun verify(input: InputStream, onProgress: (ExportProgress) -> Unit = {}, isCancelled: () -> Boolean = { false }): VerifyReport {
        val seen = HashMap<String, Pair<String, Long>>()
        var checksums: String? = null
        var manifestText: String? = null
        var bytes = 0L
        var files = 0
        try {
            ZipArchiveInputStream(input, "UTF-8", true, true).use { zip ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (isCancelled()) throw ExportCancelled()
                    val name = entry.name
                    when {
                        name == Checksums.FILE -> checksums = zip.readBytes().toString(Charsets.UTF_8)
                        name == ExportManifest.FILE -> manifestText = zip.readBytes().toString(Charsets.UTF_8)
                        name.startsWith("files/") -> {
                            val digest = MessageDigest.getInstance("SHA-256")
                            var total = 0L
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                if (isCancelled()) throw ExportCancelled()
                                digest.update(buffer, 0, n)
                                total += n
                                bytes += n
                            }
                            seen[name.removePrefix("files/")] = Exporter.hex(digest.digest()) to total
                            files++
                            onProgress(ExportProgress(files, 0, bytes, 0, 0, name))
                        }
                        else -> {}
                    }
                }
            }
        } catch (e: ExportCancelled) {
            throw e
        } catch (e: IOException) {
            return VerifyReport(0, 0, emptyList(), emptyList(), emptyList(), "The zip can't be read to its end: ${e.message}. The export didn't finish, or the file is damaged.")
        }
        val manifest = manifestText?.let { runCatching { ExportManifest.parse(it) }.getOrNull() }
            ?: return VerifyReport(0, 0, emptyList(), emptyList(), emptyList(), "export.json is missing or damaged: the export didn't finish.")
        val lines = checksums?.let { runCatching { Checksums.parse(it) }.getOrNull() }
            ?: return VerifyReport(0, 0, emptyList(), emptyList(), emptyList(), "${manifest.checksums} is missing or damaged.")
        if (lines.size != manifest.files) {
            return VerifyReport(0, 0, emptyList(), emptyList(), emptyList(), "${manifest.checksums} lists ${lines.size} files, export.json says ${manifest.files}.")
        }
        val missing = ArrayList<String>()
        val wrongSize = ArrayList<String>()
        val wrongChecksum = ArrayList<String>()
        for (line in lines) {
            val got = seen[line.path]
            when {
                got == null -> missing += line.path
                got.second != line.size -> wrongSize += line.path
                got.first != line.sha256 -> wrongChecksum += line.path
            }
        }
        return VerifyReport(lines.size, lines.sumOf { it.size }, missing, wrongSize, wrongChecksum, null)
    }
}
