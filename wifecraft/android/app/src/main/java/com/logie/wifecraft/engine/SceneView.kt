package com.logie.wifecraft.engine

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.AnimArrays
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.BodyCall
import com.logie.wifecraft.creator.DressCall
import com.logie.wifecraft.data.City
import com.logie.wifecraft.editor.PlaceKind
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
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

/** The placement editor's gizmo (SceneView.setGizmo): none, the move arrows, the yaw ring, the scale boxes. */
enum class GizmoMode { OFF, MOVE, ROTATE, SCALE }

/**
 * The camera as the renderer last drew it (SceneView.cameraState): WoW's coordinates (z up), [fovY] vertical in degrees,
 * [aspect] width over height, [view] and [proj] column-major as OpenGL takes them (clip = proj x view x point).
 */
class CameraInfo(
    val eye: Vec3, val target: Vec3, val up: Vec3, val fovY: Float, val aspect: Float, val view: FloatArray, val proj: FloatArray,
) {
    /** Where the world point is on a [w] x [h] view (pixels, y down), or null behind the eye. */
    fun project(x: Float, y: Float, z: Float, w: Float, h: Float): Pair<Float, Float>? {
        val m = FloatArray(16)
        android.opengl.Matrix.multiplyMM(m, 0, proj, 0, view, 0)
        val cw = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (cw <= 1e-4f) return null
        val cx = (m[0] * x + m[4] * y + m[8] * z + m[12]) / cw
        val cy = (m[1] * x + m[5] * y + m[9] * z + m[13]) / cw
        return (cx + 1f) * 0.5f * w to (1f - cy) * 0.5f * h
    }

    /** The ray from the eye through the screen point (pixels of a [w] x [h] view): origin and unit direction. */
    fun ray(sx: Float, sy: Float, w: Float, h: Float): Pair<Vec3, Vec3> {
        val f = (target - eye).let { d -> d * (1f / kotlin.math.sqrt(d.x * d.x + d.y * d.y + d.z * d.z).coerceAtLeast(1e-6f)) }
        val s = Vec3(f.y * up.z - f.z * up.y, f.z * up.x - f.x * up.z, f.x * up.y - f.y * up.x)
        val th = kotlin.math.tan(Math.toRadians(fovY / 2.0).toFloat())
        val nx = 2f * sx / w.coerceAtLeast(1f) - 1f
        val ny = 1f - 2f * sy / h.coerceAtLeast(1f)
        val d = f + s * (nx * th * (w / h.coerceAtLeast(1f))) + up * (ny * th)
        val l = kotlin.math.sqrt(d.x * d.x + d.y * d.y + d.z * d.z).coerceAtLeast(1e-6f)
        return eye to d * (1f / l)
    }
}

/**
 * What a ghost or a thumbnail needs before it is drawn (engine/Thumbs is one): every file of a prop fetched, or a creature's
 * or NPC's look resolved and its files fetched. [onDone] on the main thread.
 */
