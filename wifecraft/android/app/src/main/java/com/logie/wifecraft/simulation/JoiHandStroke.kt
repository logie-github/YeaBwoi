package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.dialogue.JoiSteer
import com.logie.wifecraft.sims.Channel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Pure Kotlin: the procedural hand-stroke layer of JOI mode. She strokes the air in front of her hips, as if giving a handjob
// to someone standing close in front of her (nobody is drawn). The stroke is a bone layer (rotation tracks, the engine's
// bone layer lays them over whatever pose she stands in: cpp engine/bonelayer.h, as the dragon's sway is) made here from a
// two-bone arm solve, a tempo that follows the stage and what she says, and a hip sway at the stroke's rhythm.
// JVM-tested (JoiHandStrokeTest); the pictures are made by tools/JoiHandStrokeRecorder.

/** A point or direction (model axes of the WoW model: x forward, y left, z up). */
data class Pt3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Pt3) = Pt3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Pt3) = Pt3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Pt3(x * k, y * k, z * k)
    fun dot(o: Pt3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Pt3) = Pt3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Double get() = sqrt(dot(this))
    fun unit(): Pt3 { val l = length; return if (l < 1e-12) Pt3(0.0, 0.0, 0.0) else this * (1.0 / l) }
}

/** A rotation (x, y, z, w) as the engine's tracks hold it. */
data class Rot(val x: Double, val y: Double, val z: Double, val w: Double) {
    operator fun times(o: Rot) = Rot(
        w * o.x + x * o.w + y * o.z - z * o.y, w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w, w * o.w - x * o.x - y * o.y - z * o.z)
    fun rotate(v: Pt3): Pt3 {
        val u = Pt3(x, y, z)
        val t = u.cross(v) * 2.0
        return v + t * w + u.cross(t)
    }
    fun dot(o: Rot) = x * o.x + y * o.y + z * o.z + w * o.w
    companion object {
        val IDENTITY = Rot(0.0, 0.0, 0.0, 1.0)
        fun axis(a: Pt3, rad: Double): Rot { val u = a.unit(); val s = sin(rad / 2); return Rot(u.x * s, u.y * s, u.z * s, cos(rad / 2)) }
        /** The shortest rotation taking [from] onto [to] (any lengths). */
        fun arc(from: Pt3, to: Pt3): Rot {
            val a = from.unit(); val b = to.unit()
            val d = a.dot(b).coerceIn(-1.0, 1.0)
            if (d > 1 - 1e-12) return IDENTITY
            if (d < -1 + 1e-9) {
                val any = if (abs(a.x) < 0.9) Pt3(1.0, 0.0, 0.0) else Pt3(0.0, 1.0, 0.0)
                return axis(a.cross(any), PI)
            }
            val c = a.cross(b)
            return Rot(c.x, c.y, c.z, 1 + d).let { q -> val n = sqrt(q.dot(q)); Rot(q.x / n, q.y / n, q.z / n, q.w / n) }
        }
        /** [a] eased toward [b] by [t] (0..1), the short way. */
        fun slerp(a: Rot, b0: Rot, t: Double): Rot {
            var b = b0
            var d = a.dot(b)
            if (d < 0) { b = Rot(-b.x, -b.y, -b.z, -b.w); d = -d }
            if (d > 0.9995) {
                val q = Rot(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, a.w + (b.w - a.w) * t)
                val n = sqrt(q.dot(q)); return Rot(q.x / n, q.y / n, q.z / n, q.w / n)
            }
            val th = acos(d.coerceIn(-1.0, 1.0)); val s = sin(th)
            val wa = sin((1 - t) * th) / s; val wb = sin(t * th) / s
            return Rot(a.x * wa + b.x * wb, a.y * wa + b.y * wb, a.z * wa + b.z * wb, a.w * wa + b.w * wb)
        }
    }
}

/** Which hands stroke. */
enum class StrokeHands { RIGHT, LEFT, BOTH }

/**
 * One arm of the model as it hangs in her idle stand (model space: shoulder, elbow and wrist pivots), measured on the human
 * woman's model with the harness's `bonepos` (idle, standing). Other bodies reach approximately (their arms differ by a few
 * centimetres): the layer is a rotation, so the hand lands near the same place in front of the hips.
 */
data class ArmRig(val shoulder: Pt3, val elbow: Pt3, val wrist: Pt3) {
    val upper: Double get() = (elbow - shoulder).length
    val fore: Double get() = (wrist - elbow).length
}

