package com.logie.wifecraft.simulation

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

// Pure Kotlin (no Android): immersive mode. Its settings (Options' Immersion page, kept as JSON in Settings.immersive, with
// the migration that turns it on for everyone once), the intensity a scene's moment has (IntensityModel, eased by
// IntensityEase), the HUD that shows on a tap and fades away again (HudReveal), the soft controls (SoftControl), the mix
// the intensity moves (IntensityMix) and cinematic mode's handheld sway (HandheldSway). JVM-tested
// (unit-tests/.../simulation/ImmersionTest.kt).

/**
 * Options' Immersion page. [lifeHud]: the life simulation's HUD (need bars and rings, mood gems, thought bubbles, the feed,
 * the clock, the radial and Actions menus) shown; off by default (the simulation still runs underneath). [hudSeconds]: how
 * long the minimal HUD stays after a tap. [autoFlow]: the scene's flow moves on by itself ([SceneFlow]). [undress]: the
 * flow's undressing takes pieces off one by one. [breathing], [idleMotion]: the body's additive motion in percent (the
 * engine's breathing; weight shifts and head tilts). [eyeContact]: heads turn to the partner. [particles]: glowing motes in
 * the room (always while cinematic mode runs, unless [particlesInCinematic] is off). [handheld]: cinematic camera sway in
 * percent. [atmosphere]: the atmosphere sounds (fire crackle, cloth) in percent; [rain]: light rain outside. [voice]:
 * how far the partner sounds follow the intensity, in percent (0: always at their own volume).
 */
data class ImmersiveSettings(
    val lifeHud: Boolean = false,
    val hudSeconds: Int = 4,
    val autoFlow: Boolean = false,
    val undress: Boolean = true,
    val breathing: Int = 70,
    val idleMotion: Int = 60,
    val eyeContact: Boolean = true,
    val particles: Boolean = false,
    val particlesInCinematic: Boolean = true,
    val handheld: Int = 40,
    val atmosphere: Int = 70,
    val rain: Boolean = false,
    val voice: Int = 70,
) {
    fun clamped(): ImmersiveSettings = copy(
        hudSeconds = hudSeconds.coerceIn(HUD_MIN_S, HUD_MAX_S),
        breathing = breathing.coerceIn(0, 100), idleMotion = idleMotion.coerceIn(0, 100),
        handheld = handheld.coerceIn(0, 100), atmosphere = atmosphere.coerceIn(0, 100), voice = voice.coerceIn(0, 100),
    )

    /** Whether the glowing motes are drawn now ([cinematic]: cinematic mode is on). */
    fun particlesOn(cinematic: Boolean): Boolean = particles || (cinematic && particlesInCinematic)

    fun toJson(): JSONObject = JSONObject().put("version", VERSION).put("lifeHud", lifeHud).put("hudSeconds", hudSeconds)
        .put("autoFlow", autoFlow).put("undress", undress).put("breathing", breathing).put("idleMotion", idleMotion)
        .put("eyeContact", eyeContact).put("particles", particles).put("particlesInCinematic", particlesInCinematic)
        .put("handheld", handheld).put("atmosphere", atmosphere).put("rain", rain).put("voice", voice)

    companion object {
        /** The settings' format: 1 is the first with immersive mode (before it, the life HUD was always shown). */
        const val VERSION = 1
        const val HUD_MIN_S = 2
        const val HUD_MAX_S = 10
        val DEFAULT = ImmersiveSettings()

        /**
         * The settings from what was kept ([text], null when nothing was): nothing or an older version is the defaults
         * (immersive on, the life HUD hidden), whatever the player's other options are (those are kept elsewhere and never
         * touched); a broken text is the defaults; a missing field its default.
         */
        fun parse(text: String?): ImmersiveSettings {
            if (text.isNullOrBlank()) return DEFAULT
            return try {
                val o = JSONObject(text)
                if (o.optInt("version", 0) < VERSION) return DEFAULT
                ImmersiveSettings(
                    lifeHud = o.optBoolean("lifeHud", DEFAULT.lifeHud),
                    hudSeconds = o.optInt("hudSeconds", DEFAULT.hudSeconds),
                    autoFlow = o.optBoolean("autoFlow", DEFAULT.autoFlow),
                    undress = o.optBoolean("undress", DEFAULT.undress),
                    breathing = o.optInt("breathing", DEFAULT.breathing),
                    idleMotion = o.optInt("idleMotion", DEFAULT.idleMotion),
                    eyeContact = o.optBoolean("eyeContact", DEFAULT.eyeContact),
                    particles = o.optBoolean("particles", DEFAULT.particles),
                    particlesInCinematic = o.optBoolean("particlesInCinematic", DEFAULT.particlesInCinematic),
                    handheld = o.optInt("handheld", DEFAULT.handheld),
                    atmosphere = o.optInt("atmosphere", DEFAULT.atmosphere),
                    rain = o.optBoolean("rain", DEFAULT.rain),
                    voice = o.optInt("voice", DEFAULT.voice),
                ).clamped()
            } catch (e: Exception) { DEFAULT }
        }

        /**
         * The one-time move to immersive mode: what to keep under the settings' key given what is kept there now ([stored])
         * — the same text when it is already current, else the defaults' JSON. Null: nothing to write.
         */
        fun migrate(stored: String?): String? {
            val current = try { stored?.let { JSONObject(it).optInt("version", 0) >= VERSION } ?: false } catch (e: Exception) { false }
            return if (current) null else DEFAULT.toJson().toString()
        }
    }
}

