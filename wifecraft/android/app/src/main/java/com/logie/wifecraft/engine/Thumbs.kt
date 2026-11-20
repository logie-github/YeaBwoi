package com.logie.wifecraft.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.logie.wifecraft.bodyparts.EveContext
import com.logie.wifecraft.bodyparts.EveDress
import com.logie.wifecraft.characters.ImportedLooks
import com.logie.wifecraft.characters.NamedCast
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.BodyCall
import com.logie.wifecraft.creator.CardOrder
import com.logie.wifecraft.creator.Catalog
import com.logie.wifecraft.creator.CreatureLooks
import com.logie.wifecraft.creator.DressCall
import com.logie.wifecraft.creator.Dressing
import com.logie.wifecraft.creator.ImportStore
import com.logie.wifecraft.creator.M2Files
import com.logie.wifecraft.creator.Overrides
import com.logie.wifecraft.creator.Tables
import com.logie.wifecraft.creator.TransmogCards
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
class Thumbs(
    context: Context, private val files: GameFiles, private val scene: SceneView?, private val tables: Tables,
    /** The user's imports (creator/ImportStore), applied to every NPC's and humanoid's picture as the live scene applies them. */
    private val overrides: () -> Overrides = { ImportStore.current ?: Overrides.None },
    /** The named characters: a picture of one is of her chosen look (her display, her bare or partial stand-in...). */
    private val named: () -> NamedCast? = { null },
    /** The Eve set as the dressing needs it (bodyparts/EveDress): an NPC's picture shows her Eve body as the scene does. */
    private val eve: () -> EveContext? = { null },
) : LookSource {
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

    /**
     * A picture's name: its kind, asset, look and size, and for an NPC or a creature what is imported and on (a picture made
     * before Ralia's mod was switched on is not shown after).
     */
    private fun keyOf(kind: PlaceKind, asset: Int, size: Int, appearance: Appearance? = null): String =
        kind.name.lowercase() + "_" + asset + (appearance?.let { "_" + Integer.toHexString(it.toJson().toString().hashCode()) } ?: "") +
            (if (kind == PlaceKind.PROP) "" else importsMark() + EveDress.cacheMark(eve(), appearance)) + "_" + size

    /** "_i<hash>" of the imports' signature (Overrides.signature), "" when nothing is imported. */
    private fun importsMark(): String = overrides().signature().let { if (it.isEmpty()) "" else "_i" + Integer.toHexString(it.hashCode()) }

    /** The picture if it is in memory now (no waiting), else null. */
    fun cached(kind: PlaceKind, asset: Int, size: Int): Bitmap? = memory.get(keyOf(kind, asset, size))

    /**
     * An NPC's picture if it is in memory now, else null: the grid's first frame shows it at once (a card scrolled back
     * into sight never flashes its placeholder while [requestNpc]'s answer is on its way).
     */
    fun cachedNpc(appearance: Appearance, asset: Int, size: Int = 160): Bitmap? = memory.get(keyOf(PlaceKind.NPC, asset, size, appearance))

    /** A request's handle: [cancel] drops its answer (a card scrolled out of sight); a picture nobody waits for any more is not made. */
    fun interface Ticket { fun cancel() }

    /**
     * Asks for the picture of [asset] ([PlaceKind.PROP]: a model or WMO file id; [PlaceKind.CREATURE]: a creature display;
     * an NPC needs its look: [requestNpc]) at [size] pixels square; [onReady] on the main thread (null: it couldn't be made,
     * [errors] says why), unless the returned ticket was cancelled first. The newest asked comes first.
     */
    fun request(kind: PlaceKind, asset: Int, size: Int = 160, onReady: (Bitmap?) -> Unit): Ticket = ask(kind, asset, size, null, onReady)

    /** An NPC's picture in its [appearance] ([asset] any number of the caller's: it only names the picture). */
    fun requestNpc(appearance: Appearance, asset: Int, size: Int = 160, onReady: (Bitmap?) -> Unit): Ticket =
        ask(PlaceKind.NPC, asset, size, appearance, onReady)

    private val done = Ticket { }

    private fun ask(kind: PlaceKind, asset: Int, size: Int, appearance: Appearance?, onReady: (Bitmap?) -> Unit): Ticket {
        val key = keyOf(kind, asset, size, appearance)
        memory.get(key)?.let { onReady(it); return done }
        if (kind == PlaceKind.NPC && appearance == null) { onReady(null); return done }
        running[key]?.let { job -> job.waiting += onReady; return ticketOf(job, onReady) }
        val old = queue.firstOrNull { it.key == key }
        val job = old ?: Job(key, kind, asset, size, appearance)
        if (old != null) queue.remove(old)
        job.waiting += onReady
        queue.addLast(job)
        while (queue.size > QUEUE) queue.removeFirst()   // the oldest asked: scrolled out of sight
        next()
        return ticketOf(job, onReady)
    }

    /** Main thread: takes [onReady] off [job]; a job still queued that nobody waits for leaves the queue. */
    private fun ticketOf(job: Job, onReady: (Bitmap?) -> Unit) = Ticket {
        job.waiting.remove(onReady)
        if (job.waiting.isEmpty()) queue.remove(job)
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
                p.eve?.let { e -> Native.thumbEve(h, e.on, e.bottom, e.tone, e.overlaySlot, e.overlayShade, e.opacity) }
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

    // ---- the Transmog window's cards (engine/TransmogPictures queues them)

    /** The cards' PNGs (files/thumbs/transmog/<key>.png), kept within TransmogCards.DISK_BYTES and DISK_FILES ([pruneCards]). */
    private val cardDir = File(dir, "transmog")
    /** One card on the GL thread at a time (each is a frame's work at most: the queue never floods the 3D view's thread). */
    private val glGate = Mutex()

    /** A card's picture if it is in memory now. */
    fun cachedCard(key: String, look: Appearance? = null): Bitmap? = memory.get(key + importsMark() + EveDress.cacheMark(eve(), look))

    /**
     * Off the main thread: [order]'s picture (the character wearing the card's pieces, framed as TransmogCards says, around her
     * transparent) from memory, the disk, or drawn (her files and the pieces' fetched first, then Native.thumbCard on the GL
     * thread) and saved; null when it couldn't be made ([errors] says why).
     */
    suspend fun card(order: CardOrder): Bitmap? {
        // named with what is imported and on, as the NPCs' pictures are
        val name = order.key + importsMark() + EveDress.cacheMark(eve(), order.look)
        memory.get(name)?.let { return it }
        cardDir.mkdirs()
        val file = File(cardDir, "$name.png")
        if (file.isFile && file.length() > 0) BitmapFactory.decodeFile(file.path)?.let {
            file.setLastModified(System.currentTimeMillis())
            memory.put(name, it)
            return it
        }
        val why = { w: String -> _errors.value = "The card picture ${order.key}: $w"; null }
        val view = scene ?: return why("there is no 3D view to draw it with")
        val p = look(PlaceKind.NPC, 0, order.look)
        val call = p.call ?: return why(p.error ?: "it has no look")
        val s = order.shot
        val pixels = glGate.withLock {
            onGl(view) { h ->
                p.eve?.let { e -> Native.thumbEve(h, e.on, e.bottom, e.tone, e.overlaySlot, e.overlayShade, e.opacity) }
                Native.thumbCard(h, call.model, call.scale, call.geosetKeys, call.geosetValues, call.layerFiles, call.layerMeta,
                    call.tintColours, call.extraModels, p.body.morph, p.body.k, p.body.fold, order.width, order.height,
                    s.lo, s.hi, s.az, s.el, s.fitWidth, true)
                    ?: run { why(Native.thumbError(h)); null }
            }
        }
        if (pixels == null || pixels.size < 2) return null
        val w = pixels[0]
        val h = pixels[1]
        if (w <= 0 || h <= 0 || pixels.size < 2 + w * h) return why("the picture came back the wrong size")
        // straight (not premultiplied) ARGB, as Bitmap.createBitmap(int[]) takes it
        val bmp = Bitmap.createBitmap(pixels, 2, w, w, h, Bitmap.Config.ARGB_8888)
        runCatching {
            val tmp = File(cardDir, "$name.part")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(file)
        }.onFailure { why("its picture couldn't be saved: ${it.message ?: it}") }
        memory.put(name, bmp)
        return bmp
    }

    /** Off the main thread: the cards' disk cache trimmed to its limits, the least recently used let go first. */
    fun pruneCards() {
        val list = cardDir.listFiles()?.filter { it.isFile } ?: return
        val drop = TransmogCards.prune(list.map { TransmogCards.Cached(it.name, it.length(), it.lastModified()) }).toSet()
        for (f in list) if (f.name in drop || f.name.endsWith(".part")) f.delete()
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

    /**
     * A creature's (its display) or an NPC's look, the user's imports applied as the live scene applies them
     * (characters/ImportedLooks: a named character in her chosen look, a humanoid display whose body the imports change drawn
     * as that body), its files fetched, and the renderer's call with its Body options.
     */
    private suspend fun look(kind: PlaceKind, asset: Int, appearance: Appearance?): LookSource.Prepared {
        val read = { id: Int -> if (files.has(id)) runCatching { files.file(id).readBytes() }.getOrNull() else null }
        val o = overrides()
        val c = creator ?: Catalog.build(tables).also { creator = it }
        val plan = if (kind == PlaceKind.CREATURE) {
            val r = CreatureLooks.check(tables, asset)
            if (r.look == null) return LookSource.Prepared(error = "display $asset has no look: ${r.why ?: "none"}")
            ImportedLooks.ofDisplay(c, tables, asset, o)
        } else {
            val a = appearance ?: return LookSource.Prepared(error = "the NPC's look isn't known")
            ImportedLooks.of(c, tables, a, named(), o)
        }
        val resolved = plan.look
        if (resolved.model == 0) return LookSource.Prepared(error = "it has no model in the game's tables")
        fetch(resolved.needsFiles - resolved.boneFiles.toSet())
        if (!files.has(resolved.model)) return LookSource.Prepared(error = "model ${resolved.model} couldn't be fetched (${lastFailure ?: "wago.tools didn't give it"})")
        for (m in Dressing.models(resolved)) M2Files.closure(m, if (m == resolved.model) setOf(Emotes.STAND) else emptySet(), read) { fetch(it) }
        val call: DressCall = plan.call(tables)
        val drawn = plan.drawn
        val body = if (drawn == null) BodyCall.NONE
            else runCatching { Dressing.body(files.dir.parentFile!!, drawn, o.hasBody()) }.getOrDefault(BodyCall.NONE)
        // the Eve body as the scene draws her (a creature's or a display's picture never)
        val eveOptions = if (kind == PlaceKind.CREATURE) null else EveDress.decide(appearance, drawn, resolved.model, eve()).options
        return LookSource.Prepared(call, body, plan.character, eve = eveOptions)
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
