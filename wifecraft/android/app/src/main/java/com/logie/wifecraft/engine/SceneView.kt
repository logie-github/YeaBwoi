package com.logie.wifecraft.engine

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.AnimArrays
import com.logie.wifecraft.anim.FleshProfile
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.bodyparts.EveOptions
import com.logie.wifecraft.bodyparts.ModelParts
import com.logie.wifecraft.bodyparts.PartDraw
import com.logie.wifecraft.bodyparts.PartFits
import com.logie.wifecraft.bodyparts.PartState
import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.BodyCall
import com.logie.wifecraft.creator.DressCall
import com.logie.wifecraft.creator.Sex
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
 * or the map's ground: [key] null), or the location’s walls and doodads ([key] carries the location pick ID); x, y, z where it was hit.
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
    class Prepared(val call: DressCall? = null, val body: BodyCall = BodyCall.NONE, val character: Boolean = false, val error: String? = null,
        /** The Eve body on the look (bodyparts/EveDress); null: her own body. */
        val eve: EveOptions? = null)
    /** A prop's files ([model]); null when they are here, else why not. */
    fun prepareProp(model: Int, onDone: (String?) -> Unit)
    /** A creature's ([PlaceKind.CREATURE], [asset] its display) or an NPC's ([PlaceKind.NPC], its [appearance]) look. */
    fun prepareLook(kind: PlaceKind, asset: Int, appearance: Appearance?, onDone: (Prepared) -> Unit)
}

/**
 * Where someone stands this frame. RAISED: the floor pose at the given height, not stood on the floor under it (a clip
 * lifted onto a bed or seat: Scene::kRaised).
 */
enum class FurniturePose(val nativeId: Int) { FLOOR(0), SIT(1), REST(2), RAISED(3) }

data class FurnitureContact(val point: Vec3, val facing: Float, val halfDepth: Float)

data class Placement(
    val x: Float, val y: Float, val z: Float, val facing: Float, val visible: Boolean = true,
    val furniture: FurniturePose = FurniturePose.FLOOR, val surface: Vec3? = null,
)

/**
 * Drives the view frame by frame on the GL thread (the live scene: simulation/LiveScene): called at the start of every frame
 * with its time, after the posted changes, it writes the camera and placements straight to the view ([SceneView.glCamera],
 * [SceneView.glPlace], [SceneView.glAnimate]) instead of [Stage.frame]: the frame's motion is computed at the rate the
 * screen draws, in step with it, with no state and nothing allocated on the main thread every frame.
 */
