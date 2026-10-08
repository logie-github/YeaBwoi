// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.dolphinxd

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.text.format.Formatter
import org.dolphinemu.dolphinemu.R
import org.dolphinemu.dolphinemu.utils.DirectoryInitialization
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream

/** File helpers shared by the texture pack and mod importers. */
object XdFiles {
    /** Dolphin's user folder (Android/data/com.logie.dolphinxd/files). */
    val userDirectory: File
        get() = File(DirectoryInitialization.getUserDirectory())

    /** Dolphin XD's own files inside the user folder. */
    val xdDirectory: File
        get() = File(userDirectory, "XD")

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Size in bytes, or -1 if the provider doesn't say. */
    fun size(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                if (fd.statSize >= 0) return fd.statSize
            }
        } catch (_: Exception) {
        }
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L }
                ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    /** Opens a game path the way Dolphin stores it: a content URI or a plain file path. */
    fun openGame(context: Context, path: String): InputStream =
        if (path.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(path))
                ?: throw XdTaskException(context.getString(R.string.xd_error_unreadable_rom))
        } else {
            FileInputStream(path)
        }

    /** A folder-name-safe version of [name]. */
    fun slug(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .ifEmpty { "item" }

    /** [parent]/[prefix][slug], with a number added if that's taken. */
    fun uniqueChild(parent: File, prefix: String, name: String): File {
        val base = prefix + slug(name)
        var candidate = File(parent, base)
        var n = 2
        while (candidate.exists()) {
            candidate = File(parent, "$base-$n")
            n++
        }
        return candidate
    }

    fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    /** Fails with a readable message unless [dir] has [bytes] free. */
    fun requireSpace(context: Context, dir: File, bytes: Long) {
        dir.mkdirs()
        val available = try {
            StatFs(dir.path).availableBytes
        } catch (_: Exception) {
            return
        }
        if (available < bytes) {
            throw XdTaskException(context.getString(R.string.xd_no_space, formatSize(context, bytes)))
        }
    }

    /** Counts the bytes read through it, for progress bars. */
    class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile
        var count = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            count += skipped
            return skipped
        }
    }
}
