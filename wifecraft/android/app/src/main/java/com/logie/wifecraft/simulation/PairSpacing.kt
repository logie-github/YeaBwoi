package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.BodyFrame
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.HumanScale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Two bodies of a pair kept out of each other, from the clips' own joints over the whole clip ([BodyGeometry], body units):
 * the trunks (pelvis to neck, [TRUNK_RADIUS] thick) must stay apart, and so must one body's upper arms and forearms
 * ([ARM_RADIUS]) and thighs ([LEG_RADIUS]) from the other's trunk, and the two bodies' thighs from each other, each by
 * [MARGIN]; hands, faces and arm on arm may touch. A pair that comes nearer (two clips authored on one spot, a spoon made for
 * thinner bodies, both roles given the same root, an arm through the other's chest on a couch) has its second role moved
 * away from the first, across the floor only (a slot's height is its clip's), along the line between the parts where they
 * come nearest (between the slots when they are on one axis; else to the first role's left), at most [MAX_PUSH]. Pure; the
 * simulation caches one per pair.
 */
object PairSpacing {
    /** A trunk's half-thickness in body units (a human's chest or hips, about 0.15 yards, over the 1.17-yard pelvis). */
    const val TRUNK_RADIUS = 0.13
    /** An upper arm's or forearm's half-thickness; a thigh's. */
    const val ARM_RADIUS = 0.045
    const val LEG_RADIUS = 0.07
    /** Room left between the parts (body units). */
    const val MARGIN = 0.02
    /** The farthest the second role is moved (body units, about 0.7 yards): a pair that needs more is left as it is made. */
    const val MAX_PUSH = 0.6

    /** The parts kept apart: a segment of the first body, one of the second, and how far apart their axes must stay. */
    private class Rule(val a0: String, val a1: String, val b0: String, val b1: String, val need: Double)

    private val RULES: List<Rule> = buildList {
        add(Rule("Root", "Neck1", "Root", "Neck1", 2 * TRUNK_RADIUS + MARGIN))
        for (s in listOf("L", "R")) {
            for ((j0, j1) in listOf("${s}_UpperArm" to "${s}_Forearm", "${s}_Forearm" to "${s}_Hand")) {
                add(Rule(j0, j1, "Root", "Neck1", TRUNK_RADIUS + ARM_RADIUS + MARGIN))
                add(Rule("Root", "Neck1", j0, j1, TRUNK_RADIUS + ARM_RADIUS + MARGIN))
            }
            add(Rule("${s}_Thigh", "${s}_Calf", "Root", "Neck1", TRUNK_RADIUS + LEG_RADIUS + MARGIN))
            add(Rule("Root", "Neck1", "${s}_Thigh", "${s}_Calf", TRUNK_RADIUS + LEG_RADIUS + MARGIN))
            for (t in listOf("L", "R")) add(Rule("${s}_Thigh", "${s}_Calf", "${t}_Thigh", "${t}_Calf", 2 * LEG_RADIUS + MARGIN))
        }
    }

    /** [p] with its second role moved clear of the first, or [p] itself when nothing comes too near. */
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

    /** The least room left by [RULES] between [fa] and [fb] (the second moved by [ox], [oy]), and the way apart there. */
    fun slack(fa: BodyFrame, fb: BodyFrame, ox: Double = 0.0, oy: Double = 0.0): Triple<Double, Double, Double>? {
        var worst = Double.MAX_VALUE; var wx = 0.0; var wy = 0.0
        for (r in RULES) {
            val a0 = fa[r.a0] ?: continue; val a1 = fa[r.a1] ?: continue
            val b0 = fb[r.b0]?.let { doubleArrayOf(it[0] + ox, it[1] + oy, it[2]) } ?: continue
            val b1 = fb[r.b1]?.let { doubleArrayOf(it[0] + ox, it[1] + oy, it[2]) } ?: continue
            val (d, pa, pb) = nearest(a0, a1, b0, b1)
            if (d - r.need < worst) { worst = d - r.need; wx = pb[0] - pa[0]; wy = pb[1] - pa[1] }
        }
        return if (worst == Double.MAX_VALUE) null else Triple(worst, wx, wy)
    }

    /**
     * How far the second body ([b], sampled with [a] over one clock: each list as long, or the shorter's last frame held)
     * must move across the floor; [toward] the way to go when the parts are on one axis (from the first to the second).
     */
    fun push(a: List<BodyFrame>, b: List<BodyFrame>, toward: Pair<Double, Double> = 0.0 to 0.0): Pair<Double, Double>? {
        var ox = 0.0; var oy = 0.0
        // a few rounds: moving away from the nearest moment may bring another nearer
        repeat(6) {
            var worst = Double.MAX_VALUE; var wx = 0.0; var wy = 0.0
            for (i in 0 until max(a.size, b.size)) {
                val (s, x, y) = slack(a[minOf(i, a.size - 1)], b[minOf(i, b.size - 1)], ox, oy) ?: return null
                if (s < worst) { worst = s; wx = x; wy = y }
            }
            if (worst >= -1e-6) return if (ox == 0.0 && oy == 0.0) null else ox to oy
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
            val step = -worst + 1e-3
            ox += wx / h * step; oy += wy / h * step
            val total = hypot(ox, oy)
            if (total > MAX_PUSH) return ox / total * MAX_PUSH to oy / total * MAX_PUSH
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