/**
 * Where a person's moment is, for the [IntensityModel]: [partnered] in a two-person animation; [stage] the author's stage
 * number (1.., 0: none named); [kind] the part playing; [pass] the loop pass (from 1) of [wanted]; [seconds] since the
 * animation began; [playing] anything plays at all.
 */
data class MomentProgress(
    val playing: Boolean,
    val partnered: Boolean = false,
    val stage: Int = 0,
    val kind: SegmentKind = SegmentKind.LOOP,
    val pass: Int = 1,
    val wanted: Int = 1,
    val seconds: Float = 0f,
) {
    companion object {
        val IDLE = MomentProgress(playing = false)
        private val STAGE = Regex("""(?i)\bstage\s*(\d{1,2})\b""")

        /** The author's stage number in an animation's [name] ("Stage 3 · Cowgirl", "..._stage3"), 0 when none. */
        fun stageOf(name: String?): Int {
            if (name.isNullOrEmpty()) return 0
            STAGE.find(name)?.let { return it.groupValues[1].toInt() }
            return Regex("""(?i)stage_?(\d{1,2})""").find(name)?.groupValues?.get(1)?.toInt() ?: 0
        }
    }
}

/**
 * How intense a moment is, 0..1: what the body language (the breathing's rate and depth), the sound and the light follow.
 * Idle 0.2; a solo animation 0.3; a two-person one from 0.4 at its first stage up by [PER_STAGE] a stage (to 0.85), a
 * start a little under its loop, the loops climbing a little with each pass and with time, an end easing back; the flow's
 * own phases ([FlowPhase]) set it in the lead-in (0.3), undressing (0.4) and the afterglow (0.15); [tempo] (the player's
 * Slower and Faster, -2..2) moves it [PER_TEMPO] a step.
 */
object IntensityModel {
    const val IDLE = 0.2f
    const val SOLO = 0.3f
    const val FIRST_STAGE = 0.4f
    const val PER_STAGE = 0.07f
    const val STAGE_CAP = 0.85f
    const val PER_PASS = 0.03f
    const val PASS_CAP = 0.12f
    /** Seconds over which a long animation climbs [TIME_CAP] more. */
    const val TIME_RAMP_S = 120f
    const val TIME_CAP = 0.08f
    const val PER_TEMPO = 0.08f
    const val LEAD_IN = 0.3f
    const val UNDRESS = 0.4f
    const val AFTERGLOW = 0.15f

