// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.controls

/**
 * What the app's add-on in the game ([com.logie.warcraft.client.TouchAddon]) says about the game's
 * windows, through the realm on the phone, which keeps it in `files/run/interface`
 * (patches/azerothcore/0010): where the windows whose icons drag are (the spellbook, while it's
 * open), so a drag that starts there drags an icon, as a mouse would.
 */
object InterfaceNote {
    /** A window's place, in fractions of the game's screen, from its top left. */
    data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    }

    /**
     * The windows whose icons drag, from the note: "drag <left> <bottom> <right> <top>" for each
     * (ten-thousandths of the screen from its bottom left, as WoW counts), or "drag -" for none.
     */
    fun dragAreas(note: String?): List<Area> {
        val words = note?.trim()?.split(' ')?.filter { it.isNotEmpty() } ?: return emptyList()
        if (words.firstOrNull() != "drag") return emptyList()
        val numbers = words.drop(1).map { (it.toIntOrNull() ?: return emptyList()) / 10_000f }
        return numbers.chunked(4).filter { it.size == 4 }.mapNotNull { (left, bottom, right, top) ->
            if (right <= left || top <= bottom) null else Area(left, 1f - top, right, 1f - bottom)
        }
    }
}
