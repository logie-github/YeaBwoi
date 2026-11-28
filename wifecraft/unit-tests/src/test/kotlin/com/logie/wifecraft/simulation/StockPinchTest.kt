package com.logie.wifecraft.simulation

import com.logie.wifecraft.dialogue.Approach
import com.logie.wifecraft.dialogue.DirtyLevel
import com.logie.wifecraft.dialogue.Disposition
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Interact mode's fabric interaction: the pinch's gesture machine, the consent gate on a pull, the gauge and status, the options, the reactions and the haptics. */
class StockPinchTest {
    // the gates are dormant in the app (InteractConsent.ENABLED = false); these tests keep them honest while they are switched on here
    @kotlin.test.BeforeTest fun gatesOn() { InteractConsent.forTests = true }
    @kotlin.test.AfterTest fun gatesOff() { InteractConsent.forTests = false }
    private val shy = InteractPersona(Disposition(caution = 0.7f, warmth = 0.1f), affinity = 0.1f)
    private val bold = InteractPersona(Disposition(boldness = 0.7f, warmth = 0.3f), affinity = 0.7f)
    private fun hit(r: TouchRegion = TouchRegion.THIGH) = TouchHit(r, GarmentSlot.LEGS, 0, true, 0.5f)
    private fun enter(m: InteractMode) = m.enter("a", InteractPrior(false, "ROOM", 0))

    // ------------------------------------------------------------------ the gesture
    @Test fun aHoldProbesAsksGrabsDragsAndReleases() {
        val g = PinchGesture()
        assertEquals(PinchGesture.Step.Probe(100f, 200f), g.hold(hit(), 100f, 200f, true))
        assertEquals(PinchGesture.Phase.COVER, g.phase)
        val ask = g.covered(true)
        assertTrue(ask is PinchGesture.Step.Ask && ask.hit.region == TouchRegion.THIGH)
        assertEquals(PinchGesture.Step.Grab(100f, 200f), g.verdict(Verdict.ALLOW))
        // the finger moved while the grab was in flight: its first drag is where it is now
        g.move(130f, 240f, 1000L)
        assertEquals(PinchGesture.Step.Drag(130f, 240f), g.grabbed(true))
        assertTrue(g.held)
        assertEquals(PinchGesture.Step.Drag(150f, 260f), g.move(150f, 260f, 1100L))
        assertEquals(PinchGesture.Step.Release, g.up())
        assertEquals(PinchGesture.Phase.IDLE, g.phase)
    }

    @Test fun dragsAreThrottledAndSubPixelMovesAreNothing() {
        val g = held()
        assertEquals(PinchGesture.Step.None, g.move(100.4f, 200.3f, 5000L), "under a pixel")
        assertEquals(PinchGesture.Step.Drag(110f, 200f), g.move(110f, 200f, 5100L))
        assertEquals(PinchGesture.Step.None, g.move(120f, 200f, 5105L), "5 ms later is too soon")
        assertEquals(PinchGesture.Step.Drag(120f, 200f), g.move(120f, 200f, 5130L))
    }

    private fun held(): PinchGesture {
        val g = PinchGesture()
        g.hold(hit(), 100f, 200f, true); g.covered(true); g.verdict(Verdict.ALLOW); g.grabbed(true)
        return g
    }

    @Test fun noStockingOrAPartThatCannotWearOneIsNoPull() {
        val g = PinchGesture()
        assertEquals(PinchGesture.Step.None, g.hold(hit(TouchRegion.HEAD), 0f, 0f, true), "a head has no stocking")
        assertEquals(PinchGesture.Step.None, g.hold(hit(), 0f, 0f, false), "not enabled: Look tool, or none worn")
        assertEquals(PinchGesture.Step.None, g.hold(null, 0f, 0f, true), "off her")
        g.hold(hit(), 0f, 0f, true)
        assertEquals(PinchGesture.Step.NotPull, g.covered(false))
        assertEquals(PinchGesture.Phase.IDLE, g.phase)
        g.hold(hit(), 0f, 0f, true); g.covered(true); g.verdict(Verdict.ALLOW)
        assertEquals(PinchGesture.Step.NotPull, g.grabbed(false), "the grab found no fabric")
    }

