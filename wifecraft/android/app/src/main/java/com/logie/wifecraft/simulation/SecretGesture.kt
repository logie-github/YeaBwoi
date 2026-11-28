package com.logie.wifecraft.simulation

import kotlin.math.abs
import kotlin.math.hypot

// Pure Kotlin (JVM-tested: SecretGestureTest): the hidden mode's key. Eight swipes in order, then three quick taps anywhere on the title screen.
// Nothing here knows Android: the screen feeds raw pointer events (ids, positions in pixels, times in ms) to [SecretGestureDetector], which turns
// them into whole touches ([SecretTouch]) for [SecretGesture], the state machine. Nothing is shown while it runs; only the last tap does anything.

/** The four directions of a swipe (screen axes: UP is toward the top of the screen). */
enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

/**
 * The key and its tolerances, in one place so it can be changed. Distances in dp, times in milliseconds.
 */
data class SecretSpec(
    /** The swipes, in order. */
    val sequence: List<SwipeDirection> = SEQUENCE,
    /** The taps that follow, the last one fires. */
    val taps: Int = TAPS,
    /** A swipe goes at least this far along its axis... */
    val minSwipeDp: Float = 72f,
    /** ...and this many times farther along it than across (dominant axis). */
    val axisRatio: Float = 2f,
    /** A swipe is over (finger up) within this long of its start. */
    val swipeMaxMs: Long = 900L,
    /** From the end of one step to the start of the next (swipe to swipe, last swipe to first tap) at most this long. */
    val stepPauseMs: Long = 2500L,
    /** A tap is a press shorter than this... */
    val tapMaxMs: Long = 250L,
    /** ...that moves less than this. */
    val tapSlopDp: Float = 12f,
    /** From a tap's lifting to the next tap's touching at most this long. */
    val tapGapMs: Long = 700L,
) {
    companion object {
        /** Up, up, down, down, left, right, left, right. */
        val SEQUENCE: List<SwipeDirection> = listOf(
            SwipeDirection.UP, SwipeDirection.UP, SwipeDirection.DOWN, SwipeDirection.DOWN,
            SwipeDirection.LEFT, SwipeDirection.RIGHT, SwipeDirection.LEFT, SwipeDirection.RIGHT,
        )
        const val TAPS = 3
    }
}

/**
 * One whole touch as the machine reads it: when it began and ended ([startMs], [endMs]), how far it travelled between down and up ([dxDp], [dyDp]:
 * screen axes, y grows downward), the farthest it strayed from where it began ([maxTravelDp]) and whether a second finger was ever on the screen
 * with it ([multi]) or the system cancelled it ([cancelled]).
 */
data class SecretTouch(
    val startMs: Long, val endMs: Long, val dxDp: Float, val dyDp: Float, val maxTravelDp: Float,
    val multi: Boolean = false, val cancelled: Boolean = false,
)

/** What a touch did to the key. */
enum class SecretOutcome {
    /** Nothing to do with the key, or the key was not under way. */
    NONE,
    /** The touch was the next step: the key goes on. */
    STEP,
    /** The touch was wrong, too slow or too late: the key starts afresh (the touch itself counted as a first step if it fits one). */
    RESET,
    /** The last tap: the hidden mode opens. */
    OPEN,
}

/**
 * The state machine. Swipes first (each one dominant-axis, long enough and quick enough), then the taps; a wrong or slow swipe, a touch with more than
 * one finger, a cancelled touch or a pause beyond the limits starts the key over silently. A touch that breaks the key is read again as the first
 * step of a new try, so a stumble in the middle does not cost a free first swipe. After [SecretOutcome.OPEN] the key is ready again.
 */
class SecretGesture(val spec: SecretSpec = SecretSpec()) {
    /** Swipes accepted so far in this try (0 to the sequence's length). */
    var swipes = 0; private set
    /** Taps accepted so far (only after every swipe). */
    var tapsDone = 0; private set
    private var lastEnd = Long.MIN_VALUE

    /** Whether the next touch is expected to be a tap (every swipe is done). */
    val tapping: Boolean get() = swipes >= spec.sequence.size

    /** Steps done in all (swipes and taps): the progress of the try. */
    val progress: Int get() = swipes + tapsDone

    fun reset() { swipes = 0; tapsDone = 0; lastEnd = Long.MIN_VALUE }

    /**
     * Whether a touch beginning at [nowMs] would still be read as the key's tap: the swipes are all done and the pause has not run out. The screen asks
     * it as a finger lands, to keep the tap from also pressing a button underneath.
     */
    fun expectingTap(nowMs: Long): Boolean = tapping && nowMs - lastEnd <= limitFor()

    private fun limitFor(): Long = if (tapsDone == 0) spec.stepPauseMs else spec.tapGapMs

