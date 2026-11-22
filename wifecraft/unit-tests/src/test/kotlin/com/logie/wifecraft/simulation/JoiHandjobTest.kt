package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.BodyFrame
import com.logie.wifecraft.dialogue.Band
import com.logie.wifecraft.dialogue.JoiLineRequest
import com.logie.wifecraft.dialogue.JoiPersona
import com.logie.wifecraft.dialogue.JoiPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JoiHandjobTest {
    private val libraryHandjob = JoiClip("pair:hj:1", "Handjob loop", "Partner animations", 6f, kind = "HANDJOB", giver = 1, ghostForward = 0.5f)
    private val others = listOf("Sexy dance 1", "Hip sway", "Come hither gesture", "Hair flip", "Blow kiss", "Teasing strip", "Flirty wink")
        .map { JoiClip("k:$it", it) }

    private fun cat(vararg extra: JoiClip) = JoiAnimations.catalogue(others + extra)

    // ---- classification

    @Test fun handjobClipsAreAKindOfTheirOwn() {
        val c = JoiAnimations.classify(libraryHandjob)
        assertEquals(setOf(JoiMove.HANDJOB), c)
        assertEquals(setOf(JoiMove.HANDJOB), JoiAnimations.classify(JoiClip.stroke()))
        // by the pack's category or by words; the giver's role is known
        assertEquals(setOf(JoiMove.HANDJOB), JoiAnimations.classify(JoiClip("a", "Stage 2", "x", kind = "HANDJOB", giver = 0)))
        assertEquals(setOf(JoiMove.HANDJOB), JoiAnimations.classify(JoiClip("a", "Azmodan22: hand job start", "x", giver = 0)))
        assertEquals(setOf(JoiMove.HANDJOB), JoiAnimations.classify(JoiClip("a", "Handy stage 1", "x", giver = 1)))
    }

    @Test fun otherPartnerClipsAndHandjobsWithoutAGiverAreLeftOut() {
        assertTrue(JoiAnimations.classify(JoiClip("a", "Handjob loop", "Partner")).isEmpty(), "no giver known: not playable alone")
        for (n in listOf("Footjob loop", "Blowjob loop", "Titjob loop", "Fingering", "Oral stage 1", "Partner stage 2", "Penetration loop"))
            assertTrue(JoiAnimations.classify(JoiClip("a", n, "Partner", giver = 1)).none { it == JoiMove.HANDJOB }, n)
        // (a teasing handjob is not a plain tease either)
        assertTrue(JoiAnimations.classify(JoiClip("a", "Teasing handjob", "x")).isEmpty())
    }

    @Test fun theGiverIsTheRoleWhoseHandsReachTheOthersPelvis() {
        fun frame(hand: DoubleArray, pelvis: DoubleArray) = BodyFrame(mapOf("L_Hand" to hand, "R_Hand" to hand, "Pelvis" to pelvis), emptySet())
        val near = doubleArrayOf(0.5, 0.0, 1.0); val far = doubleArrayOf(0.5, 0.0, 1.6)
        val pelvisA = doubleArrayOf(0.0, 0.0, 1.0); val pelvisB = doubleArrayOf(0.55, 0.0, 1.0)
        // role B's hands are at A's pelvis...
        val a = listOf(frame(far, pelvisA)); val b = listOf(frame(doubleArrayOf(0.05, 0.0, 1.0), pelvisB))
        assertEquals(1, JoiHandjobs.giverRole(a, b))
        // ...and the other way round
        assertEquals(0, JoiHandjobs.giverRole(b, a))
        // neither reaches: not a hand stage (unless the pack's own category says so: a larger limit)
        val c = listOf(frame(doubleArrayOf(0.0, 0.0, 2.2), pelvisA)); val d = listOf(frame(doubleArrayOf(0.5, 0.0, 2.4), pelvisB))
        assertEquals(-1, JoiHandjobs.giverRole(c, d))
        assertEquals(0, JoiHandjobs.giverRole(c, d, Double.MAX_VALUE).let { if (it >= 0) 0 else -1 })
        assertTrue(near.size == 3)
        assertEquals(-1, JoiHandjobs.giverRole(emptyList(), b))
    }

    // ---- the receiver's place and her turn

    @Test fun theReceiverIsPlacedInTheGiversFrameAndSheTurnsLittle() {
        // the giver faces +x (yaw 0), the receiver is 0.6 ahead: straight in front
        val (f, l) = JoiHandjobs.ghostFrom(0f, 0f, 0f, 0.6f, 0f)
        assertEquals(0.6f, f, 1e-5f); assertEquals(0f, l, 1e-5f)
        assertEquals(0f, JoiHandjobs.ghostAngle(f, l), 1e-4f)
        assertEquals(0f, JoiHandjobs.turn(0f), 1e-4f)
        // the giver faces +y (yaw 90): a receiver at +y is ahead of her
        val (f2, l2) = JoiHandjobs.ghostFrom(0f, 0f, 90f, 0f, 0.6f)
        assertEquals(0.6f, f2, 1e-4f); assertEquals(0f, l2, 1e-4f)
        // a receiver 60 degrees to her left: she turns half of it toward him's way (to the right, so he is nearer the line of sight too)
        assertEquals(-30f, JoiHandjobs.turn(60f), 1e-4f)
        assertEquals(30f, JoiHandjobs.turn(-60f), 1e-4f)
        // never more than the limit, whatever the clip's angle
        assertEquals(-JoiHandjobs.MAX_TURN, JoiHandjobs.turn(170f), 1e-4f)
        for (a in -180..180 step 5) assertTrue(kotlin.math.abs(JoiHandjobs.turn(a.toFloat())) <= JoiHandjobs.MAX_TURN + 1e-4f)
    }

    // ---- weighting, cooldowns, kinds

    private fun share(stage: String, framing: JoiFraming?, n: Int = 4000): Float {
        val c = cat(libraryHandjob, JoiClip.stroke())
        var hj = 0
        repeat(n) { i -> if (JoiMove.HANDJOB in c[JoiAnimationPicker(i.toLong()).pick(c, JoiPacing.energy(stage), 0f, stage, framing)]!!) hj++ }
        return hj / n.toFloat()
    }

    @Test fun aThirdOfTheBuildEdgeAndCountdownPicksAreHandjobsAndFewElsewhere() {
        for (stage in listOf("build", "edge", "countdown")) {
            val s = share(stage, JoiFraming.TORSO)
            assertTrue(s in 0.28f..0.45f, "$stage $s")
            assertEquals(JoiHandjobs.share(stage, JoiFraming.TORSO), s, 0.03f)
        }
        assertTrue(share("warmup", JoiFraming.TORSO) in 0.03f..0.14f, "rarely in the warm-up")
        assertEquals(0f, share("setup", JoiFraming.TORSO))
        assertEquals(0f, share("afterglow", JoiFraming.TORSO))
        assertTrue(share("hold", JoiFraming.TORSO) < 0.2f && share("permit", JoiFraming.TORSO) < 0.25f)
    }

    @Test fun faceFramingNeverGivesOneAndTheOthersShowThem() {
        assertEquals(0f, share("build", JoiFraming.FACE), "the stroke is out of frame in Face")
        assertTrue(share("build", JoiFraming.LEGS) > 0.28f)
        assertTrue(share("build", JoiFraming.TORSO) > 0.28f)
        assertTrue(share("build", null) > 0.28f, "none known: shown")
    }

    @Test fun withoutAStageEveryClipCompetesAsBefore() {
        val c = cat(JoiClip.stroke())
        val p = JoiAnimationPicker(4L)
        assertTrue(p.pick(c, 0.5f, 0f) != null)
    }

    @Test fun aLibraryHandjobHasItsCooldownAndTheStrokeDoesNot() {
        val onlyLib = JoiAnimations.catalogue(listOf(libraryHandjob))
        var first = 0
        for (i in 0 until 200) {
            val p = JoiAnimationPicker(i.toLong())
            if (p.pick(onlyLib, 0.8f, 0f, "edge", JoiFraming.TORSO) != null) {
                first++
                assertNull(p.pick(onlyLib, 0.8f, 20f, "edge", JoiFraming.TORSO), "again within its cooldown")
                assertNull(p.pick(onlyLib, 0.8f, 119f, "edge", JoiFraming.TORSO))
            }
        }
        assertTrue(first > 40)
        val onlyStroke = JoiAnimations.catalogue(listOf(JoiClip.stroke(), JoiClip("d", "Dance")))
        // the stroke may come again after another kind, long before 120 s
        var strokes = 0
        val p = JoiAnimationPicker(9L)
        var now = 0f
        repeat(60) { val g = p.pick(onlyStroke, 0.8f, now, "edge", JoiFraming.TORSO); if (g?.procedural == true) strokes++; now += 12f }
        assertTrue(strokes > 5, "the stroke is not held to the cooldown ($strokes)")
    }

    @Test fun neverTwoHandjobsInARowAndNeverTwoOfAKind() {
        val c = cat(libraryHandjob, JoiClip.stroke())
        for (seed in 0 until 30) {
            val p = JoiAnimationPicker(seed.toLong())
            var prev: Set<JoiMove>? = null
            var now = 0f
            repeat(40) {
                val g = p.pick(c, 0.75f, now, "edge", JoiFraming.TORSO) ?: return@repeat
                val m = c[g]!!
                assertTrue(m != prev, "seed $seed: $m twice running")
                prev = m; now += 15f
            }
        }
    }

    @Test fun aLibraryHandjobIsPreferredToTheProceduralOne() {
        val c = cat(libraryHandjob, JoiClip.stroke())
        var lib = 0; var proc = 0
        repeat(2000) { i ->
            val g = JoiAnimationPicker(i.toLong()).pick(c, 0.8f, 0f, "edge", JoiFraming.TORSO)!!
            if (g.procedural) proc++ else if (g.giver >= 0) lib++
        }
        assertTrue(lib > proc, "library $lib procedural $proc")
        assertTrue(proc > 0, "and the fallback is still used")
    }

    @Test fun withNoLibraryHandjobTheStrokeIsTheOnlyOne() {
        val c = cat(JoiClip.stroke())
        val got = (0 until 400).mapNotNull { JoiAnimationPicker(it.toLong()).pick(c, 0.8f, 0f, "edge", JoiFraming.TORSO) }
        assertTrue(got.any { it.procedural })
        assertFalse(got.any { it.giver >= 0 })
    }

    @Test fun aHandjobRunsAsLongAsItsStageButNotForever() {
        assertEquals(8f, JoiHandjobs.runSeconds(2f))
        assertEquals(30f, JoiHandjobs.runSeconds(30f))
        assertEquals(JoiHandjobs.MAX_RUN, JoiHandjobs.runSeconds(500f))
    }

    // ---- the mode: stage time left, holding the solos, her words

    private fun mode(seed: Long = 5L) = JoiMode({ _, _ -> listOf("setup", "warmup", "build", "edge", "hold", "countdown", "permit", "afterglow") },
        { _, _ -> 3 to 1000 }, JoiPacing.DEFAULT_PACES, seed)

    @Test fun aSoloIsDueWithTheStageTimeLeftAndAHandjobHoldsTheNextOnes() {
        val m = mode()
        m.enter("a", false, JoiPrior(false), true)
        var due: JoiEvent.AnimationDue? = null
        var t = 0f
        while (due == null && t < 200f) {
            m.update(0.1f); t += 0.1f
            for (e in m.drain()) { if (e is JoiEvent.NeedLine) m.offerLine(e.serial, "Mm."); if (e is JoiEvent.AnimationDue) due = e }
        }
        assertTrue(due != null && due.stageLeft > 0f && due.stageLeft < 60f, "left ${due?.stageLeft}")
        m.holdSolos(60f)
        var more = 0
        repeat(300) { m.update(0.1f); for (e in m.drain()) { if (e is JoiEvent.NeedLine) m.offerLine(e.serial, "Mm."); if (e is JoiEvent.AnimationDue) more++ } }
        assertEquals(0, more, "held while the handjob runs")
        m.holdSolos(0f)
    }

    @Test fun herShownLinesSlowTheStrokeAndAStageChangeResetsIt() {
        val m = mode()
        m.enter("a", false, JoiPrior(false), true)
        m.stroke.start("setup")
        var t = 0f
        var said = false
        while (t < 120f && !said) {
            m.update(0.1f); t += 0.1f
            for (e in m.drain()) if (e is JoiEvent.NeedLine) m.offerLine(e.serial, "Slower now, just for me.")
            if (m.subtitles.text != null) said = true
        }
        m.update(0.1f)
        assertTrue(said)
        assertEquals(HandStrokeTempo.forStage(m.stage!!) * 0.7f, m.stroke.target, 1e-4f, "the shown line slowed it")
        m.skip()
        assertEquals(HandStrokeTempo.forStage(m.stage!!), m.stroke.target, 1e-4f, "a new stage has its own")
        assertEquals(null, mode().also { it.enter("x", true, JoiPrior(false), true) }.action(), "no stroke: nothing to match")
    }

    @Test fun theDragonNeverStrokes() {
        val m = mode()
        m.enter("dragon", true, JoiPrior(false), true)
        m.stroke.start("build"); repeat(200) { m.stroke.advance(0.05f) }
        assertNull(m.action(), "her lines never name a hand")
    }

    @Test fun leavingStopsTheStroke() {
        val m = mode()
        m.enter("a", false, JoiPrior(false), true)
        m.stroke.start("build")
        m.leave()
        assertFalse(m.stroke.active)
    }

    // ---- the prompt

    @Test fun theLinesAreToldWhatHerHandDoes() {
        val p = JoiPersona("Anarcis", listOf("human"))
        val slow = JoiPrompt.user(JoiLineRequest(p, "build", Band.INTIMATE, action = "your hand is stroking them, slowly"))
        assertTrue(slow.contains("your hand is stroking them, slowly"))
        assertTrue(slow.contains("match"))
        val none = JoiPrompt.user(JoiLineRequest(p, "build", Band.INTIMATE))
        assertFalse(none.contains("your hand"))
        val dragon = JoiPrompt.user(JoiLineRequest(JoiPersona("Sindragosa", dragon = true), "build", Band.INTIMATE, action = "your hand is stroking them, slowly"))
        assertFalse(dragon.contains("your hand"), "the dragon has no hands to match")
        // (the prompt never names the world outside the room)
        assertFalse(JoiPrompt.breaksWorld(slow.replace("You already said", "")), slow)
    }
}
