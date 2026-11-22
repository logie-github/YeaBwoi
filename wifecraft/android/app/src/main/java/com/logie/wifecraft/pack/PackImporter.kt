package com.logie.wifecraft.pack

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

enum class EntryStatus(val label: String) {
    IMPORTED("Imported"), ALREADY("Already imported"), SKIPPED("Skipped"), FAILED("Failed")
}

/** What a handler did with one file: [detail] is the line the result list shows ("imported 76 clips", "skipped: no skeleton..."). */
data class HandlerResult(val status: EntryStatus, val detail: String)

/** Imports one kind's files. Called once per entry, off the main thread; it must not run anything it finds in the file. */
fun interface PackHandler {
    fun handle(entry: PackEntry, file: File, content: PackContent?): HandlerResult
}

/** One line of the result list: the [entry], what it was routed as (the content decides, [note] says when it differs from the manifest). */
data class EntryResult(val entry: PackEntry, val routedAs: PackKind, val status: EntryStatus, val detail: String, val note: String = "")

class PackReport(
    val manifest: PackManifest?,
    val results: List<EntryResult>,
    /** The reason the whole pack was refused (nothing was imported), or null. */
    val problem: String?,
    /** Zip entries that were not unpacked, with why (scripts, files outside `pack/`, files the manifest does not list). */
    val ignored: List<Pair<String, String>>,
) {
    fun count(status: EntryStatus): Int = results.count { it.status == status }
    val ok: Boolean get() = problem == null && results.none { it.status == EntryStatus.FAILED }

    /** "9 imported, 1 already, 2 skipped, 0 failed". */
    fun summary(): String = problem ?: EntryStatus.values().joinToString(", ") { "${count(it)} ${it.label.lowercase()}" }
}

data class PackProgress(val phase: String, val done: Int, val total: Int, val current: String = "")

/**
 * Pack import: unpack the pack's zip safely ([SafeZip]) into a staging folder under [root], check it against its manifest
 * ([PackManifest]: every file listed, its size and sha-256), read each package's content ([PackClassifier]) and route each to its
 * [handlers] by what it holds, then report one line per entry. Never executes anything inside; `.ts4script` files are ignored.
 *
 * Resumable and safe: what is done is written to `state.json` after each entry (the entry's id and sha-256 under the pack's
 * name), so an import that was stopped (the app closed, the phone out of power) goes on from the next entry when the same pack
 * is picked again, and a finished entry is not done twice ([EntryStatus.ALREADY]); `force` does everything again. A bad entry
 * (damaged, missing, a handler that throws) fails alone and the rest carries on. The staging folder is emptied at the start and
 * the end; the object kinds' packages are kept in `store/<folder>/` for the stores that read them.
 */
