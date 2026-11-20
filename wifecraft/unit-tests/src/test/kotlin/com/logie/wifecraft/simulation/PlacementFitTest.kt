package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyFrame
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.anim.RequirementClassifier
import com.logie.wifecraft.anim.Surface
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.*

/**
 * The placement audit's rules as pure geometry (tools/audit/README.md): two bodies of a pair kept out of each other
 * ([PairSpacing]: synthetic capsule bodies), a clip raised off a floor its joints would reach under and onto the measured
 * height of a seat ([RequirementClassifier.groundLift], [Furniture.of]), the lift carried to the renderer ([Pose.lift], the
 * sink's raised placement). Synthetic bodies; the real meshes are the audit's (engine-test/audit), nothing on a phone.
 */
class PlacementFitTest {
    private val F = PoseFixtures
    private val step = 1f / 120f

    /** A body as joints only: a trunk from [x], [y] up, arms out sideways ([armOut] body units) at chest height. */
    private fun body(x: Double, y: Double, armOut: Double = 0.2): BodyFrame {
        fun p(dx: Double, dy: Double, z: Double) = doubleArrayOf(x + dx, y + dy, z)
        val j = mapOf(
            "Root" to p(0.0, 0.0, 1.0), "Neck1" to p(0.0, 0.0, 1.4),
            "L_UpperArm" to p(0.0, 0.12, 1.3), "L_Forearm" to p(0.0, 0.12 + armOut / 2, 1.3), "L_Hand" to p(0.0, 0.12 + armOut, 1.3),
            "R_UpperArm" to p(0.0, -0.12, 1.3), "R_Forearm" to p(0.0, -0.12 - armOut / 2, 1.3), "R_Hand" to p(0.0, -0.12 - armOut, 1.3),
            "L_Thigh" to p(0.0, 0.1, 0.92), "L_Calf" to p(0.0, 0.1, 0.5), "R_Thigh" to p(0.0, -0.1, 0.92), "R_Calf" to p(0.0, -0.1, 0.5),
        )
        return BodyFrame(j, j.keys)
    }

    private fun clearance(a: BodyFrame, b: BodyFrame, push: Pair<Double, Double>?): Double =
        PairSpacing.slack(a, b, push?.first ?: 0.0, push?.second ?: 0.0)!!.first

    @Test fun twoBodiesOnOneSpotAreMovedApartSideways() {
        val push = PairSpacing.push(listOf(body(0.0, 0.0)), listOf(body(0.0, 0.0)))
        assertNotNull(push)
        // two clips stacked on one spot: moved until they come no nearer than the contact tolerance
        assertTrue(clearance(body(0.0, 0.0), body(0.0, 0.0), push) >= -PairSpacing.CONTACT_TOLERANCE - 1e-6, "after the push: $push")
        // on one axis the way apart is the first body's side (its thighs), not along its facing
        assertTrue(kotlin.math.abs(push.second) > kotlin.math.abs(push.first), "$push")
        // and between the slots when the pair names one
        val toward = PairSpacing.push(listOf(body(0.0, 0.0)), listOf(body(0.0, 0.0)), 1.0 to 0.0)!!
        assertTrue(toward.first > 0.05 && kotlin.math.abs(toward.second) < 1e-6, "$toward")
    }

    @Test fun bodiesFarEnoughApartStayWhereTheyAre() {
        assertNull(PairSpacing.push(listOf(body(0.0, 0.0)), listOf(body(0.0, 0.8))))
    }

    @Test fun anArmAcrossTheOtherTrunkIsContactNotMoved() {
        // side by side 0.4 apart, the first one's left arm reaching 0.45 out: across the second one's chest, as an arm round
        // someone is (an embrace): within the contact tolerance, left as it is made
        val a = body(0.0, 0.0, armOut = 0.45)
        val b = body(0.0, 0.4, armOut = 0.0)
        assertTrue(clearance(a, b, null) < 0)
        assertTrue(clearance(a, b, null) > -PairSpacing.CONTACT_TOLERANCE)
        assertNull(PairSpacing.push(listOf(a), listOf(b)))
        // two trunks 0.05 apart (two clips almost on one spot) come 0.21 into each other: moved
        val push = PairSpacing.push(listOf(body(0.0, 0.0, armOut = 0.0)), listOf(body(0.0, 0.05, armOut = 0.0)))!!
        assertTrue(push.second > 0.0, "moved apart sideways: $push")
        assertTrue(clearance(body(0.0, 0.0, armOut = 0.0), body(0.0, 0.05, armOut = 0.0), push) >= -PairSpacing.CONTACT_TOLERANCE - 1e-6)
    }

