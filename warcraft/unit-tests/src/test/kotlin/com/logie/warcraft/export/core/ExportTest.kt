// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.export.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun sha(bytes: ByteArray) = Exporter.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** A source in memory: path to bytes (files only). */
private class MemSource(val files: MutableMap<String, ByteArray> = HashMap(), val modified: MutableMap<String, Long> = HashMap()) : SourceFs {
    var opened = mutableListOf<String>()
    val extraEntries = mutableMapOf<String, SourceEntry>() // special files, by full path

    override fun list(dir: String): List<SourceEntry> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val names = LinkedHashMap<String, SourceEntry>()
        for ((path, bytes) in files) {
            if (!path.startsWith(prefix)) continue
            val rest = path.removePrefix(prefix)
            val first = rest.substringBefore('/')
            names[first] = if ('/' in rest) SourceEntry(first, true, false, 0, 0) else SourceEntry(first, false, true, bytes.size.toLong(), modified[path] ?: 1L)
        }
        for ((path, entry) in extraEntries) if (path.startsWith(prefix) && '/' !in path.removePrefix(prefix)) names[entry.name] = entry
        return names.values.sortedBy { it.name }
    }

    override fun open(path: String): InputStream {
        opened += path
        return ByteArrayInputStream(files.getValue(path))
    }
}

/** A destination in memory; [failAfterBytes] cuts the write of one file short, like a phone dying mid-copy. */
private class MemSink : ExportSink {
    val files = HashMap<String, ByteArray>()
    var writes = mutableListOf<String>()
    var failOn: String? = null

    override fun size(path: String) = files[path]?.size?.toLong()
    override fun read(path: String): InputStream? = files[path]?.let { ByteArrayInputStream(it) }
    override fun delete(path: String) { files.remove(path) }
    override fun write(path: String): OutputStream {
        writes += path
        val buffer = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                super.write(b, off, len)
                files[path] = toByteArray()
                if (path == failOn) throw java.io.IOException("the phone died")
            }
        }
        files[path] = ByteArray(0)
        return buffer
    }
}

private val context = ExportContext("1.2.3", "/data/user/0/com.logie.warcraft/files", "2026-11-03T10:00:00Z", mapOf("environments" to "{}\n"))

private fun source(vararg pairs: Pair<String, String>) = MemSource(pairs.associate { it.first to it.second.toByteArray() }.toMutableMap())

class ExportRulesTest {
    @Test fun `the user's data is exported and only what is regenerated or not data is left out`() {
        val src = source(
            "client/Wow.exe" to "exe", "server/bin/worldserver" to "bin", "mysql/ibdata1" to "db", "mysql/mysqld.pid" to "1",
            "etc/worldserver.conf" to "c", "etc-remix/worldserver.conf" to "c", "data/maps/a" to "m", "data-modules/x" to "x",
            "data-races/x" to "x", "mods/hd.txt" to "x", "modules/chosen.json" to "x", "addons/warperia.tsv" to "x",
            "ralia/files/Character/a" to "x", "alternate-female/files/a" to "x", "base-model/enabled" to "", "races/x" to "x",
            "turtle/client/WoW.exe" to "x", "turtle/mysql/ibdata1" to "x", "turtle/logs/a.log" to "log", "turtle/downloads/c.part" to "p",
            "turtle/client.download/a" to "partial", "turtle/server.old/a" to "old",
            "felsong/mysql/ibdata1" to "x", "felsong/.wine/system.reg" to "wine", "felsong/install.json" to "{}",
            "spp-legion/client/a" to "x", "spp-legion/logs/x" to "log", "tww/server/a" to "x", "tww/downloads/p" to "p", "tww/staging/p" to "p",
            "logs/mariadb.log" to "l", "run/mysqld.pid" to "1", "rootfs/bin/sh" to "r", "imagefs/a" to "r", "cache/a" to "c",
            "client.import/a" to "half", "data.work/a" to "half", "data.download/data.zip.part" to "p", "zones.new/a" to "half", "zones.old/a" to "old",
            "server.old/a" to "old", "mysql.deleting/a" to "gone", "client-realm" to "main", "zones/pack" to "z",
            "client/Data/x.tmp" to "t", "client/Data/y.part" to "t", "unknown-future-folder/a" to "keep",
        )
        val plan = ExportPlanner.scan(src)
        val paths = plan.files.map { it.path }
        assertEquals(
            listOf(
                "addons/warperia.tsv", "alternate-female/files/a", "base-model/enabled", "client-realm", "client/Wow.exe", "data-modules/x",
                "data-races/x", "data/maps/a", "etc-remix/worldserver.conf", "etc/worldserver.conf", "felsong/install.json",
                "felsong/mysql/ibdata1", "mods/hd.txt", "modules/chosen.json", "mysql/ibdata1", "races/x", "ralia/files/Character/a",
                "server/bin/worldserver", "spp-legion/client/a", "turtle/client/WoW.exe", "turtle/mysql/ibdata1", "tww/server/a",
                "unknown-future-folder/a", "zones/pack",
            ),
            paths,
        )
        assertEquals(
            listOf(
                "cache", "client.import", "data.download", "data.work", "imagefs", "logs", "mysql.deleting", "rootfs", "run",
                "server.old", "zones.new", "zones.old",
            ),
            plan.excludedTopLevel,
        )
        assertEquals(
            listOf("felsong/.wine", "spp-legion/logs", "turtle/client.download", "turtle/downloads", "turtle/logs", "turtle/server.old", "tww/downloads", "tww/staging"),
            plan.excludedInside,
        )
        assertEquals(
            listOf("addons", "alternate-female", "base-model", "client", "client-realm", "data", "data-modules", "data-races", "etc", "etc-remix", "felsong", "mods", "modules", "mysql", "races", "ralia", "server", "spp-legion", "turtle", "tww", "unknown-future-folder", "zones"),
            plan.folders,
        )
        assertEquals(plan.files.sumOf { it.size }, plan.totalBytes)
    }

