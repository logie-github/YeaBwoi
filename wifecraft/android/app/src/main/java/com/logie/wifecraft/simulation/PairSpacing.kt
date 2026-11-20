package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.BodyFrame
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.HumanScale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Two bodies of a pair kept out of each other: the trunks (pelvis to neck, as thick as a body: [TRUNK_RADIUS]) of the two
 * roles, read from the clips' own joints over the whole clip ([BodyGeometry], body units), must stay [TRUNK_RADIUS] twice
 * plus [MARGIN] apart; hands, arms and faces may touch. A pair whose trunks come nearer (two clips authored on one spot,
 * a spoon made for thinner bodies, both roles given the same root) has its second role moved away from the first, across
 * the floor only (a slot's height is its clip's), along the line between the trunks where they come nearest (between the
 * slots when the trunks are on one axis; else to the first role's left). Pure; the simulation caches one per pair.
 */
object PairSpacing {
    /** A trunk's half-thickness in body units (a human's chest or hips, about 0.15 yards, over the 1.17-yard pelvis). */
    const val TRUNK_RADIUS = 0.13
    /** Room left between the two trunks (body units). */
    const val MARGIN = 0.02

    /** [p] with its second role moved clear of the first, or [p] itself when the trunks never come too near. */
    fun spaced(p: PartnerPair): PartnerPair {
        val (dx, dy) = push(p) ?: return p
        val unit = (BodyGeometry.REF_UNIT * HumanScale.PAIR).toFloat()
        val b = p.slotB.copy(x = p.slotB.x + (dx * unit).toFloat(), y = p.slotB.y + (dy * unit).toFloat())
        return PartnerPair(p.id, p.name, p.a, p.b, p.slotA, b, p.loops, p.locations, p.sounds)
    }

    /** How far (body units, x and y of the pair's frame) [p]'s second role must move; null: not at all, or the bodies can't be read. */
    fun push(p: PartnerPair): Pair<Double, Double>? {
        fun frames(s: PartnerSlot, a: com.logie.wifecraft.anim.Anim) =
            BodyGeometry.frames(a, (s.x / HumanScale.PAIR).toDouble(), (s.y / HumanScale.PAIR).toDouble(), s.z.toDouble(), s.yawDeg.toDouble())
        val a = frames(p.slotA, p.a)
        val b = frames(p.slotB, p.b)
        if (a.isEmpty() || b.isEmpty()) return null
        val fallback = (p.slotB.x - p.slotA.x).toDouble() to (p.slotB.y - p.slotA.y).toDouble()
        return push(a, b, fallback)
    }

    /**
     * How far the second body ([b], sampled with [a] over one clock: each list as long, or the shorter's last frame held)
     * must move across the floor; [toward] the way to go when the trunks are on one axis (from the first to the second).
     */
    fun push(a: List<BodyFrame>, b: List<BodyFrame>, toward: Pair<Double, Double> = 0.0 to 0.0): Pair<Double, Double>? {
        val need = 2 * TRUNK_RADIUS + MARGIN
        var ox = 0.0; var oy = 0.0
        // a few rounds: moving away from the nearest moment may bring another nearer
        repeat(4) {
            var worst = Double.MAX_VALUE; var wx = 0.0; var wy = 0.0
            for (i in 0 until max(a.size, b.size)) {
                val fa = a[minOf(i, a.size - 1)]; val fb = b[minOf(i, b.size - 1)]
                val a0 = fa["Root"] ?: return null; val a1 = fa["Neck1"] ?: return null
                val b0 = fb["Root"]?.let { doubleArrayOf(it[0] + ox, it[1] + oy, it[2]) } ?: return null
                val b1 = fb["Neck1"]?.let { doubleArrayOf(it[0] + ox, it[1] + oy, it[2]) } ?: return null
                val (d, pa, pb) = nearest(a0, a1, b0, b1)
                if (d < worst) { worst = d; wx = pb[0] - pa[0]; wy = pb[1] - pa[1] }
            }
            if (worst >= need - 1e-6) return if (ox == 0.0 && oy == 0.0) null else ox to oy
            var h = hypot(wx, wy)
            if (h < 1e-3) {
                // on one axis: between the slots, else to the first body's left
                wx = toward.first; wy = toward.second; h = hypot(wx, wy)
                if (h < 1e-3) {
                    val n = a.first(); val r = n["L_Thigh"]; val l = n["R_Thigh"]
                    if (r != null && l != null) { wx = r[0] - l[0]; wy = r[1] - l[1]; h = hypot(wx, wy) }
                    if (h < 1e-3) { wx = 0.0; wy = 1.0; h = 1.0 }
                }
            }
            val step = need - worst + 1e-3
            ox += wx / h * step; oy += wy / h * step
        }
        return ox to oy
    }

    /** The nearest points of segments [a0]-[a1] and [b0]-[b1] and their distance. */
    fun nearest(a0: DoubleArray, a1: DoubleArray, b0: DoubleArray, b1: DoubleArray): Triple<Double, DoubleArray, DoubleArray> {
        var best = Double.MAX_VALUE; var pa = a0; var pb = b0
        // sampled finely, then the best pair refined: exact enough for bodies and never fails on parallel segments
        val n = 24
        for (i in 0..n) {
            val p = lerp(a0, a1, i.toDouble() / n)
            val (_, q) = closest(p, b0, b1)
            val d = dist(p, q)
            if (d < best) { best = d; pa = p; pb = q }
        }
        for (i in 0..n) {
            val q = lerp(b0, b1, i.toDouble() / n)
            val (_, p) = closest(q, a0, a1)
            val d = dist(p, q)
            if (d < best) { best = d; pa = p; pb = q }
        }
        return Triple(best, pa, pb)
    }

    private fun lerp(a: DoubleArray, b: DoubleArray, t: Double) = DoubleArray(3) { a[it] + (b[it] - a[it]) * t }
    private fun dist(a: DoubleArray, b: DoubleArray) = sqrt((0..2).sumOf { (a[it] - b[it]) * (a[it] - b[it]) })
    private fun closest(p: DoubleArray, a: DoubleArray, b: DoubleArray): Pair<Double, DoubleArray> {
        val ab = DoubleArray(3) { b[it] - a[it] }
        val l2 = ab.sumOf { it * it }
        val t = if (l2 < 1e-12) 0.0 else ((0..2).sumOf { (p[it] - a[it]) * ab[it] } / l2).coerceIn(0.0, 1.0)
        return t to lerp(a, b, t)
    }
}
