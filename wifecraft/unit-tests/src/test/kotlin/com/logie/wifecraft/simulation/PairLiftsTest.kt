package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.ActorRequirement
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.FleshLift
import com.logie.wifecraft.anim.FleshPart
import com.logie.wifecraft.anim.FleshProfile
import com.logie.wifecraft.anim.FleshSegment
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.anim.Posture
import com.logie.wifecraft.anim.Q
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.RequirementClassifier
import com.logie.wifecraft.anim.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The audit's leftovers (README, "Alignment audit"): bodies lying on a bed rest on it by their trunk, not their hanging
 * feet; stages made in water are raised out of the floor as one; and the hips of two measured bodies kneeling or lying are
 * matched by their own legs ([PairLifts], [PairWater], [PairHeight]).
 */
class PairLiftsTest {
    private val F = PoseFixtures
    private val U = floatArrayOf(1.17f, 1.17f)

    private fun profile(r: Double, over: Map<FleshPart, FleshSegment> = emptyMap(), lowestStanding: Float = 0f): FleshProfile {
        val out = ArrayList<Float>()
        out += listOf(1f, 1.17f, lowestStanding, FleshPart.entries.size.toFloat())
        for (p in FleshPart.entries) {
            val s = over[p] ?: FleshSegment(r, r, r)
            out += listOf(1f, s.front, s.back, s.side, s.start, s.end, s.tip).map { it.toFloat() }
        }
        out += BodyGeometry.JOINTS.size.toFloat()
        for (j in BodyGeometry.JOINTS) out += BodyGeometry.REF.getValue(j).map { it.toFloat() }
        return FleshProfile.of(out.toFloatArray())!!
    }

    /** About the human man's measured trunk and thighs (engine-test/audit's human_m). */
    private val man = profile(0.07, FleshPart.TRUNK.associateWith { FleshSegment(0.12, 0.16, 0.19) } +
        FleshPart.THIGHS.associateWith { FleshSegment(0.1, 0.1, 0.11) })

    /** On the back on a bed [surfaceM] up, head toward -x, the thighs along the bed and the shins hanging straight down over its edge. */
    private fun legsOverTheEdge(surfaceM: Double, name: String) =
        F.pose(name, mapOf("Root" to F.rotY(-90.0), "L_Calf" to Q.ID, "R_Calf" to Q.ID), surfaceM / 0.95 + 0.11 + 0.02)

    @Test fun aBodyLyingWithItsLegsOverTheEdgeRestsOnItsBackNotItsFeet() {
        val flat = RequirementClassifier.classify(F.lyingBack(0.55, name = "flat")).actors[0]
        val hang = RequirementClassifier.classify(legsOverTheEdge(0.55, "hang")).actors[0]
        assertTrue(hang.posture.lying, "${hang.posture}")
        assertEquals(Surface.BED, hang.surface)
        // its feet hang about half a body unit under the bed; it is lifted as the body lying flat is, not by them
        val feet = BodyGeometry.frames(legsOverTheEdge(0.55, "hang2")).first()["L_Foot"]!![2]
        assertTrue(feet < hang.heightUnits - 0.2, "the feet hang under the bed: $feet against ${hang.heightUnits}")
        assertEquals(flat.liftUnits, hang.liftUnits, 0.02f)
        assertTrue(hang.liftUnits < 0.05f, "the classifier's lift: ${hang.liftUnits}")
        // a measured body: its own trunk rests on the bed, the pelvis over it by about its back's thickness (0.16), never
        // half a body unit (55 cm) over or under it
        val frames = BodyGeometry.frames(legsOverTheEdge(0.55, "hang3"), flesh = man)
        val lift = FleshLift.liftUnits(hang, frames, man)
        val flatLift = FleshLift.liftUnits(flat, BodyGeometry.frames(F.lyingBack(0.55, name = "flat2"), flesh = man), man)
        assertEquals(flatLift, lift, 0.02)
        val rootOver = frames.first()["Root"]!![2] + lift - hang.heightUnits
        assertTrue(rootOver in 0.1..0.25, "the pelvis $rootOver over the bed")
        val trunkLow = frames.minOf { man.lowestOver(it, FleshLift.LYING)!! } + lift
        assertEquals(hang.heightUnits.toDouble(), trunkLow, 0.03, "the trunk on the bed")
    }

