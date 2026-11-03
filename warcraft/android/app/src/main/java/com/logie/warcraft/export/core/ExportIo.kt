// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.export.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** One entry of a folder in the app's storage. Links and special files (sockets, pipes) have [regular] and [directory] false. */
data class SourceEntry(val name: String, val directory: Boolean, val regular: Boolean, val size: Long, val modified: Long)

/** The app's `files/` folder, as the export reads it. Paths are relative to it, '/' separated, "" is the folder itself. */
interface SourceFs {
    fun list(dir: String): List<SourceEntry>
    fun open(path: String): InputStream
}

/** [SourceFs] over a real folder. Never follows links: a link inside the app's storage is skipped, not copied. */
class JavaFileSource(private val root: File) : SourceFs {
    override fun list(dir: String): List<SourceEntry> {
        val folder = if (dir.isEmpty()) root else File(root, dir)
        val names = folder.list() ?: throw IOException("Can't read the folder $dir")
        return names.sorted().map { name ->
            val path = File(folder, name).toPath()
            val attributes = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
            if (attributes == null) SourceEntry(name, directory = false, regular = false, size = 0, modified = 0)
            else SourceEntry(
                name, attributes.isDirectory, attributes.isRegularFile,
                if (attributes.isRegularFile) attributes.size() else 0L, attributes.lastModifiedTime().toMillis(),
            )
        }
    }

    override fun open(path: String): InputStream = File(root, path).inputStream()
}

/**
 * Where the export is written: the folder the user picked, below `WoW-export/`. Paths are relative
 * to the export's folder with '/' separators (`files/client/Wow.exe`, `checksums.tsv`). The app
 * implements it over Android's storage access framework; the tests with memory.
 */
interface ExportSink {
    /** The size of the file at [path] if there is one, else null. */
    fun size(path: String): Long?

    /** Opens [path] for writing from the start, replacing what's there and making its folders. */
    fun write(path: String): OutputStream

    fun read(path: String): InputStream?

    /** Deletes the file at [path] if there is one. */
    fun delete(path: String)

    /**
     * Where the destination really put the file last written at [path], when that isn't [path]
     * (a provider that renamed it): the path it has there. Null when it's where it was asked to be.
     */
    fun storedAs(path: String): String? = null
}

fun ExportSink.readText(path: String): String? = read(path)?.use { it.readBytes().toString(Charsets.UTF_8) }

fun ExportSink.writeText(path: String, text: String) = write(path).use { it.write(text.toByteArray(Charsets.UTF_8)) }
