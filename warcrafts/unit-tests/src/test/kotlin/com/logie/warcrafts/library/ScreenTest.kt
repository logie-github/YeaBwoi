package com.logie.warcrafts.library

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenTest {
    @Test
    fun `a 4 to 3 screen is filled exactly`() {
        assertEquals(2208 to 1656, Screen.resolutionFor(1656, 2208, null))
        assertEquals(1440 to 1080, Screen.resolutionFor(2208, 1656, 1080))
        assertEquals(960 to 720, Screen.resolutionFor(2208, 1656, 720))
    }

    @Test
    fun `a screen narrower than 4 to 3 gets the game at 4 to 3, not squeezed`() {
        assertEquals(2184 to 1638, Screen.resolutionFor(1968, 2184, null))
        assertEquals(1440 to 1080, Screen.resolutionFor(2184, 1968, 1080))
    }

    @Test
    fun `the Fold's outside screen at 1080 lines is its own pixels`() {
        assertEquals(2520 to 1080, Screen.resolutionFor(1080, 2520, 1080))
    }

    @Test
    fun `720 lines at a phone's shape, even and landscape`() {
        assertEquals(1600 to 720, Screen.resolutionFor(1080, 2400, 720))
    }

    @Test
    fun `never below 800x600`() {
        assertEquals(800 to 600, Screen.resolution(480, 480, 300))
    }
}
