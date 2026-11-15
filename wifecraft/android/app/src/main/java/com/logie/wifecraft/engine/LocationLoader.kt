package com.logie.wifecraft.engine

import android.content.Context
import com.logie.wifecraft.data.GameFiles
import com.logie.wifecraft.location.LocationInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Loads a location of assets/locations.json onto the 3D stage: its files (the entry's `files` packs: the building, its
 * props, the yard, the ground, the sound) fetched from wago.tools through [GameFiles] with progress, then
 * SceneView.loadLocation. Files that can't be fetched are said in [progress]'s error (the location is still drawn without
 * them); the renderer's own refusal too. One location at a time: a newer [load] replaces one still fetching. Main thread
 * for [load]; the work is off it.
 */
class LocationLoader(context: Context, private val files: GameFiles, private val scope: CoroutineScope) {
    data class Progress(
        val id: String = "",
        val done: Int = 0,
        val total: Int = 0,
        val bytesDone: Long = 0,
        val bytesTotal: Long = 0,
        /** On the stage. */
        val ready: Boolean = false,
        val error: String? = null,
    )

    /** assets/locations.json's text, as the renderer reads it. */
    val json: String = context.assets.open(ASSET).bufferedReader().use { it.readText() }
    /** Its locations, in the file's order. */
    val locations: List<LocationInfo> = LocationInfo.parseAll(json)

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress
    private var job: Job? = null

    fun location(id: String): LocationInfo? = locations.firstOrNull { it.id == id }

    /** Whether every file of [id] is downloaded. */
    fun ready(id: String, sound: Boolean = true): Boolean = location(id)?.files(sound)?.all { files.has(it.first) } == true

    /**
     * Fetches location [id]'s files ([sound] false: without its music and ambience) and puts it on [scene] with [view]'s
     * light and fog. [onDone] (main thread): whether it is on the stage.
     */
    fun load(scene: SceneView, id: String, view: String = "", sound: Boolean = true, onDone: (Boolean) -> Unit = {}) {
        job?.cancel()
        val loc = location(id)
        if (loc == null) {
            _progress.value = Progress(id, error = "There is no location \"$id\".")
            onDone(false)
            return
        }
        job = scope.launch {
            val wanted = loc.files(sound)
            val missing = wanted.filter { !files.has(it.first) }
            val bytesTotal = missing.sumOf { it.second }
            _progress.value = Progress(id, 0, missing.size, 0, bytesTotal)
            val failed = withContext(Dispatchers.IO) { fetchAll(id, missing, bytesTotal) }
            val error = if (failed.isEmpty()) null else
                "${failed.size} of ${loc.name}'s files couldn't be fetched (${failed.take(5).joinToString { it.first.toString() }}" +
                    (if (failed.size > 5) ", ..." else "") + "): ${failed.first().second}"
            _progress.value = _progress.value.copy(error = error)
            scene.loadLocation(json, id, view) { ok, why ->
                _progress.value = _progress.value.copy(ready = ok, error = if (ok) error else "${loc.name} couldn't be drawn: $why")
                onDone(ok)
            }
        }
    }

    /** Fetches [missing] a few at a time (GameFiles' limiter holds wago.tools to the player's download speed); the failures. */
    private suspend fun fetchAll(id: String, missing: List<Pair<Int, Long>>, bytesTotal: Long): List<Pair<Int, String>> {
        val done = AtomicInteger(0)
        val bytes = AtomicLong(0)
        val gate = Semaphore(PARALLEL)
        val failed = java.util.Collections.synchronizedList(ArrayList<Pair<Int, String>>())
        withContext(Dispatchers.IO) {
            missing.map { (fid, size) ->
                async {
                    gate.withPermit {
                        try {
                            files.fetchNow(fid)
                        } catch (e: Exception) {
                            failed += fid to (e.message ?: e.toString())
                        }
                        val got = if (files.has(fid)) files.file(fid).length() else size
                        _progress.value = Progress(id, done.incrementAndGet(), missing.size, bytes.addAndGet(got), bytesTotal)
                    }
                }
            }.awaitAll()
        }
        return failed.toList()
    }

    companion object {
        const val ASSET = "locations.json"
        /** Files asked of wago.tools at once (a free community service: gently). */
        const val PARALLEL = 8
    }
}