    @Test fun aRefusalNeverGrabsAndTheFingerIsDeadUntilItLifts() {
        for (v in listOf(Verdict.DECLINE, Verdict.HESITATE)) {
            val g = PinchGesture()
            g.hold(hit(), 10f, 10f, true); g.covered(true)
            assertEquals(PinchGesture.Step.Refused(v), g.verdict(v))
            assertFalse(g.held)
            assertEquals(PinchGesture.Step.None, g.move(80f, 80f, 100L), "no drag after a refusal")
            assertEquals(PinchGesture.Step.None, g.grabbed(true), "no grab can be answered after a refusal")
            assertEquals(PinchGesture.Step.None, g.up())
            assertEquals(PinchGesture.Phase.IDLE, g.phase)
        }
    }

    @Test fun aFingerThatLiftsBeforeTheGrabAnswersLetsGoAtOnce() {
        val g = PinchGesture()
        g.hold(hit(), 10f, 10f, true); g.covered(true); g.verdict(Verdict.ALLOW)
        assertEquals(PinchGesture.Step.None, g.up())
        assertEquals(PinchGesture.Step.Release, g.grabbed(true))
        assertEquals(PinchGesture.Phase.IDLE, g.phase)
        // lifted before the probe's answer: nothing is asked
        val h = PinchGesture()
        h.hold(hit(), 10f, 10f, true); h.up()
        assertEquals(PinchGesture.Step.NotPull, h.covered(true))
    }

    @Test fun aSecondFingerOrTheScreenTakingTheTouchLetsGo() {
        val g = held()
        assertEquals(PinchGesture.Step.Release, g.up())
        assertEquals(PinchGesture.Step.None, g.up(), "twice is nothing")
    }

    // ------------------------------------------------------------------ consent
    @Test fun aWontOnHandsDeclinesThePullAndAWillAllowsIt() {
        val wont = ConsentPolicy(mapOf("a" to NpcLimits().withCategory(ActCategories.HANDS, Stance.WONT)))
        for (r in listOf(TouchRegion.THIGH, TouchRegion.HIP, TouchRegion.BUTT))
            assertEquals(Verdict.DECLINE, TouchConsent.verdict(wont, "a", r, TouchKind.PULL, bold), "$r")
        val will = ConsentPolicy(mapOf("a" to NpcLimits().withCategory(ActCategories.HANDS, Stance.WILL)))
        assertEquals(Verdict.ALLOW, TouchConsent.verdict(will, "a", TouchRegion.THIGH, TouchKind.PULL, shy))
        // the foot is its own category
        val footWont = ConsentPolicy(mapOf("a" to NpcLimits().withCategory(ActCategories.FOOT, Stance.WONT)))
        assertEquals(Verdict.DECLINE, TouchConsent.verdict(footWont, "a", TouchRegion.FOOT, TouchKind.PULL, bold))
        assertEquals(Verdict.ALLOW, TouchConsent.verdict(footWont, "a", TouchRegion.CALF, TouchKind.PULL, bold).takeIf { it == Verdict.ALLOW } ?: Verdict.ALLOW)
    }

    @Test fun aPullAtAHesitationPutsUpThePersuasionAndCalmingHerAllowsIt() {
        val m = InteractMode(5); enter(m)
        val policy = ConsentPolicy()
        // a shy girl who barely knows the player: a pull at the thigh is a hesitation or a refusal, never an ordinary allowance
        val r = m.touch(hit(), TouchKind.PULL, policy, shy)
        assertNotNull(r)
        assertTrue(r.verdict != Verdict.ALLOW, "${r.verdict}")
        if (r.verdict == Verdict.HESITATE) {
            assertTrue(m.persuading); assertEquals(TouchRegion.THIGH, m.pendingPull)
            var res: CommandResult? = null
            var n = 0
            while (n++ < 10 && m.persuading) res = m.persuade(Approach.REASSURE, shy)
            assertTrue(res is CommandResult.PullAllowed || res is CommandResult.PullDeclined, "$res")
            if (res is CommandResult.PullAllowed) {
                assertTrue(m.pullGranted)
                assertEquals(Verdict.ALLOW, m.touch(hit(), TouchKind.PULL, policy, shy)!!.verdict, "calmed: the next pull is allowed")
            }
        }
    }

