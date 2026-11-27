package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

// Pure Kotlin (JVM-tested: InteractModeTest): Interact mode's camera. It centres on the selected NPC: the orbit's pivot is at her
// chest (or, in the Face and Legs views, at her head and her thighs), a drag on the background turns the view round her (the yaw
// without limit, the pitch within a range), a pinch zooms (continuous, in body heights), and four presets (Face, Torso, Legs, Full)
// and "Back to overview" put the distance, the aim and the angles where they were. It tracks the same body points as JOI mode and Chat
// mode ([JoiBody]); the film frame is off and her depth of field is the focus (LiveScene).

/** The views the buttons go to: [distance] in body heights, [aim] where along her body the view looks (0 the head, 1 the floor), [fov]. */
enum class InteractView(val label: String, val distance: Float, val aim: Float, val fov: Float) {
    FACE("Face", 0.42f, 0.0f, 32f),
    TORSO("Torso", 0.95f, 0.22f, 36f),
    LEGS("Legs", 1.15f, 0.62f, 38f),
    FULL("Full", 2.3f, 0.5f, 40f),
}

object InteractZoom {
    const val MIN_DISTANCE = 0.3f
    const val MAX_DISTANCE = 4.5f
    const val MIN_PITCH = -0.6f
    const val MAX_PITCH = 1.1f
    /** One finger's drag across the whole screen turns this much (radians). */
    const val ORBIT_PER_SCREEN = 3.4f
    const val PITCH_PER_SCREEN = 1.6f

    fun clampDistance(d: Float): Float = if (d.isFinite()) d.coerceIn(MIN_DISTANCE, MAX_DISTANCE) else InteractView.FULL.distance
    fun clampPitch(p: Float): Float = if (p.isFinite()) p.coerceIn(MIN_PITCH, MAX_PITCH) else 0f

    /** A pinch's [scale] (above 1: the fingers spread, the picture draws closer) applied to the [distance]. */
    fun pinch(distance: Float, scale: Float): Float = if (scale > 0f && scale.isFinite()) clampDistance(distance / scale) else clampDistance(distance)

    /** A drag of [dx], [dy] pixels over a [width] x [height] view: the new yaw and pitch (a drag to the right brings the view round to the right). */
    fun drag(yaw: Float, pitch: Float, dx: Float, dy: Float, width: Float, height: Float): Pair<Float, Float> {
        val y = if (width > 0f) wrap(yaw - dx / width * ORBIT_PER_SCREEN) else yaw
        val p = if (height > 0f) clampPitch(pitch + dy / height * PITCH_PER_SCREEN) else pitch
        return y to p
    }

    fun wrap(a: Float): Float { var x = a; val tau = 2f * PI.toFloat(); while (x > PI) x -= tau; while (x < -PI) x += tau; return x }

    /** The view nearest to [distance] (for the chip that stays lit). */
    fun nearest(distance: Float): InteractView = InteractView.entries.minByOrNull { kotlin.math.abs(ln(it.distance / distance.coerceAtLeast(1e-3f))) } ?: InteractView.FULL
}

class InteractCamera {
    /** Whether the eye sees the target (no wall between): the camera draws in when not. */
    @Volatile var visible: ((Vec3, Vec3) -> Boolean)? = null

    /** The player's view: the angle round her from straight in front (radians, 0 in front), the pitch, the distance (body heights), the aim and the field of view. */
    @Volatile var yaw = 0f
    @Volatile var pitch = 0.05f
    @Volatile var distance = InteractView.FULL.distance
    @Volatile var aim = InteractView.FULL.aim
    @Volatile var baseFov = InteractView.FULL.fov
    /** The share of the screen's height the bottom stack covers (the dock): she is lifted above it. */
    @Volatile var panelInset = 0f

    var eyeX = 0f; private set
    var eyeY = 0f; private set
    var eyeZ = 0f; private set
    var atX = 0f; private set
    var atY = 0f; private set
    var atZ = 0f; private set
    var fov = InteractView.FULL.fov; private set
    private var started = false
    private var facingS = 0f
    private var yawS = 0f; private var pitchS = 0f; private var distS = 0f; private var aimS = 0f; private var fovS = 0f
    private var fx = 0f; private var fy = 0f; private var fz = 0f
    private var avoid = 1f
    private var avoidClock = 0f