/** Where the stroke is, relative to her, and how big. */
data class StrokeRig(
    val left: ArmRig, val right: ArmRig,
    /** The grip's centre in front of her (model space), at the pelvis' height: the imagined partner stands just beyond it. */
    val centre: Pt3,
    /** The stroke's direction (unit): forward and up, a shaft rising toward her from a person close in front of her. */
    val axis: Pt3,
    /** Half the gap between the two hands' grips, across her body. */
    val handGap: Double,
) {
    companion object {
        val HUMAN_WOMAN = StrokeRig(
            left = ArmRig(Pt3(-0.022, 0.176, 1.532), Pt3(-0.032, 0.229, 1.243), Pt3(0.012, 0.282, 0.958)),
            right = ArmRig(Pt3(-0.028, -0.157, 1.539), Pt3(-0.034, -0.214, 1.251), Pt3(0.012, -0.272, 0.966)),
            centre = Pt3(0.27, 0.0, 1.12),
            axis = Pt3(0.72, 0.0, 0.69).unit(),
            handGap = 0.045,
        )
    }
}

/** The stage's stroke and the words that change it: strokes per second ("tempo", Hz). */
object HandStrokeTempo {
    const val MAX = 2.6f
    /** Under this a tempo is a stop: the hands stay where they are. */
    const val STOP = 0.12f

    /**
     * The tempo of [stage] (strokes a second): slow and teasing in setup and warm-up, steady in build, fast in the edge and the
     * count, slow at the hold, slower still as she lets them finish, none in the afterglow.
     */
    fun forStage(stage: String): Float = when (stage) {
        "setup" -> 0.45f; "warmup" -> 0.6f; "build" -> 1.0f; "edge" -> 1.7f; "countdown" -> 1.9f
        "hold" -> 0.3f; "permit" -> 0.55f; "afterglow" -> 0f; else -> 0.8f
    }

    /** The stroke's length in the stage (1 the usual): long and slow when slow, short and quick when fast. */
    fun reach(tempo: Float): Float = (1.25f - 0.38f * tempo).coerceIn(0.55f, 1.2f)

    /** The hip sway's share of the stroke (0..1): more with the tempo's middle, none at a stop. */
    fun sway(tempo: Float): Float = if (tempo < STOP) 0f else (0.45f + 0.35f * min(tempo, 1.6f)).coerceIn(0f, 1f)

    private val SLOWER = listOf("slower", "slow down", "slowly", "gentler", "gently", "easy now", "ease off", "ease up", "softly", "lazily")
    private val FASTER = listOf("faster", "quicker", "speed up", "speed it up", "harder", "quickly", "keep up", "pick up the pace", "quick now", "more quickly")
    private val STOPS = listOf("stop", "stay still", "hold still", "don't move", "do not move", "freeze", "wait", "hold it", "not yet", "pause")

    /**
     * What a line of hers asks of the tempo, as a factor on the stage's (1: nothing; 0: stop; under 1 slower; over 1 faster).
     * The last such word in the line wins (she may say "not so fast, slower").
     */
    fun hearing(line: String): Float {
        val t = " " + line.lowercase().replace(Regex("[^a-z' ]"), " ") + " "
        fun last(words: List<String>) = words.maxOf { w -> t.lastIndexOf(" $w").let { i -> if (i < 0) -1 else i } }
        val s = last(SLOWER); val f = last(FASTER); val p = last(STOPS)
        val best = max(s, max(f, p))
        if (best < 0) return 1f
        return when (best) { p -> 0f; s -> 0.7f; else -> 1.35f }
    }

    /** What the viewer's steer chips do to the tempo: Slower slows, Firmer and Tease quicken a little. */
    fun steer(steer: Set<JoiSteer>): Float {
        var k = 1f
        if (JoiSteer.SLOWER in steer) k *= 0.7f
        if (JoiSteer.FIRM in steer) k *= 1.15f
        if (JoiSteer.SOFT in steer) k *= 0.9f
        return k
    }

    /** The tempo in words, for the prompt: "slowly", "steadily", "quickly", "stopped". */
    fun words(tempo: Float): String = when {
        tempo < STOP -> "holding still"
        tempo < 0.5f -> "very slowly"
        tempo < 0.85f -> "slowly"
        tempo < 1.35f -> "steadily"
        tempo < 1.9f -> "quickly"
        else -> "fast and hard"
    }