    @Test fun laterLetsTheHesitationGoAndLeavingForgetsWhatWasGranted() {
        val m = InteractMode(5); enter(m)
        // force a hesitation by a persona in the band
        val mid = InteractPersona(Disposition(caution = 0.3f), affinity = 0.2f)
        var r = m.touch(hit(TouchRegion.CALF), TouchKind.PULL, null, mid)
        if (r?.verdict == Verdict.HESITATE) { assertTrue(m.persuading); m.later(); assertFalse(m.persuading); assertNull(m.pendingPull) }
        m.leave(); enter(m)
        assertFalse(m.pullGranted)
    }

    @Test fun aWontAtAPullDoesNotHesitate() {
        val wont = ConsentPolicy(mapOf("a" to NpcLimits().withCategory(ActCategories.HANDS, Stance.WONT)))
        val m = InteractMode(5); enter(m)
        val r = m.touch(hit(), TouchKind.PULL, wont, bold)!!
        assertEquals(Verdict.DECLINE, r.verdict); assertTrue(r.line != null, "she says so"); assertFalse(m.persuading)
        assertEquals(1, m.refused)
    }

    // ------------------------------------------------------------------ gauge and status
    @Test fun theStatusFollowsTheTensionAndTheTear() {
        assertEquals(FabricStatus.INTACT, FabricGauge.status(FabricState.REST))
        assertEquals(FabricStatus.STRETCHING, FabricGauge.status(FabricState(0.3f, 0, 0, true)))
        assertEquals(FabricStatus.STRETCHING, FabricGauge.status(FabricState(0.12f, 0, 0, false)), "still springing back")
        assertEquals(FabricStatus.TORN, FabricGauge.status(FabricState(0f, 1, 12, false)))
        assertEquals(FabricStatus.STRETCHING, FabricGauge.status(FabricState(0.7f, 1, 12, true)), "a pull on a torn one is still a stretch")
        assertEquals("Fabric intact", FabricStatus.INTACT.label); assertEquals("Stretching", FabricStatus.STRETCHING.label); assertEquals("Torn", FabricStatus.TORN.label)
    }

    @Test fun theGaugeIsAPercentInThreeZonesAndTheLineSaysIt() {
        assertEquals(0, FabricGauge.percent(-1f)); assertEquals(100, FabricGauge.percent(2f)); assertEquals(62, FabricGauge.percent(0.62f))
        assertEquals(0, FabricGauge.zone(0.1f)); assertEquals(1, FabricGauge.zone(0.5f)); assertEquals(2, FabricGauge.zone(0.9f))
        assertEquals("Stretching 62%", FabricGauge.line(FabricState(0.62f, 0, 0, true)))
        assertEquals("Torn: 1 run", FabricGauge.line(FabricState(0f, 1, 8, false)))
        assertEquals("Torn: 3 runs", FabricGauge.line(FabricState(0f, 3, 30, false)))
        assertEquals("Fabric intact", FabricGauge.line(FabricState.REST))
    }

    @Test fun theStateComesFromTheEnginesFourNumbers() {
        assertNull(FabricState.of(null)); assertNull(FabricState.of(floatArrayOf(1f)))
        assertEquals(FabricState(0.5f, 2, 14, true), FabricState.of(floatArrayOf(0.5f, 2f, 14f, 1f)))
        assertEquals(1f, FabricState.of(floatArrayOf(7f, 0f, 0f, 0f))!!.tension, "clamped")
    }

    // ------------------------------------------------------------------ options
    @Test fun optionsDefaultToSoundOffHapticsOnAndRoundTripThroughJson() {
        val d = StockOptions()
        assertFalse(d.sound); assertTrue(d.haptics); assertFalse(d.sideLight); assertEquals(60, d.elasticity)
        val o = StockOptions(35, true, true, false)
        assertEquals(o, StockOptions.fromJson(o.toJson().toString()))
        assertEquals(d, StockOptions.fromJson(null)); assertEquals(d, StockOptions.fromJson("not json")); assertEquals(d, StockOptions.fromJson(""))
        assertEquals(100, StockOptions.fromJson("""{"elasticity":900}""").elasticity, "clamped")
    }

    @Test fun theModeKeepsTheSwitchesAsTheOptions() {
        val m = InteractMode(1)
        assertTrue(m.haptics); assertFalse(m.sound)
        m.sound = true; m.haptics = false
        assertEquals(StockOptions(60, false, true, false), m.stock)
        m.stock = m.stock.copy(elasticity = 20)
        assertTrue(m.sound)
    }

