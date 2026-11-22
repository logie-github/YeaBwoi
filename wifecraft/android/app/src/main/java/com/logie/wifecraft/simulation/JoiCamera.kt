package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// Pure Kotlin: JOI mode's camera (JVM-tested: JoiCameraTest). The speaker's own body points (head, chest, pelvis: her bones
// as the engine drew them, or the director's estimates) are tracked every frame, so the picture follows her animations;
// the three framings (Face, Torso, Legs up) are sets of numbers in body heights, the camera drifts slowly and cuts now and then
// to another angle (or, in Auto, another framing), always standing in front of her inside a cone of her facing. A creature
// (Sindragosa) is circled instead: slow orbits of varied radius and height round her head. Stage coordinates, Z up; a yaw is
// radians about Z from +X, an eye is at focus + (cos pitch cos yaw, cos pitch sin yaw, sin pitch) * distance (CinematicFraming.eye).

/** The speaker's body as the camera sees it this frame (stage coordinates, yards). */
class JoiBody {
    var head = Vec3(0f, 0f, 1.62f)
    var chest = Vec3(0f, 0f, 1.25f)
    var pelvis = Vec3(0f, 0f, 0.95f)
    /** Where she faces (stage yaw). */
    var facing = 0f
    /** Her standing height (yards): the framings' unit. */
    var height = 1.7f
    var floorZ = 0f
    /** The points came from the skeleton as drawn (true) or from estimates by posture. */
    var tracked = false

    fun set(head: Vec3, chest: Vec3, pelvis: Vec3, facing: Float, height: Float, floorZ: Float, tracked: Boolean) {
        this.head = head; this.chest = chest; this.pelvis = pelvis; this.facing = facing; this.height = height; this.floorZ = floorZ; this.tracked = tracked
    }
}

/** One shot: its framing, where it looks from (relative to her facing), how it drifts and how long it lasts. */
data class JoiShot(
    val framing: JoiFraming, val yawOffset: Float, val pitchBias: Float, val distScale: Float, val seconds: Float,
    /** The slow drift over the shot: the yaw turned, the distance scaled. */
    val driftYaw: Float, val driftDist: Float,
    val cut: Boolean = true,
)

/** The framings' numbers, in body heights ([JoiBody.height]) from her points: what a Face, a Torso and a Legs-up shot are. */
object JoiFramings {
    /** The camera stays inside this angle (radians) of her facing: in front of her, never behind. */
    const val CONE = 0.87f   // 50 degrees

    class Spec(val distance: Float, val fov: Float, val pitch: Float)

    fun spec(f: JoiFraming): Spec = when (f) {
        JoiFraming.FACE -> Spec(0.88f, 26f, 0.04f)
        JoiFraming.TORSO -> Spec(1.05f, 30f, 0.04f)
        JoiFraming.LEGS -> Spec(1.20f, 40f, -0.40f)
        JoiFraming.AUTO -> Spec(1.05f, 30f, 0.04f)
    }

    /** What the shot looks at: the head (a little under it), the chest and head's middle, the hip and chest's upper third. */
    fun focus(f: JoiFraming, b: JoiBody): Vec3 = when (f) {
        JoiFraming.FACE -> b.head.lerp(b.chest, 0.10f)
        JoiFraming.TORSO, JoiFraming.AUTO -> b.chest.lerp(b.head, 0.45f)
        JoiFraming.LEGS -> b.pelvis.lerp(b.chest, 0.30f)
    }

    /** The points a framing must have in the picture (with a margin), to test its fit: Face the head, Torso head to hip, Legs up thigh to head. */
    fun mustShow(f: JoiFraming, b: JoiBody): List<Vec3> {
        // (the head bone sits at the neck's top: the crown is about a tenth of her height over it)
        val crown = Vec3(b.head.x, b.head.y, b.head.z + 0.1f * b.height)
        return when (f) {
            JoiFraming.FACE -> listOf(crown)
            JoiFraming.TORSO, JoiFraming.AUTO -> listOf(crown, b.chest)
            JoiFraming.LEGS -> listOf(crown, b.chest, b.pelvis)
        }
    }

    /** The eye's height over the floor for Legs up (yards): knee height, so the camera looks up the body. */
    fun legsEyeHeight(b: JoiBody) = b.floorZ + 0.18f * b.height
}

