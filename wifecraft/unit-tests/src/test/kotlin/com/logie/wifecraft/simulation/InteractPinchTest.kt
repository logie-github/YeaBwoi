package com.logie.wifecraft.simulation

import com.logie.wifecraft.dialogue.DirtyLevel
import com.logie.wifecraft.dialogue.Disposition
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pinch's consent per finger, her reaction (rate-limited), the haptics, the contact API of SceneView's kin. */
class InteractPinchTest {
    private val bold = InteractPersona(Disposition(boldness = 0.6f, warmth = 0.3f), affinity = 0.6f)
    private val shy = InteractPersona(Disposition(caution = 0.6f, warmth = 0.1f), affinity = 0.1f)
    private fun hit(r: TouchRegion) = TouchHit(r, GarmentSlot.BARE, 0, true, 0.5f)
    private val wontHands = ConsentPolicy(mapOf("a" to NpcLimits().withCategory(ActCategories.HANDS, Stance.WONT)))
    private val willAll = ConsentPolicy(mapOf("a" to NpcLimits().withCategory(ActCategories.HANDS, Stance.WILL).withCategory(ActCategories.FRIENDLY, Stance.WILL)))

    @Test fun eachFingerIsJudgedByItsOwnPart() {
        // the thigh is a "grope" (Hands): a Won't there declines that finger; the arm (a pat, Friendly) goes on
        val gate = PinchGate(wontHands, "a", { bold })
        val thigh = gate.finger(1, hit(TouchRegion.THIGH), Random(1))
        val arm = gate.finger(2, hit(TouchRegion.ARM), Random(1))
        assertFalse(thigh.allowed); assertEquals(Verdict.DECLINE, thigh.verdict)
        assertTrue(arm.allowed)
    }

    @Test fun aDeclinedFingerAnswersOnceThenStaysQuiet() {
        val gate = PinchGate(wontHands, "a", { bold })
        val first = gate.finger(1, hit(TouchRegion.THIGH), Random(2))
        val again = gate.finger(1, hit(TouchRegion.THIGH), Random(2))
        assertNotNull(first.reaction); assertTrue(first.reaction!!.declined)
        assertNull(again.reaction); assertFalse(again.allowed)
        // another finger on the same part answers for itself
        assertNotNull(gate.finger(2, hit(TouchRegion.THIGH), Random(2)).reaction)
        // a finger that moved onto an allowed part presses, and a later decline answers afresh
        assertTrue(gate.finger(1, hit(TouchRegion.ARM), Random(2)).allowed)
        assertNotNull(gate.finger(1, hit(TouchRegion.THIGH), Random(2)).reaction)
    }

    @Test fun noHitMeansNoContact() {
        val gate = PinchGate(null, "a", { bold })
        assertFalse(gate.finger(1, null, Random(1)).allowed)
        assertFalse(gate.finger(1, hit(TouchRegion.NONE), Random(1)).allowed)
    }

    @Test fun theGroinFollowsIntimateTouch() {
        val wont = ConsentPolicy(mapOf("a" to NpcLimits().withCategory(ActCategories.INTIMATE, Stance.WONT)))
        val will = ConsentPolicy(mapOf("a" to NpcLimits().withCategory(ActCategories.INTIMATE, Stance.WILL)))
        assertFalse(PinchGate(wont, "a", { bold }).finger(1, hit(TouchRegion.GROIN), Random(1)).allowed)
        assertTrue(PinchGate(will, "a", { shy }).finger(1, hit(TouchRegion.GROIN), Random(1)).allowed)
        // a Won't on Intimate touch does not touch the thigh
        assertTrue(PinchGate(wont, "a", { bold }).finger(1, hit(TouchRegion.THIGH), Random(1)).allowed)
    }

    @Test fun aPinchIsMoreThanATouchInTheSamePlace() {
        val p = InteractPersona(Disposition(), 0f, 0.3f)
        assertTrue(TouchConsent.resistance(TouchRegion.THIGH, TouchKind.PINCH, p) > TouchConsent.resistance(TouchRegion.THIGH, TouchKind.TAP, p))
    }

