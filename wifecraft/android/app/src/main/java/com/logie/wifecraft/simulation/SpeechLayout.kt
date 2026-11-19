package com.logie.wifecraft.simulation

import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Where the speech bubbles go on the screen, worked out each frame on the GL thread from what the frame already has (the
 * camera's eye, target and vertical field of view, as the renderer takes them: z up, near plane 0.2) and each person's
 * head: [project] puts a world point on a w x h view (pixels, y down) the way the renderer draws it; [choose] keeps the
 * bubbles of the speakers on screen, the nearest [MAX_BUBBLES]. Nothing allocated. Pure JVM.
 */
object SpeechLayout {
    /** At most this many bubbles at once (the nearest speakers). */
    const val MAX_BUBBLES = 4
    const val NEAR = 0.2f
    /** Fields per person in a layout array: screen x, screen y, depth (<= 0: off screen), shown (1) or not (0). */
    const val STRIDE = 4

    /**
     * World point [px], [py], [pz] seen from eye [ex].. toward [ax].. with [fovDeg] (vertical) on a [w] x [h] view: its
     * screen x, y and depth into [out] at [at] (depth 0: behind the eye or outside the view, with a small margin).
     */
    fun project(ex: Float, ey: Float, ez: Float, ax: Float, ay: Float, az: Float, fovDeg: Float, w: Float, h: Float,
        px: Float, py: Float, pz: Float, out: FloatArray, at: Int) {
        out[at] = 0f; out[at + 1] = 0f; out[at + 2] = 0f
        if (w <= 0f || h <= 0f) return
        var fx = ax - ex; var fy = ay - ey; var fz = az - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        if (fl < 1e-6f) return
        fx /= fl; fy /= fl; fz /= fl
        // right = forward x up (z up; straight down or up: y up instead), then the camera's own up = right x forward
        var ux = 0f; var uy = 0f; var uz = 1f
        if (fz * fz > 0.998f) { uy = 1f; uz = 0f }
        var sx = fy * uz - fz * uy; var sy = fz * ux - fx * uz; var sz = fx * uy - fy * ux
        val sl = sqrt(sx * sx + sy * sy + sz * sz)
        sx /= sl; sy /= sl; sz /= sl
        val vx = sy * fz - sz * fy; val vy = sz * fx - sx * fz; val vz = sx * fy - sy * fx
        val dx = px - ex; val dy = py - ey; val dz = pz - ez
        val depth = dx * fx + dy * fy + dz * fz
        if (depth < NEAR) return
        val th = tan(fovDeg * 0.5f * 0.017453292f)
        val aspect = w / h
        val nx = (dx * sx + dy * sy + dz * sz) / (depth * th * aspect)
        val ny = (dx * vx + dy * vy + dz * vz) / (depth * th)
        if (nx < -1.05f || nx > 1.05f || ny < -1.05f || ny > 1.25f) return
        out[at] = (nx + 1f) * 0.5f * w
        out[at + 1] = (1f - ny) * 0.5f * h
        out[at + 2] = depth
    }

    /**
     * Marks in [layout] (STRIDE per person, projected) which of the [speaking] people get a bubble: on screen, the nearest
     * [max]; the rest are hidden. [n] people.
     */
    fun choose(n: Int, speaking: BooleanArray, layout: FloatArray, max: Int = MAX_BUBBLES) {
        for (i in 0 until n) layout[i * STRIDE + 3] = 0f
        for (k in 0 until max) {
            var best = -1
            var bestDepth = Float.MAX_VALUE
            for (i in 0 until n) {
                val d = layout[i * STRIDE + 2]
                if (!speaking[i] || d <= 0f || layout[i * STRIDE + 3] != 0f) continue
                if (d < bestDepth) { bestDepth = d; best = i }
            }
            if (best < 0) return
            layout[best * STRIDE + 3] = 1f
        }
    }

    /** How high above where they stand a person's head is: standing [standing], sitting about two thirds, lying a third. */
    fun head(standing: Float, sitting: Boolean, lying: Boolean): Float = when {
        lying -> standing * 0.38f
        sitting -> standing * 0.7f
        else -> standing
    }
}