    @Test fun `every exclusion has a reason, and exported names are never matched by accident`() {
        assertTrue(ExportRules.TOP_LEVEL.all { it.reason.isNotBlank() && (it.name != null || it.suffix != null) })
        for (kept in listOf("client", "server", "mysql", "etc", "etc-remix", "data", "data-modules", "data-races", "mods", "modules", "addons", "ralia", "alternate-female", "base-model", "races", "turtle", "felsong", "spp-legion", "tww", "client-realm", "zones")) {
            assertNull(ExportRules.directoryReason(kept), kept)
        }
        // a module or folder merely containing a word is not "logs"
        assertNull(ExportRules.directoryReason("server/logs"))
        assertNull(ExportRules.directoryReason("modules/run"))
        assertNull(ExportRules.fileReason("client/Data/patch-Z.MPQ"))
        assertNotNull(ExportRules.fileReason("client/Data/x.part"))
    }

    @Test fun `links and special files are skipped and counted`() {
        val src = source("client/a" to "x")
        src.extraEntries["client/sock"] = SourceEntry("sock", directory = false, regular = false, size = 0, modified = 0)
        // (the walker lists client's entries)
        val plan = ExportPlanner.scan(src)
        assertEquals(listOf("client/a"), plan.files.map { it.path })
        assertEquals(1, plan.skipped)
    }

    @Test fun `a file name with a tab can't be listed so the export refuses`() {
        assertFailsWith<java.io.IOException> { ExportPlanner.scan(source("client/a\tb" to "x")) }
    }

    @Test fun `groups sum per environment`() {
        val plan = ExportPlanner.scan(source("client/a" to "1234", "mysql/b" to "12", "turtle/c" to "123", "tww/d" to "1", "felsong/e" to "12", "odd/f" to "1"))
        val groups = plan.byGroup()
        assertEquals(2 to 6L, groups[ExportRules.Group.WRATH])
        assertEquals(1 to 3L, groups[ExportRules.Group.TURTLE])
        assertEquals(1 to 2L, groups[ExportRules.Group.LEGION])
        assertEquals(1 to 1L, groups[ExportRules.Group.TWW])
        assertEquals(1 to 1L, groups[ExportRules.Group.OTHER])
        assertEquals(listOf("client/a", "mysql/b"), plan.only(setOf(ExportRules.Group.WRATH)).files.map { it.path })
    }

    @Test fun `space check`() {
        assertTrue(ExportPlanner.fits(100, null))
        assertTrue(ExportPlanner.fits(100, 1L shl 30))
        assertFalse(ExportPlanner.fits(1L shl 30, 1L shl 30))
    }

    @Test fun `scan works on a real folder and does not follow links`() {
        val dir = Files.createTempDirectory("export-scan").toFile()
        try {
            File(dir, "client/Data").mkdirs()
            File(dir, "client/Wow.exe").writeText("exe")
            File(dir, "logs").mkdirs()
            File(dir, "logs/a.log").writeText("x")
            val outside = Files.createTempDirectory("export-outside").toFile()
            File(outside, "secret").writeText("not ours")
            runCatching { Files.createSymbolicLink(File(dir, "client/link").toPath(), outside.toPath()) }
            val plan = ExportPlanner.scan(JavaFileSource(dir))
            assertEquals(listOf("client/Wow.exe"), plan.files.map { it.path })
            assertEquals(listOf("logs"), plan.excludedTopLevel)
            outside.deleteRecursively()
        } finally {
            dir.deleteRecursively()
        }
    }
}

