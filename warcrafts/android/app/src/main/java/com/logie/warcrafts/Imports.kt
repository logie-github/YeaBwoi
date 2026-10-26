// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.logie.warcrafts.library.GameLayout
import com.logie.warcrafts.library.Install
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Brings a game's files into the app ([ImportService] runs it in the background): a folder picked
 * with Android's file picker, or a zip of it, or (Warcraft I and II) GOG's offline installer, the
 * setup program and the .bin files beside it, unpacked on the phone by innoextract. Everything goes
 * into a staging folder first, and only once it's all there does it take the place of what was
 * before, so a failed or stopped import changes nothing. A zip or folder whose game sits in a
 * folder of its own ("Warcraft III 1.30 ... by Mashiro/") is taken from inside it.
 */
object Imports {
    /** What an import is doing: copying the files, unpacking GOG's installer, converting for Stratagus. */
    enum class Phase { COPY, UNPACK, CONVERT, DOWNLOAD, MOD_DOWNLOAD, MOD_INSTALL }

    sealed interface State {
        data object Idle : State
        data class Working(val install: Install, val progress: Float? = null, val detail: String? = null, val phase: Phase = Phase.COPY) : State
        data class Done(val install: Install) : State
        data class Failed(val install: Install, val message: String) : State
    }

    class ImportException(message: String) : IOException(message)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile
    var cancelled = false

    fun set(state: State) {
        _state.value = state
    }

    fun clearResult() {
        if (_state.value is State.Done || _state.value is State.Failed) _state.value = State.Idle
    }

    private fun checkCancelled() {
        if (cancelled) throw ImportException("Stopped")
    }

    private class Picked(val uri: Uri, val path: String, val size: Long)

    /** Where an import is: its [Phase], how far (null: unknown), what it's on. */
    fun interface Report {
        operator fun invoke(phase: Phase, progress: Float?, detail: String?)
    }

    /**
     * A folder picked with the file picker: the game's folder in it, file by file; or, for Warcraft
     * I and II, the GOG installer in it if the game itself isn't there.
     */
    fun importFolder(context: Context, install: Install, tree: Uri, report: Report) {
        val files = mutableListOf<Picked>()
        listTree(context, tree, DocumentsContract.getTreeDocumentId(tree), "", files)
        val paths = files.map { it.path }
        if (install.stratagusGame != null && !GameLayout.hasMain(paths, install.mainNames)) {
            val installer = GameLayout.findInstaller(paths, install.installerHint)
            if (installer != null) {
                val wanted = files.filter { it.path == installer.setup || it.path in installer.parts }
                unpackInstaller(context, install, wanted, installer.setup, report)
                return
            }
        }
        val root = GameLayout.findRoot(files.map { it.path }, install.mainNames)
            ?: throw ImportException(context.getString(R.string.rts_import_no_program))
        val prefix = if (root.isEmpty()) "" else "$root/"
        val wanted = files.filter { it.path.startsWith(prefix) }
        val total = wanted.sumOf { it.size }
        checkSpace(context, total)

        val staging = fresh(RtsFiles.staging(context))
        var done = 0L
        for (file in wanted) {
            checkCancelled()
            val relative = file.path.removePrefix(prefix)
            if (!GameLayout.isSafe(relative)) continue
            val target = File(staging, relative)
            target.parentFile?.mkdirs()
            val input = context.contentResolver.openInputStream(file.uri)
                ?: throw ImportException(context.getString(R.string.rts_import_error_read, file.path))
            input.use {
                target.outputStream().use { output ->
                    copy(input, output) { read ->
                        done += read
                        report(Phase.COPY, if (total > 0) done.toFloat() / total else 0f, relative)
                    }
                }
            }
        }
        finish(context, install, staging)
    }

