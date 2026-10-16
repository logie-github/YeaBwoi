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
    fun whereAHoldIsARightClick() {
        val note = "drag 2500 2500 5000 7500 hold 7000 1000 9900 6000 0 0 10000 900\n"
        assertEquals(listOf(InterfaceNote.Area(0.25f, 0.25f, 0.5f, 0.75f)), InterfaceNote.dragAreas(note))
        assertEquals(
            listOf(InterfaceNote.Area(0.7f, 1f - 0.6f, 0.99f, 1f - 0.1f), InterfaceNote.Area(0f, 1f - 0.09f, 1f, 1f)),
            InterfaceNote.holdAreas(note),
        )
        assertEquals(emptyList(), InterfaceNote.dragAreas("drag - hold 0 0 10000 900"))
        assertEquals(listOf(InterfaceNote.Area(0f, 1f - 0.09f, 1f, 1f)), InterfaceNote.holdAreas("drag - hold 0 0 10000 900"))
        assertEquals(emptyList(), InterfaceNote.holdAreas("drag 2500 2500 5000 7500 hold -"))
        assertEquals(emptyList(), InterfaceNote.holdAreas("drag 2500 2500 5000 7500"))
    }

    @Test
    fun nothingWhenItsClosedOrTheNoteIsOdd() {
        for (note in listOf(null, "", "-", "drag -", "drag 1000 2000", "drag 5000 2000 1000 9000", "drag 0.1 0.2 0.3 0.4", "drag a b c d", "other 1000 2000 3000 4000")) {
            assertEquals(emptyList(), InterfaceNote.dragAreas(note), note.toString())
        }
    }
}
