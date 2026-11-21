package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Build mode's hold: no animation ends, switches, times out or is replaced while [SceneSimulation.holdAnimations] is on. */
class AnimationHoldTest {
    private val zero = Vec3(0f, 0f, 0f)
    private fun roles() = listOf(PlaybackRole("lead-clip", zero, 0f), PlaybackRole("guest-clip", zero, 1f))

    private fun clips(sim: SceneSimulation): Pair<String?, String?> {
        val a = Pose(); val b = Pose()
        sim.pose(0, a); sim.pose(1, b)
        return a.playback?.clip to b.playback?.clip
    }

    @Test fun aPairsPlayDoesNotEndWhileHeldAndEndsAfterwards() {
        val sim = SceneSimulation(listOf("lead", "guest"), emptySet(), Random(1), wanderOnly = true)
        assertTrue(sim.perform("lead", PerformanceOrder("guest", roles(), .5f, repeat = false, repetitions = 1)))
        repeat(12) { sim.tick(.1f) }
        assertEquals("lead-clip" to "guest-clip", clips(sim))
        sim.holdAnimations = true
        val command = Pose().also { sim.pose(0, it) }.playback!!.command
        // 60 s: a one-pass play would have ended after half a second
        repeat(600) { sim.tick(.1f); assertEquals("lead-clip" to "guest-clip", clips(sim)) }
        val a = Pose(); sim.pose(0, a)
        assertNotNull(a.playback)
        // each pass is the same clip replayed on the one clock (never another clip, never standing)
        assertTrue(a.playback!!.command >= command)
        sim.holdAnimations = false
        repeat(30) { sim.tick(.1f) }
        assertEquals(null to null, clips(sim))
    }

    @Test fun theSharedClockKeepsRunningWhileHeld() {
        val sim = SceneSimulation(listOf("lead", "guest"), emptySet(), Random(1), wanderOnly = true)
        sim.perform("lead", PerformanceOrder("guest", roles(), .5f, repeat = true))
        repeat(12) { sim.tick(.1f) }
        sim.holdAnimations = true
        val before = sim.partnerOf("lead")
        assertNotNull(before)
        repeat(100) { sim.tick(.1f) }
        val after = sim.partnerOf("lead")
        assertNotNull(after)
        assertEquals(before.stage, after.stage)
        assertTrue(after.clock > before.clock + 9f, "clock ${before.clock} -> ${after.clock}")
    }

    @Test fun aStagedPlayStaysOnItsCurrentPartWhileHeld() {
        val sim = SceneSimulation(listOf("lead", "guest"), emptySet(), Random(1), wanderOnly = true)
        val start = PlaybackSegment(SegmentKind.START, listOf(PlaybackRole("s0", zero, 0f), PlaybackRole("s1", zero, 1f)), .4f)
        val loop = PlaybackSegment(SegmentKind.LOOP, listOf(PlaybackRole("l0", zero, 0f), PlaybackRole("l1", zero, 1f)), .4f)
        val end = PlaybackSegment(SegmentKind.END, listOf(PlaybackRole("e0", zero, 0f), PlaybackRole("e1", zero, 1f)), .4f)
        assertTrue(sim.perform("lead", PerformanceOrder("guest", loop.roles, .4f, repeat = false, repetitions = 1,
            segments = listOf(start, loop, end))))
        // run into the start part, then hold: it loops the start, never moving on to the loop or the end
        repeat(14) { sim.tick(.1f) }
        assertEquals("s0" to "s1", clips(sim))
        sim.holdAnimations = true
        repeat(300) { sim.tick(.1f); assertEquals("s0" to "s1", clips(sim)) }
        sim.holdAnimations = false
        sim.tick(.1f)
        assertEquals("l0" to "l1", clips(sim))
    }

    @Test fun queuedOrdersAndTheNextPickWaitWhileHeld() {
        val sim = SceneSimulation(listOf("lead", "guest"), emptySet(), Random(2), wanderOnly = true)
        sim.perform("lead", PerformanceOrder("guest", roles(), .3f))
        sim.enqueue("lead", listOf(PerformanceOrder("guest", roles().map { it.copy(clip = "${it.clip}-next") }, .3f)))
        repeat(12) { sim.tick(.1f) }
        sim.holdAnimations = true
        repeat(300) { sim.tick(.1f) }
        assertEquals("lead-clip" to "guest-clip", clips(sim))
        sim.holdAnimations = false
        var seen = false
        repeat(60) { sim.tick(.1f); if (clips(sim).first == "lead-clip-next") seen = true }
        assertTrue(seen, "the queued order plays once Build mode is left")
    }

    @Test fun peopleStandingAboutDoNotPickAnythingNewWhileHeld() {
        val sim = SceneSimulation(listOf("a", "b"), emptySet(), Random(3), wanderOnly = true)
        sim.holdAnimations = true
        val start = listOf(sim.position("a"), sim.position("b"))
        repeat(1200) { sim.tick(.1f) }
        assertEquals(start, listOf(sim.position("a"), sim.position("b")))
        sim.holdAnimations = false
        repeat(1200) { sim.tick(.1f) }
        assertFalse(start == listOf(sim.position("a"), sim.position("b")), "they go on with their day afterwards")
    }

    @Test fun aSoloAnimationAlsoLoopsAndAPlayerStopStillWorks() {
        val sim = SceneSimulation(listOf("a"), emptySet(), Random(1), wanderOnly = true)
        assertTrue(sim.perform("a", PerformanceOrder(null, listOf(PlaybackRole("solo", zero, 0f)), .3f)))
        repeat(2) { sim.tick(.1f) }
        sim.holdAnimations = true
        repeat(200) { sim.tick(.1f) }
        val p = Pose(); sim.pose(0, p)
        assertEquals("solo", p.playback?.clip)
        // the player's own Stop is theirs to give
        assertTrue(sim.stop("a"))
        sim.pose(0, p)
        assertNull(p.playback)
    }
}
