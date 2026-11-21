package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.BodyFrame
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.FleshPart
import com.logie.wifecraft.anim.FleshProfile
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.anim.PartFrame
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
 * come nearest (between the slots when they are on one axis; else to the first role's left), at most [MAX_PUSH]. Parts may
 * come [CONTACT_TOLERANCE] into each other first (stages made to touch keep their contact), measured bodies as far as the
 * reference bodies come in the stage as authored and [EXTRA] more, and a stage made for contact (a kiss, an embrace, a
 * cuddle, a hug, a cradle) is moved at most [MAX_PUSH_CONTACT]. Pure; the simulation caches one per pair.
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
    /**
     * How far two bodies may come into each other before the second is moved (body units, about 21 cm): stages made to
     * touch (a kiss, an embrace, a lap) keep their contact; two clips stacked on one spot (30 cm and more) are still moved,
     * by what they come nearer than this.
     */
    const val CONTACT_TOLERANCE = 0.2
    /**
     * How much deeper than the reference bodies (the generic flesh on the reference skeleton, as the stage was authored) two
     * measured bodies may come into each other at a moment before the second is moved (body units, about 3.5 cm): a stage
     * whose author made the bodies meet keeps that meeting for thicker bodies too, moved only by what they add past it.
     */
    const val EXTRA = 0.03
    /** The farthest a stage made for contact (a kiss, an embrace, a cuddle, a hug, a cradle: [contact]) is moved (body units). */
    const val MAX_PUSH_CONTACT = 0.1

    private val CONTACT_WORDS = Regex("kiss|embrac|cuddl|hug|cradl", RegexOption.IGNORE_CASE)

    /** Whether [p] is a stage made for contact, by its name or id (a kiss, an embrace, a cuddle, a hug, a cradle). */
    fun contact(p: PartnerPair): Boolean = CONTACT_WORDS.containsMatchIn(p.name) || CONTACT_WORDS.containsMatchIn(p.id)

    /**
     * Two bodies whose parts come this near (body units of [RULES]' room: about 6 cm between their flesh) at some moment of
     * the clip meet: a stage made for contact ([touching]).
     */
    const val TOUCH = 0.05

    /**
     * Whether [p]'s two bodies meet at some moment of its clip (the generic body's parts within [TOUCH] of each other, or a
     * head or hand within [REACH_TOUCH] of the other's trunk, hips, thighs or head: a kiss, an embrace, one body on or behind
     * the other), so the pair is one piece: raised together ([PairLift]) and never moved
     * apart further than its author's contact allows.
     */
    fun touching(p: PartnerPair): Boolean {
        val a = frames(p.slotA, p.a); val b = frames(p.slotB, p.b)
        if (a.isEmpty() || b.isEmpty()) return false
        for (i in 0 until max(a.size, b.size)) {
            val fa = a[minOf(i, a.size - 1)]; val fb = b[minOf(i, b.size - 1)]
            val s = slack(fa, fb)
            if (s != null && s.first < TOUCH) return true
            // a head or a hand at the other's body (a kiss, oral, a hand on the hips): the trunks may be far apart
            for ((x, y) in listOf(fa to fb, fb to fa)) for (j in REACHING) {
                val p = x[j] ?: continue
                for (k in TOUCHED) { val q = y[k] ?: continue; if (dist(p, q) < REACH_TOUCH) return true }
            }
        }
        return false
    }

    /** A head or a hand this near (body units, about 29 cm) one of the other's [TOUCHED] joints meets it ([touching]). */
    const val REACH_TOUCH = 0.25
    private val REACHING = listOf("Head", "L_Hand", "R_Hand")
    private val TOUCHED = listOf("Root", "Pelvis", "Spine1", "Chest", "Neck1", "Head", "L_Thigh", "R_Thigh")

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

    /** The parts kept apart with measured flesh: a part of the first body and one of the second. */
    private val PART_RULES: List<Pair<FleshPart, FleshPart>> = buildList {
        for (a in FleshPart.TRUNK) for (b in FleshPart.TRUNK) add(a to b)
        for (limb in FleshPart.ARMS + FleshPart.THIGHS) for (t in FleshPart.TRUNK) { add(limb to t); add(t to limb) }
        for (a in FleshPart.THIGHS) for (b in FleshPart.THIGHS) add(a to b)
    }

    /**
     * [p] with its second role moved clear of the first, or [p] itself when nothing comes too near; [fleshA] the body
     * playing the first role, [fleshB] the second's (generic: the reference body).
     */
    fun spaced(p: PartnerPair, fleshA: FleshProfile = FleshProfile.GENERIC, fleshB: FleshProfile = FleshProfile.GENERIC): PartnerPair {
        val (dx, dy) = push(p, fleshA, fleshB) ?: return p
        val unit = (BodyGeometry.REF_UNIT * HumanScale.PAIR).toFloat()
        val b = p.slotB.copy(x = p.slotB.x + (dx * unit).toFloat(), y = p.slotB.y + (dy * unit).toFloat())
        return PartnerPair(p.id, p.name, p.a, p.b, p.slotA, b, p.loops, p.locations, p.sounds, p.kind)
    }

    /** How far (body units, x and y of the pair's frame) [p]'s second role must move; null: not at all, or the bodies can't be read. */
    fun push(p: PartnerPair, fleshA: FleshProfile = FleshProfile.GENERIC, fleshB: FleshProfile = FleshProfile.GENERIC): Pair<Double, Double>? {
        val a = frames(p.slotA, p.a, fleshA)
        val b = frames(p.slotB, p.b, fleshB)
        if (a.isEmpty() || b.isEmpty()) return null
        val fallback = (p.slotB.x - p.slotA.x).toDouble() to (p.slotB.y - p.slotA.y).toDouble()
        // measured bodies: each moment may come as deep as the reference bodies do there (the author's own contact) and
        // [EXTRA] more, before the second is moved; the reference bodies keep the plain tolerance
        val authored: DoubleArray? = if (!fleshA.measured && !fleshB.measured) null else {
            val ga = frames(p.slotA, p.a); val gb = frames(p.slotB, p.b)
            if (ga.isEmpty() || gb.isEmpty()) null else DoubleArray(max(a.size, b.size)) { i ->
                slack(ga[minOf(i, ga.size - 1)], gb[minOf(i, gb.size - 1)])?.first ?: 0.0
            }
        }
        val tolerance: (Int) -> Double = if (authored == null) { _ -> CONTACT_TOLERANCE }
            else { i ->
                // a moment the reference bodies already come deeper than the tolerance into (two clips on one spot) is moved
                // as before; one they meet within it may take the measured bodies [EXTRA] deeper than they come
                val deep = -authored[minOf(i, authored.size - 1)]
                if (deep > CONTACT_TOLERANCE) CONTACT_TOLERANCE else max(CONTACT_TOLERANCE, deep + EXTRA)
            }
        val cap = if (contact(p)) MAX_PUSH_CONTACT else MAX_PUSH
        val moved = push(a, b, fallback, fleshA, fleshB, cap, tolerance)
        if (authored == null || moved == null) return moved
        // never farther than the plain rule (every moment [CONTACT_TOLERANCE], which the authored tolerance never tightens)
        // moves the same bodies: its move clears these moments too
        val plain = push(a, b, fallback, fleshA, fleshB, cap) ?: return null
        return if (hypot(plain.first, plain.second) < hypot(moved.first, moved.second)) plain else moved
    }

    /** A role's body over its clip at its slot, on [flesh]'s own skeleton when measured. */
    fun frames(s: PartnerSlot, a: com.logie.wifecraft.anim.Anim, flesh: FleshProfile = FleshProfile.GENERIC): List<BodyFrame> =
        BodyGeometry.frames(a, (s.x / HumanScale.PAIR).toDouble(), (s.y / HumanScale.PAIR).toDouble(), s.z.toDouble(), s.yawDeg.toDouble(),
            flesh = flesh.takeIf { it.measured })

    /** The least room left by [RULES] between [fa] and [fb] (the second moved by [ox], [oy]), and the way apart there. */
    fun slack(fa: BodyFrame, fb: BodyFrame, ox: Double = 0.0, oy: Double = 0.0): Triple<Double, Double, Double>? {
        var worst = Double.MAX_VALUE; var wx = 0.0; var wy = 0.0
        eachRule(fa, fb, ox, oy) { _, s, x, y, _ -> if (s < worst) { worst = s; wx = x; wy = y } }
        return if (worst == Double.MAX_VALUE) null else Triple(worst, wx, wy)
    }

    /** Each of [RULES] [fa] and [fb] have the joints of: its index, the room left, the way apart (x, y) and the axes' distance. */
    private fun eachRule(fa: BodyFrame, fb: BodyFrame, ox: Double, oy: Double, f: (Int, Double, Double, Double, Double) -> Unit) {
        for ((idx, r) in RULES.withIndex()) {
            val a0 = fa[r.a0] ?: continue; val a1 = fa[r.a1] ?: continue
            val b0 = fb[r.b0]?.let { doubleArrayOf(it[0] + ox, it[1] + oy, it[2]) } ?: continue
            val b1 = fb[r.b1]?.let { doubleArrayOf(it[0] + ox, it[1] + oy, it[2]) } ?: continue
            val (d, pa, pb) = nearest(a0, a1, b0, b1)
            f(idx, d - r.need, pb[0] - pa[0], pb[1] - pa[1], d)
        }
    }

    /**
     * How far the second body ([b], sampled with [a] over one clock: each list as long, or the shorter's last frame held)
     * must move across the floor; [toward] the way to go when the parts are on one axis (from the first to the second).
     *
     * One move for the whole clip, the least that clears every moment ([solve]): each part pair that comes near is a
     * constraint on the move (its room grows by the move along the line between the parts), the constraints of every
     * moment are kept together and the shortest move meeting them all is taken, read again at that move and solved again
     * until it holds. Before, the push stepped toward whichever part pair was worst at each round, and two moments that
     * wanted different ways (an arm on the chest, the thighs) zigzagged it 20 cm away for 2 mm more flesh. A clip no move
     * clears is moved by the one that leaves the least of it too near, never farther than [BOUND] times its deepest moment.
     */
    fun push(a: List<BodyFrame>, b: List<BodyFrame>, toward: Pair<Double, Double> = 0.0 to 0.0,
             fleshA: FleshProfile = FleshProfile.GENERIC, fleshB: FleshProfile = FleshProfile.GENERIC,
             maxPush: Double = MAX_PUSH, tolerance: (Int) -> Double = { CONTACT_TOLERANCE }): Pair<Double, Double>? {
        val measured = fleshA.measured || fleshB.measured
        // with measured flesh, each moment's parts placed once (moving the second body only shifts its parts)
        val partsA = if (measured) a.map { parts(it, fleshA) } else emptyList()
        val partsB = if (measured) b.map { parts(it, fleshB) } else emptyList()
        val n = max(a.size, b.size)
        // the way apart for parts on one axis: between the slots, else to the first body's left
        var axX = toward.first; var axY = toward.second; var axH = hypot(axX, axY)
        if (axH < 1e-3) {
            val f = a.first(); val r = f["L_Thigh"]; val l = f["R_Thigh"]
            if (r != null && l != null) { axX = r[0] - l[0]; axY = r[1] - l[1]; axH = hypot(axX, axY) }
            if (axH < 1e-3) { axX = 0.0; axY = 1.0; axH = 1.0 }
        }
        axX /= axH; axY /= axH
        val rules = if (measured) PART_RULES.size else RULES.size
        // every part pair that has come near: its constraint read again at each move (key: moment * rules + rule)
        val seen = HashSet<Int>()
        val cons = ArrayList<DoubleArray>()
        // reads every moment at a move: the worst room left past the tolerance (NaN: a moment can't be read)
        fun read(ox: Double, oy: Double, collect: Boolean): Double {
            if (collect) cons.clear()
            var worst = Double.MAX_VALUE
            for (i in 0 until n) {
                val tol = tolerance(i)
                var any = false
                val sink = { rule: Int, s: Double, wx: Double, wy: Double, d: Double ->
                    any = true
                    val over = s + tol
                    if (over < worst) worst = over
                    val key = i * rules + rule
                    if (collect && (over < ACTIVE || key in seen)) {
                        seen.add(key)
                        // the room's rate of growth with the move: the line between the parts across the floor
                        val h = hypot(wx, wy)
                        var gx: Double; var gy: Double
                        if (h < 1e-3) { gx = axX; gy = axY } else {
                            gx = wx / max(d, 1e-9); gy = wy / max(d, 1e-9)
                            val g = hypot(gx, gy)
                            if (g < MIN_RATE) { gx *= MIN_RATE / g; gy *= MIN_RATE / g }
                        }
                        // g . move >= g . (ox, oy) - over (and a millimetre)
                        cons.add(doubleArrayOf(gx, gy, gx * ox + gy * oy - over + 1e-3))
                    }
                }
                if (measured) eachPart(partsA[minOf(i, a.size - 1)], partsB[minOf(i, b.size - 1)], fleshA, fleshB, ox, oy, sink)
                else eachRule(a[minOf(i, a.size - 1)], b[minOf(i, b.size - 1)], ox, oy, sink)
                if (!any) return Double.NaN
            }
            return worst
        }
        val start = read(0.0, 0.0, true)
        if (start.isNaN()) return null
        // nearer than the contact tolerance nowhere: left as it is
        if (start >= -1e-6) return null
        val reach = minOf(BOUND * -start + 0.01, maxPush * 2)
        var ox = 0.0; var oy = 0.0
        var bestX = 0.0; var bestY = 0.0; var bestLeft = -start; var bestLen = 0.0
        for (round in 0 until ROUNDS) {
            val (nx, ny) = solve(cons, reach)
            val worst = read(nx, ny, true)
            if (worst.isNaN()) return null
            val left = max(0.0, -worst); val len = hypot(nx, ny)
            // the best move so far: the least left too near (to 2 mm), then the shortest
            if (left < bestLeft - 0.002 || (left < bestLeft + 0.002 && len < bestLen)) { bestX = nx; bestY = ny; bestLeft = left; bestLen = len }
            // a move that clears every moment is read again there and solved again while that shortens it (the parts'
            // flesh is round: each constraint, taken at the move, is the tangent of the room it needs there)
            if (hypot(nx - ox, ny - oy) < 1e-4) break
            ox = nx; oy = ny
        }
        if (bestX == 0.0 && bestY == 0.0) return null
        if (bestLen > maxPush) return bestX / bestLen * maxPush to bestY / bestLen * maxPush
        return bestX to bestY
    }

    /** A part pair whose room past its tolerance is under this (body units) is one of the constraints [push] solves. */
    private const val ACTIVE = 0.03
    /** The least rate a part pair's room is taken to grow at with the move ([push]: parts one over the other). */
    private const val MIN_RATE = 0.25
    /** How many times [push] reads the moments again at its move and solves again. */
    private const val ROUNDS = 12
    /** The farthest [push] looks, against the deepest moment's depth past its tolerance (two ways at a right angle: 1.41). */
    const val BOUND = 1.5

    /**
     * The shortest move (x, y) meeting every constraint of [cons] (each gx, gy, c: gx x + gy y >= c), looked for on every
     * way across the floor (half a degree apart) within [reach]; where none meets them all, the move within [reach] that
     * leaves the least unmet, then the shortest.
     */
    fun solve(cons: List<DoubleArray>, reach: Double): Pair<Double, Double> {
        // the constraints by way (to half a degree), the strictest of each: a constraint of a moment met by far can't bind
        val ways = 720
        val c = DoubleArray(ways) { Double.NEGATIVE_INFINITY }
        for (k in cons) {
            val g = hypot(k[0], k[1]); if (g < 1e-9) continue
            val v = k[2] / g
            if (v < -reach) continue
            val w = Math.floorMod(Math.round(Math.atan2(k[1], k[0]) / (2 * Math.PI) * ways).toInt(), ways)
            if (v > c[w]) c[w] = v
        }
        val ux = DoubleArray(ways) { kotlin.math.cos(2 * Math.PI * it / ways) }
        val uy = DoubleArray(ways) { kotlin.math.sin(2 * Math.PI * it / ways) }
        val used = (0 until ways).filter { c[it] != Double.NEGATIVE_INFINITY }
        if (used.isEmpty()) return 0.0 to 0.0
        var best = Double.MAX_VALUE; var bx = 0.0; var by = 0.0
        for (t in 0 until ways) {
            var lo = 0.0; var hi = reach; var ok = true
            for (w in used) {
                val gu = ux[w] * ux[t] + uy[w] * uy[t]
                if (gu > 1e-9) lo = max(lo, c[w] / gu)
                else if (gu < -1e-9) hi = minOf(hi, c[w] / gu)
                else if (c[w] > 0) { ok = false; break }
                if (lo > hi) { ok = false; break }
            }
            if (ok && lo < best) { best = lo; bx = ux[t] * lo; by = uy[t] * lo }
        }
        if (best != Double.MAX_VALUE) return bx to by
        // nothing meets them all: the least left unmet within reach, then the shortest
        var bestLeft = Double.MAX_VALUE; best = Double.MAX_VALUE
        for (s in 1..24) {
            val r = reach * s / 24
            for (t in 0 until ways step 2) {
                val x = ux[t] * r; val y = uy[t] * r
                var left = 0.0
                for (w in used) left = max(left, c[w] - (ux[w] * x + uy[w] * y))
                if (left < bestLeft - 0.002 || (left < bestLeft + 0.002 && r < best)) { bestLeft = left; best = r; bx = x; by = y }
            }
        }
        return bx to by
    }

    /** [f]'s parts the measured rules read, placed with [flesh]'s segments (a part [f] lacks the joints of is left out). */
    fun parts(f: BodyFrame, flesh: FleshProfile): Map<FleshPart, PartFrame> {
        val out = HashMap<FleshPart, PartFrame>()
        for (p in FleshPart.TRUNK + FleshPart.ARMS + FleshPart.THIGHS) flesh.frame(p, f)?.let { out[p] = it }
        return out
    }

    /**
     * The least room left between two bodies' measured parts ([pa], [pb] from [parts]; the second moved by [ox], [oy]): each
     * pair of [PART_RULES]' parts, their axes' distance less each one's flesh toward the other there ([FleshProfile.reach])
     * and [MARGIN]; and the way apart there.
     */
    fun slack(pa: Map<FleshPart, PartFrame>, pb: Map<FleshPart, PartFrame>, fleshA: FleshProfile, fleshB: FleshProfile,
              ox: Double = 0.0, oy: Double = 0.0): Triple<Double, Double, Double>? {
        var worst = Double.MAX_VALUE; var wx = 0.0; var wy = 0.0
        eachPart(pa, pb, fleshA, fleshB, ox, oy) { _, s, x, y, _ -> if (s < worst) { worst = s; wx = x; wy = y } }
        return if (worst == Double.MAX_VALUE) null else Triple(worst, wx, wy)
    }

    /** Each of [PART_RULES] both bodies have: its index, the room left, the way apart (x, y) and the axes' distance. */
    private fun eachPart(pa: Map<FleshPart, PartFrame>, pb: Map<FleshPart, PartFrame>, fleshA: FleshProfile, fleshB: FleshProfile,
                         ox: Double, oy: Double, f: (Int, Double, Double, Double, Double) -> Unit) {
        for ((idx, rule) in PART_RULES.withIndex()) {
            val (ka, kb) = rule
            val fa = pa[ka] ?: continue
            val fb0 = pb[kb] ?: continue
            val fb = if (ox == 0.0 && oy == 0.0) fb0 else fb0.shifted(ox, oy)
            val (d, ta, tb) = nearestAt(fa.a0, fa.a1, fb.a0, fb.a1)
            val pA = lerp(fa.a0, fa.a1, ta); val pB = lerp(fb.a0, fb.a1, tb)
            val sa = fleshA.segment(ka); val sb = fleshB.segment(kb)
            val need = if (d < 1e-6) sa.most + sb.most + MARGIN else {
                val dir = DoubleArray(3) { (pB[it] - pA[it]) / d }
                val back = DoubleArray(3) { -dir[it] }
                FleshProfile.reach(sa, fa, dir, ta <= 1e-6, ta >= 1 - 1e-6) + FleshProfile.reach(sb, fb, back, tb <= 1e-6, tb >= 1 - 1e-6) + MARGIN
            }
            f(idx, d - need, pB[0] - pA[0], pB[1] - pA[1], d)
        }
    }

    /** The nearest points of segments [a0]-[a1] and [b0]-[b1]: their distance and where on each (0 to 1). */
    fun nearestAt(a0: DoubleArray, a1: DoubleArray, b0: DoubleArray, b1: DoubleArray): Triple<Double, Double, Double> {
        var best = Double.MAX_VALUE; var ta = 0.0; var tb = 0.0
        val n = 24
        for (i in 0..n) {
            val t = i.toDouble() / n
            val p = lerp(a0, a1, t)
            val (u, q) = closest(p, b0, b1)
            val d = dist(p, q)
            if (d < best) { best = d; ta = t; tb = u }
        }
        for (i in 0..n) {
            val u = i.toDouble() / n
            val q = lerp(b0, b1, u)
            val (t, p) = closest(q, a0, a1)
            val d = dist(p, q)
            if (d < best) { best = d; ta = t; tb = u }
        }
        return Triple(best, ta, tb)
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

/**
 * The lift of a pair's two roles (world units, the first role's then the second's), each worked out for its own body's flesh
 * (SceneSimulation.roleLifts). Two bodies that meet are one piece made by their author: raising one and not the other moves
 * their contact (a rear-facing pair's hips apart up and down by the difference), so they are raised together by the larger
 * of the two (neither goes into its support; the other is lifted with it, its flesh as far over its support as the author
 * had it at worst). Apart, each keeps its own. Pure.
 */
object PairLift {
    fun shared(first: Float, second: Float, touching: Boolean): FloatArray =
        if (touching) maxOf(first, second).let { floatArrayOf(it, it) } else floatArrayOf(first, second)

    /**
     * [shared], and a stage made in water ([water]: [PairWater]) raised together too, touching or not: its bodies were
     * placed against one water line (a body on the pool's edge, one standing in the pool) and are taken out of it as one.
     */
    fun lifts(first: Float, second: Float, touching: Boolean, water: Boolean): FloatArray = shared(first, second, touching || water)
}
