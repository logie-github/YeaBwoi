// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import com.logie.warcraft.export.core.ExportRules
import com.logie.warcraft.export.core.ExportSink
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The export's destination: `WoW-export/` inside the folder the user picked with Android's folder
 * picker (a storage access framework tree). Shared storage: nothing here uses file paths.
 *
 * A folder's listing is read once and kept, so a file's existence and size are looked up without
 * asking the provider again (a tree of tens of thousands of files would otherwise take hours).
 */
class SafExportSink(context: Context, private val tree: Uri) : ExportSink {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    private class Child(val id: String, val directory: Boolean, var size: Long, var actualName: String? = null)

    private val treeRoot = DocumentsContract.getTreeDocumentId(tree)
    private val listings = HashMap<String, MutableMap<String, Child>>()

    /** The folder id of `WoW-export/`, made if it isn't there. */
    private val exportRoot: String by lazy { child(treeRoot, ExportRules.ROOT_FOLDER, directory = true).id }

    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun children(folderId: String): MutableMap<String, Child> = listings.getOrPut(folderId) {
        val result = LinkedHashMap<String, Child>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val directory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                result[cursor.getString(1)] = Child(cursor.getString(0), directory, if (cursor.isNull(3)) -1L else cursor.getLong(3))
            }
        } ?: throw IOException("The folder can't be read. Pick it again.")
        result
    }

    /** The child [name] of [folderId], made if it isn't there. */
    private fun child(folderId: String, name: String, directory: Boolean): Child {
        val existing = children(folderId)[name]
        if (existing != null) {
            if (existing.directory != directory) throw IOException("$name is a ${if (directory) "file" else "folder"} where the export needs a ${if (directory) "folder" else "file"}.")
            return existing
        }
        val created = DocumentsContract.createDocument(
            resolver, uri(folderId), if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream", name,
        ) ?: throw IOException("Couldn't make $name in the destination.")
        val id = DocumentsContract.getDocumentId(created)
        // The provider may keep another name than the one asked for ("name (1).ext" beside a name that differs in case).
        val actual = if (directory) null else runCatching {
            resolver.query(created, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        return Child(id, directory, 0, actual?.takeIf { it != name }).also { children(folderId)[name] = it }
    }

    /** The folder for [path]'s folders (made as needed), and the file's name. */
    private fun parentOf(path: String, make: Boolean): Pair<String, String>? {
        val parts = path.split('/')
        var folder = exportRoot
        for (part in parts.dropLast(1)) {
            folder = if (make) child(folder, part, directory = true).id
            else children(folder)[part]?.takeIf { it.directory }?.id ?: return null
        }
        return folder to parts.last()
    }

    override fun size(path: String): Long? {
        val (folder, name) = parentOf(path, make = false) ?: return null
        val found = children(folder)[name] ?: return null
        if (found.directory) return null
        // (some providers don't say a size: the file is read to find out)
        if (found.size < 0) found.size = countBytes(found.id) ?: return null
        return found.size
    }

    private fun countBytes(id: String): Long? = resolver.openInputStream(uri(id))?.use { stream ->
        val buffer = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
        }
        total
    }

    override fun read(path: String): InputStream? {
        val (folder, name) = parentOf(path, make = false) ?: return null
        val found = children(folder)[name]?.takeIf { !it.directory } ?: return null
        return resolver.openInputStream(uri(found.id))
    }

    /** Files the provider kept under another name than asked: the path they have (set when they're written). */
    private val renamedByProvider = HashMap<String, String>()

    override fun storedAs(path: String): String? = renamedByProvider[path]

    override fun write(path: String): OutputStream {
        val (folder, name) = parentOf(path, make = true)!!
        val file = child(folder, name, directory = false)
        file.actualName?.let { renamedByProvider[path] = path.substringBeforeLast('/', "").let { dir -> if (dir.isEmpty()) it else "$dir/$it" } } ?: renamedByProvider.remove(path)
        // "wt": from the start, the old content gone (a cut-off copy being redone)
        val stream = resolver.openOutputStream(uri(file.id), "wt") ?: throw IOException("Couldn't write $path.")
        file.size = 0
        return object : FilterOutputStream(stream) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
                file.size += len
            }

            override fun write(b: Int) {
                out.write(b)
                file.size += 1
            }

            override fun close() {
                try {
                    flush()
                } finally {
                    out.close()
                }
            }
        }
    }

    override fun delete(path: String) {
        val (folder, name) = parentOf(path, make = false) ?: return
        val found = children(folder)[name]?.takeIf { !it.directory } ?: return
        runCatching { DocumentsContract.deleteDocument(resolver, uri(found.id)) }
        children(folder).remove(name)
    }

    /**
     * The free space of the storage the picked folder is on, or null when the provider doesn't say
     * (the export is then tried, and fails with a clear message if the storage fills up).
     */
    fun freeBytes(): Long? = runCatching {
        val probe = child(exportRoot, ".space-check", directory = false)
        try {
            resolver.openFileDescriptor(uri(probe.id), "r")?.use { fd ->
                val stat = Os.fstatvfs(fd.fileDescriptor)
                (stat.f_bavail * stat.f_frsize).takeIf { it > 0 }
            }
        } finally {
            runCatching { DocumentsContract.deleteDocument(resolver, uri(probe.id)) }
            children(exportRoot).remove(".space-check")
        }
    }.getOrNull()

    /** A name for the folder, for the screen. */
    fun displayName(): String = runCatching {
        resolver.query(uri(treeRoot), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: tree.lastPathSegment ?: tree.toString()
}
