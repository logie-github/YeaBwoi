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
    /** GL thread: each key's imported animation ([playAnim]), put on its actor whenever that actor is (re)made. */
    private val poses = mutableMapOf<String, AnimArrays>()
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
     */
    fun dress(key: String, call: DressCall, body: BodyCall = BodyCall.NONE) = post { h, ids ->
        var id = ids[key]
        if (id != null && bodies[key] != (call.model to call.scale)) {
            Native.removeActor(h, id); ids.remove(key); bodies.remove(key); id = null
        }
        if (id == null) {
            val added = Native.addActor(h, call.model, IntArray(0), IntArray(0), call.geosetKeys, call.geosetValues, call.scale)
            if (added <= 0) return@post
            ids[key] = added; bodies[key] = call.model to call.scale
            id = added
            poses[key]?.let { applyPose(h, added, it) }
        }
        Native.dress(h, id, call.geosetKeys, call.geosetValues, call.layerFiles, call.layerMeta, call.tintColours, call.extraModels)
        Native.body(h, id, body.morph, body.k, body.fold)
    }

    override fun removeActor(key: String) = post { h, ids -> ids.remove(key)?.let { Native.removeActor(h, it) }; bodies.remove(key) }

    override fun removeAll() = post { h, ids ->
        for (id in ids.values) Native.removeActor(h, id)
        ids.clear()
        bodies.clear()
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

    override fun anim(key: String, anim: Int, loop: Boolean, nowMs: Int) =
        post { h, ids -> ids[key]?.let { Native.anim(h, it, anim, loop, nowMs) } }

    /** Releases the renderer once the view is gone. */
    fun dispose() {
        queueEvent { Native.destroy(handle) }
    }
}
