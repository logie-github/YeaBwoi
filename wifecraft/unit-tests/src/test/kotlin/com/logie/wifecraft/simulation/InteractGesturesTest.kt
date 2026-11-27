package com.logie.wifecraft.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Interact mode's finger rules (InteractGestures): touch, pinch, camera, Look, stocking precedence, cancel, mode end. */
class InteractGesturesTest {
    private fun InteractGestures.body(id: Long, actor: String = "a"): List<InteractGestures.Change> = classified(id, FingerTarget.BODY, actor)
    private fun InteractGestures.land(id: Long, target: FingerTarget, actor: String = "a"): List<InteractGestures.Change> { down(id, 10f * id, 10f); return classified(id, target, actor) }

    @Test fun oneFingerOnHerBodyIsATouch() {
        val g = InteractGestures()
        assertEquals(FingerRole.PENDING, g.down(1, 0f, 0f).let { g.roleOf(1) })
        val ch = g.body(1)
        assertEquals(FingerRole.TOUCH, g.roleOf(1)); assertEquals(0, g.finger(1)!!.slot)
        assertEquals(listOf(FingerRole.TOUCH), ch.map { it.to })
        val up = g.up(1)
        assertEquals(FingerRole.TOUCH, up.single().from); assertEquals(0, up.single().slot)
    }

    @Test fun oneFingerOnTheBackgroundIsTheCamera() {
        val g = InteractGestures()
        g.land(1, FingerTarget.BACKGROUND)
        assertEquals(FingerRole.CAMERA, g.roleOf(1)); assertEquals(CameraGesture.ORBIT, g.cameraGesture())
    }

    @Test fun twoFingersOnTheSameBodyArePinchContacts() {
        val g = InteractGestures()
        g.land(1, FingerTarget.BODY); g.land(2, FingerTarget.BODY)
        assertEquals(FingerRole.PINCH, g.roleOf(1)); assertEquals(FingerRole.PINCH, g.roleOf(2))
        assertEquals(setOf(0, 1), setOf(g.finger(1)!!.slot, g.finger(2)!!.slot))
        assertEquals(CameraGesture.NONE, g.cameraGesture())
    }

    @Test fun aSecondFingerWaitsForItsPickWhileTheFirstGoesOnTouching() {
        val g = InteractGestures()
        g.land(1, FingerTarget.BODY)
        g.down(2, 50f, 50f)
        assertEquals(FingerRole.TOUCH, g.roleOf(1)); assertEquals(FingerRole.PENDING, g.roleOf(2))
        g.body(2)
        assertEquals(FingerRole.PINCH, g.roleOf(1))
    }

    @Test fun twoFingersOnTwoDifferentPeopleAreTwoTouches() {
        val g = InteractGestures()
        g.land(1, FingerTarget.BODY, "a"); g.land(2, FingerTarget.BODY, "b")
        assertEquals(FingerRole.TOUCH, g.roleOf(1)); assertEquals(FingerRole.TOUCH, g.roleOf(2))
        assertEquals(setOf(0, 1), setOf(g.finger(1)!!.slot, g.finger(2)!!.slot))
        g.up(1)
        assertEquals(FingerRole.TOUCH, g.roleOf(2), "the other goes on touching on its own")
    }

    @Test fun twoFingersWithOneOnTheBackgroundZoomTheCameraAndLetTheTouchGo() {
        val g = InteractGestures()
        g.land(1, FingerTarget.BODY)
        val ch = g.land(2, FingerTarget.BACKGROUND)
        assertEquals(FingerRole.CAMERA, g.roleOf(1)); assertEquals(FingerRole.CAMERA, g.roleOf(2))
        assertEquals(CameraGesture.ZOOM, g.cameraGesture())
        assertTrue(ch.any { it.id == 1L && it.from == FingerRole.TOUCH && it.to == FingerRole.CAMERA && it.slot == 0 }, "the first finger's contact is let go: $ch")
    }

    @Test fun threeOrMoreFingersPanTheCameraWhateverTheyLandOn() {
        val g = InteractGestures()
        g.land(1, FingerTarget.BODY); g.land(2, FingerTarget.BODY); val ch = g.down(3, 30f, 10f) + g.classified(3, FingerTarget.BODY, "a")
        assertEquals(CameraGesture.PAN, g.cameraGesture())
        assertEquals(3, g.withRole(FingerRole.CAMERA).size)
        assertTrue(ch.count { it.from == FingerRole.PINCH && it.to == FingerRole.CAMERA } == 2, "the pinch is let go: $ch")
        // back to two fingers: still the camera (a latch until all are up)
        g.up(3)
        assertEquals(CameraGesture.ZOOM, g.cameraGesture()); assertEquals(FingerRole.CAMERA, g.roleOf(1))
        g.up(2); assertEquals(FingerRole.CAMERA, g.roleOf(1), "the last finger of a camera gesture is not a touch")
        g.up(1)
        g.land(4, FingerTarget.BODY)
        assertEquals(FingerRole.TOUCH, g.roleOf(4), "a fresh gesture starts clean")
    }

