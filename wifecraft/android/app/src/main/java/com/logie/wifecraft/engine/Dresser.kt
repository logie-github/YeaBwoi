package com.logie.wifecraft.engine

import com.logie.wifecraft.bodyparts.ModelParts
import com.logie.wifecraft.bodyparts.PartChoice
import com.logie.wifecraft.bodyparts.PartDraw
import com.logie.wifecraft.bodyparts.PartFile
import com.logie.wifecraft.bodyparts.PartSkin
import com.logie.wifecraft.bodyparts.RgbaImage
import com.logie.wifecraft.characters.CharacterLooks
import com.logie.wifecraft.characters.NamedCast
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.PartOptions
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
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
    data class Fetching(val done: Int = 0, val total: Int = 0, val error: String? = null, val emotes: Int = 0, val modelSets: Int = 0)

    /** The named characters (assets/characters.json, assets/model_parts.json): an appearance naming one is drawn as its variant. */
    @Volatile var named: NamedCast = NamedCast(emptyList())
    /** assets/model_parts.json's text, for Native.buildModelParts. */
    @Volatile var manifestJson: String? = null
    /** files/bodyparts: the model sets cut on the phone and the imported body-part sets. */
    @Volatile var partsRoot: File? = null
    /** The imported body-part set the men wear (Settings.partSet); null: none. */
    @Volatile var partSet: String? = null

    private val _withParts = MutableStateFlow<Set<String>>(emptySet())
    /** The keys whose actors wear the body parts now (a man with nothing below the waist, the add-on imported). */
    val withParts: StateFlow<Set<String>> = _withParts
    /** The keys a model set was put on (their stand-in is let go of it at the next dress). */
    private val withSets = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** What follows a dress per key (the model set, the body parts): cancelled with it. */
    private val extras = HashMap<String, Job>()
    private val setLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private val _fetching = MutableStateFlow(Fetching())
    val fetching: StateFlow<Fetching> = _fetching
    private val jobs = HashMap<String, Job>()
    /** The body model each key was last dressed in: an emote's animation files are that model's. */
    private val bodies = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Why the last file wasn't fetched, for the failure the screen shows. */
    @Volatile private var lastFailure: String? = null

    fun cancel(key: String) {
        jobs.remove(key)?.cancel()
        extras.remove(key)?.cancel()
        bodies.remove(key)
        // the actor leaves the stage with the caller (SceneView.removeActor or removeAll take its set and parts with it)
        withSets.remove(key)
        synchronized(_withParts) { if (key in _withParts.value) _withParts.value = _withParts.value - key }
    }

    /**
     * Puts [a] on the stage under [key]. An appearance naming a named character ([Appearance.named], [named]) is drawn as
     * its variant: the game's NPC display itself ([dressDisplay]); a body variant, without the skin colour's underwear for
     * the bare and nude ones (CharacterLooks.bare after the look is resolved); a partial one as its stand-in's bare body
     * wearing the pieces of her own model ([putModelSet]: the set cut on the phone the first time). A man with nothing
     * below the waist wears the imported body parts ([wearParts]) when a set is imported.
     */
    fun dress(scene: SceneView, catalog: Catalog, key: String, a: Appearance, extraAnimations: Set<Int> = emptySet(),
        onComplete: (() -> Unit)? = null) {
        jobs.remove(key)?.cancel()
        extras.remove(key)?.cancel()
        bodies.remove(key)
        letGo(scene, key)
        val choice = named.choiceOf(a)
        if (choice != null && choice.display > 0) {
            dressDisplay(scene, key, a, choice.display, onComplete)
            return
        }
        val drawn = choice?.let { CharacterLooks.appearance(it.variant) } ?: a
        val bare = choice?.bare == true
        jobs[key] = scope.launch {
            val call = withContext(Dispatchers.IO) {
                try {
                    prepare(key, catalog, drawn, extraAnimations, bare)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(e.message ?: e.toString()); null
                }
            }
            if (call == null) {
                onComplete?.invoke()
                return@launch
            }
            val set = choice?.set
            val parts = PartChoice.applies(a) && PartChoice.lowerBodyBare(a) && partSet != null
            // the look's sex and race: the live scene's NPCs stand at human scale, the dwarves and gnomes at their own
            // (SceneView.dress, anim/HumanScale); the creator's and the previews' keys keep each race's own size
            scene.dress(key, call.call, call.body, character = true, onReady = {
                onComplete?.invoke()
                if (set != null || parts) extras[key] = scope.launch {
                    if (set != null) putModelSet(scene, key, set, call.skin)
                    if (parts) wearParts(scene, key, a.parts ?: PartOptions(), call.skin)
                }
            }, sex = a.sex, raceId = a.raceId) { why ->
                fail(why)
                if (onComplete != null) scope.launch { onComplete() }
            }
        }
    }

    /** A named character in one of the game's own NPC displays ([display]), standing for [a]'s race and sex in the scene. */
    private fun dressDisplay(scene: SceneView, key: String, a: Appearance, display: Int, onComplete: (() -> Unit)?) {
        jobs[key] = scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                try {
                    val r = CreatureLooks.check(tables, display)
                    val look = r.look
                    if (look == null) { fail("Display $display can't be drawn: ${r.why ?: "it has no look"}."); null }
                    else prepareLook(key, look)?.let { it to CreatureLooks.isCharacter(tables, look) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fail(e.message ?: e.toString()); null
                }
            }
            if (prepared == null) {
                onComplete?.invoke()
                return@launch
            }
            scene.dress(key, prepared.first, BodyCall.NONE, character = prepared.second, onReady = onComplete, sex = a.sex, raceId = a.raceId) { why ->
                fail(why)
                if (onComplete != null) scope.launch { onComplete() }
            }
        }
    }

    /** Main thread: the key's model set and body parts taken off before it is dressed again. */
    private fun letGo(scene: SceneView, key: String) {
        if (withSets.remove(key)) scene.clearModelSet(key)
        if (key in _withParts.value) {
            scene.detachParts(key)
            synchronized(_withParts) { _withParts.value = _withParts.value - key }
        }
    }

    /**
     * The body-part state [state] (PartDraw: the scene's switch, bodyparts/PartStates) on the actor [key]: drawn only while it
     * wears the parts ([withParts]: its look is bare below the waist).
     */
    fun showParts(scene: SceneView, key: String, state: Int) {
        scene.setPartState(key, PartDraw.visible(state, key in _withParts.value))
    }

    // ---- model sets (bodyparts/ModelParts): a named character's partial look

    /**
     * Cuts [set] from her downloaded model on the phone the first time (Native.buildModelParts into files/bodyparts/<set>,
     * after the display's files are fetched), then puts it on the stand-in [key] and tints the stand-in's skin to her face's
     * ([skin]: the stand-in's skin file, sampled where SceneView.faceSkinUvs says; ModelParts.tint). A set that can't be
     * made leaves the plain stand-in and says why.
     */
    private suspend fun putModelSet(scene: SceneView, key: String, set: ModelParts.PartSet, skin: Int) {
        val root = partsRoot ?: return
        val why = withContext(Dispatchers.IO) {
            try {
                buildSet(root, set)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
        }
        if (why != null) { fail("Her partial look couldn't be made ($why); she shows her bare look."); return }
        withSets += key
        val put = kotlinx.coroutines.CompletableDeferred<Boolean>()
        scene.putModelSet(key, set.set, set.regions.toSet()) { put.complete(it) }
        // every region of the set is on (putModelSet's regions; showModelRegion would hide one: none is hidden here)
        if (!put.await()) { fail("Her partial look couldn't be put on; she shows her bare look."); return }
        if (!set.skinMatch) return
        val uvs = kotlinx.coroutines.CompletableDeferred<FloatArray>()
        scene.faceSkinUvs(key) { uvs.complete(it) }
        val face = uvs.await()
        val tint = withContext(Dispatchers.IO) {
            runCatching {
                val index = ModelParts.parseIndex(File(File(root, set.set), "set.json").readText())
                val own = skinImage(skin)?.let { PartSkin.sampleAt(it, face) } ?: -1
                ModelParts.tint(own, index.skinColour)
            }.getOrNull()
        } ?: return
        scene.setModelSetTint(key, tint[0], tint[1], tint[2])
    }

    /** IO: [set] cut into [root]/<set>/ unless it is there; null when it is, else why not. */
    private suspend fun buildSet(root: File, set: ModelParts.PartSet): String? = setLocks.getOrPut(set.set) { Mutex() }.withLock {
        if (File(File(root, set.set), "set.json").isFile) return@withLock null
        val manifest = manifestJson ?: return@withLock "the model parts' list isn't there"
        update { it.copy(modelSets = it.modelSets + 1) }
        try {
            // the display's own files (its model, skin, skeleton, textures) and the model the set is cut from
            CreatureLooks.check(tables, set.display).look?.let { look -> fetch(look.needsFiles) }
            fetch(listOf(set.model))
            coroutineContext.ensureActive()
            if (!files.has(set.model)) return@withLock "her model (file ${set.model}) couldn't be fetched: ${lastFailure ?: "wago.tools didn't give it"}"
            val read = { id: Int -> if (files.has(id)) runCatching { files.file(id).readBytes() }.getOrNull() else null }
            M2Files.closure(set.model, emptySet(), read) { fetch(it) }
            coroutineContext.ensureActive()
            root.mkdirs()
            val report = Native.buildModelParts(manifest, files.dir.path, root.path, set.set)
            if (report.lineSequence().firstOrNull()?.trim() == "ok" && File(File(root, set.set), "set.json").isFile) null
            else report.lineSequence().drop(1).firstOrNull { it.isNotBlank() && !it.startsWith("==") } ?: "the cut failed"
        } finally {
            update { it.copy(modelSets = (it.modelSets - 1).coerceAtLeast(0)) }
        }
    }

    // ---- the body parts (bodyparts/: PartChoice, PartSkin)

    /**
     * Puts the imported set's parts for [options] (its DEFAULT and ON part, PartChoice.select) on the actor [key], each
     * texture tinted to its skin ([skin], sampled where SceneView.partSkinUvs says; PartSkin.tinted), DEFAULT shown.
     */
    private suspend fun wearParts(scene: SceneView, key: String, options: PartOptions, skin: Int) {
        val set = partSet ?: return
        val dir = File(partsRoot ?: return, set)
        val chosen = withContext(Dispatchers.IO) {
            runCatching { PartChoice.select(PartChoice.parse(File(dir, "parts.jsonl").readText()), options) }.getOrNull()
        } ?: run { fail("The body parts ($set) can't be read: import them again in Options."); return }
        val entries = listOfNotNull(chosen.first, chosen.second)
        if (entries.isEmpty()) return
        val uvs = kotlinx.coroutines.CompletableDeferred<FloatArray>()
        scene.partSkinUvs(key) { uvs.complete(it) }
        val at = uvs.await()
        val textures = withContext(Dispatchers.IO) {
            val skinImage = skinImage(skin)
            entries.mapNotNull { e ->
                runCatching {
                    val part = PartFile.read(File(dir, "${e.id}.wcbp").readBytes())
                    val texture = e.texture?.let { pngImage(File(dir, it)) } ?: RgbaImage(4, 4, ByteArray(64) { i -> if (i % 4 == 3) (-1).toByte() else 0x9A.toByte() })
                    val tinted = if (skinImage != null && at.isNotEmpty()) PartSkin.tinted(texture, part, skinImage, at) else texture
                    e.id to tinted
                }.onFailure { fail("A body part (${e.id}) can't be read: ${it.message ?: it}") }.getOrNull()
            }
        }
        if (textures.isEmpty()) return
        for ((id, t) in textures) scene.attachPart(key, "$set/$id", t.rgba, t.width, t.height)
        synchronized(_withParts) { _withParts.value = _withParts.value + key }
        scene.setPartState(key, PartDraw.visible(PartDraw.DEFAULT, true))
    }

    /** IO: the skin file [fileId] (a BLP) decoded, 512 pixels at most; null when it can't be. */
    private fun skinImage(fileId: Int): RgbaImage? {
        if (fileId <= 0 || !files.has(fileId)) return null
        val px = Native.decodeBlp(files.file(fileId).path, 512) ?: return null
        if (px.size < 2) return null
        val w = px[0]; val h = px[1]
        if (w <= 0 || h <= 0 || px.size < 2 + w * h) return null
        val rgba = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            val c = px[2 + i]
            rgba[i * 4] = (c shr 16).toByte(); rgba[i * 4 + 1] = (c shr 8).toByte(); rgba[i * 4 + 2] = c.toByte(); rgba[i * 4 + 3] = (c ushr 24).toByte()
        }
        return RgbaImage(w, h, rgba)
    }

    /** IO: a part's PNG decoded (straight alpha, top row first). */
    private fun pngImage(file: File): RgbaImage? {
        val bmp = android.graphics.BitmapFactory.decodeFile(file.path) ?: return null
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val rgba = ByteArray(w * h * 4)
        for (i in px.indices) {
            val c = px[i]
            rgba[i * 4] = (c shr 16).toByte(); rgba[i * 4 + 1] = (c shr 8).toByte(); rgba[i * 4 + 2] = c.toByte(); rgba[i * 4 + 3] = (c ushr 24).toByte()
        }
        return RgbaImage(w, h, rgba)
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

    /** A dress's call, its body options and its skin's base file (the body parts' and model sets' skin match; 0: none). */
    private class Prepared(val call: DressCall, val body: BodyCall, val skin: Int)

    private suspend fun prepare(key: String, catalog: Catalog, a: Appearance, extraAnimations: Set<Int>, bare: Boolean = false): Prepared? {
        val resolved = LookResolver.resolve(catalog, tables, a, overrides)
        // a named character's bare and nude looks: without the skin colour's underwear layers
        val look = if (bare) CharacterLooks.bare(catalog, tables, a, resolved) else resolved
        if (look.model == 0) { fail("This race has no model in the game's tables."); return null }
        // the face's bone files aren't drawn yet (and the pinned build has some empty): not waited for
        fetch(look.needsFiles - look.boneFiles.toSet())
        coroutineContext.ensureActive()
        if (!files.has(look.model)) { fail("The model (file ${look.model}) couldn't be fetched: ${lastFailure ?: "wago.tools didn't give it"}."); return null }
        // what each model needs beside itself (its skin, textures, skeleton and the skeleton's parent, their animation
        // files): the body's animations are the ones the story plays
        val read = { id: Int -> if (files.has(id)) runCatching { files.file(id).readBytes() }.getOrNull() else null }
        for (m in Dressing.models(look)) {
            M2Files.closure(m, if (m == look.model) Emotes.all + extraAnimations else emptySet(), read) { fetch(it) }
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
        val skin = look.layers.firstOrNull { it.textureType == 1 && it.region == -1 }?.fileId ?: look.textures[1] ?: 0
        return Prepared(call, body, skin)
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
