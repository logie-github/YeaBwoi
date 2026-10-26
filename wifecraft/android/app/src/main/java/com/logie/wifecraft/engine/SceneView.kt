package com.logie.wifecraft.engine

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.logie.wifecraft.data.Content
import com.logie.wifecraft.data.Look
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

/** What the story stages on: the 3D view, or a stand-in in tests. People are named by the story's keys. */
interface Stage {
    /** Each frame's camera and placements. */
    var frame: Pair<CameraPose, Map<String, Placement>>?
    /** Dalaran is loaded once and kept for every story after. */
    val cityLoaded: java.util.concurrent.atomic.AtomicBoolean
    fun loadCity(centres: FloatArray, onDone: () -> Unit)
    fun light(values: FloatArray)
    fun addActor(key: String, look: Look)
    fun removeActor(key: String)
    fun removeAll()
    fun anim(key: String, anim: Int, loop: Boolean, nowMs: Int)
}

/**
 * The 3D view behind the story. The story side posts changes ([post]) and each frame's camera and
 * placements ([frame]); the GL thread applies them and draws. People are named by the story's
 * keys ("alleria", "you"...); the renderer's own ids stay on the GL thread.
 */
class SceneView(context: Context, filesDir: String) : GLSurfaceView(context), Stage {

    @Volatile override var frame: Pair<CameraPose, Map<String, Placement>>? = null
    @Volatile var ready = false
        private set

    override val cityLoaded = java.util.concurrent.atomic.AtomicBoolean(false)

    private val changes = ConcurrentLinkedQueue<Change>()
    private val handle = Native.create(filesDir)
    private val ids = mutableMapOf<String, Int>()
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
        Native.loadCity(h, Content.CITY_ROOT, Content.CITY_GROUPS, centres, Content.PROP_RADIUS)
        cityLoaded.set(true)
        onDone()
    }

    override fun light(values: FloatArray) = post { h, _ -> Native.light(h, values) }

    override fun addActor(key: String, look: Look) = post { h, ids ->
        ids.remove(key)?.let { Native.removeActor(h, it) }
        val id = Native.addActor(
            h, look.model,
            look.textures.keys.toIntArray(), look.textures.values.toIntArray(),
            look.geosetGroups.keys.toIntArray(), look.geosetGroups.values.toIntArray(), look.scale,
        )
        if (id > 0) {
            ids[key] = id
            for (att in look.attachments) Native.attach(h, id, att.point, att.model, intArrayOf(2), intArrayOf(att.texture))
        }
    }

    override fun removeActor(key: String) = post { h, ids -> ids.remove(key)?.let { Native.removeActor(h, it) } }

    override fun removeAll() = post { h, ids ->
        for (id in ids.values) Native.removeActor(h, id)
        ids.clear()
    }

    override fun anim(key: String, anim: Int, loop: Boolean, nowMs: Int) =
        post { h, ids -> ids[key]?.let { Native.anim(h, it, anim, loop, nowMs) } }

    /** Releases the renderer once the view is gone. */
    fun dispose() {
        queueEvent { Native.destroy(handle) }
    }
}
