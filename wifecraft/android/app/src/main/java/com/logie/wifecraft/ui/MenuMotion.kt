package com.logie.wifecraft.ui

import kotlin.math.max

// Pure Kotlin, no android.* and no Compose (JVM-tested: unit-tests/.../ui/MenuMotionTest): how the menus move and answer a
// finger. One set of durations for every window, dropdown, tooltip, page and press (MenuMotionUi.kt applies them), all of
// them nothing while the phone's "Remove animations" is on; the gate that keeps a second tap from doing a thing twice; the
// sums that give a small drawn thing a 48 dp touch box without drawing it bigger.

object MenuMotion {
    /** A window (its scrim and its frame) coming in, and going. */
    const val WINDOW_IN_MS = 160
    const val WINDOW_OUT_MS = 120
    /** A window starts this much smaller and grows to its size as it fades in (and shrinks back as it goes). */
    const val WINDOW_SCALE_FROM = 0.96f
    /** A dropdown or a tooltip: quicker, and it drops this far from its button as it shows. */
    const val POP_MS = 90
    const val POP_SLIDE_DP = 6f
    /** One page of a window giving way to another (Options' categories, Import Animations' steps). */
    const val PAGE_MS = 140
    /** A press: everything that can be tapped sinks to this and back, in this long. */
    const val PRESS_SCALE = 0.96f
    const val PRESS_MS = 100
    /** A second tap within this long of the first does nothing (no window opened twice, no Back twice). */
    const val DEBOUNCE_MS = 300L
    /** A dropdown closed by a tap outside it does not open again from that same tap on its own button. */
    const val REOPEN_MS = 250L

    /**
     * Motion is off when the phone says so: Settings' animator duration scale at 0 ("Remove animations", which accessibility's
     * own switch sets too), or the system's reduce-motion request where the phone has one.
     */
    fun reduced(animatorScale: Float, removeAnimations: Boolean = false): Boolean = removeAnimations || animatorScale <= 0f

    /** [ms] as it is played: none under reduced motion. */
    fun duration(ms: Int, reduced: Boolean): Int = if (reduced) 0 else ms

    /** A press's scale: [PRESS_SCALE] while down, 1 when up or under reduced motion (the art's own pressed look stays). */
    fun pressScale(down: Boolean, reduced: Boolean): Float = if (down && !reduced) PRESS_SCALE else 1f
}

/**
 * Lets the first tap through and drops any other within [windowMs] of the last one let through ([clock] in ms, injected for
 * tests). One per tap target: two different buttons are never held up by each other.
 */
class ClickGate(private val windowMs: Long = MenuMotion.DEBOUNCE_MS, private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private var last = Long.MIN_VALUE

    /** True when this tap may act (and starts the quiet time), false while the last one's is running. */
    fun pass(): Boolean {
        val now = clock()
        if (last != Long.MIN_VALUE && now - last in 0 until windowMs) return false
        last = now
        return true
    }

    /** Forgets the last tap (a window reopened: its first tap always counts). */
    fun reset() { last = Long.MIN_VALUE }
}

/** Touch boxes: a thing drawn smaller than a finger keeps its drawn size and gets the rest as space around it. */
object HitTarget {
    const val MIN_DP = 48f

    /** The space to add on each side of a [drawnDp] thing so it is [minDp] to touch (0 when it is big enough). */
    fun pad(drawnDp: Float, minDp: Float = MIN_DP): Float = max(0f, (minDp - drawnDp) / 2f)

    /** The touch box a [drawnDp] thing gets: its own size, or [minDp] when it is smaller. */
    fun box(drawnDp: Float, minDp: Float = MIN_DP): Float = drawnDp + 2 * pad(drawnDp, minDp)
}

/**
 * A scroll thumb that moves smoothly: where its top is (0..1 of the track) and how long it is (0..1), from the first visible
 * item, how far it is scrolled off ([offsetPx] of its [firstSizePx]) and how many items show of [total]. Null when it all fits.
 */
object ScrollThumb {
    fun of(first: Int, offsetPx: Int, firstSizePx: Int, visible: Int, total: Int): Pair<Float, Float>? {
        if (total <= 0 || visible >= total || visible <= 0) return null
        val within = if (firstSizePx > 0) (offsetPx.toFloat() / firstSizePx).coerceIn(0f, 1f) else 0f
        val len = visible.toFloat() / total
        val top = ((first + within) / total).coerceIn(0f, 1f - len)
        return top to len
    }
}

/** Keys for lists: the same item keeps its key, and two items never share one (a repeated id gets its place after it). */
object ListKeys {
    fun unique(ids: List<String>): List<String> {
        val seen = HashMap<String, Int>()
        return ids.map { id ->
            val n = seen.getOrDefault(id, 0)
            seen[id] = n + 1
            if (n == 0) id else "$id#$n"
        }
    }
}
