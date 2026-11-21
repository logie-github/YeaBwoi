package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.FleshLift
import com.logie.wifecraft.anim.FleshPart
import com.logie.wifecraft.anim.FleshProfile
import com.logie.wifecraft.anim.FleshSegment
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.tools.PairAlignment
import com.logie.wifecraft.tools.PairAlignmentAudit
import java.io.File
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The pair alignment's regression tests (README, "Alignment audit"): a two-person stage keeps its author's relative placement
 * as the app places it. Per pose class (rear-facing, face-to-face standing, side-by-side lying, side-by-side seated), with
 * synthetic bodies (anim/PoseFixtures) and synthetic flesh (generic, slim, bulky): two bodies that meet are neither moved
 * apart nor raised apart beyond the audit's placement tolerance; and the relative hips of the real packs' stages
 * (pair_alignment.csv: numbers only, measured by tools/PairAlignmentAudit) placed as fixtures meet and stay put. The retarget's
 * own part (the WoW hips kept on the Sims hips) is RetargetTest.aTiltedPelvisKeepsTheWowHipsOnTheSimsHips.
 */
class PairAlignmentTest {
    private val F = PoseFixtures
    private val tolCm = PairAlignmentAudit.Tol.PLACE_CM

    private fun profile(r: Double, over: Map<FleshPart, FleshSegment> = emptyMap()): FleshProfile {
        val out = ArrayList<Float>()
        out += listOf(1f, 1.17f, 0f, FleshPart.entries.size.toFloat())
        for (p in FleshPart.entries) {
            val s = over[p] ?: FleshSegment(r, r, r)
            out += listOf(1f, s.front, s.back, s.side, s.start, s.end, s.tip).map { it.toFloat() }
        }
        out += BodyGeometry.JOINTS.size.toFloat()
        for (j in BodyGeometry.JOINTS) out += BodyGeometry.REF.getValue(j).map { it.toFloat() }
        return FleshProfile.of(out.toFloatArray())!!
    }

    private val slim = profile(0.05)
    /** About the human man's measured trunk (engine-test/audit's human_m: the pelvis 0.11 in front, 0.16 behind, 0.19 wide). */
    private val bulky = profile(0.07, FleshPart.TRUNK.associateWith { FleshSegment(0.12, 0.16, 0.19) } +
        FleshPart.THIGHS.associateWith { FleshSegment(0.1, 0.1, 0.11) })

    private fun slot(xBu: Double, yBu: Double, yawDeg: Float = 0f) =
        PartnerSlot.human((xBu * BodyGeometry.REF_UNIT).toFloat(), (yBu * BodyGeometry.REF_UNIT).toFloat(), 0f, yawDeg)

    /** One pose class's fixture pair. */
    private class Case(val name: String, val pair: PartnerPair)

    private fun cases(): List<Case> = listOf(
        // rear-facing: the front body on hands and knees facing +x, the rear one kneeling up behind its hips, facing the same way
        Case("rear-facing", PartnerPair("rear", "rear", F.kneeling(name = "rear_a"), F.allFours("rear_b"), slot(-0.3, 0.0), slot(0.0, 0.0), true)),
        // face-to-face standing, an embrace's distance apart
        Case("face-to-face standing", PartnerPair("ff", "ff", F.standing, F.pose("ff_b", emptyMap(), 1.0), slot(0.0, 0.0), slot(0.30, 0.0, 180f), true)),
        // side by side lying, shoulder to shoulder
        Case("side-by-side lying", PartnerPair("sl", "sl", F.lyingBack(0.0, name = "sl_a"), F.lyingBack(0.0, name = "sl_b"), slot(0.0, 0.0), slot(0.0, 0.33), true)),
        // seated side by side, hip to hip
        Case("side-by-side seated", PartnerPair("ss", "ss", F.sitting(0.0, name = "ss_a"), F.sitting(0.0, name = "ss_b"), slot(0.0, 0.0), slot(0.0, 0.3), true)),
    )

    @Test fun eachClassFixtureMeets() {
        for (c in cases()) assertTrue(PairSpacing.touching(c.pair), "${c.name}: the two bodies meet")
        // two lying bodies an arm's length apart don't
        val apart = PartnerPair("apart", "apart", F.lyingBack(0.0, name = "ap_a"), F.lyingBack(0.0, name = "ap_b"), slot(0.0, 0.0), slot(0.0, 0.9), true)
        assertTrue(!PairSpacing.touching(apart), "an arm's length apart")
    }

