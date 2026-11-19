package com.logie.wifecraft.engine

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.BodyCall
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.creator.CreatureLooks
import com.logie.wifecraft.creator.DressCall
import com.logie.wifecraft.creator.Dressing
import com.logie.wifecraft.creator.ImportStore
import com.logie.wifecraft.creator.LookResolver
import com.logie.wifecraft.creator.M2Files
import com.logie.wifecraft.creator.Overrides
import com.logie.wifecraft.creator.ResolvedLook
import com.logie.wifecraft.creator.Tables
import com.logie.wifecraft.data.Emotes
import com.logie.wifecraft.data.GameFiles
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
 * Puts an NPC's look on the 3D stage: the appearance resolved from the game's tables (creator/LookResolver, with the
 * user's imports as [overrides]), every file it needs fetched first (the model, its skin and the story's animations, the
 * layers' textures, the extra models and theirs, the face's bone files), then made the renderer's call (creator/Dressing)
 * with Wrath++'s body options (Dressing.body: the shape's morph from the user's package, the layer's square) and posted to
 * the [SceneView]. One look at a time per key: a newer one for the same key replaces one still fetching.
 * [fetching] says how far the fetching is, for the screen. Main thread for [dress]; the work is off it.
 */
class Dresser(
    private val files: GameFiles,
    private val tables: Tables,
    private val overrides: Overrides,
    private val scope: CoroutineScope,
) {
    /**
     * Files fetched of those asked for since the last time all were in ([total] 0: nothing to fetch); the last failure;
     * [emotes]: emotes whose animation files are on their way ([fetchAnim]: the screen says "Loading emote…").
     */
    data class Fetching(val done: Int = 0, val total: Int = 0, val error: String? = null, val emotes: Int = 0)

    private val _fetching = MutableStateFlow(Fetching())
    val fetching: StateFlow<Fetching> = _fetching
    private val jobs = HashMap<String, Job>()
    /** The body model each key was last dressed in: an emote's animation files are that model's. */
    private val bodies = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Why the last file wasn't fetched, for the failure the screen shows. */
    @Volatile private var lastFailure: String? = null

    fun cancel(key: String) {
        jobs.remove(key)?.cancel()
        bodies.remove(key)
    }

    fun dress(scene: SceneView, catalog: Catalog, key: String, a: Appearance) {
        jobs.remove(key)?.cancel()
        bodies.remove(key)
        jobs[key] = scope.launch {
            val call = withContext(Dispatchers.IO) {
                try {
                    prepare(key, catalog, a)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(e.message ?: e.toString()); null
                }
            } ?: return@launch
            // a model the renderer can't read is said on the screen, never left as an empty stage
            scene.dress(key, call.first, call.second, character = true) { why -> fail(why) }
        }
    }

    /**
     * Puts a creature's or an NPC's look ([CreatureLooks.resolve] of its display) on the stage under [key], as [dress] does a
     * created one: every file it needs fetched first (the model, its skin, skeleton and the story's animations, the
     * textures, the hung and skinned models), then the renderer's call ([CreatureLooks.call]); no body options. A soft body
     * only when the look is a playable race's model ([CreatureLooks.isCharacter]: a humanoid NPC), never a creature's.
     */
    fun dressLook(scene: SceneView, key: String, look: ResolvedLook) {
        jobs.remove(key)?.cancel()
        bodies.remove(key)
        jobs[key] = scope.launch {
            val call = withContext(Dispatchers.IO) {
                try {
                    prepareLook(key, look)?.let { it to CreatureLooks.isCharacter(tables, look) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(e.message ?: e.toString()); null
                }
            } ?: return@launch
            scene.dress(key, call.first, BodyCall.NONE, character = call.second) { why -> fail(why) }
        }
    }

    private suspend fun prepareLook(key: String, look: ResolvedLook): DressCall? {
        if (look.model == 0) { fail("This creature has no model in the game's tables."); return null }
        fetch(look.needsFiles)
        coroutineContext.ensureActive()
        if (!files.has(look.model)) { fail("The model (file ${look.model}) couldn't be fetched: ${lastFailure ?: "wago.tools didn't give it"}."); return null }
        val read = { id: Int -> if (files.has(id)) runCatching { files.file(id).readBytes() }.getOrNull() else null }
        for (m in Dressing.models(look)) {
            M2Files.closure(m, if (m == look.model) Emotes.all else emptySet(), read) { fetch(it) }
            coroutineContext.ensureActive()
        }
        bodies[key] = look.model
        return CreatureLooks.call(tables, look)
    }

    private suspend fun prepare(key: String, catalog: Catalog, a: Appearance): Pair<DressCall, BodyCall>? {
        val look = LookResolver.resolve(catalog, tables, a, overrides)
        if (look.model == 0) { fail("This race has no model in the game's tables."); return null }
        // the face's bone files aren't drawn yet (and the pinned build has some empty): not waited for
        fetch(look.needsFiles - look.boneFiles.toSet())
        coroutineContext.ensureActive()
        if (!files.has(look.model)) { fail("The model (file ${look.model}) couldn't be fetched: ${lastFailure ?: "wago.tools didn't give it"}."); return null }
        // what each model needs beside itself (its skin, textures, skeleton and the skeleton's parent, their animation
        // files): the body's animations are the ones the story plays
        val read = { id: Int -> if (files.has(id)) runCatching { files.file(id).readBytes() }.getOrNull() else null }
        for (m in Dressing.models(look)) {
            M2Files.closure(m, if (m == look.model) Emotes.all else emptySet(), read) { fetch(it) }
            coroutineContext.ensureActive()
        }
        bodies[key] = look.model
        // a skin or skeleton that didn't come is said by the renderer (SceneView.dress's onFail: Native.modelError)
        val body = try {
            Dressing.body(files.dir.parentFile!!, a, overrides.hasBody())
        } catch (e: Exception) {
            fail("The body options: ${e.message ?: e}"); BodyCall.NONE
        }
        val layout = Dressing.layout(catalog, tables, a)
        val call = if (a.gear && a.clothing.isNotEmpty()) CreatureLooks.call(tables, look, layout)
            else Dressing.call(tables, layout, look)
        return call to body
    }

    /**
     * Fetches the animation files game animation [anim] needs on the actor dressed under [key] (M2Files.animFiles: every
     * variation, an alias's own sequence, through the skeleton and its parent), once its look is dressed; those already
     * here (GameFiles keeps them) aren't fetched again. The renderer holds the actor's pose meanwhile and starts the
     * animation the moment its file is in (Scene::playAnim). A file that won't come is said on the screen. Main thread.
     */
    fun fetchAnim(key: String, anim: Int) {
        if (anim <= 0) return
        val dressing = jobs[key]
        scope.launch {
            dressing?.join()
            val model = bodies[key] ?: return@launch
            val failed = withContext(Dispatchers.IO) {
                val read = { id: Int -> if (files.has(id)) runCatching { files.file(id).readBytes() }.getOrNull() else null }
                val missing = M2Files.animFiles(model, anim, read).filter { it in 1 until ImportStore.FIRST_ID && !files.has(it) }
                if (missing.isEmpty()) return@withContext null
                update { it.copy(emotes = it.emotes + 1) }
                try {
                    missing.mapNotNull { id ->
                        try { files.fetchNow(id); null } catch (e: Exception) { "file $id: ${e.message ?: e}" }
                    }.firstOrNull()
                } finally {
                    update { it.copy(emotes = (it.emotes - 1).coerceAtLeast(0)) }
                }
            }
            if (failed != null) fail("The emote's animation couldn't be fetched ($failed); it plays once its file is in.")
        }
    }

    /** Fetches what of [ids] isn't here (the user's imported files are always here: their ids are the app's own). */
    private fun fetch(ids: Collection<Int>) {
        val missing = ids.filter { it in 1 until ImportStore.FIRST_ID && !files.has(it) }
        if (missing.isEmpty()) return
        update { it.copy(total = it.total + missing.size) }
        for (id in missing) {
            try {
                files.fetchNow(id)
            } catch (e: Exception) {
                lastFailure = e.message ?: e.toString()
                update { it.copy(error = "File $id couldn't be fetched: ${e.message ?: e}") }
            }
            update { f -> (f.done + 1).let { d -> if (d >= f.total) f.copy(done = 0, total = 0) else f.copy(done = d) } }
        }
    }

    private fun fail(why: String) = update { it.copy(error = why) }

    private fun update(f: (Fetching) -> Fetching) = synchronized(_fetching) { _fetching.value = f(_fetching.value) }

    /** Forgets the last failure (the screen showed it). */
    fun clearError() = update { it.copy(error = null) }
}
