package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyFrame
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.FleshLift
import com.logie.wifecraft.anim.FleshPart
import com.logie.wifecraft.anim.FleshProfile
import com.logie.wifecraft.anim.FleshSegment
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.anim.Posture
import com.logie.wifecraft.anim.RequirementClassifier
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.*

/**
 * Bodies' own flesh (anim/FleshProfile, engine/flesh.h: each person's dressed model measured): the engine's floats read,
 * the generic body giving the old rules exactly, a bulkier body raised more off the floor and a seat, a pair of bulky
 * bodies kept farther apart (by their front, back or side as they meet), the simulation using each person's own, and the
 * JNI names matching Native.kt. Synthetic bodies; the real ones are measured by engine-test/audit's `flesh`.
 */
class FleshContactTest {
    private val F = PoseFixtures

    /** A profile of round parts of radius [r] (body units) but the [over]s, on the reference skeleton's own joints. */
    private fun profile(r: Double, over: Map<FleshPart, FleshSegment> = emptyMap()): FleshProfile =
        FleshProfile.of(packed(FleshPart.entries.associateWith { over[it] ?: FleshSegment(r, r, r) }))!!

    /** The engine's layout (FleshMeasure::pack) of [segs] and [rest]. */
    private fun packed(segs: Map<FleshPart, FleshSegment>, rest: Map<String, DoubleArray>? = BodyGeometry.REF, version: Float = 1f): FloatArray {
        val out = ArrayList<Float>()
        out += listOf(version, 1.1f, 0f, FleshPart.entries.size.toFloat())
        for (p in FleshPart.entries) {
            val s = segs[p]
            if (s == null) out += List(7) { 0f }
            else out += listOf(1f, s.front, s.back, s.side, s.start, s.end, s.tip).map { it.toFloat() }
        }
        out += BodyGeometry.JOINTS.size.toFloat()
        for (j in BodyGeometry.JOINTS) {
            val v = rest?.get(j)
            if (v == null) out += listOf(Float.NaN, Float.NaN, Float.NaN) else out += v.map { it.toFloat() }
        }
        return out.toFloatArray()
    }

    @Test fun theEnginesFloatsAreRead() {
        val thighs = FleshSegment(0.08, 0.12, 0.09, 0.0, 0.01)
        val p = FleshProfile.of(packed(FleshPart.entries.associateWith { if (it == FleshPart.L_THIGH) thighs else FleshSegment(0.1, 0.1, 0.1) }))
        assertNotNull(p)
        assertTrue(p.measured)
        assertEquals(0.12, p.segment(FleshPart.L_THIGH).back, 1e-6)
        assertEquals(0.01, p.segment(FleshPart.L_THIGH).end, 1e-6)
        assertEquals(BodyGeometry.REF.getValue("L_Calf")[2], p.rest!!.getValue("L_Calf")[2], 1e-6)
        // another version, or no trunk: not read
        assertNull(FleshProfile.of(packed(FleshPart.entries.associateWith { FleshSegment(0.1, 0.1, 0.1) }, version = 2f)))
        assertNull(FleshProfile.of(packed(FleshPart.entries.filter { it != FleshPart.PELVIS }.associateWith { FleshSegment(0.1, 0.1, 0.1) })))
        assertNull(FleshProfile.of(null))
        // a missing joint: the reference skeleton instead of its own
        val partial = FleshProfile.of(packed(FleshPart.entries.associateWith { FleshSegment(0.1, 0.1, 0.1) }, BodyGeometry.REF - "Head"))!!
        assertNull(partial.rest)
    }

    @Test fun theGenericBodyKeepsTheOldRules() {
        val g = FleshProfile.GENERIC
        assertFalse(g.measured)
        val lying = F.lyingBack(0.0, name = "lb")
        val need = RequirementClassifier.classify(lying).actors[0]
        assertEquals(need.liftUnits.toDouble(), FleshLift.liftUnits(need, BodyGeometry.frames(lying), g), 0.0)
        val a = BodyGeometry.frames(F.lyingBack(0.0, name = "a"))
        val b = BodyGeometry.frames(F.lyingBack(0.0, name = "b", dy = 0.1))
        assertEquals(PairSpacing.push(a, b), PairSpacing.push(a, b, 0.0 to 0.0, g, g))
    }

