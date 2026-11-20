package com.logie.wifecraft.simulation

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor

// The world's clock: the hour of the day and the day, moving with real time at a chosen speed, its named parts of the day,
// the settings the Options page binds (ClockSettings), and when to hand the hour to the renderer (LightFeed). Pure JVM
// (unit-tests/.../simulation/WorldClockTest). The life simulation follows it as a LifeTime (LifeClock.source); the scene's
// light (SceneView.setTimeOfDay) and sound (audio/Audio.timeOfDay) follow its hour.

/** How fast the world's time passes: paused, x1 (a real second is a game minute: a day takes 24 real minutes), x3, x6. */
enum class ClockSpeed(val label: String, val multiplier: Double) {
    PAUSED("Paused", 0.0),
    X1("x1", 1.0),
    X3("x3", 3.0),
    X6("x6", 6.0);

    companion object {
        fun of(name: String?): ClockSpeed = entries.firstOrNull { it.name == name } ?: X1
    }
}

/** The named parts of the day, each from its [startHour] to the next one's. Night is on both sides of Midnight. */
enum class DayPhase(val label: String) {
    DAWN("Dawn"),
    MORNING("Morning"),
    NOON("Noon"),
    AFTERNOON("Afternoon"),
    DUSK("Dusk"),
    EVENING("Evening"),
    NIGHT("Night"),
    MIDNIGHT("Midnight");

    companion object {
        /** Where each part begins (hours, in the day's order from midnight); the last runs on to 24. */
        val STARTS: List<Pair<Double, DayPhase>> = listOf(
            0.0 to MIDNIGHT,
            1.0 to NIGHT,
            5.0 to DAWN,
            7.0 to MORNING,
            11.0 to NOON,
            14.0 to AFTERNOON,
            17.0 to DUSK,
            19.5 to EVENING,
            22.0 to NIGHT,
            23.5 to MIDNIGHT,
        )

        /** The part of the day at [hour] (any number: brought into 0..24 first). */
        fun at(hour: Double): DayPhase {
            val h = WorldClock.wrap(hour)
            var p = STARTS.first().second
            for ((start, phase) in STARTS) if (h >= start) p = phase
            return p
        }
    }
}

/** What one [WorldClock.advance] moved past: the game hours, the hours of the day struck on the way, a new day, a new part. */
data class ClockStep(
    val hours: Double,
    /** The hours of the day (0..23) whose start was passed, in order (usually none or one). */
    val struck: List<Int> = emptyList(),
    val newDay: Boolean = false,
    /** The part of the day entered, when it changed. */
    val phase: DayPhase? = null,
) {
    companion object {
        val NONE = ClockStep(0.0)
    }
}

/**
 * The Options page's clock settings, as plain state the screen binds: [speed], the hour a scene starts at ([startHour],
 * morning by default) and whether the light and sound follow the day ([cycle]: off, the scene keeps its start hour's light
 * while the clock, the needs and the HUD's time still run).
 */
data class ClockSettings(
    val speed: ClockSpeed = ClockSpeed.X1,
    val startHour: Double = WorldClock.DEFAULT_START_HOUR,
    val cycle: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("speed", speed.name).put("startHour", startHour).put("cycle", cycle)

    companion object {
        val DEFAULT = ClockSettings()

        /** The start hours offered, with their labels. */
        val START_HOURS: List<Pair<Double, String>> = listOf(
            6.0 to "Dawn (6:00)",
            8.0 to "Morning (8:00)",
            12.0 to "Noon (12:00)",
            17.0 to "Dusk (17:00)",
            21.0 to "Evening (21:00)",
            0.0 to "Midnight (0:00)",
        )

        fun fromJson(o: JSONObject?): ClockSettings {
            if (o == null) return DEFAULT
            val h = o.optDouble("startHour", WorldClock.DEFAULT_START_HOUR)
            return ClockSettings(
                ClockSpeed.of(o.optString("speed", ClockSpeed.X1.name)),
                if (h.isFinite()) WorldClock.wrap(h) else WorldClock.DEFAULT_START_HOUR,
                o.optBoolean("cycle", true),
            )
        }

        fun parse(text: String?): ClockSettings = try {
            if (text.isNullOrBlank()) DEFAULT else fromJson(JSONObject(text))
        } catch (e: Exception) {
            DEFAULT
        }
    }
}

