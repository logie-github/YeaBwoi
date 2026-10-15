package com.logie.warcraft.turtle.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server's data imported from a zip made on a computer ([TurtleDataImport]): which files are
 * taken, which are refused, and Turtle's ctl.sh taking MoveMapGen's finished build for one.
 */
class TurtleDataImportTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** The first 16 bytes of a tile Tortoise's MoveMapGen made (map 13, tile 0,0): 'MMAP', navmesh 7, generator 6. */
    private val realTile = bytes(0x50, 0x41, 0x4d, 0x4d, 0x07, 0, 0, 0, 0x06, 0, 0, 0, 0x68, 0xbe, 0x01, 0)

    @Test
    fun takesTheFourFoldersAtTheTopOrInsideAnother() {
        assertEquals("mmaps/0130000.mmtile", TurtleDataImport.target("mmaps/0130000.mmtile"))
        assertEquals("maps/0013231.map", TurtleDataImport.target("turtle-server-data/maps/0013231.map"))
        assertEquals("dbc/Spell.dbc", TurtleDataImport.target("DBC/Spell.dbc"))
        assertEquals("vmaps/000.vmtree", TurtleDataImport.target("data\\vmaps\\000.vmtree"))
    }

    @Test
    fun leavesOutEverythingElse() {
        for (name in listOf(
            "readme.txt", "maps/", "Buildings/dir_bin", "mmaps/sub/0130000.mmtile", "maps/.DS_Store",
            "../maps/0013231.map", "maps/../../etc/passwd", "/maps/0013231.map", "maps//0013231.map",
            "C:/maps/0013231.map", "maps/./0013231.map", "Cameras/FlyBy.m2",
        )) {
            assertNull(TurtleDataImport.target(name), name)
        }
    }

    @Test
    fun takesTortoisesOwnFormats() {
        assertNull(TurtleDataImport.problem("mmaps/0130000.mmtile", realTile))
        assertNull(TurtleDataImport.problem("mmaps/013.mmap", ByteArray(16)))
        assertNull(TurtleDataImport.problem("maps/0013231.map", "MAPSz1.4".toByteArray() + ByteArray(8)))
        assertNull(TurtleDataImport.problem("vmaps/000.vmtree", "VMAP_6.0".toByteArray() + ByteArray(8)))
        assertNull(TurtleDataImport.problem("vmaps/000_31_32.vmtile", "VMAP_6.0".toByteArray()))
        assertNull(TurtleDataImport.problem("vmaps/Stormwind.wmo.vmo", "VMAP_6.0".toByteArray()))
        assertNull(TurtleDataImport.problem("vmaps/temp_gameobject_models", ByteArray(16)))
        assertNull(TurtleDataImport.problem("dbc/Spell.dbc", "WDBC".toByteArray()))
    }

    @Test
    fun refusesDataInAnotherFormat() {
        val otherGenerator = realTile.copyOf().also { it[8] = 5 }
        assertEquals(TurtleDataImport.Problem.FOREIGN, TurtleDataImport.problem("mmaps/0130000.mmtile", otherGenerator))
        assertEquals(TurtleDataImport.Problem.FOREIGN, TurtleDataImport.problem("mmaps/0130000.mmtile", ByteArray(16)))
        assertEquals(TurtleDataImport.Problem.FOREIGN, TurtleDataImport.problem("mmaps/0130000.mmtile", realTile.copyOf(6)))
        assertEquals(TurtleDataImport.Problem.FOREIGN, TurtleDataImport.problem("maps/0013231.map", "MAPSv1.3".toByteArray() + ByteArray(8)))
        assertEquals(TurtleDataImport.Problem.FOREIGN, TurtleDataImport.problem("maps/0013231.map", ByteArray(16)))
        assertEquals(TurtleDataImport.Problem.FOREIGN, TurtleDataImport.problem("vmaps/000.vmtree", "VMAP_4.0".toByteArray()))
    }

    @Test
    fun leavesOutNotesBesideTheData() {
        assertEquals(TurtleDataImport.Problem.NOT_DATA, TurtleDataImport.problem("mmaps/offmesh.txt", ByteArray(16)))
        assertEquals(TurtleDataImport.Problem.NOT_DATA, TurtleDataImport.problem("maps/readme.txt", ByteArray(16)))
        assertEquals(TurtleDataImport.Problem.NOT_DATA, TurtleDataImport.problem("vmaps/log.txt", ByteArray(16)))
        assertEquals(TurtleDataImport.Problem.NOT_DATA, TurtleDataImport.problem("dbc/component.wow-enUS.txt", ByteArray(16)))
    }

    @Test
    fun pathfindingAloneNeedsTheDataThePhoneMade() {
        assertTrue(TurtleDataImport.complete(setOf("mmaps"), setOf("dbc", "maps", "vmaps")))
        assertFalse(TurtleDataImport.complete(setOf("mmaps"), emptySet()))
        assertFalse(TurtleDataImport.complete(setOf("maps", "mmaps"), setOf("vmaps")))
        assertTrue(TurtleDataImport.complete(setOf("dbc", "maps", "vmaps", "mmaps"), emptySet()))
    }

    /**
     * Tortoise's MoveMapGen --silent ends with 1 once it has built everything (generator.cpp:
     * `return silent ? 1 : finish(...)`), seen on a real build; the app takes anything but 0 for a
     * failure, so ctl.sh passes 1 on as 0 and every other code as it is.
     */
    @Test
    fun theServerScriptTakesMoveMapGensFinishedBuildForSuccess() {
        val base = createTempDirectory("mmaps").toFile()
        try {
            val files = File(base, "files/turtle")
            val tool = File(files, "server/bin/MoveMapGen")
            tool.parentFile.mkdirs()
            File(files, "data.work").mkdirs()
            for ((exit, expected) in listOf(1 to 0, 0 to 0, 255 to 255, 2 to 2)) {
                tool.writeText("#!/bin/sh\nexit $exit\n")
                tool.setExecutable(true)
                val process = ProcessBuilder("sh", File("../turtle/server/ctl.sh").absolutePath, "mmaps")
                    .apply { environment()["FILES"] = files.path }
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(expected, process.waitFor(), "MoveMapGen exited $exit: $output")
            }
        } finally {
            base.deleteRecursively()
        }
    }
}