    /** What she is doing with her hand, as the prompt gives it to her (null: not stroking). */
    fun action(active: Boolean, tempo: Float): String? =
        if (!active) null else if (tempo < STOP) "your hand is holding them, perfectly still" else "your hand is stroking them, ${words(tempo)}"
}

/**
 * The stroke's state: what she is doing now, as the host sees it (one thread). The tempo and the arm weight ease toward their
 * targets ([step], the same maths [bake] runs ahead, so the layer baked from here matches the clock); [generation] changes
 * whenever the plan changes (a new stage, a word of hers, a chip), and the host then bakes the layer again from [phase].
 */
class JoiStroke(var hands: StrokeHands = StrokeHands.BOTH) {
    companion object {
        /** How fast the tempo eases (Hz per second) and the arms rise (weight per second). */
        const val TEMPO_RATE = 0.7f
        const val RISE = 0.9f
        const val FALL = 1.2f
        /** One chunk's length the host bakes ahead (seconds). */
        const val CHUNK = 6f

        /** One step of the easing: ([tempo], [weight], [phase]) after [dt] toward the targets. */
        fun step(tempo: Float, weight: Float, phase: Double, targetTempo: Float, targetWeight: Float, dt: Float): Triple<Float, Float, Double> {
            val dTempo = targetTempo - tempo
            val t = if (abs(dTempo) <= TEMPO_RATE * dt) targetTempo else tempo + TEMPO_RATE * dt * (if (dTempo > 0) 1 else -1)
            val dW = targetWeight - weight
            val rate = if (dW > 0) RISE else FALL
            val w = if (abs(dW) <= rate * dt) targetWeight else weight + rate * dt * (if (dW > 0) 1 else -1)
            // the arms don't stroke before they are up: the phase runs with the tempo, scaled by how far up they are
            val p = phase + t * dt
            return Triple(t, w, p - Math.floor(p))
        }
    }

    var active = false
        private set
    var stage = ""
        private set
    var tempo = 0f
        private set
    var weight = 0f
        private set
    /** The stroke's phase 0..1 (one stroke). */
    var phase = 0.0
        private set
    var generation = 0
        private set
    private var heard = 1f
    private var steered = 1f

    /** The tempo she is easing toward: the stage's, as her words and the chips changed it. */
    val target: Float get() {
        if (!active) return 0f
        val v = HandStrokeTempo.forStage(stage) * heard * steered
        return if (v < HandStrokeTempo.STOP) 0f else v.coerceAtMost(HandStrokeTempo.MAX)
    }
    val targetWeight: Float get() = if (active) 1f else 0f
    /** Whether the layer still has to be on (arms up or rising). */
    val running: Boolean get() = active || weight > 0.001f

    /** Starts stroking in [stage] (a new stroke starts the phase afresh when the arms are down). */
    fun start(stage: String, hands: StrokeHands = this.hands) {
        if (weight < 0.01f) { phase = 0.0; tempo = 0f }
        this.hands = hands
        this.stage = stage; heard = 1f
        active = true; generation++
    }

    /** She stops stroking: the arms ease down. */
    fun stop() { if (active) { active = false; generation++ } }

    /** The stage changed: its own tempo; what she said before no longer counts. */
    fun onStage(stage: String) {
        this.stage = stage; heard = 1f
        if (active) {
            // the afterglow, or a stage without strokes: the hands come down
            if (HandStrokeTempo.forStage(stage) <= 0f) active = false
            generation++
        }
    }

    /** A line she says: "slower" slows the stroke, "faster" quickens it, "stop" holds it. */
    fun hear(line: String) {
        if (!active) return
        val f = HandStrokeTempo.hearing(line)
        if (f == 1f) return
        heard = if (f == 0f) 0f else if (heard == 0f) f else (heard * f).coerceIn(0.5f, 1.7f)
        generation++
    }

    /** The chips' steer. */
    fun steer(set: Set<JoiSteer>) {
        val k = HandStrokeTempo.steer(set)
        if (k != steered) { steered = k; if (active) generation++ }
    }

    /** Advances by [dt] seconds. */
    fun advance(dt: Float) {
        if (dt <= 0f || !running) return
        var left = dt
        while (left > 0f) {
            val d = min(left, 0.05f); left -= d
            val (t, w, p) = step(tempo, weight, phase, target, targetWeight, d)
            tempo = t; weight = w; phase = p
        }
    }

    /** What she is doing in words, for the prompt (null: not stroking). */
    fun action(): String? = HandStrokeTempo.action(active && weight > 0.5f, tempo)

