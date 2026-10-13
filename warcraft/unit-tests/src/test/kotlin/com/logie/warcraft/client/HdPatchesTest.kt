package com.logie.warcraft.client

import com.logie.warcraft.client.HdPatches.Choice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HdPatchesTest {
    private fun targets(choice: Choice) = HdPatches.files(choice).map { it.target("enUS") }

    @Test
    fun chromieCraftsPatchesWithoutTheTreesThatSpoilItsTextures() {
        assertEquals(
            listOf("Data/patch-H.MPQ", "Data/patch-F.MPQ", "Data/patch-G.MPQ", "Data/patch-T.MPQ", "Data/patch-S.MPQ"),
            targets(Choice.CHROMIECRAFT),
        )
    }

    @Test
    fun theAlternativeClientsLookWithoutItsScreens() {
        val all = targets(Choice.ALTERNATIVE)
        assertEquals(
            listOf("Data/patch-A.MPQ", "Data/enUS/patch-enUS-A.MPQ", "Data/patch-F.MPQ", "Data/enUS/patch-enUS-F.MPQ",
                "Data/patch-6.MPQ", "Data/patch-S.MPQ", "Data/patch-W.MPQ", "Data/enUS/patch-enUS-W.MPQ", "Data/patch-X.MPQ",
                "Data/enUS/patch-enUS-X.MPQ", "Data/patch-E.MPQ", "Data/enUS/patch-enUS-Q.MPQ"),
            all,
        )
        assertTrue(all.none { it.contains("-C.") || it.contains("-K.") || it.contains("-D.") || it.contains("-5.") })
        assertEquals(listOf("Data/enGB/patch-enGB-Q.MPQ"), HdPatches.files(Choice.ALTERNATIVE).takeLast(1).map { it.target("enGB") })
        assertEquals(emptyList(), targets(Choice.OFF))
    }

    @Test
    fun findsTheFilesInTheZipsWhateverTheirCase() {
        fun entry(name: String) = RemoteZip.Entry(name, 0, 1, 1, 0, 0)
        val entries = listOf(
            entry("world of warcraft 3.3.5a hd/data/patch-a.mpq"),
            entry("world of warcraft 3.3.5a hd/data/enus/patch-enus-s.mpq.disabled"),
            entry("additional_patches_for_335a/Patch-H.MPQ"),
        )
        val a = HdPatches.ALTERNATIVE.files.first { it.entry == "data/patch-a.mpq" }
        assertEquals(entries[0], HdPatches.find(entries, a))
        val h = HdPatches.CHROMIECRAFT.files.first()
        assertEquals(entries[2], HdPatches.find(entries, h))
        assertNull(HdPatches.find(entries, HdPatches.CHROMIECRAFT.files[3]))
    }
}