class ExportFormatTest {
    @Test fun `checksums round trip, sorted by path`() {
        val lines = listOf(
            ChecksumLine(sha("b".toByteArray()), 1, "b/file"),
            ChecksumLine(sha("a".toByteArray()), 1, "a/with space/ü.dat"),
            ChecksumLine(sha("".toByteArray()), 0, "z"),
        )
        val text = Checksums.write(lines)
        assertEquals(
            "${sha("a".toByteArray())}\t1\ta/with space/ü.dat\n${sha("b".toByteArray())}\t1\tb/file\n${sha("".toByteArray())}\t0\tz\n",
            text,
        )
        assertEquals(lines.sortedBy { it.path }, Checksums.parse(text))
        // the empty file's well known hash
        assertTrue(text.contains("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\t0\tz"))
    }

    @Test fun `checksums refuse damage`() {
        assertFailsWith<IllegalArgumentException> { Checksums.parse("nothex\t1\ta\n") }
        assertFailsWith<IllegalArgumentException> { Checksums.parse("${sha(byteArrayOf())}\tx\ta\n") }
        assertFailsWith<IllegalArgumentException> { Checksums.write(listOf(ChecksumLine(sha(byteArrayOf()), 0, "a\tb"))) }
        assertFailsWith<IllegalArgumentException> { Checksums.write(listOf(ChecksumLine("ABC", 0, "a"))) }
    }

    @Test fun `partial file round trip and a cut off line is dropped`() {
        val done = mapOf("a" to Checksums.Done(sha(byteArrayOf()), 0, 5), "dir/b c" to Checksums.Done(sha(byteArrayOf(1)), 1, 7))
        val text = Checksums.writePartial(done)
        assertEquals(done, Checksums.parsePartial(text))
        assertEquals(mapOf("a" to done.getValue("a")), Checksums.parsePartial(text.lines().first() + "\n" + text.lines()[1].take(30)))
    }

    @Test fun `manifest round trip and the contract's keys`() {
        val manifest = ExportManifest(
            1, "com.logie.warcraft", "1.2.3", "2026-11-03T10:00:00Z", "/data/user/0/com.logie.warcraft/files", 3, 99L,
            listOf("client", "mysql"), listOf("logs", "run"), "checksums.tsv", listOf("environments", "realms"), listOf("turtle/logs"),
        )
        val json = manifest.toJson()
        assertEquals(manifest, ExportManifest.parse(json))
        val o = org.json.JSONObject(json)
        for (key in listOf("format", "app", "appVersion", "createdAt", "sourceFilesDir", "files", "bytes", "folders", "excluded", "checksums", "prefs")) {
            assertTrue(o.has(key), key)
        }
        assertEquals(1, o.getInt("format"))
        assertEquals("checksums.tsv", o.getString("checksums"))
        assertEquals("client", o.getJSONArray("folders").getString(0))
    }

    @Test fun `preferences dump every kind and read back`() {
        val all = mapOf<String, Any?>(
            "selected" to "turtle", "count" to 7, "big" to 5_000_000_000L, "ratio" to 0.5f, "on" to true,
            "names" to setOf("b", "a\"q"), "path" to "C:\\x/y\nz",
        )
        val json = PrefsDump.toJson(all)
        val o = org.json.JSONObject(json)
        assertEquals("string", o.getJSONObject("selected").getString("type"))
        assertEquals("int", o.getJSONObject("count").getString("type"))
        assertEquals("long", o.getJSONObject("big").getString("type"))
        assertEquals(5_000_000_000L, o.getJSONObject("big").getLong("value"))
        assertEquals("float", o.getJSONObject("ratio").getString("type"))
        assertEquals("boolean", o.getJSONObject("on").getString("type"))
        assertEquals("stringset", o.getJSONObject("names").getString("type"))
        assertEquals("a\"q", o.getJSONObject("names").getJSONArray("value").getString(0))
        assertEquals(all, PrefsDump.parse(json))
        assertEquals("{}\n", PrefsDump.toJson(emptyMap()))
        assertEquals(emptyMap(), PrefsDump.parse("{}"))
        assertEquals("prefs/realms.json", PrefsDump.path("realms"))
        assertFailsWith<IllegalArgumentException> { PrefsDump.toJson(mapOf("x" to 1.5)) }
    }

