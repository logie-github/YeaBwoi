// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.export

import android.content.Context
import android.net.Uri
import com.logie.warcraft.BuildConfig
import com.logie.warcraft.R
import com.logie.warcraft.Server
import com.logie.warcraft.felsong.FelsongJobs
import com.logie.warcraft.felsong.FelsongServer
import com.logie.warcraft.Imports
import com.logie.warcraft.export.core.ExportCancelled
import com.logie.warcraft.export.core.ExportContext
import com.logie.warcraft.export.core.ExportPlan
import com.logie.warcraft.export.core.ExportPlanner
import com.logie.warcraft.export.core.ExportProgress
import com.logie.warcraft.export.core.ExportRules
import com.logie.warcraft.export.core.ExportVerifier
import com.logie.warcraft.export.core.Exporter
import com.logie.warcraft.export.core.ZipExporter
import com.logie.warcraft.export.core.JavaFileSource
import com.logie.warcraft.export.core.PrefsDump
import com.logie.warcraft.export.core.VerifyReport
import com.logie.warcraft.game.Game
import com.logie.warcraft.local.WindowsRealmRegistry
import com.logie.warcraft.spp.SppFiles
import com.logie.warcraft.spp.SppJobs
import com.logie.warcraft.turtle.TurtleJobs
import com.logie.warcraft.turtle.TurtleServer
import com.logie.warcraft.tww.TwwFiles
import com.logie.warcraft.tww.TwwJobs
import com.logie.warcraft.tww.TwwServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * "Export everything to Blizzard": a verified, resumable COPY of everything the user would hate to
 * rebuild or lose (all four games' folders in `files/`, and the preferences that hold their
 * choices) into a folder they pick, for the Blizzard launcher app (com.logie.blizz) to import.
 * Nothing in this app is changed or deleted. What is copied, and the format: [ExportRules],
 * [Exporter] and warcraft/README.md. [ExportService] runs it.
 *
 * Consistency: MariaDB's files are copied only while no database runs. A running realm is never
 * copied: the export refuses while a game is open or a job is running, and while a realm runs it
 * asks the user to confirm, then stops it through the app's own shutdown ([stopRealms]: the same
 * `stopAndWait` the imports use, which saves the characters, ends the login server, then lets
 * MariaDB flush and exit) before it reads a single file.
 */
object BlizzardExport {
    enum class Phase { CHECKING, STOPPING, SCANNING, COPYING, VERIFYING }

    sealed interface State {
        data object Idle : State
        data class Working(val phase: Phase, val progress: ExportProgress? = null, val note: String? = null) : State
        data class Done(val files: Int, val bytes: Long, val skipped: Int, val destination: String) : State
        data class Verified(val report: VerifyReport, val destination: String) : State
        data class Failed(val message: String, val canResume: Boolean = false) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    @Volatile var cancelled = false

    fun set(state: State) { _state.value = state }

    fun clearResult() {
        if (_state.value !is State.Working) _state.value = State.Idle
    }

    /** A failure the user is told as it is. */
    class ExportException(message: String, val canResume: Boolean = false) : IOException(message)

    // ---- What stops an export ------------------------------------------------------------------

    /** What the export can't go on beside: a game's screen open, or a job changing the files. Null: nothing. */
    fun blocker(context: Context): String? = when {
        WindowsRealmRegistry.anyBusy() || Game.state.value is Game.State.Preparing -> context.getString(R.string.export_blocked_game)
        Imports.state.value is Imports.State.Working || TurtleJobs.busy || FelsongJobs.busy || SppJobs.busy || TwwJobs.busy ->
            context.getString(R.string.export_blocked_job)
        else -> null
    }

    /** A realm (or a database left over) that has to be stopped before the files are read. */
    class Running(val label: String, val stop: suspend () -> Unit)

    fun runningRealms(context: Context): List<Running> {
        val running = ArrayList<Running>()
        val status = Server.status.value
        if (status.phase != Server.Phase.STOPPED && status.phase != Server.Phase.FAILED || Server.databaseUp) {
            running += Running(status.realm.displayName) { Server.stopAndWait(context) }
        }
        if (TurtleServer.isBusy || TurtleServer.databaseUp) running += Running("OctoWoW") { TurtleServer.stopAndWait(context) }
        if (FelsongServer.isBusy || FelsongServer.databaseUp) running += Running("Legion (LegionCore)") { FelsongServer.stopAndWait(context) }
        for (server in WindowsRealmRegistry.servers()) {
            if (server.isBusy) running += Running(if (server === TwwServer) "The War Within" else "Legion (Single Player Project)") { server.stopAndWait(context) }
        }
        leftoverDatabases(context).forEach { pid ->
            running += Running("a database (process $pid)") { endProcess(pid) }
        }
        return running.distinctBy { it.label }
    }

