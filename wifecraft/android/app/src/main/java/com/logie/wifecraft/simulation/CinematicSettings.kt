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

/** How auto-play hides a change of animation: a hard cut through black, or a quick fade ([fadeS] seconds each way). */
enum class CutStyle(val label: String, val fadeS: Float) {
    HARD("Hard cut", 0f), FADE("Quick fade", 0.12f);

    companion object {
        fun of(name: String?): CutStyle = entries.firstOrNull { it.name == name } ?: FADE
    }
}

/**
 * Cinematic mode's picture frame: [aspect] the 3D view's shape fitted to the screen (black round it; 0: the whole screen) and
 * [picture] the film frame inside it that the thin Letterbox bars make (FilmFrame.barScale; Full screen keeps the wide 2.39).
 */
enum class CinematicFrame(val label: String, val aspect: Float, val picture: Float) {
    AUTO("Auto (4:3)", 4f / 3f, 1.43f), WIDE("Wide (16:9)", 16f / 9f, 1.9f), FULL("Full screen", 0f, 2.39f);

    companion object {
        fun of(name: String?): CinematicFrame = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * Options' Cinematic page: [lighting] the look's strength 0..100 (the place's own cinematic light, its grade and vignette:
 * engine/daylight.h's area profile, every difference from neutral times this), the
 * camera's [style], [letterbox] bars, film [grain], [hideWords] (names, speech and thought bubbles hidden while it runs), each
 * shot [shortest]..[longest] seconds, and [motion] the camera's speed in percent (50..150: how far each shot moves and how
 * quickly it travels between shots); while an animation plays, the shot library's [shotStyle], its [randomness] (0..100: how
 * loosely the cadence and weights are followed and how far each shot drifts) and [allowCuts] (false: smooth moves only);
 * auto-play ([autoplay]: the pair plays one animation after another, CinematicAutoplay), how each change is hidden
 * ([cutStyle]), whether a change of place is walked in the open ([showMovement]) and its [pace] (0..100: how long each
 * animation plays, 50 as designed); the picture's [frame] (Auto 4:3, Wide 16:9, Full screen).
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
    val autoplay: Boolean = true,
    val cutStyle: CutStyle = CutStyle.FADE,
    val showMovement: Boolean = false,
    val pace: Int = 50,
    val frame: CinematicFrame = CinematicFrame.AUTO,
) {
    /** The settings brought within their limits (a hand-edited or old file can't break the director). */
    fun clamped(): CinematicSettings {
        val lo = shortest.coerceIn(SHOT_MIN, SHOT_MAX)
        return copy(lighting = lighting.coerceIn(0, 100), shortest = lo, longest = longest.coerceIn(lo, SHOT_MAX),
            motion = motion.coerceIn(MOTION_MIN, MOTION_MAX), randomness = randomness.coerceIn(0, 100),
            pace = pace.coerceIn(0, 100))
    }

    /** The motion as a factor (1: as designed). */
    val motionScale: Float get() = clamped().motion / 100f

    fun toJson(): JSONObject = JSONObject().put("lighting", lighting).put("style", style.name).put("letterbox", letterbox)
        .put("grain", grain).put("hideWords", hideWords).put("shortest", shortest).put("longest", longest).put("motion", motion)
        .put("shotStyle", shotStyle.name).put("randomness", randomness).put("allowCuts", allowCuts)
        .put("autoplay", autoplay).put("cutStyle", cutStyle.name).put("showMovement", showMovement).put("pace", pace)
        .put("frame", frame.name)

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
                    autoplay = o.optBoolean("autoplay", DEFAULT.autoplay),
                    cutStyle = CutStyle.of(o.optString("cutStyle", DEFAULT.cutStyle.name)),
                    showMovement = o.optBoolean("showMovement", DEFAULT.showMovement),
                    pace = o.optInt("pace", DEFAULT.pace),
                    frame = CinematicFrame.of(o.optString("frame", DEFAULT.frame.name)),
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

        /** Each bar's share of a view of [viewAspect] that bars make a [picture] film frame in (0: it is that wide already). */
        fun bars(viewAspect: Float, picture: Float): Float {
            if (!(viewAspect > 0f) || !(picture > 0f)) return 0f
            return if (viewAspect >= picture) 0f else 0.5f * (1f - viewAspect / picture)
        }
    }

    @Volatile var on = false
    @Volatile var settings: CinematicSettings = CinematicSettings.DEFAULT
    /** The 3D view's width over height in the mode (its frame: FilmFrame); 0: not known (the bars as for Full screen). */
    @Volatile var viewAspect = 0f
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
    fun letterbox(): Float {
        val s = settings
        if (!s.letterbox) return 0f
        // the engine draws 2.39 bars at 1: scaled down to the frame's thin ones (Auto, Wide); Full screen as before
        val k = if (s.frame == CinematicFrame.FULL || !(viewAspect > 0f)) 1f else FilmFrame.barScale(viewAspect, s.frame.picture)
        return eased * k
    }
}
