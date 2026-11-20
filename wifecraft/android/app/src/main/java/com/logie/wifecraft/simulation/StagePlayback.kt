package com.logie.wifecraft.simulation

import kotlin.math.PI

/** What a part of a staged performance is: played once first, repeated, played once last (anim/StageRules found them). */
enum class SegmentKind { START, LOOP, END }

/** One part of a [PerformanceOrder]: its [roles] (as the order's: the lead's, then the partner's) and one play's [seconds]. */
data class PlaybackSegment(val kind: SegmentKind, val roles: List<PlaybackRole>, val seconds: Float)

/**
 * Where a staged performance is: its parts' [kinds] in order (one START at most, then LOOPs, one END at most). The START
 * plays once, then the LOOPs in turn, a pass of them [wanted] times ([repeat]: until stopped), then the END once. The END
 * only plays after the last pass, or at once when stopped ([stop]); [finishPass] ends the repeat when this pass is done.
 * One plain LOOP is an order as it always was: [wanted] cycles, or until stopped.
 */
class StageCursor(private val kinds: List<SegmentKind>, loops: Int, var repeat: Boolean) {
    private val start = kinds.indexOf(SegmentKind.START)
    private val loopAt = kinds.indices.filter { kinds[it] == SegmentKind.LOOP }
    private val end = kinds.indexOf(SegmentKind.END)

    /** The part playing now (an index into the kinds). */
    var index: Int = when {
        start >= 0 -> start
        loopAt.isNotEmpty() -> loopAt[0]
        end >= 0 -> end
        else -> 0
    }
        private set

    /** Loop passes finished. */
    var passes = 0
        private set

    /** Loop passes to play when not [repeat]. */
    var wanted: Int = loops.coerceIn(1, MAX_LOOPS)
        private set

    /** Stopped: no more loops, the END (if any) is the last thing played. */
    var stopping = false
        private set

    val kind: SegmentKind get() = kinds.getOrElse(index) { SegmentKind.LOOP }

    /** The pass playing now, from 1 (the HUD's loop counter). */
    val pass: Int get() = passes + 1

    /** The part after the one that just ended: its index, or [DONE]. */
    fun next(): Int {
        when (kind) {
            SegmentKind.START -> index = loopAt.firstOrNull() ?: end.takeIf { it >= 0 } ?: return DONE
            SegmentKind.LOOP -> {
                val k = loopAt.indexOf(index)
                if (k >= 0 && k + 1 < loopAt.size) { index = loopAt[k + 1]; return index }
                passes++
                if (!stopping && (repeat || passes < wanted)) { index = loopAt[0]; return index }
                index = end.takeIf { it >= 0 } ?: return DONE
            }
            SegmentKind.END -> return DONE
        }
        return index
    }

    /** Stopped now: the END at once ([index] is it, returned), [KEEP] while it plays already, [DONE] when there is none. */
    fun stop(): Int {
        stopping = true
        repeat = false
        if (kind == SegmentKind.END) return KEEP
        if (end < 0) return DONE
        index = end
        return end
    }

    /** Repeat off: this pass is the last (the END follows it). */
    fun finishPass() {
        wanted = if (repeat) passes + 1 else minOf(wanted, passes + 1)
        repeat = false
    }

    companion object {
        const val DONE = -1
        const val KEEP = -2
        const val MAX_LOOPS = 100
    }
}

/**
 * The ease at a join (a part to the next, a stage to the next, a clip to standing): the engine crossfades the pose itself
 * from the one last drawn (cpp engine/scene.cpp: Scene::setBoneAnim and clearBoneAnim, [JOIN] seconds, smoothstep), but
 * where a person stands and faces is the simulation's, and a new part's place (another stage's offsets, the floor beside
 * the bed once released) would jump there at once. So the place drawn starts at the old one and the difference is let out
 * over the same time with the same curve: the body never teleports or snaps round.
 */
object JoinEase {
    /** Seconds of a join between clips: the engine's crossfade of an imported clip (250 ms). */
    const val JOIN = 0.25f

    /** Seconds of a settle: onto a bed or seat from where they walked to, or back to standing. */
    const val SETTLE = 0.4f

    /** Shorter than this (yards, radians) is no jump: nothing is eased. */
    const val EPSILON = 1e-4f

    /** The share of a join's difference still shown [t] seconds into a [length] one: 1 at its start, 0 at its end. */
    fun weight(t: Float, length: Float): Float {
        if (!(length > 0f) || t >= length) return 0f
        if (t <= 0f) return 1f
        val f = t / length
        return 1f - f * f * (3f - 2f * f)
    }

    /** [angle] turned into -PI..PI (the short way round). */
    fun wrap(angle: Float): Float {
        val tau = (2.0 * PI).toFloat()
        var a = angle % tau
        if (a > PI) a -= tau
        if (a < -PI) a += tau
        return a
    }
}

/**
 * The HUD's line for a performance: its [name], the part and the loop counter ("Stage 2 · Cowgirl · Loop 3/5", "... · Loop
 * 3 · repeating", "... · Start", "... · End"). One plain clip played once says its name alone.
 */
object StageLine {
    fun of(name: String, kind: SegmentKind, pass: Int, wanted: Int, repeat: Boolean, staged: Boolean): String {
        val part = when (kind) {
            SegmentKind.START -> "Start"
            SegmentKind.END -> "End"
            SegmentKind.LOOP -> when {
                repeat -> "Loop $pass · repeating"
                wanted > 1 || staged -> "Loop $pass/$wanted"
                else -> null
            }
        }
        val title = name.ifBlank { "Animation" }
        return if (part == null) title else "$title · $part"
    }
}
