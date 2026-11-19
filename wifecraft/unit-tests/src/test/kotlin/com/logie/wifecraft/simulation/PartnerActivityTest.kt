package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The partner activity: choreography, slots, loops, sounds, cooldown, interruption, fallback, places, determinism.
 * Since the merge with the performance runtime (PerformanceTest) a partner animation is a two-person Performance: the
 * stages are APPROACH (walking to their places) and PLAY; there is no separate eased ALIGN or RELEASE stage (the one gone to
 * stands still on its slot, the other walks onto its own; at the end both step apart at once), and the renderer hears of
 * it through Pose.playback (PlaybackKeys) instead of a pair, a role and a serial.
 */
class PartnerActivityTest {
    private val step = 1f / 120f

    private fun anim(id: String, duration: Float, loop: Boolean) = Anim(id, id, "Test", duration, loop, emptyList(), "test:$id", 0L)

    private fun pair(
        id: String, duration: Float = 2f, loops: Boolean = false, locations: Set<String> = emptySet(),
        sounds: List<PartnerSound> = emptyList(),
        slotA: PartnerSlot = PartnerSlot(-0.4f, 0f, 0f, 0f), slotB: PartnerSlot = PartnerSlot(0.4f, 0f, 0f, 180f),
    ) = PartnerPair(id, "Pair $id", anim("$id-a", duration, loops), anim("$id-b", duration, loops), slotA, slotB, loops, locations, sounds)

    private fun sim(ids: List<String>, objects: Set<SceneObject> = emptySet(), seed: Int = 7, chance: Float = 0f,
        pairs: List<PartnerPair> = emptyList(), emotes: ModelEmotes = ModelEmotes.STANDARD) =
        SceneSimulation(ids, objects, Random(seed), partnerChance = chance, pairs = pairs, emotes = emotes)

    private fun activity(sim: SceneSimulation, index: Int): Activity = Pose().also { sim.pose(index, it) }.activity

    private fun until(sim: SceneSimulation, limit: Int = 20_000, done: () -> Boolean): Int {
        var n = 0
        while (!done()) { sim.tick(step); n++; check(n < limit) { "never happened" } }
        return n
    }

    @Test fun approachThenAlignThenPlayWithBothStartedAtTheSameStep() {
        val s = sim(listOf("a", "b", "c"), pairs = listOf(pair("hug")))
        assertTrue(s.partner("a", "c"))
        assertEquals("APPROACH", s.partnerOf("a")?.stage)
        assertEquals("c", s.partnerOf("a")?.partner)
        assertEquals("a", s.partnerOf("c")?.partner)
        assertEquals(Activity.WALK, activity(s, 0))
        assertEquals(Activity.IDLE, activity(s, 2))
        val stages = linkedSetOf<String>()
        var startA = -1L; var startC = -1L
        while (startA < 0 || startC < 0) {
            s.tick(step)
            s.partnerOf("a")?.let { stages += it.stage }
            if (startA < 0 && activity(s, 0) == Activity.PERFORM && Pose().also { p -> s.pose(0, p) }.playback != null) startA = s.steps
            if (startC < 0 && activity(s, 2) == Activity.PERFORM && Pose().also { p -> s.pose(2, p) }.playback != null) startC = s.steps
            check(s.steps < 10_000)
        }
        assertEquals(listOf("APPROACH", "PLAY"), stages.toList())
        assertEquals(startA, startC, "both start on one step")
        assertEquals(startA, s.partnerOf("a")!!.startStep)
        assertEquals(s.partnerOf("a")!!.startStep, s.partnerOf("c")!!.startStep)
        // the partner turned to face the initiator
        val c = s.people()[2]; val a = s.people()[0]
        assertTrue(hypot(a.position.x - c.position.x, a.position.y - c.position.y) < 1.2f)
        val pose = Pose(); s.pose(0, pose)
        assertEquals(PlaybackKeys.pair("hug", 0), pose.playback?.clip); assertEquals(0, s.partnerOf("a")!!.role)
        val command = pose.playback!!.command
        s.pose(2, pose); assertEquals(PlaybackKeys.pair("hug", 1), pose.playback?.clip); assertEquals(1, s.partnerOf("c")!!.role)
        assertEquals(command, pose.playback!!.command, "one command: one start for both")
        assertEquals(listOf("c", null, "a"), s.people().map { it.partner })
    }

