package com.logie.wifecraft.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The scene's flow (simulation/SceneFlow.kt): lead-in, undressing a piece at a time, stages, afterglow; Continue and Auto flow. */
class SceneFlowTest {
    private val wornA = listOf(UndressOrder.CHEST, UndressOrder.FEET, UndressOrder.BACK, UndressOrder.LEGS, UndressOrder.WAIST, UndressOrder.HEAD)
    private val wornB = listOf(UndressOrder.SHIRT, UndressOrder.LEGS, UndressOrder.FEET)

    private fun run(flow: SceneFlow, seconds: Float, dt: Float = 1f / 30f): List<FlowStep> {
        val out = ArrayList<FlowStep>()
        var t = 0f
        while (t < seconds) { out += flow.tick(dt); t += dt }
        return out
    }

    @Test
    fun undressOrderIsNaturalAndKeepsWhatIsKept() {
        assertEquals(listOf(UndressOrder.BACK, UndressOrder.HEAD, UndressOrder.FEET, UndressOrder.WAIST, UndressOrder.LEGS, UndressOrder.CHEST),
            UndressOrder.of(wornA))
        assertEquals(listOf(UndressOrder.BACK, UndressOrder.FEET, UndressOrder.WAIST, UndressOrder.LEGS, UndressOrder.CHEST),
            UndressOrder.of(wornA, keep = setOf(UndressOrder.HEAD)))
        val both = UndressOrder.interleave("a", listOf(1, 2, 3), "b", listOf(9))
        assertEquals(listOf("a" to 1, "b" to 9, "a" to 2, "a" to 3), both)
        assertTrue(UndressOrder.ORDER.all { it in UndressOrder.NAMES })
    }

    @Test
    fun continueWalksThePhases() {
        val f = SceneFlow("a", "b", wornA, wornB)
        assertEquals(FlowPhase.NONE, f.phase)
        assertEquals(listOf<FlowStep>(FlowStep.LeadIn("a", "b", SceneFlow.EMOTE_KISS)), f.start())
        assertEquals(FlowPhase.LEAD_IN, f.phase)
        // without Auto flow the lead-in waits for Continue
        assertTrue(run(f, 60f).isEmpty())
        assertEquals(FlowPhase.LEAD_IN, f.phase)
        f.proceed()
        assertEquals(FlowPhase.UNDRESS, f.phase)
        // a piece about every PIECE_S, the first at once, alternating between them
        val first = run(f, 0.05f)
        assertEquals(listOf<FlowStep>(FlowStep.TakeOff("a", UndressOrder.BACK)), first)
        val more = run(f, SceneFlow.PIECE_S * 2.5f).filterIsInstance<FlowStep.TakeOff>()
        assertEquals(listOf(FlowStep.TakeOff("b", UndressOrder.FEET), FlowStep.TakeOff("a", UndressOrder.HEAD)), more)
        // Continue hurries the rest off and begins the stages
        val rest = f.proceed()
        assertEquals(wornA.size + wornB.size - 3, rest.count { it is FlowStep.TakeOff })
        assertEquals(FlowStep.Stages("a", "b"), rest.last())
        assertEquals(0, f.remaining)
        assertEquals(FlowPhase.STAGE, f.phase)
        f.faster(); f.faster(); f.faster()
        assertEquals(SceneFlow.TEMPO_MAX, f.tempo)
        assertEquals(listOf<FlowStep>(FlowStep.Afterglow("a", "b", SceneFlow.EMOTE_LIE)), f.proceed())
        assertEquals(listOf<FlowStep>(FlowStep.Rest("a", "b")), f.proceed())
        assertEquals(FlowPhase.DONE, f.phase)
        assertTrue(f.proceed().isEmpty())
    }

    @Test
    fun autoFlowMovesOnByItself() {
        val f = SceneFlow("a", "b", wornA, wornB, auto = true)
        f.start()
        val steps = run(f, SceneFlow.LEAD_IN_S + (wornA.size + wornB.size) * SceneFlow.PIECE_S + SceneFlow.AFTER_UNDRESS_S + 1f)
        assertEquals(wornA.size + wornB.size, steps.count { it is FlowStep.TakeOff })
        assertTrue(steps.last() is FlowStep.Stages, "${steps.last()}")
        assertEquals(FlowPhase.STAGE, f.phase)
        // the stages hold until their queue has played
        assertTrue(run(f, 300f).isEmpty())
        assertTrue(f.stagesFinished().single() is FlowStep.Afterglow)
        assertTrue(run(f, SceneFlow.AFTERGLOW_S + 1f).single() is FlowStep.Rest)
        assertEquals(FlowPhase.DONE, f.phase)
    }

    @Test
    fun noUndressingOrNothingWornGoesStraightToTheStages() {
        val off = SceneFlow("a", "b", wornA, wornB, undress = false)
        off.start()
        assertEquals(FlowStep.Stages("a", "b"), off.proceed().single())
        val bare = SceneFlow("a", "b", emptyList(), emptyList())
        bare.start()
        assertEquals(FlowPhase.STAGE, bare.proceed().let { bare.phase })
    }

    @Test
    fun endDressesAgainWhoeverUndressed() {
        val f = SceneFlow("a", "b", wornA, wornB, keepB = wornB)
        f.start(); f.proceed()
        run(f, 2f)
        val ended = f.end()
        assertEquals(listOf<FlowStep>(FlowStep.Redress("a")), ended)
        assertEquals(FlowPhase.NONE, f.phase)
        assertTrue(f.end().isEmpty())
    }

    @Test
    fun intensityFollowsTheFlow() {
        val f = SceneFlow("a", "b", wornA, wornB)
        f.start()
        val lead = IntensityModel.of(MomentProgress.IDLE, f.phase, f.tempo)
        f.proceed()
        val undress = IntensityModel.of(MomentProgress.IDLE, f.phase, f.tempo)
        f.proceed()
        val stage = IntensityModel.of(MomentProgress(true, true, 4), f.phase, f.tempo)
        f.proceed()
        val after = IntensityModel.of(MomentProgress(true, true, 4), f.phase, f.tempo)
        assertTrue(lead < undress && undress < stage && after < lead, "$lead $undress $stage $after")
    }
}
