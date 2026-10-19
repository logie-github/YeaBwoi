// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.felsong

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import com.logie.warcraft.R
import com.logie.warcraft.WowFiles
import com.logie.warcraft.felsong.core.FelsongAccountRules
import com.logie.warcraft.felsong.core.FelsongClient
import com.logie.warcraft.felsong.core.FelsongDataImport
import com.logie.warcraft.felsong.core.FelsongLayout
import com.logie.warcraft.turtle.core.SafeFs
import com.logie.warcraft.turtle.core.VerifiedDownload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Felsong's installation, repair and removal, run one at a time by [FelsongService] (which keeps
 * them going with the app in the background). Every file they touch is in `files/felsong`, through
 * [FelsongFiles.fs]; a step that fails leaves what was there before it (staging folders, a download
 * kept to carry on from), so Install can be pressed again, and Repair or Uninstall clean it up.
 */
object FelsongJobs {
    enum class Kind { INSTALL, PROGRAMS, DATABASE, RUNTIME, CLIENT, VERIFY, DATA, IMPORT_DATA, PATCH, REPAIR, BACKUP, RESTORE, UNINSTALL }

    enum class Step { CHECKING, DOWNLOADING, UNPACKING, DATABASE, RUNTIME, COPYING_CLIENT, VERIFYING_CLIENT, MAPS, VMAPS, MMAPS, VERIFYING_DATA, PATCHING, ACCOUNT, BACKUP, RESTORE, REMOVING }

    sealed interface State {
        data object Idle : State

        data class Working(val kind: Kind, val step: Step, val progress: Float? = null, val detail: String? = null) : State

        data class Done(val kind: Kind, val message: String) : State

        data class Failed(val kind: Kind, val message: String, val log: String? = null) : State
    }

    /** What Repair does (the user picks). */
    enum class Repair { PROGRAMS, CONFIGS, CLIENT, DATA, DATABASES }

