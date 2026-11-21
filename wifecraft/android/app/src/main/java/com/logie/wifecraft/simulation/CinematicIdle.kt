package com.logie.wifecraft.simulation

// Pure Kotlin (JVM-tested: unit-tests/.../simulation/CinematicIdleTest): cinematic mode's idle hide. With cinematic mode on and
// Build mode off, a screen not touched for [idleMs] hides all of the interface (every chip, bar, panel, button and hint: only the
// 3D view and the film frame stay) until the next touch, which shows it again, restarts the count and is not a tap in the scene.
// Build mode, or a window open over the scene ([held]), never hides it. The clock is injected.

class CinematicIdle(val idleMs: Long = DEFAULT_MS, private val clock: () -> Long) {
    companion object {
        const val DEFAULT_MS = 5_000L
    }

    private var last = clock()
    private var eligible = false

    /**
     * What the screen is in now: [cinematic] mode, [buildOn], [held] (a window or menu is open). Idle hiding is on only in
     * cinematic mode with Build mode off and nothing held; the count starts when it turns on (a mode entered, Build left,
     * a window closed counts as a touch).
     */
    fun mode(cinematic: Boolean, buildOn: Boolean, held: Boolean = false) {
        val now = cinematic && !buildOn && !held
        if (now && !eligible) last = clock()
        eligible = now
    }

    /** The screen was touched (any finger, down or moving). True: the interface was hidden, so this touch only shows it (consume it). */
    fun touch(): Boolean {
        val was = hidden()
        last = clock()
        return was
    }

    /** Whether the interface is hidden now. */
    fun hidden(): Boolean = eligible && clock() - last >= idleMs

    /** Milliseconds until it hides (0: hidden now; [Long.MAX_VALUE]: not counting: not cinematic, building or held). */
    fun remainingMs(): Long = if (!eligible) Long.MAX_VALUE else (idleMs - (clock() - last)).coerceAtLeast(0L)
}
