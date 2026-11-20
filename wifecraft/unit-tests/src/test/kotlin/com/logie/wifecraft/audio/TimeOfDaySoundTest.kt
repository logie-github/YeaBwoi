package com.logie.wifecraft.audio

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeOfDaySoundTest {
    private fun book(): SoundBook {
        val dir = System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets"
        return SoundBook.parse(File(dir, "sounds.json").readText())
    }

    @Test fun nightAmbienceFromEightInTheEveningToHalfPastFive() {
        assertFalse(TimeOfDaySound.isNight(12.0))
        assertFalse(TimeOfDaySound.isNight(19.99))
        assertTrue(TimeOfDaySound.isNight(20.0))
        assertTrue(TimeOfDaySound.isNight(2.0))
        assertTrue(TimeOfDaySound.isNight(5.49))
        assertFalse(TimeOfDaySound.isNight(5.5))
        assertTrue(TimeOfDaySound.isNight(-1.0))
        assertTrue(TimeOfDaySound.isNight(26.0))
    }

    @Test fun theEmbassysAmbienceByHourIsItsTwoLoops() {
        val p = book().places.getValue("embassy")
        assertEquals(539127, TimeOfDaySound.ambience(p, 9.0))     // SoundAmbience 569's day kit 17895
        assertEquals(539080, TimeOfDaySound.ambience(p, 23.0))    // its night kit 17896
        assertEquals(539080, TimeOfDaySound.ambience(p, 3.0))
        // a place with only one loop keeps it day and night
        val one = p.copy(ambienceNight = emptyList())
        assertEquals(539127, TimeOfDaySound.ambience(one, 23.0))
        val none = p.copy(ambienceDay = emptyList(), ambienceNight = emptyList())
        assertNull(TimeOfDaySound.ambience(none, 12.0))
        // the music is the same by day and by night (so it plays on while only the ambience crosses)
        assertEquals(MusicPicker.place(p, false)!!.tracks, MusicPicker.place(p, true)!!.tracks)
        assertNotEquals(MusicPicker.place(p, false)!!.key, MusicPicker.place(p, true)!!.key)
    }

    @Test fun musicIsQuieterAtNightAndChangesSmoothly() {
        assertEquals(1f, TimeOfDaySound.musicLevel(12.0), 1e-6f)
        assertEquals(TimeOfDaySound.NIGHT_MUSIC, TimeOfDaySound.musicLevel(1.0), 1e-6f)
        assertEquals(TimeOfDaySound.NIGHT_MUSIC, TimeOfDaySound.musicLevel(22.0), 1e-6f)
        assertEquals((1f + TimeOfDaySound.NIGHT_MUSIC) / 2, TimeOfDaySound.musicLevel(20.0), 1e-6f)
        // no jump anywhere in the day: a game minute moves it at most a little
        var worst = 0f
        for (m in 0..24 * 60) worst = maxOf(worst, kotlin.math.abs(TimeOfDaySound.musicLevel(m / 60.0) - TimeOfDaySound.musicLevel((m + 1) / 60.0)))
        assertTrue(worst < 0.005f, "a minute changes the music's level by $worst")
    }

    @Test fun flavourSoundsAreTheTablesAndSpacedOut() {
        val e = TimeOfDaySound.PLACES.getValue("embassy")
        assertEquals((2843065..2843074).toList(), e.flavor(false))      // kit 130139: SoundAmbienceFlavor 2314 of SoundAmbience 569
        assertEquals(e.flavor(false), e.flavor(true))
        assertEquals(listOf(566564), e.bell)                            // kit 6594: belltollalliance.ogg
        assertTrue(e.files.containsAll(e.bell + e.flavorDay))
        for (seed in 0L until 200L) {
            val d = TimeOfDaySound.flavorDelayMs(seed)
            assertTrue(d in TimeOfDaySound.FLAVOR_MIN_MS..TimeOfDaySound.FLAVOR_MAX_MS)
        }
        // a place with night flavour only plays it by day as well
        assertEquals(listOf(1), PlaceTimeSounds(flavorNight = listOf(1)).flavor(false))
        // the bedrooms have nothing made up for them
        assertEquals(setOf("embassy"), TimeOfDaySound.PLACES.keys)
    }

    @Test fun theBellTellsTheHourOnItsHours() {
        assertEquals(6, TimeOfDaySound.bellStrokes(6, 600.0))
        assertEquals(12, TimeOfDaySound.bellStrokes(12, 600.0))
        assertEquals(9, TimeOfDaySound.bellStrokes(21, 600.0))
        assertEquals(0, TimeOfDaySound.bellStrokes(7, 600.0))
        assertEquals(0, TimeOfDaySound.bellStrokes(0, 600.0))
        // at x1 (60 real seconds an hour: 90 s to the next bell) noon's twelve strokes fit (21.6 s)
        assertEquals(12, TimeOfDaySound.bellStrokes(12, 60.0))
        // at x6 (10 s an hour: 30 s to the next bell, half of it 15 s) eight strokes fit (14.4 s)
        assertEquals(8, TimeOfDaySound.bellStrokes(12, 10.0))
        assertEquals(1, TimeOfDaySound.bellStrokes(12, 0.01))
    }

    @Test fun theBellIsStruckOnlyWhenTheClockPassesABellHour() {
        assertEquals(12, TimeOfDaySound.struck(11.99, 12.01))
        assertNull(TimeOfDaySound.struck(12.01, 12.5))                  // no hour passed
        assertNull(TimeOfDaySound.struck(6.99, 7.01))                   // 7 isn't a bell hour
        assertNull(TimeOfDaySound.struck(11.0, 13.0))                   // a jump of two hours (a restore, a skip) strikes nothing
        assertNull(TimeOfDaySound.struck(12.5, 11.99))                  // backwards
        assertNull(TimeOfDaySound.struck(Double.NaN, 12.0))             // the first time
        assertEquals(6, TimeOfDaySound.struck(29.99, 30.01))            // game hours past a day: 6:00 of day 2
        assertNull(TimeOfDaySound.struck(23.99, 0.01))                  // midnight is passed but isn't a bell hour
        assertEquals(21, TimeOfDaySound.struck(20.999, 21.0))
    }
}