    /** MariaDB programs named in the pid files that are still alive (Android ended the app without stopping them). */
    private fun leftoverDatabases(context: Context): List<Int> {
        val files = context.filesDir
        val runFolders = listOf(File(files, "run"), File(files, "turtle/run"), File(files, "felsong/run"))
        return runFolders.mapNotNull { File(it, "mysqld.pid").takeIf { f -> f.isFile }?.readText()?.trim()?.toIntOrNull() }
            .filter { isDatabase(it) }
    }

    private fun isDatabase(pid: Int) =
        runCatching { File("/proc/$pid/cmdline").readText().let { "mysqld" in it || "mariadbd" in it } }.getOrDefault(false)

    private suspend fun endProcess(pid: Int) {
        // (the realm's own stop may have ended it already: then the pid is gone, or another program's now)
        if (!isDatabase(pid)) return
        android.os.Process.sendSignal(pid, 15) // SIGTERM: MariaDB flushes InnoDB and ends, as the realm's own stop does
        repeat(120) {
            if (!isDatabase(pid)) return
            delay(500)
        }
        throw ExportException("A database (process $pid) didn't stop. Stop the realm in its game's Server screen and try again.")
    }

    /** Stops [realms] and makes sure no database is left running. Background thread. */
    suspend fun stopRealms(context: Context, realms: List<Running>, onStopping: (String) -> Unit) {
        for (realm in realms) {
            onStopping(realm.label)
            realm.stop()
        }
        // The databases are what must be still: anything still running is a refusal, never a copy.
        val left = runningRealms(context)
        if (left.isNotEmpty()) {
            throw ExportException(context.getString(R.string.export_still_running, left.joinToString { it.label }))
        }
    }

    // ---- What is exported ----------------------------------------------------------------------

    fun source(context: Context) = JavaFileSource(context.filesDir)

    fun scan(context: Context, isCancelled: () -> Boolean = { false }): ExportPlan = ExportPlanner.scan(source(context), isCancelled)

    /** The preferences with something in them, as `prefs/<name>.json` text. */
    fun preferences(context: Context): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (name in ExportRules.PREFERENCES) {
            val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            if (all.isNotEmpty()) result[name] = PrefsDump.toJson(all)
        }
        return result
    }

    /** Installs the user chose to keep on the phone's external storage (outside `files/`): not exported. */
    fun outsideFiles(context: Context): List<String> = buildList {
        runCatching { if (SppFiles.external(context)) add("Legion's Single Player Project files (spp-legion)") }
        runCatching { if (TwwFiles.storageExternal(context)) add("The War Within's files (tww)") }
    }

    // ---- The remembered destination ------------------------------------------------------------

    private const val PREFS = "export"
    private const val KEY_TREE = "tree"

    fun rememberedTree(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)?.let { Uri.parse(it) }

