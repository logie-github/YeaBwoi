// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import com.logie.warcraft.R
import com.logie.warcraft.WowFiles
import com.logie.warcraft.client.ClientBuild
import com.logie.warcraft.turtle.core.ClientManifest
import com.logie.warcraft.turtle.core.InstallState
import com.logie.warcraft.turtle.core.SafeFs
import com.logie.warcraft.turtle.core.TurtleLayout
import com.logie.warcraft.turtle.core.VerifiedDownload
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * Turtle WoW's installation, repair and removal, run one at a time by [TurtleService] (which keeps
 * them going with the app in the background). Every file they touch is in `files/turtle`, through
 * [TurtleFiles.fs]; a step that fails leaves what was there before it (staging folders, a download
 * kept to carry on from), so Install can be pressed again, and Repair or Uninstall clean it up.
 */
object TurtleJobs {
    enum class Kind { INSTALL, PROGRAMS, DATABASE, RUNTIME, CLIENT, VERIFY, DATA, REPAIR, BACKUP, RESTORE, UNINSTALL }

    enum class Step { CHECKING, DOWNLOADING, UNPACKING, DATABASE, RUNTIME, COPYING_CLIENT, VERIFYING_CLIENT, MAPS, VMAPS, MMAPS, VERIFYING_DATA, ACCOUNT, BACKUP, RESTORE, REMOVING }

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
                Kind.REPAIR -> repair(context, options)
                Kind.BACKUP -> backup(context)
                Kind.RESTORE -> restore(context, options.restoreFile ?: throw JobException("No backup chosen"))
                Kind.UNINSTALL -> uninstall(context)
            }
            if (kind != Kind.UNINSTALL) TurtleFiles.updateState(context) { it.copy(interruptedStep = null) }
            _state.value = State.Done(kind, message)
        } catch (e: VerifiedDownload.Cancelled) {
            remember(context, kind)
            _state.value = State.Failed(kind, context.getString(R.string.turtle_job_cancelled))
        } catch (e: JobException) {
            remember(context, kind)
            _state.value = State.Failed(kind, e.message ?: "", e.log)
        } catch (e: SafeFs.Refused) {
            // A guard stopped it: nothing outside Turtle's folder was touched.
            _state.value = State.Failed(kind, context.getString(R.string.turtle_job_refused, e.message ?: ""))
        } catch (e: TurtlePackage.NotPinned) {
            _state.value = State.Failed(kind, context.getString(R.string.turtle_error_not_pinned))
        } catch (e: VerifiedDownload.Corrupt) {
            remember(context, kind)
            _state.value = State.Failed(kind, context.getString(R.string.turtle_error_corrupt))
        } catch (e: Exception) {
            remember(context, kind)
            _state.value = State.Failed(kind, context.getString(R.string.turtle_job_error, e.message ?: e.javaClass.simpleName))
        }
    }

    /** The step a job stopped on, for the launcher to offer carrying on. */
    private fun remember(context: Context, kind: Kind) {
        if (kind == Kind.UNINSTALL) return
        val step = (_state.value as? State.Working)?.step?.name ?: return
        runCatching { TurtleFiles.updateState(context) { it.copy(interruptedStep = step) } }
    }

    // Install ------------------------------------------------------------------------------------

    /** Everything the app can do by itself, from where the phone is: the one-click install. */
    private fun install(context: Context, options: Options): String {
        val kind = Kind.INSTALL
        working(kind, Step.CHECKING)
        preflight(context)
        val pin = TurtlePackage.pinned ?: throw TurtlePackage.NotPinned()
        cleanLeftovers(context)

        if (!TurtlePackage.isCurrent(context)) programsStep(context, kind, pin)
        if (!TurtleFiles.hasDatabases(context)) databaseStep(context, kind, pin)

        val state = TurtleFiles.state(context)
        if (TurtleFiles.hasClient(context) && (!TurtleFiles.hasData(context) || state.dataFrom != state.clientExeSha256)) {
            makeData(context, kind, options.withMmaps)
        }

        val name = options.accountName
        val password = options.accountPassword
        if (name != null && password != null) {
            working(kind, Step.ACCOUNT)
            runBlocking {
                TurtleServer.withDatabase(context) {
                    if (TurtleAccounts.list(context).none { it.name.equals(name, ignoreCase = true) }) {
                        TurtleAccounts.create(context, name, password, com.logie.warcraft.turtle.core.TurtleAccountRules.RANK_ADMINISTRATOR)
                    }
                }
            }
            TurtleLogin.save(context, name, password)
        }
        return context.getString(
            if (TurtleFiles.hasClient(context) && TurtleFiles.hasData(context)) R.string.turtle_done_install_ready
            else R.string.turtle_done_install_client
        )
    }

    /** The realm's programs alone (a component's own button): downloaded and put in, again if [force]. */
    private fun programs(context: Context, force: Boolean): String {
        working(Kind.PROGRAMS, Step.CHECKING)
        preflight(context)
        val pin = TurtlePackage.pinned ?: throw TurtlePackage.NotPinned()
        cleanLeftovers(context)
        if (force || !TurtlePackage.isCurrent(context)) programsStep(context, Kind.PROGRAMS, pin)
        return context.getString(R.string.turtle_done_programs, pin.tortoise.take(7))
    }

    private fun programsStep(context: Context, kind: Kind, pin: TurtlePackage.Pin) {
        if (TurtleServer.isBusy) throw JobException(context.getString(R.string.turtle_error_stop_first))
        val needed = (pin.size - TurtlePackage.downloadedSoFar(context)) + pin.unpacked + SPACE_MARGIN
        checkSpace(context, needed)
        if (TurtleFiles.hasDatabases(context) && TurtlePackage.isInstalled(context)) backupQuietly(context, "before-update")
        working(kind, Step.DOWNLOADING, 0f)
        TurtlePackage.download(context, { cancelled }) { done, total ->
            working(kind, Step.DOWNLOADING, done.toFloat() / total, "${size(context, done)} / ${size(context, total)}")
        }
        working(kind, Step.UNPACKING, 0f)
        TurtlePackage.install(context, { cancelled }) { working(kind, Step.UNPACKING, it) }
        TurtleFiles.updateState(context) { it.copy(serverSha256 = pin.sha256, serverTag = pin.tag) }
    }

    /** Turtle's own databases alone (a component's own button), from the package's seed. */
    private fun database(context: Context): String {
        working(Kind.DATABASE, Step.CHECKING)
        preflight(context)
        val pin = TurtlePackage.pinned ?: throw TurtlePackage.NotPinned()
        if (!TurtlePackage.isInstalled(context)) throw JobException(context.getString(R.string.turtle_error_not_installed))
        if (!TurtleFiles.hasDatabases(context)) databaseStep(context, Kind.DATABASE, pin)
        return context.getString(R.string.turtle_done_database)
    }

    /**
     * The game's Windows runtime (Wine, Box64, the graphics drivers), from the APK: the same one
     * the 3.3.5a game installs at its first start, shared program files that Turtle never changes
     * or removes once they're there. Nothing happens if they're in place already.
     */
    private fun runtime(context: Context): String {
        working(Kind.RUNTIME, Step.RUNTIME, 0f)
        // Only where there's none at all: an older one is brought up to date by the game's start
        // (either game's), which clears and unpacks the runtime's folder.
        if (!TurtleGame.runtimeThere(context)) {
            val ok = com.winlator.xenvironment.RootFSInstaller.installIfNeeded(context) { percent ->
                working(Kind.RUNTIME, Step.RUNTIME, percent / 100f)
            }
            if (!ok) throw JobException(context.getString(R.string.turtle_game_error_runtime))
        }
        return context.getString(R.string.turtle_done_runtime)
    }

    private fun databaseStep(context: Context, kind: Kind, pin: TurtlePackage.Pin) {
        working(kind, Step.DATABASE)
        checkSpace(context, SPACE_MARGIN * 2)
        runCtl(context, kind, Step.DATABASE, listOf("db-init"), "db-init.log", timeoutMinutes = 20)
        TurtleFiles.updateState(context) { it.copy(databaseFrom = pin.sha256, databaseUpdated = false) }
    }

    private fun preflight(context: Context) {
        when (TurtleFiles.blocker(context)) {
            "arm64" -> throw JobException(context.getString(R.string.turtle_error_not_arm64))
            "path" -> throw JobException(context.getString(R.string.turtle_error_path, WowFiles.root(context).path))
        }
    }

    /** Staging left by a step that stopped (never the finished folders). */
    private fun cleanLeftovers(context: Context) {
        val layout = TurtleFiles.layout(context)
        val fs = TurtleFiles.fs(context)
        for (folder in listOf(layout.serverStaging, layout.serverOld, layout.clientStaging, File(layout.client.path + ".old"), File(layout.server.path + ".old"))) {
            fs.delete(folder)
        }
    }

    // The client ---------------------------------------------------------------------------------

    private data class Picked(val uri: Uri, val path: String, val size: Long)

    /**
     * Copies the user's Turtle WoW client (a folder or a zip) into `client.import`, hashing every
     * file as it's copied and comparing it with Turtle's final 1.18.1 manifest, then puts it in
     * place of `client/`. Then the server's data is made from it.
     */
    private fun importClient(context: Context, uri: Uri, isFolder: Boolean, options: Options): String {
        val kind = Kind.CLIENT
        working(kind, Step.CHECKING)
        preflight(context)
        val layout = TurtleFiles.layout(context)
        val fs = TurtleFiles.fs(context)
        val manifest = TurtleFiles.clientManifest(context)
        cleanLeftovers(context)
        fs.mkdirs(layout.clientStaging)
        val hashes = mutableMapOf<String, String>()
        try {
            if (isFolder) copyFolder(context, uri, layout, fs, manifest, hashes) else copyZip(context, uri, layout, fs, manifest, hashes)
            checkCancelled()
            working(kind, Step.VERIFYING_CLIENT)
            val exe = ClientManifest.find(layout.clientStaging, ClientManifest.EXE)
                ?: throw JobException(context.getString(R.string.turtle_error_not_client))
            val report = compare(manifest, hashes)
            if (!report.usable(manifest)) {
                throw JobException(context.getString(R.string.turtle_error_client_incomplete, report.missing.filter { it in manifest.required }.joinToString()))
            }
            val exeHash = hashes[exe.relativeTo(layout.clientStaging).path.replace('\\', '/').lowercase()]
                ?: exe.inputStream().use { ClientManifest.sha256(it) }
            fs.replace(layout.clientStaging, layout.client)
            TurtleFiles.updateState(context) {
                it.copy(
                    clientExeSha256 = exeHash,
                    clientCheck = if (report.exact) InstallState.ClientCheck.EXACT else InstallState.ClientCheck.DIFFERENT,
                    clientDifferences = report.missing.size + report.different.size,
                )
            }
        } finally {
            fs.delete(layout.clientStaging)
        }
        // The server's data comes from this client: made now, if the realm is installed.
        if (TurtlePackage.isInstalled(context)) makeData(context, kind, options.withMmaps)
        val state = TurtleFiles.state(context)
        return context.getString(
            if (state.clientCheck == InstallState.ClientCheck.EXACT) R.string.turtle_done_client_exact else R.string.turtle_done_client_different,
            state.clientDifferences,
        )
    }

    private fun compare(manifest: ClientManifest, hashes: Map<String, String>): ClientManifest.Report {
        val missing = mutableListOf<String>()
        val different = mutableListOf<String>()
        for (entry in manifest.files) {
            val hash = hashes[entry.path.lowercase()]
            when {
                hash == null -> missing += entry.path
                !hash.equals(entry.sha256, ignoreCase = true) -> different += entry.path
            }
        }
        return ClientManifest.Report(manifest.files.size, missing, different, emptyList())
    }

    private fun copyFolder(context: Context, tree: Uri, layout: TurtleLayout, fs: SafeFs, manifest: ClientManifest, hashes: MutableMap<String, String>) {
        val files = mutableListOf<Picked>()
        listTree(context, tree, DocumentsContract.getTreeDocumentId(tree), "", files)
        val root = ClientManifest.findRoot(files.map { it.path }) ?: throw JobException(context.getString(R.string.turtle_error_not_client))
        val prefix = if (root.isEmpty()) "" else "$root/"
        val wanted = files.filter { it.path.startsWith(prefix) }
        val total = wanted.sumOf { it.size }
        checkSpace(context, total + SPACE_MARGIN)
        var done = 0L
        for (file in wanted) {
            checkCancelled()
            val relative = file.path.removePrefix(prefix)
            if (!ClientManifest.isSafePath(relative)) continue
            val target = fs.check(File(layout.clientStaging, relative))
            target.parentFile?.let { fs.mkdirs(it) }
            val input = context.contentResolver.openInputStream(file.uri) ?: throw JobException(context.getString(R.string.turtle_error_read, file.path))
            val hash = input.use { copyHashing(it, target) { read -> done += read; progressCopy(context, done, total, relative) } }
            hashes[relative.lowercase()] = hash
        }
    }

    private fun copyZip(context: Context, zip: Uri, layout: TurtleLayout, fs: SafeFs, manifest: ClientManifest, hashes: MutableMap<String, String>) {
        val total = sizeOf(context, zip)
        checkSpace(context, (if (total > 0) total else manifest.totalSize) + SPACE_MARGIN)
        val input = context.contentResolver.openInputStream(zip) ?: throw JobException(context.getString(R.string.turtle_error_read, zip.lastPathSegment ?: ""))
        var read = 0L
        val counting = object : java.io.FilterInputStream(input) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
        }
        val written = mutableMapOf<String, String>()
        try {
            ZipInputStream(counting.buffered(1 shl 16)).use { stream ->
                while (true) {
                    checkCancelled()
                    val entry = stream.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.replace('\\', '/')
                    if (!ClientManifest.isSafePath(name)) continue
                    val target = fs.check(File(layout.clientStaging, name))
                    target.parentFile?.let { fs.mkdirs(it) }
                    written[name.lowercase()] = copyHashing(stream, target, close = false) { progressCopy(context, read, total, name.substringAfterLast('/')) }
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw JobException(context.getString(R.string.turtle_error_zip, e.message ?: ""))
        }
        // The client may be in a folder of the zip: it's moved up to the top of the staging.
        val paths = written.keys.toList()
        val root = ClientManifest.findRoot(paths) ?: throw JobException(context.getString(R.string.turtle_error_not_client))
        if (root.isNotEmpty()) {
            val inner = ClientManifest.find(layout.clientStaging, root) ?: throw JobException(context.getString(R.string.turtle_error_not_client))
            val lifted = File(layout.root, "client.lift")
            fs.delete(lifted)
            fs.move(inner, lifted)
            fs.delete(layout.clientStaging)
            fs.move(lifted, layout.clientStaging)
        }
        val prefix = if (root.isEmpty()) "" else "${root.lowercase()}/"
        for ((path, hash) in written) if (path.startsWith(prefix)) hashes[path.removePrefix(prefix)] = hash
    }

    private fun progressCopy(context: Context, done: Long, total: Long, name: String) {
        working(Kind.CLIENT, Step.COPYING_CLIENT, if (total > 0) done.toFloat() / total else null, "${size(context, done)} · $name")
    }

    /** Copies [input] into [target] and returns its SHA-256; [onRead] gets each block's size. */
    private fun copyHashing(input: InputStream, target: File, close: Boolean = true, onRead: (Int) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        target.outputStream().use { output ->
            val buffer = ByteArray(1 shl 16)
            var since = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                digest.update(buffer, 0, n)
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

    /** The client in place, checked again file by file against the manifest (Repair). */
    private fun verifyClient(context: Context): String {
        val kind = Kind.VERIFY
        val layout = TurtleFiles.layout(context)
        if (!TurtleFiles.hasClient(context)) throw JobException(context.getString(R.string.turtle_error_no_client))
        val manifest = TurtleFiles.clientManifest(context)
        val total = manifest.totalSize
        val report = manifest.verify(layout.client, deep = true) { done, name ->
            checkCancelled()
            working(kind, Step.VERIFYING_CLIENT, done.toFloat() / total, name)
        }
        val exe = ClientManifest.find(layout.client, ClientManifest.EXE)
        val exeHash = exe?.inputStream()?.use { ClientManifest.sha256(it) }
        TurtleFiles.updateState(context) {
            it.copy(
                clientExeSha256 = exeHash,
                clientCheck = if (report.exact) InstallState.ClientCheck.EXACT else InstallState.ClientCheck.DIFFERENT,
                clientDifferences = report.missing.size + report.different.size,
            )
        }
        val version = exe?.inputStream()?.buffered()?.use { ClientBuild.read(it) }?.toString() ?: "?"
        return if (report.exact) context.getString(R.string.turtle_done_verify_exact, version)
        else context.getString(R.string.turtle_done_verify_different, report.missing.size, report.different.size, (report.missing + report.different).take(6).joinToString())
    }

    // The server's data --------------------------------------------------------------------------

    /**
     * dbc and maps (minutes), vmaps (minutes more) and, if asked, mmaps (hours) from the client in
     * `client/`, into `data.work`; the DBC files checked against Tortoise's checksums; then in place
     * of `data/` in one rename.
     */
    private fun makeData(context: Context, kind: Kind, withMmaps: Boolean): String {
        if (!TurtlePackage.isInstalled(context)) throw JobException(context.getString(R.string.turtle_error_not_installed))
        if (!TurtleFiles.hasClient(context)) throw JobException(context.getString(R.string.turtle_error_no_client))
        if (TurtleServer.isBusy) throw JobException(context.getString(R.string.turtle_error_stop_first))
        checkSpace(context, DATA_SPACE)
        val client = TurtleFiles.serverRoot(context) + "/client"
        val parts = if (withMmaps) 3 else 2
        working(kind, Step.MAPS, 0f)
        val extracting = Regex("""Extract \S+ \((\d+)/(\d+)\)""")
        runCtl(context, kind, Step.MAPS, listOf("maps", client), "maps.log", timeoutMinutes = 120) { line ->
            extracting.find(line)?.let { match ->
                val (at, of) = match.destructured
                working(kind, Step.MAPS, at.toFloat() / of.toFloat().coerceAtLeast(1f) / parts, line.trim())
            } ?: working(kind, Step.MAPS, null, line.trim())
        }
        working(kind, Step.VMAPS, 1f / parts)
        runCtl(context, kind, Step.VMAPS, listOf("vmaps", client), "vmaps.log", timeoutMinutes = 240) { line ->
            working(kind, Step.VMAPS, 1f / parts, line.trim())
        }
        if (withMmaps) {
            working(kind, Step.MMAPS, 2f / parts)
            runCtl(context, kind, Step.MMAPS, listOf("mmaps"), "mmaps.log", timeoutMinutes = 24 * 60) { line ->
                working(kind, Step.MMAPS, 2f / parts, line.trim())
            }
        }
        working(kind, Step.VERIFYING_DATA, 1f)
        val layout = TurtleFiles.layout(context)
        val dbc = TurtleFiles.dbcManifest(context).verify(File(layout.dataWork, "dbc"))
        if (!File(layout.dataWork, "maps").isDirectory || (File(layout.dataWork, "maps").list()?.size ?: 0) == 0) {
            throw JobException(context.getString(R.string.turtle_error_no_maps), WowFiles.tail(File(layout.logs, "maps.log"), 30))
        }
        runCtl(context, kind, Step.VERIFYING_DATA, listOf("data-done"), "data-done.log")
        val exeHash = TurtleFiles.state(context).clientExeSha256
        TurtleFiles.updateState(context) {
            it.copy(
                dataFrom = exeHash,
                dataParts = TurtleFiles.dataParts(context),
                dbcCheck = if (dbc.exact) InstallState.ClientCheck.EXACT else InstallState.ClientCheck.DIFFERENT,
            )
        }
        return if (dbc.exact) context.getString(R.string.turtle_done_data, TurtleFiles.dataParts(context).sorted().joinToString())
        else context.getString(R.string.turtle_done_data_dbc_different, dbc.missing.size + dbc.different.size)
    }

    // Repair, backups, uninstall -----------------------------------------------------------------

    private fun repair(context: Context, options: Options): String {
        val kind = Kind.REPAIR
        working(kind, Step.CHECKING)
        preflight(context)
        if (TurtleServer.isBusy) throw JobException(context.getString(R.string.turtle_error_stop_first))
        val layout = TurtleFiles.layout(context)
        val fs = TurtleFiles.fs(context)
        cleanLeftovers(context)
        fs.delete(layout.dataWork)
        val done = mutableListOf<String>()
        if (Repair.DATABASES in options.repairs && TurtleFiles.hasDatabases(context)) {
            working(kind, Step.BACKUP)
            backupQuietly(context, "before-reset")
            working(kind, Step.DATABASE)
            fs.delete(File(layout.databases.path + ".new"))
            fs.delete(layout.databases)
            TurtleFiles.updateState(context) { it.copy(databaseFrom = null, databaseUpdated = false) }
            done += context.getString(R.string.turtle_repair_databases)
        }
        if (Repair.PROGRAMS in options.repairs) {
            TurtlePackage.forgetDownload(context, fs)
            fs.delete(File(layout.server, ".installed"))
            done += context.getString(R.string.turtle_repair_programs)
        }
        if (Repair.CONFIGS in options.repairs) {
            fs.delete(File(layout.etc, "mangosd.conf"))
            fs.delete(File(layout.etc, "realmd.conf"))
            done += context.getString(R.string.turtle_repair_configs)
        }
        // Programs and databases come back the way Install brings them.
        if (Repair.PROGRAMS in options.repairs || Repair.DATABASES in options.repairs) {
            install(context, Options(withMmaps = options.withMmaps))
            _state.value = State.Working(kind, Step.CHECKING)
        }
        if (Repair.CLIENT in options.repairs && TurtleFiles.hasClient(context)) {
            verifyClient(context)
            done += context.getString(R.string.turtle_repair_client)
        }
        if (Repair.DATA in options.repairs && TurtleFiles.hasClient(context)) {
            makeData(context, kind, options.withMmaps)
            done += context.getString(R.string.turtle_repair_data)
        }
        return context.getString(R.string.turtle_done_repair, done.joinToString().ifEmpty { context.getString(R.string.turtle_repair_cleaned) })
    }

    /** The accounts and characters into `backups/` (the last few kept). */
    private fun backup(context: Context): String {
        working(Kind.BACKUP, Step.BACKUP)
        val file = backupNow(context, "manual")
        return context.getString(R.string.turtle_done_backup, file.name)
    }

    private fun backupQuietly(context: Context, label: String) {
        runCatching { backupNow(context, label) }
    }

    private fun backupNow(context: Context, label: String): File {
        if (!TurtlePackage.isInstalled(context) || !TurtleFiles.hasDatabases(context)) throw JobException(context.getString(R.string.turtle_error_no_databases))
        val layout = TurtleFiles.layout(context)
        val fs = TurtleFiles.fs(context)
        fs.mkdirs(layout.backups)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = fs.check(File(layout.backups, "turtle-$stamp-$label.sql"))
        val path = TurtleFiles.serverRoot(context) + "/backups/" + file.name
        runBlocking {
            TurtleServer.withDatabase(context) {
                runCtl(context, Kind.BACKUP, Step.BACKUP, listOf("backup", path), "backup.log", timeoutMinutes = 30)
            }
        }
        backups(context).drop(MAX_BACKUPS).forEach { fs.delete(it) }
        return file
    }

    /** Backups, newest first. */
    fun backups(context: Context): List<File> =
        TurtleFiles.layout(context).backups.listFiles { file -> file.isFile && file.name.endsWith(".sql") }
            ?.sortedByDescending { it.name }.orEmpty()

    private fun restore(context: Context, name: String): String {
        if (TurtleServer.isBusy) throw JobException(context.getString(R.string.turtle_error_stop_first))
        val layout = TurtleFiles.layout(context)
        val file = TurtleFiles.fs(context).check(File(layout.backups, name))
        if (!file.isFile || name.contains('/')) throw JobException(context.getString(R.string.turtle_error_no_backup))
        working(Kind.RESTORE, Step.RESTORE)
        val path = TurtleFiles.serverRoot(context) + "/backups/" + file.name
        runBlocking {
            TurtleServer.withDatabase(context) {
                runCtl(context, Kind.RESTORE, Step.RESTORE, listOf("restore", path), "restore.log", timeoutMinutes = 30)
            }
        }
        return context.getString(R.string.turtle_done_restore, file.name)
    }

    /**
     * Everything of Turtle WoW goes: its realm stopped, its game Windows (Wine prefix), `files/turtle`
     * whole, its preferences. Nothing else: the guard refuses any other folder, and the Windows
     * removal is limited to Turtle's own prefix.
     */
    private fun uninstall(context: Context): String {
        val kind = Kind.UNINSTALL
        working(kind, Step.REMOVING)
        runBlocking { TurtleServer.stopAndWait(context) }
        TurtleGame.removeWindows(context)
        val layout = TurtleFiles.layout(context)
        // Scope: the app's files folder, with every other game's folder protected: only
        // files/turtle can go.
        SafeFs(context.filesDir, layout.protectedFolders).delete(layout.root)
        TurtleLogin.forgetAll(context)
        return context.getString(R.string.turtle_done_uninstall)
    }

    // Helpers ------------------------------------------------------------------------------------

    /** Runs Turtle's ctl.sh to its end on this thread, its output in `logs/<log>`. */
    private fun runCtl(context: Context, kind: Kind, step: Step, args: List<String>, log: String, timeoutMinutes: Long = 10, onLine: ((String) -> Unit)? = null) {
        val layout = TurtleFiles.layout(context)
        val fs = TurtleFiles.fs(context)
        fs.mkdirs(layout.logs)
        val logFile = fs.check(File(layout.logs, log))
        val process = TurtleServer.ctl(context, *args.toTypedArray()).redirectErrorStream(true).redirectInput(File("/dev/null")).start()
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
        if (System.currentTimeMillis() > deadline) throw JobException(context.getString(R.string.turtle_error_step_slow, args.first()), WowFiles.tail(logFile, 30))
        val code = process.exitValue()
        if (code != 0) throw JobException(context.getString(R.string.turtle_error_step, args.first(), code), WowFiles.tail(logFile, 30))
        working(kind, step)
    }

    private fun checkSpace(context: Context, needed: Long) {
        val free = TurtleFiles.freeSpace(context)
        if (needed > 0 && free < needed) {
            throw JobException(context.getString(R.string.turtle_error_space, size(context, needed), size(context, free)))
        }
    }

    private fun sizeOf(context: Context, uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L

    private fun size(context: Context, bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    /** Room kept free beyond what a step writes. */
    private const val SPACE_MARGIN = 500_000_000L

    /** dbc, maps and vmaps while they're made (mmaps come to about as much again). */
    private const val DATA_SPACE = 3_000_000_000L

    private const val MAX_BACKUPS = 6
}