    fun of(p: MomentProgress, phase: FlowPhase = FlowPhase.NONE, tempo: Int = 0): Float {
        val base = when (phase) {
            FlowPhase.LEAD_IN -> LEAD_IN
            FlowPhase.UNDRESS -> UNDRESS
            FlowPhase.AFTERGLOW, FlowPhase.DONE -> AFTERGLOW
            FlowPhase.NONE, FlowPhase.STAGE -> moment(p)
        }
        val t = if (phase == FlowPhase.AFTERGLOW || phase == FlowPhase.DONE) 0f else tempo.coerceIn(-2, 2) * PER_TEMPO
        return (base + t).coerceIn(0f, 1f)
    }

    private fun moment(p: MomentProgress): Float {
        if (!p.playing) return IDLE
        if (!p.partnered) return SOLO
        val stage = (FIRST_STAGE + PER_STAGE * (p.stage.coerceAtLeast(1) - 1)).coerceAtMost(STAGE_CAP)
        val climb = (PER_PASS * (p.pass.coerceAtLeast(1) - 1)).coerceAtMost(PASS_CAP) +
            TIME_CAP * (p.seconds.coerceAtLeast(0f) / TIME_RAMP_S).coerceAtMost(1f)
        return when (p.kind) {
            SegmentKind.START -> stage - 0.05f
            SegmentKind.LOOP -> stage + climb
            SegmentKind.END -> stage * 0.75f
        }.coerceIn(0f, 1f)
    }
}

/**
 * The intensity eased toward its goal: it rises with a time constant of [RISE_S] and falls more slowly ([FALL_S]), so a
 * change of stage swells and a stop settles, never a jump. [step] by real seconds.
 */
class IntensityEase(var value: Float = IntensityModel.IDLE) {
    companion object {
        const val RISE_S = 2.5f
        const val FALL_S = 5f
    }

    fun step(goal: Float, dt: Float): Float {
        val g = goal.coerceIn(0f, 1f)
        val d = if (dt.isFinite()) dt.coerceIn(0f, 0.5f) else 0f
        val tau = if (g > value) RISE_S else FALL_S
        value = g + (value - g) * exp(-d / tau)
        return value
    }
}

/**
 * The minimal HUD of immersive mode: hidden until a tap ([tap]), then shown for [holdMs] and faded out over [FADE_MS]
 * (its [alpha] at a time); any touch on it or a window open keeps it ([hold]). Pure: the caller gives the time.
 */
class HudReveal(var holdMs: Long = 4_000L) {
    companion object {
        const val FADE_MS = 400L
    }

    private var shownAt = Long.MIN_VALUE
    private var held = false

    /** A tap on the view at [nowMs]: shown from now (again from now when it was already). */
    fun tap(nowMs: Long) { shownAt = nowMs }

    /** Kept up while [on] (a window or menu over the scene); let go, it hides [holdMs] later. */
    fun hold(on: Boolean, nowMs: Long) {
        if (held && !on) shownAt = nowMs
        held = on
    }

    /** Hidden at once (cinematic mode starting, the scene ending). */
    fun hide() { shownAt = Long.MIN_VALUE; held = false }

    /** 0..1: the HUD's opacity at [nowMs]. */
    fun alpha(nowMs: Long): Float {
        if (held) return 1f
        if (shownAt == Long.MIN_VALUE) return 0f
        val t = nowMs - shownAt
        return when {
            t < 0 -> 1f
            t <= holdMs -> 1f
            t >= holdMs + FADE_MS -> 0f
            else -> 1f - (t - holdMs).toFloat() / FADE_MS
        }
    }

    fun visible(nowMs: Long): Boolean = alpha(nowMs) > 0f

