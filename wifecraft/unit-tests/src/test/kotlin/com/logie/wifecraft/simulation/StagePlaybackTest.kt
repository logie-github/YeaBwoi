package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.*

class StagePlaybackTest {
    private val S = SegmentKind.START
    private val L = SegmentKind.LOOP
    private val E = SegmentKind.END

    /** Every part [cursor] plays until it is done (at most 50). */
    private fun run(cursor: StageCursor, kinds: List<SegmentKind>, onStep: (Int) -> Unit = {}): List<SegmentKind> {
        val out = mutableListOf(kinds[cursor.index])
        var i = 0
        while (i++ < 50) {
            onStep(i)
            val next = cursor.next()
            if (next < 0) break
            out += kinds[next]
        }
        return out
    }

    @Test
    fun onceIsStartLoopEnd() {
        val kinds = listOf(S, L, E)
        assertEquals(listOf(S, L, E), run(StageCursor(kinds, 1, false), kinds))
    }

    @Test
    fun repeatNPlaysTheLoopNTimesBetweenOneStartAndOneEnd() {
        val kinds = listOf(S, L, E)
        assertEquals(listOf(S, L, L, L, E), run(StageCursor(kinds, 3, false), kinds))
        // several loop clips: a pass is all of them in turn
        val dance = listOf(S, L, L, E)
        val c = StageCursor(dance, 2, false)
        val indices = mutableListOf(c.index)
        while (true) { val n = c.next(); if (n < 0) break; indices += n }
        assertEquals(listOf(0, 1, 2, 1, 2, 3), indices)
    }

    @Test
    fun untilStoppedLoopsAndPlaysTheEndOnlyWhenStopped() {
        val kinds = listOf(S, L, E)
        val c = StageCursor(kinds, 1, true)
        assertEquals(0, c.index)
        repeat(20) { assertEquals(1, c.next(), "start, then loop on and on") }
        assertEquals(L, c.kind)
        assertEquals(20, c.pass, "the loop counter")
        assertEquals(2, c.stop(), "stop: the end at once")
        assertEquals(E, c.kind)
        assertEquals(StageCursor.KEEP, c.stop(), "stopping again lets the end play on")
        assertEquals(StageCursor.DONE, c.next())
    }

    @Test
    fun stoppingInTheStartSkipsTheLoops() {
        val c = StageCursor(listOf(S, L, E), 5, false)
        assertEquals(2, c.stop())
        assertEquals(StageCursor.DONE, c.next())
    }

    @Test
    fun withoutAnEndStopIsDoneAndFinishPassEndsAfterThisPass() {
        val loopOnly = StageCursor(listOf(L), 1, true)
        repeat(3) { assertEquals(0, loopOnly.next()) }
        assertEquals(StageCursor.DONE, loopOnly.stop())
        val c = StageCursor(listOf(S, L, E), 1, true)
        c.next(); c.next(); c.next()            // start, loop, loop (pass 3 now)
        c.finishPass()
        assertEquals(2, c.next(), "this pass was the last: then the end")
        assertEquals(StageCursor.DONE, c.next())
    }

    @Test
    fun theJoinEaseStartsWhereItWasAndEndsWhereItIs() {
        assertEquals(1f, JoinEase.weight(0f, JoinEase.JOIN))
        assertEquals(0f, JoinEase.weight(JoinEase.JOIN, JoinEase.JOIN))
        assertEquals(0.5f, JoinEase.weight(JoinEase.JOIN / 2, JoinEase.JOIN), 1e-6f)
        assertEquals(0f, JoinEase.weight(0.1f, 0f), "no ease: where it is")
        // smoothstep: no step at either end, and never back
        var last = 1f
        var steepest = 0f
        val n = 100
        for (i in 1..n) {
            val w = JoinEase.weight(JoinEase.JOIN * i / n, JoinEase.JOIN)
            assertTrue(w <= last)
            steepest = maxOf(steepest, last - w)
            last = w
        }
        assertEquals(1.5f / n, steepest, 1e-3f, "the steepest step is 1.5x a straight line's")
        assertTrue(1f - JoinEase.weight(JoinEase.JOIN / n, JoinEase.JOIN) < 0.001f, "it starts gently")
        assertEquals(-0.2f, JoinEase.wrap((2 * PI).toFloat() - 0.2f), 1e-5f, "the short way round")
        assertEquals(0.3f, JoinEase.wrap(0.3f + 4 * PI.toFloat()), 1e-4f)
    }