    /** The layer for the next [seconds] (at most [CHUNK] is wise: it holds its last pose after), from the state now. */
    fun bake(seconds: Float = CHUNK, rig: StrokeRig = StrokeRig.HUMAN_WOMAN): Anim =
        HandStroke.bake("joi:handstroke:$generation", seconds, phase, tempo, weight, target, targetWeight, hands, rig)
}

/** The arms' maths and the bake. */
object HandStroke {
    const val FPS = 20

    /** The stroke's offset along its axis at [phase] (-1..1): out and back, a touch of dwell at the ends. */
    fun profile(phase: Double): Double {
        val x = -cos(2 * PI * phase)
        // (a touch of dwell at the ends, odd about the middle so the stroke is as long each way)
        return x + 0.1 * x * (1 - x * x)
    }

    /** The hip sway at [phase] (-1..1): once per stroke, a quarter ahead of the hands. */
    fun hip(phase: Double): Double = sin(2 * PI * phase + 0.4)

    /**
     * The two-bone solve: the elbow for a wrist at [target] from [arm]'s shoulder, bent toward [pole] (the side the elbow
     * points to; any length). A target beyond the arm's reach is brought to its length.
     */
    fun elbow(arm: ArmRig, target: Pt3, pole: Pt3): Pt3 {
        val s = arm.shoulder
        val to = target - s
        val d = to.length.coerceIn(abs(arm.upper - arm.fore) + 1e-4, arm.upper + arm.fore - 1e-4)
        val dir = to.unit()
        val a = (d * d + arm.upper * arm.upper - arm.fore * arm.fore) / (2 * d)
        val h = sqrt(max(0.0, arm.upper * arm.upper - a * a))
        var perp = pole - dir * pole.dot(dir)
        if (perp.length < 1e-6) perp = Pt3(0.0, 0.0, -1.0) - dir * (-dir.z)
        return s + dir * a + perp.unit() * h
    }

    /** One arm's turns at [target] (the wrist), bent toward [pole]: (upper arm about the shoulder, forearm about the elbow). */
    fun armTurns(arm: ArmRig, target: Pt3, pole: Pt3): Pair<Rot, Rot> {
        val e = elbow(arm, target, pole)
        val u0 = (arm.elbow - arm.shoulder); val f0 = (arm.wrist - arm.elbow)
        val upper = Rot.arc(u0, e - arm.shoulder)
        val fore = Rot.arc(upper.rotate(f0), target - e)
        return upper to fore
    }

    /** The side the elbow points to when the arm is rest: away from the line from the shoulder to the wrist. */
    private fun restPole(arm: ArmRig): Pt3 {
        val line = (arm.wrist - arm.shoulder).unit()
        val off = arm.elbow - arm.shoulder
        return off - line * off.dot(line)
    }

    /** The stroking pole: out to the side, down and a little back. */
    private fun strokePole(side: Double) = Pt3(-0.35, 0.55 * side, -0.8)

    /** The grip's place for one hand at [offset] along the stroke (and its sideways gap). */
    fun grip(rig: StrokeRig, side: Double, both: Boolean, offset: Double, reach: Double, amp: Double = 0.10): Pt3 {
        val gap = if (both) rig.handGap * side else rig.handGap * 0.4 * side
        val lift = if (both) 0.045 * (if (side > 0) 1 else -1) else 0.0       // left hand above, right below, along the axis
        return rig.centre + rig.axis * (offset * amp * reach + lift) + Pt3(0.0, gap, 0.0)
    }

    private val FINGER_CURL = listOf("Index1" to 58.0, "Middle1" to 66.0, "Ring1" to 72.0, "Pinky1" to 78.0, "Thumb1" to 28.0)