    @Test fun slotsAreTheOffsetsTurnedByThePairYaw() {
        val out = FloatArray(4)
        PartnerMath.place(1f, 2f, 0f, (PI / 2).toFloat(), PartnerSlot(1f, 0f, 0.5f, 90f), out)
        assertEquals(1f, out[0], 1e-5f); assertEquals(3f, out[1], 1e-5f); assertEquals(0.5f, out[2], 1e-5f)
        assertEquals(PI.toFloat(), out[3], 1e-5f)
        PartnerMath.place(0f, 0f, 0f, (PI / 2).toFloat(), PartnerSlot(0f, 1f, 0f, 0f), out)   // +Y (left) turned a quarter: -X
        assertEquals(-1f, out[0], 1e-5f); assertEquals(0f, out[1], 1e-5f)
        // slot A to slot B along +X, a to b along +Y: a quarter turn
        val yaw = PartnerMath.yaw(0f, 0f, 0f, 2f, PartnerSlot(-0.5f, 0f, 0f, 0f), PartnerSlot(0.5f, 0f, 0f, 180f), 0f)
        assertEquals((PI / 2).toFloat(), yaw, 1e-5f)

        val s = sim(listOf("a", "b", "c"), pairs = listOf(pair("p", slotA = PartnerSlot(-0.5f, 0.2f, 0f, 30f))))
        assertTrue(s.partner("a", "c"))
        until(s) { s.partnerOf("a")?.stage == "PLAY" }
        val v = s.partnerOf("a")!!
        val expect = FloatArray(4)
        PartnerMath.place(v.ox, v.oy, 0f, v.yaw, PartnerSlot(-0.5f, 0.2f, 0f, 30f), expect)
        val at = s.position("a")!!
        assertEquals(expect[0], at.x, 1e-4f); assertEquals(expect[1], at.y, 1e-4f)
        assertEquals(expect[3], s.people()[0].facing, 1e-4f)
        PartnerMath.place(v.ox, v.oy, 0f, v.yaw, PartnerSlot(0.4f, 0f, 0f, 180f), expect)
        assertEquals(expect[0], s.position("c")!!.x, 1e-4f); assertEquals(expect[1], s.position("c")!!.y, 1e-4f)
    }

    @Test fun theAlignmentIsAnEasedMoveWithoutAPop() {
        val s = sim(listOf("a", "b", "c"), pairs = listOf(pair("p")))
        assertTrue(s.partner("a", "c"))
        var last = s.position("c")!!
        var biggest = 0f
        until(s) {
            val now = s.position("c")!!
            biggest = maxOf(biggest, hypot(now.x - last.x, now.y - last.y))
            last = now
            s.partnerOf("a")?.stage == "PLAY"
        }
        assertTrue(biggest < 0.05f, "largest move in one step: $biggest")
    }

    @Test fun aLoopingPairRepeatsWholeLoopsFiresItsSoundsThenBothPartWithACooldown() {
        val sounds = listOf(PartnerSound(0f, "start", 0), PartnerSound(1f, "middle", 1))
        val s = sim(listOf("a", "b"), pairs = listOf(pair("loop", 2f, loops = true, sounds = sounds)))
        val heard = ArrayList<Pair<Int, String>>()
        s.sounds = { index, name -> heard += index to name }
        assertTrue(s.partner("a", "b"))
        until(s) { s.partnerOf("a")?.stage == "PLAY" }
        var clock = 0f
        // the view is read before each step: the last one (ending it) adds one step to the clock
        until(s) {
            s.partnerOf("a")?.let { clock = it.clock }
            s.partnerOf("a") == null
        }
        clock += step
        val loops = heard.count { it == (1 to "middle") }
        assertTrue(loops in 1..13, "loops: $loops")
        assertEquals(loops * 2f, clock, 0.05f)
        assertTrue(clock >= SceneSimulation.MIN_LOOP_TIME - 0.05f && clock <= SceneSimulation.MAX_LOOP_TIME + 2f)
        assertEquals(loops, heard.count { it == (0 to "start") })
        // both stop together and step apart
        assertNull(s.partnerOf("b"))
        assertEquals(listOf(Activity.WALK, Activity.WALK), s.people().map { it.activity })
        assertTrue(s.people().all { it.partner == null })
    }

    @Test fun theCooldownKeepsThemFromStartingAgainAtOnce() {
        val s = sim(listOf("a", "b"), chance = 1f, pairs = listOf(pair("p", 1f)))
        var t = 0f
        var gap = Float.MAX_VALUE
        var starts = 0
        var inPair = false
        var endedAt = -1f
        repeat((200 / step).toInt()) {
            val before = s.pairStarts
            s.tick(step); t += step
            if (s.pairStarts != before) {
                starts++
                if (endedAt >= 0f) gap = minOf(gap, t - endedAt)
            }
            val now = s.partnerOf("a") != null
            if (inPair && !now) endedAt = t
            inPair = now
        }
        assertTrue(starts >= 2, "starts: $starts")
        assertTrue(gap >= SceneSimulation.MIN_COOLDOWN, "gap: $gap")
    }