fun interface FrameDriver {
    fun drive(nowNanos: Long)
}

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
        /** Frames a person's flesh is tried for before it is given up ([glMeasureFlesh]). */
        private const val FLESH_TRIES = 600
        /** How long focusOn's camera move takes (ms). */
        const val FOCUS_MS = 550
    }

    @Volatile override var frame: Pair<CameraPose, Map<String, Placement>>? = null
    /** While set, it drives each frame in place of [frame] (FrameDriver). Any thread. */
    @Volatile var driver: FrameDriver? = null
    /** Frames drawn so far (a screen waits on it to know its first frame is up). */
    @Volatile var framesDrawn = 0L
        private set
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
    /** GL thread: an actor drawn at human scale (anim/HumanScale): the scale that stands in for its look's own. */
    private val humanScales = mutableMapOf<String, Float>()
    /** Each live person's size over a human's of their sex ([HumanScale.bodyScale]: 1 at human scale, a dwarf's or gnome's own). */
    private val bodyRatios = java.util.concurrent.ConcurrentHashMap<String, Float>()
    /** GL thread: people whose flesh is to be measured (dressed or redressed since), and the frames each has been tried. */
    private val fleshWanted = mutableMapOf<String, Int>()
    /** Each person's flesh as last measured ([flesh]). */
    private val fleshes = java.util.concurrent.ConcurrentHashMap<String, FleshProfile>()
    /** Goes up whenever a person's flesh is measured again ([flesh]). */
    @Volatile var fleshVersion = 0L
        private set
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
                glTimeOfDay()
                val d = driver
                if (d != null) {
                    d.drive(System.nanoTime())
                    applyGizmo()
                    Native.render(handle, width, height, Clock.now())
                    com.logie.wifecraft.graphics.Graphics.glFrame(handle)   // (Options' Graphics: timings, the governor)
                    glMeasureFlesh()
                    framesDrawn++
                    return
                }
                val f = frame
                steer(f?.first)?.let { cam -> Native.camera(handle, cam.eye.x, cam.eye.y, cam.eye.z, cam.at.x, cam.at.y, cam.at.z, cam.fov) }
                applyGizmo()
                f?.let { (_, people) ->
                    for ((key, p) in people) {
                        val id = ids[key] ?: continue
                        val surface = p.surface
                        Native.place(handle, id, p.x, p.y, p.z, p.facing, p.furniture.nativeId,
                            surface?.x ?: p.x, surface?.y ?: p.y, surface?.z ?: p.z)
                        Native.show(handle, id, p.visible)
                    }
                }
                Native.render(handle, width, height, Clock.now())
                com.logie.wifecraft.graphics.Graphics.glFrame(handle)
                glMeasureFlesh()
                framesDrawn++
            }
        })
        renderMode = RENDERMODE_CONTINUOUSLY
        // Options' Graphics: frames paced to the target frame rate on the screen's vsync while the view is on screen
        com.logie.wifecraft.graphics.Graphics.attach(this)
    }

    /**
     * GL thread, after a frame is drawn (a body's shape is made as it is drawn): one waiting person's flesh measured
     * ([Native.actorFlesh], the engine's once per look), at most one a frame; one that can't be measured (a creature's
     * skeleton) is given up after [FLESH_TRIES] frames and keeps the generic body's rules.
     */
    private fun glMeasureFlesh() {
        if (fleshWanted.isEmpty()) return
        val key = fleshWanted.keys.first()
        val id = ids[key]
        val tries = (fleshWanted[key] ?: 0) + 1
        val profile = id?.let { FleshProfile.of(Native.actorFlesh(handle, it)) }
        if (profile != null) { fleshes[key] = profile; fleshWanted.remove(key); fleshVersion++ }
        else if (id == null || tries >= FLESH_TRIES) fleshWanted.remove(key)
        else { fleshWanted.remove(key); fleshWanted[key] = tries }   // to the back: the others are tried meanwhile
    }

    /**
     * The person [key]'s flesh as their dressed model has it ([FleshProfile]: how thick each part of them is, for the floor,
     * seat and bed contact and a pair's spacing), or null while not measured (not dressed yet, or not a person's skeleton).
     * Any thread; [fleshVersion] goes up when one changes.
     */
    fun flesh(key: String): FleshProfile? = fleshes[key]

    /** GL thread, a [FrameDriver] only: the camera this frame. */
    fun glCamera(ex: Float, ey: Float, ez: Float, ax: Float, ay: Float, az: Float, fov: Float) {
        lastCam = null
        Native.camera(handle, ex, ey, ez, ax, ay, az, fov)
    }

    /** GL thread, a [FrameDriver] only: where the actor [key] stands this frame (an actor not on stage yet: nothing). */
    fun glPlace(key: String, x: Float, y: Float, z: Float, facing: Float, furniture: FurniturePose, sx: Float, sy: Float, sz: Float) {
        val id = ids[key] ?: return
        Native.place(handle, id, x, y, z, facing, furniture.nativeId, sx, sy, sz)
        Native.show(handle, id, true)
    }

    /** GL thread, a [FrameDriver] only: the actor [key]'s game animation from this very frame (as [animateActor]). */
    fun glAnimate(key: String, animation: Int) {
        if (requestedAnimations.put(key, animation) != animation) ids[key]?.let { Native.anim(handle, it, animation, true, Clock.now()) }
    }

    /** GL thread: start both roles on the same render clock. */
    fun glPlayAnimation(key: String, animation: Anim?) {
        val id = ids[key] ?: return
        if (animation == null) Native.clearBoneAnim(handle, id)
        else applyPose(handle, id, AnimArrays.of(animation))
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

    @Volatile private var wantedHour = -1f
    private var drawnHour = -1f

    /**
     * The world clock's hour (0..24, simulation/WorldClock.lightHour) on the location's light: sky, sun or moon, ambient,
     * fog and the rooms' night (Native.timeOfDay). Negative: off (the location's own light). Any thread, as often as the
     * clock moves: only the latest is used, on the next frame, and only when it changed.
     */
    fun setTimeOfDay(hours: Float) { wantedHour = if (hours.isFinite() && hours >= 0f) hours % 24f else -1f }

    /** GL thread: the hour asked for, once per change. */
    private fun glTimeOfDay() {
        val h = wantedHour
        if (h != drawnHour) { drawnHour = h; Native.timeOfDay(handle, h) }
    }

    // cinematic mode's look as last sent to the engine (mood, vignette, grain, bars)
    private val cineSent = FloatArray(4)

    /**
     * GL thread, a [FrameDriver] only: cinematic mode's look this frame (simulation/CinematicLook: [mood], [vignette] and
     * [grain] 0..1, [letterbox] 0..1 how far the bars are in, sized here for the view), sent to the engine only when it changed.
     */
    fun glCinematic(mood: Float, vignette: Float, grain: Float, letterbox: Float) {
        val bars = com.logie.wifecraft.simulation.CinematicLook.bars(width, height, letterbox)
        if (mood == cineSent[0] && vignette == cineSent[1] && grain == cineSent[2] && bars == cineSent[3]) return
        cineSent[0] = mood; cineSent[1] = vignette; cineSent[2] = grain; cineSent[3] = bars
        Native.cinematic(handle, mood, vignette, grain, bars)
    }

    private var zoneSent = -1f
    private var boostSent = false

    /** GL thread, a [FrameDriver] only: cinematic mode's zone mood (Native.zoneMood), sent only when it changed. */
    fun glZoneMood(strength: Float, boost: Boolean) {
        if (strength == zoneSent && boost == boostSent) return
        zoneSent = strength; boostSent = boost
        Native.zoneMood(handle, strength, boost)
    }

    /** Cinematic mode's look off at once (a scene ending: the next screen is drawn as before). Any thread. */
    fun clearCinematic() = post { h, _ ->
        cineSent.fill(0f)
        Native.cinematic(h, 0f, 0f, 0f, 0f)
    }

    // the body language's shares and the motes as last sent to the engine (GL thread)
    private val motionSent = floatArrayOf(-1f, -1f, -1f)
    private var motesSent = -1
    private var motesStrength = -1f

    /** GL thread, a [FrameDriver] only: Options' body language for everyone (cpp engine/motionlayer.h), sent when it changed. */
    fun glMotionParams(breathing: Float, idle: Float, lookAt: Boolean) {
        val l = if (lookAt) 1f else 0f
        if (breathing == motionSent[0] && idle == motionSent[1] && l == motionSent[2]) return
        motionSent[0] = breathing; motionSent[1] = idle; motionSent[2] = l
        Native.motionParams(handle, breathing, idle, lookAt)
    }

    /**
     * GL thread, a [FrameDriver] only: actor [key]'s body language this frame: its [intensity] 0..1 and who it looks at
     * ([lookAt] another actor's key; null nobody). An actor not on stage yet: nothing.
     */
    fun glMotion(key: String, intensity: Float, lookAt: String?) {
        val id = ids[key] ?: return
        Native.motion(handle, id, intensity, lookAt?.let { ids[it] } ?: 0)
    }

    // the skin sheen as last sent to the engine per actor id (GL thread)
    private val sheenSent = HashMap<Int, Float>()

    /**
     * GL thread, a [FrameDriver] only: actor [key]'s skin sheen 0..1 (cpp Scene::setSheen; graphics/SkinSheen), sent when it
     * moved by more than a little. An actor not on stage yet: nothing.
     */
    fun glSheen(key: String, value: Float) {
        val id = ids[key] ?: return
        val v = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        val last = sheenSent[id]
        if (last != null && kotlin.math.abs(last - v) < 0.004f && (v > 0f || last == 0f)) return
        sheenSent[id] = v
        Native.sheen(handle, id, v)
    }

    /** GL thread, a [FrameDriver] only: the atmosphere's motes ([count] 0: none; [strength] 0..1), sent when changed. */
    fun glParticles(count: Int, strength: Float) {
        if (count == motesSent && strength == motesStrength) return
        motesSent = count; motesStrength = strength
        Native.particles(handle, count, strength)
    }

    /** The body language and the motes off (a scene ending). Any thread. */
    fun clearAtmosphere() = post { h, ids ->
        motesSent = 0; motesStrength = 0f
        Native.particles(h, 0, 0f)
        for (id in ids.values) { Native.clearMotion(h, id); Native.sheen(h, id, 0f) }
        sheenSent.clear()
    }

    override fun showCity(shown: Boolean) = post { h, _ -> Native.showCity(h, shown) }

    private val requestedAnimations = HashMap<String, Int>()

    fun animateActor(key: String, animation: Int) = post { h, ids ->
        if (requestedAnimations.put(key, animation) != animation) {
            ids[key]?.let { Native.anim(h, it, animation, true, Clock.now()) }
        }
    }

    /**
     * Puts a look on the actor [key] (creator/Dressing's call; its files fetched first, engine/Dresser): the same model is
     * dressed in place ([Native.dress]: its place, facing and animation stay), another model is a new actor. Files the
     * look's layers or extra models still wait for join when they arrive. [body] (Wrath++'s body options) follows the dress.
     * A model the renderer can't read is said to [onFail] (on the GL thread), why included. [character]: a playable race's
     * model (the creator's people, NPCs of those races), the only actors with a soft body; a creature never has one.
     * [sex]: a person's; with it, an actor [HumanScale.applies] to (the live scene's NPCs, the editor's ghost) stands as tall
     * as a human of that sex instead of its look's own scale, its imported animations' root motion in the pairs' yards; but a
     * dwarf or gnome ([raceId]: [HumanScale.keepsOwnSize]) keeps its look's own scale, its root motion across the floor taken
     * by [HumanScale.pairScale] (a gnome's steps and pair slots its size's, simulation/PairFit).
     */
    fun dress(key: String, call: DressCall, body: BodyCall = BodyCall.NONE, character: Boolean = false,
        onReady: (() -> Unit)? = null, sex: Sex? = null, raceId: Int = 0, onFail: (String) -> Unit = {}) = post { h, ids ->
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
            humanScales.remove(key)
            if (sex != null && HumanScale.applies(key)) bodyRatios[key] = HumanScale.bodyScale(raceId, sex == Sex.FEMALE)
            else bodyRatios.remove(key)
            if (sex != null && HumanScale.applies(key) && HumanScale.keepsOwnSize(raceId)) {
                sizes[key]?.let { if (it != 1f) Native.setActorScale(h, added, call.scale * it) }
                Native.humanRootMotion(h, added, HumanScale.PAIR * HumanScale.pairScale(raceId, sex == Sex.FEMALE))
            } else if (sex != null && HumanScale.applies(key)) {
                val human = HumanScale.scale(Native.standHeight(h, added), sex == Sex.FEMALE)
                humanScales[key] = human
                Native.setActorScale(h, added, human * (sizes[key] ?: 1f))
                Native.humanRootMotion(h, added, HumanScale.PAIR)
            } else {
                sizes[key]?.let { if (it != 1f) Native.setActorScale(h, added, call.scale * it) }
            }
            id = added
            poses[key]?.let { applyPose(h, added, it) }
            requestedAnimations[key]?.let { Native.anim(h, added, it, true, Clock.now()) }
            partRaces[key] = raceId
            applyParts(h, key, added)
            applyModelSet(h, key, added)
            applyEve(h, key, added)
        }
        Native.character(h, id, character)
        Native.dress(h, id, call.geosetKeys, call.geosetValues, call.layerFiles, call.layerMeta, call.tintColours, call.extraModels)
        Native.body(h, id, body.morph, body.k, body.fold)
        if (key != GHOST_KEY) fleshWanted[key] = 0
        if (onReady != null) main.post { onReady() }
    }

    override fun removeActor(key: String) = post { h, ids -> ids.remove(key)?.let { Native.removeActor(h, it) }; bodies.remove(key); fleshWanted.remove(key); if (fleshes.remove(key) != null) fleshVersion++; sizes.remove(key); humanScales.remove(key); bodyRatios.remove(key); requestedAnimations.remove(key); actorParts.remove(key); partRaces.remove(key); modelSets.remove(key); eveOptions.remove(key) }

    /**
     * The actor [key]'s size over a human's of its sex as the live scene draws it (1 at human scale, a dwarf's or gnome's own:
     * [HumanScale.bodyScale]), for what is sized to the body (furniture's seat and bed heights); 1 for one not drawn so.
     * Any thread.
     */
    fun bodyScale(key: String): Float = bodyRatios[key] ?: 1f

    /**
     * Sizes the actor [key] at [factor] times its look's own scale (the placement editor's Size on a person or creature);
     * set before it is on stage it waits for it, and a re-dress or a new model keeps it. 1 is the look's own.
     */
    fun setActorScale(key: String, factor: Float) = post { h, ids ->
        sizes[key] = factor
        val id = ids[key]
        val look = humanScales[key] ?: bodies[key]?.second
        if (id != null && look != null) Native.setActorScale(h, id, look * factor)
    }

    override fun removeAll() = post { h, ids ->
        for (id in ids.values) Native.removeActor(h, id)
        ids.clear()
        bodies.clear()
        fleshWanted.clear()
        if (fleshes.isNotEmpty()) { fleshes.clear(); fleshVersion++ }
        sizes.clear()
        humanScales.clear()
        bodyRatios.clear()
        poses.clear()
        requestedAnimations.clear()
        actorParts.clear()
        partRaces.clear()
        modelSets.clear()
        eveOptions.clear()
    }

    // ---- body parts (cpp engine/bodypart.h; bodyparts/: the files, PartFits, PartSkin, PartDraw)

    /** GL thread: a part's texture as given to [attachPart]. */
    private class PartTexture(val part: String, val rgba: ByteArray, val width: Int, val height: Int)
    /** GL thread: what each key's actor wears ([attachPart], by slot: its file's state) and shows ([setPartState]), put on again when the actor is made anew. */
    private class ActorParts(val textures: MutableMap<String, PartTexture> = mutableMapOf(), var state: Int = PartDraw.NONE)
    private val actorParts = mutableMapOf<String, ActorParts>()
    /** GL thread: each key's race as last dressed (its parts' fit, [PartFits]). */
    private val partRaces = mutableMapOf<String, Int>()
    /** GL thread: loaded part files by "set/id" (their engine handles). */
    private val partHandles = mutableMapOf<String, Int>()

    /** GL thread: the part "set/id" (files/bodyparts/<set>/<id>.wcbp) loaded, its handle; 0 when it can't be read (logged). */
    private fun partHandle(h: Long, part: String): Int {
        partHandles[part]?.let { return it }
        val file = java.io.File(java.io.File(context.filesDir, "bodyparts"), "$part.wcbp")
        val got = Native.loadPart(h, file.path)
        if (got == 0) android.util.Log.w("Wifecraft", "part $part: ${Native.partError(h)}")
        else partHandles[part] = got
        return got
    }

    /** GL thread: the key's parts, fit and state on its actor [id] (a new actor, or after a change). */
    private fun applyParts(h: Long, key: String, id: Int) {
        val ap = actorParts[key] ?: return
        val fit = PartFits.forRace(partRaces[key] ?: 0)
        Native.partFit(h, id, fit.dx, fit.dz, fit.scale)
        for (t in ap.textures.values) {
            val handle = partHandle(h, t.part)
            if (handle != 0) Native.attachPart(h, id, handle, t.rgba, t.width, t.height)
        }
        Native.partState(h, id, ap.state)
    }

    /** Reads the part [set]/[id] (files/bodyparts/<set>/<id>.wcbp) ahead of its use; [onDone] (main thread) says whether it could. */
    fun loadPart(set: String, id: String, onDone: (Boolean) -> Unit = {}) = post { h, _ ->
        val ok = partHandle(h, "$set/$id") != 0
        main.post { onDone(ok) }
    }

    /**
     * Puts the part [part] ("set/id") on the actor [actorKey], drawn with [rgba] ([width] x [height], top row first: its
     * <id>.png tinted to the actor's skin, [PartSkin.tinted] with [partSkinUvs]). It takes the slot its file's state names
     * (DEFAULT or ON; one each, a new one replaces it). Nothing shows until [setPartState] picks a slot. Set before the
     * actor is on stage it waits for it; a new model for the key keeps it.
     */
    fun attachPart(actorKey: String, part: String, rgba: ByteArray, width: Int, height: Int) = post { h, ids ->
        val handle = partHandle(h, part)
        if (handle == 0) return@post
        // one texture per slot: the file's state, from the set's manifest (DEFAULT unless ON); the engine slots it the same way
        val ap = actorParts.getOrPut(actorKey) { ActorParts() }
        ap.textures[slotOf(part)] = PartTexture(part, rgba, width, height)
        ids[actorKey]?.let { applyParts(h, actorKey, it) }
    }

    /** Which part the actor [actorKey] shows: [PartDraw.NONE], [PartDraw.DEFAULT] or [PartDraw.ON] ([PartDraw.visible]: none unless bare). */
    fun setPartState(actorKey: String, state: Int) = post { h, ids ->
        val ap = actorParts.getOrPut(actorKey) { ActorParts() }
        ap.state = state
        ids[actorKey]?.let { Native.partState(h, it, state) }
    }

    /** Takes every part off the actor [actorKey]. */
    fun detachParts(actorKey: String) = post { h, ids ->
        actorParts.remove(actorKey)
        ids[actorKey]?.let { Native.detachParts(h, it) }
    }

    /** Where to sample the actor [actorKey]'s skin for its parts' tint ([PartSkin.sampleAt]); empty when it isn't on stage. [onResult] on the main thread. */
    fun partSkinUvs(actorKey: String, onResult: (FloatArray) -> Unit) = post { h, ids ->
        val uvs = ids[actorKey]?.let { Native.partSkinUvs(h, it) } ?: FloatArray(0)
        main.post { onResult(uvs) }
    }

    // ---- the Eve body (cpp engine/evebody.h; bodyparts/EveConvert, EveOptions, EveRule)

    /** GL thread: each key's Eve options ([setEveBody]), put on again when the actor is made anew. */
    private val eveOptions = mutableMapOf<String, EveOptions>()
    /** GL thread: the Eve set given ([loadEveSet]) and the scene it was loaded into (a new scene loads it again). */
    private var eveSet: Pair<String, String>? = null
    private var eveLoadedInto = 0L

    /** GL thread: the set loaded into [h] if it isn't yet; "" when it is, else why not. */
    private fun ensureEveSet(h: Long): String {
        val set = eveSet ?: return "no Eve set"
        if (eveLoadedInto == h) return ""
        val why = Native.loadEveSet(h, set.first, set.second)
        if (why.isEmpty()) eveLoadedInto = h else android.util.Log.w("Wifecraft", "Eve set: $why")
        return why
    }

    /** GL thread: the key's Eve options on its actor [id] (a new actor, or after a change). */
    private fun applyEve(h: Long, key: String, id: Int) {
        val o = eveOptions[key] ?: return
        if (o.on && ensureEveSet(h).isNotEmpty()) return
        Native.setEveBody(h, id, o.on, o.bottom, o.tone, o.overlaySlot, o.overlayShade, o.opacity, o.bones)
    }

    /**
     * The converted Eve set in [dir] (files/bodyparts/eve, bodyparts/EveConvert) with the fit table's text [fitJson]
     * (assets/eve_fit.json), for every actor from now on; [onResult] (main thread) gets "" or why it can't be read.
     */
    fun loadEveSet(dir: String, fitJson: String, onResult: (String) -> Unit = {}) = post { h, ids ->
        eveSet = dir to fitJson
        eveLoadedInto = 0L
        val why = ensureEveSet(h)
        if (why.isEmpty()) for ((key, id) in ids) applyEve(h, key, id)
        main.post { onResult(why) }
    }

    /**
     * The actor [actorKey]'s Eve body ([options]; its `on` false: its own body). The caller decides when the look allows it
     * (bodyparts/EveRule.applies: a woman, unclothed); the engine also says no for a race its fit table excludes. Set before
     * the actor is on stage it waits for it; kept for a new model of the key; the swap follows every change of look.
     */
    fun setEveBody(actorKey: String, options: EveOptions) = post { h, ids ->
        eveOptions[actorKey] = options
        ids[actorKey]?.let { applyEve(h, actorKey, it) }
        if (actorKey != GHOST_KEY) fleshWanted[actorKey] = 0   // (her flesh is measured again on the body she shows)
    }

    /** The actor [actorKey] back in her own body. */
    fun clearEveBody(actorKey: String) = post { h, ids ->
        eveOptions.remove(actorKey)
        ids[actorKey]?.let { Native.clearEveBody(h, it) }
        if (actorKey != GHOST_KEY) fleshWanted[actorKey] = 0
    }

    /** What became of the actor's Eve body (Native.eveStatus's "on|NECK|ANKLE|SHADE|MS|why"); [onResult] on the main thread. */
    fun eveStatus(actorKey: String, onResult: (String) -> Unit) = post { h, ids ->
        val st = ids[actorKey]?.let { Native.eveStatus(h, it) } ?: "no such actor"
        main.post { onResult(st) }
    }

    // ---- model parts (cpp engine/modelpart.h; bodyparts/ModelParts: the manifest, set.json, the skin tint)

    /** GL thread: a key's model set as put on ([putModelSet]): its regions (null: all), hidden regions, tint, and its parts' handles on the actor. */
    private class ActorModelSet(
        val set: String,
        val regions: Set<String>?,
        var tint: FloatArray = floatArrayOf(1f, 1f, 1f),
        val hidden: MutableSet<String> = mutableSetOf(),
        val handles: MutableList<Pair<Int, String>> = mutableListOf(),
    )
    private val modelSets = mutableMapOf<String, ActorModelSet>()

    /** GL thread: the key's model set on its actor [id] (a new actor, or after a change): its cut, its parts, its tint. False: nothing put on. */
    private fun applyModelSet(h: Long, key: String, id: Int): Boolean {
        val ms = modelSets[key] ?: return false
        Native.detachBoneParts(h, id)
        ms.handles.clear()
        val attachedBones = mutableSetOf<String>()
        val dir = java.io.File(java.io.File(context.filesDir, "bodyparts"), ms.set)
        val index = runCatching { ModelParts.parseIndex(java.io.File(dir, "set.json").readText()) }.getOrElse {
            android.util.Log.w("Wifecraft", "model set ${ms.set}: ${it.message}")
            return false
        }
        for (p in index.parts) {
            if ((ms.regions != null && p.region !in ms.regions) || p.region in ms.hidden) continue
            val part = partHandle(h, "${ms.set}/${p.file.removeSuffix(".wcbp")}")
            if (part == 0) continue
            val handle = Native.attachBonePart(h, id, part, p.dx, p.dy, p.dz, p.scale)
            if (handle == 0) continue
            ms.handles += handle to p.region
            attachedBones += p.bone
        }
        // the stand-in's own head cut away only under a piece on that bone (never a headless stand-in)
        val cut = index.cut.filter { bone -> bone in attachedBones }
        Native.bodyCut(h, id, cut.toTypedArray())
        Native.bodyTint(h, id, ms.tint[0], ms.tint[1], ms.tint[2])
        return ms.handles.isNotEmpty()
    }

    /**
     * Puts the cut model set [set] (files/bodyparts/<set>/: Native.buildModelParts made it) on the actor [actorKey], its
     * stand-in: its own head cut away as set.json says, the pieces of [regions] (null: all) on its bones. Set before the
     * actor is on stage, it waits for it; a new model gets it again. [onDone] (main thread): whether any part went on.
     * The skin match is the caller's: [faceSkinUvs], its composed skin sampled there, [ModelParts.tint] with set.json's
     * skinColour, [setModelSetTint].
     */
    fun putModelSet(actorKey: String, set: String, regions: Set<String>? = null, onDone: (Boolean) -> Unit = {}) = post { h, ids ->
        val old = modelSets[actorKey]
        modelSets[actorKey] = ActorModelSet(set, regions, old?.tint ?: floatArrayOf(1f, 1f, 1f))
        val ok = ids[actorKey]?.let { applyModelSet(h, actorKey, it) } ?: false
        main.post { onDone(ok) }
    }

    /** The stand-in [actorKey]'s skin tint (ModelParts.tint); kept with its model set. */
    fun setModelSetTint(actorKey: String, r: Float, g: Float, b: Float) = post { h, ids ->
        modelSets[actorKey]?.tint = floatArrayOf(r, g, b)
        ids[actorKey]?.let { Native.bodyTint(h, it, r, g, b) }
    }

    /** Shows or hides the [region]'s pieces of the actor [actorKey]'s model set (hiding the head gives the stand-in its own back). */
    fun showModelRegion(actorKey: String, region: String, shown: Boolean) = post { h, ids ->
        val ms = modelSets[actorKey] ?: return@post
        if (shown) ms.hidden -= region else ms.hidden += region
        ids[actorKey]?.let { applyModelSet(h, actorKey, it) }
    }

    /** Takes the model set off the actor [actorKey]: no pieces, no cut, no tint. */
    fun clearModelSet(actorKey: String) = post { h, ids ->
        modelSets.remove(actorKey)
        ids[actorKey]?.let {
            Native.detachBoneParts(h, it)
            Native.bodyCut(h, it, emptyArray())
            Native.bodyTint(h, it, 1f, 1f, 1f)
        }
    }

    /** Where to sample the stand-in [actorKey]'s own skin for the match (its face); empty when it isn't on stage. [onResult] on the main thread. */
    fun faceSkinUvs(actorKey: String, onResult: (FloatArray) -> Unit) = post { h, ids ->
        val uvs = ids[actorKey]?.let { Native.faceSkinUvs(h, it) } ?: FloatArray(0)
        main.post { onResult(uvs) }
    }

    /** The slot a part file goes in, from the set's manifest line (parts.jsonl: its "state"); DEFAULT when it can't be read. */
    private fun slotOf(part: String): String {
        val slash = part.lastIndexOf('/')
        if (slash < 0) return PartState.DEFAULT.name
        val manifest = java.io.File(java.io.File(java.io.File(context.filesDir, "bodyparts"), part.substring(0, slash)), "parts.jsonl")
        val id = part.substring(slash + 1)
        return runCatching {
            manifest.useLines { lines ->
                lines.map { org.json.JSONObject(it) }.firstOrNull { it.optString("id") == id }?.optString("state")
            }
        }.getOrNull()?.takeIf { it == PartState.ON.name } ?: PartState.DEFAULT.name
    }

    /**
     * Plays an imported animation ([Anim], WoW-side bone names) on the actor [actorKey] over its own animation: a pose
     * holds, a looping one loops, until [stopAnim]. Called before the actor is on stage, it waits for it; a re-dress
     * of the same key keeps it (a new model gets it again). Taking everyone off stage ([removeAll]) forgets it.
     */
    fun playAnim(actorKey: String, anim: Anim, onMapped: ((Int) -> Unit)? = null) {
        val arrays = AnimArrays.of(anim)
        post { h, ids ->
            poses[actorKey] = arrays
            val mapped = ids[actorKey]?.let { applyPose(h, it, arrays) }
            if (onMapped != null) main.post { onMapped(mapped ?: 0) }
        }
    }

    /** Takes [actorKey]'s imported animation off (it returns to its own animation). */
    fun stopAnim(actorKey: String) = post { h, ids ->
        poses.remove(actorKey)
        ids[actorKey]?.let { Native.clearBoneAnim(h, it) }
    }

    private fun applyPose(h: Long, id: Int, a: AnimArrays): Int =
        Native.boneAnim(h, id, a.bones, a.channels, a.keyCounts, a.times, a.values, a.duration, a.loop, Clock.now())

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

    fun creatorBackdrop(fileId: Int, onDone: (Boolean) -> Unit = {}) = post { h, _ ->
        val loaded = Native.creatorBackdrop(h, fileId)
        main.post { onDone(loaded) }
    }

    fun clearLocation() = post { h, _ -> currentView = null; Native.clearLocation(h) }

    /**
     * Puts the prop [key] (a static M2 or a WMO by file id, its files downloaded: PropLoader) at x, y, z turned [yaw] radians
     * about z at [scale]; a key already there is replaced. [onFail] (main thread) says why it couldn't be drawn.
     */
    fun addProp(key: String, model: Int, x: Float, y: Float, z: Float, yaw: Float, scale: Float,
        onReady: () -> Unit = {}, onFail: (String) -> Unit = {}) = post { h, _ ->
        propIds.remove(key)?.let { Native.removeProp(h, it) }
        val id = Native.addProp(h, model, x, y, z, yaw, scale)
        if (id <= 0) {
            val why = "The prop couldn't be drawn: " + Native.modelError(h, model).ifEmpty { "file $model couldn't be read" } + "."
            main.post { onFail(why) }
        } else {
            propIds[key] = id
            main.post { onReady() }
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

    /**
     * The part of actor [key]'s body the screen point lands on (the creator's tap-to-edit): [onRegion] on the main thread with
     * Native.pickBodyRegion's code, 0 when the point misses her or she isn't here.
     */
    fun pickBodyRegion(key: String, sx: Float, sy: Float, w: Int, h: Int, onRegion: (Int) -> Unit) = post { handle, ids ->
        val id = ids[key]
        val region = if (id == null) 0 else Native.pickBodyRegion(handle, id, sx, sy, w, h)
        main.post { onRegion(region) }
    }

    /** [setSculpt] by the Eve body's name for it: the sculpt is drawn on the fitted Eve mesh (engine/sculpt.h, evescene.cpp). */
    fun setEveSculpt(key: String, values: FloatArray) = setSculpt(key, values)

    /**
     * The creator's sculpt sliders on actor [key] (creator/BodySculpt.toArray: the sliders, then their sides): the game's own
     * body is sculpted again (at most six times a second while they change), the Eve body takes them as it is fitted.
     */
    fun setSculpt(key: String, values: FloatArray) = post { handle, ids ->
        ids[key]?.let { Native.setSculpt(handle, it, values) }
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
            Native.PICK_LOCATION -> Hit(Hit.Kind.LOCATION, "location:$id", r[2], r[3], r[4])
            else -> null
        }
        main.post { onHit(hit) }
    }

    fun findLocationObjects(references: List<com.logie.wifecraft.location.LocationInfo.ModelReference>, onFound: (Set<String>) -> Unit) =
        post { h, _ ->
            val keys = references.mapNotNullTo(HashSet()) { reference ->
                val p = reference.position
                Native.locationObject(h, reference.id, p.x, p.y, p.z).takeIf { it >= 0 }?.let { "location:$it" }
            }
            main.post { onFound(keys) }
        }

    /** GL thread: a direct segment, or a short detour checked against the scene mesh (simulation/WalkRoute). */
    fun glWalkRoute(from: Vec3, to: Vec3): List<Vec3>? = com.logie.wifecraft.simulation.WalkRoute.route(from, to,
        { a, b -> Native.walkSegment(handle, a.x, a.y, a.z, b.x, b.y, b.z) },
        { x, y, z -> Native.groundAt(handle, x, y, z) })

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

    fun propContact(key: String, pose: FurniturePose, onResult: (FurnitureContact?) -> Unit) = post { h, _ ->
        val surface = propIds[key]?.let { Native.propContact(h, it, pose.nativeId) }
        main.post {
            onResult(surface?.let { FurnitureContact(Vec3(it[0], it[1], it[2]), it[3], it[4]) })
        }
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
            // an NPC's ghost stands as the live scene draws it: at human scale, a dwarf or gnome at its own (a creature at its own)
            val sex = if (kind == PlaceKind.NPC) appearance?.sex else null
            // the Eve body as the scene would draw her (set before the dress: fitted once)
            p.eve.let { e -> if (e != null) setEveBody(GHOST_KEY, e) else clearEveBody(GHOST_KEY) }
            dress(GHOST_KEY, call, p.body, p.character, sex = sex, raceId = appearance?.raceId ?: 0) { why -> done(false, why) }
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