/** Cut scheduling: what the next shot is, from the framing chosen (Auto mixes), the last shot and chance. Deterministic from the seed. */
class JoiCutPlan(seed: Long = Random.nextLong()) {
    private val rnd = Random(seed)
    var count = 0
        private set

    /** How long a shot of [f] lasts (seconds). */
    fun seconds(f: JoiFraming): Float = when (f) {
        JoiFraming.FACE -> 3.5f + 2.5f * rnd.nextFloat()
        JoiFraming.TORSO -> 4.5f + 3.5f * rnd.nextFloat()
        JoiFraming.LEGS -> 4f + 3f * rnd.nextFloat()
        JoiFraming.AUTO -> 4f
    }

    /** The framing for the next shot: [chosen] unless it is Auto, which picks by weight and never the same twice running. */
    fun framingFor(chosen: JoiFraming, last: JoiFraming?): JoiFraming {
        if (chosen != JoiFraming.AUTO) return chosen
        val opts = listOf(JoiFraming.FACE to 0.34f, JoiFraming.TORSO to 0.40f, JoiFraming.LEGS to 0.26f).filter { it.first != last }
        var r = rnd.nextFloat() * opts.sumOf { it.second.toDouble() }.toFloat()
        for ((f, w) in opts) { r -= w; if (r <= 0f) return f }
        return opts.last().first
    }

    /**
     * The next shot after [last] for [chosen]: another angle at least [MIN_TURN] radians from the last (and, in Auto, another
     * framing), inside the cone, with its own distance, small pitch change and drift.
     */
    fun next(chosen: JoiFraming, last: JoiShot?): JoiShot {
        count++
        val f = framingFor(chosen, last?.framing)
        var off = 0f
        var tries = 0
        do {
            off = (rnd.nextFloat() * 2f - 1f) * JoiFramings.CONE * 0.92f
            tries++
        } while (last != null && kotlin.math.abs(off - last.yawOffset) < MIN_TURN && tries < 8)
        if (last != null && kotlin.math.abs(off - last.yawOffset) < MIN_TURN)
            off = (if (last.yawOffset > 0f) -1f else 1f) * (MIN_TURN * 0.6f + rnd.nextFloat() * 0.3f)
        val secs = seconds(f)
        return JoiShot(f, off.coerceIn(-JoiFramings.CONE, JoiFramings.CONE), (rnd.nextFloat() * 2f - 1f) * 0.05f,
            0.92f + 0.22f * rnd.nextFloat(), secs, (rnd.nextFloat() * 2f - 1f) * 0.10f, (rnd.nextFloat() * 2f - 1f) * 0.06f)
    }

    companion object { const val MIN_TURN = 0.3f }  // about 17 degrees
}

/** The orbit round a creature's head: azimuth, radius and height as it goes (in the creature's own scale units, see [JoiOrbit.at]). */
object JoiOrbit {
    /** One pose: [az] the eye's angle round the head (stage yaw), [radius] and [height] (yards), [fov]. */
    data class Pose(val az: Float, val radius: Float, val height: Float, val fov: Float)

    /**
     * The orbit at [t] seconds for a creature [scale] times the 0.25 reference (Sindragosa's): a slow revolution (one in
     * 45 to 75 s, never still), the radius wandering 1.8 to 3.4, the height -0.2 to +1.3 over the head, each on its own
     * period. Behind the head the body is in the way: the eye rises and draws back ([facing] her heading).
     */
    fun at(t: Float, az: Float, facing: Float, scale: Float, phase: Float): Pose {
        val k = scale / 0.25f
        val wob = 0.5f + 0.5f * sin(2f * PI.toFloat() * t / 53f + phase)
        val rise = 0.5f + 0.5f * sin(2f * PI.toFloat() * t / 71f + phase * 1.7f)
        var radius = (1.8f + 1.6f * wob) * k
        var height = (-0.2f + 1.5f * rise) * k
        val behind = behind(az, facing)
        radius = max(radius, (1f - behind) * radius + behind * 3.3f * k)
        height = max(height, behind * 1.8f * k)
        val fov = 32f + 8f * (1f - wob)
        return Pose(az, radius, height, fov)
    }