    @Test fun aBodyOnItsOwnSkeletonIsTheSameWhenItsSkeletonIsTheReferences() {
        val p = profile(0.1)
        val clip = F.sitting(0.47, name = "s")
        val ref = BodyGeometry.frames(clip)
        val own = BodyGeometry.frames(clip, flesh = p)
        for ((i, f) in ref.withIndex()) for (j in BodyGeometry.JOINTS) {
            val x = f[j]!!; val y = own[i][j]!!
            for (k in 0..2) assertEquals(x[k], y[k], 1e-6, j)   // (the engine's floats)
        }
        // a skeleton with longer shins: its feet land lower under the same clip
        val tall = BodyGeometry.REF.mapValues { (k, v) -> if (k in setOf("L_Foot", "L_Toe", "R_Foot", "R_Toe")) doubleArrayOf(v[0], v[1], v[2] - 0.1) else v }
        val t = FleshProfile.of(packed(FleshPart.entries.associateWith { FleshSegment(0.1, 0.1, 0.1) }, tall))!!
        val longer = BodyGeometry.frames(clip, flesh = t)
        assertTrue(longer[0]["L_Foot"]!![2] < ref[0]["L_Foot"]!![2] - 0.05, "the body's own shins")
    }

    @Test fun theFrontIsTheBodysFront() {
        val p = profile(0.1)
        val standing = BodyGeometry.frames(F.standing).first()
        assertTrue(p.frame(FleshPart.SPINE, standing)!!.n[0] > 0.9)
        assertTrue(p.frame(FleshPart.L_THIGH, standing)!!.n[0] > 0.9)
        assertTrue(p.frame(FleshPart.L_FOOT, standing)!!.n[2] > 0.5, "the foot's front is its top")
        // sitting: the thigh's back faces down
        val sit = BodyGeometry.frames(F.sitting(0.47, name = "s")).first()
        assertTrue(p.frame(FleshPart.L_THIGH, sit)!!.n[2] > 0.9)
        // lying on the back: the trunk's back faces down
        val lie = BodyGeometry.frames(F.lyingBack(0.0, name = "l")).first()
        assertTrue(p.frame(FleshPart.CHEST, lie)!!.n[2] > 0.9)
    }

    @Test fun aBulkierBodyIsRaisedOffTheFloorByItsOwnFlesh() {
        val clip = F.lyingBack(0.0, name = "floor")
        val need = RequirementClassifier.classify(clip).actors[0]
        assertTrue(need.posture.lying)
        val slim = profile(0.05)
        val bulky = profile(0.05, FleshPart.TRUNK.associateWith { FleshSegment(0.2, 0.3, 0.25) })
        val slimLift = FleshLift.liftUnits(need, BodyGeometry.frames(clip, flesh = slim), slim)
        val bulkyLift = FleshLift.liftUnits(need, BodyGeometry.frames(clip, flesh = bulky), bulky)
        assertTrue(bulkyLift > slimLift + 0.15, "slim $slimLift, bulky $bulkyLift")
        // raised by it, its back rests on the floor: its lowest flesh at the support, none under
        val frames = BodyGeometry.frames(clip, z = bulkyLift * BodyGeometry.REF_UNIT, flesh = bulky)
        val low = frames.minOf { bulky.lowest(it, FleshPart.entries)!! }
        assertEquals(need.heightUnits.toDouble(), low, 1e-6)
        // the lift is the need's ([ActorRequirement.restUnits] puts the support lower by it)
        val adjusted = FleshLift.adjusted(need, BodyGeometry.frames(clip, flesh = bulky), bulky)
        assertEquals(bulkyLift.toFloat(), adjusted.liftUnits, 1e-6f)
    }

