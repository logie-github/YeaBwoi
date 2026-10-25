package com.logie.wifecraft.engine

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.SystemClock
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

/**
 * The 3D view behind the story. The story side posts changes ([post]) and each frame's camera and
 * placements ([frame]); the GL thread applies them and draws. People are named by the story's
 * keys ("alleria", "you"...); the renderer's own ids stay on the GL thread.
 */
class SceneView(context: Context, filesDir: String) : GLSurfaceView(context) {

    /** A change to the scene, run on the GL thread with the renderer's handle and ids. */
    fun interface Change {
        fun apply(scene: Long, ids: MutableMap<String, Int>)
    }

    @Volatile var frame: Pair<CameraPose, Map<String, Placement>>? = null
    @Volatile var ready = false
        private set

    /** Dalaran is loaded once and kept for every story after. */
    val cityLoaded = java.util.concurrent.atomic.AtomicBoolean(false)

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

    /** Releases the renderer once the view is gone. */
    fun dispose() {
        queueEvent { Native.destroy(handle) }
    }
}
