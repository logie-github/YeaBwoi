package com.logie.wifecraft.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BuildEntryTest {
    @Test
    fun theMenuAlwaysOffersBuildAndSaysWhichWay() {
        assertTrue(BuildEntry.menuShown())
        assertEquals("Build mode", BuildEntry.menuLabel(false))
        assertNotEquals(BuildEntry.menuLabel(false), BuildEntry.menuLabel(true))
        assertNotEquals(BuildEntry.menuDetail(false), BuildEntry.menuDetail(true))
    }

    @Test
    fun theChipIsOfferedExceptInMoveMode() {
        assertTrue(BuildEntry.chipShown(mapperOn = false))
        assertFalse(BuildEntry.chipShown(mapperOn = true))
    }

    @Test
    fun theHintShowsOnceInAFreshScene() {
        assertTrue(BuildEntry.hintShown(seen = false, buildOn = false, hudHidden = false, revealed = true))
        assertFalse(BuildEntry.hintShown(seen = true, buildOn = false, hudHidden = false, revealed = true))
        assertFalse(BuildEntry.hintShown(seen = false, buildOn = true, hudHidden = false, revealed = true))
        assertFalse(BuildEntry.hintShown(seen = false, buildOn = false, hudHidden = true, revealed = true))
        assertFalse(BuildEntry.hintShown(seen = false, buildOn = false, hudHidden = false, revealed = false))
    }

    @Test
    fun enteringEndsCinematic() {
        assertFalse(BuildEntry.cinematicAfterEnter(true))
        assertFalse(BuildEntry.cinematicAfterEnter(false))
    }
}