    /** A zip of the game: unpacked into the staging folder, then the game's folder in it taken. */
    fun importZip(context: Context, install: Install, zip: Uri, report: Report) {
        val total = sizeOf(context, zip)
        // A zip unpacks to about its own size or more; the space it needs is checked as it goes too.
        checkSpace(context, total)
        val staging = fresh(RtsFiles.staging(context))
        val input = context.contentResolver.openInputStream(zip)
            ?: throw ImportException(context.getString(R.string.rts_import_error_read, zip.lastPathSegment ?: ""))
        var read = 0L
        val counting = object : FilterInputStream(input) {
            override fun read(): Int = super.read().also { if (it >= 0) read++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
        }
        val paths = mutableListOf<String>()
        ZipInputStream(counting.buffered(1 shl 16)).use { stream ->
            while (true) {
                checkCancelled()
                val entry = stream.nextEntry ?: break
                val name = entry.name.replace('\\', '/').trimEnd('/')
                if (entry.isDirectory || !GameLayout.isSafe(name)) continue
                val target = File(staging, name)
                target.parentFile?.mkdirs()
                target.outputStream().use { output ->
                    copy(stream, output) {
                        report(Phase.COPY, if (total > 0) read.toFloat() / total else 0f, name)
                    }
                }
                paths += name
            }
        }
        val root = GameLayout.findRoot(paths, install.mainNames)
            ?: throw ImportException(context.getString(R.string.rts_import_no_program))
        finish(context, install, if (root.isEmpty()) staging else File(staging, root))
        staging.deleteRecursively()
    }

    /**
     * GOG's offline installer, its files picked together (the setup program and the .bin files
     * beside it): copied into the app, unpacked by innoextract, the game's folder in it taken.
     */
    fun importInstaller(context: Context, install: Install, uris: List<Uri>, report: Report) {
        val picked = uris.map { uri -> Picked(uri, displayName(context, uri) ?: uri.lastPathSegment.orEmpty(), sizeOf(context, uri)) }
        val installer = GameLayout.findInstaller(picked.map { it.path }, install.installerHint)
            ?: throw ImportException(context.getString(R.string.rts_installer_none))
        unpackInstaller(context, install, picked.filter { it.path == installer.setup || it.path in installer.parts }, installer.setup, report)
    }

    private fun unpackInstaller(context: Context, install: Install, files: List<Picked>, setupPath: String, report: Report) {
        val total = files.sumOf { it.size }
        // The installer's copy, then the game it unpacks to: its files are compressed in it.
        checkSpace(context, total * 5 / 2)
        val staging = fresh(RtsFiles.staging(context))
        val copies = File(staging, "installer").apply { mkdirs() }
        var done = 0L
        for (file in files) {
            checkCancelled()
            val name = file.path.substringAfterLast('/')
            if (!GameLayout.isSafe(name)) continue
            val input = context.contentResolver.openInputStream(file.uri)
                ?: throw ImportException(context.getString(R.string.rts_import_error_read, name))
            input.use {
                File(copies, name).outputStream().use { output ->
                    copy(input, output) { read ->
                        done += read
                        report(Phase.COPY, if (total > 0) done.toFloat() / total else 0f, name)
                    }
                }
            }
        }

        report(Phase.UNPACK, null, null)
        val setup = File(copies, setupPath.substringAfterLast('/'))
        val innoextract = Tools.file(context, "libinnoextract.so").path
        // English, where the installer has several languages' files of the same name.
        val languages = mutableListOf<String>()
        Tools.run(listOf(innoextract, "--list-languages", "--silent", "--color=0", setup.path), staging, null) { languages += it }
        val language = GameLayout.englishLanguage(languages)
        val unpacked = File(staging, "unpacked").apply { mkdirs() }
        val command = listOf(innoextract, "--extract", "--exclude-temp", "--progress=0", "--color=0", "--output-dir", unpacked.path) +
            (language?.let { listOf("--default-language", it) } ?: emptyList()) + setup.path
        val result = Tools.run(command, staging, null) { line ->
            report(Phase.UNPACK, null, line.trim().removePrefix("- ").replace("\"", "").ifEmpty { null })
        }
        if (result.code != 0) {
            val tail = result.tail.joinToString("\n")
            throw ImportException(
                when {
                    tail.contains("could not open slice", ignoreCase = true) -> context.getString(R.string.rts_installer_missing_parts)
                    tail.contains("Not a supported Inno Setup installer", ignoreCase = true) -> context.getString(R.string.rts_installer_not_inno)
                    else -> context.getString(R.string.rts_installer_failed, result.code, tail)
                }
            )
        }
        copies.deleteRecursively()

        val paths = unpacked.walkTopDown().filter { it.isFile }.map { it.relativeTo(unpacked).invariantSeparatorsPath }.toList()
        val root = GameLayout.findRoot(paths, install.mainNames)?.takeIf { GameLayout.hasMain(paths, install.mainNames) }
            ?: throw ImportException(context.getString(R.string.rts_installer_wrong_game))
        finish(context, install, if (root.isEmpty()) unpacked else File(unpacked, root))
    }

    /** The game's folder [from] takes the place of the one before. */
    private fun finish(context: Context, install: Install, from: File) {
        val target = RtsFiles.folder(context, install)
        val old = File(target.parentFile, "${install.key}.old")
        old.deleteRecursively()
        target.parentFile?.mkdirs()
        if (target.exists() && !target.renameTo(old)) throw ImportException("Can't replace ${target.name}")
        if (!from.renameTo(target)) {
            old.renameTo(target)
            throw ImportException("Can't move the game into place")
        }
        old.deleteRecursively()
        RtsFiles.setProgram(context, install, null)
    }

    private fun fresh(folder: File): File {
        folder.deleteRecursively()
        folder.mkdirs()
        return folder
    }

    private fun listTree(context: Context, tree: Uri, documentId: String, path: String, out: MutableList<Picked>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                checkCancelled()
                val id = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val childPath = if (path.isEmpty()) name else "$path/$name"
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    listTree(context, tree, id, childPath, out)
                } else {
                    out += Picked(DocumentsContract.buildDocumentUriUsingTree(tree, id), childPath, cursor.getLong(3))
                }
            }
        }
    }

    private fun copy(input: InputStream, output: java.io.OutputStream, onRead: (Int) -> Unit) {
        val buffer = ByteArray(1 shl 16)
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            onRead(count)
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private fun sizeOf(context: Context, uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L

    /** Room for [needed] bytes more, with some to spare. */
    fun checkSpace(context: Context, needed: Long) {
        val free = StatFs(context.filesDir.path).availableBytes
        if (needed + 200L * 1024 * 1024 > free) {
            throw ImportException(
                context.getString(
                    R.string.rts_import_no_space,
                    android.text.format.Formatter.formatShortFileSize(context, needed),
                    android.text.format.Formatter.formatShortFileSize(context, free),
                )
            )
        }
    }
}
