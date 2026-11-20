package com.logie.wifecraft.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cinematic mode's music level (Soundscape.kt's CinematicMusic): eased down to 70% while it runs and back up after. */
class CinematicMusicTest {
    @Test fun easesDownAndBack() {
        val m = CinematicMusic()
        assertEquals(1f, m.level)
        assertFalse(m.step(16))
        m.on = true
        assertTrue(m.moving)
        var ms = 0L
        while (m.step(16)) ms += 16
        assertEquals(CinematicMusic.MUSIC, m.level)
        assertTrue(ms in (CinematicMusic.FADE_MS - 32L)..(CinematicMusic.FADE_MS + 32L), "took $ms ms")
        m.on = false
        repeat(CinematicMusic.FADE_MS / 16 + 2) { m.step(16) }
        assertEquals(1f, m.level)
        assertFalse(m.moving)
    }

    @Test fun halfWayIsHalfWay() {
        val m = CinematicMusic()
        m.on = true
        m.step(CinematicMusic.FADE_MS / 2L)
        assertEquals(0.85f, m.level, 1e-4f)
    }
}
