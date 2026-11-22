package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

// Pure Kotlin: Chat mode's camera (JVM-tested: ChatModeTest). A conversation framing as if the player stood in front of her: at her
// head's height, straight in front (inside a few degrees of her facing), head and shoulders to the waist, with a slow drift (a few
// degrees round, a few percent in) and no cuts. It tracks the same body points as JOI mode's camera ([JoiBody]: her bones as the engine
// drew them, or estimates). A creature (Sindragosa) is seen from in front of her head, a little above it. Stage coordinates, Z up.

/** The conversation framing's numbers, in body heights ([JoiBody.height]). */
object ChatFraming {
    /** The eye's distance from the focus, in body heights, and the field of view (vertical, degrees). The eye is at her eye level. */
    const val DISTANCE = 1.0f
    const val FOV = 34f
    /** Where the eyes are under the head bone (the bone sits at the crown on the models: 1.695 yards on a 1.709 woman), in body heights. */
    const val EYES_UNDER_HEAD = 0.04f
    /** The most the camera strays (radians) from straight in front of her, with the drift. */
    const val MAX_YAW = 0.12f
    /** A creature's eye: distance and height over the head, in her own scale units (see [JoiOrbit.at]); the field of view. */
    const val CREATURE_DISTANCE = 3.0f
    const val CREATURE_LIFT = 0.45f
    const val CREATURE_FOV = 36f
    /** The creature's look-at sits this far under her head (her scale units), so the head is in the upper part of the picture (the chat is under it). */
    const val CREATURE_DROP = 0.2f

    /**
     * What the shot looks at: a point most of the way from the head to the chest, so her face sits in the upper part of the picture (the
     * chat is under her) and the picture reaches the waist.
     */
    fun focus(b: JoiBody): Vec3 = b.head.lerp(b.chest, 0.70f)

    /** Her eye level: the eye stands at her eyes' height. */
    fun eyeZ(b: JoiBody): Float = b.head.z - EYES_UNDER_HEAD * b.height

    /** The points the picture must hold (with a margin): the crown and the chest. */
    fun mustShow(b: JoiBody): List<Vec3> = listOf(Vec3(b.head.x, b.head.y, b.head.z + 0.04f * b.height), b.chest)
}

class ChatCamera(seed: Long = Random.nextLong()) {
    var creature = false
    var creatureScale = 0.25f
    /** Whether the eye sees the target (no wall between): the camera draws in when not. */
    @Volatile var visible: ((Vec3, Vec3) -> Boolean)? = null

    private val phase = (seed % 628L).toFloat() / 100f
    var eyeX = 0f; private set
    var eyeY = 0f; private set
    var eyeZ = 0f; private set
    var atX = 0f; private set
    var atY = 0f; private set
    var atZ = 0f; private set
    var fov = ChatFraming.FOV; private set
    /** The yaw the camera looks from. */
    var yaw = 0f; private set
    private var t = 0f
    private var started = false
    private var facingS = 0f
    private var fx = 0f; private var fy = 0f; private var fz = 0f
    private var avoidScale = 1f
    private var avoidYaw = 0f
    private var avoidClock = 0f

    /** Starts afresh on the next update (entering the mode). */
    fun restart() { started = false; t = 0f; avoidScale = 1f; avoidYaw = 0f; avoidClock = 0f }

    private fun ease(dt: Float, w: Float) = 1f - exp(-w * dt)

    /** One frame, [dt] seconds after the last, with the speaker's [body]; false (nothing written): not usable. */
    fun update(dt: Float, body: JoiBody, aspect: Float): Boolean {
        if (!(body.height > 0.2f) || !body.height.isFinite()) return false
        val step = if (dt.isFinite()) dt.coerceIn(0f, 0.1f) else 0f
        val first = !started
        if (first) { started = true; facingS = body.facing }
        t += step
        // her facing, smoothed (she is turned to face the camera: the camera stays in front of her)
        facingS += JoiOrbit.wrap(body.facing - facingS) * ease(step, 3f)
        val tau = 2f * PI.toFloat()
        // the drift: a slow sway of the yaw, a breath of distance and of height
        val drift = 0.06f * sin(tau * t / 19f + phase) + 0.025f * sin(tau * t / 7.3f + phase * 1.3f)
        val scaleDrift = 1f + 0.025f * sin(tau * t / 23f + phase * 0.7f)
        val pitchDrift = 0.012f * sin(tau * t / 13f + phase * 1.9f)
        val focus: Vec3
        val base: Float
        val pitch: Float
        val fovBase: Float
        if (creature) {
            val k = creatureScale / 0.25f
            focus = Vec3(body.head.x, body.head.y, body.head.z - ChatFraming.CREATURE_DROP * k)
            base = ChatFraming.CREATURE_DISTANCE * k
            pitch = asin(((ChatFraming.CREATURE_LIFT + ChatFraming.CREATURE_DROP) / ChatFraming.CREATURE_DISTANCE).coerceIn(-0.9f, 0.9f)) + pitchDrift
            fovBase = ChatFraming.CREATURE_FOV
        } else {
            focus = ChatFraming.focus(body)
            base = ChatFraming.DISTANCE * body.height
            pitch = asin(((ChatFraming.eyeZ(body) - focus.z) / base).coerceIn(-0.5f, 0.5f)) + pitchDrift
            fovBase = ChatFraming.FOV
        }
        // a wall in the way: draw in, or lean to the side, tried now and then (the clearance test is not free)
        avoidClock -= step
        if (first || avoidClock <= 0f) {
            avoidClock = 0.5f
            val v = visible
            val want = facingS + drift
            if (v == null || v(CinematicFraming.eye(focus, want, pitch, base * scaleDrift), focus)) { avoidScale = 1f; avoidYaw = 0f }
            else {
                val tries = listOf(0.8f to 0f, 0.65f to 0f, 0.8f to 0.3f, 0.8f to -0.3f, 0.65f to 0.4f, 0.65f to -0.4f, 0.5f to 0f)
                var chosen = tries.last()
                for (o in tries) if (v(CinematicFraming.eye(focus, want + o.second, pitch, base * scaleDrift * o.first), focus)) { chosen = o; break }
                avoidScale = chosen.first; avoidYaw = chosen.second
            }
        }
        yaw = facingS + (drift + avoidYaw).coerceIn(-ChatFraming.MAX_YAW - 0.4f, ChatFraming.MAX_YAW + 0.4f)
        val eye = CinematicFraming.eye(focus, yaw, pitch, base * scaleDrift * avoidScale)
        val fovNow = if (creature) fovBase else
            CinematicFraming.fit(eye, focus, fovBase, fovBase + 16f, aspect, ChatFraming.mustShow(body), 0.06f) ?: (fovBase + 16f)
        if (first) { fx = focus.x; fy = focus.y; fz = focus.z; eyeX = eye.x; eyeY = eye.y; eyeZ = eye.z; fov = fovNow }
        else {
            val k = ease(step, 6f)
            fx += (focus.x - fx) * k; fy += (focus.y - fy) * k; fz += (focus.z - fz) * k
            eyeX += (eye.x - eyeX) * k; eyeY += (eye.y - eyeY) * k; eyeZ += (eye.z - eyeZ) * k
            fov += (fovNow - fov) * k
        }
        atX = fx; atY = fy; atZ = fz
        return true
    }
}
