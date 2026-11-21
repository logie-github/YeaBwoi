package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

class RoleSwitchTest {
    private val roleA = PlaybackRole("clip-a", Vec3(-0.4f, 0f, 0f), 0f)
    private val roleB = PlaybackRole("clip-b", Vec3(0.4f, 0.1f, 0.2f), PI.toFloat())
    private val order = PerformanceOrder("bea", listOf(roleA, roleB), 3f, name = "Waltz",
        sounds = listOf(PartnerSound(1f, "step", 1)), pairId = "p1")

    @Test
    fun switchingGivesEachPersonTheOthersClipSlotAndFacing() {
        val cast = RoleCast.of("ann", order)!!
        assertEquals(RoleSeat("ann", "clip-a", roleA.offset, 0f), cast.a)
        assertEquals(RoleSeat("bea", "clip-b", roleB.offset, PI.toFloat()), cast.b)
        val sw = cast.switched()
        assertEquals("bea", sw.a.npc)
        assertEquals("ann", sw.b.npc)
        // ann now has what bea had, and the other way round
        assertEquals(cast.seatOf("bea")!!.copy(npc = "ann"), sw.seatOf("ann"))
        assertEquals(cast.seatOf("ann")!!.copy(npc = "bea"), sw.seatOf("bea"))
        assertEquals(1, sw.roleOf("ann"))
        assertEquals(0, sw.roleOf("bea"))
        assertEquals(-1, sw.roleOf("cid"))
    }

    @Test
    fun theSwappedOrderLeadsWithThePartnerAndKeepsRolesSoundsAndPair() {
        val (lead, swapped) = RoleSwitch.swapped("ann", order)!!
        assertEquals("bea", lead)
        assertEquals("ann", swapped.partnerId)
        assertEquals(order.roles, swapped.roles, "role A stays role A: only the people change")
        assertEquals(order.sounds, swapped.sounds)
        assertEquals("p1", swapped.pairId)
        // as played: the cast is the switched one
        assertEquals(RoleCast.of("ann", order)!!.switched(), RoleCast.of(lead, swapped))
    }

    @Test
    fun switchingTwiceIsTheSame() {
        val cast = RoleCast.of("ann", order)!!
        assertEquals(cast, cast.switched().switched())
        val (l1, o1) = RoleSwitch.swapped("ann", order)!!
        val (l2, o2) = RoleSwitch.swapped(l1, o1)!!
        assertEquals("ann", l2)
        assertEquals(order, o2)
        assertEquals("ann" to order, RoleSwitch.apply("ann", order, switch = false))
        assertEquals(RoleSwitch.swapped("ann", order), RoleSwitch.apply("ann", order, switch = true))
    }

    @Test
    fun aSoloOrderHasNothingToSwitch() {
        val solo = PerformanceOrder(null, listOf(roleA), 2f)
        assertNull(RoleSwitch.swapped("ann", solo))
        assertNull(RoleCast.of("ann", solo))
        assertEquals("ann" to solo, RoleSwitch.apply("ann", solo, switch = true))
        assertFalse(RoleSwitch.switchable(1))
        assertTrue(RoleSwitch.switchable(2))
        assertFalse(RoleSwitch.switchable(3))
        assertFalse(PairConfirm.open("solo", "ann", "bea", 1, RoleMemory(listOf("solo"))).switchable)
        assertEquals(false, PairConfirm.open("solo", "ann", "bea", 1, RoleMemory(listOf("solo"))).switch().switched)
    }

    @Test
    fun refitKeepsTheContactAndTakesEachNewBodysSize() {
        // fitted for a gnome (0.5) on A and a human on B, then the other way round
        val fitted = PairFit.roles(listOf(roleA, roleB), floatArrayOf(0.5f, 1f))
        val refit = RoleSwitch.refit(fitted, 0.5f, 1f)
        val expected = PairFit.roles(listOf(roleA, roleB), floatArrayOf(1f, 0.5f))
        for (i in 0..1) {
            assertEquals(expected[i].offset.x, refit[i].offset.x, 1e-5f)
            assertEquals(expected[i].offset.y, refit[i].offset.y, 1e-5f)
            assertEquals(expected[i].offset.z, refit[i].offset.z, 1e-5f)
            assertEquals(expected[i].clip, refit[i].clip)
        }
        // twice: back where it was
        val back = RoleSwitch.refit(refit, 1f, 0.5f)
        for (i in 0..1) assertEquals(fitted[i].offset.x, back[i].offset.x, 1e-5f)
        assertSame(fitted, RoleSwitch.refit(fitted, 1f, 1f))
    }

    @Test
    fun theChoiceIsRememberedPerAnimationAndSurvivesSaving() {
        val m = RoleMemory()
        assertFalse(m.isSwitched("pair:x"))
        assertTrue(m.toggle("pair:x"))
        assertTrue(m.isSwitched("pair:x"))
        assertFalse(m.isSwitched("pair:y"), "per animation")
        m.set("pair:y", true)
        val saved = RoleMemory.decode(m.encode())
        assertTrue(saved.isSwitched("pair:x"))
        assertTrue(saved.isSwitched("pair:y"))
        assertFalse(saved.toggle("pair:x"))
        assertFalse(saved.isSwitched("pair:x"))
        assertEquals(0, RoleMemory.decode(null).size)
        assertEquals(0, RoleMemory.decode("").size)
        assertFalse(m.set("bad\nid", true))
        // the preview opens switched as remembered
        assertTrue(PairConfirm.open("pair:y", "ann", "bea", 2, saved).switched)
        assertFalse(PairConfirm.open("pair:z", "ann", "bea", 2, saved).switched)
    }

