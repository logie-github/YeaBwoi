package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import org.json.JSONObject
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

/** Build mode's per-actor moves (one of a pair, both, a solo NPC), the user-made spots of the New tab, and the sparkles' rules. */
class BuildModeActorsTest {
    private fun near(a: Float, b: Float, eps: Float = 1e-3f, what: String = "") = assertTrue(abs(a - b) <= eps, "$what: $a vs $b")
    private val roleA = PlaybackRole("a", Vec3(0.4f, 0.1f, 0f), 0.3f)
    private val roleB = PlaybackRole("b", Vec3(-0.2f, 0f, 0f), 3.4f)
    private val anchor = Vec3(2f, -1f, 0.5f)
    private val heading = 0.7f

    // ---- per-actor frame offsets

    @Test fun anActorsPoseAndItsNudgeAreInverses() {
        val fixed = RoleDelta(0.05f, -0.02f, 0f, 4f)
        val target = BuildPose(2.9f, -0.2f, 0.62f, 75f)
        val extra = BuildActors.extraFor(anchor, heading, roleA, fixed, target)
        val back = BuildActors.poseOf(anchor, heading, roleA, fixed, extra)
        near(back.x, target.x); near(back.y, target.y); near(back.z, target.z)
        near(ItemFrame.wrapDeg(back.yawDeg - target.yawDeg), 0f, 0.01f, "facing")
    }

    @Test fun noNudgeIsTheComputedPlacement() {
        val p = BuildActors.poseOf(anchor, heading, roleA, RoleDelta.ZERO, RoleDelta.ZERO)
        val c = kotlin.math.cos(heading); val s = kotlin.math.sin(heading)
        near(p.x, anchor.x + 0.4f * c - 0.1f * s); near(p.y, anchor.y + 0.4f * s + 0.1f * c); near(p.z, anchor.z)
        val e = BuildActors.extraFor(anchor, heading, roleA, RoleDelta.ZERO, p)
        assertTrue(e.none, "the computed place is no nudge: $e")
    }

    @Test fun movingOneActorLeavesThePartnerWhereItWas() {
        val bBefore = BuildActors.poseOf(anchor, heading, roleB, RoleDelta.ZERO, RoleDelta.ZERO)
        val aTarget = BuildPose(3.5f, 0.4f, 0.5f, 10f)
        val extras = BuildActors.withExtra(emptyList(), 0, BuildActors.extraFor(anchor, heading, roleA, RoleDelta.ZERO, aTarget))
        assertEquals(2, extras.size); assertTrue(extras[1].none)
        val a = BuildActors.poseOf(anchor, heading, roleA, RoleDelta.ZERO, extras[0])
        val b = BuildActors.poseOf(anchor, heading, roleB, RoleDelta.ZERO, extras[1])
        near(a.x, 3.5f); near(a.y, 0.4f)
        near(b.x, bBefore.x); near(b.y, bBefore.y); near(b.yawDeg, bBefore.yawDeg, 0.01f)
    }

    @Test fun movingTheOriginMovesBothAndKeepsTheirOwnNudges() {
        val extras = listOf(RoleDelta(0.1f, 0f, 0f, 0f), RoleDelta.ZERO)
        val a0 = BuildActors.poseOf(anchor, heading, roleA, RoleDelta.ZERO, extras[0])
        val a1 = BuildActors.poseOf(anchor + Vec3(1f, 0f, 0f), heading, roleA, RoleDelta.ZERO, extras[0])
        near(a1.x - a0.x, 1f); near(a1.y, a0.y)
    }

    @Test fun targetsOfferThePairOrJustTheLoneNpc() {
        fun pair(other: String?) = PlayingPair("x", "lead", other, anchor, heading, anchor, heading, null, listOf(roleA, roleB), emptyList())
        assertEquals(MoveTarget.entries, BuildActors.targets(pair("guest"), "lead"))
        assertEquals(listOf(MoveTarget.BOTH), BuildActors.targets(pair(null), "lead"))
        assertEquals(listOf(MoveTarget.BOTH), BuildActors.targets(null, "lead"))
        assertEquals(0, MoveTarget.THIS.role(0)); assertEquals(1, MoveTarget.PARTNER.role(0)); assertEquals(0, MoveTarget.PARTNER.role(1)); assertEquals(-1, MoveTarget.BOTH.role(1))
    }

