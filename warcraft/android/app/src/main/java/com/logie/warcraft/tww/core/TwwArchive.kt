// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.tww.core

import com.logie.warcraft.turtle.core.SafeFs
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.Locale
import java.util.zip.CRC32

/** Extracts into a new temporary folder. Installation is a separate, validated rename. */
object TwwArchive {
    class ArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private const val MAX_FILES = 2_000_000
    private const val MAX_BYTES = 2_000_000_000_000L

    fun extract(
        archive: File,
        staging: File,
        cancelled: () -> Boolean = { false },
        progress: (Long, String) -> Unit = { _, _ -> },
    ) {
        if (!archive.isFile || archive.length() == 0L) throw ArchiveException("Archive is missing or empty: ${archive.name}")
        if (staging.exists()) throw ArchiveException("Extraction needs a new, empty staging directory: ${staging.path}")
        if (Files.isSymbolicLink(staging.toPath())) throw ArchiveException("Staging directory is a symbolic link")
        Files.createDirectories(staging.toPath())
        val fs = SafeFs(staging)
        var count = 0
        var written = 0L
        val names = hashSetOf<String>()
        val spellings = hashMapOf<String, String>()

        fun checkCancelled() {
            if (cancelled()) throw ArchiveException("Download or extraction cancelled")
        }

        fun target(name: String, directory: Boolean): File {
            checkCancelled()
            if (++count > MAX_FILES) throw ArchiveException("Archive contains too many entries")
            val normalized = safeName(name, directory)
            normalized.split('/').runningReduce { parent, child -> "$parent/$child" }.forEach { path ->
                val before = spellings.putIfAbsent(path.lowercase(Locale.ROOT), path)
                if (before != null && before != path) throw ArchiveException("Archive paths differ only by Windows letter case: $before, $path")
            }
            if (!names.add(normalized.lowercase(Locale.ROOT))) throw ArchiveException("Archive has duplicate Windows path: $name")
            val file = fs.check(File(staging, normalized))
            if (Files.isSymbolicLink(file.toPath())) throw ArchiveException("Archive target is a symbolic link: $name")
            if (directory) fs.mkdirs(file) else file.parentFile?.let { fs.mkdirs(it) }
            return file
        }

        fun copy(input: InputStream, output: File, expected: Long, expectedCrc: Long = -1L) {
            var bytes = 0L
            val crc = CRC32()
            output.outputStream().buffered(1 shl 16).use { stream ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytes += n
                    written += n
                    if (written > MAX_BYTES || (expected >= 0 && bytes > expected)) throw ArchiveException("Archive expanded beyond its declared size: ${output.name}")
                    if (staging.usableSpace < 8L * 1024 * 1024) throw ArchiveException("Not enough free space while extracting ${output.name}")
                    stream.write(buffer, 0, n)
                    crc.update(buffer, 0, n)
                    progress(written, output.relativeTo(staging).path)
                }
            }
            if (expected >= 0 && bytes != expected) throw ArchiveException("Incomplete archive entry ${output.name}: $bytes of $expected bytes")
            if (expectedCrc >= 0 && crc.value != expectedCrc) throw ArchiveException("Archive checksum failed: ${output.name}")
        }

        try {
            val magic = archive.inputStream().use { input -> ByteArray(6).let { bytes -> input.read(bytes); bytes } }
            when {
                magic[0] == 0x50.toByte() && magic[1] == 0x4b.toByte() -> {
                    ZipFile.builder().setFile(archive).get().use { zip ->
                        val entries = zip.entries
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.isUnixSymlink || (entry.unixMode and 0xf000) !in setOf(0, 0x4000, 0x8000)) {
                                throw ArchiveException("Archive links or special files are not supported: ${entry.name}")
                            }
                            if (!zip.canReadEntryData(entry)) throw ArchiveException("Encrypted or unsupported ZIP entry: ${entry.name}")
                            val file = target(entry.name, entry.isDirectory)
                            if (!entry.isDirectory) zip.getInputStream(entry).use { copy(it, file, entry.size, entry.crc) }
                        }
                    }
                }
                magic.contentEquals(byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)) -> {
                    SevenZFile.builder().setFile(archive).get().use { seven ->
                        while (true) {
                            checkCancelled()
                            val entry = seven.nextEntry ?: break
                            // 7z stores Unix mode in the upper 16 bits and Windows attributes below.
                            if (entry.hasWindowsAttributes &&
                                ((entry.windowsAttributes and 0x400) != 0 ||
                                    ((entry.windowsAttributes ushr 16) and 0xf000) !in setOf(0, 0x4000, 0x8000))) {
                                throw ArchiveException("Archive links or special files are not supported: ${entry.name}")
                            }
                            if (entry.isAntiItem) throw ArchiveException("7z deletion entries are not supported: ${entry.name}")
                            val file = target(entry.name, entry.isDirectory)
                            if (!entry.isDirectory) {
                                val input = object : InputStream() {
                                    override fun read(): Int = seven.read()
                                    override fun read(b: ByteArray, off: Int, len: Int): Int = seven.read(b, off, len)
                                }
                                copy(input, file, entry.size, if (entry.hasCrc) entry.crcValue else -1L)
                            }
                        }
                    }
                }
                else -> throw ArchiveException("Unsupported archive format. Import a ZIP or 7z archive, or an extracted folder.")
            }
            if (count == 0 || written == 0L) throw ArchiveException("Archive contains no files")
        } catch (e: Exception) {
            // Staging is exclusively ours. Deletion never follows links from the archive.
            runCatching { fs.deleteContents(staging); Files.deleteIfExists(staging.toPath()) }
            if (e is ArchiveException) throw e
            throw ArchiveException("Could not extract ${archive.name}: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    internal fun safeName(name: String, directory: Boolean = false): String {
        val path = name.replace('\\', '/').let { if (directory) it.trimEnd('/') else it }
        val parts = path.split('/')
        if (path.isEmpty() || path.startsWith('/') || parts.any { part ->
                part.isEmpty() || part == "." || part == ".." || part.last() in listOf(' ', '.') ||
                    part.any { it < ' ' || it in ":<>\"|?*" } ||
                    part.substringBefore('.').uppercase(Locale.ROOT) in WINDOWS_DEVICES
            }) throw ArchiveException("Unsafe archive path: $name")
        return path
    }

    private val WINDOWS_DEVICES = setOf("CON", "PRN", "AUX", "NUL", "CLOCK$") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
}
