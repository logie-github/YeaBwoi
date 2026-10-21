// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww.core

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class TwwNativeArchiveTest {
    private val extractor = File("/usr/bin/7z")
    private fun temporary(block: (File) -> Unit) {
        val root = Files.createTempDirectory("tww-native-archive-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun nativeBackendExtractsBcj2WithRealCrcAndSizes() = temporary { root ->
        assertTrue(extractor.canExecute(), "CI must provide 7-Zip for archive integration tests")
        val payload = File(root, "toy.exe").apply { writeBytes(ByteArray(65536) { if (it % 8 == 0) 0xe8.toByte() else (it % 253).toByte() }) }
        val archive = File(root, "bcj2.7z")
        val create = ProcessBuilder(extractor.path, "a", "-t7z", "-m0=BCJ2", "-m1=LZMA2", "-m2=LZMA2", "-m3=LZMA2", "-mb0:1", "-mb0s1:2", "-mb0s2:3", archive.path, payload.path)
            .redirectErrorStream(true).start()
        val output = create.inputStream.bufferedReader().readText()
        assertEquals(0, create.waitFor(), output)
        val stage = File(root, "stage")
        TwwArchive.extract(archive, stage, nativeExtractor = extractor)
        assertContentEquals(payload.readBytes(), File(stage, "toy.exe").readBytes())
    }

    @Test fun nativeBackendRejectsTraversalBeforeExtraction() = temporary { root ->
        val archive = File(root, "bad.zip")
        ZipOutputStream(archive.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("../outside")); zip.write(byteArrayOf(1)); zip.closeEntry() }
        val stage = File(root, "stage").apply { mkdirs() }
        assertFailsWith<TwwArchive.ArchiveException> { TwwNativeArchive.extract(extractor, archive, stage, { false }, { _, _ -> }) }
        assertFalse(File(root, "outside").exists())
        assertTrue(stage.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationStopsNativeExtractionAndRemovesStaging() = temporary { root ->
        val source = File(root, "payload").apply { writeText("toy data") }
        val archive = File(root, "input.7z")
        val create = ProcessBuilder(extractor.path, "a", archive.path, source.path).redirectErrorStream(true).start()
        create.inputStream.bufferedReader().readText(); assertEquals(0, create.waitFor())
        val stage = File(root, "stage")
        assertFailsWith<TwwArchive.ArchiveException> { TwwArchive.extract(archive, stage, cancelled = { true }, nativeExtractor = extractor) }
        assertFalse(stage.exists())
        assertTrue(archive.isFile)
    }
}
