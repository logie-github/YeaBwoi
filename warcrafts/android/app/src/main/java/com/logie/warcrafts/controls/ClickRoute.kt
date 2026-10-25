// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.controls

/**
 * Where Winlator's X server sends a mouse button press, in words for Warcraft III's run report: to a
 * window (the game's), to a program that holds the pointer (Wine, keeping the cursor in the game's
 * window: ClipCursor, DirectInput), or nowhere (no window there takes presses, or one Winlator
 * disables). Read as the press happens, before the X server routes it (Winlator's pointer listeners
 * run newest first, the app's before the X server's own).
 */
object ClickRoute {
    /**
     * A window as the report names it: its X id, its class (the program's name, as Wine sets it),
     * whether Winlator lets it have input, and whether it's input-only (Wine's cursor-clipping
     * window is).
     */
    data class Win(val id: Int, val name: String, val enabled: Boolean, val inputOnly: Boolean)

    enum class Kind { WINDOW, HELD, DROPPED }

    /**
     * [grab]: the window the pointer is held on, if it is; [programGrab]: held by a program (its
     * XGrabPointer) rather than by a button already down; [target]: the window under the pointer
     * that takes presses (itself or its nearest parent that does), if any.
     */
    fun kind(grab: Win?, programGrab: Boolean, target: Win?): Kind = when {
        grab != null && programGrab -> Kind.HELD
        grab != null -> if (grab.enabled) Kind.WINDOW else Kind.DROPPED
        target == null || !target.enabled -> Kind.DROPPED
        else -> Kind.WINDOW
    }

    fun describe(grab: Win?, programGrab: Boolean, target: Win?): String = when {
        grab != null && programGrab -> "the pointer held by a program (its grab on ${label(grab)}): sent to that program"
        grab != null && grab.enabled -> "to ${label(grab)} (a button already down there)"
        grab != null -> "dropped: a button already down on ${label(grab)}, a window Winlator disables"
        target == null -> "dropped: no window there takes presses"
        !target.enabled -> "dropped: ${label(target)} takes it, a window Winlator disables"
        else -> "to ${label(target)}"
    }

    /** The presses of a run, counted by where they went (counts in [Kind]'s order). */
    fun summary(counts: IntArray): String {
        val total = counts.sum()
        val each = Kind.entries.map { counts.getOrElse(it.ordinal) { 0 } }
        return "$total presses: ${each[0]} to a window, ${each[1]} to a program holding the pointer, ${each[2]} dropped"
    }

    private fun label(win: Win): String = buildString {
        append("0x%08x".format(win.id))
        if (win.name.isNotEmpty()) append(" \"").append(win.name).append('"')
        if (win.inputOnly) append(" (input only)")
    }
}