    @Test fun aSeatBearsTheHipsAndThighsOwnFlesh() {
        val clip = F.sitting(0.47, name = "chair")
        val need = RequirementClassifier.classify(clip).actors[0]
        assertEquals(Posture.SIT, need.posture)
        // (the classifier puts the seat 0.1 under the hip joints: a thigh thinner than that underneath rests on it)
        val thin = profile(0.05, mapOf(FleshPart.L_THIGH to FleshSegment(0.1, 0.12, 0.1), FleshPart.R_THIGH to FleshSegment(0.1, 0.12, 0.1)))
        val thick = profile(0.05, mapOf(FleshPart.L_THIGH to FleshSegment(0.1, 0.32, 0.1), FleshPart.R_THIGH to FleshSegment(0.1, 0.32, 0.1)))
        val a = FleshLift.liftUnits(need, BodyGeometry.frames(clip, flesh = thin), thin)
        val b = FleshLift.liftUnits(need, BodyGeometry.frames(clip, flesh = thick), thick)
        assertTrue(a > 0.0 && a < 0.06, "the thin thighs: $a")
        // the thighs lie flat: the thick ones' backs are 0.2 deeper, the seat goes 0.2 lower under them
        assertEquals(0.2, b - a, 1e-3)
        // the shins and feet hang in front of the seat: a thick foot raises nothing
        val feet = profile(0.05, mapOf(FleshPart.L_THIGH to FleshSegment(0.1, 0.12, 0.1), FleshPart.R_THIGH to FleshSegment(0.1, 0.12, 0.1),
            FleshPart.L_FOOT to FleshSegment(0.5, 0.5, 0.5), FleshPart.R_FOOT to FleshSegment(0.5, 0.5, 0.5)))
        assertEquals(a, FleshLift.liftUnits(need, BodyGeometry.frames(clip, flesh = feet), feet), 1e-9)
    }

    /** A standing body as joints at [x], [y] facing [yawDeg] (body units): arms down at its sides, legs straight. */
    private fun body(x: Double, y: Double, yawDeg: Double = 0.0): BodyFrame {
        val c = kotlin.math.cos(Math.toRadians(yawDeg)); val s = kotlin.math.sin(Math.toRadians(yawDeg))
        fun p(dx: Double, dy: Double, z: Double) = doubleArrayOf(x + dx * c - dy * s, y + dx * s + dy * c, z)
        val j = HashMap<String, DoubleArray>()
        j["Root"] = p(0.0, 0.0, 1.0); j["Pelvis"] = p(0.0, 0.0, 1.0); j["Spine1"] = p(0.0, 0.0, 1.03); j["Spine2"] = p(0.0, 0.0, 1.07)
        j["Chest"] = p(0.0, 0.0, 1.12); j["Neck1"] = p(0.0, 0.0, 1.37); j["Head"] = p(0.0, 0.0, 1.43)
        for ((side, k) in listOf("L" to 1.0, "R" to -1.0)) {
            j["${side}_Clavicle"] = p(0.0, 0.04 * k, 1.3); j["${side}_UpperArm"] = p(0.0, 0.12 * k, 1.32)
            j["${side}_Forearm"] = p(0.0, 0.14 * k, 1.12); j["${side}_Hand"] = p(0.0, 0.15 * k, 0.95)
            j["${side}_Thigh"] = p(0.0, 0.07 * k, 0.92); j["${side}_Calf"] = p(0.0, 0.08 * k, 0.5)
            j["${side}_Foot"] = p(0.0, 0.08 * k, 0.07); j["${side}_Toe"] = p(0.1, 0.08 * k, 0.02)
        }
        return BodyFrame(j, j.keys)
    }

