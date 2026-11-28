package com.logie.wifecraft.ui

import androidx.compose.ui.geometry.Offset
import com.logie.wifecraft.anim.CharacterSize
import com.logie.wifecraft.engine.FrameDriver
import com.logie.wifecraft.engine.FurniturePose
import com.logie.wifecraft.engine.SceneView
import com.logie.wifecraft.simulation.InteractCamera
import com.logie.wifecraft.simulation.JoiBody
import com.logie.wifecraft.simulation.SceneSimulation
import com.logie.wifecraft.simulation.SecretVoid
import com.logie.wifecraft.story.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// The hidden mode's frame (ui/SecretScene.kt owns the screen): what the GL thread does each frame in the void. She stays where she is, lowered so her
// knees rest on the floor; the Interact camera (simulation/InteractCamera) orbits her; the depth of field's band holds her; the breathing is the
// engine's own body language, tiny. No simulation, no walking, nobody else.

/** What the screen tells the frame (any thread) and what the frame tells back. */
internal class SecretFrameState {
    /** How far she is lowered, in body units (SecretKneel.floorDrop). */
    @Volatile var dropUnits = 0f
    @Volatile var breathing = true
    @Volatile var dof = true
    /** The share of the screen's height the dock covers: she is lifted above it. */
    @Volatile var panel = 0.2f
    /** The camera as last set: eye x, y, z, at x, y, z, fov (degrees); the floor shadow is drawn from it. */
    @Volatile var cam = FloatArray(7)
    @Volatile var camSet = false
    /** Her height as the framing's unit (yards), set each frame. */
    @Volatile var height = 1.7f
}

internal class SecretFrame(
    private val view: SceneView, private val key: String, private val camera: InteractCamera, private val st: SecretFrameState,
) : FrameDriver {
    private val body = JoiBody()
    private val pts = FloatArray(9)
    private var last = 0L

    override fun drive(nowNanos: Long) {
        val dt = if (last == 0L) 0f else ((nowNanos - last) / 1e9f).coerceIn(0f, 0.1f)
        last = nowNanos
        val scale = view.bodyScale(key) * CharacterSize.active
        val height = 1.7f * scale
        val drop = st.dropUnits * SceneSimulation.HUMAN_BODY_UNIT * scale
        view.glPlace(key, 0f, 0f, -drop, 0f, FurniturePose.FLOOR, 0f, 0f, -drop)
        val tracked = view.glBonePoints(key, pts) && pts.all { it.isFinite() }
        if (tracked) body.set(Vec3(pts[0], pts[1], pts[2]), Vec3(pts[3], pts[4], pts[5]), Vec3(pts[6], pts[7], pts[8]), 0f, height, 0f, true)
        else body.set(Vec3(0f, 0f, 1.62f * scale - drop), Vec3(0f, 0f, 1.25f * scale - drop), Vec3(0f, 0f, 0.95f * scale - drop), 0f, height, 0f, false)
        st.height = height
        camera.panelInset = st.panel
        val aspect = if (view.height > 0) view.width.toFloat() / view.height else 1f
        if (camera.update(dt, body, aspect)) {
            view.glCamera(camera.eyeX, camera.eyeY, camera.eyeZ, camera.atX, camera.atY, camera.atZ, camera.fov)
            val c = st.cam
            c[0] = camera.eyeX; c[1] = camera.eyeY; c[2] = camera.eyeZ; c[3] = camera.atX; c[4] = camera.atY; c[5] = camera.atZ; c[6] = camera.fov
            st.camSet = true
        }
        val (near, far, strength) = SecretVoid.focusBand(st.dof, camera.distance, height)
        view.glFocus(near, far, strength)
        view.glMotionParams(SecretVoid.breathing(st.breathing), 0f, false)
        view.glMotion(key, if (st.breathing) 0.2f else 0f, null)
    }
}

/** Where a point of the world falls on a [w] x [h] screen for the camera [c] (SecretFrameState.cam, z up); null: behind the eye. Pure. */
internal fun secretProject(c: FloatArray, w: Float, h: Float, x: Float, y: Float, z: Float): Offset? {
    var fx = c[3] - c[0]; var fy = c[4] - c[1]; var fz = c[5] - c[2]
    val fl = sqrt(fx * fx + fy * fy + fz * fz)
    if (fl < 1e-4f) return null
    fx /= fl; fy /= fl; fz /= fl
    // right = forward x up (0, 0, 1)
    var rx = fy; var ry = -fx
    val rl = sqrt(rx * rx + ry * ry)
    if (rl < 1e-4f) return null
    rx /= rl; ry /= rl
    // up = right x forward
    val ux = ry * fz; val uy = -rx * fz; val uz = rx * fy - ry * fx
    val dx = x - c[0]; val dy = y - c[1]; val dz = z - c[2]
    val depth = dx * fx + dy * fy + dz * fz
    if (depth < 0.05f) return null
    val th = tan(Math.toRadians(c[6] / 2.0)).toFloat()
    val aspect = w / h.coerceAtLeast(1f)
    val nx = (dx * rx + dy * ry) / (depth * th * aspect)
    val ny = (dx * ux + dy * uy + dz * uz) / (depth * th)
    return Offset((nx + 1f) / 2f * w, (1f - ny) / 2f * h)
}

/** The floor shadow's outline on the screen: [n] points round a circle of [radius] on the floor at the origin (empty when the eye is under the floor). */
internal fun secretShadowRing(c: FloatArray, w: Float, h: Float, radius: Float, n: Int = 28): List<Offset> {
    if (!(c[2] > 0.02f) || !(radius > 0f)) return emptyList()
    val out = ArrayList<Offset>(n)
    for (i in 0 until n) {
        val a = 2f * Math.PI.toFloat() * i / n
        out += secretProject(c, w, h, cos(a) * radius, sin(a) * radius, 0f) ?: return emptyList()
    }
    return out
}

internal fun secretRingSize(ring: List<Offset>): Float = ring.maxOfOrNull { abs(it.x - ring.first().x) + abs(it.y - ring.first().y) } ?: 0f
