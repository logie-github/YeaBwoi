package com.logie.wifecraft.simulation

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

// Pure Kotlin (no Android): where depth of field keeps the picture sharp, so the focus follows who the camera is about.
// Run by the JVM tests (unit-tests/.../simulation/FocusTargetTest.kt). WoW's coordinates: Z up.

/** What the camera is about (the subject the focus tracks); [NONE]: nothing, the renderer's own band is used. */
enum class FocusKind {
    /** The selected NPC, whatever the camera mode (orbit, follow, room): the focus plane is her chest. */
    NPC,
    /** The selected NPC and her partner (a two-person animation): the midpoint between their chests. */
    PAIR,
    /** A cinematic director's shot: the point the shot looks at, with the people round it. */
    SHOT,
    /** JOI mode's camera: the point it tracks (her face or chest), her head, chest and feet in depth. */
    JOI,
    /** Chat mode's camera: her face, head to waist. */
    CHAT,
    /** The Build-mode pivot (the tapped object) when no NPC is selected. */
    PIVOT,
    NONE,
}

/**
 * Depth of field's sharp band on the subject. Each frame, [update] is given the camera, the subject's centre point (an NPC's chest, a
 * pair's midpoint, a shot's look-at, a pivot) and the points that give the subject its depth (head, chest and feet of each person);
 * it finds where along the view axis those lie, the band [near]..[far] kept sharp round the centre, and eases the band toward
 * it with critically damped springs (about [SETTLE_S] to settle, so it neither pops when the camera cuts nor lags behind an
 * orbit). The renderer's depth of field is a plane in view depth (its circle of confusion is each pixel's depth against the band),
 * so a subject off the middle of the screen stays sharp wherever it is drawn; [screenX] and [screenY] say where the centre falls
 * on the screen (-1..1, y up) for the harness and the tests.
 *
 * [strength] scales the blur: full up to [FULL_STRENGTH_TO] yards away, less (down to [MIN_STRENGTH]) for far shots, where a
 * lens that wide would not blur so much. A close-up's band never gets narrower than the face is deep ([minHalf]).
 */
class FocusTarget {
    /** Whether a band is in force (false: the app gives the renderer none and it uses its own). */
    var active = false; private set
    var near = 0f; private set
    var far = 0f; private set
    /** The centre's depth along the view axis (what the camera is focused on, yards), as asked this frame (not smoothed). */
    var distance = 0f; private set
    var strength = 1f; private set
    var screenX = 0f; private set
    var screenY = 0f; private set
    var onScreen = false; private set
    var kind = FocusKind.NONE; private set

    private val sNear = Spring(0f, OMEGA)
    private val sFar = Spring(0f, OMEGA)
    private val sStrength = Spring(1f, OMEGA)
    private var held = false

    /** The inputs of a frame, reused (nothing allocated per frame). */
    class Input {
        var eyeX = 0f; var eyeY = 0f; var eyeZ = 0f
        var atX = 0f; var atY = 1f; var atZ = 0f
        /** Vertical field of view, degrees. */
        var fov = 50f
        var aspect = 16f / 9f
        var cx = 0f; var cy = 0f; var cz = 0f
        /** Points (x, y, z each) that give the subject its depth: [count] of them. */
        val pts = FloatArray(MAX_POINTS * 3)
        var count = 0

        fun clearPoints() { count = 0 }
        fun addPoint(x: Float, y: Float, z: Float) {
            if (count >= MAX_POINTS) return
            pts[count * 3] = x; pts[count * 3 + 1] = y; pts[count * 3 + 2] = z
            count++
        }
    }

    /** Forget the band: the next one that comes is taken as it is, not glided to. */
    fun reset() { active = false; held = false; kind = FocusKind.NONE }

    /**
     * One frame: [dt] seconds, what the camera is about ([kind]) and the frame's [input]. [enabled] false (Options' Graphics: Focus
     * follows the NPC off) or [kind] NONE: no band, the renderer's own. Returns [active].
     */
    fun update(dt: Float, kind: FocusKind, input: Input, enabled: Boolean = true): Boolean {
        this.kind = kind
        if (!enabled || kind == FocusKind.NONE || !compute(input)) { active = false; held = false; return false }
        val step = if (dt.isFinite()) dt.coerceIn(0f, 0.1f) else 0f
        if (!held) { sNear.snap(goalNear); sFar.snap(goalFar); sStrength.snap(goalStrength); held = true }
        else { sNear.update(goalNear, step); sFar.update(goalFar, step); sStrength.update(goalStrength, step) }
        near = max(MIN_NEAR, sNear.value)
        far = max(near + 0.05f, sFar.value)
        strength = sStrength.value.coerceIn(0f, 1f)
        active = true
        return true
    }

