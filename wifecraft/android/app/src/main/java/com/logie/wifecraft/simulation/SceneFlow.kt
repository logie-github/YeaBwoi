package com.logie.wifecraft.simulation

// Pure Kotlin (no Android): a scene between two people as one flow rather than a menu: the lead-in (they come together,
// face each other, a slow emote), undressing (a piece at a time, in a natural order, what each keeps on left), the stages
// (the author's chain, the intensity climbing), the afterglow (a calm pose, then rest). Each phase moves on with one soft
// control (Continue) or, with Auto flow, by its own timers. The flow only says what should happen ([FlowStep]); the screen
// does it (orders, the dresser, the camera). JVM-tested (unit-tests/.../simulation/SceneFlowTest.kt).

/** Where a flow is. NONE: no flow (the scene as it was). */
enum class FlowPhase { NONE, LEAD_IN, UNDRESS, STAGE, AFTERGLOW, DONE }

/** What the screen is asked to do as the flow moves. */
sealed interface FlowStep {
    /** [a] and [b] come together and face each other; [emote] a slow Warcraft emote both play (game animation id). */
    data class LeadIn(val a: String, val b: String, val emote: Int) : FlowStep
    /** [actor] takes off what they wear in equipment slot [slot] (re-dressed without it, a short fade). */
    data class TakeOff(val actor: String, val slot: Int) : FlowStep
    /** The stages begin (the author's chain, as queued): the screen plays them with [a] and [b]. */
    data class Stages(val a: String, val b: String) : FlowStep
    /** A calm pose together (lying or a cuddle; else the nearest emote: [emote]). */
    data class Afterglow(val a: String, val b: String, val emote: Int) : FlowStep
    /** They rest (the sleep action) once the afterglow has lasted. */
    data class Rest(val a: String, val b: String) : FlowStep
    /** The flow was ended: [actor] dressed again as they were. */
    data class Redress(val actor: String) : FlowStep
}

/**
 * The order clothes come off (WoW equipment slots): the outer layers (cloak, helm, shoulders, gloves, bracers), the boots,
 * the belt, the legs, the tabard, the chest, the shirt. What isn't worn, or is kept on ([keep]), is left out.
 */
object UndressOrder {
    const val HEAD = 1; const val SHOULDERS = 3; const val SHIRT = 4; const val CHEST = 5; const val WAIST = 6
    const val LEGS = 7; const val FEET = 8; const val WRISTS = 9; const val HANDS = 10; const val BACK = 15; const val TABARD = 19
    val ORDER = listOf(BACK, HEAD, SHOULDERS, HANDS, WRISTS, FEET, WAIST, LEGS, TABARD, CHEST, SHIRT)
    /** The plain names Options' Keep on chips use. */
    val NAMES = mapOf(HEAD to "Helm", SHOULDERS to "Shoulders", BACK to "Cloak", HANDS to "Gloves", WRISTS to "Bracers",
        FEET to "Boots", WAIST to "Belt", LEGS to "Legs", TABARD to "Tabard", CHEST to "Chest", SHIRT to "Shirt")

    fun of(worn: Collection<Int>, keep: Collection<Int> = emptySet()): List<Int> = ORDER.filter { it in worn && it !in keep }

    /** Two people's pieces taken in turn (one of hers, one of his...), each in their own order. */
    fun interleave(a: String, aSlots: List<Int>, b: String, bSlots: List<Int>): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>(aSlots.size + bSlots.size)
        for (i in 0 until maxOf(aSlots.size, bSlots.size)) {
            aSlots.getOrNull(i)?.let { out += a to it }
            bSlots.getOrNull(i)?.let { out += b to it }
        }
        return out
    }
}

/**
 * One flow between [a] and [b]. [wornA]/[wornB] the slots each wears, [keepA]/[keepB] what each keeps on; [undress] off
 * skips undressing. [start] begins it; [proceed] (the Continue control) moves to the next phase; [tick] runs its timers
 * (only with [auto] do the phases move on by themselves; undressing always goes a piece at a time once begun);
 * [stagesFinished] says the stages' queue has played out; [end] stops it. Every call returns the steps for the screen.
 */