interface LookSource {
    class Prepared(val call: DressCall? = null, val body: BodyCall = BodyCall.NONE, val character: Boolean = false, val error: String? = null)
    /** A prop's files ([model]); null when they are here, else why not. */
    fun prepareProp(model: Int, onDone: (String?) -> Unit)
    /** A creature's ([PlaceKind.CREATURE], [asset] its display) or an NPC's ([PlaceKind.NPC], its [appearance]) look. */
    fun prepareLook(kind: PlaceKind, asset: Int, appearance: Appearance?, onDone: (Prepared) -> Unit)
}

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

    companion object {
        /** The ghost actor's key (never a placement's). */
        const val GHOST_KEY = "ghost:"
        /** How long focusOn's camera move takes (ms). */
        const val FOCUS_MS = 550
    }

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
                val f = frame
                steer(f?.first)?.let { cam -> Native.camera(handle, cam.eye.x, cam.eye.y, cam.eye.z, cam.at.x, cam.at.y, cam.at.z, cam.fov) }
                applyGizmo()
                f?.let { (_, people) ->
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
            if (key == GHOST_KEY && ghostGen.get() != ghostWanted) return@post   // the ghost was let go meanwhile
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
        currentView = view
        val ok = Native.loadLocation(h, json, id, view)
        val why = if (ok) null else Native.locationError(h)
        main.post { onDone(ok, why) }
    }

    /** The loaded location's [view] (its light, fog and ground; the camera is the frame's). */
    fun locationView(view: String) = post { h, _ -> currentView = view; Native.locationView(h, view) }

    fun clearLocation() = post { h, _ -> currentView = null; Native.clearLocation(h) }

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

    // ---- the placement editor's gizmo, camera and ghost (cpp engine/editview.cpp)

    @Volatile private var gizmoMode = GizmoMode.OFF
    @Volatile private var gizmoKey: String? = null
    @Volatile private var gizmoAxis = -1

    /**
     * Shows [mode]'s gizmo on the prop or actor [key] (the keys [select] takes; null or OFF: none), [activeAxis] lit (the
     * handle being dragged: 0 x, 1 y, 2 z, 3 the yaw ring, 4 the uniform box, 5 the floor square; -1 none). It follows its
     * thing every frame (a re-added prop, a moved actor) at the same size on the screen. Any thread.
     */
    fun setGizmo(mode: GizmoMode, key: String?, activeAxis: Int = -1) {
        gizmoMode = if (key == null) GizmoMode.OFF else mode
        gizmoKey = key
        gizmoAxis = activeAxis
    }

    /** GL thread, each frame: the gizmo on its thing's renderer id now. */
    private fun applyGizmo() {
        val key = gizmoKey
        val mode = gizmoMode
        val prop = key?.let { propIds[it] }
        val actor = key?.let { ids[it] }
        when {
            mode == GizmoMode.OFF || key == null -> Native.gizmo(handle, Native.GIZMO_OFF, 0, 0, -1)
            prop != null -> Native.gizmo(handle, mode.ordinal, Native.PICK_PROP, prop, gizmoAxis)
            actor != null -> Native.gizmo(handle, mode.ordinal, Native.PICK_ACTOR, actor, gizmoAxis)
            else -> Native.gizmo(handle, Native.GIZMO_OFF, 0, 0, -1)
        }
    }

    /**
     * Which handle of the gizmo is under the screen point ([sx], [sy] pixels of a [w] x [h] view, the camera last drawn),
     * within [slopPx] pixels (the nearest), to [onHit] on the main thread: an axis id as [setGizmo]'s, -1 none.
     */
    fun gizmoHit(sx: Float, sy: Float, w: Int, h: Int, onHit: (Int) -> Unit, slopPx: Float = 40f) = post { hd, _ ->
        applyGizmo()
        val hit = Native.gizmoHit(hd, sx, sy, w, h, slopPx)
        main.post { onHit(hit) }
    }

    /** The camera as last drawn, to [onState] on the main thread (to project points and make rays as the renderer does). */
    fun cameraState(onState: (CameraInfo) -> Unit) = post { h, _ ->
        val c = cameraInfoOf(h)
        main.post { onState(c) }
    }

    private fun cameraInfoOf(h: Long): CameraInfo {
        val v = Native.cameraInfo(h)
        return CameraInfo(Vec3(v[0], v[1], v[2]), Vec3(v[3], v[4], v[5]), Vec3(v[6], v[7], v[8]), v[9], v[10],
            v.copyOfRange(11, 27), v.copyOfRange(27, 43))
    }

    /** GL thread: the loaded location's view (focusOn(null) goes back to its camera). */
    private var currentView: String? = null
    /** GL thread: the camera last given to the renderer, a move under way, and a camera held after one. */
    private var lastCam: CameraPose? = null
    private class CamMove(val from: CameraPose, val to: CameraPose?, val start: Int, val ms: Int, val onDone: ((CameraInfo) -> Unit)?)
    private var camMove: CamMove? = null
    private var held: CameraPose? = null
    private var heldUnder: CameraPose? = null

    /**
     * Frames the prop or actor [key] (null: back to the stage's own view: the location view's camera, else the frame's) with
     * an eased move of about half a second, the way the camera looks now kept. The frame's camera ([frame]) is overridden
     * until the screen gives it a new one: [onDone] (main thread) has the camera it ended on, for the screen to take as its
     * own (OrbitCamera.from(CameraPose(eye, target, fovY))). Any thread.
     */
    fun focusOn(key: String?, onDone: ((CameraInfo) -> Unit)? = null) = post { h, ids ->
        val from = lastCam ?: frame?.first ?: return@post
        var to: CameraPose? = null
        if (key == null) {
            currentView?.let { Native.viewCamera(h, it) }?.let { v -> to = CameraPose(Vec3(v[0], v[1], v[2]), Vec3(v[3], v[4], v[5]), v[6]) }
        } else {
            val b = propIds[key]?.let { Native.propBounds(h, it) }
                ?: ids[key]?.let { id -> frame?.second?.get(key)?.let { p -> Native.actorBounds(h, id, p.x, p.y, p.z, p.facing) } }
                ?: return@post
            val c = Vec3((b[0] + b[3]) / 2f, (b[1] + b[4]) / 2f, (b[2] + b[5]) / 2f)
            val r = (kotlin.math.sqrt((b[3] - b[0]) * (b[3] - b[0]) + (b[4] - b[1]) * (b[4] - b[1]) + (b[5] - b[2]) * (b[5] - b[2])) / 2f).coerceAtLeast(0.4f)
            // the way the camera looks now, from at least a little above
            val d = from.eye - from.at
            val flat = kotlin.math.hypot(d.x, d.y).coerceAtLeast(1e-3f)
            val yaw = kotlin.math.atan2(d.y, d.x)
            val pitch = kotlin.math.atan2(d.z, flat).coerceIn(0.25f, 1.2f)
            val dist = (r / kotlin.math.sin(Math.toRadians(from.fov / 2.0).toFloat()) * 1.15f).coerceAtLeast(2f)
            val dir = Vec3(kotlin.math.cos(pitch) * kotlin.math.cos(yaw), kotlin.math.cos(pitch) * kotlin.math.sin(yaw), kotlin.math.sin(pitch))
            to = CameraPose(c + dir * dist, c, from.fov)
        }
        camMove = CamMove(from, to, Clock.now(), FOCUS_MS, onDone)
        held = null
    }

    /** GL thread, each frame: the camera to draw with ([base] the frame's): a focus move under way, one held, or [base]. */
    private fun steer(base: CameraPose?): CameraPose? {
        val m = camMove
        if (m != null) {
            val to = m.to ?: base ?: m.from
            val t = ((Clock.now() - m.start).toFloat() / m.ms).coerceIn(0f, 1f)
            val e = t * t * (3f - 2f * t)
            val pose = CameraPose(m.from.eye + (to.eye - m.from.eye) * e, m.from.at + (to.at - m.from.at) * e, m.from.fov + (to.fov - m.from.fov) * e)
            if (t >= 1f) {
                camMove = null
                held = if (m.to == null) null else to
                heldUnder = base
                m.onDone?.let { cb -> Native.camera(handle, to.eye.x, to.eye.y, to.eye.z, to.at.x, to.at.y, to.at.z, to.fov); val c = cameraInfoOf(handle); main.post { cb(c) } }
            }
            lastCam = pose
            return pose
        }
        val hd = held
        if (hd != null) {
            if (base == heldUnder) { lastCam = hd; return hd }
            held = null
        }
        if (base != null) lastCam = base
        return base
    }

    /** What fetches a ghost's files and resolves its look (engine/Thumbs sets itself here). */
    @Volatile var looks: LookSource? = null
    private val ghostGen = java.util.concurrent.atomic.AtomicInteger(0)
    /** GL thread: the ghost generation whose actor may be made (an older one's dress is dropped). */
    @Volatile private var ghostWanted = 0
    @Volatile private var ghostAt = floatArrayOf(0f, 0f, 0f, 0f)

    /**
     * Starts the ghost of what is about to be placed: a prop ([PlaceKind.PROP], [asset] its file), a creature (its display) or
     * an NPC (its [appearance]) at [scale], its files fetched first (through [looks]); drawn half see-through, tinted
     * blue-white, depth-tested, never picked, never saved, wherever [ghostMove] last put it. [onReady] (main thread): whether
     * it is drawn, and why not. A newer ghostBegin or [ghostClear] drops one still on its way.
     */
    fun ghostBegin(kind: PlaceKind, asset: Int, scale: Float, onReady: (Boolean, String?) -> Unit, appearance: Appearance? = null) {
        val gen = ghostGen.incrementAndGet()
        post { h, ids -> ids.remove(GHOST_KEY)?.let { Native.removeActor(h, it) }; bodies.remove(GHOST_KEY); sizes.remove(GHOST_KEY); Native.ghostClear(h) }
        val src = looks
        fun done(ok: Boolean, why: String?) { main.post { if (ghostGen.get() == gen) onReady(ok, why) } }
        if (kind == PlaceKind.PROP) {
            val draw = {
                post { h, _ ->
                    if (ghostGen.get() != gen) return@post
                    val a = ghostAt
                    val ok = Native.ghostProp(h, asset, a[0], a[1], a[2], a[3], scale)
                    done(ok, if (ok) null else "The ghost couldn't be drawn: " + Native.modelError(h, asset).ifEmpty { "file $asset couldn't be read" } + ".")
                }
            }
            if (src == null) draw() else src.prepareProp(asset) { why -> if (why == null) draw() else done(false, why) }
            return
        }
        if (src == null) { done(false, "The ghost's look can't be fetched yet (the library's pictures aren't set up)."); return }
        src.prepareLook(kind, asset, appearance) { p ->
            val call = p.call
            if (ghostGen.get() != gen) return@prepareLook
            if (call == null) { done(false, p.error ?: "No look."); return@prepareLook }
            post { _, _ -> ghostWanted = gen; sizes[GHOST_KEY] = scale }
            dress(GHOST_KEY, call, p.body, p.character) { why -> done(false, why) }
            post { h, ids ->
                val id = ids[GHOST_KEY] ?: return@post
                if (ghostGen.get() != gen) { ids.remove(GHOST_KEY); Native.removeActor(h, id); return@post }
                Native.ghostActor(h, id)
                val a = ghostAt
                Native.ghostMove(h, a[0], a[1], a[2], a[3])
                done(true, null)
            }
        }
    }

    /** Moves the ghost to x, y, z (WoW's, z up) turned [yaw] radians about z. Any thread. */
    fun ghostMove(x: Float, y: Float, z: Float, yaw: Float) {
        ghostAt = floatArrayOf(x, y, z, yaw)
        post { h, _ -> val a = ghostAt; Native.ghostMove(h, a[0], a[1], a[2], a[3]) }
    }

    /** No ghost (one still being fetched is dropped). Any thread. */
    fun ghostClear() {
        ghostGen.incrementAndGet()
        post { h, ids ->
            Native.ghostClear(h)
            ids.remove(GHOST_KEY)?.let { Native.removeActor(h, it) }
            bodies.remove(GHOST_KEY); sizes.remove(GHOST_KEY)
        }
    }

    /** The ghost's box in the world, to [onResult] on the main thread (null: no ghost). */
    fun ghostBounds(onResult: (Bounds?) -> Unit) = post { h, _ ->
        val b = Native.ghostBounds(h)
        main.post { onResult(b?.let { Bounds(it[0], it[1], it[2], it[3], it[4], it[5]) }) }
    }

    /** GL thread: the renderer's handle, for engine/Thumbs (its pictures are drawn between frames). */
    internal val nativeHandle: Long get() = handle

    /** Releases the renderer once the view is gone. */
    fun dispose() {
        queueEvent { Native.destroy(handle) }
    }
}
