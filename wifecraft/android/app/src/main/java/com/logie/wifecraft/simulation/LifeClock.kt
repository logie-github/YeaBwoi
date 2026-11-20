package com.logie.wifecraft.simulation

/**
 * The life simulation's time ([LifeSim]): hours of game time since the cast's life began. Anything can drive it: the life
 * layer's own [LifeClock] (one game minute a real second at x1), or the world's day-and-night clock, which the screen hands
 * in as [LifeClock.source] once it exists (the clock then only follows it). Pure JVM.
 */
interface LifeTime {
    /** Game hours, only ever going forward (a source whose hours wrap at 24 is followed across midnight). */
    val simHours: Double
}

/** How fast game time passes against real time: paused (needs and the clock stand still), x1, x3, x6. */
enum class LifeSpeed(val label: String, val multiplier: Double) {
    PAUSE("Pause", 0.0),
    NORMAL("x1", 1.0),
    FAST("x3", 3.0),
    FASTEST("x6", 6.0);

    companion object {
        fun of(name: String?): LifeSpeed = entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/**
 * The life layer's own clock: [advance] moves it by real seconds at [speed] ([SIM_MINUTES_PER_SECOND] game minutes a real
 * second at x1: a game day is 24 real minutes, 8 at x3, 4 at x6), or, while [source] is set, by however far the source moved
 * since the last call (its own speed rules; a step back of more than 12 hours is a wrapped day). [simHours] counts from
 * midnight of day 1 (as the world's WorldClock does) and starts at [START_HOUR] (8 in the morning); with a source it shows the
 * source's hours (a jump in them spends at most [MAX_STEP_HOURS] of needs).
 */
class LifeClock(start: Double = START_HOUR) : LifeTime {
    override var simHours: Double = start
        private set
    var speed: LifeSpeed = LifeSpeed.NORMAL
    /** The world's clock, when it drives this one (null: this clock runs by itself). */
    var source: LifeTime? = null
        set(v) { field = v; lastSource = Double.NaN }
    private var lastSource = Double.NaN

    /** Day 1, 2, ... */
    val day: Int get() = (simHours / 24.0).toInt() + 1
    /** 0 until 24. */
    val hourOfDay: Double get() = simHours - (day - 1) * 24.0

    /** Moves the clock by [realSeconds]: the game hours that passed for the needs (0 when paused, never negative). */
    fun advance(realSeconds: Float): Double {
        val src = source
        if (src != null) {
            val now = src.simHours
            var d = if (lastSource.isNaN()) 0.0 else now - lastSource
            lastSource = now
            if (d < -12.0) d += 24.0
            // the hours shown are the world's; the needs move by at most a step's worth of a jump in it
            if (now.isFinite() && now >= 0.0) simHours = now
            return d.coerceIn(0.0, MAX_STEP_HOURS)
        }
        val s = realSeconds.toDouble()
        val hours = if (!s.isFinite() || s <= 0.0) 0.0 else s * speed.multiplier * SIM_MINUTES_PER_SECOND / 60.0
        simHours += hours
        return hours
    }

    /** Puts the clock at [hours] (a saved game's). */
    fun restore(hours: Double) {
        if (hours.isFinite() && hours >= 0.0) simHours = hours
    }

    /** Real seconds [hours] of game time take at the current speed (paused: as at x1). */
    fun realSeconds(hours: Double): Float {
        val m = speed.multiplier.takeIf { it > 0.0 } ?: 1.0
        return (hours * 60.0 / SIM_MINUTES_PER_SECOND / m).toFloat()
    }

    companion object {
        /** Game minutes a real second at x1. */
        const val SIM_MINUTES_PER_SECOND = 1.0
        const val START_HOUR = 8.0
        /** The most a source may move the clock in one call (a jump of the world's clock is not hours of hunger at once). */
        const val MAX_STEP_HOURS = 0.5
    }
}
