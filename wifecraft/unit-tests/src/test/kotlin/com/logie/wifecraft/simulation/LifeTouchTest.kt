package com.logie.wifecraft.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LifeTouchTest {
    @Test fun everyReactionKindHasAnEngineEvent() {
        for (k in ReactionKind.entries) assertTrue(LifeTouch.event(k) in 1..5, "$k")
        assertEquals(LifeTouch.FLINCH, LifeTouch.event(ReactionKind.FLINCH))
        assertEquals(LifeTouch.GIGGLE, LifeTouch.event(ReactionKind.BLUSH))
        assertEquals(LifeTouch.LEAN_IN, LifeTouch.event(ReactionKind.LEAN_IN))
    }

    @Test fun everyRegionButNoneNamesABoneTheEngineKnows() {
        val known = setOf("Head", "Neck1", "L_Clavicle", "Chest", "Spine1", "Pelvis", "L_Thigh", "L_Calf", "L_Foot", "L_Hand", "L_UpperArm")
        assertEquals("", LifeTouch.bone(TouchRegion.NONE))
        for (r in TouchRegion.entries.filter { it != TouchRegion.NONE }) assertTrue(LifeTouch.bone(r) in known, "$r")
    }

    @Test fun strengthIsBoundedAndFollowsTheKindAndTheTenderness() {
        for (r in TouchRegion.entries) for (k in TouchKind.entries) assertTrue(LifeTouch.strength(r, k) in 0.1f..1f, "$r $k")
        assertTrue(LifeTouch.strength(TouchRegion.CHEST, TouchKind.TAP) > LifeTouch.strength(TouchRegion.HEAD, TouchKind.TAP))
        assertTrue(LifeTouch.strength(TouchRegion.THIGH, TouchKind.PINCH) > LifeTouch.strength(TouchRegion.THIGH, TouchKind.TAP))
        assertTrue(LifeTouch.strength(TouchRegion.THIGH, TouchKind.STROKE) < LifeTouch.strength(TouchRegion.THIGH, TouchKind.TAP))
    }
}
