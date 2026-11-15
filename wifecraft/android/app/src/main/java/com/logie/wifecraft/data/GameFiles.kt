package com.logie.wifecraft.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.logie.wifecraft.BuildConfig
import com.logie.wifecraft.creator.GameTables
import com.logie.wifecraft.creator.M2Files
import com.logie.wifecraft.creator.Prefetch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The game's files, fetched from wago.tools into the app's storage and kept; nothing of the game ships in the app. The
 * first launch gathers everything (the user: "I want it to download all the options in import instead of on demand"):
 * what every story needs (assets/files.json: ids and sizes, in packs: WoW's interface and fonts, Dalaran's buildings and
 * props for the places, the music and sounds), the game's tables the character creator reads ([GameTables], into
 * files/cache/tables, right after the interface), and every file the creator can draw for every race and sex, every
 * option and choice ([LOOKS]: creator/Prefetch's plan from those tables, kept in files/cache/looks-plan.txt; each model's
 * skin and the story's animations read from the model once it is here), before the music. Files go to files/game/<id>;
 * whatever is here is never fetched again, so a gathering stopped or killed carries on where it was. A file wago.tools
 * won't give (after five tries) is listed ([Progress.failed]) and the rest carry on; [start] again retries them.
 * [fetchNow] still fetches a look's file on demand (engine/Dresser) when it isn't here yet.
 */
class GameFiles(private val context: Context) {

    data class Pack(val name: String, val files: List<Pair<Int, Long>>) {
        val bytes: Long get() = files.sumOf { it.second }
    }

    data class Progress(
        val running: Boolean = false,
        /** Bytes on the phone, of [total] ([estimated]: the creator's files are counted at their measured average until all are here). */
        val done: Long = 0,
        val total: Long = 0,
        val estimated: Boolean = true,
        /** Files on the phone, of [filesTotal] ([filesExact] false: the models' own files aren't counted yet). */
        val files: Int = 0,
        val filesTotal: Int = 0,
        val filesExact: Boolean = false,
        /** The pack being fetched, and which are complete. */
        val current: String = "",
        val complete: Set<String> = emptySet(),
        /** Files that couldn't be fetched this time (the rest went on). */
        val failed: List<Int> = emptyList(),
        val error: String? = null,
        val waitingForWifi: Boolean = false,
        /** Every pack, the tables and the creator's files are here. */
        val allDone: Boolean = false,
    ) {
        val finished: Boolean get() = allDone && error == null && failed.isEmpty()
    }

    val dir = File(context.filesDir, "game").apply { mkdirs() }
    /** The game's tables (CSV), as creator/CsvTables reads them. */
    val tablesDir = File(context.filesDir, "cache/tables").apply { mkdirs() }
    private val planFile = File(context.filesDir, "cache/looks-plan.txt")
    private val looksDoneFile = File(context.filesDir, "cache/looks-complete")
    private val locks = ConcurrentHashMap<Int, Any>()
    val packs: List<Pack>
    private val source: String
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress
    // wago.tools answers each file in about a second whatever its size (measured 15 Nov 2026 from a desktop: 4 at once
    // fetched 4 files a second, 16 at once 15, 32 at once 25, with no refusals), so the download is bound by the wait
    // for each answer, not by the line: many at once.
    private val pool = Executors.newFixedThreadPool(PARALLEL)
    private val cancelled = AtomicBoolean(false)
    private var job: Thread? = null

    /**
     * Makes the creator's plan from the tables (MainActivity: Prefetch.plan on the catalog and the tables); null: the
     * creator's files aren't gathered.
     */
    @Volatile var planner: (() -> Prefetch.Plan)? = null

    /**
     * What is known of the creator's files: their count and bytes on the phone, how many there are in all, and how many
     * of the models (about [Prefetch.MODEL_BYTES] each, the rest about [Prefetch.AVERAGE_BYTES]) aren't here yet.
     */
    private class Looks(val have: Int, val haveBytes: Long, val count: Int, val exact: Boolean, val complete: Boolean, val modelsMissing: Int) {
        /** The bytes in all: exact once complete, else what's here and the rest at the measured averages (wago.tools gives no sizes ahead). */
        val bytes: Long get() = if (complete) haveBytes else {
            val rest = (count - have - modelsMissing).coerceAtLeast(0)
            haveBytes + modelsMissing * Prefetch.MODEL_BYTES + rest * Prefetch.AVERAGE_BYTES
        }

        fun got(bytes: Long, model: Boolean) = Looks(have + 1, haveBytes + bytes, count, exact, complete, modelsMissing - if (model) 1 else 0)
    }

    @Volatile private var looks = Looks(0, 0, Prefetch.FILES_ESTIMATE, false, false, Prefetch.MODELS_ESTIMATE)

    init {
        // files from before the build was pinned (or another build) go once, to be fetched again at GameTables.BUILD
        runCatching { GameTables.migrate(context.filesDir) }
        val json = JSONObject(context.assets.open("files.json").bufferedReader().use { it.readText() })
        source = json.getString("source")
        val arr = json.getJSONArray("packs")
        packs = List(arr.length()) { i ->
            val p = arr.getJSONObject(i)
            val files = p.getJSONArray("files")
            Pack(p.getString("name"), List(files.length()) { k ->
                val f = files.getJSONArray(k)
                f.getInt(0) to f.getLong(1)
            })
        }
        looksMarker()?.takeIf { it.first == tablesKey() }?.let { (_, bytes, count) -> looks = Looks(count, bytes, count, true, true, 0) }
        refresh()
        // the creator's files left half way: counted off the main thread (thousands of files)
        if (!looks.complete) Thread { countLooks(); refresh() }.apply { name = "game-files-count" }.start()
    }

    fun file(id: Int): File = File(dir, id.toString())
    fun has(id: Int): Boolean = file(id).let { it.isFile && it.length() > 0 }
    fun packComplete(name: String): Boolean = packs.firstOrNull { it.name == name }?.files?.all { has(it.first) } == true
    val tablesComplete: Boolean get() = GameTables.complete(tablesDir)
    /** Every file of the creator's plan for these tables is here. */
    val looksComplete: Boolean get() = looksMarker()?.first == tablesKey()
    val allComplete: Boolean get() = packs.all { p -> p.files.all { has(it.first) } } && tablesComplete && looksComplete
    /** The whole gathering's bytes (the creator's part estimated until it is all here). */
    val totalBytes: Long get() = packs.sumOf { it.bytes } + GameTables.APPROX_BYTES + looks.bytes
    private fun tableBytes(): Long = GameTables.NAMES.sumOf { File(tablesDir, "$it.csv").length() }.coerceAtMost(GameTables.APPROX_BYTES)

    /** Recounts what's on the phone. */
    fun refresh() {
        var done = 0L
        var files = 0
        val complete = mutableSetOf<String>()
        for (p in packs) {
            var all = true
            for ((id, size) in p.files) if (has(id)) { done += size; files++ } else all = false
            if (all) complete += p.name
        }
        done += tableBytes()
        files += GameTables.NAMES.count { GameTables.has(tablesDir, it) }
        if (tablesComplete) complete += TABLES
        val l = looks
        if (l.complete && looksComplete) complete += LOOKS
        done += l.haveBytes
        files += l.have
        val all = packs.all { it.name in complete } && TABLES in complete && LOOKS in complete
        // (the tables' size is an estimate: all here is all done)
        if (all) done = totalBytes
        _progress.value = _progress.value.copy(
            done = done.coerceAtMost(totalBytes), total = totalBytes, estimated = !l.complete,
            files = files, filesTotal = filesTotal(), filesExact = l.exact, complete = complete, allDone = all,
        )
    }

    private fun filesTotal(): Int = packs.sumOf { it.files.size } + GameTables.NAMES.size + looks.count

    fun onWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Fetches whatever is missing: the interface, the tables, Dalaran and its props, the creator's files, the music. */
    fun start(wifiOnly: Boolean) {
        if (job?.isAlive == true) return
        cancelled.set(false)
        if (wifiOnly && !onWifi()) {
            _progress.value = _progress.value.copy(running = false, waitingForWifi = true, error = null)
            return
        }
        _progress.value = _progress.value.copy(running = true, waitingForWifi = false, error = null, failed = emptyList())
        job = Thread {
            val failed = ConcurrentHashMap.newKeySet<Int>()
            var error: String? = null
            try {
                val done = AtomicLong(packs.sumOf { p -> p.files.filter { has(it.first) }.sumOf { it.second } } + tableBytes() + looks.haveBytes)
                val count = AtomicInteger(_progress.value.files)
                fun post() = _progress.value.let { p ->
                    _progress.value = p.copy(done = done.get().coerceAtMost(totalBytes), total = totalBytes, files = count.get(),
                        filesTotal = filesTotal(), filesExact = looks.exact, estimated = !looks.complete, failed = failed.sortedBy { it })
                }
                /** Fetches [ids] that aren't here, four at a time; each one's bytes from [size] (null: its length on the phone). */
                fun fetchAll(ids: Collection<Int>, size: (Int) -> Long?, got: (Int, Long) -> Unit = { _, _ -> }): Int {
                    val tasks = mutableListOf<Future<*>>()
                    for (id in ids) {
                        if (has(id)) continue
                        tasks += pool.submit {
                            if (cancelled.get()) return@submit
                            try {
                                fetch(id)
                            } catch (e: Exception) {
                                if (!cancelled.get()) failed += id
                            }
                            if (has(id)) {
                                val bytes = size(id) ?: file(id).length()
                                got(id, bytes)
                                done.addAndGet(bytes)
                                count.incrementAndGet()
                                failed -= id
                            }
                            post()
                        }
                    }
                    for (t in tasks) t.get()
                    return tasks.size
                }
                fun tables() {
                    if (cancelled.get() || tablesComplete) return
                    _progress.value = _progress.value.copy(current = TABLES)
                    GameTables.fetch(tablesDir, stop = { cancelled.get() }) { name, _, _ ->
                        done.addAndGet(File(tablesDir, "$name.csv").length())
                        count.incrementAndGet()
                        post()
                    }
                    if (tablesComplete) _progress.value = _progress.value.copy(complete = _progress.value.complete + TABLES)
                }
                fun looks() {
                    if (cancelled.get() || !tablesComplete || looksComplete) return
                    _progress.value = _progress.value.copy(current = LOOKS)
                    val plan = try {
                        plan() ?: return
                    } catch (e: Exception) {
                        error = "The creator's files couldn't be listed: ${e.message ?: e}"
                        return
                    }
                    val models = plan.models
                    fun recount() {
                        done.set(packs.sumOf { p -> p.files.filter { has(it.first) }.sumOf { it.second } } + tableBytes() + looks.haveBytes)
                        count.set(packs.sumOf { p -> p.files.count { has(it.first) } } + GameTables.NAMES.count { GameTables.has(tablesDir, it) } + looks.have)
                        post()
                    }
                    // the models first: what each needs beside itself is in it, and then the count is whole
                    countLooks(plan)
                    recount()
                    val gotLook = { id: Int, bytes: Long -> synchronized(this@GameFiles) { looks = looks.got(bytes, id in models) } }
                    fetchAll(models, { null }, gotLook)
                    if (cancelled.get()) return
                    var order = plan.order(::deepDeps)
                    val here = order.filter { has(it) }
                    looks = Looks(here.size, here.sumOf { file(it).length() }, order.size, models.all { has(it) }, false, models.count { !has(it) })
                    // the bytes and the count again, now that the order is whole
                    recount()
                    fetchAll(order, { null }, gotLook)
                    if (cancelled.get()) return
                    // the skeletons are here now: their own animation files and parents, a second round
                    order = plan.order(::deepDeps)
                    recount()
                    fetchAll(order.filter { !has(it) }, { null }, gotLook)
                    if (cancelled.get()) return
                    if (order.all { has(it) }) {
                        val bytes = order.sumOf { file(it).length() }
                        writeAtomic(looksDoneFile, "${tablesKey()} $bytes ${order.size}\n")
                        looks = Looks(order.size, bytes, order.size, true, true, 0)
                        _progress.value = _progress.value.copy(complete = _progress.value.complete + LOOKS)
                    } else countLooks(plan)
                }
                for (p in packs) {
                    if (cancelled.get()) break
                    // the creator's files before the music: the music is the one thing a story goes without
                    if (p.name == "sound") looks()
                    if (cancelled.get()) break
                    _progress.value = _progress.value.copy(current = p.name)
                    val sizes = p.files.toMap()
                    fetchAll(sizes.keys, { sizes[it] })
                    if (!cancelled.get() && p.files.all { has(it.first) }) _progress.value = _progress.value.copy(complete = _progress.value.complete + p.name)
                    // the creator's tables right after the interface: the editor needs them, the city can follow
                    if (p.name == "interface") tables()
                }
                tables()
                looks()
                refresh()
                _progress.value = _progress.value.copy(running = false, current = "", failed = failed.sortedBy { it }, error = error)
            } catch (e: Exception) {
                val cause = (e.cause ?: e)
                refresh()
                _progress.value = _progress.value.copy(running = false, current = "", failed = failed.sortedBy { it }, error = cause.message ?: cause.toString())
            }
        }.apply { name = "game-files"; start() }
    }

    fun pause() {
        cancelled.set(true)
        _progress.value = _progress.value.copy(running = false)
    }

    /**
     * Fetches one file now if it isn't here (a look's own files, as they are needed; any connection, and not stopped by
     * [pause]). Blocks; throws when wago.tools can't give it.
     */
    fun fetchNow(id: Int) {
        fetch(id, null)
    }

    // --- the creator's plan --------------------------------------------------------------------------------------

    /** What the tables on the phone are (each one's length): a new set of tables makes a new plan. */
    private fun tablesKey(): String = GameTables.NAMES.joinToString("-") { File(tablesDir, "$it.csv").length().toString(36) }

    /** (tables key, bytes, files) of the last complete gathering of the creator's files. */
    private fun looksMarker(): Triple<String, Long, Int>? = runCatching {
        val p = looksDoneFile.readText().trim().split(' ')
        Triple(p[0], p[1].toLong(), p[2].toInt())
    }.getOrNull()

    /** The plan kept for these tables, else a new one (kept). Null without a [planner]. */
    private fun plan(): Prefetch.Plan? {
        val key = tablesKey()
        runCatching { Prefetch.Plan.read(planFile.readText(), key) }.getOrNull()?.let { return it }
        val p = (planner ?: return null)()
        writeAtomic(planFile, p.write(key))
        return p
    }

    /** Counts the creator's files on the phone, by the kept plan (its models' own files counted once the models are here). */
    private fun countLooks(plan: Prefetch.Plan? = runCatching { Prefetch.Plan.read(planFile.readText(), tablesKey()) }.getOrNull()) {
        if (plan == null) return
        if (looksComplete) {
            looksMarker()?.let { (_, bytes, count) -> looks = Looks(count, bytes, count, true, true, 0) }
            return
        }
        val exact = plan.models.all { has(it) }
        val order = if (!exact) plan.files else plan.order(::deepDeps)
        var n = 0; var bytes = 0L
        for (id in order) file(id).length().takeIf { it > 0 }?.let { n++; bytes += it }
        looks = Looks(n, bytes, if (exact) order.size else maxOf(order.size, Prefetch.FILES_ESTIMATE), exact, false, plan.models.count { !has(it) })
    }

    /** What a model needs through its skeletons that are here (the model's own list, the skeletons' animations and parents). */
    private fun deepDeps(m: Int, body: Boolean): List<Int>? {
        if (!has(m)) return null
        val anims = if (body) Emotes.all else emptySet()
        return runCatching { M2Files.closure(m, anims, { if (has(it)) file(it).readBytes() else null }, { }).toList() }.getOrNull()
    }

    private fun writeAtomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val part = File(f.path + ".part")
        part.writeText(text)
        if (!part.renameTo(f)) { f.delete(); part.renameTo(f) }
    }

    // --- one file ---------------------------------------------------------------------------------------------------

    private fun fetch(id: Int, cancel: AtomicBoolean? = cancelled) {
        synchronized(locks.getOrPut(id) { Any() }) {
            if (!has(id)) download(id, cancel)
        }
    }

    private fun download(id: Int, cancel: AtomicBoolean?) {
        val target = file(id)
        val part = File(dir, "$id.part")
        var last: Exception? = null
        for (attempt in 0 until 5) {
            if (cancel?.get() == true) return
            try {
                val conn = URL(source.replace("<id>", id.toString())).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.setRequestProperty("User-Agent", "Wifecraft/${BuildConfig.VERSION_NAME} (+https://github.com/logie-github/YeaBwoi)")
                try {
                    if (conn.responseCode != 200) throw IOException("wago.tools answered ${conn.responseCode} for file $id")
                    // a file it doesn't have comes as 200 with a JSON error ({"error":"File not found"}), never as the file
                    if (conn.contentType?.startsWith("application/json") == true) {
                        val why = runCatching { JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("error") }.getOrNull()
                        throw NoSuchFile("wago.tools has no file $id" + (why?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""))
                    }
                    conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it, 64 * 1024) } }
                } finally {
                    conn.disconnect()
                }
                if (part.length() == 0L) throw IOException("File $id came empty")
                if (!part.renameTo(target)) throw IOException("Can't save file $id")
                return
            } catch (e: NoSuchFile) {
                throw e                                   // asking again won't make it
            } catch (e: Exception) {
                last = e
                if (cancel?.get() == true) return
                Thread.sleep(1000L shl attempt)
            }
        }
        throw last ?: IOException("Can't fetch file $id")
    }

    private class NoSuchFile(message: String) : IOException(message)

    companion object {
        /** Files fetched at once. */
        const val PARALLEL = 24

        /** The name the game's tables go by in [Progress.current] and [Progress.complete]. */
        const val TABLES = "tables"
        /** The creator's files: every race's and sex's every option and choice (creator/Prefetch). */
        const val LOOKS = "looks"
    }
}