    /** 0 in front of the head, 1 behind it (from about 100 degrees round): where the body hides it. */
    fun behind(az: Float, facing: Float): Float = smooth((kotlin.math.abs(wrap(az - facing)) - 1.75f) / 0.85f)

    /**
     * Radians per second at [t]: a revolution every 45 to 75 s in front of the head; behind it ([behind] 1) the eye passes 2.6
     * times as fast (nothing of her head is seen from there: the neck and the wings are).
     */
    fun speed(t: Float, behind: Float = 0f): Float = (1f + 1.6f * behind.coerceIn(0f, 1f)) * 2f * PI.toFloat() / (60f + 15f * sin(2f * PI.toFloat() * t / 90f))

    fun wrap(a: Float): Float { var x = a; val tau = 2f * PI.toFloat(); while (x > PI) x -= tau; while (x < -PI) x += tau; return x }
    private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }
}

/**
 * JOI mode's camera. [update] each frame with the speaker's [JoiBody]; the pose is in [eyeX]..[fov]. [creature]: the orbit
 * round her head instead of a framing ([framing] is ignored); [creatureScale]: her model scale ([CreatureSpec.scale]).
 * [visible] (eye, target): whether the eye sees the target (no wall between); the camera tries other angles when not.
 */
class JoiCamera(seed: Long = Random.nextLong()) {
    var framing = JoiFraming.AUTO
    var creature = false
    var creatureScale = 0.25f
    @Volatile var visible: ((Vec3, Vec3) -> Boolean)? = null

    private val plan = JoiCutPlan(seed)
    private val rnd = Random(seed xor 0x9E3779B97F4A7C15uL.toLong())
    private val phase = (seed % 628L).toFloat() / 100f

    var shot: JoiShot? = null
        private set
    var shotClock = 0f
        private set
    val history = ArrayList<JoiShot>()
    var eyeX = 0f; private set
    var eyeY = 0f; private set
    var eyeZ = 0f; private set
    var atX = 0f; private set
    var atY = 0f; private set
    var atZ = 0f; private set
    var fov = 40f; private set
    /** The yaw the camera looks from, the orbit's azimuth, and the time on the orbit. */
    var yaw = 0f; private set
    var orbitAz = 0f; private set
    var orbitPose: JoiOrbit.Pose? = null; private set
    private var orbitT = 0f
    private var started = false
    private var facingS = 0f
    private var fx = 0f; private var fy = 0f; private var fz = 0f
    private var lastFraming: JoiFraming? = null
    private var cutNow = false

    /** Starts afresh on the next update (entering the mode). */
    fun restart() { started = false; shot = null; history.clear(); orbitT = 0f }

    /** Forces a cut at the next update (the host changed the framing). */
    fun cut() { shot = null }

    private fun ease(dt: Float, w: Float) = 1f - exp(-w * dt)

    /** One frame, [dt] seconds after the last; false (nothing written): not usable. */
    fun update(dt: Float, body: JoiBody, aspect: Float): Boolean {
        if (!(body.height > 0.2f) || !body.height.isFinite()) return false
        val step = if (dt.isFinite()) dt.coerceIn(0f, 0.1f) else 0f
        return if (creature) orbit(step, body) else framed(step, body, aspect)
    }

