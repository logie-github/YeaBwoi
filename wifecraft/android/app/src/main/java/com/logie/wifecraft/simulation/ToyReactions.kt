package com.logie.wifecraft.simulation

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// Pure Kotlin (JVM-tested: ToyReactionsTest): how she reacts to a toy, as numbers for the engine's layer (engine/toyrig.h) rather than
// canned clips. An arousal level 0..1 rises with insertion activity (strokes, depth, the vibrator) and time, and falls slowly without them;
// from it come the breathing's rate and depth, the chest's flutter, the back's arch, the pelvis' tilt that follows each stroke with a lag,
// the thighs parting and the knees bending, the head tilted back, the shoulders lifted, a hand reaching down to the toy. A sustained high
// level, held for a few seconds, tips into a climax (full-body tension, the back arched, a tremble, then deep breaths) and a cooldown.
// Personality scales it (shy: quieter; bold: stronger) and Options' "Reactions" (Off, Subtle, Natural, Intense) scales all of it. The layer's
// order over the others (the life layers' breathing and gaze, a touch's bone layer, the reach) is engine/toyrig.h's header.

enum class ReactionLevel(val label: String, val gain: Float) {
    OFF("Off", 0f), SUBTLE("Subtle", 0.5f), NATURAL("Natural", 1f), INTENSE("Intense", 1.6f);
    companion object { fun of(key: String?) = entries.firstOrNull { it.name.equals(key, true) } ?: NATURAL }
}

/** The engine's pose numbers in toyrig::Pose's order (radians): see [toArray]. */
data class ToyPoseValues(
    val breath: Float = 0f, val breathHz: Float = 0.25f, val flutter: Float = 0f, val arch: Float = 0f, val hipTilt: Float = 0f,
    val thighPart: Float = 0f, val kneeFlex: Float = 0f, val headBack: Float = 0f, val shoulder: Float = 0f, val tremor: Float = 0f,
    val reachL: Float = 0f, val reachR: Float = 0f,
) {
    fun toArray() = floatArrayOf(breath, breathHz, flutter, arch, hipTilt, thighPart, kneeFlex, headBack, shoulder, tremor, reachL, reachR)
}

/** What the toy is doing this frame. [speed] metres a second (+ inward), [intensity] the vibrator's strength 0..1 (0: off). */
data class ToyActivity(val inserted: Boolean, val depth: Float, val speed: Float, val intensity: Float, val touching: Boolean = false)

/** The result of a step: the pose, her eyes (0 open .. 1 shut) and mouth (0..1), the expression to show (null: leave it), a line to say (null: none). */
data class ToyReaction(val pose: ToyPoseValues, val eyesClosed: Float, val mouthOpen: Float, val expression: Expression?, val line: String?, val climax: Boolean)

class ToyReactions(var level: ReactionLevel = ReactionLevel.NATURAL, boldness: Float = 0f, seed: Long = 1L) {
    /** -1 shy .. +1 bold (Disposition.boldness): shy is quieter, bold stronger. */
    var boldness: Float = boldness.coerceIn(-1f, 1f)
    private val rnd = Random(seed)
    /** The climax option: off, the build-up holds at the edge without tipping over. */
    var climaxEnabled = true

    var arousal = 0f; private set
    private var hip = 0f
    private var clock = 0f
    private var sustain = 0f
    private var climaxT = -1f           // seconds into a climax (-1: none)
    private var cooldown = 0f
    private var lastLine = -100f
    private var lineIndex = 0
    private var eyes = 0f
    val inClimax: Boolean get() = climaxT >= 0f
    val coolingDown: Boolean get() = cooldown > 0f

    fun reset() { arousal = 0f; hip = 0f; sustain = 0f; climaxT = -1f; cooldown = 0f; eyes = 0f }

    private val strength: Float get() = (level.gain * (1f + 0.35f * boldness)).coerceIn(0f, 2.4f)

