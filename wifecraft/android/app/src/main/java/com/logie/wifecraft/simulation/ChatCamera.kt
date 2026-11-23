package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan
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

/**
 * The user's zoom in Chat mode, pure maths (JVM-tested: ChatZoomTest). A zoom is a number from 0 (close: head and shoulders) to 1 (the
 * widest: the room round her); the eye's distance grows geometrically with it, from [NEAR] to [FAR] body heights, so equal steps feel
 * equal. [DEFAULT] is the old fixed framing (a body height away). Further out the aim moves from her head and chest toward the middle of
 * her body, and the bottom panel's share of the screen ([panelAim]) lifts her above it.
 */
object ChatZoom {
    const val MIN = 0f
    const val MAX = 1f
    /** The distance (body heights) at zoom 0 and zoom 1: 0.55 is the face and shoulders, 4.5 body heights is about 7.6 yards for a 1.7 yard woman. */
    const val NEAR = 0.55f
    const val FAR = 4.5f
    /** The framing Chat mode always had: one body height, head and shoulders to the waist. */
    val DEFAULT = ln(1.0f / NEAR) / ln(FAR / NEAR)
    /** Where the body is fully in the picture (Full body). */
    const val FULL_BODY = 0.78f
    /** One finger's drag across the whole screen width turns this much (radians); the turn is limited to [MAX_ORBIT]. */
    const val ORBIT_PER_SCREEN = 2.2f
    /** The most the camera may be turned round her (60 degrees, radians). */
    const val MAX_ORBIT = 1.0472f
    /** The most of the screen's height the bottom panel may be taken to cover. */
    const val MAX_INSET = 0.6f

    data class Preset(val label: String, val zoom: Float)
    val PRESETS = listOf(Preset("Close", 0f), Preset("Medium", DEFAULT), Preset("Wide", 0.55f), Preset("Full body", FULL_BODY))

    fun clamp(z: Float): Float = if (z.isFinite()) z.coerceIn(MIN, MAX) else DEFAULT
    fun clampOrbit(o: Float): Float = if (o.isFinite()) o.coerceIn(-MAX_ORBIT, MAX_ORBIT) else 0f
    fun clampInset(p: Float): Float = if (p.isFinite()) p.coerceIn(0f, MAX_INSET) else 0f

    /** The eye's distance in body heights at [zoom]. */
    fun distance(zoom: Float): Float = NEAR * (FAR / NEAR).pow(clamp(zoom))

    /** The zoom for a distance in body heights (the inverse of [distance]). */
    fun zoomOf(distance: Float): Float = clamp(ln(distance.coerceAtLeast(1e-3f) / NEAR) / ln(FAR / NEAR))

    /** A pinch's [scale] (above 1: the fingers spread, the picture draws closer) applied to [zoom]. */
    fun pinch(zoom: Float, scale: Float): Float = if (scale > 0f && scale.isFinite()) clamp(zoom - ln(scale) / ln(FAR / NEAR)) else clamp(zoom)

    /** One drag of [dx] pixels over a view [width] pixels wide, turning the [orbit] (positive dx drags the view round to the right). */
    fun drag(orbit: Float, dx: Float, width: Float): Float = if (width > 0f) clampOrbit(orbit - dx / width * ORBIT_PER_SCREEN) else clampOrbit(orbit)

