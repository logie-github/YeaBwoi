// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import com.logie.warcraft.Imports
import com.logie.warcraft.felsong.FelsongJobs
import com.logie.warcraft.turtle.TurtleJobs
import com.logie.warcraft.turtle.core.SafeFs
import com.logie.warcraft.tww.core.TwwArchive
import com.logie.warcraft.tww.core.TwwDownload
import com.logie.warcraft.tww.core.TwwInstall
import com.logie.warcraft.tww.core.TwwPlacement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipException

/** Real installation jobs, run by the existing app's foreground-service pattern. */
open class LocalInstallJobs(private val realm: LocalRealmInstallation) {
    enum class Kind { INSTALL, DOWNLOAD_CLIENT, DOWNLOAD_SERVER, DOWNLOAD_MAPS, IMPORT_CLIENT, IMPORT_SERVER, IMPORT_MAPS, VERIFY, CLEAN_DOWNLOADS }

    sealed interface State {
        data object Idle : State
        data class Working(val kind: Kind, val progress: Float? = null, val detail: String = "Checking installation") : State
        data class Done(val kind: Kind, val message: String) : State
        data class Failed(val kind: Kind, val message: String) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState
    val busy: Boolean get() = state.value is State.Working
    @Volatile private var cancelled = false
    @Volatile private var installing = false
    private var currentComponent: Kind? = null
    @Volatile private var activeDownload: TwwDownload? = null
    private val RECEIPT = ".tww-integrity.tsv"
    private val HEX = Regex("[0-9a-f]{64}")

    init { synchronized(gate) { instances += this } }

    internal fun begin(kind: Kind): Boolean = synchronized(gate) {
        if (busy) return@synchronized false
        if (instances.any { it !== this && it.busy } || Imports.state.value is Imports.State.Working || FelsongJobs.busy || TurtleJobs.busy) {
            mutableState.value = State.Failed(kind, "Another installation is running. Finish or cancel it before starting this installation.")
            return@synchronized false
        }
        cancelled = false
        mutableState.value = State.Working(kind)
        true
    }

    fun cancel() { cancelled = true; activeDownload?.cancel() }
    fun clearResult() { if (!busy) mutableState.value = State.Idle }

    internal fun failed(kind: Kind, message: String) { mutableState.value = State.Failed(kind, message) }