    @Test fun twoBulkyBodiesAreKeptFartherApartThanTwoSlimOnes() {
        // side by side, 0.4 apart
        val a = listOf(body(0.0, 0.0)); val b = listOf(body(0.0, 0.4))
        val slim = profile(0.06)
        val bulky = profile(0.06, FleshPart.TRUNK.associateWith { FleshSegment(0.2, 0.2, 0.4) })
        assertNull(PairSpacing.push(a, b, 0.0 to 0.0, slim, slim), "two slim bodies 0.4 apart touch nowhere")
        val push = PairSpacing.push(a, b, 0.0 to 0.0, bulky, bulky)
        assertNotNull(push)
        assertTrue(push.second > 0.1, "moved apart sideways: $push")
        // and as near after it as the contact tolerance lets them come, by their own flesh: their sides 0.4 each and the margin
        assertEquals(0.4 + push.second, 0.4 * 2 + PairSpacing.MARGIN - PairSpacing.CONTACT_TOLERANCE, 2e-3)
        assertTrue(PairSpacing.slack(PairSpacing.parts(a[0], bulky), PairSpacing.parts(b[0], bulky), bulky, bulky, push.first, push.second)!!.first >=
            -PairSpacing.CONTACT_TOLERANCE - 1e-3)
        // one bulky, one slim: less (within the contact tolerance here: not at all)
        val mixed = PairSpacing.push(a, b, 0.0 to 0.0, bulky, slim)
        assertTrue(mixed == null || hypot(mixed.first, mixed.second) < hypot(push.first, push.second))
        // the clip on the pair: two lying fixtures made on one root are moved apart, more for the bulky
        val pair = PartnerPair("p", "p", F.lyingBack(0.0, name = "pa"), F.lyingBack(0.0, name = "pb"), PartnerSlot.human(0f, 0f, 0f, 0f),
            PartnerSlot.human(0f, 0f, 0f, 0f), true)
        val ps = PairSpacing.push(pair, slim, slim); val pb = PairSpacing.push(pair, bulky, bulky)!!
        assertTrue(hypot(pb.first, pb.second) > (ps?.let { hypot(it.first, it.second) } ?: 0.0), "$ps $pb")
    }

    @Test fun aStageMadeForContactIsMovedLittle() {
        // two bulky bodies stacked on one spot: an ordinary stage is moved apart, one made for contact (a cuddle) at most
        // MAX_PUSH_CONTACT
        fun pair(id: String) = PartnerPair(id, id, F.lyingBack(0.0, name = "${id}_a"), F.lyingBack(0.0, name = "${id}_b"),
            PartnerSlot.human(0f, 0f, 0f, 0f), PartnerSlot.human(0f, 0f, 0f, 0f), true)
        val bulky = profile(0.06, FleshPart.TRUNK.associateWith { FleshSegment(0.2, 0.2, 0.4) })
        val stacked = PairSpacing.push(pair("stacked"), bulky, bulky)!!
        val cuddle = PairSpacing.push(pair("Cuddle"), bulky, bulky)!!
        assertTrue(hypot(stacked.first, stacked.second) > 0.3, "$stacked")
        assertTrue(hypot(cuddle.first, cuddle.second) <= PairSpacing.MAX_PUSH_CONTACT + 1e-9, "$cuddle")
    }

    @Test fun aBodyMeetsTheOtherWithTheSideThatFacesIt() {
        // face to face, 0.5 apart: a big belly (front) comes nearer than the same thickness at the sides
        val a = listOf(body(0.0, 0.0)); val b = listOf(body(0.5, 0.0, 180.0))
        val belly = profile(0.05, FleshPart.TRUNK.associateWith { FleshSegment(0.45, 0.1, 0.12) })
        val wide = profile(0.05, FleshPart.TRUNK.associateWith { FleshSegment(0.1, 0.1, 0.45) })
        val push = PairSpacing.push(a, b, 1.0 to 0.0, belly, belly)
        assertNotNull(push, "two bellies 0.5 apart meet")
        assertEquals(0.45 * 2 + PairSpacing.MARGIN - PairSpacing.CONTACT_TOLERANCE - 0.5, push.first, 2e-3)
        assertNull(PairSpacing.push(a, b, 1.0 to 0.0, wide, wide), "wide hips don't reach the one in front")
        // back to back: the belly is away, the backs (0.1) don't meet
        assertNull(PairSpacing.push(listOf(body(0.0, 0.0, 180.0)), listOf(body(0.5, 0.0)), 1.0 to 0.0, belly, belly))
    }