    private val zero = Vec3(0f, 0f, 0f)
    private fun solo(clip: String) = listOf(PlaybackRole(clip, zero, 0f))
    private fun staged(repeat: Boolean, loops: Int = 1, name: String = "statue") = PerformanceOrder(null, solo("$name-loop"), 0.5f,
        repeat = repeat, repetitions = loops, name = name, segments = listOf(
            PlaybackSegment(S, solo("$name-start"), 0.3f), PlaybackSegment(L, solo("$name-loop"), 0.5f),
            PlaybackSegment(E, solo("$name-end"), 0.4f)))

    /** The clips [sim]'s first person plays over [seconds], each new play once (a repeated clip again when it starts again). */
    private fun clipsOver(sim: SceneSimulation, seconds: Float, out: MutableList<String> = mutableListOf()): MutableList<String> {
        val p = Pose()
        var command = -1L
        sim.pose(0, p)
        p.playback?.let { if (it.command != command) { out += it.clip; command = it.command } }
        var t = 0f
        while (t < seconds) {
            sim.tick(0.05f); t += 0.05f
            sim.pose(0, p)
            val pb = p.playback ?: continue
            if (pb.command != command) { out += pb.clip; command = pb.command }
        }
        return out
    }

    @Test
    fun aStagedOrderPlaysItsStartLoopsAndEndThenStands() {
        val sim = SceneSimulation(listOf("a"), emptySet(), Random(1), wanderOnly = true)
        assertTrue(sim.perform("a", staged(false, loops = 2)))
        assertEquals(listOf("statue-start", "statue-loop", "statue-loop", "statue-end"), clipsOver(sim, 3f))
        assertFalse(sim.people().single().performing)
    }

    @Test
    fun repeatedItIsStartThenLoopUntilStoppedAndStopPlaysTheEnd() {
        val sim = SceneSimulation(listOf("a"), emptySet(), Random(1), wanderOnly = true)
        assertTrue(sim.perform("a", staged(true)))
        val seen = clipsOver(sim, 6f)
        assertEquals("statue-start", seen.first())
        assertTrue(seen.drop(1).all { it == "statue-loop" } && seen.size > 8, "$seen")
        val person = sim.people().single()
        assertTrue(person.performing && person.repeating)
        assertTrue(person.playing!!.startsWith("statue · Loop ") && person.playing!!.endsWith("repeating"), person.playing)
        assertTrue(sim.stop("a"))
        val after = clipsOver(sim, 1f)
        assertEquals(listOf("statue-end"), after, "the end at once, then nothing")
        assertNull(Pose().also { sim.pose(0, it) }.playback)
    }

    @Test
    fun stopRepeatFinishesThePassThenPlaysTheEnd() {
        val sim = SceneSimulation(listOf("a"), emptySet(), Random(1), wanderOnly = true)
        assertTrue(sim.perform("a", staged(true)))
        clipsOver(sim, 1.0f)
        sim.stopRepeat("a")
        val after = clipsOver(sim, 2f)
        assertEquals(listOf("statue-end"), after.filter { it != "statue-loop" })
        assertTrue(after.count { it == "statue-loop" } <= 1)
    }

    @Test
    fun aRepeatedQueuePlaysEachEntryWholeAndRoundAgainAndSkipMovesOn() {
        val sim = SceneSimulation(listOf("a"), emptySet(), Random(1), wanderOnly = true)
        sim.queueOptions("a", repeat = true, shuffle = false)
        assertTrue(sim.enqueue("a", listOf(staged(false, name = "one"), PerformanceOrder(null, solo("two"), 0.5f, name = "two"))))
        val seen = clipsOver(sim, 6f)
        val round = listOf("one-start", "one-loop", "one-end", "two")
        assertEquals(round + round + round, seen.take(12), "$seen")
        assertTrue(sim.people().single().queueRepeat)
        assertEquals(2, sim.people().single().queued, "a repeated queue keeps its entries")
        // Skip (stop with the queue kept): the end of this one, then the next
        val p = Pose()
        do { sim.tick(0.05f); sim.pose(0, p) } while (p.playback?.clip != "one-loop")
        assertTrue(sim.stop("a"))
        assertEquals(listOf("one-end", "two"), clipsOver(sim, 0.7f))
        // Stop everything: the end, the queue cleared and not repeated
        do { sim.tick(0.05f); sim.pose(0, p) } while (p.playback?.clip != "one-loop")
        assertTrue(sim.stopAll("a"))
        assertEquals(listOf("one-end"), clipsOver(sim, 2f))
        assertEquals(0, sim.people().single().queued)
        assertFalse(sim.people().single().queueRepeat)
    }

