package com.logie.wifecraft.simulation

import com.logie.wifecraft.dialogue.ChatClean
import com.logie.wifecraft.dialogue.ChatLength
import com.logie.wifecraft.dialogue.DialogueRequest
import com.logie.wifecraft.dialogue.DialoguePriority
import com.logie.wifecraft.dialogue.DirtyLevel
import com.logie.wifecraft.dialogue.JoiPrompt
import com.logie.wifecraft.dialogue.StreamGuard
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

// Pure Kotlin (JVM-tested: StockPinchTest): Interact mode's fabric interaction, the logic behind the pinch on a stocking. The solver is
// engine/stockfabric.cpp (tension 0..1, a run that tears, springback); this file is what the screen does with it: the gesture's state
// machine (hold, probe, consent, grab, drag, release), the status and the gauge, the panel's options and how they reach the solver, the
// reactions by tension and personality, and the feedback (haptic ticks by tension, a long buzz at the tear) that the sound shares.

/** What the fabric is doing now, as the status line says it. */
enum class FabricStatus(val label: String) { INTACT("Fabric intact"), STRETCHING("Stretching"), TORN("Torn") }

/** The solver's state (Native.stockState: tension 0..1, runs torn, dead triangles, grabbed 1/0). */
data class FabricState(val tension: Float, val runs: Int, val dead: Int, val grabbed: Boolean) {
    companion object {
        val REST = FabricState(0f, 0, 0, false)
        fun of(a: FloatArray?): FabricState? =
            if (a == null || a.size < 4) null else FabricState(a[0].coerceIn(0f, 1f), a[1].toInt().coerceAtLeast(0), a[2].toInt().coerceAtLeast(0), a[3] > 0.5f)
    }
}

object FabricGauge {
    /** Below this the yarn is slack: nothing shows. */
    const val STRETCH_FROM = 0.06f
    /** The tension where she reacts to the strain, and where she is alarmed. */
    const val STRAIN_AT = 0.45f
    const val ALARM_AT = 0.8f

    fun percent(t: Float): Int = (t.coerceIn(0f, 1f) * 100f + 0.5f).toInt()

    /** The status: "Stretching" while pulled or still tight; "Torn" when a run is there and nothing is pulling; else "Fabric intact". */
    fun status(s: FabricState): FabricStatus = when {
        s.tension >= STRETCH_FROM || (s.grabbed && s.tension > 0f) -> FabricStatus.STRETCHING
        s.runs > 0 -> FabricStatus.TORN
        else -> FabricStatus.INTACT
    }

    /** The gauge's colour band: 0 calm, 1 tight, 2 about to give. */
    fun zone(t: Float): Int = when { t >= ALARM_AT -> 2; t >= STRAIN_AT -> 1; else -> 0 }

    /** The gauge's line under the sheet: "Stretching 62%" or "Torn: 2 runs". */
    fun line(s: FabricState): String = when (status(s)) {
        FabricStatus.STRETCHING -> "Stretching ${percent(s.tension)}%"
        FabricStatus.TORN -> "Torn: ${s.runs} ${if (s.runs == 1) "run" else "runs"}"
        FabricStatus.INTACT -> "Fabric intact"
    }
}

/**
 * The Stockings panel's options (kept in the app's settings): the elasticity, the side light, the fabric sound (default off) and the haptics
 * (default on). The denier is the stocking's own ([com.logie.wifecraft.creator.StockingSpec.denier], 8 to 80), kept per NPC.
 */
data class StockOptions(val elasticity: Int = 60, val sideLight: Boolean = false, val sound: Boolean = false, val haptics: Boolean = true) {
    fun toJson(): JSONObject = JSONObject().put("v", 1).put("elasticity", elasticity).put("side", sideLight).put("sound", sound).put("haptics", haptics)

    /**
     * What the engine takes (Native.stockLook): elasticity 0..1 (the solver's own), the strain the yarn tears at (a heavier denier is a stronger
     * yarn: 8 D about 26%, 20 D 31%, 80 D 58%; a stretchier knit gives a little further), the Fresnel sheen (the sheerer, the glossier) and the
     * side light 0 or 1.
     */
    fun look(denier: Int): FloatArray {
        val d = denier.coerceIn(MIN_DENIER, MAX_DENIER)
        val tear = 0.22f + 0.0045f * d + 0.0006f * (elasticity.coerceIn(0, 100) - 60)
        val sheen = (0.9f - 0.8f * (d - MIN_DENIER) / (MAX_DENIER - MIN_DENIER).toFloat()).coerceIn(0.05f, 0.9f)
        return floatArrayOf(elasticity.coerceIn(0, 100) / 100f, tear.coerceIn(0.1f, 0.9f), sheen, if (sideLight) 1f else 0f)
    }

