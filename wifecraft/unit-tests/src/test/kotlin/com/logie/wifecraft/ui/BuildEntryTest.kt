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
    fun buildModeAndCinematicModeRunTogether() {
        // entering Build mode leaves cinematic as it is, and turning cinematic on leaves Build mode as it is
        assertTrue(BuildEntry.cinematicAfterEnter(true))
        assertFalse(BuildEntry.cinematicAfterEnter(false))
        assertTrue(BuildEntry.buildAfterCinematic(true))
        assertFalse(BuildEntry.buildAfterCinematic(false))
    }

    @Test
    fun buildModeTakesTheCameraAndHoldsTheAnimationsInCinematic() {
        assertTrue(BuildEntry.directorCamera(cinematic = true, buildOn = false))
        assertFalse(BuildEntry.directorCamera(cinematic = true, buildOn = true))
        assertFalse(BuildEntry.directorCamera(cinematic = false, buildOn = false))
        assertTrue(BuildEntry.holdAnimations(buildOn = true))
        assertFalse(BuildEntry.holdAnimations(buildOn = false))
    }

    @Test
    fun theBuildButtonSitsRightUnderTheCinematicButtonAndSaysWhenItIsOn() {
        // the Cinematic button is 48 dp tall, centred in a 56 dp top bar: it ends at 4 + 48
        assertEquals(52f, BuildEntry.buttonTop(56f))
        assertEquals("Build", BuildEntry.chipLabel(false))
        assertNotEquals(BuildEntry.chipLabel(false), BuildEntry.chipLabel(true))
        assertTrue(BuildEntry.HINT.contains("Cinematic"))
    }
}
