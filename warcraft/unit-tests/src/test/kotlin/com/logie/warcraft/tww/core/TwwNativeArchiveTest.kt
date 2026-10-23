// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww.core

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
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

    @Test fun hiddenFoldersAreFolders() {
        // 7-Zip lists a 7z entry's Windows letters, then any Unix mode; a hidden folder is `HD`.
        assertTrue(TwwNativeArchive.isDirectory(mapOf("Attributes" to "HD")))
        assertTrue(TwwNativeArchive.isDirectory(mapOf("Attributes" to "D")))
        assertTrue(TwwNativeArchive.isDirectory(mapOf("Attributes" to "D_ drwxr-xr-x")))
        assertTrue(TwwNativeArchive.isDirectory(mapOf("Folder" to "+")))
        assertFalse(TwwNativeArchive.isDirectory(mapOf("Attributes" to "RA")))
        assertFalse(TwwNativeArchive.isDirectory(mapOf("Attributes" to "A -rw-r--r--")))
        assertFalse(TwwNativeArchive.isDirectory(mapOf("Folder" to "-", "Attributes" to "A")))
    }

    /** As in SPP Legion's repack: a hidden `.git` folder (attributes 0x12) with a read-only file in it. */
    @Test fun nativeBackendExtractsHiddenFolders() = temporary { root ->
        assertTrue(extractor.canExecute(), "CI must provide 7-Zip for archive integration tests")
        val archive = File(root, "hidden.7z")
        SevenZOutputFile(archive).use { out ->
            fun folder(name: String, attributes: Int) {
                out.putArchiveEntry(out.createArchiveEntry(root, name).apply { isDirectory = true; hasWindowsAttributes = true; windowsAttributes = attributes })
                out.closeArchiveEntry()
            }
            fun file(name: String, attributes: Int, text: String) {
                val bytes = text.toByteArray()
                out.putArchiveEntry(out.createArchiveEntry(root, name).apply { isDirectory = false; setHasStream(true); size = bytes.size.toLong(); hasWindowsAttributes = true; windowsAttributes = attributes })
                out.write(bytes)
                out.closeArchiveEntry()
            }
            folder("core", 0x10)
            folder("core/.git", 0x12)
            file("core/.git/HEAD", 0x20, "ref: refs/heads/master\n")
            folder("core/.git/objects", 0x10)
            file("core/.git/objects/pack.idx", 0x21, "idx")
            file("core/readme.txt", 0x20, "hi")
        }
        val stage = File(root, "stage")
        TwwArchive.extract(archive, stage, nativeExtractor = extractor)
        assertTrue(File(stage, "core/.git").isDirectory)
        assertEquals("ref: refs/heads/master\n", File(stage, "core/.git/HEAD").readText())
        assertEquals("idx", File(stage, "core/.git/objects/pack.idx").readText())
        assertEquals("hi", File(stage, "core/readme.txt").readText())
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