    @Test fun `the known preferences cover every game's settings and logins`() {
        for (name in listOf("environments", "realms", "login", "game", "mods", "felsong_login", "turtle_login", "spp-login", "tww-installation", "spp-installation", "github")) {
            assertTrue(name in ExportRules.PREFERENCES, name)
        }
        assertEquals(ExportRules.PREFERENCES.size, ExportRules.PREFERENCES.toSet().size)
    }
}

class ExportRunTest {
    private fun fresh() = source("client/Wow.exe" to "wow", "mysql/ibdata1" to "database", "mysql/ib_logfile0" to "log", "etc/a.conf" to "cfg", "logs/x.log" to "no")

    @Test fun `a run copies, hashes while copying, and writes export_json last`() {
        val src = fresh()
        val sink = MemSink()
        val manifest = Exporter.run(src, ExportPlanner.scan(src), sink, context)

        assertEquals("database", sink.files.getValue("files/mysql/ibdata1").decodeToString())
        assertNull(sink.files["files/logs/x.log"])
        assertEquals("export.json", sink.writes.last())
        assertTrue(sink.writes.indexOf("checksums.tsv") < sink.writes.indexOf("export.json"))
        assertEquals(4, manifest.files)
        assertEquals(listOf("etc", "client", "mysql").sorted(), manifest.folders)
        assertEquals(listOf("logs"), manifest.excluded)
        assertEquals(listOf("environments"), manifest.prefs)
        assertEquals("{}\n", sink.files.getValue("prefs/environments.json").decodeToString())

        val lines = Checksums.parse(sink.files.getValue("checksums.tsv").decodeToString())
        assertEquals(listOf("client/Wow.exe", "etc/a.conf", "mysql/ib_logfile0", "mysql/ibdata1"), lines.map { it.path })
        assertEquals(sha("database".toByteArray()), lines.first { it.path == "mysql/ibdata1" }.sha256)
        assertEquals(manifest.bytes, lines.sumOf { it.size })
        assertEquals(manifest, ExportManifest.parse(sink.files.getValue("export.json").decodeToString()))
    }

    @Test fun `an interrupted run carries on and does not copy finished files again`() {
        val src = fresh()
        val sink = MemSink()
        val plan = ExportPlanner.scan(src)
        // dies while writing the third file
        sink.failOn = "files/mysql/ib_logfile0"
        assertFailsWith<java.io.IOException> { Exporter.run(src, plan, sink, context) }
        assertNull(sink.files["export.json"])
        val partial = Checksums.parsePartial(sink.files.getValue("checksums.partial").decodeToString())
        assertEquals(setOf("client/Wow.exe", "etc/a.conf"), partial.keys)
        // (a half-written file is at the destination, but not trusted)
        assertTrue(sink.files.containsKey("files/mysql/ib_logfile0"))

        sink.failOn = null
        src.opened.clear()
        sink.writes.clear()
        val manifest = Exporter.run(src, plan, sink, context)
        assertEquals(listOf("mysql/ib_logfile0", "mysql/ibdata1"), src.opened.sorted())
        assertFalse(sink.writes.contains("files/client/Wow.exe"))
        assertEquals("log", sink.files.getValue("files/mysql/ib_logfile0").decodeToString())
        assertTrue(ExportVerifier.verify(sink).ok)
        assertEquals(4, manifest.files)
    }

    @Test fun `a file the cut off copy left at the right size is still copied again`() {
        val src = fresh()
        val sink = MemSink()
        val plan = ExportPlanner.scan(src)
        // the destination has a same-size wrong file, never listed as finished
        sink.files["files/client/Wow.exe"] = "XXX".toByteArray()
        Exporter.run(src, plan, sink, context)
        assertEquals("wow", sink.files.getValue("files/client/Wow.exe").decodeToString())
    }

    @Test fun `running again after a finished export skips what has not changed and copies what has`() {
        val src = fresh()
        val sink = MemSink()
        Exporter.run(src, ExportPlanner.scan(src), sink, context)

        // the database is played on: same size, new content and time
        src.files["mysql/ibdata1"] = "DATABASE".toByteArray()
        src.modified["mysql/ibdata1"] = 99L
        src.opened.clear()
        Exporter.run(src, ExportPlanner.scan(src), sink, context)
        assertEquals(listOf("mysql/ibdata1"), src.opened)
        assertEquals("DATABASE", sink.files.getValue("files/mysql/ibdata1").decodeToString())
        assertTrue(ExportVerifier.verify(sink).ok)
    }