/**
 * The world's clock. [simHours] counts game hours from midnight of day 1, only ever forward (the life simulation's
 * [LifeTime]); [hourOfDay] is 0 until 24, [day] 1, 2, ... It starts at [startHour] of day [startDay] (the scene chooses:
 * morning by default). [advance] moves it by real seconds at [speed] ([SIM_MINUTES_PER_SECOND] game minutes a real second
 * at x1); one call moves at most [MAX_REAL_SECONDS] (a resume after the app slept doesn't skip the day). [lightHour] is the
 * hour the light and sound show: [hourOfDay], or the start hour while [cycle] is off. Not thread-safe: one thread drives it.
 */
class WorldClock(startHour: Double = DEFAULT_START_HOUR, startDay: Int = 1) : LifeTime {
    override var simHours: Double = (startDay.coerceAtLeast(1) - 1) * 24.0 + wrap(startHour)
        private set
    var speed: ClockSpeed = ClockSpeed.X1
    /** Whether the light and sound follow the day ([lightHour]). */
    var cycle: Boolean = true
    /** The hour shown while [cycle] is off: the scene's start hour. */
    var fixedHour: Double = wrap(startHour)
        set(v) { field = wrap(v) }

    /** Day 1, 2, ... */
    val day: Int get() = floor(simHours / 24.0).toInt() + 1
    /** 0 until 24. */
    val hourOfDay: Double get() = wrap(simHours)
    /** The minute of the day, 0..1439. */
    val minuteOfDay: Int get() = (floor(hourOfDay * 60.0).toInt()).coerceIn(0, 1439)
    val phase: DayPhase get() = DayPhase.at(hourOfDay)
    /** The hour the scene's light and sound show. */
    val lightHour: Double get() = if (cycle) hourOfDay else fixedHour
    /** Real seconds one game hour takes now (paused: as at x1). */
    val realSecondsPerHour: Double get() = 60.0 / SIM_MINUTES_PER_SECOND / (speed.multiplier.takeIf { it > 0.0 } ?: 1.0)

    /** The settings put on the clock (its speed and cycle; the start hour is used by [start]). */
    fun apply(settings: ClockSettings) {
        speed = settings.speed
        cycle = settings.cycle
    }

    /** A scene begins: the clock at [hour] of day [day], the fixed hour with it. */
    fun start(hour: Double = DEFAULT_START_HOUR, day: Int = 1) {
        simHours = (day.coerceAtLeast(1) - 1) * 24.0 + wrap(hour)
        fixedHour = hour
    }

    /** Moves the clock by [realSeconds] at [speed]: what it passed. */
    fun advance(realSeconds: Double): ClockStep {
        if (!realSeconds.isFinite() || realSeconds <= 0.0 || speed.multiplier <= 0.0) return ClockStep.NONE
        val s = realSeconds.coerceAtMost(MAX_REAL_SECONDS)
        return forward(s * speed.multiplier * SIM_MINUTES_PER_SECOND / 60.0)
    }

    /** Moves the clock on to the next [hour] of the day (later today, else tomorrow): a night's sleep, say. */
    fun skipTo(hour: Double): ClockStep {
        var d = wrap(hour) - hourOfDay
        if (d <= 0.0) d += 24.0
        return forward(d)
    }