    @Test fun anOrderInterruptsAndThePartnerIsReleasedAtOnce() {
        val s = SceneSimulation(listOf("a", "b"), setOf(SceneObject.CHAIR), Random(3), partnerChance = 0f, pairs = listOf(pair("p", 4f)))
        assertTrue(s.partner("a", "b"))
        until(s) { s.partnerOf("a")?.stage == "PLAY" }
        val pose = Pose(); s.pose(1, pose)
        assertTrue(pose.playback != null)
        assertTrue(s.use("a", SceneObject.CHAIR))
        assertNull(s.partnerOf("a")); assertNull(s.partnerOf("b"))
        s.pose(1, pose)
        assertEquals(Activity.IDLE, pose.activity)
        assertNull(pose.playback, "the renderer hears the stop")
        assertEquals(Activity.WALK, s.people()[0].activity)
        // and the partner is free again, not frozen: it carries on by itself
        val seen = HashSet<Activity>()
        repeat(2000) { s.tick(0.05f); seen += s.people()[1].activity }
        assertTrue(seen.size > 1)
        // the scene's end stops every pair
        assertTrue(s.partner("b", "a"))
        s.endPartners()
        assertNull(s.partnerOf("a")); assertNull(s.partnerOf("b"))
    }

    @Test fun theFallbackEmotesAreThoseBothModelsHave() {
        val has = mapOf("a" to setOf(67, 69, 76), "b" to setOf(69, 76, 80))
        val source = ModelEmotes { id, anim -> anim in has.getValue(id) }
        val out = PartnerEmotes.mutual("a", "b", source, ArrayList())
        assertEquals(listOf("Dance", "Kiss"), out.map { it.name })
        for (seed in 1..20) {
            val s = sim(listOf("a", "b"), seed = seed, emotes = source)
            assertTrue(s.partner("a", "b"))
            assertTrue(s.partnerOf("a")!!.emote in setOf(69, 76))
        }
        val none = sim(listOf("a", "b"), emotes = { id, anim -> id == "a" && anim == 67 })
        assertTrue(!none.partner("a", "b"))
    }

    @Test fun pairsNeedingFurnitureTheSceneLacksAreSkipped() {
        val bed = pair("bed", locations = setOf("DOUBLE_BED"))
        val sofa = pair("sofa", locations = setOf("SOFA"))
        val floor = pair("floor", locations = setOf("FLOOR"))
        // a couch only: the sofa pair, never the bed's
        for (seed in 1..20) {
            val s = SceneSimulation(listOf("a", "b"), setOf(SceneObject.COUCH), Random(seed), partnerChance = 0f, pairs = listOf(bed, sofa))
            assertTrue(s.partner("a", "b"))
            assertEquals("Pair sofa", s.partnerOf("a")!!.name)
            assertEquals(SceneObject.COUCH, s.partnerOf("a")!!.furniture)
        }
        // no furniture: neither; the Warcraft emotes instead
        val bare = sim(listOf("a", "b"), pairs = listOf(bed, sofa))
        assertTrue(bare.partner("a", "b"))
        assertTrue(bare.partnerOf("a")!!.emote >= 0)
        // an unknown location ("FLOOR") needs nothing
        val free = sim(listOf("a", "b"), pairs = listOf(bed, floor))
        assertTrue(free.partner("a", "b"))
        assertEquals("Pair floor", free.partnerOf("a")!!.name)
        assertEquals(null, free.partnerOf("a")!!.furniture)
        assertEquals(SceneObject.COUCH, PartnerPlaces.furniture("loveseat"))
        assertEquals(null, PartnerPlaces.furniture("POOL"))
    }

    @Test fun onFurnitureTheOriginIsTheSeatAndTheFurnitureIsHeld() {
        val s = SceneSimulation(listOf("a", "b", "c"), setOf(SceneObject.COUCH), Random(2), partnerChance = 0f,
            pairs = listOf(pair("sofa", locations = setOf("SOFA"))))
        assertTrue(s.partner("a", "b"))
        assertTrue(!s.available(SceneObject.COUCH, "c"))
        until(s) { s.partnerOf("a")?.stage == "PLAY" }
        val v = s.partnerOf("a")!!
        assertEquals(SceneObject.COUCH.approach.x, v.ox, 1e-4f)
        assertEquals(SceneObject.COUCH.approach.y, v.oy, 1e-4f)
        until(s) { s.partnerOf("a") == null }
        assertTrue(s.available(SceneObject.COUCH, "c"))
    }

    @Test fun theSameSeedPlaysTheSameScene() {
        fun run(): List<String> {
            val s = SceneSimulation(listOf("a", "b", "c", "d"), setOf(SceneObject.COUCH, SceneObject.TABLE), Random(42),
                partnerChance = PartnerFrequency.OFTEN.chance, pairs = listOf(pair("p", 1.5f, true), pair("q", 2f)))
            val trace = ArrayList<String>()
            repeat(6000) { i ->
                s.tick(1f / 30f)
                if (i % 50 == 0) trace += s.people().joinToString { "${it.id}:${it.activity}:${it.partner}:${it.position}" }
            }
            trace += "starts=${s.pairStarts}"
            return trace
        }
        val first = run()
        assertEquals(first, run())
        assertTrue(first.last() != "starts=0", "pairs started")
    }

