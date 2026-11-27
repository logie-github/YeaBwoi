package com.logie.wifecraft.ui

// Pure Kotlin (JVM-tested: ScreenPlanTest): which panel may be open over the scene (one at a time), what the Back key does next,
// the one-line toast's queue, and when the chrome hides in the cinematic mode.

/** What the Back key does, most urgent first: close the panel, leave the mode, end the cinematic, let the NPC go, nothing. */
enum class BackStep { CLOSE_PANEL, LEAVE_MODE, LEAVE_CINEMATIC, RELEASE_NPC, NONE }

object PanelRules {
    /** The mode each mode-bound panel belongs to; any other panel is allowed in any mode. */
    private fun allowed(panel: ScreenPanel, mode: ScreenMode): Boolean = when (panel) {
        ScreenPanel.BUILD_PANEL -> mode == ScreenMode.BUILD
        ScreenPanel.MOVE_PANEL -> mode == ScreenMode.MOVE
        ScreenPanel.CHAT_LOG -> mode == ScreenMode.CHAT
        ScreenPanel.INTERACT -> mode == ScreenMode.INTERACT
        ScreenPanel.AUTOPLAY -> mode == ScreenMode.CINEMATIC
        ScreenPanel.MORE -> mode == ScreenMode.NORMAL || mode == ScreenMode.CINEMATIC || mode == ScreenMode.INTERACT
        ScreenPanel.ANIMATIONS -> mode == ScreenMode.NORMAL || mode == ScreenMode.CINEMATIC
        ScreenPanel.STEER -> mode == ScreenMode.NORMAL || mode == ScreenMode.JOI || mode == ScreenMode.CHAT
        else -> true
    }

    /**
     * The panel open after [next] is asked for while [open] is: always exactly one (the asked one replaces what was open; a modal
     * one closes every sheet), unless [next] has no place in [mode] (then [open] stays). Asking for the open panel again closes it.
     */
    fun open(open: ScreenPanel, next: ScreenPanel, mode: ScreenMode): ScreenPanel = when {
        next == ScreenPanel.NONE -> ScreenPanel.NONE
        next == open && next.sheet -> ScreenPanel.NONE
        !allowed(next, mode) -> open
        else -> next
    }

    /** The panel after another NPC is chosen: the NPC's own sheets (More, Time, Steer, Auto-play) close; the browser and Build's stay. */
    fun onSelect(open: ScreenPanel): ScreenPanel = when (open) {
        ScreenPanel.MORE, ScreenPanel.TIME, ScreenPanel.STEER, ScreenPanel.AUTOPLAY -> ScreenPanel.NONE
        else -> open
    }

    /** The panel after a mode is entered: every sheet closes (Build's own panel opens by itself when something is chosen). */
    fun onMode(): ScreenPanel = ScreenPanel.NONE

    /** The next step of the Back key. */
    fun back(open: ScreenPanel, mode: ScreenMode, cinematic: Boolean, npc: Boolean): BackStep = when {
        open != ScreenPanel.NONE -> BackStep.CLOSE_PANEL
        mode == ScreenMode.BUILD || mode == ScreenMode.MOVE || mode == ScreenMode.JOI || mode == ScreenMode.CHAT || mode == ScreenMode.INTERACT -> BackStep.LEAVE_MODE
        cinematic || mode == ScreenMode.CINEMATIC -> BackStep.LEAVE_CINEMATIC
        npc -> BackStep.RELEASE_NPC
        else -> BackStep.NONE
    }
}

/** The one line the toast slot shows: a [text], its [danger] tone, when it appeared ([at], ms). */
data class Toast(val text: String, val danger: Boolean, val at: Long)

/**
 * The toast line's queue: the newest toast replaces the one shown (nothing stacks); one lasts [LIFE_MS], a problem [PROBLEM_MS];
 * the same words again only renew the time. Replaces the separate answer, problem and hint pop-ups.
 */
class ToastQueue {
    private var current: Toast? = null

