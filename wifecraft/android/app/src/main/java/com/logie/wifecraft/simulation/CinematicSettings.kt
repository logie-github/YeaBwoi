package com.logie.wifecraft.simulation

import org.json.JSONObject

// Pure Kotlin (no Android): cinematic mode's settings (Options' Cinematic page, kept as JSON in Settings.cinematic) and its
// look's fade (the lighting, vignette, grain and letterbox eased in and out as the mode goes on and off). JVM-tested
// (unit-tests/.../simulation/CinematicDirectorTest.kt).

/** How the director moves the camera: only slow orbits round the action, a mix of shots joined by moves, or a mix cut together. */
enum class CinematicStyle(val label: String) {
    SLOW_ORBIT("Slow orbit only"), MIXED("Mixed shots"), CUTS("Cuts");

    companion object {
        fun of(name: String?): CinematicStyle = entries.firstOrNull { it.name == name } ?: MIXED
    }
}

/**
 * Options' Cinematic page: [lighting] the mood's strength 0..100 (the warm low-key light, the grade and the vignette), the
 * camera's [style], [letterbox] bars, film [grain], [hideWords] (names, speech and thought bubbles hidden while it runs), each
 * shot [shortest]..[longest] seconds, and [motion] the camera's speed in percent (50..150: how far each shot moves and how
 * quickly it travels between shots); while an animation plays, the shot library's [shotStyle], its [randomness] (0..100: how
 * loosely the cadence and weights are followed and how far each shot drifts) and [allowCuts] (false: smooth moves only).
 */
data class CinematicSettings(
    val lighting: Int = 70,
    val style: CinematicStyle = CinematicStyle.MIXED,
    val letterbox: Boolean = true,
    val grain: Boolean = false,
    val hideWords: Boolean = true,
    val shortest: Int = 4,
    val longest: Int = 9,
    val motion: Int = 100,
    val shotStyle: ShotStyle = ShotStyle.CLASSIC,
    val randomness: Int = 40,
    val allowCuts: Boolean = true,
) {
    /** The settings brought within their limits (a hand-edited or old file can't break the director). */
    fun clamped(): CinematicSettings {
        val lo = shortest.coerceIn(SHOT_MIN, SHOT_MAX)
        return copy(lighting = lighting.coerceIn(0, 100), shortest = lo, longest = longest.coerceIn(lo, SHOT_MAX),
            motion = motion.coerceIn(MOTION_MIN, MOTION_MAX), randomness = randomness.coerceIn(0, 100))
    }

    /** The motion as a factor (1: as designed). */
    val motionScale: Float get() = clamped().motion / 100f

    fun toJson(): JSONObject = JSONObject().put("lighting", lighting).put("style", style.name).put("letterbox", letterbox)
        .put("grain", grain).put("hideWords", hideWords).put("shortest", shortest).put("longest", longest).put("motion", motion)
        .put("shotStyle", shotStyle.name).put("randomness", randomness).put("allowCuts", allowCuts)

    companion object {
        const val SHOT_MIN = 3
        const val SHOT_MAX = 15
        const val MOTION_MIN = 50
        const val MOTION_MAX = 150
        val DEFAULT = CinematicSettings()

        /** Settings from their JSON ([toJson]); null, empty or broken: the defaults; a missing field: its default. */
        fun parse(text: String?): CinematicSettings {
            if (text.isNullOrBlank()) return DEFAULT
            return try {
                val o = JSONObject(text)
                CinematicSettings(
                    lighting = o.optInt("lighting", DEFAULT.lighting),
                    style = CinematicStyle.of(o.optString("style", DEFAULT.style.name)),
                    letterbox = o.optBoolean("letterbox", DEFAULT.letterbox),
                    grain = o.optBoolean("grain", DEFAULT.grain),
                    hideWords = o.optBoolean("hideWords", DEFAULT.hideWords),
                    shortest = o.optInt("shortest", DEFAULT.shortest),
                    longest = o.optInt("longest", DEFAULT.longest),
                    motion = o.optInt("motion", DEFAULT.motion),
                    shotStyle = ShotStyle.of(o.optString("shotStyle", DEFAULT.shotStyle.name)),
                    randomness = o.optInt("randomness", DEFAULT.randomness),
                    allowCuts = o.optBoolean("allowCuts", DEFAULT.allowCuts),
                ).clamped()
            } catch (e: Exception) { DEFAULT }
        }
    }
}

/**
 * The cinematic look eased in and out: [on] is what is asked, [amount] goes 0..1 over [FADE_S] (linear in time, eased by
 * [eased]), and the engine's four numbers follow it ([mood], [vignette], [grain], [letterbox]: Native.cinematic). Stepped
 * once a frame on the GL thread; [on] and the settings may be set from any thread.
 */
class CinematicLook {
    companion object {
        const val FADE_S = 1.2f
        /** The grain's strength when it is on (engine/grade.h: 1 is about +-4% on the mid-tones). */
        const val GRAIN = 0.6f
        /** The film frame the letterbox bars make (width over height). */
        const val ASPECT = 2.39f

        /** Each letterbox bar's share of a [width] x [height] view at [amount] 0..1 (engine/grade.h letterboxBars, eased in). */
        fun bars(width: Int, height: Int, amount: Float): Float {
            if (width <= 0 || height <= 0 || !(amount > 0f)) return 0f
            val a = width.toFloat() / height
            return if (a >= ASPECT) 0f else 0.5f * (1f - a / ASPECT) * amount.coerceAtMost(1f)
        }
    }

    @Volatile var on = false
    @Volatile var settings: CinematicSettings = CinematicSettings.DEFAULT
    var amount = 0f
        private set

    val eased: Float get() = easeInOut(amount)
    /** Whether anything is still showing (on, or fading out). */
    val showing: Boolean get() = amount > 0f

    private var lastNanos = 0L

    /** One frame at [nowNanos] (the frame clock): [step] by the time since the last. */
    fun frame(nowNanos: Long): Boolean {
        val dt = if (lastNanos == 0L) 0f else ((nowNanos - lastNanos).coerceAtLeast(0L) / 1e9f)
        lastNanos = nowNanos
        return step(dt)
    }

    /** One frame, [dt] seconds: [amount] moves toward [on]. Returns whether it moved. */
    fun step(dt: Float): Boolean {
        val goal = if (on) 1f else 0f
        if (amount == goal) return false
        val d = (if (dt.isFinite()) dt.coerceIn(0f, 0.1f) else 0f) / FADE_S
        amount = if (goal > amount) (amount + d).coerceAtMost(goal) else (amount - d).coerceAtLeast(goal)
        return true
    }

    fun mood(): Float = eased * settings.clamped().lighting / 100f
    fun vignette(): Float = mood()
    fun grain(): Float = if (settings.grain) eased * GRAIN else 0f
    /** 0..1: how far the letterbox bars are in (the engine sizes them for the view: grade.h letterboxBars). */
    fun letterbox(): Float = if (settings.letterbox) eased else 0f
}
