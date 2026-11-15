package com.logie.wifecraft.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.logie.wifecraft.BuildConfig
import com.logie.wifecraft.creator.GameTables
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
import java.util.concurrent.atomic.AtomicLong

/**
 * The game's files, fetched from wago.tools into the app's storage and kept; nothing of the game
 * ships in the app. The first launch gathers what every story needs (assets/files.json: ids and
 * sizes, in packs: WoW's interface and fonts, Dalaran's buildings and props for the places, the
 * music and sounds) and the game's tables the character creator reads ([GameTables], into
 * files/cache/tables, right after the interface). A look's own files (its model, skins, animations,
 * textures) are fetched when it is first shown ([fetchNow], engine/Dresser), into files/game/<id>.
 */
class GameFiles(private val context: Context) {

    data class Pack(val name: String, val files: List<Pair<Int, Long>>) {
        val bytes: Long get() = files.sumOf { it.second }
    }

    data class Progress(
        val running: Boolean = false,
        val done: Long = 0,
        val total: Long = 0,
        /** The pack being fetched, and which are complete. */
        val current: String = "",
        val complete: Set<String> = emptySet(),
        val error: String? = null,
        val waitingForWifi: Boolean = false,
    ) {
        val finished: Boolean get() = total > 0 && done >= total && error == null
    }

    val dir = File(context.filesDir, "game").apply { mkdirs() }
    /** The game's tables (CSV), as creator/CsvTables reads them. */
    val tablesDir = File(context.filesDir, "cache/tables").apply { mkdirs() }
    private val locks = ConcurrentHashMap<Int, Any>()
    val packs: List<Pack>
    private val source: String
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress
    private val pool = Executors.newFixedThreadPool(4)
    private val cancelled = AtomicBoolean(false)
    private var job: Thread? = null

    init {
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
        refresh()
    }

    fun file(id: Int): File = File(dir, id.toString())
    fun has(id: Int): Boolean = file(id).let { it.isFile && it.length() > 0 }
    fun packComplete(name: String): Boolean = packs.firstOrNull { it.name == name }?.files?.all { has(it.first) } == true
    val tablesComplete: Boolean get() = GameTables.complete(tablesDir)
    val allComplete: Boolean get() = packs.all { p -> p.files.all { has(it.first) } } && tablesComplete
    val totalBytes: Long get() = packs.sumOf { it.bytes } + GameTables.APPROX_BYTES
    private fun tableBytes(): Long = GameTables.NAMES.sumOf { File(tablesDir, "$it.csv").length() }.coerceAtMost(GameTables.APPROX_BYTES)

    /** Recounts what's on the phone. */
    fun refresh() {
        var done = 0L
        val complete = mutableSetOf<String>()
        for (p in packs) {
            var all = true
            for ((id, size) in p.files) if (has(id)) done += size else all = false
            if (all) complete += p.name
        }
        done += tableBytes()
        if (tablesComplete) complete += TABLES
        // (the tables' size is an estimate: all here is all done)
        if (allComplete) done = totalBytes
        _progress.value = _progress.value.copy(done = done.coerceAtMost(totalBytes), total = totalBytes, complete = complete)
    }

    fun onWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Fetches whatever is missing, pack by pack. */
    fun start(wifiOnly: Boolean) {
        if (job?.isAlive == true) return
        cancelled.set(false)
        if (wifiOnly && !onWifi()) {
            _progress.value = _progress.value.copy(running = false, waitingForWifi = true, error = null)
            return
        }
        _progress.value = _progress.value.copy(running = true, waitingForWifi = false, error = null)
        job = Thread {
            try {
                val done = AtomicLong(packs.sumOf { p -> p.files.filter { has(it.first) }.sumOf { it.second } } + tableBytes())
                fun tables() {
                    if (cancelled.get() || tablesComplete) return
                    _progress.value = _progress.value.copy(current = TABLES)
                    GameTables.fetch(tablesDir, stop = { cancelled.get() }) { name, _, _ ->
                        done.addAndGet(File(tablesDir, "$name.csv").length())
                        _progress.value = _progress.value.copy(done = done.get().coerceAtMost(totalBytes))
                    }
                    if (tablesComplete) _progress.value = _progress.value.copy(complete = _progress.value.complete + TABLES)
                }
                for (p in packs) {
                    if (cancelled.get()) break
                    _progress.value = _progress.value.copy(current = p.name)
                    val tasks = mutableListOf<Future<*>>()
                    for ((id, size) in p.files) {
                        if (has(id)) continue
                        tasks += pool.submit {
                            if (cancelled.get()) return@submit
                            fetch(id)
                            done.addAndGet(size)
                            _progress.value = _progress.value.copy(done = done.get())
                        }
                    }
                    for (t in tasks) t.get()
                    if (!cancelled.get()) _progress.value = _progress.value.copy(complete = _progress.value.complete + p.name)
                    // the creator's tables right after the interface: the editor needs them, the city can follow
                    if (p.name == "interface") tables()
                }
                tables()
                refresh()
                _progress.value = _progress.value.copy(running = false, current = "")
            } catch (e: Exception) {
                val cause = (e.cause ?: e)
                _progress.value = _progress.value.copy(running = false, error = cause.message ?: cause.toString())
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
                    conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it, 64 * 1024) } }
                } finally {
                    conn.disconnect()
                }
                if (part.length() == 0L) throw IOException("File $id came empty")
                if (!part.renameTo(target)) throw IOException("Can't save file $id")
                return
            } catch (e: Exception) {
                last = e
                Thread.sleep(1000L shl attempt)
            }
        }
        throw last ?: IOException("Can't fetch file $id")
    }

    companion object {
        /** The name the game's tables go by in [Progress.current] and [Progress.complete]. */
        const val TABLES = "tables"
    }
}
