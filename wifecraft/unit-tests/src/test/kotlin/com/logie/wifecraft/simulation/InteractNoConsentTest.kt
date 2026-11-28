package com.logie.wifecraft.simulation

import com.logie.wifecraft.dialogue.Disposition
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Interact mode and the hidden mode do not judge: no touch, stroke, pinch, pull or command is declined, hesitated at or put to the persuasion chips. */
class InteractNoConsentTest {
    private val shy = InteractPersona(Disposition(caution = 0.9f, warmth = 0.0f), affinity = 0f)
    private fun hit(r: TouchRegion) = TouchHit(r, GarmentSlot.BARE, 0, true, 0.5f)
    private val wontEverything = ConsentPolicy(mapOf("a" to NpcLimits().let { l -> ActCategories.ORDER.fold(l) { acc, c -> acc.withCategory(c, Stance.WONT) } }))

    @Test fun theGateIsOff() {
        assertFalse(InteractConsent.ENABLED)
        assertFalse(InteractConsent.on())
    }

    @Test fun everyRegionAndKindIsAllowedForTheShyestWhateverTheLimitsSay() {
        for (r in TouchRegion.entries) for (k in TouchKind.entries) {
            assertEquals(Verdict.ALLOW, TouchConsent.verdict(wontEverything, "a", r, k, shy), "$r $k")
            assertEquals(Verdict.ALLOW, TouchConsent.verdict(null, "a", r, k, shy), "$r $k without a policy")
        }
    }

    @Test fun touchingTheGroinIsAnsweredByAReactionNotARefusal() {
        val m = InteractMode(5L)
        m.enter("a", InteractPrior(false, "", 0))
        for (i in 0 until 40) {
            val r = assertNotNull(m.touch(hit(TouchRegion.GROIN), if (i % 2 == 0) TouchKind.TAP else TouchKind.PINCH, wontEverything, shy))
            assertEquals(Verdict.ALLOW, r.verdict)
            assertFalse(r.declined)
        }
        assertEquals(0, m.refused)
        assertFalse(m.persuading)
    }

    @Test fun aStrokeNeverStopsAtAPart() {
        val m = InteractMode(7L)
        m.enter("a", InteractPrior(false, "", 0))
        val gate = assertNotNull(m.stroke(wontEverything, shy))
        val rnd = Random(3)
        for (r in listOf(TouchRegion.THIGH, TouchRegion.HIP, TouchRegion.GROIN, TouchRegion.CHEST, TouchRegion.BUTT)) {
            assertTrue(gate.sample(hit(r), rnd).kind != StrokeGate.Step.Kind.STOP, "$r")
        }
        assertNull(gate.stoppedAt)
    }

    @Test fun everyFingerOfAPinchPresses() {
        val gate = PinchGate(wontEverything, "a", { shy })
        for (r in TouchRegion.entries.filter { it != TouchRegion.NONE }) assertTrue(gate.finger(r.ordinal.toLong(), hit(r), Random(1)).allowed, "$r")
    }

    @Test fun everyCommandIsDoneAtOnce() {
        val m = InteractMode(9L)
        m.enter("a", InteractPrior(false, "", 0))
        for (a in InteractAction.catalogue()) {
            val res = m.command(a, wontEverything, shy)
            assertFalse(res is CommandResult.Declined || res is CommandResult.Hesitates, "${a.id}: $res")
            assertFalse(m.persuading)
        }
        assertEquals(0, m.refused)
        assertTrue(m.tell("take off your top", wontEverything, shy) !is CommandResult.Declined)
    }

    @Test fun thePullAtTheStockingsIsNeverPutToThePersuasion() {
        val m = InteractMode(11L)
        m.enter("a", InteractPrior(false, "", 0))
        val r = assertNotNull(m.touch(hit(TouchRegion.THIGH), TouchKind.PULL, wontEverything, shy))
        assertEquals(Verdict.ALLOW, r.verdict)
        assertFalse(m.persuading)
        assertNull(m.pendingPull)
    }
}
