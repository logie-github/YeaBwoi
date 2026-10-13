// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.logie.warcraft.client.ClientBuild
import com.logie.warcraft.client.ClientLayout
import com.logie.warcraft.client.DataLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The long jobs that fill the app's storage from the user's files, run by [ImportService]:
 *
 * - **The client**: the user's 3.3.5a folder (picked as a folder) or a zip of it, copied into
 *   `files/client`, checked to be build 12340.
 * - **The server's data, downloaded**: AzerothCore's own (warcraft/server-data.env), unpacked
 *   into `files/data`.
 * - **The server's data, made on the phone**: AzerothCore's tools run on the imported client.
 * - **The server's data, made on a PC**: the `server-data.zip` the Windows tools make, unpacked into
 *   `files/data`.
 *
 * Each job works in a folder of its own and only replaces the real one at the end, so a failed or
 * stopped job leaves things as they were.
 */
object Imports {
    enum class Kind { CLIENT, DOWNLOAD, EXTRACT, SERVER_DATA, MODS, MODULES }

    sealed interface State {
        data object Idle : State

        data class Working(
            val kind: Kind,
            /** Which part, for jobs in parts (downloading: 1 to 2, extracting: 1 to 3), else 0. */
            val part: Int = 0,
            val parts: Int = 0,
            val progress: Float? = null,
            val detail: String? = null,
        ) : State

        data class Done(val kind: Kind, val detail: String? = null) : State
        data class Failed(val kind: Kind, val message: String, val log: String? = null) : State
    }

    class ImportException(message: String, val log: String? = null) : IOException(message)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile var cancelled = false

    fun set(state: State) {
        _state.value = state
    }

    fun clearResult() {
        if (_state.value is State.Done || _state.value is State.Failed) _state.value = State.Idle
    }

    private fun checkCancelled() {
        if (cancelled) throw ImportException("cancelled")
    }

    // The client ------------------------------------------------------------------------------

    private data class Picked(val uri: Uri, val path: String, val size: Long)