    @Test fun `a file that went missing at the destination is copied again`() {
        val src = fresh()
        val sink = MemSink()
        val plan = ExportPlanner.scan(src)
        Exporter.run(src, plan, sink, context)
        sink.files.remove("files/etc/a.conf")
        src.opened.clear()
        Exporter.run(src, plan, sink, context)
        assertEquals(listOf("etc/a.conf"), src.opened)
    }

    @Test fun `export json is removed first so a half redone export does not look finished`() {
        val src = fresh()
        val sink = MemSink()
        val plan = ExportPlanner.scan(src)
        Exporter.run(src, plan, sink, context)
        assertTrue(sink.files.containsKey("export.json"))
        src.files["etc/a.conf"] = "changed!".toByteArray()
        src.modified["etc/a.conf"] = 5
        sink.failOn = "files/etc/a.conf"
        assertFailsWith<java.io.IOException> { Exporter.run(src, ExportPlanner.scan(src), sink, context) }
        assertFalse(sink.files.containsKey("export.json"))
    }

    @Test fun `cancel stops between buffers and keeps the progress`() {
        val src = fresh()
        val sink = MemSink()
        var calls = 0
        assertFailsWith<ExportCancelled> {
            Exporter.run(src, ExportPlanner.scan(src), sink, context, isCancelled = { ++calls > 3 })
        }
        assertNull(sink.files["export.json"])
        assertTrue(sink.files.containsKey("checksums.partial"))
        Exporter.run(src, ExportPlanner.scan(src), sink, context)
        assertTrue(ExportVerifier.verify(sink).ok)
    }

    @Test fun `progress counts files and bytes and ends complete`() {
        val src = fresh()
        val sink = MemSink()
        val seen = mutableListOf<ExportProgress>()
        val plan = ExportPlanner.scan(src)
        Exporter.run(src, plan, sink, context, onProgress = { seen += it })
        val last = seen.last()
        assertEquals(plan.files.size, last.files)
        assertEquals(plan.totalBytes, last.bytes)
        assertEquals(0, last.skipped)
        seen.clear()
        Exporter.run(src, plan, sink, context, onProgress = { seen += it })
        assertEquals(plan.files.size, seen.last().skipped)
        assertEquals(plan.totalBytes, seen.last().bytes)
    }

    @Test fun `a larger file is copied across several buffers`() {
        val big = ByteArray(3_500_000) { (it * 31).toByte() }
        val src = MemSource(mutableMapOf("client/big.mpq" to big))
        val sink = MemSink()
        Exporter.run(src, ExportPlanner.scan(src), sink, context)
        assertContentEquals(big, sink.files.getValue("files/client/big.mpq"))
        assertEquals(sha(big), Checksums.parse(sink.files.getValue("checksums.tsv").decodeToString()).single().sha256)
    }
}

class ExportVerifyTest {
    private fun exported(): MemSink {
        val src = source("client/Wow.exe" to "wow", "mysql/ibdata1" to "database", "etc/a.conf" to "cfg")
        return MemSink().also { Exporter.run(src, ExportPlanner.scan(src), it, context) }
    }

    @Test fun `an intact export verifies`() {
        val report = ExportVerifier.verify(exported())
        assertTrue(report.ok)
        assertEquals(3, report.checked)
        assertEquals(14L, report.bytes)
    }

    @Test fun `a corrupted file is reported`() {
        val sink = exported()
        sink.files["files/mysql/ibdata1"] = "databasX".toByteArray() // same size, one byte changed
        val report = ExportVerifier.verify(sink)
        assertFalse(report.ok)
        assertEquals(listOf("mysql/ibdata1"), report.wrongChecksum)
        assertTrue(report.missing.isEmpty() && report.wrongSize.isEmpty())
    }

    @Test fun `a truncated and a missing file are reported`() {
        val sink = exported()
        sink.files["files/client/Wow.exe"] = "wo".toByteArray()
        sink.files.remove("files/etc/a.conf")
        val report = ExportVerifier.verify(sink)
        assertFalse(report.ok)
        assertEquals(listOf("client/Wow.exe"), report.wrongSize)
        assertEquals(listOf("etc/a.conf"), report.missing)
    }

    @Test fun `an export that did not finish can't be verified`() {
        val sink = exported()
        sink.files.remove("export.json")
        val report = ExportVerifier.verify(sink)
        assertFalse(report.ok)
        assertNotNull(report.problem)
        val damaged = exported().also { it.files["checksums.tsv"] = "garbage".toByteArray() }
        assertNotNull(ExportVerifier.verify(damaged).problem)
    }

    @Test fun `verify can be stopped`() {
        assertFailsWith<ExportCancelled> { ExportVerifier.verify(exported(), isCancelled = { true }) }
    }
}
