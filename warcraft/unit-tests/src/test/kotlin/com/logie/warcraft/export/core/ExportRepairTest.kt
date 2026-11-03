// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.export.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FlatSource(val files: Map<String, ByteArray>) : SourceFs {
    val opened = mutableListOf<String>()
    override fun list(dir: String): List<SourceEntry> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        return files.filterKeys { it.startsWith(prefix) }.entries.groupBy { it.key.removePrefix(prefix).substringBefore('/') }
            .map { (name, entries) ->
                val e = entries.first()
                if ('/' in e.key.removePrefix(prefix)) SourceEntry(name, true, false, 0, 0) else SourceEntry(name, false, true, e.value.size.toLong(), 1L)
            }
    }
    override fun open(path: String): InputStream { opened += path; return ByteArrayInputStream(files.getValue(path)) }
}

private class FlatSink : ExportSink {
    val files = HashMap<String, ByteArray>()
    override fun size(path: String) = files[path]?.size?.toLong()
    override fun read(path: String): InputStream? = files[path]?.let { ByteArrayInputStream(it) }
    override fun delete(path: String) { files.remove(path) }
    override fun write(path: String): OutputStream = object : ByteArrayOutputStream() {
        override fun close() { super.close(); files[path] = toByteArray() }
    }
}

class ExportRepairTest {
    private val context = ExportContext("1", "/files", "2026-11-03T00:00:00Z", emptyMap())
    private fun src() = FlatSource(mapOf("client/Wow.exe" to "wow".toByteArray(), "mysql/ibdata1" to "database".toByteArray()))

    @Test fun `verify forgets a bad file so running the export again repairs it`() {
        val src = src()
        val sink = FlatSink()
        val plan = ExportPlanner.scan(src)
        Exporter.run(src, plan, sink, context)
        sink.files["files/mysql/ibdata1"] = "databasX".toByteArray()
        assertFalse(ExportVerifier.verify(sink).ok)

        src.opened.clear()
        Exporter.run(src, plan, sink, context)
        assertEquals(listOf("mysql/ibdata1"), src.opened)
        assertTrue(ExportVerifier.verify(sink).ok)
    }

    @Test fun `remaining bytes count only what the destination lacks`() {
        val src = src()
        val sink = FlatSink()
        val plan = ExportPlanner.scan(src)
        assertEquals(11L, ExportPlanner.remainingBytes(plan, sink))
        Exporter.run(src, plan, sink, context)
        assertEquals(0L, ExportPlanner.remainingBytes(plan, sink))
        sink.files.remove("files/client/Wow.exe")
        assertEquals(3L, ExportPlanner.remainingBytes(plan, sink))
    }
}