    // ---- the roles' deltas and the report

    private fun pair(origin: Vec3, head: Float, extra: List<RoleDelta>) =
        PlayingPair("Dance", "lead", "guest", origin, head, anchor, heading, null, listOf(roleA, roleB), extra, listOf(RoleDelta.ZERO, RoleDelta.ZERO))

    @Test fun aSingleMoveIsThatRolesOwnDeltaABothMoveIsShared() {
        val alone = pair(anchor, heading, listOf(RoleDelta(0.2f, 0.1f, 0f, 15f), RoleDelta.ZERO))
        assertEquals(setOf(0), BuildActors.changedRoles(alone)); assertEquals("A", BuildActors.moveKind(alone))
        val onlyB = pair(anchor, heading, listOf(RoleDelta.ZERO, RoleDelta(0f, -0.3f, 0f, 0f)))
        assertEquals(setOf(1), BuildActors.changedRoles(onlyB)); assertEquals("B", BuildActors.moveKind(onlyB))
        val both = pair(anchor + Vec3(0.5f, 0f, 0f), heading, listOf(RoleDelta.ZERO, RoleDelta.ZERO))
        assertEquals(setOf(0, 1), BuildActors.changedRoles(both)); assertEquals("both", BuildActors.moveKind(both))
        val dA = RaceOffsets.deltaFor(roleA, alone.baseAnchor, alone.baseHeading, alone.anchor, alone.heading, alone.extra[0])
        near(dA.dx, 0.2f); near(dA.dy, 0.1f); near(dA.dyawDeg, 15f, 0.01f)
        val dB = RaceOffsets.deltaFor(roleB, alone.baseAnchor, alone.baseHeading, alone.anchor, alone.heading, alone.extra[1])
        assertTrue(dB.none, "the partner's role carries nothing")
        // a shared move: the same shift in the world is each role's delta in the pair's own frame
        val s0 = RaceOffsets.deltaFor(roleA, both.baseAnchor, both.baseHeading, both.anchor, both.heading)
        val s1 = RaceOffsets.deltaFor(roleB, both.baseAnchor, both.baseHeading, both.anchor, both.heading)
        near(s0.dx, s1.dx); near(s0.dy, s1.dy)
        assertEquals("mixed", BuildActors.moveKind(pair(anchor + Vec3(1f, 0f, 0f), heading, listOf(RoleDelta(0.1f, 0f, 0f, 0f), RoleDelta.ZERO))))
    }

    @Test fun aPairMoveKeepsItsRoleDeltasInTheReportAndOldReportsStillRead() {
        val m = PairMove("lead", "Dance", listOf(RoleActor(1, false), RoleActor(7, true)), BuildPose(0f, 0f, 0f, 0f), BuildPose(1f, 0f, 0f, 0f), null,
            listOf(RoleDelta(0.2f, 0f, 0f, 10f), RoleDelta.ZERO), "A")
        val j = m.toJson()
        assertEquals("A", j.getString("move"))
        val back = assertNotNull(PairMove.of(j))
        assertEquals("A", back.kind); near(back.roleDeltas[0].dx, 0.2f); assertTrue(back.roleDeltas.getOrNull(1)?.none != false)
        // schema 2 reports without the new fields: a shared move
        val old = JSONObject("""{"animationId":"x","roles":[],"origin":{"x":1,"y":2,"z":3,"yawDeg":4},"computed":{"x":0,"y":0,"z":0,"yawDeg":0}}""")
        val o = assertNotNull(PairMove.of(old))
        assertEquals("both", o.kind); assertTrue(o.roleDeltas.isEmpty())
        assertFalse(PairMove("l", "x", emptyList(), m.from, m.to).toJson().has("roleDeltas"), "a shared move writes none")
    }

    @Test fun aRaceOffsetKeepsHowItWasMadeAndAnOldOneReads() {
        val o = RaceOffset("anim", "Anim", 0, 7, true, -1, "bed", null, RaceScope.ROLE, RoleDelta(0.1f, 0f, 0f, 0f), move = "single")
        assertEquals("single", RaceOffset.of(o.toJson())?.move)
        val j = o.copy(move = "").toJson(); assertFalse(j.has("move")); assertEquals("", RaceOffset.of(j)?.move)
        assertEquals(o.key, o.copy(move = "both").key, "the key is the same: a newer one replaces the older")
    }