    fun remember(context: Context, tree: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, tree.toString()).apply()
    }

    // ---- The jobs ------------------------------------------------------------------------------

    /** Runs the whole export. [mayStop]: the user confirmed that a running realm may be stopped. Background thread. */
    suspend fun export(context: Context, tree: Uri, groups: Set<ExportRules.Group>, mayStop: Boolean) {
        val sink = SafExportSink(context, tree)
        set(State.Working(Phase.CHECKING))
        blocker(context)?.let { throw ExportException(it) }
        val running = runningRealms(context)
        if (running.isNotEmpty()) {
            if (!mayStop) throw ExportException(context.getString(R.string.export_blocked_realm, running.joinToString { it.label }))
            stopRealms(context, running) { set(State.Working(Phase.STOPPING, note = it)) }
        }

        set(State.Working(Phase.SCANNING))
        val plan = scan(context) { cancelled }.only(groups)
        if (plan.files.isEmpty()) throw ExportException(context.getString(R.string.export_nothing))
        val needed = ExportPlanner.remainingBytes(plan, sink)
        val free = sink.freeBytes()
        if (!ExportPlanner.fits(needed, free)) {
            throw ExportException(
                context.getString(
                    R.string.export_error_space,
                    android.text.format.Formatter.formatShortFileSize(context, needed),
                    android.text.format.Formatter.formatShortFileSize(context, free ?: 0),
                ),
                canResume = true,
            )
        }

        val manifestContext = ExportContext(
            appVersion = BuildConfig.VERSION_NAME,
            sourceFilesDir = context.filesDir.absolutePath,
            createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            prefs = preferences(context),
        )
        var last = 0L
        val manifest = try {
            Exporter.run(source(context), plan, sink, manifestContext, onProgress = { progress ->
                val now = System.currentTimeMillis()
                if (now - last >= 250 || progress.files == progress.totalFiles) {
                    last = now
                    set(State.Working(Phase.COPYING, progress))
                }
            }, isCancelled = { cancelled })
        } catch (e: ExportCancelled) {
            throw ExportException(context.getString(R.string.export_cancelled), canResume = true)
        } catch (e: IOException) {
            throw ExportException(context.getString(R.string.export_error_write, e.message ?: e.javaClass.simpleName), canResume = true)
        }
        val skipped = (_state.value as? State.Working)?.progress?.skipped ?: 0
        set(State.Done(manifest.files, manifest.bytes, skipped, sink.displayName()))
    }

    /**
     * [export]'s checks, then the same files written as one zip file ([ZipExporter]) at [destination], a
     * document the user made with the system's "save as" picker. Background thread.
     */
    suspend fun exportZip(context: Context, destination: Uri, groups: Set<ExportRules.Group>, mayStop: Boolean, mode: String? = null) {
        set(State.Working(Phase.CHECKING))
        blocker(context)?.let { throw ExportException(it) }
        val running = runningRealms(context)
        if (running.isNotEmpty()) {
            if (!mayStop) throw ExportException(context.getString(R.string.export_blocked_realm, running.joinToString { it.label }))
            stopRealms(context, running) { set(State.Working(Phase.STOPPING, note = it)) }
        }
        set(State.Working(Phase.SCANNING))
        val plan = scan(context) { cancelled }.only(groups)
        if (plan.files.isEmpty()) throw ExportException(context.getString(R.string.export_nothing))
        val manifestContext = ExportContext(
            appVersion = BuildConfig.VERSION_NAME,
            sourceFilesDir = context.filesDir.absolutePath,
            createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            prefs = preferences(context),
        )
        var last = 0L
        val resolver = context.contentResolver
        val manifest = try {
            val out = resolver.openOutputStream(destination, "wt") ?: throw ExportException(context.getString(R.string.export_error_write, "the destination can't be opened"))
            ZipExporter.run(source(context), plan, out, manifestContext, mode = mode, onProgress = { progress ->
                val now = System.currentTimeMillis()
                if (now - last >= 250 || progress.files == progress.totalFiles) {
                    last = now
                    set(State.Working(Phase.COPYING, progress))
                }
            }, isCancelled = { cancelled })
        } catch (e: ExportCancelled) {
            runCatching { android.provider.DocumentsContract.deleteDocument(resolver, destination) }
            throw ExportException(context.getString(R.string.export_cancelled))
        } catch (e: IOException) {
            throw ExportException(context.getString(R.string.export_error_write, e.message ?: e.javaClass.simpleName))
        }
        set(State.Done(manifest.files, manifest.bytes, 0, documentName(context, destination)))
    }

    /**
     * The chosen [modes], each as its own zip ([ExportRules.Mode.fileName]) in the folder [tree]: the realms are stopped and the
     * files counted once, then the zips are written one after another. A zip of that name already there is replaced. Background thread.
     */
    suspend fun exportZips(context: Context, tree: Uri, modes: List<ExportRules.Mode>, mayStop: Boolean) {
        set(State.Working(Phase.CHECKING))
        blocker(context)?.let { throw ExportException(it) }
        val running = runningRealms(context)
        if (running.isNotEmpty()) {
            if (!mayStop) throw ExportException(context.getString(R.string.export_blocked_realm, running.joinToString { it.label }))
            stopRealms(context, running) { set(State.Working(Phase.STOPPING, note = it)) }
        }
        set(State.Working(Phase.SCANNING))
        val all = scan(context) { cancelled }
        val todo = modes.map { it to all.only(it.groups) }.filter { it.second.files.isNotEmpty() }
        if (todo.isEmpty()) throw ExportException(context.getString(R.string.export_nothing))
        val manifestContext = ExportContext(
            appVersion = BuildConfig.VERSION_NAME,
            sourceFilesDir = context.filesDir.absolutePath,
            createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            prefs = preferences(context),
        )
        val resolver = context.contentResolver
        val folder = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
        var files = 0
        var bytes = 0L
        for ((index, pair) in todo.withIndex()) {
            val (mode, plan) = pair
            val note = "${mode.title} (${index + 1} of ${todo.size}): ${mode.fileName}"
            set(State.Working(Phase.COPYING, note = note))
            val document = try {
                replaceDocument(context, tree, folder, mode.fileName)
            } catch (e: Exception) {
                throw ExportException(context.getString(R.string.export_error_write, "${mode.fileName} can't be made there (${e.message})"))
            }
            var last = 0L
            try {
                val out = resolver.openOutputStream(document, "wt") ?: throw IOException("${mode.fileName} can't be opened")
                val manifest = ZipExporter.run(source(context), plan, out, manifestContext, mode = mode.key, onProgress = { progress ->
                    val now = System.currentTimeMillis()
                    if (now - last >= 250 || progress.files == progress.totalFiles) {
                        last = now
                        set(State.Working(Phase.COPYING, progress, note))
                    }
                }, isCancelled = { cancelled })
                files += manifest.files
                bytes += manifest.bytes
            } catch (e: ExportCancelled) {
                runCatching { android.provider.DocumentsContract.deleteDocument(resolver, document) }
                throw ExportException(context.getString(R.string.export_cancelled))
            } catch (e: IOException) {
                runCatching { android.provider.DocumentsContract.deleteDocument(resolver, document) }
                throw ExportException(context.getString(R.string.export_error_write, e.message ?: e.javaClass.simpleName))
            }
        }
        set(State.Done(files, bytes, 0, todo.joinToString { it.first.fileName }))
    }

    /** A new, empty [name] in [folder] (of the tree [tree]): one of that name already there goes first, so it isn't saved as "name (1)". */
    private fun replaceDocument(context: Context, tree: Uri, folder: Uri, name: String): Uri {
        val resolver = context.contentResolver
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, android.provider.DocumentsContract.getDocumentId(folder))
        resolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) runCatching { android.provider.DocumentsContract.deleteDocument(resolver, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))) }
            }
        }
        return android.provider.DocumentsContract.createDocument(resolver, folder, "application/zip", name) ?: throw IOException("the folder took no new file")
    }

    /** Re-reads the zip at [document] and compares it with its checksums.tsv. Background thread. */
    suspend fun verifyZip(context: Context, document: Uri) {
        set(State.Working(Phase.VERIFYING))
        var last = 0L
        val report = try {
            val input = context.contentResolver.openInputStream(document) ?: throw ExportException("The file can't be opened.")
            ZipExporter.verify(input, onProgress = { progress ->
                val now = System.currentTimeMillis()
                if (now - last >= 250) {
                    last = now
                    set(State.Working(Phase.VERIFYING, progress))
                }
            }, isCancelled = { cancelled })
        } catch (e: ExportCancelled) {
            throw ExportException(context.getString(R.string.export_cancelled))
        }
        set(State.Verified(report, documentName(context, document)))
    }

    fun documentName(context: Context, document: Uri): String = runCatching {
        context.contentResolver.query(document, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: document.lastPathSegment ?: document.toString()

    /** Re-reads the destination and compares it with checksums.tsv. Background thread. */
    suspend fun verify(context: Context, tree: Uri) {
        val sink = SafExportSink(context, tree)
        set(State.Working(Phase.VERIFYING))
        var last = 0L
        val report = try {
            ExportVerifier.verify(sink, onProgress = { progress ->
                val now = System.currentTimeMillis()
                if (now - last >= 250) {
                    last = now
                    set(State.Working(Phase.VERIFYING, progress))
                }
            }, isCancelled = { cancelled })
        } catch (e: ExportCancelled) {
            throw ExportException(context.getString(R.string.export_cancelled))
        }
        set(State.Verified(report, sink.displayName()))
    }
}