    /** Starts afresh on the next update (entering the mode); the view is the whole of her. */
    fun restart() { goTo(InteractView.FULL); yaw = 0f; pitch = 0.05f; started = false; avoid = 1f; avoidClock = 0f }

    /** A preset: the distance, the aim and the field of view (the angles stay where the player turned them). */
    fun goTo(v: InteractView) { distance = v.distance; aim = v.aim; baseFov = v.fov }

    /** "Back to overview": the whole of her from the front. */
    fun overview() { goTo(InteractView.FULL); yaw = 0f; pitch = 0.05f }

    /** A drag on the background. */
    fun orbit(dx: Float, dy: Float, width: Float, height: Float) { val (y, p) = InteractZoom.drag(yaw, pitch, dx, dy, width, height); yaw = y; pitch = p }

    /** A pinch. */
    fun pinch(scale: Float) { distance = InteractZoom.pinch(distance, scale) }

    /** Which preset the view is on now (null: in between, after a pinch). */
    fun current(): InteractView? = InteractView.entries.firstOrNull { kotlin.math.abs(ln(it.distance / distance)) < 0.05f && kotlin.math.abs(it.aim - aim) < 0.05f }

    private fun ease(dt: Float, w: Float) = 1f - exp(-w * dt)

    /** The point the view looks at and orbits: along her body from the head ([aim] 0) over the chest to the floor (1). */
    fun pivot(b: JoiBody, a: Float = aim): Vec3 {
        val floor = Vec3(b.head.x, b.head.y, b.floorZ)
        return if (a <= 0.22f) b.head.lerp(b.chest, (a / 0.22f).coerceIn(0f, 1f)) else b.chest.lerp(floor, ((a - 0.22f) / 0.78f).coerceIn(0f, 1f))
    }

    /** One frame, [dt] seconds after the last, with her [body]; false (nothing written): not usable. */
    fun update(dt: Float, body: JoiBody, aspect: Float): Boolean {
        if (!(body.height > 0.2f) || !body.height.isFinite()) return false
        val step = if (dt.isFinite()) dt.coerceIn(0f, 0.1f) else 0f
        val first = !started
        if (first) {
            started = true; facingS = body.facing; yawS = yaw; pitchS = pitch; distS = distance; aimS = aim; fovS = baseFov
        }
        val k = ease(step, 7f)
        distS = (distS.pow(1f - k) * InteractZoom.clampDistance(distance).pow(k))
        aimS += (aim - aimS) * k
        fovS += (baseFov - fovS) * k
        pitchS += (InteractZoom.clampPitch(pitch) - pitchS) * k
        yawS += InteractZoom.wrap(yaw - yawS) * k
        // her facing, smoothed (she may turn: the view turns with her unless the player turned it)
        facingS += InteractZoom.wrap(body.facing - facingS) * ease(step, 3f)
        val pv = pivot(body, aimS)
        // the bottom stack's share lifts her: the aim drops by that share of the half picture
        val base = distS * body.height
        val lift = panelInset.coerceIn(0f, 0.6f) * base * kotlin.math.tan(fovS * 0.5f * 0.017453292f)
        val focus = Vec3(pv.x, pv.y, pv.z - lift)
        val want = facingS + yawS
        avoidClock -= step
        if (first || avoidClock <= 0f) {
            avoidClock = 0.5f
            val v = visible
            avoid = if (v == null || v(CinematicFraming.eye(focus, want, pitchS, base), focus)) 1f
            else listOf(0.8f, 0.65f, 0.5f, 0.4f).firstOrNull { f -> v(CinematicFraming.eye(focus, want, pitchS, base * f), focus) } ?: 0.4f
        }
        val eye = CinematicFraming.eye(focus, want, pitchS, base * avoid)
        if (first) { fx = focus.x; fy = focus.y; fz = focus.z; eyeX = eye.x; eyeY = eye.y; eyeZ = eye.z; fov = fovS }
        else {
            val e = ease(step, 9f)
            fx += (focus.x - fx) * e; fy += (focus.y - fy) * e; fz += (focus.z - fz) * e
            eyeX += (eye.x - eyeX) * e; eyeY += (eye.y - eyeY) * e; eyeZ += (eye.z - eyeZ) * e
            fov += (fovS - fov) * e
        }
        atX = fx; atY = fy; atZ = fz
        return true
    }
}