    /** Milliseconds from [nowMs] until the HUD is gone (0: it is; [Long.MAX_VALUE] while held). */
    fun remaining(nowMs: Long): Long = when {
        held -> Long.MAX_VALUE
        shownAt == Long.MIN_VALUE -> 0L
        else -> (shownAt + holdMs + FADE_MS - nowMs).coerceAtLeast(0L)
    }
}

/** The soft controls (a small radial with plain words): what each does is the screen's ([SceneFlow], the camera). */
enum class SoftControl(val label: String) {
    SLOWER("Slower"), FASTER("Faster"), CLOSER("Closer"), POSITION("Change position"), CONTINUE("Continue"), END("End");

    companion object {
        /** The controls offered in [phase] (no Slower/Faster before anything plays, nothing to continue when done). */
        fun offered(phase: FlowPhase): List<SoftControl> = when (phase) {
            FlowPhase.NONE -> listOf(CLOSER, CONTINUE)
            FlowPhase.LEAD_IN, FlowPhase.UNDRESS -> listOf(CLOSER, CONTINUE, END)
            FlowPhase.STAGE -> listOf(SLOWER, FASTER, CLOSER, POSITION, CONTINUE, END)
            FlowPhase.AFTERGLOW -> listOf(CLOSER, CONTINUE, END)
            FlowPhase.DONE -> listOf(CLOSER, END)
        }
    }
}

/**
 * The sound the intensity moves, through the existing mixer's buses (factors on their levels): the music swells from
 * [MUSIC_QUIET] to full as the intensity rises, the room's ambience recedes to [AMBIENCE_LOW], the atmosphere layer (fire
 * crackle) is at [ATMOS_LEVEL] times the Atmosphere setting; the partner sounds' volume follows it by the Voice intensity
 * setting. Pure numbers: Audio and SceneAudio apply them.
 */
object IntensityMix {
    const val MUSIC_QUIET = 0.55f
    const val AMBIENCE_LOW = 0.7f
    const val ATMOS_LEVEL = 0.6f
    const val VOICE_FLOOR = 0.35f

    fun music(intensity: Float): Float = MUSIC_QUIET + (1f - MUSIC_QUIET) * intensity.coerceIn(0f, 1f)
    fun ambience(intensity: Float): Float = 1f - (1f - AMBIENCE_LOW) * intensity.coerceIn(0f, 1f)
    fun atmosphere(setting: Int): Float = ATMOS_LEVEL * setting.coerceIn(0, 100) / 100f

    /** A partner sound's volume factor at [intensity] with the Voice intensity [setting] (0: always 1). */
    fun voice(intensity: Float, setting: Int): Float {
        val k = setting.coerceIn(0, 100) / 100f
        val quiet = VOICE_FLOOR + (1f - VOICE_FLOOR) * intensity.coerceIn(0f, 1f)
        return 1f - k * (1f - quiet)
    }
}

/**
 * Cinematic mode's handheld camera: a small, slow drift of the eye and the point looked at, from three sines of unrelated
 * periods per axis (never a repeat you notice), at most [MAX_YARDS] at the eye and half that at the target times the
 * amount 0..1. Deterministic in time.
 */
object HandheldSway {
    const val MAX_YARDS = 0.035f
    private val periods = floatArrayOf(7.3f, 4.1f, 2.9f, 6.7f, 3.7f, 2.3f, 8.9f, 5.3f, 3.1f)
    private val shares = floatArrayOf(0.55f, 0.3f, 0.15f)

    /** The eye's offset at [seconds] (yards), into [out] (x, y, z; the target's is half of it). */
    fun offset(seconds: Double, amount: Float, out: FloatArray) {
        val a = amount.coerceIn(0f, 1f) * MAX_YARDS
        for (axis in 0 until 3) {
            var s = 0f
            for (k in 0 until 3) {
                val p = periods[axis * 3 + k]
                s += shares[k] * sin((2.0 * PI * seconds / p + axis * 1.7 + k * 0.9).toFloat())
            }
            out[axis] = s * a * if (axis == 2) 0.6f else 1f
        }
    }
}
