package com.logie.wifecraft.engine

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.AnimArrays
import com.logie.wifecraft.creator.BodyCall
import com.logie.wifecraft.creator.DressCall
import com.logie.wifecraft.data.City
import com.logie.wifecraft.story.CameraPose
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** One clock for the story and the renderer: milliseconds since the app started. */
object Clock {
    private val base = SystemClock.uptimeMillis()
    fun now(): Int = (SystemClock.uptimeMillis() - base).toInt()
}

/**
 * What a screen point hits (SceneView.pick): a prop or an actor by the key it was added with, the ground (a location's floor
 * or the map's ground: [key] null), or the location's walls and doodads ([key] "location"); x, y, z where it was hit.
 */
data class Hit(val kind: Kind, val key: String?, val x: Float, val y: Float, val z: Float) {
    enum class Kind { PROP, ACTOR, GROUND, LOCATION }
}

/** A box in the world (SceneView.propBounds). */
data class Bounds(val minX: Float, val minY: Float, val minZ: Float, val maxX: Float, val maxY: Float, val maxZ: Float)

/** Where someone stands this frame. */
data class Placement(val x: Float, val y: Float, val z: Float, val facing: Float, val visible: Boolean = true)

/** A change to the scene, run on the GL thread with the renderer's handle and its ids for people. */
fun interface Change {
    fun apply(scene: Long, ids: MutableMap<String, Int>)
}

/** What the story stages on: the 3D view, or a stand-in in tests. People are named by the story's keys (dressed by the host). */
interface Stage {
    /** Each frame's camera and placements. */
    var frame: Pair<CameraPose, Map<String, Placement>>?
    /** Dalaran is loaded once and kept for every story after. */
    val cityLoaded: java.util.concurrent.atomic.AtomicBoolean
    fun loadCity(centres: FloatArray, onDone: () -> Unit)
    fun light(values: FloatArray)
    /** Whether the city is drawn (false on the studio's void stage). */
    fun showCity(shown: Boolean)
    fun removeActor(key: String)
    fun removeAll()
    fun anim(key: String, anim: Int, loop: Boolean, nowMs: Int)
}

/**
 * The 3D view behind the story. The story side posts changes ([post]) and each frame's camera and
 * placements ([frame]); the GL thread applies them and draws. People are named by keys (the
 * editor's actor ids, the script's `p_` names, "creator"); the renderer's own ids stay on the GL thread.
 */
class SceneView(context: Context, filesDir: String) : GLSurfaceView(context), Stage {

    @Volatile override var frame: Pair<CameraPose, Map<String, Placement>>? = null
    @Volatile var ready = false
        private set

    override val cityLoaded = java.util.concurrent.atomic.AtomicBoolean(false)