    @Test fun theSimulationPlaysOnWhileOneActorIsNudged() {
        val sim = SceneSimulation(listOf("lead", "guest"), emptySet(), Random(1), wanderOnly = true)
        val roles = listOf(PlaybackRole("a", Vec3(0.4f, 0f, 0f), 0f), PlaybackRole("b", Vec3(0f, 0f, 0f), Math.PI.toFloat()))
        assertTrue(sim.perform("lead", PerformanceOrder("guest", roles, 1f, repeat = true, name = "Dance")))
        repeat(20) { sim.tick(0.1f) }
        val p = assertNotNull(sim.playingOf("lead"))
        assertEquals(2, p.fixed.size)
        val a0 = Pose().also { sim.pose(0, it) }; val b0 = Pose().also { sim.pose(1, it) }
        val command = a0.playback?.command
        val target = BuildActors.poseOf(p.anchor, p.heading, p.roles[0], p.fixed[0], RoleDelta(0.5f, 0f, 0f, 0f))
        val extras = BuildActors.withExtra(p.extra, 0, BuildActors.extraFor(p.anchor, p.heading, p.roles[0], p.fixed[0], target))
        assertTrue(sim.movePlaying("lead", p.anchor, p.heading, extras))
        sim.tick(0.1f)
        val a1 = Pose().also { sim.pose(0, it) }; val b1 = Pose().also { sim.pose(1, it) }
        assertEquals(command, a1.playback?.command, "no snap back: the same playback")
        near(b1.x, b0.x); near(b1.y, b0.y)
        assertTrue(hypotOf(a1.x - a0.x, a1.y - a0.y) > 0.4f, "the lead moved")
        // reset that one's nudge only
        assertTrue(sim.movePlaying("lead", p.anchor, p.heading, BuildActors.withExtra(extras, 0, RoleDelta.ZERO)))
        sim.tick(0.1f)
        val a2 = Pose().also { sim.pose(0, it) }
        near(a2.x, a0.x, 1e-2f); near(a2.y, a0.y, 1e-2f)
    }

    private fun hypotOf(a: Float, b: Float) = kotlin.math.sqrt(a * a + b * b)

    @Test fun aSoloNpcCanBePlacedButNotOneInAnAnimation() {
        val sim = SceneSimulation(listOf("a", "b"), emptySet(), Random(3), wanderOnly = true)
        assertTrue(sim.placeActor("a", Vec3(1f, 2f, 0f), 1.5f))
        val (at, f) = assertNotNull(sim.placeOf("a"))
        near(at.x, 1f); near(at.y, 2f); near(f, 1.5f)
        repeat(50) { sim.tick(0.1f) }
        sim.holdAnimations = true
        assertTrue(sim.placeActor("a", Vec3(-1f, 0.5f, 0f), 0f))
        repeat(200) { sim.tick(0.1f) }
        near(assertNotNull(sim.placeOf("a")).first.x, -1f, 1e-3f, "stays where it was put while held")
        assertFalse(sim.placeActor("nobody", Vec3(0f, 0f, 0f), 0f))
        assertFalse(sim.placeActor("a", Vec3(Float.NaN, 0f, 0f), 0f))
    }

    // ---- the New tab's spots

    @Test fun aNewSpotTakesItsClassSizeAndHeight() {
        val chair = Spots.make("s1", SpotKind.CHAIR, "", null, 1f, 2f, 0f, 0f, 30f)
        assertEquals(ItemClass.SEAT, chair.cls); assertEquals(SceneObject.CHAIR, chair.slot); assertTrue(chair.surface > 0.2f)
        val floor = Spots.make("s2", SpotKind.SURFACE, "", null, 0f, 0f, 0f, 0f, 0f)
        assertEquals(ItemClass.FLOOR, floor.cls); near(floor.surface, 0f)
        assertEquals(ItemClass.BED, Spots.make("s3", SpotKind.BED, "", null, 0f, 0f, 0f, 0f, 0f).cls)
        assertEquals(SceneObject.TABLE, Spots.make("s4", SpotKind.TABLE, "", null, 0f, 0f, 0f, 0f, 0f).slot)
        // a hit on a prop's top gives the surface its height
        near(Spots.make("s5", SpotKind.CHAIR, "", null, 0f, 0f, 0f, 0.8f, 0f).surface, 0.8f)
        val custom = Spots.make("s6", SpotKind.CUSTOM, "  Fountain rim ", null, 0f, 0f, 0f, 0f, 0f)
        assertEquals("Fountain rim", custom.name); assertEquals(ItemClass.FLOOR, custom.cls, "default class: Surface")
        assertEquals(ItemClass.BED, Spots.make("s7", SpotKind.CUSTOM, "Hay", ItemClass.BED, 0f, 0f, 0f, 0f, 0f).cls)
        assertEquals("Chair", chair.name)
    }

