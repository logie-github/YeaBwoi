package com.logie.wifecraft.creator

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Release 1.0.17's move to a pinned game build deleted imported body files; they come back from the import's own copy. */
class ImportRepairTest {
    private fun root(): File = Files.createTempDirectory("repair").toFile()

    @Test
    fun theMigrationLeavesTheUsersImportedFilesAlone() {
        val r = root()
        val game = File(r, "game").apply { mkdirs() }
        File(game, "2000000555").writeBytes("MD21....".toByteArray())   // an imported body file that looks like a model
        File(game, "123456").writeBytes("MD21....".toByteArray())       // the game's own model, fetched before the pin
        val gone = GameTables.migrate(r)
        assertEquals(1, gone)
        assertTrue(File(game, "2000000555").isFile)
        assertFalse(File(game, "123456").exists())
    }

    @Test
    fun missingImportedFilesAreRestoredFromTheImportsOwnCopy() {
        val r = root()
        val kind = File(r, "imports/wrathpp-body").apply { mkdirs() }
        File(kind, "HumanFemaleWpp1.m2").writeBytes("shape".toByteArray())
        File(r, "imports/ids.json").writeText(JSONObject().put("wrathpp-body/HumanFemaleWpp1.m2", 2000000555).toString())
        File(r, "game").mkdirs()
        assertFalse(File(r, "game/2000000555").exists())
        ImportStore(r, MapNames(mapOf("x" to 1)))
        assertEquals("shape", File(r, "game/2000000555").readText())
    }

    @Test
    fun anIdListEntryThatLeavesTheImportsFolderIsNotCopied() {
        val r = root()
        File(r, "imports").mkdirs()
        File(r, "secret.txt").writeText("no")
        File(r, "imports/ids.json").writeText(JSONObject().put("../secret.txt", 2000000777).toString())
        ImportStore(r, MapNames(mapOf("x" to 1)))
        assertFalse(File(r, "game/2000000777").exists())
    }
}