    private fun checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) throw IOException("Installation cancelled. Partial downloads are kept for Resume; installed files are unchanged.")
    }

    private fun working(kind: Kind, detail: String, progress: Float? = null) {
        val component = when (kind) { Kind.DOWNLOAD_CLIENT -> "Game Client"; Kind.DOWNLOAD_SERVER -> "Server"; Kind.DOWNLOAD_MAPS -> "Map Files"; else -> null }
        mutableState.value = State.Working(if (installing) Kind.INSTALL else kind, progress?.coerceIn(0f, 1f), if (component == null) detail else "$component · $detail")
    }

    internal fun run(context: Context, kind: Kind, uri: Uri?, folder: Boolean) {
        installing = kind == Kind.INSTALL
        currentComponent = null
        try {
            val layout = layout(context)
            val fs = fs(context)
            fs.mkdirs(layout.root)
            if (kind != Kind.CLEAN_DOWNLOADS) {
                runBlocking { realm.server.stopAndWait(context) }
                TwwPlacement.recover(layout.root)
            } else if (TwwPlacement.pending(layout.root)) {
                throw IOException("An interrupted installation needs recovery. Run Verify Installation before clearing temporary files.")
            }
            checkCancelled()
            if (kind == Kind.VERIFY) {
                val issues = mutableListOf<String>()
                listOf(layout.client, layout.server, layout.maps).filter { it.isDirectory }.forEach { verifyReceipt(it, kind, issues) }
                val report = realm.report(context)
                report.detected?.dataDirectory?.let { data ->
                    val master = File(layout.maps, "mmaps")
                    if (master.isDirectory) verifyDeployedMaps(master, File(data, "mmaps"), kind, issues)
                }
                issues += report.blockers
                val parts = listOf("Game Client" to report.client, "Server Core" to report.core, "Server Database" to report.database, "Map Files" to report.maps)
                val summary = parts.joinToString("\n") { (name, component) ->
                    "$name: ${component.status.name.lowercase(Locale.ROOT)}" + component.missing.takeIf { it.isNotEmpty() }?.joinToString(prefix = " — missing ").orEmpty()
                }
                mutableState.value = State.Done(kind, summary + if (issues.isEmpty()) "\nVerification complete." else issues.distinct().joinToString("\n", prefix = "\n"))
                return
            }
            if (kind == Kind.CLEAN_DOWNLOADS) {
                fs.delete(layout.downloads)
                fs.delete(layout.staging)
                mutableState.value = State.Done(kind, "Temporary downloads and unfinished extraction files removed. Installed files are unchanged.")
                return
            }

            if (kind == Kind.INSTALL) {
                val before = realm.report(context)
                if (before.client.status != TwwInstall.Status.INSTALLED) {
                    // Report a restricted/quota-limited repack before spending hours on
                    // the client. A complete, verified server archive is usable offline.
                    if (before.detected == null) realm.sources[Kind.DOWNLOAD_SERVER]?.let { source ->
                        currentComponent = Kind.DOWNLOAD_SERVER
                        working(Kind.DOWNLOAD_SERVER, "Checking download availability")
                        val download = TwwDownload(source.url, layout.downloads, "server", source.size, source.sha256)
                        activeDownload = download
                        try { download.checkAvailable { cancelled } }
                        finally { activeDownload = null }
                    }
                    installComponent(context, Kind.DOWNLOAD_CLIENT, null, false)
                }
                checkCancelled()
                val current = realm.report(context)
                if (current.detected == null) {
                    installComponent(context, Kind.DOWNLOAD_SERVER, null, false)
                } else if (current.database.status != TwwInstall.Status.INSTALLED || current.core.missing.isNotEmpty()) {
                    throw IOException("The existing server needs repair. Open Game Files to review missing files. Your database has been preserved; reinstall the server only after backing it up.")
                }
                checkCancelled()
                if (realm.report(context).maps.status != TwwInstall.Status.INSTALLED) {
                    installComponent(context, Kind.DOWNLOAD_MAPS, null, false)
                }
                checkCancelled()
                currentComponent = null
                working(kind, "Configuring local connection")
                realm.configureServer(context)
                realm.configureClient(context)
                val report = realm.report(context)
                if (!report.canStartServer) throw IOException((report.blockers + report.client.missing + report.core.missing + report.database.missing + report.maps.missing).distinct().joinToString("\n").ifBlank { "Installation needs attention. Open Game Files to review it." })
                mutableState.value = State.Done(kind, "Installation complete. Start the local server to check its startup and launch the game.")
            } else {
                installComponent(context, kind, uri, folder)
            }
        } catch (e: Exception) {
            mutableState.value = State.Failed(kind, if (cancelled) "Installation cancelled. Partial downloads are kept for Resume; the previous installation is preserved."
                else {
                    val component = when (currentComponent) {
                        Kind.DOWNLOAD_CLIENT, Kind.IMPORT_CLIENT -> "Game Client"
                        Kind.DOWNLOAD_SERVER, Kind.IMPORT_SERVER -> "Server Core / Database"
                        Kind.DOWNLOAD_MAPS, Kind.IMPORT_MAPS -> "Map Files"
                        else -> null
                    }
                    val detail = e.message ?: e.javaClass.simpleName
                    if (component == null) detail else "$component: $detail\nCompleted installation steps are kept. Retry continues with the missing components."
                })
        } finally {
            installing = false
            currentComponent = null
            activeDownload = null
            runCatching {
                val layout = layout(context)
                // A transaction that could not roll back keeps its sources and backups for
                // recovery on the next attempt; never delete the only surviving installation.
                if (!TwwPlacement.pending(layout.root)) fs(context).delete(layout.staging)
            }
        }
    }

    private fun installComponent(context: Context, kind: Kind, uri: Uri?, folder: Boolean) {
        currentComponent = kind
        val layout = layout(context)
        val fs = fs(context)
        // Only our staging area is removed after interrupted jobs. Downloads stay resumable.
        fs.delete(layout.staging)
        fs.mkdirs(layout.staging)
        val work = File(layout.staging, "install")
        val importedFrom = when (kind) {
            Kind.DOWNLOAD_CLIENT, Kind.DOWNLOAD_SERVER, Kind.DOWNLOAD_MAPS -> {
                val source = realm.sources[kind] ?: throw IOException("Map files are included in this server repack; install or repair the server instead.")
                val url = source.url
                val key = when (kind) { Kind.DOWNLOAD_CLIENT -> "client"; Kind.DOWNLOAD_SERVER -> "server"; else -> "mmaps" }
                val download = TwwDownload(url, layout.downloads, key,
                    expectedSize = source.size, expectedSha256 = source.sha256, parallelConnections = source.connections)
                activeDownload = download
                val archive = try {
                    download.run(cancelled = { cancelled }) { p ->
                        val total = p.total
                        val amount = size(context, p.bytes) + if (total != null && total > 0) " / ${size(context, total)}" else ""
                        val speed = if (p.bytesPerSecond > 0) " · ${size(context, p.bytesPerSecond.toLong())}/s" else ""
                        val parallel = if (p.connections > 1) " · ${p.connections} connections" else ""
                        val eta = p.etaSeconds?.let { " · ${it / 60}m ${it % 60}s remaining" }.orEmpty()
                        working(kind, (if (p.reused) "Reusing validated download · " else "Downloading · ") + amount + speed + parallel + eta,
                            total?.takeIf { it > 0 }?.let { p.bytes.toFloat() / it })
                    }
                } finally { activeDownload = null }
                checkCancelled()
                working(kind, "Extracting ${archive.name}")
                try {
                    TwwArchive.extract(archive, work, { cancelled }, nativeExtractor = File(context.applicationInfo.nativeLibraryDir, "libtww7zip.so")) { bytes, name ->
                        checkSpace(work, 0)
                        working(kind, if (bytes > 0) "Extracting ${size(context, bytes)} · $name" else "Extracting archive…")
                    }
                } catch (e: TwwArchive.ArchiveException) {
                    if (!cancelled && corruptArchive(e)) {
                        // A computed transport hash can faithfully describe a truncated
                        // unknown-length response. Do not reuse that known-bad cache forever.
                        listOf("$key.archive", "$key.part", "$key.properties").forEach { fs.delete(File(layout.downloads, it)) }
                        throw IOException("The downloaded archive is incomplete or damaged. Its cached copy was removed; Retry will download a fresh copy. ${e.message}", e)
                    }
                    throw e
                }
                url
            }
            else -> {
                val selected = uri ?: throw IOException("No source folder or archive was selected.")
                if (folder) {
                    fs.mkdirs(work)
                    copyFolder(context, selected, work, kind)
                }
                else {
                    fs.mkdirs(layout.downloads)
                    val source = fs.check(File(layout.downloads, "picked.archive"))
                    copyPickedArchive(context, selected, source, kind)
                    working(kind, "Extracting imported archive")
                    TwwArchive.extract(source, work, { cancelled }, nativeExtractor = File(context.applicationInfo.nativeLibraryDir, "libtww7zip.so")) { bytes, name -> working(kind, if (bytes > 0) "Extracting ${size(context, bytes)} · $name" else "Extracting archive…") }
                    fs.delete(source)
                }
                "User-selected ${if (folder) "folder" else "archive"}"
            }
        }
        checkCancelled()

        val component = when (kind) {
            Kind.DOWNLOAD_CLIENT, Kind.IMPORT_CLIENT -> "client"
            Kind.DOWNLOAD_SERVER, Kind.IMPORT_SERVER -> "server"
            Kind.DOWNLOAD_MAPS, Kind.IMPORT_MAPS -> "maps"
            else -> throw IOException("Unsupported installation job")
        }
        if (component == "server") {
            // Build proof is generated only by this installation's live database check.
            // A repack or picked folder cannot attest to its own compatibility.
            work.walkTopDown().filter { it.isFile && it.name.equals(realm.spec.proofFile, ignoreCase = true) }
                .toList().forEach { fs.delete(it) }
        }
        val candidate = when (component) {
            "client" -> realm.findClientRoot(work) ?: throw IOException("No matching game client was found. Import the folder containing the game executable and its Data folder.")
            "server" -> realm.findServerRoot(work) ?: throw IOException("No server executable and configuration were found in this source.")
            else -> normalizeMaps(work, fs)
        }
        working(kind, "Validating $component files")
        val report = realm.inspect(if (component == "client") candidate else layout.client,
            if (component == "server") candidate else layout.server,
            if (component == "maps") candidate else layout.maps)
        when (component) {
            "client" -> if (report.client.status != TwwInstall.Status.INSTALLED) {
                throw IOException((report.client.details + report.client.missing).joinToString("\n").ifBlank { "Client validation failed: expected ${realm.spec.version} build ${realm.spec.build}." })
            }
            "server" -> if (report.detected == null || report.core.status == TwwInstall.Status.MISSING) {
                throw IOException((report.core.details + report.core.missing).joinToString("\n").ifBlank { "No recognizable local server programs were found." })
            }
            "maps" -> if (!candidate.walkTopDown().any { it.isFile && it.length() > 0 && (it.extension.equals("mmap", true) || it.extension.equals("mmtile", true)) }) {
                throw IOException("No non-empty MMAPS .mmap or .mmtile files were found. Import the additional map archive or its mmaps folder.")
            }
        }
        // Additional MMAPS become usable at this explicit installation action. Starting
        // the server never silently changes its map files or its executables.
        val changes = mutableListOf<Pair<File, File>>()
        if (component == "server" && File(layout.maps, "mmaps").isDirectory) {
            report.detected?.dataDirectory?.let { data ->
                val serverGuard = SafeFs(candidate)
                serverGuard.check(data, allowScope = true)
                val destination = File(data, "mmaps")
                serverGuard.delete(destination)
                copyLocalTree(File(layout.maps, "mmaps"), destination, kind)
            }
        }
        writeReceipt(candidate, component, kind, importedFrom)
        checkCancelled()
        val target = when (component) { "client" -> layout.client; "server" -> layout.server; else -> layout.maps }
        // If an identical complete component is already installed, preserve its files in place.
        val same = File(target, RECEIPT).isFile && File(target, RECEIPT).readText() == File(candidate, RECEIPT).readText() && receiptMatches(target)
        if (!same) changes += candidate to target
        if (component == "maps") {
            val serverData = report.detected?.dataDirectory
            if (serverData != null) {
                SafeFs(layout.server).check(serverData, allowScope = true)
                val deployed = File(layout.staging, "maps-deployment")
                copyLocalTree(File(candidate, "mmaps"), deployed, kind)
                changes += deployed to File(serverData, "mmaps")
            }
        }
        checkCancelled()
        if (changes.isNotEmpty()) TwwPlacement.replace(layout.root, changes)
        val after = realm.report(context)
        val componentStatus = when (component) { "client" -> after.client; "server" -> after.core; else -> after.maps }
        val notes = (componentStatus.missing + componentStatus.details + after.blockers).distinct()
        if (!installing) mutableState.value = State.Done(kind, "${if (same) "Reused existing" else "Imported and validated"} $component files.\n" +
            notes.joinToString("\n").ifBlank { "Verify Installation shows the remaining setup requirements." })
    }


    private fun layout(context: Context) = LocalInstallation(realm.root(context))
    private fun fs(context: Context) = SafeFs(realm.root(context))

    companion object {
        private val gate = Any()
        private val instances = mutableListOf<LocalInstallJobs>()
        val anyBusy: Boolean get() = synchronized(gate) { instances.any { it.busy } }
    }

    private data class Picked(val uri: Uri, val path: String, val size: Long)

    private fun copyFolder(context: Context, tree: Uri, target: File, kind: Kind) {
        val picked = mutableListOf<Picked>()
        val visited = mutableSetOf<String>()
        val paths = mutableSetOf<String>()
        fun scan(id: String, prefix: String, depth: Int) {
            checkCancelled()
            if (depth > 64 || !visited.add(id)) throw IOException("The selected folder contains a cycle or an excessively nested directory.")
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
            val cursor = context.contentResolver.query(children, columns, null, null, null)
                ?: throw IOException("Cannot read selected folder. Its permission may have expired.")
            cursor.use {
                while (it.moveToNext()) {
                    checkCancelled()
                    val name = it.getString(1) ?: throw IOException("A source file has no name.")
                    if (name.isBlank() || name == "." || name == ".." || name.last() in listOf(' ', '.') ||
                        name.any { ch -> ch < ' ' || ch in "/\\:<>\"|?*" } ||
                        name.substringBefore('.').uppercase(Locale.ROOT) in WINDOWS_DEVICES) throw IOException("Unsafe source file name: $name")
                    val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (!paths.add(relative.lowercase(Locale.ROOT))) throw IOException("Conflicting Windows file paths: $relative")
                    val childId = it.getString(0) ?: throw IOException("A source file has no document ID.")
                    if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) scan(childId, relative, depth + 1)
                    else {
                        picked += Picked(DocumentsContract.buildDocumentUriUsingTree(tree, childId), relative, if (it.isNull(3)) -1L else it.getLong(3))
                        if (picked.size > 2_000_000) throw IOException("Too many files in the selected folder.")
                    }
                }
            }
        }
        scan(DocumentsContract.getTreeDocumentId(tree), "", 0)
        val total = picked.sumOf { it.size.coerceAtLeast(0) }
        val known = picked.all { it.size >= 0 }
        checkSpace(target, total)
        var done = 0L
        val guard = SafeFs(target)
        for (source in picked) {
            checkCancelled()
            val file = guard.check(File(target, source.path))
            guard.mkdirs(file.parentFile!!)
            val copied = context.contentResolver.openInputStream(source.uri)?.use { input ->
                file.outputStream().use { output -> copy(input, output) { bytes ->
                    done += bytes
                    working(kind, "Importing ${source.path} · ${size(context, done)}", if (known && total > 0) done.toFloat() / total else null)
                } }
            } ?: throw IOException("Cannot read ${source.path}. Download cloud files to local storage first.")
            if (source.size >= 0 && copied != source.size) throw IOException("Incomplete source file: ${source.path} ($copied / ${source.size} bytes).")
        }
    }

    private fun copyPickedArchive(context: Context, uri: Uri, target: File, kind: Kind) {
        val total = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
        checkSpace(target.parentFile!!, total.coerceAtLeast(0))
        var done = 0L
        val count = context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { output ->
            copy(input, output) { bytes ->
                done += bytes
                working(kind, "Reading archive · ${size(context, done)}", if (total > 0) done.toFloat() / total else null)
            }
        } } ?: throw IOException("Cannot read the selected archive.")
        if (total >= 0 && count != total) throw IOException("Incomplete archive: $count / $total bytes.")
    }

    private fun normalizeMaps(work: File, fs: SafeFs): File {
        val existing = work.walkTopDown().maxDepth(8).filter { it.isDirectory && it.name.equals("mmaps", true) }.toList()
        if (existing.size == 1) {
            val found = existing.single()
            if (found.parentFile == work && found.name == "mmaps") return work
            val normalized = File(work.parentFile, "maps-normalized")
            fs.mkdirs(normalized)
            fs.move(found, File(normalized, "mmaps"))
            return normalized
        }
        if (existing.size > 1) throw IOException("Several mmaps folders were found. Select the intended map folder directly.")
        // A folder picker rooted at mmaps returns its contents without the folder's own name.
        if (work.listFiles()?.any { it.isFile && (it.extension.equals("mmap", true) || it.extension.equals("mmtile", true)) } == true) {
            val normalized = File(work.parentFile, "maps-normalized")
            fs.mkdirs(normalized)
            fs.move(work, File(normalized, "mmaps"))
            return normalized
        }
        return work
    }

    private fun copyLocalTree(source: File, destination: File, kind: Kind) {
        if (!source.isDirectory) throw IOException("The additional mmaps folder is missing.")
        val guard = SafeFs(destination)
        guard.mkdirs(destination)
        val files = source.walkTopDown().onEnter {
            if (Files.isSymbolicLink(it.toPath())) throw IOException("Symbolic links are not allowed in map imports.")
            true
        }.filter { it.isFile }.toList()
        checkSpace(destination, files.sumOf { it.length() })
        files.forEachIndexed { index, file ->
            checkCancelled()
            if (Files.isSymbolicLink(file.toPath())) throw IOException("Symbolic links are not allowed in map imports.")
            val relative = file.relativeTo(source).invariantSeparatorsPath
            val target = guard.check(File(destination, relative))
            guard.mkdirs(target.parentFile!!)
            working(kind, "Installing additional map files · ${index + 1} / ${files.size} · $relative")
            file.inputStream().use { input -> target.outputStream().use { output -> copy(input, output) { } } }
            if (file.length() != target.length()) throw IOException("Incomplete map copy: $relative")
        }
    }

    private fun copy(input: InputStream, output: java.io.OutputStream, onBytes: (Int) -> Unit): Long {
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            total += read
            onBytes(read)
        }
        return total
    }

    private fun immutable(path: String, component: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        if (lower == RECEIPT) return false
        if (component == "maps") return true
        if (component == "client") {
            // CASC indexes and chunks grow during streaming; the pinned archive validates the
            // initial transfer, while executable/bootstrap receipts remain stable during play.
            if (lower.startsWith("data/data/") || lower.contains("/data/data/")) return false
            return lower.split('/').dropLast(1).none { it in setOf("wtf", "cache", "logs", "screenshots") }
        }
        // Additional MMAPS are replaced by their own explicit installer. Their master receipt
        // and deployed copy are verified together, independently from the original repack.
        if (lower.split('/').dropLast(1).any { it == "mmaps" }) return false
        return lower.substringAfterLast('.') in setOf("exe", "dll", "sql", "mmap", "mmtile", "vmap", "vmtree", "vmtile", "map", "db2", "dbc")
    }

    private fun verifyDeployedMaps(master: File, installed: File, kind: Kind, issues: MutableList<String>) {
        if (Files.isSymbolicLink(master.toPath()) || Files.isSymbolicLink(installed.toPath())) {
            issues += "The MMAPS installation contains a symbolic link. Reimport the map files into managed storage."
            return
        }
        val guard = SafeFs(installed)
        master.walkTopDown().onEnter {
            if (Files.isSymbolicLink(it.toPath())) throw IOException("Map integrity directory is a symbolic link.")
            true
        }.filter { it.isFile }.forEach { source ->
            checkCancelled()
            if (Files.isSymbolicLink(source.toPath())) throw IOException("Map integrity source is a symbolic link.")
            val relative = source.relativeTo(master).invariantSeparatorsPath
            val target = guard.check(File(installed, relative))
            working(kind, "Verifying deployed MMAPS · $relative")
            if (Files.isSymbolicLink(target.toPath()) || !target.isFile || source.length() != target.length() || sha256(source) != sha256(target)) {
                issues += "Server MMAPS/$relative: missing or different from the imported map files. Install the additional map files again to deploy them."
            }
        }
    }

    private fun writeReceipt(folder: File, component: String, kind: Kind, source: String) {
        val files = folder.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && immutable(it.relativeTo(folder).invariantSeparatorsPath, component) }.sortedBy { it.relativeTo(folder).invariantSeparatorsPath }.toList()
        val receipt = StringBuilder("# ${realm.receiptHeader}\n# Source: $source\n")
        files.forEachIndexed { index, file ->
            checkCancelled()
            val relative = file.relativeTo(folder).invariantSeparatorsPath
            if (relative.any { it == '\t' || it == '\n' || it == '\r' }) throw IOException("File name cannot be recorded safely: $relative")
            working(kind, "Checking ${index + 1} / ${files.size} · $relative", if (files.isNotEmpty()) index.toFloat() / files.size else null)
            receipt.append(relative).append('\t').append(file.length()).append('\t').append(sha256(file)).append('\n')
        }
        SafeFs(folder).writeAtomically(File(folder, RECEIPT), receipt.toString())
    }

    private fun receiptMatches(folder: File): Boolean {
        val issues = mutableListOf<String>()
        verifyReceipt(folder, null, issues)
        return issues.isEmpty()
    }

    private fun verifyReceipt(folder: File, kind: Kind?, issues: MutableList<String>) {
        val receipt = File(folder, RECEIPT)
        if (!receipt.isFile) { issues += "${folder.name}: no import integrity receipt; import these files again to record their checksums."; return }
        val guard = SafeFs(folder)
        receipt.useLines { lines -> lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
            checkCancelled()
            val parts = line.split('\t')
            if (parts.size != 3 || !HEX.matches(parts[2]) || parts[1].toLongOrNull() == null) throw IOException("Invalid integrity receipt in ${folder.name}.")
            val path = parts[0]
            if (path.startsWith('/') || path.split('/').any { it == ".." || it == "." } || path.contains('\\')) throw IOException("Unsafe integrity receipt path.")
            val file = guard.check(File(folder, path))
            kind?.let { working(it, "Verifying ${folder.name}/$path") }
            if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() != parts[1].toLong() || sha256(file) != parts[2]) issues += "${folder.name}/$path: missing, incomplete or modified since import."
        } }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                checkCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checkSpace(where: File, needed: Long) {
        val free = where.usableSpace
        if (free < needed + 256L * 1024 * 1024) throw IOException("Insufficient installation space: need at least $needed bytes plus 256 MiB free; $free bytes available.")
    }

    private fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    private fun corruptArchive(error: Throwable): Boolean {
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        val message = causes.joinToString(" ") { it.message.orEmpty() }.lowercase(Locale.ROOT)
        if (listOf("unsupported", "encrypted", "password", "cancelled", "not enough free space", "insufficient").any { it in message }) return false
        return causes.any { it is EOFException || it is ZipException } ||
            listOf("checksum", "incomplete archive", "truncated", "unexpected end", "premature end", "not a zip archive", "invalid signature", "bad signature", "expanded beyond its declared size").any { it in message }
    }

    private val WINDOWS_DEVICES = setOf("CON", "PRN", "AUX", "NUL", "CLOCK$") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
}