    @Test fun theFootprintResizesWithinLimitsAndTheSpotIsClickableOverIt() {
        var s = Spots.make("s", SpotKind.TABLE, "", null, 5f, 5f, 0f, 0f, 90f)
        val hx = s.halfX
        s = Spots.resize(s, 0, 2)
        near(s.halfX, hx + 0.1f)
        repeat(100) { s = Spots.resize(s, -1, -1) }
        near(s.halfX, Spots.MIN_HALF); near(s.halfY, Spots.MIN_HALF)
        repeat(200) { s = Spots.resize(s, 1, 1) }
        near(s.halfX, Spots.MAX_HALF)
        val sp = Spots.make("t", SpotKind.CHAIR, "", null, 5f, 5f, 0f, 0f, 0f).copy(halfX = 0.5f, halfY = 0.25f)
        assertTrue(sp.contains(5.4f, 5.2f)); assertFalse(sp.contains(5.6f, 5f)); assertFalse(sp.contains(5f, 5.3f))
        val turned = sp.copy(yawDeg = 90f)
        assertTrue(turned.contains(5f, 5.4f)); assertFalse(turned.contains(5.4f, 5f))
        assertTrue(sp.contains(5f, 5f, sp.top + 0.5f)); assertFalse(sp.contains(5f, 5f, sp.top + 3f))
        val low = sp.copy(id = "low", surface = 0.1f); val high = sp.copy(id = "high", surface = 0.6f)
        assertEquals("high", Spots.hit(listOf(low, high), 5f, 5f)?.id)
        assertNull(Spots.hit(listOf(low), 9f, 9f))
        assertEquals(1.2f, Spots.setSurface(sp, 1.2f).surface); assertEquals(0f, Spots.setSurface(sp, -1f).surface)
    }

    @Test fun aSpotIsFurnitureTheSceneCanUse() {
        val sp = Spots.make("c", SpotKind.CHAIR, "", null, 3f, 1f, 0f, 0f, 0f).copy(halfX = 0.5f, halfY = 0.7f)
        val f = sp.furniture({ it + Vec3(10f, 0f, 0f) }, 0f)
        near(f.x, 13f); near(f.spec.halfX, 0.5f); near(f.spec.halfY, 0.7f)
        assertEquals(FurnitureKind.CHAIR, f.kind)
        assertTrue(f.spec.regions.all { it.maxX <= 0.5f + 1e-3f && it.maxY <= 0.7f + 1e-3f }, "regions follow the footprint")
        assertTrue(Sparkles.usable(f))
        val spot = Spots.interactionSpot(SceneObject.CHAIR, f)
        near(spot.entry.z, f.z)
        assertEquals("Sit here", Spots.useLabel(sp)); assertEquals("Lie here", Spots.useLabel(sp.copy(cls = ItemClass.BED))); assertEquals("Play animation", Spots.useLabel(sp.copy(cls = ItemClass.FLOOR)))
        assertEquals(SceneObject.COUCH, Spots.slotFor(ItemClass.FLOOR))
    }

