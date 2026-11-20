package com.logie.wifecraft.characters

import com.logie.wifecraft.creator.Appearance
import com.logie.wifecraft.creator.PartCut
import com.logie.wifecraft.creator.PartOptions
import com.logie.wifecraft.creator.PartSize
import com.logie.wifecraft.creator.Sex
import com.logie.wifecraft.editor.Actor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The saved NPCs' file (NpcRosterFile) and the built-in NPCs' choices (NpcLooks): pure operations. */
class NpcRosterFileTest {
    private val ann = Actor("a1", "Ann", Appearance(4, Sex.FEMALE))
    private val bo = Actor("b2", "Bo", Appearance(1, Sex.MALE, gear = false))
    private val cy = Actor("c3", "Cy", Appearance(2, Sex.FEMALE))

    @Test fun addReadAndDelete() {
        var text: String? = null
        for (a in listOf(ann, bo, cy)) text = NpcRosterFile.add(text, a)
        assertEquals(listOf(ann, bo, cy), NpcRosterFile.parse(text))
        // adding one again replaces it (no copy)
        assertEquals(3, NpcRosterFile.parse(NpcRosterFile.add(text, bo)).size)
        val without = NpcRosterFile.delete(text, "b2")
        assertEquals(listOf(ann, cy), NpcRosterFile.parse(without))
        // persisted: what is written reads back the same; deleting what isn't there changes nothing
        assertEquals(NpcRosterFile.parse(without), NpcRosterFile.parse(NpcRosterFile.delete(without, "zz")))
        assertEquals(emptyList(), NpcRosterFile.parse(NpcRosterFile.delete(NpcRosterFile.delete(without, "a1"), "c3")))
        assertEquals(emptyList(), NpcRosterFile.parse(null))
    }

    @Test fun replaceKeepsThePlace() {
        val text = NpcRosterFile.write(listOf(ann, bo, cy))
        val changed = bo.copy(appearance = bo.appearance.copy(parts = PartOptions(PartSize.SMALL, PartCut.RETRACTED)))
        val out = NpcRosterFile.parse(NpcRosterFile.replace(text, changed))
        assertEquals(listOf(ann, changed, cy), out)
        assertEquals(PartSize.SMALL, out[1].appearance.parts?.size)
    }

    @Test fun onlyTheUsersOwnHaveDelete() {
        val saved = setOf("a1", "b2")
        assertTrue(NpcRosterFile.canDelete("a1", saved))
        assertFalse(NpcRosterFile.canDelete("c3", saved))                    // not in the file (an older project's)
        assertFalse(NpcRosterFile.canDelete("starter:FEMALE", saved + "starter:FEMALE"))
        assertFalse(NpcRosterFile.canDelete("character:jaina", saved + "character:jaina"))
        assertEquals(setOf("a1"), NpcRosterFile.castWithout(setOf("a1", "b2"), "b2"))
        assertEquals(setOf("a1"), NpcRosterFile.castWithout(setOf("a1"), "b2"))
    }

    @Test fun itsThumbnailsGoWithIt() {
        val h = "b2".hashCode()
        val names = listOf("npc_${h}_abc_192.png", "npc_${h}_def_160.png", "npc_${"a1".hashCode()}_abc_192.png", "prop_${h}_192.png")
        assertEquals(names.take(2), NpcRosterFile.thumbnails(names, "b2"))
    }

    @Test fun builtInChoicesRoundTrip() {
        val looks = NpcLooks().withVariant("character:jaina", "bare").withParts("starter:MALE", PartOptions(PartSize.LARGE))
        assertEquals(looks, NpcLooks.parse(looks.toJson()))
        assertEquals(NpcLooks(), NpcLooks.parse("not json"))
        val rowan = Actor("starter:MALE", "Rowan", Appearance(1, Sex.MALE))
        assertEquals(PartSize.LARGE, looks.apply(rowan).appearance.parts?.size)
        assertEquals(ann, looks.apply(ann))
    }
}
