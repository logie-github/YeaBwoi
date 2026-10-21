// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.tww

import com.logie.warcraft.tww.core.TwwArchive
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TwwArchiveTest {
    private fun temporary(block: (File) -> Unit) {
        val folder = Files.createTempDirectory("tww-archive-").toFile()
        try { block(folder) } finally { folder.deleteRecursively() }
    }

    private fun zip(root: File, vararg paths: String): File = File(root, "input.zip").apply {
        ZipOutputStream(outputStream()).use { stream ->
            for (path in paths) {
                stream.putNextEntry(ZipEntry(path)); stream.write("archive payload".toByteArray()); stream.closeEntry()
            }
        }
    }

    @Test fun extractsIntoSeparateStagingAndReportsRealBytes() = temporary { root ->
        val archive = zip(root, "Draconic/Core/worldserver.conf", "Draconic/mmaps/0000.mmap")
        val stage = File(root, "stage")
        var total = 0L
        TwwArchive.extract(archive, stage, progress = { bytes, _ -> total = bytes })
        assertEquals(30L, total)
        assertEquals("archive payload", File(stage, "Draconic/mmaps/0000.mmap").readText())
        assertTrue(archive.isFile)
    }

    @Test fun extractsSevenZipByMagicEvenWhenDownloadedFilenameIsGeneric() = temporary { root ->
        val archive = File(root, "drive-download.archive")
        val payload = "map parameters".toByteArray()
        SevenZOutputFile(archive).use { stream ->
            val entry = SevenZArchiveEntry().apply { name = "MMAPS/0000.mmap"; size = payload.size.toLong() }
            stream.putArchiveEntry(entry); stream.write(payload); stream.closeArchiveEntry()
        }
        val stage = File(root, "stage")
        TwwArchive.extract(archive, stage)
        assertEquals("map parameters", File(stage, "MMAPS/0000.mmap").readText())
    }

    @Test fun traversalWindowsAliasesAndCaseCollisionsRollBack() = temporary { root ->
        for (path in listOf("../outside", "C:/outside", "client/CON.txt", "client/file.", "client/file:stream")) {
            val stage = File(root, "stage")
            assertFailsWith<TwwArchive.ArchiveException> { TwwArchive.extract(zip(root, path), stage) }
            assertFalse(stage.exists())
            assertFalse(File(root, "outside").exists())
        }
        assertFailsWith<TwwArchive.ArchiveException> {
            TwwArchive.extract(zip(root, "Data/a", "data/b"), File(root, "stage"))
        }
        assertFalse(File(root, "stage").exists())
    }

    @Test fun symlinksAreRejectedBeforeInstallation() = temporary { root ->
        val archive = File(root, "link.zip")
        ZipArchiveOutputStream(archive).use { stream ->
            val entry = ZipArchiveEntry("escape").apply { unixMode = 0xa1ff }
            stream.putArchiveEntry(entry); stream.write("../outside".toByteArray()); stream.closeArchiveEntry()
        }
        val stage = File(root, "stage")
        assertFailsWith<TwwArchive.ArchiveException> { TwwArchive.extract(archive, stage) }
        assertFalse(stage.exists())
    }

    @Test fun cancelledExtractionCleansTemporaryFiles() = temporary { root ->
        val stage = File(root, "stage")
        assertFailsWith<TwwArchive.ArchiveException> { TwwArchive.extract(zip(root, "file"), stage, cancelled = { true }) }
        assertFalse(stage.exists())
    }

    @Test fun payloadCorruptionIsDetectedByCrc() = temporary { root ->
        val payload = "unique archive payload".toByteArray()
        val archive = File(root, "corrupt.zip")
        ZipOutputStream(archive.outputStream()).use { stream ->
            val entry = ZipEntry("file").apply { method = ZipEntry.STORED; size = payload.size.toLong(); crc = CRC32().apply { update(payload) }.value }
            stream.putNextEntry(entry); stream.write(payload); stream.closeEntry()
        }
        val bytes = archive.readBytes()
        val offset = bytes.indices.first { at -> at + payload.size <= bytes.size && payload.indices.all { bytes[at + it] == payload[it] } }
        bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
        archive.writeBytes(bytes)
        val stage = File(root, "stage")
        assertFailsWith<TwwArchive.ArchiveException> { TwwArchive.extract(archive, stage) }
        assertFalse(stage.exists())
    }

    @Test fun existingInstalledFolderCannotBeUsedAsStaging() = temporary { root ->
        val installed = File(root, "installed").apply { mkdirs() }
        File(installed, "keep").writeText("existing installation")
        assertFailsWith<TwwArchive.ArchiveException> { TwwArchive.extract(zip(root, "file"), installed) }
        assertEquals("existing installation", File(installed, "keep").readText())
    }
}
