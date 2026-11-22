package com.logie.wifecraft.simulation

import com.logie.wifecraft.dialogue.JoiSteer
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JoiHandStrokeTest {
    private fun near(a: Double, b: Double, e: Double = 1e-6) = abs(a - b) <= e

    // ---- tempo mapping

    @Test fun theStagesStrokeSlowThenSteadyThenFastAndStopAtTheEnd() {
        val t = HandStrokeTempo::forStage
        assertTrue(t("setup") < t("build") && t("warmup") < t("build"), "slow and teasing before the build")
        assertTrue(t("build") < t("edge") && t("edge") <= t("countdown"), "fast at the edge and the count")
        assertTrue(t("hold") < t("build") && t("permit") < t("build"), "slow at the hold and the permit")
        assertEquals(0f, t("afterglow"))
        for (s in listOf("setup", "warmup", "build", "edge", "hold", "countdown", "permit")) assertTrue(t(s) in 0.2f..HandStrokeTempo.MAX, s)
    }

    @Test fun herWordsChangeTheTempo() {
        assertEquals(0.7f, HandStrokeTempo.hearing("Slower now, nice and slow."))
        assertEquals(1.35f, HandStrokeTempo.hearing("Faster. Keep up with me."))
        assertEquals(0f, HandStrokeTempo.hearing("Stop. Don't move."))
        assertEquals(1f, HandStrokeTempo.hearing("Good, just like that."))
        assertEquals(0.7f, HandStrokeTempo.hearing("Faster... no, slower."), "the last word wins")
        assertEquals(1.35f, HandStrokeTempo.hearing("Slowly now... and faster."), "the last word wins")
    }

    @Test fun theChipsSteerTheTempo() {
        assertEquals(1f, HandStrokeTempo.steer(emptySet()))
        assertTrue(HandStrokeTempo.steer(setOf(JoiSteer.SLOWER)) < 1f)
        assertTrue(HandStrokeTempo.steer(setOf(JoiSteer.FIRM)) > 1f)
    }

    @Test fun aFasterStrokeIsShorterAndSwaysMore() {
        assertTrue(HandStrokeTempo.reach(0.5f) > HandStrokeTempo.reach(1.0f))
        assertTrue(HandStrokeTempo.reach(1.0f) > HandStrokeTempo.reach(2.0f))
        assertTrue(HandStrokeTempo.reach(2.6f) >= 0.55f)
        assertEquals(0f, HandStrokeTempo.sway(0f))
        assertTrue(HandStrokeTempo.sway(1.0f) > HandStrokeTempo.sway(0.3f))
    }

    @Test fun theTempoInWordsFeedsThePrompt() {
        assertEquals("slowly", HandStrokeTempo.words(0.6f))
        assertEquals("steadily", HandStrokeTempo.words(1.0f))
        assertEquals("quickly", HandStrokeTempo.words(1.7f))
        assertNull(HandStrokeTempo.action(false, 1f))
        assertTrue(HandStrokeTempo.action(true, 0.6f)!!.contains("slowly"))
        assertTrue(HandStrokeTempo.action(true, 0f)!!.contains("still"))
    }

    // ---- the loop's maths

    @Test fun theStrokeLoopsWithoutASeamAndStaysInRange() {
        assertTrue(near(HandStroke.profile(0.0), HandStroke.profile(1.0)))
        assertTrue(near(HandStroke.hip(0.0), HandStroke.hip(1.0)))
        var lo = 9.0; var hi = -9.0
        for (i in 0..400) { val p = HandStroke.profile(i / 400.0); lo = minOf(lo, p); hi = maxOf(hi, p) }
        assertTrue(lo >= -1.0001 && hi <= 1.0001 && lo < -0.9 && hi > 0.9, "lo $lo hi $hi")
    }

    @Test fun thePhaseRunsWithTheTempoAndWraps() {
        var tempo = 1.5f; var weight = 1f; var phase = 0.0
        var elapsed = 0.0
        repeat(200) {
            val (t, w, p) = JoiStroke.step(tempo, weight, phase, 1.5f, 1f, 0.05f)
            tempo = t; weight = w; phase = p; elapsed += 0.05
            assertTrue(phase in 0.0..1.0)
        }
        assertTrue(near(phase, (1.5 * elapsed) % 1.0, 1e-4), "phase $phase")
    }

    @Test fun theTempoEasesToItsTargetWithoutOvershoot() {
        var tempo = 0.6f; var w = 1f; var p = 0.0
        var prev = tempo
        repeat(100) {
            val (t, ww, pp) = JoiStroke.step(tempo, w, p, 1.9f, 1f, 0.05f)
            assertTrue(t >= prev - 1e-6f && t <= 1.9f + 1e-6f); prev = t; tempo = t; w = ww; p = pp
        }
        assertEquals(1.9f, tempo)
    }

    // ---- the state

    @Test fun startingRaisesTheArmsAndTheTempoFollowsTheStage() {
        val s = JoiStroke()
        s.start("build")
        assertTrue(s.active && s.running)
        repeat(300) { s.advance(0.05f) }
        assertEquals(1f, s.weight)
        assertEquals(HandStrokeTempo.forStage("build"), s.tempo)
        s.onStage("edge")
        assertEquals(HandStrokeTempo.forStage("edge"), s.target)
        repeat(300) { s.advance(0.05f) }
        assertEquals(HandStrokeTempo.forStage("edge"), s.tempo)
    }

    @Test fun herWordsSlowAndQuickenAndAStageForgetsThem() {
        val s = JoiStroke(); s.start("build"); repeat(200) { s.advance(0.05f) }
        val g0 = s.generation
        s.hear("A little slower for me.")
        assertTrue(s.generation > g0, "a new plan is baked")
        assertEquals(1.0f * 0.7f, s.target, 1e-5f)
        repeat(200) { s.advance(0.05f) }
        assertEquals(0.7f, s.tempo, 1e-4f)
        s.hear("Faster now.")
        assertEquals(0.7f * 1.35f, s.target, 1e-5f)
        s.hear("Stop.")
        assertEquals(0f, s.target)
        s.onStage("edge")
        assertEquals(HandStrokeTempo.forStage("edge"), s.target, "the new stage has its own tempo")
        s.steer(setOf(JoiSteer.SLOWER))
        assertTrue(s.target < HandStrokeTempo.forStage("edge"))
        val g1 = s.generation
        s.hear("Good."); assertEquals(g1, s.generation, "nothing to change")
    }

    @Test fun theAfterglowAndStopLowerTheArms() {
        val s = JoiStroke(); s.start("permit"); repeat(100) { s.advance(0.05f) }
        s.onStage("afterglow")
        assertFalse(s.active)
        repeat(100) { s.advance(0.05f) }
        assertFalse(s.running, "the arms are down")
        assertEquals(0f, s.weight)
        s.start("build"); s.stop(); repeat(100) { s.advance(0.05f) }
        assertFalse(s.running)
        assertNull(s.action())
    }

    @Test fun whatSheIsDoingGoesIntoThePrompt() {
        val s = JoiStroke(); s.start("warmup"); repeat(200) { s.advance(0.05f) }
        assertTrue(s.action()!!.contains("slowly"), s.action())
        s.onStage("countdown"); repeat(300) { s.advance(0.05f) }
        assertTrue(s.action()!!.contains("quick") || s.action()!!.contains("fast"), s.action())
    }

    // ---- the arms

    private val rig = StrokeRig.HUMAN_WOMAN

    @Test fun theArmReachesTheGripWithItsBonesKeptTheirLength() {
        for (arm in listOf(rig.left, rig.right)) {
            val side = if (arm === rig.left) 1.0 else -1.0
            for (off in listOf(-1.0, 0.0, 1.0)) {
                val target = HandStroke.grip(rig, side, true, off, 1.0)
                val pole = Pt3(-0.35, 0.55 * side, -0.8)
                val e = HandStroke.elbow(arm, target, pole)
                assertTrue(near((e - arm.shoulder).length, arm.upper, 1e-9))
                assertTrue(near((target - e).length, arm.fore, 1e-6), "forearm ${(target - e).length} vs ${arm.fore}")
                val (upper, fore) = HandStroke.armTurns(arm, target, pole)
                val e2 = arm.shoulder + upper.rotate(arm.elbow - arm.shoulder)
                val w2 = e2 + fore.rotate(upper.rotate(arm.wrist - arm.elbow))
                assertTrue((e2 - e).length < 1e-6 && (w2 - target).length < 1e-6, "the turns bring the wrist to the grip")
            }
        }
    }

    @Test fun theGripIsInFrontOfHerHipsAndTheStrokeMovesAlongItsAxis() {
        val pelvisZ = 1.145
        for (side in listOf(1.0, -1.0)) {
            val a = HandStroke.grip(rig, side, true, -1.0, 1.0); val b = HandStroke.grip(rig, side, true, 1.0, 1.0)
            for (g in listOf(a, b)) {
                assertTrue(g.x in 0.12..0.5, "forward ${g.x}")
                assertTrue(g.z in pelvisZ - 0.2..pelvisZ + 0.1, "height ${g.z}")
                assertTrue(abs(g.y) < 0.12, "near the middle ${g.y}")
            }
            val d = (b - a)
            assertTrue(d.length in 0.1..0.25, "stroke length ${d.length}")
            assertTrue(near(d.unit().dot(rig.axis), 1.0, 1e-9), "along its axis")
        }
    }

    @Test fun anArmAtRestIsLeftAsItIs() {
        val anim = HandStroke.bake("t", 2f, 0.3, 0f, 0f, 0f, 0f, StrokeHands.BOTH)
        for (t in anim.tracks) for (i in t.times.indices) {
            assertTrue(abs(t.values[i * 4]) < 1e-6 && abs(t.values[i * 4 + 1]) < 1e-6 && abs(t.values[i * 4 + 2]) < 1e-6 && abs(t.values[i * 4 + 3] - 1f) < 1e-6,
                "${t.bone} key $i is not the identity")
        }
    }

    @Test fun theBakedLayerHasTheRightTracksKeysAndUnitRotations() {
        val both = HandStroke.bake("b", 3f, 0.0, 1f, 1f, 1f, 1f, StrokeHands.BOTH)
        val right = HandStroke.bake("r", 3f, 0.0, 1f, 1f, 1f, 1f, StrokeHands.RIGHT)
        for (n in listOf("L_UpperArm", "L_Forearm", "L_Hand", "R_UpperArm", "R_Forearm", "R_Hand", "L_Index1", "R_Pinky1", "Spine1", "Chest"))
            assertNotNull(both.tracks.firstOrNull { it.bone == n }, n)
        assertTrue(right.tracks.none { it.bone.startsWith("L_") }, "the free arm is left as she stands")
        assertTrue(right.tracks.any { it.bone == "R_Forearm" })
        for (t in both.tracks) {
            assertEquals(61, t.times.size)
            for (i in t.times.indices) {
                val n = sqrt((0 until 4).sumOf { (t.values[i * 4 + it] * t.values[i * 4 + it]).toDouble() })
                assertTrue(abs(n - 1.0) < 1e-4, "${t.bone} not a unit rotation")
            }
        }
        assertFalse(both.loop)
        assertEquals(3f, both.duration)
    }

    @Test fun theStrokeMovesAtItsTempo() {
        // the forearm's turn over three seconds crosses its mean twice a stroke: about 2 x tempo x seconds times
        fun crossings(tempo: Float): Int {
            val a = HandStroke.bake("c", 6f, 0.0, tempo, 1f, tempo, 1f, StrokeHands.RIGHT)
            val t = a.tracks.first { it.bone == "Spine1" }
            val xs = (t.times.indices).map { t.values[it * 4 + 2].toDouble() }
            val mean = xs.average()
            return (1 until xs.size).count { (xs[it - 1] - mean) * (xs[it] - mean) < 0 }
        }
        val slow = crossings(0.6f); val fast = crossings(1.8f)
        assertTrue(fast > slow * 2, "slow $slow fast $fast")
        assertTrue(abs(slow - 2 * 0.6 * 6) <= 3 && abs(fast - 2 * 1.8 * 6) <= 3, "slow $slow fast $fast")
    }

    @Test fun aLayerBakedAgainFromTheStateGoesOnWhereTheLastLeftOff() {
        // the host bakes again whenever the plan changes: the new layer's first key is the old one's key at that moment
        val s = JoiStroke(); s.start("build"); repeat(100) { s.advance(0.05f) }
        val first = s.bake(6f)
        repeat(60) { s.advance(0.05f) }                       // three seconds on (the bake's own key 60 at 20 fps)
        val second = s.bake(6f)
        for (b in listOf("R_UpperArm", "R_Forearm", "L_Hand", "Spine1")) {
            val a = first.tracks.first { it.bone == b }; val c = second.tracks.first { it.bone == b }
            for (k in 0 until 4) assertTrue(abs(a.values[60 * 4 + k] - c.values[k]) < 2e-3, "$b component $k")
        }
    }

    @Test fun aStopHoldsTheHandsStillWhereTheyAre() {
        val a = HandStroke.bake("s", 2f, 0.25, 0f, 1f, 0f, 1f, StrokeHands.RIGHT)
        val t = a.tracks.first { it.bone == "R_Forearm" }
        for (i in t.times.indices) for (k in 0 until 4) assertTrue(abs(t.values[i * 4 + k] - t.values[k]) < 1e-6, "moves at a stop")
    }
}