    @Test fun theReactionToAPinchIsRateLimitedAndOncePerPair() {
        val gate = PinchGate(willAll, "a", { bold })
        val r = gate.pinch(1, 2, hit(TouchRegion.THIGH), hit(TouchRegion.THIGH), 1_000L, Random(3))
        assertNotNull(r); assertEquals(Verdict.ALLOW, r!!.verdict)
        assertNull(gate.pinch(1, 2, hit(TouchRegion.THIGH), hit(TouchRegion.THIGH), 9_000L, Random(3)), "the same pair is answered once however long it is held")
        assertNull(gate.pinch(3, 4, hit(TouchRegion.THIGH), hit(TouchRegion.THIGH), 1_500L, Random(3)), "a new pair 0.5 s later: too soon")
        assertNotNull(gate.pinch(5, 6, hit(TouchRegion.BELLY), hit(TouchRegion.BELLY), 4_000L, Random(3)), "after the gap she answers again")
        gate.end(5)
        assertNotNull(gate.pinch(5, 6, hit(TouchRegion.BELLY), hit(TouchRegion.BELLY), 9_000L, Random(3)), "a pinch that ended can be answered again")
    }

    @Test fun reactionLimiterAllowsOnePerGap() {
        val l = ReactionLimiter(1_000)
        assertTrue(l.allow(0)); assertFalse(l.allow(999)); assertTrue(l.allow(1_000)); assertFalse(l.allow(1_500))
        l.reset(); assertTrue(l.allow(1_501))
    }

    @Test fun aPinchReactionIsItsOwnByRegionAndNature() {
        for (r in listOf(TouchRegion.THIGH, TouchRegion.GROIN, TouchRegion.ARM, TouchRegion.NECK)) {
            val kinds = PinchReactions.allowedKinds(r).map { it.first }
            assertTrue(kinds.isNotEmpty() && kinds.all { it in ReactionKind.entries })
        }
        assertNotEquals(PinchReactions.allowedKinds(TouchRegion.THIGH), InteractReactions.allowedKinds(TouchRegion.THIGH), "its own table")
        assertTrue(PinchReactions.allowedKinds(TouchRegion.THIGH).any { it.first == ReactionKind.FLINCH })
        val lines = (0 until 30).map { PinchReactions.line(shy, Random(it)) }.toSet()
        assertTrue(lines.isNotEmpty() && lines.all { it.isNotBlank() })
        val chosen = (0 until 40).map { InteractReactions.choose(hit(TouchRegion.THIGH), TouchKind.PINCH, Verdict.ALLOW, bold, Random(it)).kind }.toSet()
        assertTrue(chosen.all { k -> PinchReactions.allowedKinds(TouchRegion.THIGH).any { it.first == k } }, "$chosen")
        assertEquals(PinchHaptics.start(TouchRegion.THIGH), InteractReactions.haptic(TouchRegion.THIGH, Verdict.ALLOW, TouchKind.PINCH))
    }

    @Test fun theHapticStartsWithAShortPatternAndHoldsGentlyWhileTheFingersClose() {
        val h = PinchHaptics()
        val start = h.begin(TouchRegion.THIGH, 200f)
        assertTrue(start.timings.sum() < 120 && start.amplitudes.all { it in 0..255 })
        assertNull(h.hold(200f, 1_000), "still: nothing")
        val a = h.hold(150f, 1_000)
        assertNotNull(a); assertEquals(1, a!!.timings.size)
        assertNull(h.hold(120f, 1_030), "too soon after the last tick")
        val b = h.hold(60f, 1_100)
        assertNotNull(b); assertTrue(b!!.amplitudes[0] > a.amplitudes[0], "stronger the closer they are")
        assertNull(h.hold(90f, 1_300), "moving apart: nothing")
        assertNull(h.hold(Float.NaN, 1_400), "hostile input")
    }

    @Test fun theHapticIsStrongerOnTenderParts() {
        assertTrue(PinchHaptics.start(TouchRegion.GROIN).amplitudes[0] > PinchHaptics.start(TouchRegion.ARM).amplitudes[0])
    }

    @Test fun consentPerPointerThroughTheModeToo() {
        val m = InteractMode(3)
        m.enter("a", InteractPrior(false, "x", 0))
        val r = m.touch(hit(TouchRegion.THIGH), TouchKind.PINCH, wontHands, bold)!!
        assertTrue(r.declined)
        assertNotNull(m.touch(hit(TouchRegion.ARM), TouchKind.PINCH, willAll, bold))
        m.setTool(InteractTool.LOOK)
        assertNull(m.touch(hit(TouchRegion.ARM), TouchKind.PINCH, willAll, bold), "the Look tool touches nothing")
    }
}