    @Test fun spotsAreKeptPerPlaceAndSentInTheReportWithTheUserFlag() {
        val sp = Spots.make("abc", SpotKind.CUSTOM, "Stone bench", ItemClass.SEAT, 1f, 2f, 0.1f, 0.1f, 45f).copy(halfX = 0.3f, halfY = 0.4f, surface = 0.5f, sparkle = false)
        var doc = SpotEdits.add(BuildDoc(), sp)
        doc = BuildEdits.moveNpc(doc, "npc1", BuildPose(1f, 1f, 0f, 0f))
        assertFalse(doc.empty); assertEquals(1, doc.count)
        val back = BuildDoc.of(JSONObject(doc.toJson("p", "Place").toString()))
        assertEquals(sp, back.spots["abc"]); assertTrue(back.items.isEmpty(), "a spot is not an item interaction")
        assertEquals(BuildPose(1f, 1f, 0f, 0f), back.npcMoves["npc1"])
        val report = BuildReport("r", "p", "Place", "1", 1, doc)
        val j = report.toJson()
        val items = j.getJSONArray("items")
        assertEquals(1, items.length())
        val item = items.getJSONObject(0)
        assertTrue(item.getBoolean("user")); assertEquals("custom", item.getString("kind")); assertEquals("Stone bench", item.getString("label")); assertEquals("seat", item.getString("class"))
        assertFalse(j.has("npcMoves"), "nothing personal in a report")
        assertTrue(BuildReports.validate(j).isEmpty())
        assertTrue(report.body().contains("Stone bench"))
        // edits: update and remove, a move sets the height from the gizmo (z of the surface less the surface)
        assertEquals(0.3f, SpotEdits.move(doc, "abc", BuildPose(2f, 2f, sp.surface + 0.3f, 10f)).spots["abc"]!!.z, 1e-4f)
        assertTrue(SpotEdits.remove(doc, "abc").spots.isEmpty())
        assertSame(doc, SpotEdits.update(doc, sp.copy(id = "other")))
    }

    @Test fun shippedSpotsReadPerPlace() {
        val sp = Spots.make("x", SpotKind.BED, "", null, 1f, 1f, 0f, 0f, 0f)
        val json = JSONObject().put("schema", 1).put("places", JSONObject().put("place", org.json.JSONArray(listOf(sp.toJson())))).toString()
        val got = Spots.shipped(json)["place"]!!
        assertEquals(1, got.size); assertTrue(got[0].shipped); assertEquals(ItemClass.BED, got[0].cls)
        assertTrue(Spots.shipped("nope").isEmpty())
    }

    // ---- sparkles

    @Test fun sparklesShowOnlyOutsideBuildAndCinematicWithTheSwitchOn() {
        assertTrue(Sparkles.shown(true, false, false))
        assertFalse(Sparkles.shown(false, false, false)); assertFalse(Sparkles.shown(true, true, false)); assertFalse(Sparkles.shown(true, false, true))
    }

    @Test fun anOccupiedOrSwitchedOffSpotHasNoSparklesAndTheGovernorLowersTheDensity() {
        val a = Sparkles.Source("a", Vec3(1f, 2f, 0.5f), 0.5f, 0.4f, 0.3f, occupied = false)
        val b = Sparkles.Source("b", Vec3(3f, 2f, 0.5f), 0.5f, 0.4f, 0f, occupied = true)
        val c = Sparkles.Source("c", Vec3(5f, 2f, 0.5f), 0.5f, 0.4f, 0f, occupied = false, enabled = false)
        val f = Sparkles.floats(listOf(a, b, c), true)
        assertEquals(8, f.size); near(f[0], 1f); near(f[2], 0.5f); near(f[3], 0.5f); near(f[5], 0.3f); near(f[6], 1f)
        assertEquals(0, Sparkles.floats(listOf(a), false).size)
        assertEquals(48 * 8, Sparkles.floats(List(60) { a.copy(key = "k$it") }, true).size)
        assertTrue(Sparkles.density(0) == 1f && Sparkles.density(1) < 1f && Sparkles.density(2) < Sparkles.density(1) && Sparkles.density(3) == 0f)
    }

    @Test fun buildKeepsEveryPathFromAdvancingTheScene() {
        // the simulation's own: a held scene starts nothing (queued orders, picks and the life simulation's endings wait)
        val sim = SceneSimulation(listOf("a", "b"), emptySet(), Random(5), wanderOnly = true)
        sim.holdAnimations = true
        val before = sim.placeOf("a")!!.first
        repeat(600) { sim.tick(0.1f) }
        near(sim.placeOf("a")!!.first.x, before.x, 1e-4f, "no wandering"); near(sim.placeOf("b")!!.first.y, sim.placeOf("b")!!.first.y)
    }
}