    /**
     * The bone layer for [seconds] (not a loop: it holds its last pose) from a state ([phase], [tempo], [weight]) easing to
     * ([targetTempo], [targetWeight]). Tracks: the spine (sway), then each stroking arm (upper arm, forearm, hand, fingers);
     * the free arm is left as she stands.
     */
    fun bake(id: String, seconds: Float, phase0: Double, tempo0: Float, weight0: Float, targetTempo: Float, targetWeight: Float,
             hands: StrokeHands, rig: StrokeRig = StrokeRig.HUMAN_WOMAN): Anim {
        val n = max(2, Math.round(seconds * FPS))
        val dt = seconds / n
        val times = FloatArray(n + 1) { it * dt }
        val sides = when (hands) { StrokeHands.RIGHT -> listOf(-1.0); StrokeHands.LEFT -> listOf(1.0); StrokeHands.BOTH -> listOf(1.0, -1.0) }
        val both = hands == StrokeHands.BOTH
        // the tracks in chain order
        val names = ArrayList<String>()
        names += "Spine1"; names += "Chest"
        for (s in sides) {
            val p = if (s > 0) "L_" else "R_"
            names += p + "UpperArm"; names += p + "Forearm"; names += p + "Hand"
            for ((f, _) in FINGER_CURL) names += p + f
        }
        val values = names.map { FloatArray((n + 1) * 4) }
        var tempo = tempo0; var weight = weight0; var phase = phase0
        for (i in 0..n) {
            if (i > 0) { val (t, w, p) = JoiStroke.step(tempo, weight, phase, targetTempo, targetWeight, dt); tempo = t; weight = w; phase = p }
            val w = weight.toDouble()
            val reach = HandStrokeTempo.reach(tempo).toDouble() * (if (tempo < HandStrokeTempo.STOP) 0.0 else 1.0)
            val off = profile(phase) * (if (tempo < HandStrokeTempo.STOP) 0.0 else 1.0)
            val sway = HandStrokeTempo.sway(tempo).toDouble() * w
            val hipv = hip(phase) * sway
            var k = 0
            fun put(q: Rot) { val a = values[k++]; a[i * 4] = q.x.toFloat(); a[i * 4 + 1] = q.y.toFloat(); a[i * 4 + 2] = q.z.toFloat(); a[i * 4 + 3] = q.w.toFloat() }
            // the spine: a lean with the hips' rock (yaw) and a tilt (roll), a few degrees, and a lean toward her work
            val deg = PI / 180
            put(Rot.axis(Pt3(0.0, 0.0, 1.0), 3.2 * deg * hipv) * Rot.axis(Pt3(1.0, 0.0, 0.0), 1.8 * deg * hipv) * Rot.axis(Pt3(0.0, 1.0, 0.0), 3.0 * deg * w))
            put(Rot.axis(Pt3(0.0, 0.0, 1.0), -2.0 * deg * hipv) * Rot.axis(Pt3(0.0, 1.0, 0.0), 2.0 * deg * w))
            for (s in sides) {
                val arm = if (s > 0) rig.left else rig.right
                val g = grip(rig, s, both, off, reach)
                // (the grip is where the hand is; the wrist is a hand's length short of it, along the forearm's way)
                val full = g
                val target = arm.wrist + (full - arm.wrist) * w
                val pole = restPole(arm).unit() * (1 - w) + strokePole(s).unit() * w
                val (upper, fore) = armTurns(arm, target, pole)
                put(upper); put(fore)
                // the hand: follows the forearm, a little down toward the grip, rolled with the stroke
                val foreDir = (target - elbow(arm, target, pole)).unit()
                val twist = Rot.axis(foreDir, (7.0 * deg) * profile(phase + 0.25) * w * s)
                val flex = Rot.axis(Pt3(0.0, 1.0, 0.0), (-(8.0 + 10.0 * profile(phase + 0.5)) * deg) * w)
                put(twist * flex)
                val curl = w
                for ((_, c) in FINGER_CURL) put(fingerCurl(foreDir, s, c * deg * curl))
            }
        }
        val tracks = names.mapIndexed { j, nm -> BoneTrack(nm, Channel.ROTATION, times, values[j]) }
        return Anim(id, "JOI hand stroke", "Stage", seconds, false, tracks, "layer:$id", 0L)
    }

    /** A finger bone's curl by [rad] toward the palm (the palm faces the middle of her body): about the hand's axis crossed with it. */
    private fun fingerCurl(handDir: Pt3, side: Double, rad: Double): Rot {
        val palm = Pt3(0.0, -side, 0.0)
        val axis = handDir.cross(palm)
        return if (axis.length < 1e-6) Rot.IDENTITY else Rot.axis(axis, rad)
    }

    /** The wrist's place at a moment ([phase] 0..1, arms at [weight]) for a checker: [side] 1 left, -1 right. */
    fun gripAt(rig: StrokeRig, hands: StrokeHands, side: Double, phase: Double, tempo: Float): Pt3 =
        grip(rig, side, hands == StrokeHands.BOTH, profile(phase), HandStrokeTempo.reach(tempo).toDouble())
}