    @Test fun aStageMadeForContactIsMovedLittle() {
        fun pair(id: String) = PartnerPair(id, id, F.lyingBack(0.55, name = "${id}_a"), F.lyingBack(0.55, name = "${id}_b"),
            PartnerSlot.human(0f, 0f, 0f, 0f), PartnerSlot.human(0f, 0f, 0f, 0f), true)
        // by the stage's name (the cap itself: FleshContactTest.aStageMadeForContactIsMovedLittle)
        assertTrue(PairSpacing.contact(pair("Kissing")) && PairSpacing.contact(pair("Cradle")) && PairSpacing.contact(pair("Embrace")) &&
            PairSpacing.contact(pair("hug_2")) && PairSpacing.contact(pair("Cuddle")) && !PairSpacing.contact(pair("stacked")))
    }

    @Test fun thePushIsBounded() {
        val push = PairSpacing.push(listOf(body(0.0, 0.0)), listOf(body(0.0, 0.0)), maxPush = 0.03)!!
        assertTrue(hypot(push.first, push.second) <= 0.03 + 1e-9)
    }

    @Test fun aSpoonMadeOnOneRootIsSpacedInTheSimulation() {
        fun pair(id: String, a: Anim, b: Anim, dy: Double) = PartnerPair(id, id, a, b, PartnerSlot.human(0f, 0f, 0f, 0f),
            PartnerSlot.human(0f, (dy * BodyGeometry.REF_UNIT).toFloat(), 0f, 0f), true)
        val spoon = pair("spoon", F.lyingBack(0.55, name = "s_a"), F.lyingBack(0.55, name = "s_b"), 0.0)
        val sim = SceneSimulation(listOf("a", "b"), setOf(SceneObject.BED), Random(1), pairs = listOf(spoon))
        val spaced = sim.spaced(spoon)
        assertNotSame(spoon, spaced)
        // (moved until the trunks come no nearer than the contact tolerance)
        assertTrue(hypot(spaced.slotB.x - spaced.slotA.x, spaced.slotB.y - spaced.slotA.y) > 0.05f)
        assertTrue(sim.partner("a", "b"))
        var n = 0
        while (sim.partnerOf("a")?.stage != "PLAY") { sim.tick(step); check(++n < 20_000) }
        val pa = sim.position("a")!!; val pb = sim.position("b")!!
        assertTrue(hypot(pa.x - pb.x, pa.y - pb.y) > 0.05f, "not on one axis: $pa $pb")
        // a side-by-side pair already apart is left as it is
        // (the fixtures' arms stand straight out, about half a body unit each side: 1.3 apart they clear each other's trunk)
        val apart = pair("apart", F.lyingBack(0.55, name = "p_a"), F.lyingBack(0.55, name = "p_b"), 1.3)
        assertSame(apart, sim.spaced(apart))
    }

    @Test fun aBodyWhoseJointsReachUnderTheFloorIsRaised() {
        // the reference body standing: its toes' flesh just on the floor, nothing to raise
        val standing = BodyGeometry.frames(F.standing)
        assertEquals(0.0, RequirementClassifier.groundLift(standing), 1e-3)
        assertEquals(0f, RequirementClassifier.classify(F.standing).actors[0].liftUnits, 1e-3f)
        // the same body 0.1 body units down (a clip made for a thinner body: its hips in the floor) is raised by that much
        val sunk = standing.map { f -> BodyFrame(f.joints.mapValues { (_, v) -> doubleArrayOf(v[0], v[1], v[2] - 0.1) }, f.driven) }
        assertEquals(0.1, RequirementClassifier.groundLift(sunk), 0.01)
        // relative to a support: a body lying on a bed, its joints over the mattress, needs little (its heels' flesh)
        assertTrue(RequirementClassifier.classify(F.lyingBack(0.55)).actors[0].liftUnits < 0.08f)
    }

