package com.logie.wifecraft.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CinematicIdleTest {
    private var now = 1_000L
    private val idle = CinematicIdle { now }

    @Test fun hidesAfterFiveSecondsUntouchedInCinematicMode() {
        idle.mode(cinematic = true, buildOn = false)
        now += 4_999
        assertFalse(idle.hidden())
        assertEquals(1L, idle.remainingMs())
        now += 1
        assertTrue(idle.hidden())
        assertEquals(0L, idle.remainingMs())
    }

    @Test fun theRevealingTouchShowsTheInterfaceIsConsumedAndRestartsTheCount() {
        idle.mode(true, false)
        now += 6_000
        assertTrue(idle.touch())      // hidden: this touch only shows it
        assertFalse(idle.hidden())
        now += 4_000
        assertFalse(idle.touch())     // shown: an ordinary touch, restarts the count
        now += 4_999
        assertFalse(idle.hidden())
        now += 1
        assertTrue(idle.hidden())
    }

    @Test fun movingFingersKeepItUp() {
        idle.mode(true, false)
        repeat(20) { now += 1_000; assertFalse(idle.touch()); assertFalse(idle.hidden()) }
    }

    @Test fun neverHidesOutsideCinematicModeOrWhileBuildingOrWithAWindowOpen() {
        idle.mode(false, false)
        now += 60_000
        assertFalse(idle.hidden())
        assertEquals(Long.MAX_VALUE, idle.remainingMs())
        idle.mode(true, true)
        now += 60_000
        assertFalse(idle.hidden())
        idle.mode(true, false, held = true)
        now += 60_000
        assertFalse(idle.hidden())
    }

    @Test fun leavingBuildModeOrClosingAWindowStartsTheCountAfresh() {
        idle.mode(true, true)
        now += 60_000
        idle.mode(true, false)
        assertFalse(idle.hidden())
        now += 4_000
        assertFalse(idle.hidden())
        now += 1_000
        assertTrue(idle.hidden())
        idle.mode(true, true)          // Build mode on while hidden: shown at once
        assertFalse(idle.hidden())
        idle.mode(true, false)
        assertFalse(idle.hidden())     // and counted afresh once left
        now += 5_000
        assertTrue(idle.hidden())
    }

    @Test fun theIdleTimeCanBeChosen() {
        val short = CinematicIdle(1_000L) { now }
        short.mode(true, false)
        now += 1_000
        assertTrue(short.hidden())
    }
}