    @Test
    fun theConfirmationGoesFromPreviewToPlayingOrCancelledAndNothingElse() {
        assertEquals(ConfirmPhase.IDLE, PairConfirm.IDLE.phase)
        assertFalse(PairConfirm.IDLE.open)
        assertEquals(PairConfirm.IDLE, PairConfirm.IDLE.play(), "nothing to play when idle")
        val c = PairConfirm.open("pair:x", "ann", "bea", 2, null)
        assertEquals(ConfirmPhase.PREVIEW, c.phase)
        assertEquals("ann", c.lead); assertEquals("bea", c.other)
        val s = c.switch()
        assertEquals("bea", s.lead); assertEquals("ann", s.other)
        assertEquals(c, s.switch(), "double switch")
        val played = s.play()
        assertEquals(ConfirmPhase.PLAYING, played.phase)
        assertEquals("bea", played.lead, "plays as last shown")
        assertEquals(played, played.switch(), "no switching once playing")
        assertEquals(played, played.cancel())
        val cancelled = c.cancel()
        assertEquals(ConfirmPhase.CANCELLED, cancelled.phase)
        assertEquals(cancelled, cancelled.play(), "a cancelled one never plays")
        assertFalse(PairConfirm.open("pair:x", "ann", "ann", 2, null).switchable, "one person with themselves")
    }

    @Test
    fun holdFrameIsTheFirstKeyHeld() {
        val t = BoneTrack("Root", Channel.POSITION, floatArrayOf(0f, 0.5f, 1f), floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f))
        val anim = Anim("id", "n", "c", 1f, false, listOf(t), "src", 0L)
        val held = HoldFrame.of(anim)
        assertTrue(held.loop)
        val h = held.tracks.single()
        assertContentEquals(floatArrayOf(0f, 1f), h.times)
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 1f, 2f, 3f), h.values)
        assertEquals("x", PreviewKeys.heldOf(PreviewKeys.hold("x")))
        assertNull(PreviewKeys.heldOf("x"))
    }

    @Test
    fun theFramingSeesBothFromAcrossTheirLine() {
        val pose = PreviewFraming.frame(0f, 0f, 2f, 0f, 0f, 0f, 50f, null)
        assertEquals(1f, pose.at.x, 1e-5f)
        assertEquals(0f, pose.at.x - pose.eye.x, 1e-5f, "square on to the line between them")
        assertTrue(abs(pose.eye.y) >= PreviewFraming.MIN_DISTANCE)
        // a wall on the first side: the other
        val other = PreviewFraming.frame(0f, 0f, 2f, 0f, 0f, 0f, 50f) { eye, _ -> eye.y < 0f }
        assertTrue(other.eye.y < 0f)
    }

    @Test
    fun thePreviewPlacesAreWhereTheOrderStartsAndSwitchFollows() {
        val sim = SceneSimulation(listOf("ann", "bea"), emptySet(), Random(1), wanderOnly = true)
        val places = sim.previewPlaces("ann", order)!!
        assertEquals(listOf(0, 1), places.map { it.index })
        assertEquals(listOf("clip-a", "clip-b"), places.map { it.clip })
        val annAt = sim.position("ann")!!
        // ann's slot is 0.4 behind the anchor (her spot) along her facing; bea's 0.4 ahead
        assertEquals(0.8f, kotlin.math.hypot(places[1].x - places[0].x, places[1].y - places[0].y), 0.11f)
        assertEquals(annAt.z, places[0].z, 1e-5f)
        val (lead, swapped) = RoleSwitch.swapped("ann", order)!!
        val sw = sim.previewPlaces(lead, swapped)!!
        assertEquals(listOf(1, 0), sw.map { it.index }, "bea on A, ann on B")
        assertEquals(listOf("clip-a", "clip-b"), sw.map { it.clip })
        assertNull(sim.previewPlaces("ann", PerformanceOrder(null, listOf(roleA), 2f)))
    }

    @Test
    fun switchingMidPlayRestartsWithThePeopleInEachOthersRoles() {
        val sim = SceneSimulation(listOf("ann", "bea"), emptySet(), Random(1), wanderOnly = true)
        assertTrue(sim.perform("ann", order.copy(pairId = null)))
        assertFalse(sim.switchRoles("ann"), "still walking: nothing to switch")
        repeat(15) { sim.tick(0.1f) }
        val a = Pose(); val b = Pose()
        sim.pose(0, a); sim.pose(1, b)
        assertEquals("clip-a", a.playback?.clip)
        assertEquals("clip-b", b.playback?.clip)
        val ax = a.x; val ay = a.y; val bx = b.x; val by = b.y
        val command = a.playback!!.command
        assertTrue(sim.switchRoles("bea"))
        sim.pose(0, a); sim.pose(1, b)
        assertEquals("clip-b", a.playback?.clip, "ann now plays B")
        assertEquals("clip-a", b.playback?.clip, "bea now plays A")
        assertEquals(a.playback?.command, b.playback?.command, "one start for both")
        assertNotEquals(command, a.playback?.command, "started again")
        repeat(10) { sim.tick(0.05f) }   // past the join's ease
        sim.pose(0, a); sim.pose(1, b)
        assertEquals(bx, a.x, 1e-3f); assertEquals(by, a.y, 1e-3f)
        assertEquals(ax, b.x, 1e-3f); assertEquals(ay, b.y, 1e-3f)
        assertEquals(0, sim.partnerOf("bea")?.role)
        // and back
        assertTrue(sim.switchRoles("ann"))
        sim.pose(0, a)
        assertEquals("clip-a", a.playback?.clip)
    }
}