    companion object {
        const val MIN_DENIER = 8
        const val MAX_DENIER = 80
        fun fromJson(text: String?): StockOptions {
            if (text.isNullOrBlank()) return StockOptions()
            return runCatching {
                val o = JSONObject(text)
                StockOptions(o.optInt("elasticity", 60).coerceIn(0, 100), o.optBoolean("side", false), o.optBoolean("sound", false), o.optBoolean("haptics", true))
            }.getOrDefault(StockOptions())
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------- the gesture

/**
 * The pinch's state machine. A hold on her stocking (the layer arms it after [HOLD_MS] without moving, so a quick swipe stays a stroke) probes
 * whether there is stocking under the finger, asks the consent gate ([Step.Ask]: the pull is a [TouchKind.PULL]), grabs, drags in the view
 * plane and lets go. A refusal never grabs: the finger is dead until it lifts.
 */
class PinchGesture {
    enum class Phase { IDLE, ARMED, COVER, ASKED, GRABBING, HELD, REFUSED }

    sealed class Step {
        object None : Step()
        /** Ask the engine whether there is stocking at the finger ([x], [y]); answer with [covered]. */
        data class Probe(val x: Float, val y: Float) : Step()
        /** There is stocking there: the screen asks the consent gate for [hit] and answers with [verdict]. */
        data class Ask(val hit: TouchHit) : Step()
        /** Take hold of the fabric at [x], [y]; answer with [grabbed]. */
        data class Grab(val x: Float, val y: Float) : Step()
        /** She does not allow it ([verdict]): nothing was grabbed. */
        data class Refused(val verdict: Verdict) : Step()
        data class Drag(val x: Float, val y: Float) : Step()
        object Release : Step()
        /** Not a pull after all (no stocking there, or the grab found none): the touch is an ordinary one. */
        object NotPull : Step()
    }

    var phase = Phase.IDLE; private set
    var x = 0f; private set
    var y = 0f; private set
    private var hit: TouchHit? = null
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastMs = 0L
    private var up = false

    val active: Boolean get() = phase != Phase.IDLE
    /** The finger is carrying fabric. */
    val held: Boolean get() = phase == Phase.HELD

    /** The finger has been still on [hit] for the hold time at [px], [py]: starts the probe (only when [enabled]: the Touch tool, stockings on, a covered part). */
    fun hold(hit: TouchHit?, px: Float, py: Float, enabled: Boolean): Step {
        if (phase != Phase.IDLE || !enabled || hit == null || hit.region !in COVERED) return Step.None
        this.hit = hit; x = px; y = py; lastX = px; lastY = py; up = false
        phase = Phase.COVER
        return Step.Probe(px, py)
    }

    /** The engine's answer to [Step.Probe]. */
    fun covered(yes: Boolean): Step {
        if (phase != Phase.COVER) return Step.None
        if (!yes || up) return end(Step.NotPull)
        phase = Phase.ASKED
        return Step.Ask(hit ?: return end(Step.NotPull))
    }

    /** The consent gate's verdict on the pull: ALLOW grabs, anything else ends it (a refusal, a hesitation). */
    fun verdict(v: Verdict): Step {
        if (phase != Phase.ASKED) return Step.None
        if (up) return end(Step.NotPull)
        if (v != Verdict.ALLOW) { phase = Phase.REFUSED; return Step.Refused(v) }
        phase = Phase.GRABBING
        return Step.Grab(x, y)
    }

    /** The engine's answer to [Step.Grab]. A finger that lifted meanwhile lets go at once; one that moved gets its drag. */
    fun grabbed(ok: Boolean): Step {
        if (phase != Phase.GRABBING) return Step.None
        if (!ok) return end(Step.NotPull)
        if (up) return end(Step.Release)
        phase = Phase.HELD
        return Step.Drag(lastX, lastY)
    }

    /** The finger moved to [px], [py] at [ms]: a drag step (not more often than every [MIN_MS], not for under a pixel). */
    fun move(px: Float, py: Float, ms: Long): Step {
        lastX = px; lastY = py
        if (phase != Phase.HELD) return Step.None
        if (ms - lastMs < MIN_MS || (abs(px - x) < 0.75f && abs(py - y) < 0.75f)) return Step.None
        x = px; y = py; lastMs = ms
        return Step.Drag(px, py)
    }

    /** The finger lifted (or a second finger came, or the screen took the touch away): the fabric lets go. */
    fun up(): Step {
        up = true
        return when (phase) {
            Phase.HELD -> end(Step.Release)
            Phase.GRABBING, Phase.COVER, Phase.ASKED -> Step.None   // ended by the answer in flight
            Phase.REFUSED, Phase.ARMED -> end(Step.None)
            Phase.IDLE -> Step.None
        }
    }

    private fun end(s: Step): Step { phase = Phase.IDLE; hit = null; return s }

    companion object {
        /** How long the finger rests before the pinch takes hold (a quick swipe stays a stroke). */
        const val HOLD_MS = 190L
        const val MIN_MS = 12L
        /** The parts a stocking can be on (the engine says whether it is there). */
        val COVERED = setOf(TouchRegion.HIP, TouchRegion.BUTT, TouchRegion.THIGH, TouchRegion.KNEE, TouchRegion.CALF, TouchRegion.FOOT, TouchRegion.BELLY)
    }
}

// ---------------------------------------------------------------------------------------------------------------- events and feedback

/** One thing that happened while the fabric was pulled: what the screen answers with a reaction, a haptic pattern or a sound. */
sealed class PinchEvent {
    /** The tension rose through the tick level [level] (1..10: every tenth). */
    data class Tick(val level: Int, val tension: Float) : PinchEvent()
    /** She reacts at [stage] (once per pull). */
    data class Stage(val stage: PinchStage) : PinchEvent()
    /** A run started (the stocking tore). */
    object Tear : PinchEvent()
    /** The finger let go with the fabric under strain: the springback. */
    data class Released(val fromTension: Float) : PinchEvent()
}

enum class PinchStage { STRAIN, ALARM, TEAR }

/** Reads the solver's state each poll and says what happened since the last one (one per pull: made afresh when the finger takes hold). */
class PinchTracker {
    private var tension = 0f
    private var runs = 0
    private var wasGrabbed = false
    private var tick = 0
    private var strain = false
    private var alarm = false
    /** The rise in tension per second since the last poll (the friction's pitch and loudness follow it). */
    var slope = 0f; private set
    private var lastMs = 0L

    fun begin(runsNow: Int) { tension = 0f; runs = runsNow; wasGrabbed = true; tick = 0; strain = false; alarm = false; slope = 0f; lastMs = 0L }

    fun update(s: FabricState, ms: Long): List<PinchEvent> {
        val out = ArrayList<PinchEvent>(3)
        val dt = if (lastMs == 0L) 0f else (ms - lastMs).coerceAtLeast(1L) / 1000f
        slope = if (dt > 0f) ((s.tension - tension) / dt).coerceIn(-8f, 8f) else 0f
        lastMs = ms
        if (s.grabbed) {
            val level = floor(s.tension * 10f).toInt().coerceIn(0, 10)
            while (tick < level) { tick++; out += PinchEvent.Tick(tick, s.tension) }
            if (level < tick) tick = level   // slackening: the ticks come again when it is pulled on
            if (!strain && s.tension >= FabricGauge.STRAIN_AT) { strain = true; out += PinchEvent.Stage(PinchStage.STRAIN) }
            if (!alarm && s.tension >= FabricGauge.ALARM_AT) { alarm = true; out += PinchEvent.Stage(PinchStage.ALARM) }
        }
        if (s.runs > runs) { out += PinchEvent.Tear; out += PinchEvent.Stage(PinchStage.TEAR); tick = 0; alarm = false; strain = false }
        runs = s.runs
        if (wasGrabbed && !s.grabbed && tension > 0.15f) out += PinchEvent.Released(tension)
        wasGrabbed = s.grabbed
        tension = s.tension
        return out
    }
}

object PinchFeedback {
    /** The haptic tick for tension level [level] (1..10): short, stronger as it tightens. */
    fun tick(level: Int): HapticPattern = HapticPattern(longArrayOf(7), intArrayOf((28 + level * 20).coerceIn(1, 255)))

    /** The long buzz when it tears: a hard hit, a gap and a rolling buzz. */
    fun tear(): HapticPattern = HapticPattern(longArrayOf(40, 25, 140), intArrayOf(255, 0, 170))

    /** No vibration of its own: a reaction's haptic while the pull's own events (ticks, the tear's buzz) already drive the motor. */
    val NONE = HapticPattern(longArrayOf(1), intArrayOf(1))

    /** The soft snap of the springback after a stretch (stronger from a higher tension). */
    fun springback(from: Float): HapticPattern = HapticPattern(longArrayOf(14), intArrayOf((50 + 120 * from.coerceIn(0f, 1f)).toInt().coerceIn(1, 255)))

    /** The pattern an event asks for (null: none; the setting's switch is the caller's). */
    fun of(e: PinchEvent): HapticPattern? = when (e) {
        is PinchEvent.Tick -> tick(e.level)
        PinchEvent.Tear -> tear()
        is PinchEvent.Released -> springback(e.fromTension)
        is PinchEvent.Stage -> null
    }
}

// ---------------------------------------------------------------------------------------------------------------- her reaction

/** What she does about a pull: the posture/line [reaction], and whether she looks down at it. */
data class PinchReaction(val reaction: Reaction, val lookDown: Boolean)

object PinchReactions {
    private fun hitOf(region: TouchRegion, side: Int) = TouchHit(region, GarmentSlot.LEGS, side, true, 0.5f)

    /**
     * Her reaction at [stage], by her nature: at the strain a gasp or a giggle (the bold and curious laugh, the shy catch their breath and
     * blush), alarmed she flinches or steps back, at the tear it is stronger (a step back, a look down, a line). The lines follow the
     * dirty talk level: Cute and Mild stay sweet; from Spicy a tear may be said plainly.
     */
    fun react(stage: PinchStage, region: TouchRegion, persona: InteractPersona, rnd: Random, side: Int = 0): PinchReaction {
        val d = persona.disposition
        val hit = hitOf(region, side)
        val kind = when (stage) {
            PinchStage.STRAIN -> if (d.boldness + d.curiosity > d.caution + 0.2f) ReactionKind.GIGGLE else if (d.caution > 0.5f) ReactionKind.BLUSH else ReactionKind.FLINCH
            PinchStage.ALARM -> if (d.caution + 0.2f > d.boldness) ReactionKind.STEP_BACK else ReactionKind.FLINCH
            PinchStage.TEAR -> if (d.boldness > 0.55f && d.caution < 0.4f) ReactionKind.FLINCH else ReactionKind.STEP_BACK
        }
        val strong = stage == PinchStage.TEAR
        val step = when (kind) { ReactionKind.STEP_BACK -> if (strong) 0.5f else 0.3f; ReactionKind.FLINCH -> if (strong) 0.14f else 0.07f; else -> 0f }
        val haptic = PinchFeedback.NONE
        val speak = when (stage) { PinchStage.STRAIN -> 0.45f; PinchStage.ALARM -> 0.75f; PinchStage.TEAR -> 1f }
        val line = if (rnd.nextFloat() < speak) line(stage, persona, rnd) else null
        val expr = when (kind) { ReactionKind.GIGGLE -> if (d.boldness > 0.3f) Expression.TEASE else Expression.SMILE; ReactionKind.BLUSH, ReactionKind.FLINCH -> Expression.SHY; else -> Expression.BLUSH }
        val r = Reaction(kind, Verdict.ALLOW, expr, if (kind == ReactionKind.STEP_BACK) -0.3f else 0f, step, line, haptic, if (strong) 3.2f else 2.0f)
        return PinchReaction(r, lookDown = strong && d.caution + 0.15f > d.boldness)
    }

    private val STRAIN: Map<String, List<String>> = mapOf(
        "shy" to listOf("Oh! Careful...", "That's... tight.", "Easy, please."),
        "bold" to listOf("Mm, pull harder and see.", "Careful with those.", "Oh, you're strong."),
        "tender" to listOf("Gently, love.", "Oh... slowly.", "Mm, careful."),
        "dominant" to listOf("Careful. Those are good ones.", "Mind what you pull.", "Gently, I said."),
        "natural" to listOf("Hey, careful!", "Oh, that's tight.", "Easy now."),
    )
    private val ALARM: Map<String, List<String>> = mapOf(
        "shy" to listOf("Wait, they'll rip!", "P-please, they're going to tear.", "Stop, stop..."),
        "bold" to listOf("They're about to go. Your call.", "Pull any further and they're gone.", "You wouldn't."),
        "tender" to listOf("Love, they'll tear...", "Oh, careful now, careful.", "That's as far as they go."),
        "dominant" to listOf("Another inch and you'll answer for it.", "Careful.", "Stop there."),
        "natural" to listOf("They're going to rip!", "Careful, that's enough.", "Easy, easy."),
    )
    private val TEAR_SOFT: Map<String, List<String>> = mapOf(
        "shy" to listOf("Oh no... they've torn.", "A ladder! Look what you did.", "Oh! They're ruined."),
        "bold" to listOf("There. Happy now?", "Well. You tore them.", "That's a ladder. You owe me a pair."),
        "tender" to listOf("Oh, my stockings...", "Oh dear, a ladder.", "They've torn, love."),
        "dominant" to listOf("You tore them. You'll replace them.", "That was a mistake.", "A ladder. Remember that."),
        "natural" to listOf("Oh, they've torn!", "There goes a good pair.", "Well, that's a ladder."),
    )
    private val TEAR_HOT: Map<String, List<String>> = mapOf(
        "shy" to listOf("You tore them... I felt that.", "Oh. Oh my. They're open now."),
        "bold" to listOf("Mm. You tore them. Do it again.", "Rough hands. I like it."),
        "tender" to listOf("You tore them... don't stop looking.", "Mm, that made me shiver."),
        "dominant" to listOf("Good. Now finish what you started.", "You tore them. Keep going."),
        "natural" to listOf("You tore them, did you? Hm.", "Mm, rough. I like that."),
    )

    /** One line at [stage] in her style (the tear's follows the dirty talk level). */
    fun line(stage: PinchStage, persona: InteractPersona, rnd: Random): String? {
        val style = persona.disposition.style
        val table = when (stage) {
            PinchStage.STRAIN -> STRAIN
            PinchStage.ALARM -> ALARM
            PinchStage.TEAR -> if (persona.dirty >= DirtyLevel.SPICY) TEAR_HOT else TEAR_SOFT
        }
        val pool = table[style] ?: table.getValue("natural")
        return pool[rnd.nextInt(pool.size)]
    }
}

/** Her spoken reaction through the dialogue model when one is loaded (the templates above are the fallback): the request, and the answer made into a line. */
object PinchModelLine {
    /** One short, in-character line at [stage]; the model is told what happened and her nature, never asked to describe the act itself. */
    fun request(npcId: String, name: String, persona: InteractPersona, stage: PinchStage, region: TouchRegion): DialogueRequest {
        val d = persona.disposition
        val what = when (stage) {
            PinchStage.STRAIN -> "is pulling at the stockings on your ${region.label}: they stretch tight"
            PinchStage.ALARM -> "is pulling your stockings on your ${region.label} so hard they are about to tear"
            PinchStage.TEAR -> "just tore the stocking on your ${region.label}: a ladder runs up it"
        }
        val system = "You are $name, a ${d.style} woman in a fantasy world, and you allowed this touch. Answer with one short spoken line in character, " +
            "at most 14 words, no narration, no actions in asterisks. Talk level: ${persona.dirty.label}: ${persona.dirty.blurb.lowercase()}."
        return DialogueRequest(npcId = "interact:$npcId", system = system, user = "The player $what. What do you say?", maxTokens = ChatLength.SHORT.tokens,
            temperature = persona.dirty.temperature, topP = persona.dirty.topP, repeatPenalty = persona.dirty.repeatPenalty, priority = DialoguePriority.SELECTED,
            tag = "speech", sentences = 1)
    }

    /** The model's answer as a line to say (null: nothing usable, or it names something beyond the world: the template line is used). */
    fun line(raw: String?, name: String): String? {
        val l = ChatClean.clean(raw, name, ChatLength.SHORT) ?: return null
        return l.takeIf { !JoiPrompt.breaksWorld(it) && StreamGuard.problem(it) == null }
    }
}

/** Where she walks to for Come here and Follow me: the orbit camera is fixed to her, so the spot is on the floor [STAND] yards from the camera, on the line to her. */
object InteractFollow {
    const val STAND = 1.0f
    /** Nearer than this to the camera she is already there. */
    const val ALREADY = 1.35f

    /** The floor point (x, y) for a camera eye at ([eyeX], [eyeY]) and her at ([herX], [herY]); null when she is already near the camera or nothing is finite. */
    fun standPoint(eyeX: Float, eyeY: Float, herX: Float, herY: Float): Pair<Float, Float>? {
        if (!(eyeX.isFinite() && eyeY.isFinite() && herX.isFinite() && herY.isFinite())) return null
        val dx = herX - eyeX; val dy = herY - eyeY
        val d = kotlin.math.hypot(dx, dy)
        if (d <= ALREADY) return null
        return (eyeX + dx / d * STAND) to (eyeY + dy / d * STAND)
    }
}