    @Test fun twoBodiesThatMeetAreNotMovedApart() {
        // the author's contact is kept for the generic body, a slim one and one as bulky as the human man
        for (c in cases()) for ((fa, fb) in listOf(FleshProfile.GENERIC to FleshProfile.GENERIC, slim to slim, bulky to slim, bulky to bulky)) {
            val push = PairSpacing.push(c.pair, fa, fb)
            val moved = (push?.let { hypot(it.first, it.second) } ?: 0.0) * PairAlignment.AUTHORED_UNIT * PairAlignment.CM_PER_YARD
            assertTrue(moved <= tolCm, "${c.name}: moved $moved cm for ${fa.measured}/${fb.measured}")
        }
        // two clips made on one spot are still moved apart (generic and measured)
        val stacked = PartnerPair("stacked", "stacked", F.lyingBack(0.0, name = "st_a"), F.lyingBack(0.0, name = "st_b"), slot(0.0, 0.0), slot(0.0, 0.0), true)
        assertNotNull(PairSpacing.push(stacked)); assertNotNull(PairSpacing.push(stacked, bulky, bulky))
    }

    @Test fun measuredBodiesAreNeverMovedFartherThanThePlainRuleMovesThem() {
        // measured bodies may come as deep as the reference bodies do in the stage as authored and EXTRA more (never less
        // than the plain tolerance): against the plain rule (every moment CONTACT_TOLERANCE), never a longer push
        val stacked = PartnerPair("stacked", "stacked", F.lyingBack(0.0, name = "st_a"), F.lyingBack(0.0, name = "st_b"), slot(0.0, 0.0), slot(0.0, 0.05), true)
        for (c in cases().map { it.pair } + stacked) for ((fa, fb) in listOf(bulky to bulky, bulky to slim, slim to slim)) {
            val plain = PairSpacing.push(PairSpacing.frames(c.slotA, c.a, fa), PairSpacing.frames(c.slotB, c.b, fb),
                (c.slotB.x - c.slotA.x).toDouble() to (c.slotB.y - c.slotA.y).toDouble(), fa, fb)
            val now = PairSpacing.push(c, fa, fb)
            val lp = plain?.let { hypot(it.first, it.second) } ?: 0.0; val ln = now?.let { hypot(it.first, it.second) } ?: 0.0
            assertTrue(ln <= lp + 1e-9, "${c.id}: $now against the plain rule's $plain")
        }
        // a plain tolerance gives the plain rule exactly
        val a = BodyGeometry.frames(F.lyingBack(0.0, name = "pa")); val b = BodyGeometry.frames(F.lyingBack(0.0, name = "pb", dy = 0.05))
        assertEquals(PairSpacing.push(a, b), PairSpacing.push(a, b, tolerance = { PairSpacing.CONTACT_TOLERANCE }))
    }

    @Test fun twoBodiesThatMeetAreRaisedTogether() {
        assertEquals(listOf(0.3f, 0.3f), PairLift.shared(0.1f, 0.3f, true).toList())
        assertEquals(listOf(0.1f, 0.3f), PairLift.shared(0.1f, 0.3f, false).toList())
        assertEquals(listOf(-0.05f, -0.05f), PairLift.shared(-0.05f, -0.2f, true).toList(), "a slim pair let down onto its support no farther than either")
        // in the simulation: a rear-facing pair on the floor, one body bulky (it needs more lift): both are raised alike
        val pair = cases().first().pair
        val sim = SceneSimulation(listOf("a", "b"), emptySet(), Random(3), pairs = listOf(pair))
        sim.flesh = mapOf("a" to profile(0.06, FleshPart.entries.associateWith { FleshSegment(0.2, 0.3, 0.25) }))
        assertTrue(sim.partner("a", "b"))
        val p = Pose()
        var n = 0
        while (true) {
            sim.tick(1f / 60f)
            sim.pose(0, p); val ok0 = p.playback != null
            sim.pose(1, p); val ok1 = p.playback != null
            if (ok0 && ok1) break
            check(++n < 20_000)
        }
        repeat(((JoinEase.SETTLE + 0.05f) * 60f).toInt()) { sim.tick(1f / 60f) }
        sim.pose(0, p); val l0 = p.lift; val z0 = p.z
        sim.pose(1, p); val l1 = p.lift; val z1 = p.z
        assertEquals(l0, l1, 1e-4f, "raised together: $l0 and $l1")
        assertEquals(z0, z1, 1e-4f, "on one floor")
        assertTrue(l0 > 0.05f, "by the bulky one's lift: $l0")
    }