    data class Options(
        val withMmaps: Boolean = false,
        val accountName: String? = null,
        val accountPassword: String? = null,
        val repairs: Set<Repair> = emptySet(),
        val restoreFile: String? = null,
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    @Volatile var cancelled = false
        private set

    val busy: Boolean get() = _state.value is State.Working

    fun cancel() {
        cancelled = true
    }

    fun clearResult() {
        if (_state.value !is State.Working) _state.value = State.Idle
    }

    internal fun begin(kind: Kind) {
        cancelled = false
        _state.value = State.Working(kind, Step.CHECKING)
    }

    private fun working(kind: Kind, step: Step, progress: Float? = null, detail: String? = null) {
        _state.value = State.Working(kind, step, progress, detail)
    }

    class JobException(message: String, val log: String? = null) : IOException(message)

    private fun checkCancelled() {
        if (cancelled) throw VerifiedDownload.Cancelled()
    }

    /** Runs [kind] to its end on this (background) thread; its outcome ends in [state]. */
    internal fun run(context: Context, kind: Kind, uri: Uri?, isFolder: Boolean, options: Options) {
        try {
            val message = when (kind) {
                Kind.INSTALL -> install(context, options)
                Kind.PROGRAMS -> programs(context, force = true)
                Kind.DATABASE -> database(context)
                Kind.RUNTIME -> runtime(context)
                Kind.CLIENT -> importClient(context, uri ?: throw JobException("No client picked"), isFolder, options)
                Kind.VERIFY -> verifyClient(context)
                Kind.DATA -> makeData(context, Kind.DATA, options.withMmaps)
                Kind.IMPORT_DATA -> importData(context, uri ?: throw JobException("No zip picked"))
                Kind.PATCH -> patchClient(context, Kind.PATCH)
                Kind.REPAIR -> repair(context, options)
                Kind.BACKUP -> backup(context)
                Kind.RESTORE -> restore(context, options.restoreFile ?: throw JobException("No backup chosen"))
                Kind.UNINSTALL -> uninstall(context)
            }
            if (kind != Kind.UNINSTALL) FelsongFiles.updateState(context) { it.copy(interruptedStep = null) }
            _state.value = State.Done(kind, message)
        } catch (e: VerifiedDownload.Cancelled) {
            remember(context, kind)
            _state.value = State.Failed(kind, context.getString(R.string.felsong_job_cancelled))
        } catch (e: JobException) {
            remember(context, kind)
            _state.value = State.Failed(kind, e.message ?: "", e.log)
        } catch (e: SafeFs.Refused) {
            _state.value = State.Failed(kind, context.getString(R.string.felsong_job_refused, e.message ?: ""))
        } catch (e: FelsongPackage.NotPinned) {
            _state.value = State.Failed(kind, context.getString(R.string.felsong_error_not_pinned))
        } catch (e: VerifiedDownload.Corrupt) {
            remember(context, kind)
            _state.value = State.Failed(kind, context.getString(R.string.felsong_error_corrupt))
        } catch (e: Exception) {
            remember(context, kind)
            _state.value = State.Failed(kind, context.getString(R.string.felsong_job_error, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun remember(context: Context, kind: Kind) {
        if (kind == Kind.UNINSTALL) return
        val step = (_state.value as? State.Working)?.step?.name ?: return
        runCatching { FelsongFiles.updateState(context) { it.copy(interruptedStep = step) } }
    }

    // Install ------------------------------------------------------------------------------------

    /** Everything the app can do by itself, from where the phone is: the one-click install. */
    private fun install(context: Context, options: Options): String {
        val kind = Kind.INSTALL
        working(kind, Step.CHECKING)
        preflight(context)
        val pin = FelsongPackage.pinned ?: throw FelsongPackage.NotPinned()
        cleanLeftovers(context)

        if (!FelsongPackage.isCurrent(context)) programsStep(context, kind, pin)
        if (!FelsongFiles.hasDatabases(context)) databaseStep(context, kind, pin)

        val state = FelsongFiles.state(context)
        if (FelsongFiles.hasClient(context)) {
            if (!FelsongFiles.hasData(context) || state.dataFrom != state.clientExeSha256) makeData(context, kind, options.withMmaps)
            if (!FelsongFiles.isPatched(context)) patchClient(context, kind)
        }

        val name = options.accountName
        val password = options.accountPassword
        if (name != null && password != null) {
            working(kind, Step.ACCOUNT)
            runBlocking {
                FelsongServer.withDatabase(context) {
                    if (FelsongAccounts.list(context).none { it.name.equals(name, ignoreCase = true) }) {
                        FelsongAccounts.create(context, name, password, FelsongAccountRules.LEVEL_ADMINISTRATOR)
                    }
                }
            }
            FelsongLogin.save(context, name, password)
        }
        return context.getString(
            if (FelsongFiles.hasClient(context) && FelsongFiles.hasData(context) && FelsongFiles.isPatched(context)) R.string.felsong_done_install_ready
            else R.string.felsong_done_install_client
        )
    }

    private fun programs(context: Context, force: Boolean): String {
        working(Kind.PROGRAMS, Step.CHECKING)
        preflight(context)
        val pin = FelsongPackage.pinned ?: throw FelsongPackage.NotPinned()
        cleanLeftovers(context)
        if (force || !FelsongPackage.isCurrent(context)) programsStep(context, Kind.PROGRAMS, pin)
        return context.getString(R.string.felsong_done_programs, pin.legioncore.take(7))
    }

    private fun programsStep(context: Context, kind: Kind, pin: FelsongPackage.Pin) {
        if (FelsongServer.isBusy) throw JobException(context.getString(R.string.felsong_error_stop_first))
        val needed = (pin.size - FelsongPackage.downloadedSoFar(context)) + pin.unpacked + SPACE_MARGIN
        checkSpace(context, needed)
        if (FelsongFiles.hasDatabases(context) && FelsongPackage.isInstalled(context)) backupQuietly(context, "before-update")
        working(kind, Step.DOWNLOADING, 0f)
        FelsongPackage.download(context, { cancelled }) { done, total ->
            working(kind, Step.DOWNLOADING, done.toFloat() / total, "${size(context, done)} / ${size(context, total)}")
        }
        working(kind, Step.UNPACKING, 0f)
        FelsongPackage.install(context, { cancelled }) { working(kind, Step.UNPACKING, it) }
        FelsongFiles.updateState(context) { it.copy(serverSha256 = pin.sha256, serverTag = pin.tag) }
    }

    private fun database(context: Context): String {
        working(Kind.DATABASE, Step.CHECKING)
        preflight(context)
        val pin = FelsongPackage.pinned ?: throw FelsongPackage.NotPinned()
        if (!FelsongPackage.isInstalled(context)) throw JobException(context.getString(R.string.felsong_error_not_installed))
        if (!FelsongFiles.hasDatabases(context)) databaseStep(context, Kind.DATABASE, pin)
        return context.getString(R.string.felsong_done_database)
    }

    /** The game's Windows runtime (Wine, Box64, the graphics drivers), from the APK: shared, never changed here. */
    private fun runtime(context: Context): String {
        working(Kind.RUNTIME, Step.RUNTIME, 0f)
        if (!FelsongGame.runtimeThere(context)) {
            val ok = com.winlator.xenvironment.RootFSInstaller.installIfNeeded(context) { percent ->
                working(Kind.RUNTIME, Step.RUNTIME, percent / 100f)
            }
            if (!ok) throw JobException(context.getString(R.string.felsong_game_error_runtime))
        }
        return context.getString(R.string.felsong_done_runtime)
    }

    private fun databaseStep(context: Context, kind: Kind, pin: FelsongPackage.Pin) {
        working(kind, Step.DATABASE)
        // LegionCore's world and hotfixes: about 1.5 GB on disk, copied from the package's seed.
        checkSpace(context, DATABASE_SPACE + SPACE_MARGIN)
        runCtl(context, kind, Step.DATABASE, listOf("db-init"), "db-init.log", timeoutMinutes = 30)
        FelsongFiles.updateState(context) { it.copy(databaseFrom = pin.sha256, databaseUpdated = false) }
    }

    private fun preflight(context: Context) {
        when (FelsongFiles.blocker(context)) {
            "arm64" -> throw JobException(context.getString(R.string.felsong_error_not_arm64))
            "path" -> throw JobException(context.getString(R.string.felsong_error_path, WowFiles.root(context).path))
        }
    }

    /** Staging left by a step that stopped (never the finished folders). */
    private fun cleanLeftovers(context: Context) {
        val layout = FelsongFiles.layout(context)
        val fs = FelsongFiles.fs(context)
        for (folder in listOf(layout.serverStaging, layout.serverOld, layout.clientStaging, layout.dataImport, File(layout.client.path + ".old"), File(layout.server.path + ".old"))) {
            fs.delete(folder)
        }
    }

    // The client ---------------------------------------------------------------------------------

    private data class Picked(val uri: Uri, val path: String, val size: Long)

    /**
     * Copies the user's Legion client (a folder or a zip) into `client.import`, hashing Wow-64.exe
     * as it's copied and reading the build the client says it is, then puts it in place of
     * `client/`. Then the server's data is made from it and the program is patched for the realm.
     */
    private fun importClient(context: Context, uri: Uri, isFolder: Boolean, options: Options): String {
        val kind = Kind.CLIENT
        working(kind, Step.CHECKING)
        preflight(context)
        val layout = FelsongFiles.layout(context)
        val fs = FelsongFiles.fs(context)
        cleanLeftovers(context)
        fs.mkdirs(layout.clientStaging)
        val hashes = mutableMapOf<String, String>()
        try {
            if (isFolder) copyFolder(context, uri, layout, fs, hashes) else copyZip(context, uri, layout, fs, hashes)
            checkCancelled()
            working(kind, Step.VERIFYING_CLIENT)
            val exe = FelsongClient.find(layout.clientStaging, FelsongClient.EXE)
                ?: throw JobException(context.getString(R.string.felsong_error_not_client))
            val version = FelsongClient.version(layout.clientStaging)
                ?: throw JobException(context.getString(R.string.felsong_error_client_version))
            if (!version.isExpected) throw JobException(context.getString(R.string.felsong_error_client_build, version.text, FelsongClient.BUILD))
            val exeHash = hashes[exe.relativeTo(layout.clientStaging).path.replace('\\', '/').lowercase()]
                ?: exe.inputStream().use { sha256(it) }
            // A patched program copied along with the client was made for someone else's realm.
            FelsongClient.find(layout.clientStaging, FelsongClient.PATCHED_EXE)?.let { fs.delete(it) }
            fs.replace(layout.clientStaging, layout.client)
            FelsongFiles.updateState(context) { it.copy(clientExeSha256 = exeHash, clientBuild = version.build, patchedFrom = null) }
        } finally {
            fs.delete(layout.clientStaging)
        }
        if (FelsongPackage.isInstalled(context)) {
            makeData(context, kind, options.withMmaps)
            patchClient(context, kind)
        }
        return context.getString(R.string.felsong_done_client, FelsongFiles.state(context).clientBuild)
    }

    private fun copyFolder(context: Context, tree: Uri, layout: FelsongLayout, fs: SafeFs, hashes: MutableMap<String, String>) {
        val files = mutableListOf<Picked>()
        listTree(context, tree, DocumentsContract.getTreeDocumentId(tree), "", files)
        val root = FelsongClient.findRoot(files.map { it.path }) ?: throw JobException(context.getString(R.string.felsong_error_not_client))
        FelsongClient.missing(files.map { it.path }, root).takeIf { it.isNotEmpty() }?.let {
            throw JobException(context.getString(R.string.felsong_error_client_incomplete, it.joinToString()))
        }
        val prefix = if (root.isEmpty()) "" else "$root/"
        val wanted = files.filter { it.path.startsWith(prefix) }
        val total = wanted.sumOf { it.size }
        checkSpace(context, total + SPACE_MARGIN)
        var done = 0L
        for (file in wanted) {
            checkCancelled()
            val relative = file.path.removePrefix(prefix)
            if (!FelsongClient.isSafePath(relative)) continue
            val target = fs.check(File(layout.clientStaging, relative))
            target.parentFile?.let { fs.mkdirs(it) }
            val input = context.contentResolver.openInputStream(file.uri) ?: throw JobException(context.getString(R.string.felsong_error_read, file.path))
            val hash = input.use { copyHashing(it, target, hash = relative.equals(FelsongClient.EXE, ignoreCase = true)) { read -> done += read; progressCopy(context, done, total, relative) } }
            if (hash != null) hashes[relative.lowercase()] = hash
        }
    }

    private fun copyZip(context: Context, zip: Uri, layout: FelsongLayout, fs: SafeFs, hashes: MutableMap<String, String>) {
        val total = sizeOf(context, zip)
        checkSpace(context, (if (total > 0) total else CLIENT_SPACE) + SPACE_MARGIN)
        val input = context.contentResolver.openInputStream(zip) ?: throw JobException(context.getString(R.string.felsong_error_read, zip.lastPathSegment ?: ""))
        var read = 0L
        val counting = object : java.io.FilterInputStream(input) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
        }
        val written = mutableMapOf<String, String?>()
        try {
            ZipInputStream(counting.buffered(1 shl 16)).use { stream ->
                while (true) {
                    checkCancelled()
                    val entry = stream.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.replace('\\', '/')
                    if (!FelsongClient.isSafePath(name)) continue
                    val target = fs.check(File(layout.clientStaging, name))
                    target.parentFile?.let { fs.mkdirs(it) }
                    val isExe = name.substringAfterLast('/').equals(FelsongClient.EXE, ignoreCase = true)
                    written[name.lowercase()] = copyHashing(stream, target, close = false, hash = isExe) { progressCopy(context, read, total, name.substringAfterLast('/')) }
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw JobException(context.getString(R.string.felsong_error_zip, e.message ?: ""))
        }
        val paths = written.keys.toList()
        val root = FelsongClient.findRoot(paths) ?: throw JobException(context.getString(R.string.felsong_error_not_client))
        FelsongClient.missing(paths, root.lowercase()).takeIf { it.isNotEmpty() }?.let {
            throw JobException(context.getString(R.string.felsong_error_client_incomplete, it.joinToString()))
        }
        if (root.isNotEmpty()) {
            val inner = FelsongClient.find(layout.clientStaging, root) ?: throw JobException(context.getString(R.string.felsong_error_not_client))
            val lifted = File(layout.root, "client.lift")
            fs.delete(lifted)
            fs.move(inner, lifted)
            fs.delete(layout.clientStaging)
            fs.move(lifted, layout.clientStaging)
        }
        val prefix = if (root.isEmpty()) "" else "${root.lowercase()}/"
        for ((path, hash) in written) if (hash != null && path.startsWith(prefix)) hashes[path.removePrefix(prefix)] = hash
    }

    private fun progressCopy(context: Context, done: Long, total: Long, name: String) {
        working(Kind.CLIENT, Step.COPYING_CLIENT, if (total > 0) done.toFloat() / total else null, "${size(context, done)} · $name")
    }

    /** Copies [input] into [target]; its SHA-256 if [hash]; [onRead] gets each block's size. */
    private fun copyHashing(input: InputStream, target: File, close: Boolean = true, hash: Boolean, onRead: (Int) -> Unit): String? {
        val digest = if (hash) MessageDigest.getInstance("SHA-256") else null
        target.outputStream().use { output ->
            val buffer = ByteArray(1 shl 16)
            var since = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                digest?.update(buffer, 0, n)
                since += n
                if (since >= 4 shl 20) {
                    checkCancelled()
                    onRead(since)
                    since = 0
                }
            }
            if (since > 0) onRead(since)
        }
        if (close) input.close()
        return digest?.digest()?.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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

    /** The client in place, checked again: its program's hash and the build it says it is (Repair). */
    private fun verifyClient(context: Context): String {
        val kind = Kind.VERIFY
        val layout = FelsongFiles.layout(context)
        if (!FelsongFiles.hasClient(context)) throw JobException(context.getString(R.string.felsong_error_no_client))
        working(kind, Step.VERIFYING_CLIENT)
        val exe = FelsongClient.find(layout.client, FelsongClient.EXE) ?: throw JobException(context.getString(R.string.felsong_error_not_client))
        val exeHash = exe.inputStream().use { sha256(it) }
        val version = FelsongClient.version(layout.client)
        FelsongFiles.updateState(context) { state ->
            state.copy(
                clientExeSha256 = exeHash,
                clientBuild = version?.build ?: 0,
                patchedFrom = state.patchedFrom?.takeIf { it == exeHash },
            )
        }
        return when {
            version == null -> context.getString(R.string.felsong_done_verify_unknown)
            version.isExpected -> context.getString(R.string.felsong_done_verify_exact, version.text)
            else -> context.getString(R.string.felsong_done_verify_different, version.text, FelsongClient.BUILD)
        }
    }

    // The server's data --------------------------------------------------------------------------

    /**
     * dbc (the DB2 tables), maps, cameras and gt (minutes), vmaps (more) and, if asked, mmaps (hours)
     * from the client in `client/`, into `data.work`, then in place of `data/` in one rename.
     */
    private fun makeData(context: Context, kind: Kind, withMmaps: Boolean): String {
        if (!FelsongPackage.isInstalled(context)) throw JobException(context.getString(R.string.felsong_error_not_installed))
        if (!FelsongFiles.hasClient(context)) throw JobException(context.getString(R.string.felsong_error_no_client))
        if (FelsongServer.isBusy) throw JobException(context.getString(R.string.felsong_error_stop_first))
        checkSpace(context, DATA_SPACE)
        val client = FelsongFiles.serverRoot(context) + "/client"
        val parts = if (withMmaps) 3 else 2
        working(kind, Step.MAPS, 0f)
        val extracting = Regex("""Extract \S+ \((\d+)/(\d+)\)""")
        runCtl(context, kind, Step.MAPS, listOf("maps", client), "maps.log", timeoutMinutes = 240) { line ->
            extracting.find(line)?.let { match ->
                val (at, of) = match.destructured
                working(kind, Step.MAPS, at.toFloat() / of.toFloat().coerceAtLeast(1f) / parts, line.trim())
            } ?: working(kind, Step.MAPS, null, line.trim())
        }
        working(kind, Step.VMAPS, 1f / parts)
        runCtl(context, kind, Step.VMAPS, listOf("vmaps", client), "vmaps.log", timeoutMinutes = 360) { line ->
            working(kind, Step.VMAPS, 1f / parts, line.trim())
        }
        if (withMmaps) {
            working(kind, Step.MMAPS, 2f / parts)
            runCtl(context, kind, Step.MMAPS, listOf("mmaps"), "mmaps.log", timeoutMinutes = 48 * 60) { line ->
                working(kind, Step.MMAPS, 2f / parts, line.trim())
            }
        }
        working(kind, Step.VERIFYING_DATA, 1f)
        val layout = FelsongFiles.layout(context)
        if (File(layout.dataWork, "maps").list()?.isEmpty() != false) {
            throw JobException(context.getString(R.string.felsong_error_no_maps), WowFiles.tail(File(layout.logs, "maps.log"), 30))
        }
        if (File(layout.dataWork, "dbc").list()?.isEmpty() != false) {
            throw JobException(context.getString(R.string.felsong_error_no_dbc), WowFiles.tail(File(layout.logs, "maps.log"), 30))
        }
        // Pathfinding made before (hours on the phone, or imported) stays when it isn't made again.
        val mmaps = File(layout.data, "mmaps")
        if (!withMmaps && mmaps.list()?.isNotEmpty() == true) FelsongFiles.fs(context).move(mmaps, File(layout.dataWork, "mmaps"))
        runCtl(context, kind, Step.VERIFYING_DATA, listOf("data-done"), "data-done.log")
        val exeHash = FelsongFiles.state(context).clientExeSha256
        FelsongFiles.updateState(context) { it.copy(dataFrom = exeHash, dataParts = FelsongFiles.dataParts(context)) }
        return context.getString(R.string.felsong_done_data, FelsongFiles.dataParts(context).sorted().joinToString())
    }

    /**
     * The server's data made on a computer with LegionCore's own extractors: a zip of any of its
     * folders, unpacked into `data.import` with every map, vmap and mmap file checked by its header
     * ([FelsongDataImport]), then each folder it has put in place of the server's (the others stay).
     */
    private fun importData(context: Context, zip: Uri): String {
        val kind = Kind.IMPORT_DATA
        working(kind, Step.CHECKING)
        preflight(context)
        if (FelsongServer.isBusy) throw JobException(context.getString(R.string.felsong_error_stop_first))
        val layout = FelsongFiles.layout(context)
        val fs = FelsongFiles.fs(context)
        cleanLeftovers(context)
        val total = sizeOf(context, zip)
        checkSpace(context, (if (total > 0) total * 2 else DATA_SPACE) + SPACE_MARGIN)
        fs.mkdirs(layout.dataImport)
        try {
            val counts = unzipData(context, zip, total, layout.dataImport, fs)
            val found = FelsongDataImport.FOLDERS.filter { (counts[it] ?: 0) > 0 }
            if (found.isEmpty()) throw JobException(context.getString(R.string.felsong_error_import_not_data))
            if (!FelsongDataImport.complete(found.toSet(), FelsongFiles.dataParts(context))) {
                throw JobException(context.getString(R.string.felsong_error_import_incomplete, found.joinToString()))
            }
            working(kind, Step.VERIFYING_DATA)
            fs.mkdirs(layout.data)
            for (folder in found) fs.replace(File(layout.dataImport, folder), File(layout.data, folder))
            FelsongFiles.updateState(context) { state ->
                state.copy(
                    dataFrom = if ("dbc" in found || "maps" in found) state.clientExeSha256 else state.dataFrom,
                    dataParts = FelsongFiles.dataParts(context),
                )
            }
            val summary = found.joinToString { context.getString(R.string.felsong_import_part, it, counts[it] ?: 0) }
            return context.getString(R.string.felsong_done_import, summary)
        } finally {
            fs.delete(layout.dataImport)
        }
    }

    private fun unzipData(context: Context, zip: Uri, total: Long, into: File, fs: SafeFs): Map<String, Int> {
        val input = context.contentResolver.openInputStream(zip) ?: throw JobException(context.getString(R.string.felsong_error_read, zip.lastPathSegment ?: ""))
        var read = 0L
        val counting = object : java.io.FilterInputStream(input) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
        }
        val counts = mutableMapOf<String, Int>()
        val foreign = mutableListOf<String>()
        try {
            ZipInputStream(counting.buffered(1 shl 16)).use { stream ->
                while (true) {
                    checkCancelled()
                    val entry = stream.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val path = FelsongDataImport.target(entry.name) ?: continue
                    val head = readHead(stream, FelsongDataImport.HEAD)
                    when (FelsongDataImport.problem(path, head)) {
                        null -> {}
                        FelsongDataImport.Problem.NOT_DATA -> continue
                        FelsongDataImport.Problem.FOREIGN -> {
                            foreign += path
                            continue
                        }
                    }
                    val target = fs.check(File(into, path))
                    target.parentFile?.let { fs.mkdirs(it) }
                    target.outputStream().use { output ->
                        output.write(head)
                        copyAll(stream, output) {
                            working(Kind.IMPORT_DATA, Step.UNPACKING, if (total > 0) read.toFloat() / total else null, "${size(context, read)} · $path")
                        }
                    }
                    val folder = path.substringBefore('/')
                    counts[folder] = (counts[folder] ?: 0) + 1
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw JobException(context.getString(R.string.felsong_error_zip, e.message ?: ""))
        }
        if (foreign.isNotEmpty()) {
            throw JobException(context.getString(R.string.felsong_error_import_foreign, foreign.size, foreign.take(3).joinToString()))
        }
        return counts
    }

    private fun readHead(input: InputStream, count: Int): ByteArray {
        val head = ByteArray(count)
        var got = 0
        while (got < count) {
            val n = input.read(head, got, count - got)
            if (n < 0) break
            got += n
        }
        return head.copyOf(got)
    }

    private fun copyAll(input: InputStream, output: java.io.OutputStream, onProgress: () -> Unit) {
        val buffer = ByteArray(1 shl 16)
        var since = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            since += n
            if (since >= 4 shl 20) {
                checkCancelled()
                onProgress()
                since = 0
            }
        }
        onProgress()
    }

    // The client patched for the realm -----------------------------------------------------------

    /**
     * LegionCore's connection patcher on the client's Wow-64.exe (step 5 of the user's list): writes
     * Wow-64_Patched.exe beside it (the portal takes a plain host, the Battle.net key and
     * certificate fingerprint are the realm's, no version check) and the certificate bundle the
     * patched program trusts, under Felsong's folder (ctl.sh runs it with HOME there). The game's
     * start copies the bundle into Felsong's Windows.
     */
    private fun patchClient(context: Context, kind: Kind): String {
        if (!FelsongPackage.isInstalled(context)) throw JobException(context.getString(R.string.felsong_error_not_installed))
        if (!FelsongFiles.hasClient(context)) throw JobException(context.getString(R.string.felsong_error_no_client))
        val layout = FelsongFiles.layout(context)
        val fs = FelsongFiles.fs(context)
        val exe = FelsongClient.find(layout.client, FelsongClient.EXE) ?: throw JobException(context.getString(R.string.felsong_error_not_client))
        working(kind, Step.PATCHING)
        checkSpace(context, exe.length() * 2 + SPACE_MARGIN)
        val patched = File(exe.parentFile, FelsongClient.PATCHED_EXE)
        fs.delete(patched)
        fs.delete(layout.certBundle)
        val client = FelsongFiles.serverRoot(context) + "/client/" + exe.relativeTo(layout.client).path
        runCtl(context, kind, Step.PATCHING, listOf("patch-client", client), "patch-client.log", timeoutMinutes = 30)
        if (!patched.isFile || patched.length() != exe.length()) {
            throw JobException(context.getString(R.string.felsong_error_patch_no_output), WowFiles.tail(File(layout.logs, "patch-client.log"), 30))
        }
        if (!layout.certBundle.isFile) {
            throw JobException(context.getString(R.string.felsong_error_patch_no_bundle), WowFiles.tail(File(layout.logs, "patch-client.log"), 30))
        }
        val exeHash = FelsongFiles.state(context).clientExeSha256 ?: exe.inputStream().use { sha256(it) }
        FelsongFiles.updateState(context) { it.copy(clientExeSha256 = exeHash, patchedFrom = exeHash) }
        return context.getString(R.string.felsong_done_patch)
    }

    // Repair, backups, uninstall -----------------------------------------------------------------

    private fun repair(context: Context, options: Options): String {
        val kind = Kind.REPAIR
        working(kind, Step.CHECKING)
        preflight(context)
        if (FelsongServer.isBusy) throw JobException(context.getString(R.string.felsong_error_stop_first))
        val layout = FelsongFiles.layout(context)
        val fs = FelsongFiles.fs(context)
        cleanLeftovers(context)
        fs.delete(layout.dataWork)
        val done = mutableListOf<String>()
        if (Repair.DATABASES in options.repairs && FelsongFiles.hasDatabases(context)) {
            working(kind, Step.BACKUP)
            backupQuietly(context, "before-reset")
            working(kind, Step.DATABASE)
            fs.delete(File(layout.databases.path + ".new"))
            fs.delete(layout.databases)
            FelsongFiles.updateState(context) { it.copy(databaseFrom = null, databaseUpdated = false) }
            done += context.getString(R.string.felsong_repair_databases)
        }
        if (Repair.PROGRAMS in options.repairs) {
            FelsongPackage.forgetDownload(context, fs)
            fs.delete(File(layout.server, ".installed"))
            done += context.getString(R.string.felsong_repair_programs)
        }
        if (Repair.CONFIGS in options.repairs) {
            fs.delete(File(layout.etc, "worldserver.conf"))
            fs.delete(File(layout.etc, "bnetserver.conf"))
            done += context.getString(R.string.felsong_repair_configs)
        }
        if (Repair.PROGRAMS in options.repairs || Repair.DATABASES in options.repairs) {
            install(context, Options(withMmaps = options.withMmaps))
            _state.value = State.Working(kind, Step.CHECKING)
        }
        if (Repair.CLIENT in options.repairs && FelsongFiles.hasClient(context)) {
            verifyClient(context)
            patchClient(context, kind)
            done += context.getString(R.string.felsong_repair_client)
        }
        if (Repair.DATA in options.repairs && FelsongFiles.hasClient(context)) {
            makeData(context, kind, options.withMmaps)
            done += context.getString(R.string.felsong_repair_data)
        }
        return context.getString(R.string.felsong_done_repair, done.joinToString().ifEmpty { context.getString(R.string.felsong_repair_cleaned) })
    }

    private fun backup(context: Context): String {
        working(Kind.BACKUP, Step.BACKUP)
        val file = backupNow(context, "manual")
        return context.getString(R.string.felsong_done_backup, file.name)
    }

    private fun backupQuietly(context: Context, label: String) {
        runCatching { backupNow(context, label) }
    }

    private fun backupNow(context: Context, label: String): File {
        if (!FelsongPackage.isInstalled(context) || !FelsongFiles.hasDatabases(context)) throw JobException(context.getString(R.string.felsong_error_no_databases))
        val layout = FelsongFiles.layout(context)
        val fs = FelsongFiles.fs(context)
        fs.mkdirs(layout.backups)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = fs.check(File(layout.backups, "felsong-$stamp-$label.sql"))
        val path = FelsongFiles.serverRoot(context) + "/backups/" + file.name
        runBlocking {
            FelsongServer.withDatabase(context) {
                runCtl(context, Kind.BACKUP, Step.BACKUP, listOf("backup", path), "backup.log", timeoutMinutes = 30)
            }
        }
        backups(context).drop(MAX_BACKUPS).forEach { fs.delete(it) }
        return file
    }

    /** Backups, newest first. */
    fun backups(context: Context): List<File> =
        FelsongFiles.layout(context).backups.listFiles { file -> file.isFile && file.name.endsWith(".sql") }
            ?.sortedByDescending { it.name }.orEmpty()

    private fun restore(context: Context, name: String): String {
        if (FelsongServer.isBusy) throw JobException(context.getString(R.string.felsong_error_stop_first))
        val layout = FelsongFiles.layout(context)
        val file = FelsongFiles.fs(context).check(File(layout.backups, name))
        if (!file.isFile || name.contains('/')) throw JobException(context.getString(R.string.felsong_error_no_backup))
        working(Kind.RESTORE, Step.RESTORE)
        val path = FelsongFiles.serverRoot(context) + "/backups/" + file.name
        runBlocking {
            FelsongServer.withDatabase(context) {
                runCtl(context, Kind.RESTORE, Step.RESTORE, listOf("restore", path), "restore.log", timeoutMinutes = 30)
            }
        }
        return context.getString(R.string.felsong_done_restore, file.name)
    }

    /**
     * Everything of Felsong goes: its realm stopped, its game Windows (Wine prefix), `files/felsong`
     * whole, its preferences. Nothing else: the guard refuses any other folder.
     */
    private fun uninstall(context: Context): String {
        val kind = Kind.UNINSTALL
        working(kind, Step.REMOVING)
        runBlocking { FelsongServer.stopAndWait(context) }
        FelsongGame.removeWindows(context)
        val layout = FelsongFiles.layout(context)
        SafeFs(context.filesDir, layout.protectedFolders).delete(layout.root)
        FelsongLogin.forgetAll(context)
        return context.getString(R.string.felsong_done_uninstall)
    }

    // Helpers ------------------------------------------------------------------------------------

    /** Runs Felsong's ctl.sh to its end on this thread, its output in `logs/<log>`. */
    private fun runCtl(context: Context, kind: Kind, step: Step, args: List<String>, log: String, timeoutMinutes: Long = 10, success: Set<Int> = setOf(0), onLine: ((String) -> Unit)? = null) {
        val layout = FelsongFiles.layout(context)
        val fs = FelsongFiles.fs(context)
        fs.mkdirs(layout.logs)
        val logFile = fs.check(File(layout.logs, log))
        val process = FelsongServer.ctl(context, *args.toTypedArray()).redirectErrorStream(true).redirectInput(File("/dev/null")).start()
        val reader = Thread {
            runCatching {
                logFile.outputStream().bufferedWriter().use { out ->
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            out.write(line)
                            out.newLine()
                            out.flush()
                            onLine?.invoke(line.take(160))
                        }
                    }
                }
            }
        }.apply { start() }
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(timeoutMinutes)
        while (!process.waitFor(1, TimeUnit.SECONDS)) {
            if (cancelled || System.currentTimeMillis() > deadline) {
                process.destroy()
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
                break
            }
        }
        reader.join(5_000)
        checkCancelled()
        if (System.currentTimeMillis() > deadline) throw JobException(context.getString(R.string.felsong_error_step_slow, args.first()), WowFiles.tail(logFile, 30))
        val code = process.exitValue()
        if (code !in success) throw JobException(context.getString(R.string.felsong_error_step, args.first(), code), WowFiles.tail(logFile, 30))
        working(kind, step)
    }

    private fun checkSpace(context: Context, needed: Long) {
        val free = FelsongFiles.freeSpace(context)
        if (needed > 0 && free < needed) {
            throw JobException(context.getString(R.string.felsong_error_space, size(context, needed), size(context, free)))
        }
    }

    private fun sizeOf(context: Context, uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L

    private fun size(context: Context, bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    /** Room kept free beyond what a step writes. */
    private const val SPACE_MARGIN = 500_000_000L

    /** LegionCore's databases, made from the seed (world and hotfixes are most of it). */
    private const val DATABASE_SPACE = 2_000_000_000L

    /** A 7.3.5 client when its zip's size isn't known. */
    private const val CLIENT_SPACE = 50_000_000_000L

    /** dbc, maps, cameras, gt and vmaps while they're made (mmaps come to as much again). */
    private const val DATA_SPACE = 12_000_000_000L

    private const val MAX_BACKUPS = 6
}