    /** Copies a client folder picked with the system's folder picker. */
    fun importClientFolder(context: Context, tree: Uri, onProgress: (Float, String?) -> Unit) {
        val files = mutableListOf<Picked>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        listTree(context, tree, rootId, "", files)
        val root = ClientLayout.findRoot(files.map { it.path })
            ?: throw ImportException(context.getString(R.string.wow_client_error_not_client))
        val prefix = if (root.isEmpty()) "" else "$root/"
        val wanted = files.filter { it.path.startsWith(prefix) }
        val total = wanted.sumOf { it.size }
        checkSpace(context, total)

        val staging = staging(context, "client.import")
        var done = 0L
        for (file in wanted) {
            checkCancelled()
            val relative = file.path.removePrefix(prefix)
            if (!ClientLayout.isSafe(relative)) continue
            val target = File(staging, relative)
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(file.uri).use { input ->
                input ?: throw ImportException(context.getString(R.string.wow_import_error_read, file.path))
                target.outputStream().use { output ->
                    copy(input, output) { read ->
                        done += read
                        onProgress(if (total > 0) done.toFloat() / total else 0f, relative)
                    }
                }
            }
        }
        finishClient(context, staging)
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
                val mime = cursor.getString(2)
                val childPath = if (path.isEmpty()) name else "$path/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    listTree(context, tree, id, childPath, out)
                } else {
                    out += Picked(DocumentsContract.buildDocumentUriUsingTree(tree, id), childPath, cursor.getLong(3))
                }
            }
        }
    }

    /** Unpacks a zip of the client. */
    fun importClientZip(context: Context, zip: Uri, onProgress: (Float, String?) -> Unit) {
        val total = sizeOf(context, zip)
        // Unpacked, the client is a little bigger than its zip (the MPQs are compressed already).
        checkSpace(context, (total * 1.1).toLong())
        val staging = staging(context, "client.import")
        readZip(context, open(context, zip), total, onProgress) { name -> name.takeIf { ClientLayout.isSafe(it) }?.let { File(staging, it) } }
        val paths = staging.walkTopDown().filter { it.isFile }.map { it.relativeTo(staging).path }.toList()
        val root = ClientLayout.findRoot(paths) ?: run {
            staging.deleteRecursively()
            throw ImportException(context.getString(R.string.wow_client_error_not_client))
        }
        finishClient(context, if (root.isEmpty()) staging else File(staging, root), staging)
    }

    /** Checks the copied client is 3.3.5a (12340), then puts it in place of the old one. */
    private fun finishClient(context: Context, folder: File, staging: File = folder) {
        val exe = folder.listFiles()?.firstOrNull { it.name.equals("Wow.exe", ignoreCase = true) }
        val build = exe?.inputStream()?.buffered()?.use { ClientBuild.read(it) }
        if (build == null || !build.isSupported) {
            staging.deleteRecursively()
            throw ImportException(
                if (build == null) context.getString(R.string.wow_client_error_no_version)
                else context.getString(R.string.wow_client_error_build, build.toString())
            )
        }
        if (exe.name != "Wow.exe") exe.renameTo(File(folder, "Wow.exe"))
        replace(context, folder, WowFiles.client(context))
        ClientMods.clientReplaced(context)
        if (staging != folder) staging.deleteRecursively()
    }

    // The server's data, downloaded --------------------------------------------------------------

    /** Kept between tries: a download that stopped carries on from where it got to. */
    private fun downloadFolder(context: Context) = File(WowFiles.root(context), "data.download")

    /** How much of the server data an earlier try downloaded, 0 if none. */
    fun downloadedSoFar(context: Context): Long = File(downloadFolder(context), "data.zip.part").length()

    /**
     * Downloads AzerothCore's server data, the version this server is built for
     * (warcraft/server-data.env, tested with it in CI), checks it's the right file, and unpacks it
     * into the server's data. [onProgress] gets the part (1: download, 2: unpack) and how far.
     */
    fun downloadServerData(context: Context, onProgress: (part: Int, progress: Float, detail: String?) -> Unit) {
        val folder = downloadFolder(context).apply { mkdirs() }
        val part = File(folder, "data.zip.part")
        val zip = File(folder, "data.zip")
        val size = BuildConfig.WOW_DATA_SIZE
        // Unpacked, the data is 2.7 times its zip.
        val unpacked = (size * 2.7).toLong()
        if (!zip.isFile) {
            checkSpace(context, size - part.length() + unpacked)
            download(context, part, size) { done ->
                onProgress(1, done.toFloat() / size, "${shortSize(context, done)} / ${shortSize(context, size)}")
            }
            onProgress(1, 1f, context.getString(R.string.wow_download_checking))
            if (sha256(part) != BuildConfig.WOW_DATA_SHA256) {
                part.delete()
                throw ImportException(context.getString(R.string.wow_download_error_damaged))
            }
            if (!part.renameTo(zip)) throw ImportException(context.getString(R.string.wow_import_error_write))
        }
        checkSpace(context, unpacked)
        val staging = staging(context, "data.import")
        readZip(context, zip.inputStream(), zip.length(), { progress, detail -> onProgress(2, progress, detail) }) { name ->
            DataLayout.target(name)?.let { File(staging, it) }
        }
        installData(context, staging)
        staging.deleteRecursively()
        WowFiles.setDownloadedData(context, BuildConfig.WOW_DATA_VERSION)
    }

    /** Downloads into [part], carrying on from what's in it already. */
    private fun download(context: Context, part: File, size: Long, onProgress: (Long) -> Unit) {
        var done = part.length()
        if (done > size) {
            part.delete()
            done = 0
        }
        if (done == size) return
        try {
            val connection = connect(context, URL(BuildConfig.WOW_DATA_URL), done)
            try {
                // 206: the rest of the file; 200: the server sends all of it.
                if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) done = 0
                connection.inputStream.use { input ->
                    FileOutputStream(part, done > 0).use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var sinceReport = 0
                        while (true) {
                            checkCancelled()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            sinceReport += n
                            if (sinceReport >= 1 shl 20) {
                                onProgress(done)
                                sinceReport = 0
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: ImportException) {
            throw e
        } catch (e: IOException) {
            throw ImportException(context.getString(R.string.wow_download_error_network, e.message ?: e.javaClass.simpleName))
        }
        onProgress(done)
        if (done != size) {
            throw ImportException(context.getString(R.string.wow_download_error_network, "${shortSize(context, done)} / ${shortSize(context, size)}"))
        }
    }

    /** Opens [url] from byte [from], following redirects (GitHub sends release files from elsewhere). */
    private fun connect(context: Context, url: URL, from: Long): HttpURLConnection {
        var current = url
        repeat(6) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}")
                if (from > 0) setRequestProperty("Range", "bytes=$from-")
            }
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL -> return connection
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    current = URL(current, location ?: throw IOException("HTTP $code"))
                }
                else -> {
                    connection.disconnect()
                    throw IOException("HTTP $code")
                }
            }
        }
        throw IOException("too many redirects")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                checkCancelled()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun shortSize(context: Context, bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)

    // The server's data, made on the phone --------------------------------------------------------

    /**
     * Runs AzerothCore's tools on the imported client: dbc, Cameras and maps; vmaps; and, if
     * [withMmaps], mmaps (pathfinding: the long one, an hour or more on a phone).
     */
    fun extract(context: Context, withMmaps: Boolean, onPart: (part: Int, parts: Int, detail: String?) -> Unit) {
        if (!WowFiles.hasClient(context)) throw ImportException(context.getString(R.string.wow_extract_error_no_client))
        if (!ServerPackage.isInstalled(context)) ServerPackage.install(context) { }
        Server.ctl(context, "configure").redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() }
        checkSpace(context, if (withMmaps) 6_000_000_000L else 3_000_000_000L)
        val work = File(WowFiles.root(context), "data.work")
        work.deleteRecursively()
        work.mkdirs()
        val client = WowFiles.client(context).path
        val steps = buildList {
            add("maps")
            add("vmaps")
            if (withMmaps) add("mmaps")
        }
        steps.forEachIndexed { index, step ->
            checkCancelled()
            onPart(index + 1, steps.size, null)
            val log = File(WowFiles.logs(context).also { it.mkdirs() }, "extract-$step.log")
            val args = if (step == "mmaps") arrayOf(step, Runtime.getRuntime().availableProcessors().coerceAtMost(4).toString()) else arrayOf(step, client)
            val process = Server.ctl(context, *args).redirectErrorStream(true).start()
            log.bufferedWriter().use { out ->
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        out.write(line)
                        out.newLine()
                        if (line.isNotBlank()) onPart(index + 1, steps.size, line.trim().take(120))
                        if (cancelled) {
                            process.destroy()
                            break
                        }
                    }
                }
            }
            val code = process.waitFor()
            checkCancelled()
            if (code != 0) throw ImportException(context.getString(R.string.wow_extract_error_step, step, code), WowFiles.tail(log, 25))
        }
        // vmap4_extractor's intermediate files.
        File(work, "Buildings").deleteRecursively()
        installData(context, work)
        work.deleteRecursively()
    }

    // The server's data, made on a PC ---------------------------------------------------------

    /** Unpacks server-data.zip (from the Windows tools) into the server's data. */
    fun importServerData(context: Context, zip: Uri, onProgress: (Float, String?) -> Unit) {
        val total = sizeOf(context, zip)
        checkSpace(context, (total * 1.3).toLong())
        val staging = staging(context, "data.import")
        readZip(context, open(context, zip), total, onProgress) { name -> DataLayout.target(name)?.let { File(staging, it) } }
        val found = DataLayout.FOLDERS.filter { File(staging, it).list()?.isNotEmpty() == true }
        if (found.isEmpty()) {
            staging.deleteRecursively()
            throw ImportException(context.getString(R.string.wow_data_error_not_data))
        }
        installData(context, staging)
        staging.deleteRecursively()
    }

    /**
     * Puts each data folder found in [from] in place of the server's (the others stay). A download
     * that stopped before the end is of no use any more: it goes.
     */
    private fun installData(context: Context, from: File) {
        val data = WowFiles.data(context)
        data.mkdirs()
        WowFiles.setDownloadedData(context, null)
        downloadFolder(context).deleteRecursively()
        for (folder in DataLayout.FOLDERS) {
            val source = File(from, folder)
            if (source.list()?.isNotEmpty() == true) replace(context, source, File(data, folder))
        }
        if (!DataLayout.REQUIRED.all { File(data, it).list()?.isNotEmpty() == true }) {
            throw ImportException(context.getString(R.string.wow_data_error_incomplete))
        }
    }

    // Helpers -----------------------------------------------------------------------------------

    private fun staging(context: Context, name: String): File =
        File(WowFiles.root(context), name).apply {
            deleteRecursively()
            mkdirs()
        }

    /** Moves [source] to [target], replacing what was there. */
    private fun replace(context: Context, source: File, target: File) {
        val old = File(target.parentFile, "${target.name}.old")
        old.deleteRecursively()
        if (target.exists() && !target.renameTo(old)) throw ImportException(context.getString(R.string.wow_import_error_write))
        if (!source.renameTo(target)) {
            old.renameTo(target)
            throw ImportException(context.getString(R.string.wow_import_error_write))
        }
        old.deleteRecursively()
    }

    private fun open(context: Context, uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw ImportException(context.getString(R.string.wow_import_error_read, uri.lastPathSegment ?: ""))

    private fun readZip(
        context: Context,
        input: InputStream,
        total: Long,
        onProgress: (Float, String?) -> Unit,
        targetOf: (String) -> File?,
    ) {
        var read = 0L
        val counting = object : java.io.FilterInputStream(input) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
        }
        try {
            ZipInputStream(counting.buffered(1 shl 16)).use { stream ->
                while (true) {
                    checkCancelled()
                    val entry = stream.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val target = targetOf(entry.name) ?: continue
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output ->
                        copy(stream, output) { onProgress(if (total > 0) read.toFloat() / total else 0f, entry.name.substringAfterLast('/')) }
                    }
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw ImportException(context.getString(R.string.wow_import_error_zip, e.message ?: ""))
        }
    }

    private fun copy(input: InputStream, output: java.io.OutputStream, onRead: (Int) -> Unit) {
        val buffer = ByteArray(1 shl 16)
        var sinceReport = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            sinceReport += n
            if (sinceReport >= 4 shl 20) {
                checkCancelled()
                onRead(sinceReport)
                sinceReport = 0
            }
        }
        if (sinceReport > 0) onRead(sinceReport)
    }

    private fun sizeOf(context: Context, uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L

    fun checkSpace(context: Context, needed: Long) {
        val free = WowFiles.freeSpace(context)
        if (needed > 0 && free < needed + 500_000_000L) {
            throw ImportException(
                context.getString(
                    R.string.wow_import_error_space,
                    android.text.format.Formatter.formatShortFileSize(context, needed),
                    android.text.format.Formatter.formatShortFileSize(context, free),
                )
            )
        }
    }
}
