package com.logie.wifecraft.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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
import com.logie.wifecraft.creator.Tables
import com.logie.wifecraft.data.Emotes
import com.logie.wifecraft.data.GameFiles
import com.logie.wifecraft.editor.PlaceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The library's pictures: a prop, a building or a creature drawn small, alone, from three quarters in front on a quiet
 * gradient, by the renderer itself (Native.thumbProp / thumbLook: an off-screen framebuffer of the [scene]'s own GL
 * context, drawn on its GL thread between frames, from a temporary prop or actor that is let go after; the live scene is
 * never touched). Its files are fetched first as PropLoader and Dresser fetch them (each failure kept in [errors], never
 * silent). Pictures are kept in memory and as PNGs in files/thumbs/<kind>_<asset>_<size>.png. At most [PARALLEL] are made at
 * once, the most recently asked for first, and only the newest [QUEUE] wait: a fast scroll leaves behind what scrolled out.
 * Also the [scene]'s [LookSource] (its ghost's files and look). Main thread for the calls; [request]'s answer comes on it.
 */
class Thumbs(context: Context, private val files: GameFiles, private val scene: SceneView?, private val tables: Tables) : LookSource {
    private val _errors = MutableStateFlow<String?>(null)
    /** The last picture that couldn't be made, and why. */
    val errors: StateFlow<String?> = _errors

    private val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private class Job(val key: String, val kind: PlaceKind, val asset: Int, val size: Int, val appearance: Appearance?) {
        val waiting = ArrayList<(Bitmap?) -> Unit>()
    }
    /** Main thread: those waiting (the newest last) and those being made. */
    private val queue = ArrayDeque<Job>()
    private val running = HashMap<String, Job>()

    /** The creator's catalogue (an NPC's look), read from the tables once it is first needed. */
    @Volatile private var creator: Catalog? = null

    init {
        scene?.looks = this
    }

    private fun keyOf(kind: PlaceKind, asset: Int, size: Int, appearance: Appearance? = null): String =
        kind.name.lowercase() + "_" + asset + (appearance?.let { "_" + Integer.toHexString(it.toJson().toString().hashCode()) } ?: "") + "_" + size

    /** The picture if it is in memory now (no waiting), else null. */
    fun cached(kind: PlaceKind, asset: Int, size: Int): Bitmap? = memory.get(keyOf(kind, asset, size))

    /**
     * Asks for the picture of [asset] ([PlaceKind.PROP]: a model or WMO file id; [PlaceKind.CREATURE]: a creature display;
     * an NPC needs its look: [requestNpc]) at [size] pixels square; [onReady] on the main thread (null: it couldn't be made,
     * [errors] says why). The newest asked comes first.
     */
    fun request(kind: PlaceKind, asset: Int, size: Int = 160, onReady: (Bitmap?) -> Unit) = ask(kind, asset, size, null, onReady)

    /** An NPC's picture in its [appearance] ([asset] any number of the caller's: it only names the picture). */
    fun requestNpc(appearance: Appearance, asset: Int, size: Int = 160, onReady: (Bitmap?) -> Unit) = ask(PlaceKind.NPC, asset, size, appearance, onReady)

    private fun ask(kind: PlaceKind, asset: Int, size: Int, appearance: Appearance?, onReady: (Bitmap?) -> Unit) {
        val key = keyOf(kind, asset, size, appearance)
        memory.get(key)?.let { onReady(it); return }
        if (kind == PlaceKind.NPC && appearance == null) { onReady(null); return }
        running[key]?.let { it.waiting += onReady; return }
        val old = queue.firstOrNull { it.key == key }
        val job = old ?: Job(key, kind, asset, size, appearance)
        if (old != null) queue.remove(old)
        job.waiting += onReady
        queue.addLast(job)
        while (queue.size > QUEUE) queue.removeFirst()   // the oldest asked: scrolled out of sight
        next()
    }

    /** Forgets every picture still waiting (one being made finishes and is kept). */
    fun cancelAll() {
        queue.clear()
        for (j in running.values) j.waiting.clear()
    }

    /** Main thread: starts the newest waiting while fewer than PARALLEL are being made. */
    private fun next() {
        while (running.size < PARALLEL && queue.isNotEmpty()) {
            val job = queue.removeLast()
            running[job.key] = job
            scope.launch {
                val bmp = try {
                    make(job)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(job, e.message ?: e.toString()); null
                }
                main.post {
                    running.remove(job.key)
                    if (bmp != null) memory.put(job.key, bmp)
                    for (cb in job.waiting) cb(bmp)
                    next()
                }
            }
        }
    }

    private fun fail(job: Job, why: String) {
        _errors.value = "The picture of ${job.kind.name.lowercase()} ${job.asset}: $why"
    }