    fun show(text: String, danger: Boolean, now: Long) {
        if (text.isBlank()) return
        val c = current
        current = if (c != null && c.text == text && c.danger == danger) c.copy(at = now) else Toast(text, danger, now)
    }

    /** What is shown at [now] (null: nothing); an expired toast is dropped. */
    fun at(now: Long): Toast? {
        val c = current ?: return null
        if (now - c.at >= (if (c.danger) PROBLEM_MS else LIFE_MS)) { current = null; return null }
        return c
    }

    fun clear() { current = null }

    companion object {
        const val LIFE_MS = 3_000L
        const val PROBLEM_MS = 6_000L
    }
}

/** When the chrome (top bar, dock, rails) is up: pure, so the cinematic's peek and the exit chip are tested. */
object ChromeVisibility {
    /**
     * Whether the chrome is shown. [cinematic]: it hides [idleMs] after the last touch when [idleMs] >= [PEEK_MS]; Build, a panel
     * or a window keeps it up; [immersive] hides it the same way outside the cinematic mode.
     */
    fun shown(cinematic: Boolean, immersive: Boolean, idleMs: Long, mode: ScreenMode, panelOpen: Boolean): Boolean {
        if (mode == ScreenMode.BUILD || mode == ScreenMode.MOVE || panelOpen) return true
        if (mode == ScreenMode.JOI || mode == ScreenMode.CHAT || mode == ScreenMode.INTERACT) return true
        return if (cinematic || immersive) idleMs < PEEK_MS else true
    }

    /** Whether the mode chip (the way out of a mode) is up: always while a mode runs, whatever the chrome does. */
    fun exitChip(mode: ScreenMode): Boolean = mode != ScreenMode.NORMAL

    const val PEEK_MS = 5_000L
}

/**
 * The chrome's visibility over time, for the cinematic mode (pure; the clock is injected): [update] says whether it is shown now, by
 * [ChromeVisibility.shown] (5 s without a touch hides all of it; Build, JOI, Chat, an open sheet or window and the keyboard never do; a
 * sheet or window closing counts as a touch); [touch] is told of every touch and says whether it only revealed the chrome (the
 * caller swallows it: it is no tap in the scene); [fading] is true for [FADE_MS] after the chrome last appeared or went, and no
 * tap on it counts then (the cover over the screen takes them).
 */
class ChromeGate(private val clock: () -> Long) {
    private var lastTouch = clock()
    private var shownNow = true
    private var changedAt = Long.MIN_VALUE / 2
    private var held = false
    private var counting = false

    fun update(cinematic: Boolean, mode: ScreenMode, panelOpen: Boolean, keyboard: Boolean): Boolean {
        val now = clock()
        val hold = panelOpen || keyboard
        if ((held && !hold) || (cinematic && !counting)) lastTouch = now
        held = hold
        counting = cinematic
        val s = ChromeVisibility.shown(cinematic, false, now - lastTouch, mode, hold)
        if (s != shownNow) { shownNow = s; changedAt = now }
        return s
    }

    /** A finger touched the screen: true when the chrome was hidden, so this touch only shows it. */
    fun touch(): Boolean {
        val now = clock()
        val was = !shownNow
        lastTouch = now
        if (was) { shownNow = true; changedAt = now }
        return was
    }

    /** Whether the chrome is fading in or out now: taps are not taken. */
    fun fading(): Boolean = clock() - changedAt < FADE_MS

    companion object { const val FADE_MS = 400L }
}

/** The one first-run tip (a single toast on the first scene): where the dock and the menu are. Tips never stack: one text, shown once. */
object ScreenTips {
    const val KEY = "tip_dock_seen"
    const val TEXT = "Tap someone in the cast, then use the bar at the bottom. The gear is the menu; Build mode is under More."

    /** Whether the tip is due now: not seen, the scene is up, nothing else is open and no other toast is on. */
    fun due(seen: Boolean, revealed: Boolean, mode: ScreenMode, panelOpen: Boolean, toastShown: Boolean): Boolean =
        !seen && revealed && mode == ScreenMode.NORMAL && !panelOpen && !toastShown
}