    private val changes = ConcurrentLinkedQueue<Change>()
    private val handle = Native.create(filesDir)
    private val ids = mutableMapOf<String, Int>()
    /** GL thread: the model and scale each key's actor was added with (a new model is a new actor). */
    private val bodies = mutableMapOf<String, Pair<Int, Float>>()
    /** GL thread: the placement editor's size of each actor key, a factor on the look's own scale (absent: 1). */
    private val sizes = mutableMapOf<String, Float>()
    /** GL thread: each key's imported animation ([playAnim]), put on its actor whenever that actor is (re)made. */
    private val poses = mutableMapOf<String, AnimArrays>()
    /** GL thread: the editor's props, by key. */
    private val propIds = mutableMapOf<String, Int>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var width = 1
    private var height = 1

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                Native.initGl(handle)
                ready = true
            }

            override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
                width = w
                height = h
            }

            override fun onDrawFrame(gl: GL10?) {
                while (true) {
                    val c = changes.poll() ?: break
                    c.apply(handle, ids)
                }
                frame?.let { (cam, people) ->
                    Native.camera(handle, cam.eye.x, cam.eye.y, cam.eye.z, cam.at.x, cam.at.y, cam.at.z, cam.fov)
                    for ((key, p) in people) {
                        val id = ids[key] ?: continue
                        Native.place(handle, id, p.x, p.y, p.z, p.facing)
                        Native.show(handle, id, p.visible)
                    }
                }
                Native.render(handle, width, height, Clock.now())
            }
        })
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun post(change: Change) {
        changes.add(change)
    }

    override fun loadCity(centres: FloatArray, onDone: () -> Unit) = post { h, _ ->
        Native.loadCity(h, City.ROOT, City.GROUPS, centres, City.PROP_RADIUS)
        cityLoaded.set(true)
        onDone()
    }

    override fun light(values: FloatArray) = post { h, _ -> Native.light(h, values) }

    override fun showCity(shown: Boolean) = post { h, _ -> Native.showCity(h, shown) }

    /**
     * Puts a look on the actor [key] (creator/Dressing's call; its files fetched first, engine/Dresser): the same model is
     * dressed in place ([Native.dress]: its place, facing and animation stay), another model is a new actor. Files the
     * look's layers or extra models still wait for join when they arrive. [body] (Wrath++'s body options) follows the dress.
     * A model the renderer can't read is said to [onFail] (on the GL thread), why included. [character]: a playable race's
     * model (the creator's people, NPCs of those races), the only actors with a soft body; a creature never has one.
     */
    fun dress(key: String, call: DressCall, body: BodyCall = BodyCall.NONE, character: Boolean = false, onFail: (String) -> Unit = {}) = post { h, ids ->
        var id = ids[key]
        if (id != null && bodies[key] != (call.model to call.scale)) {
            Native.removeActor(h, id); ids.remove(key); bodies.remove(key); id = null
        }
        if (id == null) {
            val added = Native.addActor(h, call.model, IntArray(0), IntArray(0), call.geosetKeys, call.geosetValues, call.scale)
            if (added <= 0) {
                onFail("The character couldn't be drawn: " + Native.modelError(h, call.model).ifEmpty { "model file ${call.model} couldn't be read" } + ".")
                return@post
            }
            ids[key] = added; bodies[key] = call.model to call.scale
            sizes[key]?.let { if (it != 1f) Native.setActorScale(h, added, call.scale * it) }
            id = added
            poses[key]?.let { applyPose(h, added, it) }
        }
        Native.character(h, id, character)
        Native.dress(h, id, call.geosetKeys, call.geosetValues, call.layerFiles, call.layerMeta, call.tintColours, call.extraModels)
        Native.body(h, id, body.morph, body.k, body.fold)
    }

    override fun removeActor(key: String) = post { h, ids -> ids.remove(key)?.let { Native.removeActor(h, it) }; bodies.remove(key); sizes.remove(key) }

    /**
     * Sizes the actor [key] at [factor] times its look's own scale (the placement editor's Size on a person or creature);
     * set before it is on stage it waits for it, and a re-dress or a new model keeps it. 1 is the look's own.
     */
    fun setActorScale(key: String, factor: Float) = post { h, ids ->
        sizes[key] = factor
        val id = ids[key]
        val look = bodies[key]?.second
        if (id != null && look != null) Native.setActorScale(h, id, look * factor)
    }

    override fun removeAll() = post { h, ids ->
        for (id in ids.values) Native.removeActor(h, id)
        ids.clear()
        bodies.clear()
        sizes.clear()
        poses.clear()
    }

    /**
     * Plays an imported animation ([Anim], WoW-side bone names) on the actor [actorKey] over its own animation: a pose
     * holds, a looping one loops, until [stopAnim]. Called before the actor is on stage, it waits for it; a re-dress
     * of the same key keeps it (a new model gets it again). Taking everyone off stage ([removeAll]) forgets it.
     */
    fun playAnim(actorKey: String, anim: Anim) {
        val arrays = AnimArrays.of(anim)
        post { h, ids ->
            poses[actorKey] = arrays
            ids[actorKey]?.let { applyPose(h, it, arrays) }
        }
    }

    /** Takes [actorKey]'s imported animation off (it returns to its own animation). */
    fun stopAnim(actorKey: String) = post { h, ids ->
        poses.remove(actorKey)
        ids[actorKey]?.let { Native.clearBoneAnim(h, it) }
    }

    private fun applyPose(h: Long, id: Int, a: AnimArrays) {
        Native.boneAnim(h, id, a.bones, a.channels, a.keyCounts, a.times, a.values, a.duration, a.loop, Clock.now())
    }

    /** Options' Soft body (Native.SOFT_OFF .. SOFT_STRONG), for everyone on stage from the next frame. */
    fun softBody(level: Int) = post { h, _ -> Native.softBody(h, level) }

    override fun anim(key: String, anim: Int, loop: Boolean, nowMs: Int) =
        post { h, ids -> ids[key]?.let { Native.anim(h, it, anim, loop, nowMs) } }

    // ---- locations and the editor's props (engine/LocationLoader and PropLoader fetch their files first)

    /**
     * Loads location [id] of [json] (assets/locations.json's text) with [view]'s light and fog ("" the entry's own), its
     * files already downloaded; Dalaran is hidden (it would stand in the same place). [onDone] (main thread): whether it
     * loaded, and why not.
     */
    fun loadLocation(json: String, id: String, view: String = "", onDone: (Boolean, String?) -> Unit = { _, _ -> }) = post { h, _ ->
        Native.showCity(h, false)
        val ok = Native.loadLocation(h, json, id, view)
        val why = if (ok) null else Native.locationError(h)
        main.post { onDone(ok, why) }
    }

    /** The loaded location's [view] (its light, fog and ground; the camera is the frame's). */
    fun locationView(view: String) = post { h, _ -> Native.locationView(h, view) }

    fun clearLocation() = post { h, _ -> Native.clearLocation(h) }

    /**
     * Puts the prop [key] (a static M2 or a WMO by file id, its files downloaded: PropLoader) at x, y, z turned [yaw] radians
     * about z at [scale]; a key already there is replaced. [onFail] (main thread) says why it couldn't be drawn.
     */
    fun addProp(key: String, model: Int, x: Float, y: Float, z: Float, yaw: Float, scale: Float, onFail: (String) -> Unit = {}) = post { h, _ ->
        propIds.remove(key)?.let { Native.removeProp(h, it) }
        val id = Native.addProp(h, model, x, y, z, yaw, scale)
        if (id <= 0) {
            val why = "The prop couldn't be drawn: " + Native.modelError(h, model).ifEmpty { "file $model couldn't be read" } + "."
            main.post { onFail(why) }
        } else {
            propIds[key] = id
        }
    }

    fun moveProp(key: String, x: Float, y: Float, z: Float, yaw: Float, scale: Float) =
        post { h, _ -> propIds[key]?.let { Native.moveProp(h, it, x, y, z, yaw, scale) } }

    fun removeProp(key: String) = post { h, _ -> propIds.remove(key)?.let { Native.removeProp(h, it) } }

    fun clearProps() = post { h, _ -> Native.clearProps(h); propIds.clear() }

    /** Selects the prop or actor [key] (a prop is drawn outlined), or nothing (null). */
    fun select(key: String?) = post { h, ids ->
        val prop = key?.let { propIds[it] }
        val actor = key?.let { ids[it] }
        when {
            prop != null -> Native.select(h, Native.PICK_PROP, prop)
            actor != null -> Native.select(h, Native.PICK_ACTOR, actor)
            else -> Native.select(h, Native.PICK_NONE, 0)
        }
    }

    /** What the screen point ([sx], [sy] in pixels of a [w] x [h] view) hits, to [onHit] on the main thread (null: nothing). */
    fun pick(sx: Float, sy: Float, w: Int, h: Int, onHit: (Hit?) -> Unit) = post { handle, ids ->
        val r = Native.pick(handle, sx, sy, w, h)
        val kind = r[0].toInt()
        val id = r[1].toInt()
        val hit = when (kind) {
            Native.PICK_PROP -> propIds.entries.firstOrNull { it.value == id }?.let { Hit(Hit.Kind.PROP, it.key, r[2], r[3], r[4]) }
            Native.PICK_ACTOR -> ids.entries.firstOrNull { it.value == id }?.let { Hit(Hit.Kind.ACTOR, it.key, r[2], r[3], r[4]) }
            Native.PICK_GROUND -> Hit(Hit.Kind.GROUND, null, r[2], r[3], r[4])
            Native.PICK_LOCATION -> Hit(Hit.Kind.LOCATION, "location", r[2], r[3], r[4])
            else -> null
        }
        main.post { onHit(hit) }
    }

    /** The floor under (x, y) nearest below [z] (a step up counts), to [onResult] on the main thread (null: none). */
    fun groundAt(x: Float, y: Float, z: Float = 10000f, onResult: (Float?) -> Unit) = post { h, _ ->
        val g = Native.groundAt(h, x, y, z)
        main.post { onResult(if (g.isNaN()) null else g) }
    }

    /** The prop [key]'s box in the world, to [onResult] on the main thread (null: no such prop). */
    fun propBounds(key: String, onResult: (Bounds?) -> Unit) = post { h, _ ->
        val b = propIds[key]?.let { Native.propBounds(h, it) }
        main.post { onResult(b?.let { Bounds(it[0], it[1], it[2], it[3], it[4], it[5]) }) }
    }

    /** Releases the renderer once the view is gone. */
    fun dispose() {
        queueEvent { Native.destroy(handle) }
    }
}