    @Test
    fun aShuffledQueuePlaysEveryEntry() {
        val sim = SceneSimulation(listOf("a"), emptySet(), Random(7), wanderOnly = true)
        sim.queueOptions("a", repeat = false, shuffle = true)
        val names = (1..5).map { "c$it" }
        assertTrue(sim.enqueue("a", names.map { PerformanceOrder(null, solo(it), 0.3f) }))
        val seen = clipsOver(sim, 3f)
        assertEquals(names.toSet(), seen.toSet())
        assertEquals(5, seen.size)
    }

    @Test
    fun aRepeatingStageNeverReWalksOrReSeatsAndItsJoinsAreEased() {
        val sim = SceneSimulation(listOf("lead", "guest"), emptySet(), Random(1), wanderOnly = true)
        val first = listOf(PlaybackRole("s1-a", Vec3(0.1f, 0f, 0f), 0f), PlaybackRole("s1-b", Vec3(0.6f, 0f, 0f), PI.toFloat()))
        // the next stage stands them a little elsewhere and turned (as an author's next stage does)
        val second = listOf(PlaybackRole("s2-a", Vec3(0.25f, 0.1f, 0f), 0.4f), PlaybackRole("s2-b", Vec3(0.7f, -0.1f, 0f), PI.toFloat() - 0.3f))
        assertTrue(sim.perform("lead", PerformanceOrder("guest", first, 0.5f, repetitions = 3, name = "Stage 1")))
        assertTrue(sim.enqueue("lead", listOf(PerformanceOrder("guest", second, 0.5f, repeat = true, name = "Stage 2"))))
        val a = Pose(); val b = Pose()
        // until both play
        var n = 0
        do { sim.tick(1f / 120f); sim.pose(0, a); sim.pose(1, b); check(++n < 10_000) } while (a.playback == null || b.playback == null)
        // past the settle onto their places (they turn from where they walked: JoinEase.SETTLE)
        repeat(60) { sim.tick(1f / 120f) }
        sim.pose(0, a); sim.pose(1, b)
        val places = HashSet<String>()
        var lastA = floatArrayOf(a.x, a.y, a.facing); var lastB = floatArrayOf(b.x, b.y, b.facing)
        var maxStep = 0f
        var maxTurn = 0f
        var stage2 = 0
        repeat(120 * 8) {
            sim.tick(1f / 120f)
            val people = sim.people()
            for (person in people) assertNotEquals(Activity.WALK, person.activity, "no re-walk between loops or stages")
            sim.pose(0, a); sim.pose(1, b)
            assertNotNull(a.playback, "never let go between parts"); assertNotNull(b.playback)
            assertEquals(a.playback?.command, b.playback?.command, "both start each part together")
            places += "%.4f,%.4f".format(people[0].position.x, people[0].position.y)
            maxStep = maxOf(maxStep, hypot(a.x - lastA[0], a.y - lastA[1]), hypot(b.x - lastB[0], b.y - lastB[1]))
            maxTurn = maxOf(maxTurn, abs(JoinEase.wrap(a.facing - lastA[2])), abs(JoinEase.wrap(b.facing - lastB[2])))
            lastA = floatArrayOf(a.x, a.y, a.facing); lastB = floatArrayOf(b.x, b.y, b.facing)
            if (a.playback?.clip == "s2-a") stage2++
        }
        assertTrue(stage2 > 120 * 5, "the second stage repeats on")
        assertEquals(2, places.size, "one place a stage: a loop never re-seats ($places)")
        // the stage change is 0.18 yd and 0.4 rad: eased over JoinEase.JOIN, at most ~1.5x a straight line's step a tick
        val jump = hypot(0.15f, 0.1f)
        assertTrue(maxStep < jump * 1.6f / (JoinEase.JOIN * 120f), "largest step a tick $maxStep (the jump $jump)")
        assertTrue(maxTurn < 0.4f * 1.6f / (JoinEase.JOIN * 120f), "largest turn a tick $maxTurn")
        val line = assertNotNull(sim.people()[0].playing)
        assertTrue(line.startsWith("Stage 2 · Loop ") && line.endsWith(" · repeating"), line)
    }

    @Test
    fun theHudLineSaysThePartAndTheLoop() {
        assertEquals("Stage 2 · Cowgirl · Loop 3/5", StageLine.of("Stage 2 · Cowgirl", L, 3, 5, false, false))
        assertEquals("Hug · Loop 3 · repeating", StageLine.of("Hug", L, 3, 1, true, true))
        assertEquals("Hug · Start", StageLine.of("Hug", S, 1, 1, false, true))
        assertEquals("Hug · End", StageLine.of("Hug", E, 2, 2, false, true))
        assertEquals("Wave", StageLine.of("Wave", L, 1, 1, false, false))
    }
}
