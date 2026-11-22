package com.logie.wifecraft.simulation

import com.logie.wifecraft.dialogue.Band
import com.logie.wifecraft.dialogue.JoiSteer
import kotlin.random.Random

// Pure Kotlin (no Android, no Compose): JOI mode's state machine, stage pacing and subtitles, JVM-tested (unit-tests/.../
// simulation/JoiModeTest.kt). The selected NPC gives a JOI to someone in the room (the viewer only watches): the stage order and
// countdown come from the KinkPack ([JoiMode] takes them as functions), the words from the dialogue engine ([JoiPrompt]),
// and this class decides when each is due: when the next line is asked for, how long a subtitle stays, when a solo animation
// is due, how the countdown beats, and what happens when the model is missing.

/** The three framings of the film frame, and Auto (mixes them). */
enum class JoiFraming(val label: String) {
    AUTO("Auto"), FACE("Face"), TORSO("Torso"), LEGS("Legs up");
    companion object { val SHOWN = entries.toList() }
}

enum class JoiPhase { OFF, NEEDS_MODEL, RUNNING, PAUSED }

/** What was on before JOI mode (cinematic mode and the camera), put back when it is left. */
data class JoiPrior(val cinematic: Boolean, val cameraMode: String = "", val cameraView: Int = -1)

/** What the host (the screen) must do. */
sealed class JoiEvent {
    /** Ask the engine for the next line of [stage] (answer with [JoiMode.offerLine] and this [serial]); [countdownFrom]: the count to come. */
    data class NeedLine(val stage: String, val serial: Int, val countdownFrom: Int?) : JoiEvent()
    /**
     * A solo animation is due ([JoiAnimationPicker.pick] with this [energy], 0..1, the [stage]'s); [stageLeft]: seconds the stage
     * has left (a handjob runs as long: [JoiHandjobs.runSeconds]).
     */
    data class AnimationDue(val energy: Float, val stage: String, val stageLeft: Float = 0f) : JoiEvent()
    data class StageStarted(val stage: String, val index: Int, val of: Int) : JoiEvent()
    /** The count's number now ([n] from the start down to 1). */
    data class CountdownBeat(val n: Int) : JoiEvent()
    /** The plan is over: the NPC stops; the mode ends. */
    object Finished : JoiEvent()
    /** The model gave nothing three times running: the mode waits ([JoiPhase.NEEDS_MODEL]). */
    object ModelLost : JoiEvent()
}

/** Subtitles: queued cues shown one at a time in the film frame's lower third, each for a time by its length and the stage's pace. */
class JoiSubtitles {
    data class Cue(val text: String, val seconds: Float, val number: Boolean = false, val gapAfter: Float = 0f)

    private val queue = ArrayDeque<Cue>()
    var current: Cue? = null
        private set
    /** Seconds the current cue has been shown. */
    var shown = 0f
        private set
    private var gap = 0f

    val text: String? get() = current?.text
    val isNumber: Boolean get() = current?.number == true
    /** Seconds left of the current cue (0: none shown). */
    val remaining: Float get() = current?.let { (it.seconds - shown).coerceAtLeast(0f) } ?: 0f
    val queued: Int get() = queue.size
    val idle: Boolean get() = current == null && queue.isEmpty()

    fun push(cue: Cue) { queue.addLast(cue) }

    fun clear() { queue.clear(); current = null; shown = 0f; gap = 0f }

    fun advance(dt: Float) {
        if (dt <= 0f) return
        val c = current
        if (c != null) {
            shown += dt
            if (shown >= c.seconds) { current = null; shown = 0f; gap = c.gapAfter }
            return
        }
        if (gap > 0f) { gap -= dt; if (gap > 0f) return }
        queue.removeFirstOrNull()?.let { current = it; shown = 0f }
    }

    companion object {
        /** How long [text] is shown: its reading time, longer on a slow stage, never under [MIN] nor over [MAX] seconds. */
        fun hold(text: String, pace: String): Float {
            val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
            val base = 0.9f + words * 0.40f
            val f = when (pace) { "slow" -> 1.25f; "hold" -> 1.3f; "release" -> 1.15f; "counted" -> 1f; else -> 1f }
            return (base * f).coerceIn(MIN, MAX)
        }
        /** The quiet between two lines (seconds) by the stage's pace. */
        fun gap(pace: String): Float = when (pace) { "slow" -> 1.8f; "hold" -> 2.6f; "release" -> 1.5f; "counted" -> 0.3f; else -> 1.0f }
        const val MIN = 2.2f
        const val MAX = 9.0f
    }
}

