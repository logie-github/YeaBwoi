// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.export.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The zip export: one archive in the folder export's layout, checksums and export.json at its end. */
class ZipExportTest {
    private class Mem(val files: Map<String, ByteArray>) : SourceFs {
        override fun list(dir: String): List<SourceEntry> {
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            val names = LinkedHashMap<String, SourceEntry>()
            for ((path, bytes) in files) {
                if (!path.startsWith(prefix)) continue
                val rest = path.removePrefix(prefix)
                val first = rest.substringBefore('/')
                names[first] = if ('/' in rest) SourceEntry(first, true, false, 0, 0) else SourceEntry(first, false, true, bytes.size.toLong(), 5L)
            }
            return names.values.sortedBy { it.name }
        }

        override fun open(path: String): InputStream = ByteArrayInputStream(files.getValue(path))
    }

    private val files = mapOf(
        "client/Data/common.MPQ" to ByteArray(3_000_000) { (it % 251).toByte() },
        "client/Data/enUS/Locale.mpq" to ByteArray(10) { 7 },
        // names that differ only in case: shared storage can't hold both, a zip can
        "client/Data/FlyByWorgen.m2" to byteArrayOf(1, 2, 3),
        "client/Data/flybyworgen.m2" to byteArrayOf(4, 5, 6),
        "mysql/acore_auth/account.ibd" to ByteArray(2048) { 9 },
    )

    private fun context() = ExportContext("1.0.test", "/data/data/com.logie.warcraft/files", "2026-11-04T00:00:00Z", mapOf("wow" to "{}\n"))

    private fun make(): Pair<ByteArray, ExportManifest> {
        val source = Mem(files)
        val plan = ExportPlanner.scan(source)
        val out = ByteArrayOutputStream()
        val manifest = ZipExporter.run(source, plan, out, context())
        return out.toByteArray() to manifest
    }

    private fun entries(zip: ByteArray): List<Pair<String, ByteArray>> {
        val result = ArrayList<Pair<String, ByteArray>>()
        // (Java's own ZipInputStream can't read data-descriptor Zip64 entries; commons-compress, which Blizzard uses too, can.)
        ZipArchiveInputStream(ByteArrayInputStream(zip), "UTF-8", true, true).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                result += e.name to z.readBytes()
            }
        }
        return result
    }

    @Test fun theZipHoldsTheFilesThenPrefsChecksumsAndTheManifestLast() {
        val (zip, manifest) = make()
        val list = entries(zip)
        val names = list.map { it.first }
        assertEquals(ExportManifest.FILE, names.last())
        assertEquals(Checksums.FILE, names[names.size - 2])
        assertTrue("prefs/wow.json" in names)
        for ((path, bytes) in files) {
            val entry = list.firstOrNull { it.first == "files/$path" }
            assertNotNull(entry, path)
            assertTrue(bytes.contentEquals(entry.second), path)
        }
        assertEquals(files.size, manifest.files)
        assertTrue(manifest.renames.isEmpty())
        val parsed = ExportManifest.parse(String(list.last().second))
        assertEquals(files.size, parsed.files)
        assertEquals(files.size, Checksums.parse(String(list[names.size - 2].second)).size)
    }

    @Test fun anIntactZipVerifiesAndADamagedOneIsReported() {
        val (zip, _) = make()
        val ok = ZipExporter.verify(ByteArrayInputStream(zip))
        assertTrue(ok.ok, ok.toString())
        assertEquals(files.size, ok.checked)

        // One file's bytes changed (stored uncompressed, so they can be found and flipped): its checksum no longer matches.
        val marker = byteArrayOf(4, 5, 6)
        val at = zip.indices.first { i -> i + 3 <= zip.size && zip[i] == marker[0] && zip[i + 1] == marker[1] && zip[i + 2] == marker[2] }
        val bad = zip.copyOf().also { it[at] = 99 }
        val report = ZipExporter.verify(ByteArrayInputStream(bad))
        assertFalse(report.ok)
    }

    @Test fun aZipCutOffBeforeTheEndIsNotAnExport() {
        val (zip, _) = make()
        val cut = zip.copyOf(zip.size / 2)
        val report = ZipExporter.verify(ByteArrayInputStream(cut))
        assertNotNull(report.problem)
        assertFalse(report.ok)
    }

    @Test fun stoppingStopsAtOnce() {
        val source = Mem(files)
        val plan = ExportPlanner.scan(source)
        assertFailsWith<ExportCancelled> { ZipExporter.run(source, plan, ByteArrayOutputStream(), context(), isCancelled = { true }) }
    }
}
