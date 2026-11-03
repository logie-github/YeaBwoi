// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.export.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Names that differ only in case, which shared storage can't tell apart. */
class CaseSafeTest {
    @Test fun `names with no twin are stored as they are`() {
        assertTrue(CaseSafe.stored(listOf("client/Data/a.MPQ", "client/Data/b.MPQ", "data/cameras/x.m2")).isEmpty())
    }

    @Test fun `both twins get a name of their own and the others are left alone`() {
        val stored = CaseSafe.stored(listOf("data/cameras/FlyByWorgen.m2", "data/cameras/flybyworgen.m2", "data/cameras/other.m2"))
        assertEquals(2, stored.size)
        val a = stored.getValue("data/cameras/FlyByWorgen.m2")
        val b = stored.getValue("data/cameras/flybyworgen.m2")
        assertNotEquals(a.lowercase(), b.lowercase())
        assertTrue(a.startsWith("data/cameras/FlyByWorgen.m2~") && b.startsWith("data/cameras/flybyworgen.m2~"))
        // The same input gives the same names, in any order.
        assertEquals(stored, CaseSafe.stored(listOf("data/cameras/other.m2", "data/cameras/flybyworgen.m2", "data/cameras/FlyByWorgen.m2")))
    }

    @Test fun `folders that differ only in case are kept apart too`() {
        val stored = CaseSafe.stored(listOf("Data/x/a.blp", "data/x/a.blp"))
        assertEquals(2, stored.size)
        assertNotEquals(stored.getValue("Data/x/a.blp").lowercase(), stored.getValue("data/x/a.blp").lowercase())
    }

    @Test fun `names the destination can't hold get safe names`() {
        val paths = listOf("a/what?.dbc", "a/ends.", "a/ends2 ", "a/ok.dbc", "a/" + "x".repeat(250) + ".blp", "a/pipe|colon:.txt")
        val stored = CaseSafe.stored(paths)
        assertEquals(setOf("a/what?.dbc", "a/ends.", "a/ends2 ", "a/" + "x".repeat(250) + ".blp", "a/pipe|colon:.txt"), stored.keys)
        for (v in stored.values) {
            val name = v.substringAfterLast('/')
            assertTrue(CaseSafe.holdsExactly(name), v)
        }
        assertTrue(stored.getValue("a/what?.dbc").startsWith("a/what_.dbc~"))
    }

    @Test fun `names that differ only in Unicode form are twins`() {
        val stored = CaseSafe.stored(listOf("a/caf\u00e9.m2", "a/cafe\u0301.m2"))
        assertEquals(2, stored.size)
    }

    @Test fun `the same name in two different folders is no twin`() {
        assertTrue(CaseSafe.stored(listOf("a/File.m2", "b/file.m2")).isEmpty())
    }
}

class CaseSafeRunTest {
    private class Src(val files: Map<String, ByteArray>) : SourceFs {
        override fun list(dir: String): List<SourceEntry> {
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            val names = LinkedHashMap<String, SourceEntry>()
            for ((p, b) in files) {
                if (!p.startsWith(prefix)) continue
                val rest = p.removePrefix(prefix); val first = rest.substringBefore('/')
                names[first] = if ('/' in rest) SourceEntry(first, true, false, 0, 0) else SourceEntry(first, false, true, b.size.toLong(), 1L)
            }
            return names.values.sortedBy { it.name }
        }
        override fun open(path: String): InputStream = ByteArrayInputStream(files.getValue(path))
    }

    /** A destination that, like shared storage, treats names that differ in case as one file. */
    private class CaseBlindSink : ExportSink {
        val files = HashMap<String, ByteArray>()
        private fun key(p: String) = p.lowercase()
        override fun size(path: String) = files[key(path)]?.size?.toLong()
        override fun read(path: String): InputStream? = files[key(path)]?.let { ByteArrayInputStream(it) }
        override fun delete(path: String) { files.remove(key(path)) }
        override fun write(path: String): OutputStream = object : ByteArrayOutputStream() {
            override fun close() { super.close(); files[key(path)] = toByteArray() }
        }
    }

    @Test fun `a destination that renames a file anyway is followed in the manifest`() {
        val source = Src(mapOf("client/Wow.exe" to "wow".toByteArray(), "client/x.dbc" to "d".toByteArray()))
        val inner = CaseBlindSink()
        val sink = object : ExportSink by inner {
            override fun storedAs(path: String) = if (path == "files/client/x.dbc") "files/client/x (1).dbc" else null
        }
        val manifest = Exporter.run(source, ExportPlanner.scan(source), sink, ExportContext("1", "/x", "2026-11-03T10:00:00Z", emptyMap()))
        assertEquals(mapOf("client/x.dbc" to "client/x (1).dbc"), manifest.renames)
    }

    @Test fun `twins survive a destination that can't tell them apart, and verify finds them`() {
        val source = Src(mapOf(
            "data/cameras/FlyByWorgen.m2" to "one".toByteArray(),
            "data/cameras/flybyworgen.m2" to "two!".toByteArray(),
            "client/Wow.exe" to "wow".toByteArray(),
        ))
        val sink = CaseBlindSink()
        val context = ExportContext("1", "/x", "2026-11-03T10:00:00Z", emptyMap())
        val plan = ExportPlanner.scan(source)
        val manifest = Exporter.run(source, plan, sink, context)
        assertEquals(2, manifest.renames.size)
        // Both bodies are in the destination, each under its own name.
        val a = sink.read("files/" + manifest.renames.getValue("data/cameras/FlyByWorgen.m2"))!!.readBytes()
        val b = sink.read("files/" + manifest.renames.getValue("data/cameras/flybyworgen.m2"))!!.readBytes()
        assertEquals("one", a.toString(Charsets.UTF_8))
        assertEquals("two!", b.toString(Charsets.UTF_8))
        // The manifest says so for the reader, and verify follows it.
        assertEquals(manifest.renames, ExportManifest.parse(manifest.toJson()).renames)
        assertTrue(ExportVerifier.verify(sink).ok)
    }
}
