package com.logie.wifecraft.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.logie.wifecraft.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The game's files the demo needs (assets/files.json: ids and sizes, in packs), fetched once from
 * wago.tools into the app's storage (files/game/<id>) and kept. Nothing of the game ships in the
 * app itself.
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
    val allComplete: Boolean get() = packs.all { p -> p.files.all { has(it.first) } }
    val totalBytes: Long get() = packs.sumOf { it.bytes }

    /** Recounts what's on the phone. */
    fun refresh() {
        var done = 0L
        val complete = mutableSetOf<String>()
        for (p in packs) {
            var all = true
            for ((id, size) in p.files) if (has(id)) done += size else all = false
            if (all) complete += p.name
        }
        _progress.value = _progress.value.copy(done = done, total = totalBytes, complete = complete)
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
                val done = AtomicLong(packs.sumOf { p -> p.files.filter { has(it.first) }.sumOf { it.second } })
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
                }
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

    private fun fetch(id: Int) {
        val target = file(id)
        val part = File(dir, "$id.part")
        var last: Exception? = null
        for (attempt in 0 until 5) {
            if (cancelled.get()) return
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
}