class PackImporter(
    private val root: File,
    private val handlers: Map<PackKind, PackHandler>,
    private val limits: ZipLimits = ZipLimits(),
) {
    private val staging: File get() = File(root, "staging")
    private val stateFile: File get() = File(root, "state.json")

    /** Where the kept packages of [kind] are. */
    fun storeDir(kind: PackKind): File = File(File(root, "store"), kind.dir)

    fun import(open: () -> InputStream, force: Boolean = false, onProgress: (PackProgress) -> Unit = {}): PackReport {
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            onProgress(PackProgress("Unpacking", 0, 0))
            val zip = try {
                open().use { SafeZip.extract(it, staging, limits) }
            } catch (e: PackException) {
                return PackReport(null, emptyList(), e.message, emptyList())
            } catch (e: java.io.IOException) {
                return PackReport(null, emptyList(), "The pack can't be read: ${e.message?.take(100)}", emptyList())
            }
            val manifestFile = zip.files[PackManifest.MANIFEST_PATH]
                ?: return PackReport(null, emptyList(), "This zip is not a Wifecraft pack: it has no ${PackManifest.MANIFEST_PATH}.", zip.ignored)
            val manifest = try {
                if (manifestFile.size > PackManifest.MAX_MANIFEST_BYTES) throw PackException("The pack's manifest is too large to be one.")
                PackManifest.parse(manifestFile.file.readText())
            } catch (e: PackException) {
                return PackReport(null, emptyList(), e.message, zip.ignored)
            }
            val ignored = ArrayList(zip.ignored)
            val listed = manifest.entries.map { it.path }.toSet() + PackManifest.MANIFEST_PATH
            for (name in zip.files.keys) if (name !in listed) ignored += name to "not listed in the manifest"
            val state = State.read(stateFile)
            val done = if (force) HashMap() else state.done(manifest.name)
            // what each file holds, first: a layer is told from furniture by the stages of the other packages
            val contents = HashMap<String, PackContent?>()
            val problems = HashMap<String, String>()
            for ((i, e) in manifest.entries.withIndex()) {
                onProgress(PackProgress("Reading", i, manifest.entries.size, e.file))
                val f = zip.files[e.path]
                when {
                    f == null -> problems[e.id] = "the file is missing from the zip"
                    f.size != e.size -> problems[e.id] = "its size is ${f.size} bytes, the manifest says ${e.size}"
                    f.sha256 != e.sha256 -> problems[e.id] = "its checksum does not match the manifest (the file is damaged or changed)"
                    e.kind != PackKind.SCRIPT_INFO && e.kind != PackKind.UNUSED || e.path.endsWith(".package") ->
                        contents[e.id] = runCatching { PackClassifier.read(f.file) }.getOrElse { problems[e.id] = "not a readable Sims 4 package: ${it.message?.take(100)}"; null }
                }
            }
            val refs = contents.values.filterNotNull().flatMapTo(HashSet()) { it.propRefs }
            val results = ArrayList<EntryResult>()
            for ((i, e) in manifest.entries.withIndex()) {
                onProgress(PackProgress("Importing", i, manifest.entries.size, e.file))
                val file = zip.files[e.path]?.file
                val content = contents[e.id]
                val routed = if (content != null && e.kind in PACKAGE_KINDS) PackClassifier.kindOf(content, refs) else e.kind
                val note = if (routed != e.kind) "the manifest says ${e.kind.key}, the file is ${routed.key}: imported as ${routed.key}" else ""
                val r = when {
                    problems[e.id] != null -> EntryResult(e, e.kind, EntryStatus.FAILED, problems[e.id]!!)
                    done[e.id] == e.sha256 -> EntryResult(e, routed, EntryStatus.ALREADY, "imported before: nothing to do", note)
                    routed == PackKind.UNUSED -> EntryResult(e, routed, EntryStatus.SKIPPED,
                        "kept in the pack, not used by the app" + (e.summary.optString("why").takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""), note)
                    else -> {
                        val h = handlers[routed]
                        if (h == null) EntryResult(e, routed, EntryStatus.SKIPPED, "this build has no importer for ${routed.label.lowercase()}", note)
                        else try {
                            if (routed != PackKind.ANIMATIONS && routed != PackKind.SCRIPT_INFO) keep(routed, file!!)
                            val hr = h.handle(e, if (routed == PackKind.ANIMATIONS || routed == PackKind.SCRIPT_INFO) file!! else File(storeDir(routed), file!!.name), content)
                            EntryResult(e, routed, hr.status, hr.detail, note)
                        } catch (x: InterruptedException) {
                            throw x
                        } catch (x: kotlinx.coroutines.CancellationException) {
                            throw x
                        } catch (x: Exception) {
                            EntryResult(e, routed, EntryStatus.FAILED, x.message?.take(200) ?: x.javaClass.simpleName, note)
                        }
                    }
                }
                results += r
                if (r.status == EntryStatus.IMPORTED || r.status == EntryStatus.ALREADY || r.status == EntryStatus.SKIPPED && routed == PackKind.UNUSED) {
                    done[e.id] = e.sha256
                    state.put(manifest.name, done)
                    state.write(stateFile)
                }
            }
            onProgress(PackProgress("Done", manifest.entries.size, manifest.entries.size))
            return PackReport(manifest, results, null, ignored)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Moves a package into the store of its kind (replacing an older copy of the same name) so the stores can read it later. */
    private fun keep(kind: PackKind, file: File) {
        val dir = storeDir(kind)
        dir.mkdirs()
        val target = File(dir, file.name)
        val tmp = File(dir, file.name + ".tmp")
        file.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    private class State(private val o: JSONObject) {
        fun done(pack: String): HashMap<String, String> {
            val m = HashMap<String, String>()
            o.optJSONObject(pack)?.let { d -> for (k in d.keys()) m[k] = d.optString(k) }
            return m
        }

        fun put(pack: String, done: Map<String, String>) {
            val d = JSONObject()
            for ((k, v) in done) d.put(k, v)
            o.put(pack, d)
        }

        fun write(file: File) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }

        companion object {
            fun read(file: File): State = State(runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject()))
        }
    }

    companion object {
        /** The kinds whose files are Sims packages the classifier reads. */
        val PACKAGE_KINDS = setOf(PackKind.ANIMATIONS, PackKind.BODY_LAYER, PackKind.PROPS, PackKind.FURNITURE)

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