    @Test fun aSitWithItsHipsWellOffTheFloorNeedsASeat() {
        // hips 0.2 m up with the feet down is a low seat's sit, not the floor's (the pose pack's 4, 11, 12, 14)
        val r = RequirementClassifier.classify(F.sitting(0.2))
        assertEquals(Surface.ANY_SEAT, r.surface)
        assertEquals(Surface.FLOOR, RequirementClassifier.classify(F.sitting(0.05)).surface)
    }

    @Test fun aSeatTakesItsMeasuredHeight() {
        val spot = InteractionSpot(Vec3(0.9f, 0f, 0f), Vec3(0f, 0f, 0.61f), 0f)
        assertEquals(0.61f, Furniture.of(SceneObject.COUCH, spot).spec.surface, 1e-4f)
        assertEquals(0.61f, Furniture.of(SceneObject.CHAIR, spot).spec.surface, 1e-4f)
        // an unmeasured spot (on the floor) keeps the usual height
        val usual = FurnitureSpec.standard(FurnitureKind.CHAIR).surface
        assertEquals(usual, Furniture.of(SceneObject.CHAIR, InteractionSpot(Vec3(0.9f, 0f, 0f), Vec3(0f, 0f, 0.05f), 0f)).spec.surface, 1e-4f)
    }

    @Test fun aPairOnTheBedIsPlacedRaisedOverItsFloor() {
        // a pair lying at a 0.55 m surface on a bed whose mattress is 0.95 yards up: the fit raises the clips; the pose says
        // how far over the floor, and the live scene's sink is told to draw them there (sz the floor, under z)
        val p = PartnerPair("lie", "lie", F.lyingBack(0.55, name = "l_a"), F.lyingBack(0.55, name = "l_b"), PartnerSlot.human(0f, 0f, 0f, 0f),
            PartnerSlot.human(0f, (0.6 * BodyGeometry.REF_UNIT).toFloat(), 0f, 0f), true)
        val room = CameraPose(Vec3(0f, -4f, 3f), Vec3(0f, 0f, 1f), 50f)
        val live = LiveScene(listOf("a", "b"), setOf(SceneObject.BED), emptyMap(), room, 0f, 0f, 0f, 0f, Random(5),
            pairs = listOf(p), partnerChance = 0f)
        live.running = true
        val placed = HashMap<Int, FloatArray>()
        val sink = object : LiveScene.Sink {
            override fun camera(eyeX: Float, eyeY: Float, eyeZ: Float, atX: Float, atY: Float, atZ: Float, fov: Float) {}
            override fun place(index: Int, x: Float, y: Float, z: Float, facing: Float, furniture: SceneObject?, sx: Float, sy: Float, sz: Float) {
                placed[index] = floatArrayOf(z, sz, if (furniture == null) 0f else 1f)
            }
            override fun activity(index: Int, activity: Activity) {}
        }
        live.command { it.setSpot(SceneObject.BED, InteractionSpot(Vec3(2.6f, 1.2f, 0f), Vec3(2.6f, 2.2f, 0.95f), -1.5707964f)) }
        var started: String? = "not run"
        // (the live screen reads the pairs off the GL thread and hands the simulation what they need)
        live.command { it.requirements = mapOf(p.id to FurnitureAnimation.of(p).requirement) }
        live.command { started = if (it.partnerOn(SceneObject.BED, "lie", "a", "b")) null else it.takeRefusal() ?: "refused" }
        var t = 0L
        var frames = 0
        // the most it was raised while playing, by the pose and by what the sink was told
        var lift = 0f
        var drawn = 0f
        var floorSeen = Float.NaN
        while (frames < 6000) {
            live.frame(t, sink) {}
            t += 16_666_667L; frames++
            live.command { sim ->
                val pose = Pose()
                sim.pose(0, pose)
                if (pose.playback != null) {
                    lift = maxOf(lift, pose.lift)
                    placed[0]?.let { if (it[2] == 0f && it[0] - it[1] > drawn) { drawn = it[0] - it[1]; floorSeen = it[1] } }
                }
            }
        }
        assertNull(started, "partnerOn")
        assertTrue(lift > 0.1f, "the pose's lift: $lift")
        assertTrue(drawn > 0.1f, "the sink is told to draw them raised over their floor: $drawn")
        assertEquals(0f, floorSeen, 0.02f)
    }
}