/** The stages' pacing: how long each lasts at least, its energy for the animations, how often an animation is due. */
object JoiPacing {
    val DEFAULT_PACES = mapOf("setup" to "slow", "warmup" to "slow", "build" to "steady", "edge" to "hold", "hold" to "hold",
        "countdown" to "counted", "permit" to "release", "afterglow" to "slow")

    /** The stage's length in seconds (the counted stage's is its beats'): lines fill it. */
    fun seconds(stage: String, band: Band, rnd: Random): Float {
        val k = if (band == Band.UNINHIBITED) 1.2f else 1f
        val (lo, hi) = when (stage) {
            "setup" -> 18f to 26f; "warmup" -> 34f to 50f; "build" -> 34f to 52f; "edge" -> 16f to 24f; "hold" -> 14f to 22f
            "permit" -> 12f to 18f; "afterglow" -> 24f to 36f; else -> 20f to 30f
        }
        return (lo + rnd.nextFloat() * (hi - lo)) * if (stage == "build" || stage == "warmup") k else 1f
    }

    /** How lively the stage is (0..1): warm-up gentle, edge, countdown and release teasing and intense. */
    fun energy(stage: String): Float = when (stage) {
        "setup" -> 0.2f; "warmup" -> 0.3f; "build" -> 0.5f; "edge" -> 0.75f; "hold" -> 0.5f; "countdown" -> 0.85f
        "permit" -> 0.9f; "afterglow" -> 0.15f; else -> 0.4f
    }

    /** Seconds until the next solo animation (the livelier the stage, the sooner). */
    fun animationGap(stage: String, rnd: Random): Float {
        val e = energy(stage)
        val lo = 16f - 8f * e; val hi = 28f - 12f * e
        return lo + rnd.nextFloat() * (hi - lo)
    }
}

/**
 * The JOI mode. [plan] gives the stage order for a band and seed (KinkPack.joiPlan), [countdown] the count's start and beat
 * (ms) (KinkPack.countdown); [paces] each stage's pace. Everything is deterministic from [seed]. One thread (the screen's).
 */