    private fun framed(step: Float, body: JoiBody, aspect: Float): Boolean {
        val first = !started
        if (first) { started = true; facingS = body.facing; shot = null }
        // her facing, smoothed (she turns to face the camera and the camera keeps in front of her)
        facingS += JoiOrbit.wrap(body.facing - facingS) * ease(step, 3f)
        var s = shot
        if (s == null || shotClock >= s.seconds || (framing != JoiFraming.AUTO && s.framing != framing)) {
            s = plan.next(framing, if (framing != JoiFraming.AUTO && s != null && s.framing != framing) null else s)
            shot = s; shotClock = 0f; history += s; cutNow = true
        } else shotClock += step
        val u = (shotClock / s.seconds).coerceIn(0f, 1f)
        val e = u * u * (3f - 2f * u)
        val spec = JoiFramings.spec(s.framing)
        val focus = JoiFramings.focus(s.framing, body)
        var off = (s.yawOffset + s.driftYaw * e + 0.02f * sin(shotClock * 0.7f + phase)).coerceIn(-JoiFramings.CONE, JoiFramings.CONE)
        var dist = spec.distance * body.height * s.distScale * (1f + s.driftDist * e)
        var yawNow = facingS + off
        var eye = eyeOf(focus, yawNow, s, spec, body, dist)
        if (cutNow || shotClock < step + 1e-6f) {
            val v = visible
            if (v != null && !v(eye, focus)) {
                // a wall in the way: draw in, then try the other side of her front, then the middle
                val wide = JoiFramings.CONE * 0.9f
                val tries = listOf(0.8f to off, 0.65f to off, 0.8f to -off, 0.65f to -off, 0.85f to wide, 0.85f to -wide, 0.7f to 0f, 0.5f to 0f)
                for ((k, o) in tries) {
                    val d2 = dist * k; val y2 = facingS + o
                    val e2 = eyeOf(focus, y2, s, spec, body, d2)
                    if (v(e2, focus)) { eye = e2; dist = d2; off = o; yawNow = y2; break }
                }
            }
        }
        val fovNow = fitFov(spec.fov, eye, focus, aspect, s.framing, body)
        val snap = first || cutNow
        cutNow = false
        if (snap) { fx = focus.x; fy = focus.y; fz = focus.z; eyeX = eye.x; eyeY = eye.y; eyeZ = eye.z; fov = fovNow }
        else {
            val k = ease(step, 7f)
            fx += (focus.x - fx) * k; fy += (focus.y - fy) * k; fz += (focus.z - fz) * k
            eyeX += (eye.x - eyeX) * k; eyeY += (eye.y - eyeY) * k; eyeZ += (eye.z - eyeZ) * k
            fov += (fovNow - fov) * k
        }
        atX = fx; atY = fy; atZ = fz
        yaw = yawNow
        lastFraming = s.framing
        return true
    }

    private fun eyeOf(focus: Vec3, yawNow: Float, s: JoiShot, spec: JoiFramings.Spec, body: JoiBody, dist: Float): Vec3 {
        val pitch = if (s.framing == JoiFraming.LEGS) {
            val rise = (JoiFramings.legsEyeHeight(body) - focus.z) / dist
            asin(rise.coerceIn(-0.6f, -0.1f)) + s.pitchBias
        } else spec.pitch + s.pitchBias
        return CinematicFraming.eye(focus, yawNow, pitch, dist)
    }

    /** The narrowest field of view from [base] (to +16 degrees) that frames the framing's points: the shot never cuts the head off. */
    private fun fitFov(base: Float, eye: Vec3, focus: Vec3, aspect: Float, f: JoiFraming, body: JoiBody): Float =
        CinematicFraming.fit(eye, focus, base, base + 16f, aspect, JoiFramings.mustShow(f, body), 0.06f) ?: (base + 16f)

    private fun orbit(step: Float, body: JoiBody): Boolean {
        if (!started) { started = true; orbitT = 0f; orbitAz = body.facing + 0.2f; shot = null }
        orbitT += step
        orbitAz = JoiOrbit.wrap(orbitAz + JoiOrbit.speed(orbitT, JoiOrbit.behind(orbitAz, body.facing)) * step)
        val p = JoiOrbit.at(orbitT, orbitAz, body.facing, creatureScale, phase)
        orbitPose = p
        val head = body.head
        var eye = CinematicFraming.eye(head, p.az, asin((p.height / p.radius).coerceIn(-0.9f, 0.95f)), p.radius)
        val v = visible
        if (v != null && !v(eye, head)) eye = CinematicFraming.eye(head, p.az, 0.9f, p.radius * 1.2f)
        val k = if (shot == null) 1f else ease(step, 6f)
        if (shot == null) {
            shot = JoiShot(JoiFraming.FACE, 0f, 0f, 1f, Float.MAX_VALUE, 0f, 0f, cut = false)
            fx = head.x; fy = head.y; fz = head.z; eyeX = eye.x; eyeY = eye.y; eyeZ = eye.z; fov = p.fov
        } else {
            fx += (head.x - fx) * k; fy += (head.y - fy) * k; fz += (head.z - fz) * k
            eyeX += (eye.x - eyeX) * k; eyeY += (eye.y - eyeY) * k; eyeZ += (eye.z - eyeZ) * k
            fov += (p.fov - fov) * k
        }
        atX = fx; atY = fy; atZ = fz; yaw = p.az
        return true
    }
}
