package com.logie.pe.discs

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscSorterTest {
    private val dir: File = Files.createTempDirectory("discs").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun file(name: String, text: String = "data") = File(dir, name).apply { writeText(text) }

    private fun cue(name: String, vararg tracks: String) =
        file(name, tracks.joinToString("\n") { "FILE \"$it\" BINARY\n  TRACK 01 MODE2/2352\n    INDEX 01 00:00:00" })

    private fun names(discs: Map<Int, File>) = discs.mapValues { (_, file) -> "${file.parentFile.name}/${file.name}" }

    @Test
    fun bothDiscsAsCuesAndBinsGoByTheirNames() {
        cue("Parasite Eve (USA) (Disc 2).cue", "Parasite Eve (USA) (Disc 2).bin")
        file("Parasite Eve (USA) (Disc 2).bin")
        cue("Parasite Eve (USA) (Disc 1).cue", "Parasite Eve (USA) (Disc 1).bin")
        file("Parasite Eve (USA) (Disc 1).bin")
        val discs = DiscSorter.sort(dir, null)
        assertEquals(
            mapOf(1 to "disc1/Parasite Eve (USA) (Disc 1).cue", 2 to "disc2/Parasite Eve (USA) (Disc 2).cue"),
            names(discs),
        )
        assertTrue(File(dir, "disc2/Parasite Eve (USA) (Disc 2).bin").isFile)
        assertEquals(discs[1], DiscSorter.startFile(File(dir, "disc1")))
    }

    @Test
    fun tracksAreRenamedToHowTheCueSpellsThem() {
        cue("pe.cue", "Track 01.bin", "Track 02.bin")
        file("TRACK 01.BIN")
        file("track 02.bin")
        DiscSorter.sort(dir, null)
        assertTrue(File(dir, "disc1/Track 01.bin").isFile)
        assertTrue(File(dir, "disc1/Track 02.bin").isFile)
    }

    @Test
    fun aMissingTrackIsNamed() {
        cue("pe.cue", "pe (Track 1).bin", "pe (Track 2).bin")
        file("pe (Track 1).bin")
        val error = assertFailsWith<DiscSorter.SortException> { DiscSorter.sort(dir, null) }
        assertEquals(DiscSorter.SortException.Reason.MISSING_TRACKS, error.reason)
        assertEquals("pe (Track 2).bin", error.detail)
        // Nothing was moved.
        assertFalse(File(dir, "disc1").exists())
    }

    @Test
    fun aDiscAloneGoesWhereItsNameSays() {
        file("Parasite Eve CD2.chd")
        assertEquals(mapOf(2 to "disc2/Parasite Eve CD2.chd"), names(DiscSorter.sort(dir, null)))
    }

    @Test
    fun aDiscWithoutANumberIsDisc1UnlessItsPlaceIsGiven() {
        file("pe.chd")
        assertEquals(mapOf(2 to "disc2/pe.chd"), names(DiscSorter.sort(dir, 2)))
        file("other.chd")
        assertEquals(mapOf(1 to "disc1/other.chd"), names(DiscSorter.sort(dir, null)))
    }

    @Test
    fun twoDiscsWithoutNumbersGoInNameOrder() {
        file("b.chd")
        file("A.chd")
        assertEquals(mapOf(1 to "disc1/A.chd", 2 to "disc2/b.chd"), names(DiscSorter.sort(dir, null)))
    }

    @Test
    fun subchannelDataGoesWithItsDisc() {
        file("Parasite Eve (Disc 1).chd")
        file("Parasite Eve (Disc 1).sbi")
        DiscSorter.sort(dir, null)
        assertTrue(File(dir, "disc1/Parasite Eve (Disc 1).sbi").isFile)
    }

    @Test
    fun tooManyDiscs() {
        file("a.chd"); file("b.chd"); file("c.chd")
        assertEquals(DiscSorter.SortException.Reason.TOO_MANY_DISCS, assertFailsWith<DiscSorter.SortException> { DiscSorter.sort(dir, null) }.reason)
    }

    @Test
    fun twoDiscsForOnePlace() {
        file("a.chd"); file("b.chd")
        assertEquals(DiscSorter.SortException.Reason.TOO_MANY_DISCS, assertFailsWith<DiscSorter.SortException> { DiscSorter.sort(dir, 1) }.reason)
    }

    @Test
    fun noDiscAmongTheFiles() {
        file("readme.txt")
        assertEquals(DiscSorter.SortException.Reason.NO_DISC, assertFailsWith<DiscSorter.SortException> { DiscSorter.sort(dir, null) }.reason)
        assertNull(DiscSorter.startFile(dir))
    }

    @Test
    fun discNumbersInNames() {
        assertEquals(1, DiscSorter.discNumber("Parasite Eve (USA) (Disc 1).cue"))
        assertEquals(2, DiscSorter.discNumber("parasite_eve_cd2.chd"))
        assertEquals(2, DiscSorter.discNumber("PE DISK-02.iso"))
        assertNull(DiscSorter.discNumber("Parasite Eve.chd"))
        assertNull(DiscSorter.discNumber("Parasite Eve (Disc 12).chd"))
    }

    /** A PSP eboot: "\0PBP", and at 0x24 where its disc data starts. */
    private fun pbp(name: String, magic: String) = File(dir, name).apply {
        val bytes = ByteArray(0x200)
        bytes[1] = 'P'.code.toByte(); bytes[2] = 'B'.code.toByte(); bytes[3] = 'P'.code.toByte()
        bytes[0x25] = 0x01
        magic.toByteArray().copyInto(bytes, 0x100)
        writeBytes(bytes)
    }

    @Test
    fun pbpWithBothDiscs() {
        assertTrue(DiscSorter.isMultiDiscPbp(pbp("both.pbp", "PSTITLEIMG000000")))
        assertFalse(DiscSorter.isMultiDiscPbp(pbp("one.pbp", "PSISOIMG0000")))
        assertFalse(DiscSorter.isMultiDiscPbp(file("short.pbp", "x")))
    }
}