class JoiMode(
    private val plan: (Band, Long) -> List<String>,
    private val countdown: (Band, Long) -> Pair<Int, Int>,
    private val paces: Map<String, String> = JoiPacing.DEFAULT_PACES,
    private val seed: Long = Random.nextLong(),
) {
    companion object {
        /** The comfort bands a JOI offers (the plan is empty below Intimate), the default first. */
        val BANDS = listOf(Band.INTIMATE, Band.UNINHIBITED)
        /** The next line is asked for this long (seconds) before the one shown ends. */
        const val LEAD = 1.6f
        /** Consecutive missing answers before the mode waits for the model. */
        const val MAX_MISSES = 3
        /** Seconds an ask may stay unanswered before it counts as missed. */
        const val ASK_TIMEOUT = 20f
    }

    var phase = JoiPhase.OFF
        private set
    var band = BANDS[0]
        private set
    var framing = JoiFraming.AUTO
        private set
    val steer = LinkedHashSet<JoiSteer>()
    val subtitles = JoiSubtitles()
    /** The hand stroke she gives when a handjob is picked: its tempo follows the stage, her words and the chips ([JoiStroke]). */
    val stroke = JoiStroke()
    /** Seconds no solo animation is due (the host holds them while a handjob runs). */
    private var soloHold = 0f
    private var heardCue: JoiSubtitles.Cue? = null
    var prior = JoiPrior(false)
        private set
    /** The speaker's id, whether she is a creature (the dragon: her own camera and words). */
    var actor: String? = null
        private set
    var creature = false
        private set

    private var rnd = Random(seed)
    private var stages: List<String> = emptyList()
    var stageIndex = -1
        private set
    var stageClock = 0f
        private set
    private var stageBudget = 0f
    private var serial = 0
    private var awaiting = false
    private var askedFor = 0f
    private var misses = 0
    private var linesInStage = 0
    private var animClock = 0f
    private var animGap = 0f
    private val events = ArrayList<JoiEvent>()
    // the count
    var count: Int? = null
        private set
    private var countStart = 5
    private var beatSeconds = 2f
    private var beatClock = 0f
    private var introDone = false

    val stage: String? get() = stages.getOrNull(stageIndex)
    val stageCount: Int get() = stages.size
    val active: Boolean get() = phase == JoiPhase.RUNNING || phase == JoiPhase.PAUSED
    val on: Boolean get() = phase != JoiPhase.OFF
    val pace: String get() = paces[stage] ?: "steady"

    /**
     * Enters JOI mode with [actor] speaking, remembering [prior] (restored on [leave]). [modelReady] false: no dialogue model, the
     * mode waits ([JoiPhase.NEEDS_MODEL]) and says so; [start] begins once it is there.
     */
    fun enter(actor: String, creature: Boolean, prior: JoiPrior, modelReady: Boolean) {
        this.actor = actor; this.creature = creature; this.prior = prior
        subtitles.clear(); events.clear(); steer.clear()
        misses = 0
        if (modelReady) begin() else phase = JoiPhase.NEEDS_MODEL
    }

    /** The model arrived (or a retry): starts the plan from the beginning. */
    fun modelReady() { if (phase == JoiPhase.NEEDS_MODEL) begin() }

    private fun begin() {
        rnd = Random(seed)
        stages = plan(band, seed)
        stageIndex = -1
        phase = JoiPhase.RUNNING
        misses = 0
        animGap = JoiPacing.animationGap("setup", rnd)
        animClock = 0f
        nextStage()
    }

    /** Leaves the mode: what was on before ([JoiPrior]) comes back from the host. Returns it. */
    fun leave(): JoiPrior {
        phase = JoiPhase.OFF
        subtitles.clear(); events.clear(); awaiting = false; count = null; stageIndex = -1; stages = emptyList()
        stroke.stop(); soloHold = 0f
        actor = null; creature = false
        return prior
    }

    fun pause() { if (phase == JoiPhase.RUNNING) phase = JoiPhase.PAUSED }
    fun resume() { if (phase == JoiPhase.PAUSED) phase = JoiPhase.RUNNING }
    fun togglePause() { if (phase == JoiPhase.PAUSED) resume() else pause() }

    fun setFraming(f: JoiFraming) { framing = f }

    fun toggleSteer(s: JoiSteer) { if (!steer.add(s)) steer.remove(s); stroke.steer(steer) }

    /** No solo animation is due for [seconds] (a handjob is running); 0 releases it. */
    fun holdSolos(seconds: Float) { soloHold = seconds.coerceAtLeast(0f) }

    /** What she is doing with her hands now, in words for her next line (null: nothing the lines must match). */
    fun action(): String? = if (creature) null else stroke.action()

    /**
     * Another comfort band: a JOI in progress goes on from the same stage in the new plan when it has it, else from the
     * nearest later one; the next line is asked for under the new band.
     */
    fun setBand(b: Band) {
        if (b == band || b !in BANDS) return
        band = b
        if (!active) return
        val now = stage
        stages = plan(b, seed)
        val at = stages.indexOf(now).takeIf { it >= 0 } ?: stageIndex.coerceAtMost(stages.size - 1)
        stageIndex = at - 1
        serial++; awaiting = false; subtitles.clear()
        nextStage()
    }

    /** Skips to the next stage now (the line asked for is dropped, the subtitle too). */
    fun skip() {
        if (!active) return
        serial++; awaiting = false; subtitles.clear()
        nextStage()
    }

    private fun nextStage() {
        stageIndex++
        awaiting = false; linesInStage = 0; stageClock = 0f; introDone = false; count = null
        val st = stage
        if (st == null) { events += JoiEvent.Finished; phase = JoiPhase.OFF; stroke.stop(); return }
        stroke.onStage(st)
        stageBudget = JoiPacing.seconds(st, band, rnd)
        if (st == "countdown") {
            val (from, ms) = countdown(band, seed)
            countStart = from; beatSeconds = ms / 1000f; beatClock = 0f
            stageBudget = Float.MAX_VALUE
        }
        events += JoiEvent.StageStarted(st, stageIndex, stages.size)
    }

    /** Takes the events since the last call. */
    fun drain(): List<JoiEvent> = if (events.isEmpty()) emptyList() else events.toList().also { events.clear() }

    /**
     * The engine's answer for the ask with [forSerial] ([JoiEvent.NeedLine.serial]); null: none. A late answer (the stage was
     * skipped meanwhile) is dropped. The line is shown for its length ([JoiSubtitles.hold]).
     */
    fun offerLine(forSerial: Int, text: String?) {
        if (forSerial != serial || !awaiting) return
        awaiting = false
        if (text == null) {
            misses++
            if (misses >= MAX_MISSES) { phase = JoiPhase.NEEDS_MODEL; events += JoiEvent.ModelLost }
            return
        }
        misses = 0
        linesInStage++
        subtitles.push(JoiSubtitles.Cue(text, JoiSubtitles.hold(text, pace), gapAfter = JoiSubtitles.gap(pace)))
        if (stage == "countdown" && !introDone) introDone = true
    }

    /** Advances by [dt] seconds. Events come out of [drain]. */
    fun update(dt: Float) {
        if (phase != JoiPhase.RUNNING || dt <= 0f) return
        val step = dt.coerceAtMost(0.25f)
        val st = stage ?: return
        stageClock += step
        subtitles.advance(step)
        // what she says as it is shown: "slower", "faster" and "stop" change the stroke's tempo
        subtitles.current?.let { c -> if (c !== heardCue) { heardCue = c; if (!c.number) stroke.hear(c.text) } }
        if (awaiting) askedFor += step
        // solo animations: due now and then, by the stage's energy
        animClock += step
        stroke.advance(step)
        if (soloHold > 0f) soloHold = (soloHold - step).coerceAtLeast(0f)
        if (animClock >= animGap) {
            animClock = 0f; animGap = JoiPacing.animationGap(st, rnd)
            if (soloHold <= 0f) events += JoiEvent.AnimationDue(JoiPacing.energy(st), st, stageLeft(st))
        }
        if (st == "countdown") { updateCount(step); return }
        val due = !awaiting && subtitles.queued == 0 && subtitles.remaining <= LEAD
        if (stageClock >= stageBudget && linesInStage > 0 && subtitles.idle && !awaiting) { nextStage(); return }
        if (stageClock >= stageBudget + ASK_TIMEOUT && !awaiting) { nextStage(); return }
        if (due && (stageClock < stageBudget || linesInStage == 0)) ask(st, null)
        else if (awaiting && askedFor >= ASK_TIMEOUT) { awaiting = false; misses++; if (misses >= MAX_MISSES) { phase = JoiPhase.NEEDS_MODEL; events += JoiEvent.ModelLost } }
    }

    /** Seconds the stage has left (the count's: its beats and the line before them). */
    private fun stageLeft(st: String): Float =
        if (st == "countdown") ((count ?: countStart) * beatSeconds + 2f).coerceAtLeast(0f) else (stageBudget - stageClock).coerceAtLeast(0f)

    private fun ask(st: String, from: Int?) {
        serial++; awaiting = true; askedFor = 0f
        events += JoiEvent.NeedLine(st, serial, from)
    }

    private fun updateCount(step: Float) {
        // first one line of the model's before the count, then the beats: the number shown for a beat each
        if (!introDone) {
            if (!awaiting) ask("countdown", countStart)
            return
        }
        if (!subtitles.idle && count == null) return
        beatClock += step
        val c = count
        if (c == null) {
            count = countStart; beatClock = 0f
            subtitles.push(JoiSubtitles.Cue(countStart.toString(), beatSeconds, number = true))
            events += JoiEvent.CountdownBeat(countStart)
            return
        }
        if (beatClock >= beatSeconds) {
            beatClock = 0f
            if (c <= 1) { count = null; nextStage(); return }
            count = c - 1
            subtitles.clear()
            subtitles.push(JoiSubtitles.Cue((c - 1).toString(), beatSeconds, number = true))
            events += JoiEvent.CountdownBeat(c - 1)
        }
    }
}