    @Test fun whetherABodyLiesOnItsTrunkIsReadOnTheReferenceSkeleton() {
        // a body on its back, its legs over the bed's edge; on a measured body's own rig its pelvis stands higher (a woman in
        // Eve's rig: a few centimetres), past PELVIS_REST over the bed by the median: read on its own skeleton the rule
        // flipped and its hanging shins lifted it; read on the reference skeleton (the stage as authored) it lies on its trunk
        val clip = legsOverTheEdge(0.55, "hang4")
        val need = RequirementClassifier.classify(clip).actors[0]
        val ref = BodyGeometry.frames(clip)
        val over = ref.map { it["Root"]!![2] - need.heightUnits }.sorted().let { it[it.size / 2] }
        val up = RequirementClassifier.PELVIS_REST - over + 0.02
        val own = ref.map { f -> com.logie.wifecraft.anim.BodyFrame(f.joints.mapValues { (_, v) -> doubleArrayOf(v[0], v[1], v[2] + up) }, f.driven) }
        assertTrue(RequirementClassifier.pelvisRests(ref, need.heightUnits.toDouble()))
        assertFalse(RequirementClassifier.pelvisRests(own, need.heightUnits.toDouble()), "the own rig's pelvis past the rule")
        assertEquals(FleshLift.LYING, FleshLift.bearing(need, ref))
        val byRef = FleshLift.liftUnits(need, own, man, ref)
        val trunk = FleshLift.groundLift(own, man, need.heightUnits.toDouble(), FleshLift.LYING)
        assertEquals(trunk, byRef, 1e-9, "lifted by its trunk")
        // (on its own skeleton the shins would have lifted it by more: the flip the audit found)
        assertTrue(FleshLift.liftUnits(need, own, man) > byRef + 0.1)
    }

    @Test fun aBodyLyingWithItsHipsRaisedIsBorneByItsFeet() {
        // the pelvis held well over the support (a bridge): it doesn't rest, so the feet count
        val frames = BodyGeometry.frames(F.lyingBack(0.0, name = "bridge"))
        assertTrue(RequirementClassifier.pelvisRests(frames, 0.0))
        assertFalse(RequirementClassifier.pelvisRests(frames, -0.4))
    }

    @Test fun theFeetAreReadByTheirSoles() {
        // a profile whose foot capsule reaches 0.05 under the soles standing while the engine measured the soles at 0
        val p = profile(0.07)
        assertTrue(p.footBias > 0.02, "${p.footBias}")
        val rest = BodyFrame0.of()
        val raw = p.lowest(rest, listOf(FleshPart.L_FOOT, FleshPart.R_FOOT))!!
        assertEquals(0.0, p.lowestOver(rest, listOf(FleshPart.L_FOOT, FleshPart.R_FOOT))!!, 1e-6, "standing at rest: on its soles (raw $raw)")
        // the generic body: as before
        assertEquals(0.0, FleshProfile.GENERIC.footBias)
        // a standing clip is lifted by its soles only
        val need = RequirementClassifier.classify(F.standing).actors[0]
        assertEquals(0.0, FleshLift.liftUnits(need, BodyGeometry.frames(F.standing, flesh = p), p), 0.02)
    }

    private object BodyFrame0 {
        fun of() = com.logie.wifecraft.anim.BodyFrame(BodyGeometry.REF.mapValues { (_, v) -> DoubleArray(3) { v[it] / BodyGeometry.REF_UNIT } }, BodyGeometry.REF.keys)
    }

    private fun actor(posture: Posture, surface: Surface, lift: Float, feet: Boolean = false, heightUnits: Float = 0f) =
        ActorRequirement(posture, surface, heightUnits * 0.95f, heightUnits, 0f, 0f, 0f, 0f, 0f, 0f, 0f, feet, 0.9f, "", lift)

    private fun pair(name: String, place: String = "") = PartnerPair(name, name, F.standing, F.standing, PartnerSlot(0f, 0f, 0f, 0f),
        PartnerSlot(0.5f, 0f, 0f, 180f), true, if (place.isEmpty()) emptySet() else setOf(place))