    /** How far the aim has moved from the head-and-chest toward the middle of her body, 0 at the default framing to 1 at Full body (smooth). */
    fun bodyMix(zoom: Float): Float {
        val t = ((clamp(zoom) - DEFAULT) / (FULL_BODY - DEFAULT)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * How far (world units) the aim is dropped so she stands above a bottom panel covering [inset] of the screen's height: the half
     * picture is distance x tan(fov/2), the visible part's middle is [inset] of it above the centre. Only when zoomed out of the
     * default framing (which already holds her face in the upper third).
     */
    fun panelAim(zoom: Float, inset: Float, distance: Float, fovDeg: Float): Float =
        clampInset(inset) * bodyMix(zoom) * distance * tan(fovDeg * 0.5f * 0.017453292f)

    /** The field of view for [zoom]: the framing's own at the default and closer, a little wider far out (the room round her). */
    fun fov(zoom: Float, base: Float): Float = base + 6f * ((clamp(zoom) - FULL_BODY) / (1f - FULL_BODY)).coerceIn(0f, 1f)

    /** The points the picture must hold at [zoom]: the crown (close), with the chest (default and further), with the feet (from Wide). */
    fun mustShow(b: JoiBody, zoom: Float): List<Vec3> {
        val z = clamp(zoom)
        val crown = Vec3(b.head.x, b.head.y, b.head.z + 0.04f * b.height)
        return when {
            z < DEFAULT - 0.05f -> listOf(crown)
            z < 0.5f -> listOf(crown, b.chest)
            else -> listOf(crown, Vec3(b.head.x, b.head.y, b.floorZ + 0.02f * b.height))
        }
    }
}

class ChatCamera(seed: Long = Random.nextLong()) {
    var creature = false
    var creatureScale = 0.25f
    /** Whether the eye sees the target (no wall between): the camera draws in when not. */
    @Volatile var visible: ((Vec3, Vec3) -> Boolean)? = null

    /** The user's zoom ([ChatZoom]): kept for the whole session, even across leaving and starting a chat. */
    @Volatile var zoom = ChatZoom.DEFAULT
    /** The user's turn round her (radians, [ChatZoom.MAX_ORBIT] at most): a fresh view with each chat. */
    @Volatile var orbit = 0f
    /** The share of the screen's height the chat panel covers at the bottom (0..[ChatZoom.MAX_INSET]): she is lifted above it when zoomed out. */
    @Volatile var panelInset = 0f
    private var zoomS = ChatZoom.DEFAULT
    private var orbitS = 0f

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
    fun restart() { orbit = 0f; started = false; t = 0f; avoidScale = 1f; avoidYaw = 0f; avoidClock = 0f }

    private fun ease(dt: Float, w: Float) = 1f - exp(-w * dt)

    /** One frame, [dt] seconds after the last, with the speaker's [body]; false (nothing written): not usable. */
    fun update(dt: Float, body: JoiBody, aspect: Float): Boolean {
        if (!(body.height > 0.2f) || !body.height.isFinite()) return false
        val step = if (dt.isFinite()) dt.coerceIn(0f, 0.1f) else 0f
        val first = !started
        if (first) { started = true; facingS = body.facing; zoomS = ChatZoom.clamp(zoom); orbitS = ChatZoom.clampOrbit(orbit) }
        val zoomNow = ChatZoom.clamp(zoom)
        zoomS += (zoomNow - zoomS) * ease(step, 7f)
        orbitS += (ChatZoom.clampOrbit(orbit) - orbitS) * ease(step, 7f)
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
            base = ChatFraming.CREATURE_DISTANCE * k * ChatZoom.distance(zoomS) / ChatZoom.distance(ChatZoom.DEFAULT)
            pitch = asin(((ChatFraming.CREATURE_LIFT + ChatFraming.CREATURE_DROP) / ChatFraming.CREATURE_DISTANCE).coerceIn(-0.9f, 0.9f)) + pitchDrift
            fovBase = ChatFraming.CREATURE_FOV
        } else {
            base = ChatZoom.distance(zoomS) * body.height
            val fv = ChatZoom.fov(zoomS, ChatFraming.FOV)
            val mix = ChatZoom.bodyMix(zoomS)
            val f0 = ChatFraming.focus(body)
            val mid = body.head.lerp(Vec3(body.head.x, body.head.y, body.floorZ), 0.5f)
            val aim = f0.lerp(mid, mix)
            focus = Vec3(aim.x, aim.y, aim.z - ChatZoom.panelAim(zoomS, panelInset, base, fv))
            pitch = asin(((ChatFraming.eyeZ(body) - focus.z) / base).coerceIn(-0.5f, 0.5f)) + pitchDrift
            fovBase = fv
        }
        // a wall in the way: draw in, or lean to the side, tried now and then (the clearance test is not free)
        avoidClock -= step
        if (first || avoidClock <= 0f) {
            avoidClock = 0.5f
            val v = visible
            val want = facingS + drift + orbitS
            if (v == null || v(CinematicFraming.eye(focus, want, pitch, base * scaleDrift), focus)) { avoidScale = 1f; avoidYaw = 0f }
            else {
                val tries = listOf(0.8f to 0f, 0.65f to 0f, 0.8f to 0.3f, 0.8f to -0.3f, 0.65f to 0.4f, 0.65f to -0.4f, 0.5f to 0f)
                var chosen = tries.last()
                for (o in tries) if (v(CinematicFraming.eye(focus, want + o.second, pitch, base * scaleDrift * o.first), focus)) { chosen = o; break }
                avoidScale = chosen.first; avoidYaw = chosen.second
            }
        }
        yaw = facingS + (drift + avoidYaw).coerceIn(-ChatFraming.MAX_YAW - 0.4f, ChatFraming.MAX_YAW + 0.4f) + orbitS
        val eye = CinematicFraming.eye(focus, yaw, pitch, base * scaleDrift * avoidScale)
        val fovNow = if (creature) fovBase else
            CinematicFraming.fit(eye, focus, fovBase, fovBase + 16f, aspect, ChatZoom.mustShow(body, zoomS), 0.06f) ?: (fovBase + 16f)
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
