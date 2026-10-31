package com.logie.warcraft.client

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaliaFilesTest {
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun temp(): File = Files.createTempDirectory("ralia").toFile()

    @Test
    fun theZipsCharacterTexturesAtAnyDepthAndNothingElse() {
        assertEquals("Character/Human/Female/a.blp", RaliaFiles.entryPath("Character/Human/Female/a.blp"))
        assertEquals("character/Orc/Female/b.BLP", RaliaFiles.entryPath("Ralia Patches/Wrath/character/Orc/Female/b.BLP"))
        assertEquals("Character/Human/Female/a.blp", RaliaFiles.entryPath("Character\\Human\\Female\\a.blp"))
        assertNull(RaliaFiles.entryPath("Item/ObjectComponents/Collections/c.blp"))
        assertNull(RaliaFiles.entryPath("Character/Pandaren/Female/pandarenfemale.m2"))
        assertNull(RaliaFiles.entryPath("Character/../../files/client/Wow.exe.blp"))
        assertNull(RaliaFiles.entryPath("Character"))
        assertNull(RaliaFiles.entryPath("readme.txt"))
    }

    @Test
    fun unpackingKeepsOnlyTheTexturesAndReplacesTheOldOnes() {
        val root = temp()
        try {
            val first = zip("Character/Human/Female/old.blp" to "1", "readme.txt" to "x")
            assertEquals(1, RaliaFiles.unpack(root, ByteArrayInputStream(first)))
            val second = zip("x/Character/Human/Female/a.blp" to "2", "x/Character/Worgen/Female/b.blp" to "3",
                "x/Item/c.blp" to "4", "x/Character/Pandaren/Female/p.m2" to "5")
            assertEquals(2, RaliaFiles.unpack(root, ByteArrayInputStream(second)))
            val files = RaliaFiles.files(root)
            assertEquals(listOf("Character/Human/Female/a.blp", "Character/Worgen/Female/b.blp"),
                files.walkTopDown().filter { it.isFile }.map { it.relativeTo(files).invariantSeparatorsPath }.sorted().toList())
            assertTrue(RaliaFiles.hasFiles(root))
            assertFalse(File(RaliaFiles.folder(root), "files.new").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aZipWithoutTexturesLeavesTheKeptOnes() {
        val root = temp()
        try {
            RaliaFiles.unpack(root, ByteArrayInputStream(zip("Character/Human/Female/a.blp" to "1")))
            assertFailsWith<RaliaFiles.NoTexturesException> {
                RaliaFiles.unpack(root, ByteArrayInputStream(zip("readme.txt" to "x")))
            }
            assertEquals("1", File(RaliaFiles.files(root), "Character/Human/Female/a.blp").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun onNeedsTheTexturesAndOffTakesTheModsPatchOutOfTheGame() {
        val root = temp()
        val client = File(root, "client")
        try {
            assertFailsWith<RaliaFiles.NoTexturesException> { RaliaFiles.setOn(root, client, true) }
            assertFalse(RaliaFiles.isOn(root))
            RaliaFiles.unpack(root, ByteArrayInputStream(zip("Character/Human/Female/a.blp" to "1")))
            RaliaFiles.setOn(root, client, true)
            assertTrue(RaliaFiles.isOn(root))
            assertTrue(File(root, "ralia/enabled").isFile)

            val locale = File(client, "Data/enUS").apply { mkdirs() }
            File(locale, "patch-enUS-Z.MPQ").writeText("patch")
            File(locale, "patch-enUS-Z.MPQ.ralia").writeText("stamp")
            File(locale, "patch-enUS-V.MPQ").writeText("someone else's")
            RaliaFiles.setOn(root, client, false)
            assertFalse(RaliaFiles.isOn(root))
            assertFalse(File(locale, "patch-enUS-Z.MPQ").exists())
            assertFalse(File(locale, "patch-enUS-Z.MPQ.ralia").exists())
            assertTrue(File(locale, "patch-enUS-V.MPQ").exists())

            // a patch-Z of the language's that isn't the mod's (no .ralia beside it) stays
            File(locale, "patch-enUS-Z.MPQ").writeText("not ours")
            assertFalse(RaliaFiles.removePatch(client))
            assertTrue(File(locale, "patch-enUS-Z.MPQ").exists())
            assertTrue(RaliaFiles.hasFiles(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
