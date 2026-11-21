package com.logie.wifecraft.simulation

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/** Options' Cinematic page's Zone mood and Mood boost (CinematicSettings, CinematicLook): defaults, JSON, limits. */
class CinematicZoneMoodTest {
    @Test fun defaults() {
        assertEquals(70, CinematicSettings.DEFAULT.zoneMood)
        assertFalse(CinematicSettings.DEFAULT.moodBoost)
    }

    @Test fun roundTripsAndOldFilesKeepDefaults() {
        val s = CinematicSettings(zoneMood = 35, moodBoost = true)
        val back = CinematicSettings.parse(s.toJson().toString())
        assertEquals(35, back.zoneMood)
        assertTrue(back.moodBoost)
        // a file written before the options: their defaults
        val old = CinematicSettings.parse("""{"lighting": 50}""")
        assertEquals(70, old.zoneMood)
        assertFalse(old.moodBoost)
    }

    @Test fun clamped() {
        assertEquals(100, CinematicSettings.parse("""{"zoneMood": 400}""").zoneMood)
        assertEquals(0, CinematicSettings(zoneMood = -5).clamped().zoneMood)
        val look = CinematicLook()
        look.settings = CinematicSettings(zoneMood = 40, moodBoost = true)
        assertEquals(0.4f, look.zoneMood(), 1e-6f)
        assertTrue(look.moodBoost())
    }
}