    @Test fun theSimulationUsesEachPersonsOwnFlesh() {
        val clip = F.lyingBack(0.0, name = "fl")
        val pair = PartnerPair("floor", "floor", clip, F.lyingBack(0.0, name = "fl2"), PartnerSlot.human(0f, 0f, 0f, 0f),
            PartnerSlot.human(0f, (0.9 * BodyGeometry.REF_UNIT).toFloat(), 0f, 0f), true)
        fun liftsWith(flesh: Map<String, FleshProfile>): List<Float> {
            val sim = SceneSimulation(listOf("a", "b"), emptySet(), Random(3), pairs = listOf(pair))
            sim.flesh = flesh
            assertTrue(sim.partner("a", "b"))
            var n = 0
            val p = Pose()
            while (true) {
                sim.tick(1f / 60f)
                sim.pose(0, p); val l0 = p.lift; val ok0 = p.playback != null
                sim.pose(1, p); val l1 = p.lift; val ok1 = p.playback != null
                if (ok0 && ok1) {
                    // once the settle onto their places is over (JoinEase: drawn from where they walked to, then let out)
                    repeat(((JoinEase.SETTLE + 0.05f) * 60f).toInt()) { sim.tick(1f / 60f) }
                    sim.pose(0, p); val s0 = p.lift
                    sim.pose(1, p); val s1 = p.lift
                    return listOf(s0, s1)
                }
                check(++n < 20_000)
            }
        }
        val generic = liftsWith(emptyMap())
        val bulky = profile(0.06, FleshPart.TRUNK.associateWith { FleshSegment(0.2, 0.3, 0.25) })
        val one = liftsWith(mapOf("a" to bulky))
        // only the bulky one is raised more; the other keeps the generic lift
        assertTrue(one.max() > generic.max() + 0.1f, "generic $generic, one bulky $one")
        assertTrue(one.min() <= generic.max() + 1e-4f, "the other: $one")
    }

    @Test fun theNativeNamesMatchTheEngines() {
        val native = File("../android/app/src/main/java/com/logie/wifecraft/engine/Native.kt").readText()
        val jni = File("../android/app/src/main/cpp/jni.cpp").readText()
        val kt = Regex("external fun (\\w+)\\(").findAll(native).map { it.groupValues[1] }.toSet()
        val cpp = Regex("FN\\((\\w+)\\)").findAll(jni).map { it.groupValues[1] }.toSet()
        assertTrue("actorFlesh" in kt && "actorFlesh" in cpp)
        assertEquals(emptySet(), kt - cpp, "Kotlin externals with no JNI function")
        // the JNI function's parameters: the scene's handle and the actor (jlong, jint), as Native.kt declares them
        assertTrue(Regex("FN\\(actorFlesh\\)\\(JNIEnv\\* env, jclass, jlong h, jint id\\)").containsMatchIn(jni))
        assertTrue(Regex("external fun actorFlesh\\(scene: Long, actor: Int\\): FloatArray\\?").containsMatchIn(native))
        // the layout's numbers: the engine's segment count and joint list are the Kotlin ones
        val flesh = File("../android/app/src/main/cpp/engine/flesh.h").readText()
        val segs = Regex("\\{\"(\\w+)\", \"\\w+\", \"\\w+\", (?:nullptr|\"\\w+\"), -?1, (?:true|false), (?:true|false)\\}").findAll(flesh).map { it.groupValues[1] }.toList()
        assertEquals(FleshPart.entries.size, segs.size, "flesh.h's segments: $segs")
        for ((i, p) in FleshPart.entries.withIndex()) assertEquals(p.from, Regex("\\{\"${segs[i]}\", \"(\\w+)\"").find(flesh)!!.groupValues[1], p.name)
        val joints = Regex("kFleshJoints\\[\\] = \\{([^}]*)\\}").find(flesh)!!.groupValues[1].let { Regex("\"(\\w+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }
        assertEquals(BodyGeometry.JOINTS, joints)
    }
}
