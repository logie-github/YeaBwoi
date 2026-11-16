package com.logie.wifecraft.engine

import com.logie.wifecraft.creator.M2Files
import com.logie.wifecraft.data.GameFiles
import com.logie.wifecraft.location.WmoFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Puts the editor's props on the 3D stage: a static M2 (its skin, textures and skeleton: M2Files.closure) or a WMO (its
 * groups, textures and its default doodad set's models, and theirs: WmoFiles.deps) fetched from wago.tools first, then
 * SceneView.addProp. A file that can't be fetched or a model the renderer can't read is said in [loading]'s error, never
 * left as an empty spot. One placement at a time per key: a newer one for the same key replaces one still fetching. Main
 * thread for [place]; the work is off it.
 */
class PropLoader(private val files: GameFiles, private val scope: CoroutineScope) {
    /** Files fetched of those asked for since all were last in ([total] 0: nothing to fetch); the last failure. */
    data class Loading(val done: Int = 0, val total: Int = 0, val error: String? = null)

    private val _loading = MutableStateFlow(Loading())
    val loading: StateFlow<Loading> = _loading
    private val jobs = HashMap<String, Job>()
    @Volatile private var lastFailure: String? = null

    /** Places [model] (a file id) as the prop [key] at x, y, z turned [yaw] radians about z at [scale], once its files are here. */
    fun place(scene: SceneView, key: String, model: Int, x: Float, y: Float, z: Float, yaw: Float = 0f, scale: Float = 1f) {
        jobs.remove(key)?.cancel()
        jobs[key] = scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    prepare(model)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail("The prop (file $model): ${e.message ?: e}"); false
                }
            }
            if (ok) scene.addProp(key, model, x, y, z, yaw, scale) { why -> fail(why) }
        }
    }

    /** Fetches what [model] needs; false (and the error said) when the model itself didn't come. */
    private suspend fun prepare(model: Int): Boolean {
        val why = closure(files, model) { fetch(it) } ?: return true
        fail(if (why == UNREAD) "The prop (file $model) couldn't be read." else "The prop (file $model) couldn't be fetched: ${lastFailure ?: "wago.tools didn't give it"}.")
        return false
    }

    companion object {
        /** [closure]'s answer when the model came but can't be read. */
        const val UNREAD = "unread"

        /**
         * Fetches through [fetch] everything prop [model] needs (an M2: its skin, textures and skeleton; a WMO: its groups,
         * textures, its doodads' models and theirs): null when the model is here, else [UNREAD] or "missing". Shared with
         * Thumbs. Off the main thread.
         */
        suspend fun closure(files: GameFiles, model: Int, fetch: (Collection<Int>) -> Unit): String? {
            fetch(listOf(model))
            if (!files.has(model)) return "missing"
            val read = { id: Int -> if (files.has(id)) runCatching { files.file(id).readBytes() }.getOrNull() else null }
            val bytes = read(model) ?: return UNREAD
            val models = if (WmoFiles.isWmo(bytes)) {
                val deps = WmoFiles.deps(bytes)
                fetch(deps.groups + deps.textures)
                coroutineContext.ensureActive()
                fetch(deps.models)
                deps.models
            } else {
                listOf(model)
            }
            for (m in models) {
                if (!files.has(m)) continue
                M2Files.closure(m, emptySet(), read) { fetch(it) }
                coroutineContext.ensureActive()
            }
            return null
        }
    }

    private fun fetch(ids: Collection<Int>) {
        val missing = ids.distinct().filter { it > 0 && !files.has(it) }
        if (missing.isEmpty()) return
        update { it.copy(total = it.total + missing.size) }
        for (id in missing) {
            try {
                files.fetchNow(id)
            } catch (e: Exception) {
                lastFailure = e.message ?: e.toString()
                update { it.copy(error = "File $id couldn't be fetched: ${e.message ?: e}") }
            }
            update { f -> (f.done + 1).let { d -> if (d >= f.total) Loading(error = f.error) else f.copy(done = d) } }
        }
    }

    private fun fail(why: String) = update { it.copy(error = why) }

    private fun update(f: (Loading) -> Loading) = synchronized(_loading) { _loading.value = f(_loading.value) }

    /** Forgets the last failure (the screen showed it). */
    fun clearError() = update { it.copy(error = null) }
}