    @Test fun aStageMadeInWaterIsRaisedOutOfTheFloorAsOne() {
        // both sitting in a hot spring 1.2 m under the floor, not touching by the rules: raised together by the deeper one
        val spring = Requirement(Surface.FLOOR, listOf(actor(Posture.SIT, Surface.FLOOR, 1.2f), actor(Posture.SIT, Surface.FLOOR, 1.25f)), 0.9f, "", "geometry")
        val p = pair("Hot Spring 3")
        assertTrue(PairWater.of(spring, p))
        assertFalse(PairWater.needsWater(spring, p))
        val u = 1.17f
        val placed = PairLifts.of(spring, p, floatArrayOf(1.2f * u, 1.25f * u), floatArrayOf(1.2f * u, 1.25f * u), false, null, null, U)
        assertTrue(placed.water)
        assertEquals(placed.lifts[0], placed.lifts[1], "one piece")
        assertTrue(placed.lifts.all { it >= 1.25f * u - 1e-5f }, "neither under the floor: ${placed.lifts.toList()}")
        // one on the pool's edge, one standing in the pool 1.8 m deep: it needs water; the NPCs leave it alone
        val pool = Requirement(Surface.FLOOR, listOf(actor(Posture.SIT, Surface.FLOOR, 0.05f), actor(Posture.STAND, Surface.NONE, 1.8f, feet = true)), 0.9f, "", "geometry")
        assertTrue(PairWater.needsWater(pool, pair("Poolside Cunnilingus")))
        // a shallow bathroom stage is no water; two bodies on the floor that don't meet keep their own lifts
        val loo = Requirement(Surface.ANY_SEAT, listOf(actor(Posture.SIT, Surface.ANY_SEAT, 0.1f), actor(Posture.STAND, Surface.NONE, 0.08f, feet = true)), 0.9f, "", "geometry")
        assertFalse(PairWater.of(loo, pair("Bathroom Shenanigans", "toilet")))
        val apart = PairLifts.of(loo, pair("Bathroom Shenanigans"), floatArrayOf(0.1f, 0.08f), floatArrayOf(0.1f, 0.08f), false, null, null, U)
        assertEquals(listOf(0.1f, 0.08f), apart.lifts.toList())
        // a bath stage, its name saying so, 25 cm deep: water
        val bath = Requirement(Surface.FLOOR, listOf(actor(Posture.SIT, Surface.FLOOR, 0.28f), actor(Posture.KNEEL, Surface.FLOOR, 0.1f)), 0.9f, "", "geometry")
        assertTrue(PairWater.of(bath, pair("Fingered in the Bath", "bathtub")))
    }

    @Test fun aBodyOnTopOfTheOtherIsRaisedWithTheOneUnderIt() {
        // one lying on the bed, the other lying on top of it (its support 0.3 higher): raised by the one on the bed, not by
        // the one on top's own lift (its arms reaching down past its support to the bed)
        val stack = Requirement(Surface.BED, listOf(actor(Posture.LIE_BACK, Surface.BED, 0.04f, heightUnits = 0.58f),
            actor(Posture.LIE_FRONT, Surface.BED, 0.35f, heightUnits = 0.88f)), 0.9f, "", "geometry")
        assertEquals(0, PairLifts.under(stack))
        val placed = PairLifts.of(stack, pair("stack"), floatArrayOf(0.05f, 0.3f), floatArrayOf(0.05f, 0.3f), true, null, null, U)
        assertEquals(listOf(0.05f, 0.05f), placed.lifts.toList())
        // one on top whose own flesh reaches the bed past its support (it needs more lift than its support is over the
        // other's: 0.5 against 0.3 x 1.17): raised by as much as keeps that flesh out of the bed, no more
        val room = 0.3f * 1.17f
        val reach = PairLifts.of(stack, pair("stack"), floatArrayOf(0.05f, 0.5f), floatArrayOf(0.05f, 0.5f), true, null, null, U).lifts.toList()
        assertEquals(0.5f - room, reach[0], 1e-5f); assertEquals(reach[0], reach[1])
        // continuous in the flesh: the one on top's lift a little past the room moves the pair a little, never by a stride
        // (before, a guard switched to the whole of its own lift there: 3 cm less flesh under it moved the pair 5 to 14 cm)
        for (top in listOf(room + 0.04f, room + 0.05f, room + 0.06f, room + 0.08f)) {
            val a = PairLifts.of(stack, pair("stack"), floatArrayOf(0.05f, top), floatArrayOf(0.05f, top), true, null, null, U).lifts[0]
            val b = PairLifts.of(stack, pair("stack"), floatArrayOf(0.02f, top), floatArrayOf(0.02f, top), true, null, null, U).lifts[0]
            assertTrue(kotlin.math.abs(a - b) <= 0.03f + 1e-5f, "under's lift 3 cm apart moves the pair ${a - b}")
        }
        // side by side on one support: the more either needs, as before; someone standing: no one is on top
        val side = Requirement(Surface.BED, listOf(actor(Posture.LIE_BACK, Surface.BED, 0.04f, heightUnits = 0.58f),
            actor(Posture.LIE_BACK, Surface.BED, 0.08f, heightUnits = 0.6f)), 0.9f, "", "geometry")
        assertEquals(-1, PairLifts.under(side))
        assertEquals(listOf(0.4f, 0.4f), PairLifts.of(side, pair("side"), floatArrayOf(0.05f, 0.4f), floatArrayOf(0.05f, 0.4f), true, null, null, U).lifts.toList())
        val beside = Requirement(Surface.BED, listOf(actor(Posture.STAND, Surface.NONE, 0.06f, feet = true),
            actor(Posture.LIE_BACK, Surface.BED, 0.04f, heightUnits = 0.58f)), 0.9f, "", "geometry")
        assertEquals(-1, PairLifts.under(beside))
    }