    private fun forward(hours: Double): ClockStep {
        if (hours <= 0.0) return ClockStep.NONE
        val before = simHours
        val dayBefore = day
        val phaseBefore = phase
        simHours += hours
        val struck = ArrayList<Int>()
        // the starts of hours passed: (before, after]
        var next = floor(before) + 1.0
        while (next <= simHours && struck.size < 24) {
            struck += (wrap(next).toInt()) % 24
            next += 1.0
        }
        return ClockStep(hours, struck, day != dayBefore, phase.takeIf { it != phaseBefore })
    }

    /** "08:05" (24-hour). */
    fun time24(): String = format24(hourOfDay)

    /** "8:05 AM". */
    fun time12(): String = format12(hourOfDay)

    /** "Day 2, 08:05". */
    fun label(): String = "Day $day, ${time24()}"

    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("simHours", simHours)
        .put("speed", speed.name)
        .put("cycle", cycle)
        .put("fixedHour", fixedHour)

    /** A saved clock put back ([toJson]'s); what is missing or unreadable keeps its value. */
    fun restore(o: JSONObject?) {
        if (o == null) return
        val h = o.optDouble("simHours", Double.NaN)
        if (h.isFinite() && h >= 0.0) simHours = h
        if (o.has("speed")) speed = ClockSpeed.of(o.optString("speed"))
        if (o.has("cycle")) cycle = o.optBoolean("cycle", cycle)
        val f = o.optDouble("fixedHour", Double.NaN)
        if (f.isFinite()) fixedHour = f
    }

    companion object {
        /** Game minutes a real second at x1: a game day is 24 real minutes (8 at x3, 4 at x6). */
        const val SIM_MINUTES_PER_SECOND = 1.0
        const val DEFAULT_START_HOUR = 8.0
        /** The most real time one [advance] counts. */
        const val MAX_REAL_SECONDS = 1.0

        /** [hours] brought into 0 until 24 (not a number: 0). */
        fun wrap(hours: Double): Double {
            if (!hours.isFinite()) return 0.0
            val h = hours % 24.0
            val w = if (h < 0.0) h + 24.0 else h
            return if (w >= 24.0) 0.0 else w
        }

        fun format24(hour: Double): String {
            val m = (floor(wrap(hour) * 60.0).toInt()).coerceIn(0, 1439)
            return "%02d:%02d".format(m / 60, m % 60)
        }

        fun format12(hour: Double): String {
            val m = (floor(wrap(hour) * 60.0).toInt()).coerceIn(0, 1439)
            val h = m / 60
            val h12 = if (h % 12 == 0) 12 else h % 12
            return "%d:%02d %s".format(h12, m % 60, if (h < 12) "AM" else "PM")
        }

        fun fromJson(o: JSONObject?): WorldClock = WorldClock().also { it.restore(o) }

        /** The shorter way round the clock from [a] to [b], in hours (-12..12). */
        fun hoursBetween(a: Double, b: Double): Double {
            var d = wrap(b) - wrap(a)
            if (d > 12.0) d -= 24.0
            if (d < -12.0) d += 24.0
            return d
        }
    }
}

/**
 * When to hand the hour to the renderer (SceneView.setTimeOfDay): when it moved by [stepHours] or more since the last
 * hand-over (half a game minute: at x6 a few times a real second, at x1 one every half second), or the cycle was turned on
 * or off. [next] gives the hour to send (negative: the cycle is off, the location's own light), or null for nothing new.
 */
class LightFeed(private val stepHours: Double = 1.0 / 120.0) {
    private var sent = Double.NaN

    fun next(clock: WorldClock): Float? = next(clock.lightHour, true)

    fun next(hour: Double, on: Boolean): Float? {
        val h = if (on) WorldClock.wrap(hour) else -1.0
        val last = sent
        val changed = when {
            last.isNaN() -> true
            (h < 0.0) != (last < 0.0) -> true
            h < 0.0 -> false
            else -> abs(WorldClock.hoursBetween(last, h)) >= stepHours
        }
        if (!changed) return null
        sent = h
        return h.toFloat()
    }

    /** The next [next] sends whatever it is given (a new location was loaded). */
    fun reset() { sent = Double.NaN }
}