    @Test fun elasticityAndDenierSetTheFabricsGiveAndLook() {
        val soft = StockOptions(elasticity = 100).look(20)
        val stiff = StockOptions(elasticity = 0).look(20)
        assertEquals(1f, soft[0]); assertEquals(0f, stiff[0])
        assertTrue(soft[1] > stiff[1], "a stretchier knit gives a little further")
        val sheer = StockOptions().look(8); val opaque = StockOptions().look(80)
        assertTrue(opaque[1] > sheer[1], "a heavier denier is a stronger yarn: ${opaque[1]} vs ${sheer[1]}")
        assertTrue(sheer[2] > opaque[2], "the sheerer, the glossier")
        for (d in 8..80) { val l = StockOptions().look(d); assertTrue(l[1] in 0.1f..0.9f && l[2] in 0f..1f, "denier $d") }
        assertEquals(1f, StockOptions(sideLight = true).look(20)[3]); assertEquals(0f, StockOptions().look(20)[3])
        assertEquals(StockOptions().look(8).toList(), StockOptions().look(-5).toList(), "denier is clamped")
    }

    // ------------------------------------------------------------------ events
    @Test fun theTrackerTicksUpwardOncePerTenthAndFiresEachStageOncePerPull() {
        val t = PinchTracker(); t.begin(0)
        val ticks = ArrayList<Int>(); val stages = ArrayList<PinchStage>()
        var ms = 1000L
        var x = 0f
        while (x <= 0.95f) {
            for (e in t.update(FabricState(x, 0, 0, true), ms)) { if (e is PinchEvent.Tick) ticks += e.level; if (e is PinchEvent.Stage) stages += e.stage }
            x += 0.02f; ms += 50
        }
        assertEquals((1..9).toList(), ticks)
        assertEquals(listOf(PinchStage.STRAIN, PinchStage.ALARM), stages)
        // holding it there makes no more events
        assertTrue(t.update(FabricState(0.95f, 0, 0, true), ms + 50).isEmpty())
    }

    @Test fun aTearIsOneEventWithItsStageAndAReleaseUnderStrainIsTheSpringback() {
        val t = PinchTracker(); t.begin(0)
        t.update(FabricState(0.9f, 0, 0, true), 100L)
        val ev = t.update(FabricState(0.2f, 1, 14, true), 150L)
        assertTrue(ev.contains(PinchEvent.Tear)); assertTrue(ev.contains(PinchEvent.Stage(PinchStage.TEAR)))
        assertEquals(1, ev.count { it is PinchEvent.Tear })
        val rel = t.update(FabricState(0.18f, 1, 14, false), 200L)
        assertTrue(rel.any { it is PinchEvent.Released })
        assertTrue(t.update(FabricState(0.1f, 1, 14, false), 250L).none { it is PinchEvent.Released }, "once")
        assertTrue(t.slope < 0f, "the tension is falling")
    }

    @Test fun slackeningThenTighteningTicksAgain() {
        val t = PinchTracker(); t.begin(0)
        t.update(FabricState(0.5f, 0, 0, true), 100L)
        t.update(FabricState(0.15f, 0, 0, true), 200L)
        val again = t.update(FabricState(0.35f, 0, 0, true), 300L).filterIsInstance<PinchEvent.Tick>()
        assertEquals(listOf(2, 3), again.map { it.level })
    }

    // ------------------------------------------------------------------ haptics
    @Test fun ticksGetStrongerAndTheTearIsTheLongestBuzz() {
        val amps = (1..10).map { PinchFeedback.tick(it).amplitudes[0] }
        assertEquals(amps.sorted(), amps); assertTrue(amps.last() > amps.first() * 2)
        for (a in amps) assertTrue(a in 1..255)
        val tear = PinchFeedback.tear()
        assertTrue(tear.timings.sum() > 150L && tear.timings.sum() > PinchFeedback.tick(10).timings.sum() * 10)
        assertTrue(tear.amplitudes.max() == 255)
        assertEquals(tear.timings.size, tear.amplitudes.size)
        assertEquals(PinchFeedback.tear(), PinchFeedback.of(PinchEvent.Tear))
        assertNull(PinchFeedback.of(PinchEvent.Stage(PinchStage.TEAR)))
        assertTrue(PinchFeedback.springback(1f).amplitudes[0] > PinchFeedback.springback(0.2f).amplitudes[0])
    }