class SceneFlow(
    val a: String, val b: String,
    wornA: Collection<Int>, wornB: Collection<Int>,
    keepA: Collection<Int> = emptySet(), keepB: Collection<Int> = emptySet(),
    private val undress: Boolean = true,
    var auto: Boolean = false,
) {
    companion object {
        /** Seconds a phase lasts before Auto flow moves on. */
        const val LEAD_IN_S = 9f
        const val PIECE_S = 1.1f
        const val AFTER_UNDRESS_S = 1.5f
        const val AFTERGLOW_S = 40f
        /** Game animations: a slow kiss for the lead-in, lying down for the afterglow when no pose fits. */
        const val EMOTE_KISS = 76
        const val EMOTE_LIE = 100
        const val TEMPO_MIN = -2
        const val TEMPO_MAX = 2
    }

    var phase = FlowPhase.NONE
        private set
    /** Seconds in the phase now. */
    var elapsed = 0f
        private set
    /** The player's Slower (-) and Faster (+), -2..2 (the intensity's [IntensityModel] tempo). */
    var tempo = 0
        private set

    private val pieces: ArrayDeque<Pair<String, Int>> =
        ArrayDeque(UndressOrder.interleave(a, UndressOrder.of(wornA, keepA), b, UndressOrder.of(wornB, keepB)))
    private val taken = ArrayList<Pair<String, Int>>()
    private var nextPiece = 0f
    private var stagesDone = false

    /** Pieces still to come off. */
    val remaining: Int get() = pieces.size
    /** Pieces taken off so far (in order). */
    val takenOff: List<Pair<String, Int>> get() = taken
    val active: Boolean get() = phase != FlowPhase.NONE && phase != FlowPhase.DONE

    fun start(): List<FlowStep> {
        if (phase != FlowPhase.NONE) return emptyList()
        return enter(FlowPhase.LEAD_IN)
    }

    /** Continue: the next phase now (undressing hurried: what's left comes off at once). */
    fun proceed(): List<FlowStep> = when (phase) {
        FlowPhase.NONE -> start()
        FlowPhase.LEAD_IN -> enter(if (undress && pieces.isNotEmpty()) FlowPhase.UNDRESS else FlowPhase.STAGE)
        FlowPhase.UNDRESS -> {
            val rest = ArrayList<FlowStep>()
            while (pieces.isNotEmpty()) rest += take()
            rest + enter(FlowPhase.STAGE)
        }
        FlowPhase.STAGE -> enter(FlowPhase.AFTERGLOW)
        FlowPhase.AFTERGLOW -> enter(FlowPhase.DONE)
        FlowPhase.DONE -> emptyList()
    }

    /** [dt] seconds on. */
    fun tick(dt: Float): List<FlowStep> {
        if (!active || !(dt > 0f) || !dt.isFinite()) return emptyList()
        elapsed += dt.coerceAtMost(1f)
        return when (phase) {
            FlowPhase.LEAD_IN -> if (auto && elapsed >= LEAD_IN_S) proceed() else emptyList()
            FlowPhase.UNDRESS -> {
                val out = ArrayList<FlowStep>()
                while (pieces.isNotEmpty() && elapsed >= nextPiece) { out += take(); nextPiece += PIECE_S }
                if (pieces.isEmpty() && auto && elapsed >= nextPiece - PIECE_S + AFTER_UNDRESS_S) out += enter(FlowPhase.STAGE)
                out
            }
            FlowPhase.STAGE -> if (auto && stagesDone) enter(FlowPhase.AFTERGLOW) else emptyList()
            FlowPhase.AFTERGLOW -> if (auto && elapsed >= AFTERGLOW_S) enter(FlowPhase.DONE) else emptyList()
            else -> emptyList()
        }
    }

    /** The stages' queue played out (or was stopped). */
    fun stagesFinished(): List<FlowStep> {
        stagesDone = true
        return if (phase == FlowPhase.STAGE && auto) enter(FlowPhase.AFTERGLOW) else emptyList()
    }

    fun slower() { tempo = (tempo - 1).coerceAtLeast(TEMPO_MIN) }
    fun faster() { tempo = (tempo + 1).coerceAtMost(TEMPO_MAX) }

    /** End: the flow stops; whoever took something off is dressed again. */
    fun end(): List<FlowStep> {
        if (phase == FlowPhase.NONE) return emptyList()
        phase = FlowPhase.NONE
        elapsed = 0f
        return taken.map { it.first }.distinct().map { FlowStep.Redress(it) }
    }

    private fun take(): FlowStep.TakeOff {
        val p = pieces.removeFirst()
        taken += p
        return FlowStep.TakeOff(p.first, p.second)
    }

    private fun enter(next: FlowPhase): List<FlowStep> {
        phase = next
        elapsed = 0f
        nextPiece = 0f
        return when (next) {
            FlowPhase.LEAD_IN -> listOf(FlowStep.LeadIn(a, b, EMOTE_KISS))
            FlowPhase.UNDRESS -> emptyList()   // the pieces come with the ticks, the first at once
            FlowPhase.STAGE -> { stagesDone = false; listOf(FlowStep.Stages(a, b)) }
            FlowPhase.AFTERGLOW -> listOf(FlowStep.Afterglow(a, b, EMOTE_LIE))
            FlowPhase.DONE -> listOf(FlowStep.Rest(a, b))
            FlowPhase.NONE -> emptyList()
        }
    }
}