    /** Advances [dt] seconds with what the toy is doing. */
    fun step(dt: Float, a: ToyActivity, withLines: Boolean = false): ToyReaction {
        val d = dt.coerceIn(0f, 0.1f)
        clock += d
        if (level == ReactionLevel.OFF) { reset(); return ToyReaction(ToyPoseValues(), 0f, 0f, null, null, false) }
        val stroke = min(abs(a.speed) / 0.25f, 1f)
        val activity = (if (a.inserted) 0.35f + 0.65f * stroke else if (a.touching) 0.2f else 0f) + a.intensity * 0.5f
        if (climaxT < 0f) {
            if (activity > 0.2f) arousal += d * RISE * activity * (if (cooldown > 0f) 0.4f else 1f)
            else arousal -= d * FALL
            arousal = arousal.coerceIn(0f, 1f)
            sustain = if (arousal >= CLIMAX_AT && activity > 0.5f) sustain + d else 0f
            if (cooldown > 0f) cooldown = (cooldown - d).coerceAtLeast(0f)
            if (sustain >= CLIMAX_HOLD && cooldown <= 0f && climaxEnabled) { climaxT = 0f; sustain = 0f }
        } else {
            climaxT += d
            if (climaxT >= CLIMAX_SECONDS) { climaxT = -1f; arousal = AFTER_CLIMAX; cooldown = COOLDOWN_SECONDS }
        }
        val s = strength
        // the climax's envelope: tension builds over the first third, trembles at the peak, then lets go
        val c = if (climaxT < 0f) 0f else when {
            climaxT < 1.0f -> climaxT / 1.0f
            climaxT < 2.2f -> 1f
            else -> (1f - (climaxT - 2.2f) / (CLIMAX_SECONDS - 2.2f)).coerceIn(0f, 1f)
        }
        val tremorPeak = if (climaxT in 1.0f..2.6f) 1f else 0f
        val ar = arousal
        // the pelvis follows each stroke with a lag (5 to 12 degrees)
        val hipWant = 0.10f * (a.speed / 0.2f).coerceIn(-1f, 1f) * (0.45f + 0.55f * ar) + 0.03f * ar
        hip += (hipWant - hip) * (1f - exp(-d / 0.15f))
        val after = if (cooldown > 0f) cooldown / COOLDOWN_SECONDS else 0f   // deeper, slower breaths after
        val pose = ToyPoseValues(
            breath = (0.010f + 0.040f * ar + 0.02f * after + 0.012f * c) * s.coerceAtMost(1.8f),
            breathHz = (0.22f + 0.45f * ar + 0.12f * c - 0.08f * after).coerceIn(0.15f, 0.9f),
            flutter = (0.010f * ar * ar + 0.012f * c) * s,
            arch = (0.02f + 0.13f * ar + 0.12f * c) * s,
            hipTilt = (hip + 0.04f * c) * s,
            thighPart = (0.02f + 0.09f * ar + 0.03f * c) * s,
            kneeFlex = (0.03f + 0.09f * ar) * s,
            headBack = (0.03f + 0.17f * ar + 0.08f * c) * s,
            shoulder = (0.02f + 0.06f * ar + 0.03f * c) * s,
            tremor = (min(1f, 0.15f * ar * ar + tremorPeak) * min(s, 1.4f)).coerceIn(0f, 1f),
            reachL = if (level == ReactionLevel.SUBTLE) 0f else smooth(0.22f, 0.4f, ar) * (1f - 0.7f * c),
            reachR = if (level == ReactionLevel.SUBTLE) 0f else smooth(0.6f, 0.75f, ar) * (if (boldness > -0.3f) 1f else 0f) * (1f - 0.7f * c),
        )
        // her eyes close or roll back during strong strokes, flutter at the peak; her lips part
        val eyesWant = (smooth(0.35f, 0.7f, ar) * (0.35f + 0.65f * stroke) + c).coerceIn(0f, 1f)
        eyes += (eyesWant - eyes) * (1f - exp(-d / 0.2f))
        val flutterEyes = if (climaxT >= 1f) 0.12f * sin(clock * 38f) else if (ar > 0.7f) 0.05f * sin(clock * 21f) else 0f
        val mouth = (smooth(0.3f, 0.8f, ar) * 0.6f + 0.3f * c).coerceIn(0f, 1f)
        val expr = when {
            ar >= 0.7f || c > 0f -> Expression.TEASE
            ar >= 0.3f -> Expression.BLUSH
            else -> null
        }
        var line: String? = null
        if (withLines && a.inserted && clock - lastLine >= LINE_GAP) {
            val l = pickLine(ar, c > 0f)
            if (l != null) { line = l; lastLine = clock }
        }
        return ToyReaction(pose, (eyes + flutterEyes).coerceIn(0f, 1f), mouth, expr, line, climaxT >= 0f)
    }

    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    private fun pickLine(ar: Float, climax: Boolean): String? {
        val pool = when {
            climax -> CLIMAX_LINES
            ar >= 0.7f -> STRONG_LINES
            ar >= 0.35f -> MEDIUM_LINES
            ar >= 0.1f -> SOFT_LINES
            else -> return null
        }
        val shy = boldness < -0.3f
        if (shy && rnd.nextFloat() < 0.5f) return null
        return pool[(lineIndex++ + rnd.nextInt(pool.size)) % pool.size]
    }

    companion object {
        const val RISE = 0.05f            // arousal a second at full activity: about 20 s of steady strokes from nothing to the edge
        const val FALL = 0.012f
        const val CLIMAX_AT = 0.92f
        const val CLIMAX_HOLD = 3.0f      // seconds at the edge, still stroking, before it tips over
        const val CLIMAX_SECONDS = 4.0f
        const val COOLDOWN_SECONDS = 14.0f
        const val AFTER_CLIMAX = 0.32f
        const val LINE_GAP = 6.0f         // a short line every few seconds at most
        private val SOFT_LINES = listOf("Mm...", "Oh.", "Slowly...")
        private val MEDIUM_LINES = listOf("Don't stop.", "Yes, like that.", "Ah... deeper.")
        private val STRONG_LINES = listOf("Oh god, yes...", "Don't you dare stop.", "More. Please.")
        private val CLIMAX_LINES = listOf("I'm... ah!", "Yes, yes, right there...", "Oh!")
    }
}