    // this frame's goal, before the springs
    private var goalNear = 0f
    private var goalFar = 0f
    private var goalStrength = 1f

    private fun compute(i: Input): Boolean {
        var fx = i.atX - i.eyeX; var fy = i.atY - i.eyeY; var fz = i.atZ - i.eyeZ
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        if (!fl.isFinite() || fl < 1e-4f) return false
        fx /= fl; fy /= fl; fz /= fl
        val dc = depth(i, i.cx, i.cy, i.cz, fx, fy, fz)
        if (!dc.isFinite()) return false
        val centre = max(MIN_NEAR, dc)
        var lo = centre; var hi = centre
        for (k in 0 until i.count) {
            val d = depth(i, i.pts[k * 3], i.pts[k * 3 + 1], i.pts[k * 3 + 2], fx, fy, fz)
            if (!d.isFinite() || d <= 0f) continue
            lo = min(lo, d); hi = max(hi, d)
        }
        val pad = (centre / 3f).coerceIn(0.4f, 1f)
        var n = lo - NEAR_PAD * pad
        var f = hi + FAR_PAD * pad
        val half = minHalf(centre)
        n = min(n, centre - half); f = max(f, centre + half)
        goalNear = max(MIN_NEAR, n); goalFar = f
        goalStrength = strengthAt(centre)
        distance = centre
        // where the centre falls on the screen: right = forward x up, up = right x forward
        val rl = sqrt(fy * fy + fx * fx)
        if (rl < 1e-5f) { onScreen = true; screenX = 0f; screenY = 0f; return true }   // looking straight up or down
        val rx = fy / rl; val ry = -fx / rl
        val ux = ry * fz; val uy = -rx * fz; val uz = rx * fy - ry * fx
        val px = i.cx - i.eyeX; val py = i.cy - i.eyeY; val pz = i.cz - i.eyeZ
        val t = tan(Math.toRadians(i.fov.toDouble() / 2.0)).toFloat()
        if (dc <= 1e-3f || t <= 0f) { onScreen = false; screenX = 0f; screenY = 0f; return true }
        screenX = (px * rx + py * ry) / (dc * t * i.aspect)
        screenY = (px * ux + py * uy + pz * uz) / (dc * t)
        onScreen = abs(screenX) <= 1f && abs(screenY) <= 1f
        return true
    }

    companion object {
        const val MAX_POINTS = 12
        /** About 4.6 / OMEGA = 0.19 s to settle within 1 %: inside the 150 to 250 ms that reads as a pull, not a pop. */
        const val OMEGA = 24f
        const val SETTLE_S = 4.6f / OMEGA
        /** Padding of the band past the subject's nearest and farthest point (yards; at 3 yards or more away; closer, less). */
        const val NEAR_PAD = 0.25f
        const val FAR_PAD = 0.35f
        const val MIN_NEAR = 0.1f
        const val FULL_STRENGTH_TO = 3f
        const val MIN_STRENGTH = 0.6f
        /** Two people further apart than this (yards) are not one subject: the focus stays on the selected one. */
        const val PAIR_MAX_APART = 6f

        /** Half the band's least depth at [distance] yards: a close-up's face (nose to ears) is always inside it. */
        fun minHalf(distance: Float): Float = 0.18f + 0.05f * distance

        /** The blur's strength at [distance]: 1 up to [FULL_STRENGTH_TO], then 6 % less a yard, never under [MIN_STRENGTH]. */
        fun strengthAt(distance: Float): Float = (1f - 0.06f * (distance - FULL_STRENGTH_TO)).coerceIn(MIN_STRENGTH, 1f)

        /** The depth of a point along the view axis (the renderer's linear view depth). */
        fun depth(i: Input, x: Float, y: Float, z: Float, fx: Float, fy: Float, fz: Float): Float =
            (x - i.eyeX) * fx + (y - i.eyeY) * fy + (z - i.eyeZ) * fz

        /**
         * The subject of two people: the midpoint of their chests ([ax], [ay], [az] and [bx], [by], [bz]) when they are within
         * [PAIR_MAX_APART] yards, else the first one's. Written to [out] (3 floats).
         */
        fun pairCentre(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, out: FloatArray) {
            val apart = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by) + (az - bz) * (az - bz))
            if (apart.isFinite() && apart <= PAIR_MAX_APART) { out[0] = (ax + bx) / 2f; out[1] = (ay + by) / 2f; out[2] = (az + bz) / 2f }
            else { out[0] = ax; out[1] = ay; out[2] = az }
        }
    }
}