    @Test fun eachClassKeepsItsPlacementForEveryBodyPair() {
        // as the audit measures it: the pair way (spaced for the bodies, raised together) against the order way (as authored),
        // B's hips against A's, every moment, within the placement tolerance
        val bodies = listOf(PairAlignment.Body.GENERIC, PairAlignment.Body("slim", slim, 1.13), PairAlignment.Body("bulky", bulky, 1.22))
        for (c in cases()) for (ba in bodies) for (bb in bodies) {
            val p = c.pair
            val times = PairAlignment.times(p.duration)
            fun play(anim: Anim, body: PairAlignment.Body, s: PartnerSlot, lift: Double = 0.0) =
                PairAlignment.played(anim, body, times, s.x.toDouble(), s.y.toDouble(), s.z.toDouble(), s.yawDeg.toDouble(), lift)
            val oa = play(p.a, ba, p.slotA); val ob = play(p.b, bb, p.slotB)
            val sp = PairSpacing.spaced(p, ba.flesh, bb.flesh)
            val r = FurnitureAnimation.of(PairSpacing.spaced(p)).requirement
            fun own(i: Int, body: PairAlignment.Body, anim: Anim, s: PartnerSlot): Float {
                val need = r.actors[i]
                val u = if (body.flesh.measured) FleshLift.adjusted(need, BodyGeometry.frames(anim, z = s.z.toDouble(), flesh = body.flesh), body.flesh).liftUnits else need.liftUnits
                return (u * PairAlignment.AUTHORED_UNIT).toFloat()
            }
            val lifts = PairLift.shared(own(0, ba, p.a, sp.slotA), own(1, bb, p.b, sp.slotB), PairSpacing.touching(p))
            val pa = play(p.a, ba, sp.slotA, lifts[0].toDouble()); val pb = play(p.b, bb, sp.slotB, lifts[1].toDouble())
            for (i in times.indices) {
                fun hips(x: PairAlignment.Pose) = PairAlignment.lerp(x["L_Thigh"]!!, x["R_Thigh"]!!, 0.5)
                val d = PairAlignment.sub(PairAlignment.sub(hips(pb[i]), hips(pa[i])), PairAlignment.sub(hips(ob[i]), hips(oa[i])))
                val cm = PairAlignment.len(d) * PairAlignment.CM_PER_YARD
                assertTrue(cm <= tolCm, "${c.name} ${ba}/${bb}: B's hips $cm cm off A's")
            }
        }
    }

    @Test fun theRealStagesRelativeHipsMeetAndStayPut() {
        // each row: a measured stage's id hash, pose class and B's hips from A's (body units, in A's facing) as its author made
        // them; a rear-facing row placed as fixtures (A kneeling up, B on all fours in front) meets and is not moved apart
        val table = File("src/test/resources/anim/pair_alignment.csv")
        val rows = table.readLines().drop(1).filter { it.isNotBlank() }.map { it.split(',') }
        assertTrue(rows.size >= 10, "the table: ${rows.size} rows")
        var rear = 0
        for (r in rows) {
            val cls = r[2]; val dx = r[3].toDouble(); val dy = r[4].toDouble()
            assertTrue(listOf(dx, dy, r[5].toDouble(), r[6].toDouble(), r[7].toDouble()).all { it.isFinite() }, "row ${r[1]}")
            // the retarget keeps every measured stage's hips within the audit's retarget tolerance (cm)
            assertTrue(r[7].toDouble() <= PairAlignmentAudit.Tol.RETARGET_CM, "stage ${r[1]}: the retarget moved its hips ${r[7]} cm")
            if (!cls.startsWith("rear-facing kneeling")) continue
            // B (in front, on all fours, facing +x) at the measured distance ahead of A (kneeling up)
            val ahead = hypot(dx, dy)
            // hips over hips (one body on the other) or apart (no contact at the hips): not this fixture's case
            if (ahead < 0.05 || ahead > 0.6) continue
            rear++
            // the all-fours fixture's hips sit 0.097 behind its Root (the Root's pivot over the hips, tilted forward); the
            // kneeling one's under its Root
            val pair = PartnerPair("t", "t", F.kneeling(name = "t_a"), F.allFours("t_b"), slot(0.0, 0.0), slot(ahead + 0.097, 0.0), true)
            val push = PairSpacing.push(pair)
            assertTrue((push?.let { hypot(it.first, it.second) } ?: 0.0) * PairAlignment.AUTHORED_UNIT * PairAlignment.CM_PER_YARD <= tolCm,
                "stage ${r[1]} moved apart: $push")
        }
        assertTrue(rear >= 3, "rear-facing rows: $rear")
    }
}