    @Test fun kneelingHipsAreMatchedByTheBodiesOwnLegs() {
        val kneel = Requirement(Surface.FLOOR, listOf(actor(Posture.KNEEL, Surface.FLOOR, 0.05f), actor(Posture.ALL_FOURS, Surface.FLOOR, 0.05f)), 0.9f, "", "geometry")
        assertTrue(PairHeight.eligible(kneel))
        // the first's hips come 4 cm under where the author put them against the second's: it is raised by that
        val a = PairHeight.Hips(measured = 0.40f, authored = 0.42f); val b = PairHeight.Hips(measured = 0.45f, authored = 0.43f)
        val p = pair("kneel")
        val placed = PairLifts.of(kneel, p, floatArrayOf(0.06f, 0.06f), floatArrayOf(0.06f, 0.06f), true, a, b, U)
        assertEquals(0.04f, placed.hipRaise[0], 1e-5f); assertEquals(0f, placed.hipRaise[1])
        assertEquals(0.10f, placed.lifts[0], 1e-5f); assertEquals(0.06f, placed.lifts[1], 1e-5f)
        // never lowered, and never more than MAX_RAISE
        val far = PairLifts.of(kneel, p, floatArrayOf(0.06f, 0.06f), floatArrayOf(0.06f, 0.06f), true, a, PairHeight.Hips(0.60f, 0.43f), U)
        assertEquals(PairHeight.MAX_RAISE, far.hipRaise[0], 1e-6f)
        val other = PairLifts.of(kneel, p, floatArrayOf(0.06f, 0.06f), floatArrayOf(0.06f, 0.06f), true, PairHeight.Hips(0.44f, 0.42f), PairHeight.Hips(0.40f, 0.43f), U)
        assertEquals(0f, other.hipRaise[0]); assertEquals(0.05f.coerceAtMost(PairHeight.MAX_RAISE), other.hipRaise[1], 1e-5f)
        // standing on their feet, or apart, or on two supports: their hips as their legs make them, the feet on the floor
        val stand = Requirement(Surface.NONE, listOf(actor(Posture.STAND, Surface.NONE, 0.06f, feet = true), actor(Posture.STAND, Surface.NONE, 0.06f, feet = true)), 0.9f, "", "geometry")
        assertFalse(PairHeight.eligible(stand))
        assertEquals(listOf(0f, 0f), PairLifts.of(stand, p, floatArrayOf(0.06f, 0.06f), floatArrayOf(0.06f, 0.06f), true, a, b, U).hipRaise.toList())
        assertEquals(listOf(0f, 0f), PairLifts.of(kneel, p, floatArrayOf(0.06f, 0.06f), floatArrayOf(0.06f, 0.06f), false, a, b, U).hipRaise.toList())
        val split = Requirement(Surface.BED, listOf(actor(Posture.KNEEL, Surface.FLOOR, 0.05f), actor(Posture.LIE_BACK, Surface.BED, 0.05f, heightUnits = 0.58f)), 0.9f, "", "geometry")
        assertFalse(PairHeight.eligible(split))
        // the same bodies give the same hips: nothing to match
        val same = PairHeight.hips(F.kneeling(name = "k"), 0f, FleshProfile.GENERIC, 1.17f, 1.17f)!!
        assertEquals(same.measured, same.authored)
    }
}
