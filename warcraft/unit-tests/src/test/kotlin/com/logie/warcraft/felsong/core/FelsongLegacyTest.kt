package com.logie.warcraft.felsong.core

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FelsongLegacyTest {
    @Test
    fun removesTheOldLegionAndNothingBesideIt() {
        val files = createTempDirectory("files").toFile()
        val external = createTempDirectory("external").toFile()
        val outside = createTempDirectory("outside").toFile().also { java.io.File(it, "keep.txt").writeText("x") }
        try {
            // The old folder, with a link that leads out of it (a Windows prefix's drive link) and a read-only file.
            val old = java.io.File(files, "spp-legion/Server/Database/data").apply { mkdirs() }
            java.io.File(old, "ibdata1").writeText("db")
            java.io.File(files, "spp-legion/client.part").let { it.writeText("42 GB"); it.setReadOnly() }
            Files.createSymbolicLink(java.io.File(files, "spp-legion/link").toPath(), outside.toPath())
            java.io.File(external, "spp-legion/client").mkdirs()
            // Beside it: Legion's own folder, the other games'.
            java.io.File(files, "felsong/client").mkdirs()
            java.io.File(files, "felsong/install.json").writeText("{}")
            java.io.File(files, "turtle").mkdirs()
            java.io.File(files, "server").mkdirs()

            assertTrue(FelsongLegacy.removeSppFolders(listOf(files, external)))

            assertFalse(java.io.File(files, "spp-legion").exists())
            assertFalse(java.io.File(external, "spp-legion").exists())
            assertEquals("x", java.io.File(outside, "keep.txt").readText(), "a link isn't followed")
            assertTrue(java.io.File(files, "felsong/install.json").isFile)
            assertTrue(java.io.File(files, "turtle").isDirectory && java.io.File(files, "server").isDirectory)
            // Nothing to remove is fine too.
            assertTrue(FelsongLegacy.removeSppFolders(listOf(files)))
        } finally {
            files.deleteRecursively(); external.deleteRecursively(); outside.deleteRecursively()
        }
    }
}