    @Test fun theLookToolMakesEveryFingerTheCamera() {
        val g = InteractGestures()
        g.setLook(true)
        g.down(1, 0f, 0f); g.down(2, 5f, 5f)
        assertEquals(FingerRole.CAMERA, g.roleOf(1)); assertEquals(FingerRole.CAMERA, g.roleOf(2))
        g.classified(1, FingerTarget.BODY, "a")
        assertEquals(FingerRole.CAMERA, g.roleOf(1))
        assertEquals(CameraGesture.ZOOM, g.cameraGesture())
    }

    @Test fun switchingToLookMidGestureLetsTheContactsGo() {
        val g = InteractGestures()
        g.land(1, FingerTarget.BODY); g.land(2, FingerTarget.BODY)
        val ch = g.setLook(true)
        assertEquals(2, ch.count { it.from == FingerRole.PINCH && it.to == FingerRole.CAMERA })
        assertTrue(g.withRole(FingerRole.PINCH).isEmpty())
    }

    @Test fun aStockingGrabTakesTheFirstFingerAloneAndIgnoresALaterOne() {
        val g = InteractGestures()
        g.land(1, FingerTarget.STOCKING)
        assertEquals(FingerRole.STOCKING, g.roleOf(1))
        g.land(2, FingerTarget.BODY)
        assertEquals(FingerRole.STOCKING, g.roleOf(1)); assertEquals(FingerRole.IGNORED, g.roleOf(2))
        assertNull(g.finger(2)!!.slot.takeIf { it >= 0 }, "no contact for the ignored finger")
        g.land(3, FingerTarget.BACKGROUND)
        assertEquals(FingerRole.STOCKING, g.roleOf(1)); assertEquals(FingerRole.IGNORED, g.roleOf(3)); assertEquals(CameraGesture.NONE, g.cameraGesture())
        g.up(1)
        assertEquals(FingerRole.IGNORED, g.roleOf(2), "the rest stay ignored until all are up")
        g.up(2); g.up(3)
        g.land(9, FingerTarget.BODY)
        assertEquals(FingerRole.TOUCH, g.roleOf(9))
    }

    @Test fun aStockingFingerThatLandsSecondIsBodySoItCanPinch() {
        val g = InteractGestures()
        g.land(1, FingerTarget.BODY); g.land(2, FingerTarget.STOCKING)
        assertEquals(FingerRole.PINCH, g.roleOf(1)); assertEquals(FingerRole.PINCH, g.roleOf(2))
    }

    @Test fun aPinchThatLosesAFingerLeavesTheOtherIgnoredNotATouch() {
        val g = InteractGestures()
        g.land(1, FingerTarget.BODY); g.land(2, FingerTarget.BODY)
        val up = g.up(2)
        assertEquals(FingerRole.PINCH, up.first().from)
        assertEquals(FingerRole.IGNORED, g.roleOf(1))
        assertTrue(up.any { it.id == 1L && it.from == FingerRole.PINCH && it.to == FingerRole.IGNORED && it.slot >= 0 }, "its contact is let go too: $up")
    }

    @Test fun aCancelledPointerLetsItsContactGoAndNeverLeavesOneStuck() {
        val g = InteractGestures()
        g.land(1, FingerTarget.BODY); g.land(2, FingerTarget.BODY)
        val live = HashSet(g.fingers().map { it.slot })
        val ch = g.cancel(1) + g.cancel(2)
        val released = ch.filter { it.to == FingerRole.IGNORED && it.slot >= 0 }.map { it.slot }.toSet()
        assertTrue(released.containsAll(live), "released $released of $live")
        assertEquals(0, g.count)
        assertTrue(g.cancel(77).isEmpty(), "an unknown pointer does nothing")
    }

    @Test fun modeEndReleasesEveryPointer() {
        val g = InteractGestures()
        g.land(1, FingerTarget.BODY, "a"); g.land(2, FingerTarget.BODY, "b")
        val ch = g.cancelAll()
        assertEquals(setOf(0, 1), ch.filter { it.slot >= 0 }.map { it.slot }.toSet())
        assertEquals(0, g.count)
        g.land(1, FingerTarget.BODY)
        assertEquals(FingerRole.TOUCH, g.roleOf(1))
    }

    @Test fun onlyFiveFingersGetAContactSlot() {
        val g = InteractGestures(maxContacts = 2)
        g.land(1, FingerTarget.BODY, "a"); g.land(2, FingerTarget.BODY, "b")
        assertEquals(FingerRole.TOUCH, g.roleOf(2))
        assertEquals(5, InteractGestures.MAX_CONTACTS)
    }

    @Test fun aBodyFingerThatWasPickedLateAfterTheFingerLiftedChangesNothing() {
        val g = InteractGestures()
        g.down(1, 0f, 0f); g.up(1)
        assertTrue(g.classified(1, FingerTarget.BODY, "a").isEmpty())
        assertEquals(0, g.count)
    }
}
