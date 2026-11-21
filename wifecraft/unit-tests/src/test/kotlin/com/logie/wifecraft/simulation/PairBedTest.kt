package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.HumanScale
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.anim.RequirementClassifier
import com.logie.wifecraft.location.LocationInfo
import com.logie.wifecraft.story.Vec3
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Two people on a real bed (the Brill house's, its measured row and spot as the live screen gives them): each stage of a
 * sequence rests on the mattress whatever height its clips were made at (a stage after a stage keeps the first one's origin
 * across the bed, never its height: the phone showed a lying pair floating 0.2 to 0.3 m over the bed after a stage made
 * lower, and one sunk into it after a stage made higher), a clip made on something higher than the bed is let down onto it
 * (a lift under the floor: [Pose.lift] below 0, the sink places it there instead of standing it on the floor), the two keep
 * their authored contact, and the lying body lies along the bed. The generic body (anim/PoseFixtures); the engine's side
 * (the real meshes) is checked by the alignment audit's bed mode (README, "Alignment audit"). Not run on a phone.
 */
class PairBedTest {
    private val F = PoseFixtures
    private val step = 1f / 120f
    private val unit = SceneSimulation.HUMAN_BODY_UNIT

    private fun pair(id: String, a: Anim, b: Anim, dx: Double = 0.0, dy: Double = 0.0) =
        PartnerPair(id, "Pair $id", a, b, PartnerSlot.human(0f, 0f, 0f, 0f),
            PartnerSlot.human((dx * BodyGeometry.REF_UNIT).toFloat(), (dy * BodyGeometry.REF_UNIT).toFloat(), 0f, 0f), true, emptySet())

    /** As the screen's request(): the choice's own roles and its pair's id. */
    private fun order(p: PartnerPair) = PerformanceOrder("b",
        listOf(PlaybackRole("${p.id}:0", Vec3(0f, 0f, 0f), 0f), PlaybackRole("${p.id}:1", Vec3(p.slotB.x, p.slotB.y, 0f), 0f)),
        8f, name = p.name, pairId = p.id)

    /** The Brill house's bed as SceneSessionScreen makes it (its furniture row, measured; its spot), in a frame at its entry. */
    private fun brill(pairs: List<PartnerPair>): SceneSimulation {
        val assets = File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets")
        val info = LocationInfo.parseAll(File(assets, "locations.json").readText()).first { it.id == "brill_house" }
        val row = info.interactions.first { it.item == SceneObject.BED.name }
        val toLocal: (Vec3) -> Vec3 = { it - row.entry }
        val sim = SceneSimulation(listOf("a", "b"), setOf(SceneObject.BED), Random(3), pairs = pairs)
        sim.setSpot(SceneObject.BED, InteractionSpot(toLocal(row.entry), toLocal(row.pose), row.facing))
        sim.setFurniture(SceneObject.BED, assertNotNull(info.furniture.firstOrNull { it.item == SceneObject.BED.name }?.let { Furniture.of(it, toLocal) }))
        return sim
    }

    private fun until(sim: SceneSimulation, limit: Int = 30_000, done: () -> Boolean) {
        var n = 0
        while (!done()) { sim.tick(step); n++; check(n < limit) { "never happened" } }
    }

    /** The join's ease over (they are drawn on their places): 2.5 s. */
    private fun settle(sim: SceneSimulation) = repeat(300) { sim.tick(step) }

    private fun pose(sim: SceneSimulation, id: String) = Pose().also { sim.pose((0 until sim.count).first { i -> sim.id(i) == id }, it) }

    /** The mattress's top (world). */
    private fun mattress(sim: SceneSimulation): Float {
        val bed = sim.furniture(SceneObject.BED)!!
        return bed.z + bed.spec.regions.first().height
    }

    /** The clip [p] plays for [id] now (its pair's role), turned and placed as drawn: its frames in the world (yards). */
    private fun drawn(sim: SceneSimulation, p: PartnerPair, id: String): List<com.logie.wifecraft.anim.BodyFrame> {
        val now = pose(sim, id)
        val role = now.playback!!.clip.substringAfterLast(':').toInt()
        val anim = if (role == 0) p.a else p.b
        val slot = if (role == 0) p.slotA else p.slotB
        // the screen's clip carries the slot's height in its Root track (lifted); the sink puts its origin at the pose
        return BodyGeometry.frames(anim, z = slot.z.toDouble()).map { f ->
            val c = cos(now.facing.toDouble()); val s = sin(now.facing.toDouble())
            com.logie.wifecraft.anim.BodyFrame(f.joints.mapValues { (_, v) ->
                doubleArrayOf(now.x + (v[0] * c - v[1] * s) * unit, now.y + (v[0] * s + v[1] * c) * unit, now.z + v[2] * unit)
            }, f.driven)
        }
    }

    /**
     * [frames]' lowest flesh (world z, yards): each joint less the flesh the classifier keeps under it (the generic body's,
     * RequirementClassifier.FLESH), the hands left out; the fit and the lifts put it on the mattress.
     */
    private fun lowestLying(frames: List<com.logie.wifecraft.anim.BodyFrame>): Double = frames.minOf { f ->
        RequirementClassifier.FLESH.filterKeys { !it.endsWith("_Hand") }.minOf { (j, pad) -> (f[j]?.get(2) ?: 1e9) - pad * unit }
    }

    @Test fun eachStageOfASequenceRestsOnTheMattress() {
        // made on a 0.55 m bed, then on a 0.3 m one, then on the 0.55 m one again: each one's own height, one origin across the bed
        val high = pair("high", F.lyingBack(0.55, name = "h_a"), F.lyingBack(0.55, name = "h_b"), dy = 0.6)
        val low = pair("low", F.lyingBack(0.3, name = "l_a"), F.lyingBack(0.3, name = "l_b"), dy = 0.6)
        val high2 = pair("high2", F.lyingBack(0.55, name = "h2_a"), F.lyingBack(0.55, name = "h2_b"), dy = 0.5)
        val sim = brill(listOf(high, low, high2))
        assertTrue(sim.enqueue("a", listOf(order(high), order(low), order(high2))))
        val top = mattress(sim)
        var origin: Pair<Float, Float>? = null
        for (p in listOf(high, low, high2)) {
            until(sim) { sim.partnerOf("a")?.let { it.stage == "PLAY" && it.name == p.name } == true }
            settle(sim)
            val v = sim.partnerOf("a")!!
            assertEquals(SceneObject.BED, v.furniture)
            // the author's one origin for the whole sequence (across the bed)
            if (origin == null) origin = v.ox to v.oy
            assertEquals(origin.first, v.ox, 1e-4f, "${p.name}: the same origin"); assertEquals(origin.second, v.oy, 1e-4f)
            for (id in listOf("a", "b")) {
                val low0 = lowestLying(drawn(sim, p, id))
                assertEquals(top.toDouble(), low0, 0.03 * FurnitureSpec.YARDS_PER_METRE, "${p.name}: $id lies on the mattress (${low0 - top} yd)")
                val now = pose(sim, id)
                assertEquals(now.z - now.floor, now.lift, 1e-4f, "${p.name}: the lift is the height over the floor")
            }
        }
    }

    @Test fun aClipMadeHigherThanTheBedIsLetDownOntoIt() {
        // a low bed (its mattress 0.3 m up) and a stage made on a 0.7 m one: its origin goes under the floor (the fit's lift
        // below 0) and stays there (it was stood on the floor: 0.4 m over the mattress)
        val tall = pair("tall", F.lyingBack(0.7, name = "t_a"), F.lyingBack(0.7, name = "t_b"), dy = 0.6)
        val sim = SceneSimulation(listOf("a", "b"), setOf(SceneObject.BED), Random(3), pairs = listOf(tall))
        val y = FurnitureSpec.YARDS_PER_METRE
        sim.setSpot(SceneObject.BED, InteractionSpot(Vec3(0f, 2.2f, 0f), Vec3(0f, 0f, 0.3f * y), -(PI / 2).toFloat()))
        assertTrue(sim.perform("a", order(tall)))
        assertEquals(SceneObject.BED, sim.partnerOf("a")!!.furniture)
        until(sim) { sim.partnerOf("a")?.stage == "PLAY" }
        settle(sim)
        val top = mattress(sim)
        for (id in listOf("a", "b")) {
            val now = pose(sim, id)
            assertTrue(now.lift < -0.3f * y, "$id: lowered under its floor (${now.lift})")
            assertEquals(now.z - now.floor, now.lift, 1e-4f)
            val low0 = lowestLying(drawn(sim, tall, id))
            assertEquals(top.toDouble(), low0, 0.03 * y, "$id lies on the mattress (${low0 - top} yd)")
        }
    }

    @Test fun aPairOnTheBedKeepsItsContactAndLiesAlongTheBed() {
        // face to face: one on its back, one on its front over it (made on the floor), their pelvises as the author put them
        val a = F.lyingBack(0.0, name = "m_a")
        val b = F.lyingFront(0.12, name = "m_b")
        val p = pair("mission", a, b)
        val sim = brill(listOf(p))
        // (made on the floor: the bed's Play puts it there)
        assertTrue(sim.partnerOn(SceneObject.BED, p.id, "a", "b", fixedRoles = true))
        until(sim) { sim.partnerOf("a")?.stage == "PLAY" }
        settle(sim)
        val fa = drawn(sim, p, "a"); val fb = drawn(sim, p, "b")
        fun pelvis(f: com.logie.wifecraft.anim.BodyFrame) = f["Root"]!!
        val app = fa.indices.map { i -> val x = pelvis(fa[i]); val y = pelvis(fb[minOf(i, fb.size - 1)]); hypot(hypot(x[0] - y[0], x[1] - y[1]), x[2] - y[2]) }
        val ra = BodyGeometry.frames(p.a, z = p.slotA.z.toDouble()); val rb = BodyGeometry.frames(p.b, x = p.slotB.x.toDouble() / HumanScale.PAIR,
            y = p.slotB.y.toDouble() / HumanScale.PAIR, z = p.slotB.z.toDouble())
        val authored = ra.indices.map { i -> val x = pelvis(ra[i]); val y = pelvis(rb[minOf(i, rb.size - 1)]); hypot(hypot(x[0] - y[0], x[1] - y[1]), x[2] - y[2]) * unit }
        for (i in app.indices) assertEquals(authored[i], app[i], 0.06 * FurnitureSpec.YARDS_PER_METRE, "the pelvises' distance at $i")
        // the one under on the mattress
        assertEquals(mattress(sim).toDouble(), lowestLying(fa), 0.03 * FurnitureSpec.YARDS_PER_METRE)
        // lying along the bed's long axis (its head toward the head end)
        val bed = sim.furniture(SceneObject.BED)!!
        val f0 = fa.first()
        val head = f0["Head"]!!; val root = f0["Root"]!!
        val along = atan2(head[1] - root[1], head[0] - root[0])
        var off = along - (bed.yaw + PI)
        while (off > PI) off -= 2 * PI
        while (off < -PI) off += 2 * PI
        assertTrue(abs(off) < Math.toRadians(15.0), "lies along the bed, its head to the head end (off by ${Math.toDegrees(off)} deg)")
    }
}
