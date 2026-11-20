package com.logie.wifecraft.simulation

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldClockTest {
    private val eps = 1e-9

    @Test fun startsInTheMorningOfDayOne() {
        val c = WorldClock()
        assertEquals(8.0, c.hourOfDay, eps)
        assertEquals(1, c.day)
        assertEquals(8.0, c.simHours, eps)
        assertEquals(DayPhase.MORNING, c.phase)
        assertEquals("08:00", c.time24())
        assertEquals("8:00 AM", c.time12())
        assertEquals("Day 1, 08:00", c.label())
    }

    @Test fun aRealSecondIsAGameMinuteAtX1AndADayTakes24RealMinutes() {
        val c = WorldClock(0.0)
        repeat(60) { c.advance(1.0) }
        assertEquals(1.0, c.hourOfDay, 1e-6)
        // the rest of the day, 23 real minutes
        repeat(23 * 60) { c.advance(1.0) }
        assertEquals(24.0, c.simHours, 1e-6)
        c.advance(1.0)       // (1440 sums of a sixtieth land a hair either side of 24)
        assertEquals(2, c.day)
        assertEquals(1.0 / 60.0, c.hourOfDay, 1e-6)
        assertEquals(60.0, c.realSecondsPerHour, eps)
    }

    @Test fun speedsMultiplyAndPausedStandsStill() {
        val c = WorldClock(12.0)
        c.speed = ClockSpeed.X3
        c.advance(1.0)
        assertEquals(12.0 + 3.0 / 60.0, c.hourOfDay, 1e-9)
        c.speed = ClockSpeed.X6
        c.advance(0.5)
        assertEquals(12.0 + 6.0 / 60.0, c.hourOfDay, 1e-9)
        assertEquals(10.0, c.realSecondsPerHour, eps)
        c.speed = ClockSpeed.PAUSED
        val step = c.advance(1.0)
        assertEquals(0.0, step.hours, eps)
        assertEquals(12.1, c.hourOfDay, 1e-9)
        assertEquals(60.0, c.realSecondsPerHour, eps)     // paused counts as x1 for "how long would an hour take"
    }

    @Test fun badOrHugeStepsAreHarmless() {
        val c = WorldClock(10.0)
        assertEquals(0.0, c.advance(-5.0).hours, eps)
        assertEquals(0.0, c.advance(Double.NaN).hours, eps)
        assertEquals(0.0, c.advance(Double.POSITIVE_INFINITY).hours, eps)
        // a resume after an hour in the background moves at most MAX_REAL_SECONDS
        c.advance(3600.0)
        assertEquals(10.0 + WorldClock.MAX_REAL_SECONDS / 60.0, c.hourOfDay, 1e-9)
    }

    @Test fun rollsOverMidnightIntoTheNextDayAndStrikesTheHours() {
        val c = WorldClock(23.99)
        c.speed = ClockSpeed.X6
        var newDay = false
        val struck = ArrayList<Int>()
        repeat(30) { val s = c.advance(1.0); newDay = newDay || s.newDay; struck += s.struck }
        assertTrue(newDay)
        assertEquals(2, c.day)
        assertEquals(listOf(0, 1, 2), struck)     // 23:59.4 + 3 hours: 0:00, 1:00, 2:00
        assertTrue(c.simHours > 24.0)
        assertTrue(c.hourOfDay < 3.0)
    }

    @Test fun simHoursOnlyGoesForward() {
        val c = WorldClock(22.0)
        c.speed = ClockSpeed.X6
        var last = c.simHours
        repeat(2000) {
            c.advance(0.7)
            assertTrue(c.simHours >= last)
            last = c.simHours
        }
        assertTrue(c.day >= 2)
        // the life layer follows it across midnight as a LifeTime source
        val life = LifeClock()
        life.source = c
        life.advance(0f)
        val before = life.simHours
        repeat(10) { c.advance(1.0); life.advance(1f) }
        assertEquals(1.0, life.simHours - before, 1e-6)     // ten real seconds at x6 = one game hour
    }

    @Test fun phasesCoverTheDayInOrder() {
        val expect = mapOf(
            0.5 to DayPhase.MIDNIGHT, 2.0 to DayPhase.NIGHT, 5.0 to DayPhase.DAWN, 6.5 to DayPhase.DAWN,
            7.0 to DayPhase.MORNING, 9.0 to DayPhase.MORNING, 12.0 to DayPhase.NOON, 15.0 to DayPhase.AFTERNOON,
            17.0 to DayPhase.DUSK, 19.0 to DayPhase.DUSK, 20.0 to DayPhase.EVENING, 22.0 to DayPhase.NIGHT,
            23.75 to DayPhase.MIDNIGHT, 24.0 to DayPhase.MIDNIGHT, -1.0 to DayPhase.NIGHT,
        )
        for ((h, p) in expect) assertEquals(p, DayPhase.at(h), "at $h")
        // every named part is reached in a day
        val seen = (0 until 24 * 60).map { DayPhase.at(it / 60.0) }.toSet()
        assertEquals(DayPhase.entries.toSet(), seen)
    }

    @Test fun aStepSaysWhenThePhaseChanges() {
        val c = WorldClock(6.99)
        val s = c.advance(1.0)
        assertEquals(DayPhase.MORNING, s.phase)
        assertNull(c.advance(1.0).phase)
    }

    @Test fun skipToMovesForwardToTheNextOccurrence() {
        val c = WorldClock(23.0)
        val s = c.skipTo(7.0)
        assertEquals(8.0, s.hours, 1e-9)
        assertEquals(2, c.day)
        assertEquals(7.0, c.hourOfDay, 1e-9)
        assertTrue(s.newDay)
        c.skipTo(9.0)
        assertEquals(2, c.day)
        assertEquals(9.0, c.hourOfDay, 1e-9)
    }

    @Test fun theCycleOffKeepsTheStartHoursLightWhileTheClockRuns() {
        val c = WorldClock()
        c.start(17.0, day = 3)
        assertEquals(3, c.day)
        c.cycle = false
        c.speed = ClockSpeed.X6
        repeat(20) { c.advance(1.0) }     // two game hours
        assertEquals(17.0, c.lightHour, eps)
        assertTrue(c.hourOfDay > 17.0)
        c.cycle = true
        assertEquals(c.hourOfDay, c.lightHour, eps)
    }

    @Test fun savesAndRestores() {
        val c = WorldClock(21.25, startDay = 4)
        c.speed = ClockSpeed.X3
        c.cycle = false
        c.advance(0.8)
        val text = c.toJson().toString()
        val r = WorldClock.fromJson(JSONObject(text))
        assertEquals(c.simHours, r.simHours, eps)
        assertEquals(4, r.day)
        assertEquals(ClockSpeed.X3, r.speed)
        assertFalse(r.cycle)
        assertEquals(c.fixedHour, r.fixedHour, eps)
        // a bad or partial save keeps the defaults
        val d = WorldClock.fromJson(JSONObject("""{"simHours": "x", "speed": "WARP"}"""))
        assertEquals(WorldClock.DEFAULT_START_HOUR, d.simHours, eps)
        assertEquals(ClockSpeed.X1, d.speed)
        val n = WorldClock.fromJson(null)
        assertEquals(8.0, n.hourOfDay, eps)
    }

    @Test fun settingsAreStateWithJson() {
        val s = ClockSettings(ClockSpeed.X6, 21.0, cycle = false)
        assertEquals(s, ClockSettings.fromJson(s.toJson()))
        assertEquals(s, ClockSettings.parse(s.toJson().toString()))
        assertEquals(ClockSettings.DEFAULT, ClockSettings.parse("not json"))
        assertEquals(ClockSettings.DEFAULT, ClockSettings.parse(null))
        assertEquals(1.0, ClockSettings.fromJson(JSONObject("""{"startHour": 25}""")).startHour, eps)
        assertTrue(ClockSettings.START_HOURS.any { it.first == WorldClock.DEFAULT_START_HOUR })
        val c = WorldClock()
        c.apply(s)
        assertEquals(ClockSpeed.X6, c.speed)
        assertFalse(c.cycle)
    }

    @Test fun formatsTheTime() {
        assertEquals("00:00", WorldClock.format24(0.0))
        assertEquals("23:59", WorldClock.format24(23.999))
        assertEquals("12:30 PM", WorldClock.format12(12.5))
        assertEquals("12:00 AM", WorldClock.format12(24.0))
        assertEquals("6:05 AM", WorldClock.format12(6.0 + 5.0 / 60.0 + 1e-9))
    }

    @Test fun wrapAndShortestDistance() {
        assertEquals(1.5, WorldClock.wrap(25.5), eps)
        assertEquals(23.0, WorldClock.wrap(-1.0), eps)
        assertEquals(0.0, WorldClock.wrap(Double.NaN), eps)
        assertEquals(1.0, WorldClock.hoursBetween(23.5, 0.5), eps)
        assertEquals(-1.0, WorldClock.hoursBetween(0.5, 23.5), eps)
    }

    @Test fun theLightFeedSendsOnlyWhatMoved() {
        val f = LightFeed(stepHours = 0.01)
        assertEquals(8.0f, f.next(8.0, true))
        assertNull(f.next(8.005, true))
        assertNotNull(f.next(8.011, true))
        // across midnight is a small step, not a day
        assertNotNull(f.next(23.995, true))
        assertNull(f.next(0.004, true))
        assertNotNull(f.next(0.01, true))
        // off and on again
        assertEquals(-1f, f.next(0.01, false))
        assertNull(f.next(5.0, false))
        assertEquals(5f, f.next(5.0, true))
        f.reset()
        assertEquals(5f, f.next(5.0, true))
        // driven by a clock at x6, a few hand-overs a real second, never a jump of more than a few minutes
        val c = WorldClock(19.0)
        c.speed = ClockSpeed.X6
        val feed = LightFeed()
        var last = feed.next(c)!!.toDouble()
        var sends = 0
        repeat(600) {
            c.advance(1.0 / 60.0)
            feed.next(c)?.let { h -> assertTrue(kotlin.math.abs(WorldClock.hoursBetween(last, h.toDouble())) < 0.05); last = h.toDouble(); sends++ }
        }
        assertTrue(sends in 30..130, "$sends hand-overs in 10 real seconds at x6")
    }
}