    /** Reads one finished touch. */
    fun feed(t: SecretTouch): SecretOutcome {
        if (progress > 0 && t.startMs - lastEnd > limitFor()) reset()   // (a pause beyond the limit: this touch starts afresh)
        if (t.multi || t.cancelled) { val had = progress > 0; reset(); return if (had) SecretOutcome.RESET else SecretOutcome.NONE }
        val before = progress
        val ok = if (tapping) tap(t) else swipe(t)
        if (ok) {
            lastEnd = t.endMs
            if (tapping && tapsDone >= spec.taps) { reset(); return SecretOutcome.OPEN }
            return SecretOutcome.STEP
        }
        // a stray touch: the key starts over, and a touch that fits the first swipe begins the new try
        reset()
        if (swipe(t)) { lastEnd = t.endMs; return SecretOutcome.RESET }
        return if (before > 0) SecretOutcome.RESET else SecretOutcome.NONE
    }

    private fun swipe(t: SecretTouch): Boolean {
        if (tapping) return false
        if (direction(t, spec) != spec.sequence[swipes]) return false
        swipes++
        return true
    }

    private fun tap(t: SecretTouch): Boolean {
        if (!isTap(t, spec)) return false
        tapsDone++
        return true
    }

    companion object {
        /** The direction [t] is a swipe in, or null: too short, too slow, not along one axis, or a touch with two fingers. */
        fun direction(t: SecretTouch, spec: SecretSpec = SecretSpec()): SwipeDirection? {
            if (t.multi || t.cancelled) return null
            if (t.endMs - t.startMs > spec.swipeMaxMs) return null
            val ax = abs(t.dxDp); val ay = abs(t.dyDp)
            if (!ax.isFinite() || !ay.isFinite()) return null
            return when {
                ay >= spec.minSwipeDp && ay >= spec.axisRatio * ax -> if (t.dyDp < 0f) SwipeDirection.UP else SwipeDirection.DOWN
                ax >= spec.minSwipeDp && ax >= spec.axisRatio * ay -> if (t.dxDp < 0f) SwipeDirection.LEFT else SwipeDirection.RIGHT
                else -> null
            }
        }

        /** Whether [t] is a tap: a short press that stays put. */
        fun isTap(t: SecretTouch, spec: SecretSpec = SecretSpec()): Boolean =
            !t.multi && !t.cancelled && t.endMs - t.startMs < spec.tapMaxMs && t.maxTravelDp < spec.tapSlopDp
    }
}

/**
 * Raw pointer events to whole touches. [density] is the pixels in a dp. The events are only watched: [down] answers whether the finger should be kept
 * from the buttons under it (true only for the key's own taps, once every swipe is done: the screen consumes those so that a tap on a button is not
 * also a press of it; every other touch is left entirely alone). A second finger on the screen spoils the touch and the key.
 */
class SecretGestureDetector(private val density: Float, private val gesture: SecretGesture = SecretGesture()) {
    private var firstId = Long.MIN_VALUE
    private var down = 0
    private var startMs = 0L
    private var startX = 0f
    private var startY = 0f
    private var maxTravel = 0f
    private var multi = false
    private var spoiled = false

    val progress: Int get() = gesture.progress
    val state: SecretGesture get() = gesture

    private fun dp(px: Float) = if (density > 0f) px / density else px

    /** A finger lands. True: this touch is a candidate tap of the key's last stage; the screen may consume it. */
    fun down(id: Long, x: Float, y: Float, nowMs: Long): Boolean {
        if (down == 0) {
            firstId = id; startMs = nowMs; startX = x; startY = y; maxTravel = 0f; multi = false; spoiled = false
            down = 1
            return gesture.expectingTap(nowMs)
        }
        down++
        multi = true
        if (!spoiled) { spoiled = true; gesture.reset() }   // (two fingers: the key starts over at once)
        return false
    }

    fun move(id: Long, x: Float, y: Float) {
        if (down == 0 || id != firstId) return
        maxTravel = maxOf(maxTravel, dp(hypot(x - startX, y - startY)))
    }

    /** A finger lifts; the outcome when it was the last one of a touch the key can read. */
    fun up(id: Long, x: Float, y: Float, nowMs: Long): SecretOutcome {
        if (down == 0) return SecretOutcome.NONE
        if (id == firstId) { maxTravel = maxOf(maxTravel, dp(hypot(x - startX, y - startY))) }
        val endX = if (id == firstId) x else startX
        val endY = if (id == firstId) y else startY
        down--
        if (down > 0) return SecretOutcome.NONE
        val out = if (spoiled) SecretOutcome.NONE else gesture.feed(SecretTouch(startMs, nowMs, dp(endX - startX), dp(endY - startY), maxTravel, multi = multi))
        firstId = Long.MIN_VALUE
        return out
    }

    /** The system took the touch away (or the layer left the screen). */
    fun cancel() {
        if (down > 0 || gesture.progress > 0) gesture.reset()
        down = 0; firstId = Long.MIN_VALUE; spoiled = false
    }
}