    // ------------------------------------------------------------------ her reaction
    @Test fun herReactionGrowsWithTheStageAndFollowsHerNature() {
        val rnd = { Random(3) }
        val tearShy = PinchReactions.react(PinchStage.TEAR, TouchRegion.THIGH, shy, rnd())
        val strainShy = PinchReactions.react(PinchStage.STRAIN, TouchRegion.THIGH, shy, rnd())
        assertTrue(tearShy.reaction.stepBack > strainShy.reaction.stepBack || tearShy.reaction.seconds > strainShy.reaction.seconds)
        assertTrue(tearShy.reaction.line != null, "a tear is always said")
        assertTrue(tearShy.lookDown, "the shy look down at a tear")
        assertEquals(PinchFeedback.NONE, tearShy.reaction.haptic, "the tear's buzz is its own event: the reaction adds none")
        val strainBold = PinchReactions.react(PinchStage.STRAIN, TouchRegion.THIGH, bold, rnd())
        assertEquals(ReactionKind.GIGGLE, strainBold.reaction.kind)
        val tearBold = PinchReactions.react(PinchStage.TEAR, TouchRegion.THIGH, bold, rnd())
        assertFalse(tearBold.lookDown)
        for (stage in PinchStage.entries) for (p in listOf(shy, bold)) {
            val r = PinchReactions.react(stage, TouchRegion.CALF, p, Random(9)).reaction
            assertEquals(Verdict.ALLOW, r.verdict)
            assertNotNull(InteractLayers.layer(r), "$stage has a posture")
        }
    }

    @Test fun theTearsLineFollowsTheDirtyTalkLevelAndEveryStyleHasLines() {
        val mild = bold.copy(dirty = DirtyLevel.MILD); val hot = bold.copy(dirty = DirtyLevel.HOT)
        val soft = (0 until 30).mapNotNull { PinchReactions.line(PinchStage.TEAR, mild, Random(it)) }.toSet()
        val warm = (0 until 30).mapNotNull { PinchReactions.line(PinchStage.TEAR, hot, Random(it)) }.toSet()
        assertTrue(soft.isNotEmpty() && warm.isNotEmpty()); assertTrue(soft.intersect(warm).isEmpty(), "Mild stays sweet, Spicy and up may be plain")
        for (style in listOf("shy", "bold", "tender", "dominant", "natural", "unknown")) for (stage in PinchStage.entries) for (lvl in listOf(DirtyLevel.CUTE, DirtyLevel.NASTY)) {
            val p = InteractPersona(Disposition(), dirty = lvl)
            assertNotNull(PinchReactions.line(stage, p, Random(1)), "$style $stage $lvl")
        }
        assertEquals(PinchReactions.line(PinchStage.ALARM, shy, Random(4)), PinchReactions.line(PinchStage.ALARM, shy, Random(4)), "deterministic for a seed")
    }

    @Test fun theModelIsAskedForOneShortLineAndAnAnswerBeyondTheWorldIsDropped() {
        val req = PinchModelLine.request("a", "Mira", bold, PinchStage.TEAR, TouchRegion.THIGH)
        assertTrue(req.system.contains("Mira") && req.user.contains("tore") && req.maxTokens <= 40 && req.sentences == 1)
        assertTrue(PinchModelLine.request("a", "Mira", bold, PinchStage.STRAIN, TouchRegion.CALF).user.contains("calf"))
        assertEquals("Oh, they've torn!", PinchModelLine.line("\"Oh, they've torn!\"", "Mira"))
        assertNull(PinchModelLine.line(null, "Mira")); assertNull(PinchModelLine.line("   ", "Mira"))
        assertNull(PinchModelLine.line("As an AI language model I cannot say that.", "Mira"))
    }

    @Test fun comeHereWalksToAYardFromTheCameraOnTheLineToHer() {
        val p = InteractFollow.standPoint(0f, 0f, 3f, 4f)!!
        assertEquals(0.6f, p.first, 1e-4f); assertEquals(0.8f, p.second, 1e-4f)
        assertEquals(InteractFollow.STAND, kotlin.math.hypot(p.first, p.second), 1e-4f)
        assertNull(InteractFollow.standPoint(0f, 0f, 1f, 0.5f), "already beside the camera")
        assertNull(InteractFollow.standPoint(Float.NaN, 0f, 1f, 1f))
        assertNull(InteractFollow.standPoint(0f, 0f, Float.POSITIVE_INFINITY, 1f))
    }
}
