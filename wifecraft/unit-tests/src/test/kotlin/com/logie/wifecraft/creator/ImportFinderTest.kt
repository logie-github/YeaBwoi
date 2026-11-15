package com.logie.wifecraft.creator

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportFinderTest {
    @Test fun findsByNameInTheFirstFolderThatHasItWhateverTheCase() {
        val root = Files.createTempDirectory("finder").toFile()
        File(root, "Documents").mkdirs(); File(root, "Download").mkdirs()
        File(root, "Documents/Ralia.ZIP").writeText("d")
        assertEquals("Ralia.ZIP", ImportFinder.find(ImportFinder.folders(root), listOf("ralia.zip"))!!.name)
        File(root, "Download/ralia.zip").writeText("n")
        assertEquals(File(root, "Download"), ImportFinder.find(ImportFinder.folders(root), listOf("ralia.zip"))!!.parentFile)
    }

    @Test fun newestOfSeveralNamesAndNothingWhenAbsent() {
        val root = Files.createTempDirectory("finder").toFile()
        File(root, "Download").mkdirs()
        val old = File(root, "Download/body.zip").apply { writeText("a"); setLastModified(1000) }
        val new = File(root, "Download/wrathpp-body.zip").apply { writeText("b"); setLastModified(9000) }
        assertEquals(new, ImportFinder.find(ImportFinder.folders(root), listOf("wrathpp-body.zip", "body.zip")))
        old.delete(); new.delete()
        assertNull(ImportFinder.find(ImportFinder.folders(root), listOf("wrathpp-body.zip")))
        assertNull(ImportFinder.find(listOf(File(root, "nowhere")), listOf("a.zip")))
    }
}