    /** Off the main thread: from the disk, or fetched and drawn and saved. */
    private suspend fun make(job: Job): Bitmap? {
        val file = File(dir, job.key + ".png")
        if (file.isFile && file.length() > 0) BitmapFactory.decodeFile(file.path)?.let { return it }
        val view = scene ?: run { fail(job, "there is no 3D view to draw it with"); return null }
        val pixels: IntArray?
        if (job.kind == PlaceKind.PROP) {
            propFiles(job.asset)?.let { fail(job, it); return null }
            pixels = onGl(view) { h -> Native.thumbProp(h, job.asset, job.size) ?: run { fail(job, Native.thumbError(h)); null } }
        } else {
            val p = look(job.kind, job.asset, job.appearance)
            val call = p.call ?: run { fail(job, p.error ?: "it has no look"); return null }
            pixels = onGl(view) { h ->
                Native.thumbLook(h, call.model, call.scale, call.geosetKeys, call.geosetValues, call.layerFiles, call.layerMeta,
                    call.tintColours, call.extraModels, p.body.morph, p.body.k, p.body.fold, job.size)
                    ?: run { fail(job, Native.thumbError(h)); null }
            }
        }
        if (pixels == null || pixels.size < 2) return null
        val w = pixels[0]
        val h = pixels[1]
        val bmp = Bitmap.createBitmap(pixels, 2, w, w, h, Bitmap.Config.ARGB_8888)
        runCatching {
            val tmp = File(dir, job.key + ".part")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(file)
        }.onFailure { fail(job, "its picture couldn't be saved: ${it.message ?: it}") }
        return bmp
    }

    /** Runs [f] on the GL thread between frames; null when the 3D view didn't draw within GL_WAIT_MS (paused, gone). */
    private suspend fun <T> onGl(view: SceneView, f: (Long) -> T?): T? {
        val done = CompletableDeferred<T?>()
        view.post { h, _ -> done.complete(runCatching { f(h) }.getOrNull()) }
        return withTimeoutOrNull(GL_WAIT_MS) { done.await() }
    }

    // ---- the files and looks (also the scene's LookSource: its ghost)

    @Volatile private var lastFailure: String? = null

    private fun fetch(ids: Collection<Int>) {
        for (id in ids.distinct()) {
            if (id !in 1 until ImportStore.FIRST_ID || files.has(id)) continue
            try { files.fetchNow(id) } catch (e: Exception) { lastFailure = "file $id: ${e.message ?: e}" }
        }
    }

    /** A prop's files fetched; null when the model is here, else why not. */
    private suspend fun propFiles(model: Int): String? = when (PropLoader.closure(files, model) { fetch(it) }) {
        null -> null
        PropLoader.UNREAD -> "file $model couldn't be read"
        else -> "file $model couldn't be fetched (${lastFailure ?: "wago.tools didn't give it"})"
    }

    private suspend fun look(kind: PlaceKind, asset: Int, appearance: Appearance?): LookSource.Prepared {
        val read = { id: Int -> if (files.has(id)) runCatching { files.file(id).readBytes() }.getOrNull() else null }
        val resolved = if (kind == PlaceKind.CREATURE) {
            val r = CreatureLooks.check(tables, asset)
            r.look ?: return LookSource.Prepared(error = "display $asset has no look: ${r.why ?: "none"}")
        } else {
            val a = appearance ?: return LookSource.Prepared(error = "the NPC's look isn't known")
            val c = creator ?: Catalog.build(tables).also { creator = it }
            LookResolver.resolve(c, tables, a, Overrides.None)
        }
        if (resolved.model == 0) return LookSource.Prepared(error = "it has no model in the game's tables")
        fetch(resolved.needsFiles - resolved.boneFiles.toSet())
        if (!files.has(resolved.model)) return LookSource.Prepared(error = "model ${resolved.model} couldn't be fetched (${lastFailure ?: "wago.tools didn't give it"})")
        for (m in Dressing.models(resolved)) M2Files.closure(m, if (m == resolved.model) setOf(Emotes.STAND) else emptySet(), read) { fetch(it) }
        val call: DressCall = if (kind == PlaceKind.CREATURE) CreatureLooks.call(tables, resolved) else {
            val npc = checkNotNull(appearance)
            val layout = Dressing.layout(checkNotNull(creator), tables, npc)
            if (npc.gear && npc.clothing.isNotEmpty()) CreatureLooks.call(tables, resolved, layout)
            else Dressing.call(tables, layout, resolved)
        }
        val character = if (kind == PlaceKind.CREATURE) CreatureLooks.isCharacter(tables, resolved) else true
        return LookSource.Prepared(call, BodyCall.NONE, character)
    }

    override fun prepareProp(model: Int, onDone: (String?) -> Unit) {
        scope.launch {
            val why = try { propFiles(model) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { e.message ?: e.toString() }
            main.post { onDone(why?.let { "The ghost (file $model): $it." }) }
        }
    }

    override fun prepareLook(kind: PlaceKind, asset: Int, appearance: Appearance?, onDone: (LookSource.Prepared) -> Unit) {
        scope.launch {
            val p = try { look(kind, asset, appearance) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                LookSource.Prepared(error = e.message ?: e.toString())
            }
            main.post { onDone(p) }
        }
    }

    /** Stops every fetch (the screen is gone). */
    fun close() {
        cancelAll()
        scope.cancel()
        if (scene?.looks === this) scene.looks = null
    }

    companion object {
        const val PARALLEL = 2
        const val QUEUE = 48
        const val MEMORY_BYTES = 24 * 1024 * 1024
        const val GL_WAIT_MS = 20_000L
    }
}
