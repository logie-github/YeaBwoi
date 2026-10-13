package com.logie.warcraft.controls

import kotlin.test.Test
import kotlin.test.assertEquals

class InterfaceNoteTest {
    @Test
    fun theSpellbooksPlaceFromTheTopLeft() {
        assertEquals(
            listOf(InterfaceNote.Area(0.25f, 0.25f, 0.5f, 0.75f)),
            InterfaceNote.dragAreas("drag 2500 2500 5000 7500\n"),
        )
    }

    @Test
    fun nothingWhenItsClosedOrTheNoteIsOdd() {
        for (note in listOf(null, "", "-", "drag -", "drag 1000 2000", "drag 5000 2000 1000 9000", "drag 0.1 0.2 0.3 0.4", "drag a b c d", "other 1000 2000 3000 4000")) {
            assertEquals(emptyList(), InterfaceNote.dragAreas(note), note.toString())
        }
    }
}
