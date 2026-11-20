package com.logie.wifecraft.audio

import kotlin.math.floor
import kotlin.random.Random

// What the time of day does to a place's sound, apart from the phone (pure JVM: unit-tests/.../audio/TimeOfDaySoundTest):
// the ambience bed by day or by night (SoundAmbience's two loops: sounds.json's ambienceDay / ambienceNight), the music a
// little quieter at night, the ambience's flavour sounds now and then (SoundAmbienceFlavor: the day's or the night's
// kit), and a bell on some hours where the game has one for the place. Audio.timeOfDay plays what these decide.

/**
 * A place's sounds that depend on the hour, beyond its two ambience loops: file ids from the game's tables, never made up.
 * [flavorDay] and [flavorNight] (SoundAmbienceFlavor's SoundEntriesIDDay / SoundEntriesIDNight kits' files) play one at a
 * time, now and then, at [flavorVolume] (the kit's SoundKit.VolumeFloat); [bell] (a bell toll kit's files) is rung on
 * [TimeOfDaySound.BELL_HOURS], once for each hour of a 12-hour clock, at [bellVolume].
 */
data class PlaceTimeSounds(
    val flavorDay: List<Int> = emptyList(),
    val flavorNight: List<Int> = emptyList(),
    val flavorVolume: Float = 1f,
    val bell: List<Int> = emptyList(),
    val bellVolume: Float = 1f,
) {
    /** Every file (loaded ahead so the first plays on time). */
    val files: Set<Int> get() = (flavorDay + flavorNight + bell).toSet()

    fun flavor(night: Boolean): List<Int> = if (night) flavorNight.ifEmpty { flavorDay } else flavorDay.ifEmpty { flavorNight }
}

object TimeOfDaySound {
    /** The ambience's night loop from this hour, its day loop from [DAY_FROM] (as the light: night by 20:30, dawn at 6). */
    const val NIGHT_FROM = 20.0
    const val DAY_FROM = 5.5
    /** How long the day and night ambience loops take to cross (real ms): slow, so the change is heard, not noticed. */
    const val AMBIENCE_CROSS_MS = 8_000
    /** The music's level at night, against 1 by day; eased over the two hours of dusk and of dawn. */
    const val NIGHT_MUSIC = 0.7f
    /** A flavour sound every 25 to 70 real seconds. */
    const val FLAVOR_MIN_MS = 25_000L
    const val FLAVOR_MAX_MS = 70_000L
    /** The hours a bell is rung (every three hours of the waking day, not every hour: a game hour is a real minute at x1). */
    val BELL_HOURS = setOf(6, 9, 12, 15, 18, 21)
    /** Between two strokes of the bell (its toll, belltollalliance.ogg, lasts 1.8 s). */
    const val BELL_SPACING_MS = 1_800L

    /**
     * The places' hour-bound sounds by location id, from the tables at build 12.1.7.70892 (tools: SoundAmbience,
     * SoundAmbienceFlavor, SoundKit, SoundKitEntry). The Embassy (AreaTable 9171, SoundAmbience 569): its flavour row 2314
     * names kit 130139 by day and by night (fx_high_altitude_winds_periodic_flavor_01..10.ogg, VolumeFloat 0.126); its
     * bell is Stormwind's, kit 6594 (belltollalliance.ogg, the toll the game's Alliance towns ring on the hour, VolumeFloat
     * 0.251). The bedrooms have no entry in sounds.json's places yet, so no day and night sound here either.
     */
    val PLACES: Map<String, PlaceTimeSounds> = mapOf(
        "embassy" to PlaceTimeSounds(
            flavorDay = (2843065..2843074).toList(),
            flavorNight = (2843065..2843074).toList(),
            flavorVolume = 0.126f,
            bell = listOf(566564),
            bellVolume = 0.251f,
        ),
    )

    private fun wrap(hour: Double): Double {
        if (!hour.isFinite()) return 0.0
        val h = hour % 24.0
        return if (h < 0.0) h + 24.0 else h
    }

    /** Whether [hour] plays the night's ambience. */
    fun isNight(hour: Double): Boolean {
        val h = wrap(hour)
        return h >= NIGHT_FROM || h < DAY_FROM
    }

    /** The music's level at [hour] (times the mixer's): 1 by day, [NIGHT_MUSIC] by night, linear over 19-21 and 5-7. */
    fun musicLevel(hour: Double): Float {
        val h = wrap(hour)
        val night = when {
            h < 5.0 -> 1.0
            h < 7.0 -> 1.0 - (h - 5.0) / 2.0
            h < 19.0 -> 0.0
            h < 21.0 -> (h - 19.0) / 2.0
            else -> 1.0
        }
        return (1.0 - (1.0 - NIGHT_MUSIC) * night).toFloat()
    }

    /** The ambience file a place's loops give at [hour] ([PlaceSound]'s day or night; the other's when one is missing). */
    fun ambience(p: PlaceSound, hour: Double): Int? = MusicPicker.place(p, isNight(hour))?.ambience?.firstOrNull()

    /** The wait before the next flavour sound, by [seed]. */
    fun flavorDelayMs(seed: Long): Long = FLAVOR_MIN_MS + Random(seed).nextLong(FLAVOR_MAX_MS - FLAVOR_MIN_MS + 1)

    /** One of [files] by [seed] (null: none). */
    fun pick(files: List<Int>, seed: Long): Int? = if (files.isEmpty()) null else files[Random(seed).nextInt(files.size)]

    /**
     * How many strokes the bell rings for [hour] (0..23: 12-hour clock, 12 at noon), no more than fit in half the real time
     * until the next bell ([realSecondsPerHour]: three game hours away), at least one; 0 when [hour] isn't a bell hour.
     */
    fun bellStrokes(hour: Int, realSecondsPerHour: Double): Int {
        val h = ((hour % 24) + 24) % 24
        if (h !in BELL_HOURS) return 0
        val count = if (h % 12 == 0) 12 else h % 12
        val room = if (realSecondsPerHour.isFinite() && realSecondsPerHour > 0.0)
            floor(realSecondsPerHour * 3.0 * 0.5 * 1000.0 / BELL_SPACING_MS).toInt() else count
        return count.coerceAtMost(room).coerceAtLeast(1)
    }

    /**
     * The bell hour struck going from [before] to [after] (hours of the day, or ever-growing game hours; forward by less
     * than an hour: a jump, a restore or a step back strikes nothing), or null.
     */
    fun struck(before: Double, after: Double): Int? {
        if (!before.isFinite() || !after.isFinite()) return null
        var d = wrap(after) - wrap(before)
        if (d < -12.0) d += 24.0
        if (d <= 0.0 || d >= 1.0) return null
        val next = floor(wrap(before)) + 1.0
        if (wrap(before) + d < next) return null
        val h = next.toInt() % 24
        return h.takeIf { it in BELL_HOURS }
    }
}
