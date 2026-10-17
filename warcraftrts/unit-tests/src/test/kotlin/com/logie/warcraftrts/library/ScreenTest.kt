package com.logie.warcraftrts.library

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenTest {
    @Test
    fun `the Fold's inside screen at its own pixels`() {
        assertEquals(2184 to 1968, Screen.resolutionFor(1968, 2184, null))
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