    @Test fun noPairIsChosenWhenTheChanceIsZero() {
        val s = sim(listOf("a", "b", "c", "d"), chance = 0f, pairs = listOf(pair("p")))
        repeat(20_000) { s.tick(0.05f) }
        assertEquals(0, s.pairStarts)
        assertTrue(s.people().all { it.partner == null })
    }

    @Test fun theLiveSceneStartsBothInOneFrameAndSaysThePairsName() {
        val room = CameraPose(Vec3(0f, 0f, 3f), Vec3(10f, 0f, 2f), 50f)
        val live = LiveScene(listOf("a", "b", "c"), emptySet(), emptyMap(), room, 0f, 0f, 0f, 0f, Random(5),
            pairs = listOf(pair("hug", sounds = listOf(PartnerSound(0f, "hi", 1)))), partnerChance = 0f)
        live.running = true
        val started = HashMap<Int, Int>()
        val stopped = HashMap<Int, Int>()
        val heard = ArrayList<Pair<Int, String>>()
        var frame = 0
        val sink = object : LiveScene.Sink {
            override fun camera(eyeX: Float, eyeY: Float, eyeZ: Float, atX: Float, atY: Float, atZ: Float, fov: Float) {}
            override fun place(index: Int, x: Float, y: Float, z: Float, facing: Float, furniture: SceneObject?, sx: Float, sy: Float, sz: Float) {}
            override fun activity(index: Int, activity: Activity) {}
            override fun playback(index: Int, clip: ClipPlayback?) {
                if (clip != null) started.putIfAbsent(index, frame) else if (index in started) stopped.putIfAbsent(index, frame)
            }
            override fun sound(index: Int, name: String) { heard += index to name }
        }
        var snap: SceneSnapshot? = null
        live.order { it.partner("a", "c"); null }
        var t = 0L
        while (stopped.size < 2 && frame < 3000) { live.frame(t, sink) { snap = it }; t += 16_666_667L; frame++ }
        assertEquals(setOf(0, 2), started.keys)
        assertEquals(started[0], started[2])
        assertEquals(stopped[0], stopped[2])
        assertEquals(listOf(2 to "hi"), heard)
        assertEquals(1, snap?.partnerPairs)
        // the notice said the pair's name when it started
        val live2 = LiveScene(listOf("a", "b"), emptySet(), emptyMap(), room, 0f, 0f, 0f, 0f, Random(5),
            pairs = listOf(pair("hug")), partnerChance = 0f)
        live2.running = true
        live2.order { it.partner("a", "b"); null }
        val notices = HashSet<String?>()
        t = 0L
        repeat(600) { live2.frame(t, sink) { notices += it.notice }; t += 16_666_667L }
        assertTrue("Partner animation: Pair hug" in notices, "$notices")
    }

    @Test fun theFrequencySettingReadsBack() {
        assertEquals(PartnerFrequency.OFTEN, PartnerFrequency.of("OFTEN"))
        assertEquals(PartnerFrequency.SOMETIMES, PartnerFrequency.of(null))
        assertEquals(0f, PartnerFrequency.OFF.chance)
    }
}

class PartnerPairAdapterTest {
    @Test fun theLibrarysEnabledPairsAreTaken() {
        val a = Anim("x", "x", "Partner", 2f, true, emptyList(), "s:x", 0L)
        val b = a.copy(id = "y", source = "s:y")
        fun pair(id: String, enabled: Boolean) = com.logie.wifecraft.anim.PairAnim(id, "Name $id", "Partner", a, b,
            com.logie.wifecraft.anim.PairOffset(0.4f, 0.1f, 0f, 0f), com.logie.wifecraft.anim.PairOffset(-0.4f, 0f, 0f, 180f),
            true, setOf("COUCH"), listOf(com.logie.wifecraft.anim.SoundEvent(0.5f, "kiss", 1)), enabled, "s:x+s:y", 0L)
        val taken = PartnerPair.enabled(listOf(pair("on", true), pair("off", false)))
        assertEquals(listOf("on"), taken.map { it.id })
        val p = taken.single()
        assertEquals(PartnerSlot(0.4f, 0.1f, 0f, 0f), p.slotA)
        assertEquals(PartnerSlot(-0.4f, 0f, 0f, 180f), p.slotB)
        assertEquals(listOf(PartnerSound(0.5f, "kiss", 1)), p.sounds)
        assertEquals(2f, p.duration)
        assertEquals(SceneObject.COUCH, PartnerPlaces.furniture(p.locations.single()))
    }
}
