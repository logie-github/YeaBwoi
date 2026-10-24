// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TwwRangeDownloadTest {
    // Network integrity fixture: archive magic plus deterministic bytes; no game data.
    private val bytes = ByteArray(8 * 1024 * 1024 + 137) { (it * 31).toByte() }.apply {
        this[0] = 0x50; this[1] = 0x4b; this[2] = 3; this[3] = 4
    }
    private val digest get() = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class Host(val bytes: ByteArray) {
        val requests = CopyOnWriteArrayList<String>()
        val starts = CopyOnWriteArrayList<Long>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val simultaneous = CountDownLatch(4)
        @Volatile var etag = "\"v1\""
        @Volatile var changed: ByteArray? = null
        @Volatile var slow = false
        @Volatile var rejectBounded = false
        @Volatile var truncate = false
        @Volatile var rendezvous = false
        @Volatile var wrongHeader = false
        fun respond(exchange: HttpExchange) {
            val range = exchange.requestHeaders.getFirst("Range")
            val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range.orEmpty())
            val bounded = match != null && match.groupValues[2].isNotEmpty()
            val data = changed ?: bytes
            val accepted = match != null && exchange.requestHeaders.getFirst("If-Range") == etag && !(bounded && rejectBounded)
            val start = if (accepted) match!!.groupValues[1].toInt() else 0
            val end = if (accepted && bounded) match!!.groupValues[2].toInt() else data.lastIndex
            requests += range ?: "full"
            if (bounded) starts += start.toLong()
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.responseHeaders.add("ETag", if (bounded && wrongHeader) "\"changed\"" else etag)
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            if (accepted) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${data.size}")
            exchange.sendResponseHeaders(if (accepted) 206 else 200, (end - start + 1).toLong())
            if (bounded) {
                val count = active.incrementAndGet()
                maximum.accumulateAndGet(count, ::maxOf)
                simultaneous.countDown()
            }
            try {
                if (bounded && rendezvous) simultaneous.await(3, TimeUnit.SECONDS)
                val stop = if (bounded && truncate) start + (end - start + 1) / 2 else end + 1
                exchange.responseBody.use { out ->
                    var position = start
                    while (position < stop) {
                        val amount = minOf(32 * 1024, stop - position)
                        out.write(data, position, amount)
                        position += amount
                        if (bounded && slow) Thread.sleep(8)
                    }
                }
            } catch (_: IOException) { // Preflight and Pause deliberately close response bodies.
            } finally {
                if (bounded) active.decrementAndGet()
                exchange.close()
            }
        }
    }
    private fun fixture(block: (Host, File, String) -> Unit) {
        val root = Files.createTempDirectory("range-download-").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool()
        val host = Host(bytes)
        server.executor = pool
        server.createContext("/client", host::respond)
        server.start()
        try { block(host, root, "http://127.0.0.1:${server.address.port}/client") }
        finally { server.stop(0); pool.shutdownNow(); root.deleteRecursively() }
    }
    private fun legacy(root: File, url: String, count: Int) {
        File(root, "client.part").writeBytes(bytes.copyOf(count))
        File(root, "client.properties").outputStream().use { out ->
            Properties().apply {
                setProperty("source", url); setProperty("total", bytes.size.toString()); setProperty("etag", "\"v1\"")
            }.store(out, null)
        }
    }
    private fun download(root: File, url: String) = TwwDownload(url, root, "client",
        expectedSize = bytes.size.toLong(), expectedSha256 = digest, parallelConnections = 4)

    @Test fun fourConcurrentRangesPreserveLegacyProgressAndProduceExactArchiveBytes() = fixture { host, root, url ->
        val prefix = 500_003
        legacy(root, url, prefix)
        host.rendezvous = true
        val reports = mutableListOf<TwwDownload.Progress>()
        val output = download(root, url).run(onProgress = { reports += it })
        assertContentEquals(bytes, output.readBytes())
        assertEquals(4, host.maximum.get())
        assertTrue(host.starts.all { it >= prefix })
        assertTrue(reports.any { it.connections == 4 })
        assertTrue(reports.zipWithNext().all { (a, b) -> b.bytes >= a.bytes })
        assertEquals(bytes.size.toLong(), reports.last().bytes)
        assertTrue(root.listFiles().orEmpty().none { it.name.contains("range") })
    }
    @Test fun pauseKeepsPartialChunksAndRetryRequestsOnlyTheirMissingSuffixes() = fixture { host, root, url ->
        host.slow = true
        val cancelled = AtomicBoolean(false)
        val first = download(root, url)
        assertFailsWith<TwwDownload.Cancelled> {
            first.run(cancelled = { cancelled.get() }, onProgress = {
                if (it.bytes >= 512 * 1024) { cancelled.set(true); first.cancel() }
            })
        }
        assertFalse(File(root, "client.archive").exists())
        val chunks = root.listFiles().orEmpty().filter { it.name.matches(Regex("client.range-\\d+-\\d+")) }
        assertTrue(chunks.sumOf { it.length() } > 0)
        val expectedSuffixes = chunks.filter { it.length() > 0 }.map {
            val values = it.name.removePrefix("client.range-").split('-').map(String::toLong)
            values[0] + it.length()
        }.filter { it < bytes.size }
        host.starts.clear()
        assertContentEquals(bytes, download(root, url).run().readBytes())
        assertTrue(expectedSuffixes.any { it in host.starts })
    }
    @Test fun changedSourceNeverCombinesOldPrefixOrCachedRangesWithNewBytes() = fixture { host, root, url ->
        legacy(root, url, 700_003)
        File(root, "client.range-0-1048575").writeBytes(bytes.copyOf(1048576))
        File(root, "client.ranges.properties").outputStream().use { out -> Properties().apply {
            setProperty("identity", "$url\n${bytes.size}\n\"v1\"")
        }.store(out, null) }
        host.changed = bytes.copyOf().apply { for (i in 4 until size) this[i] = (this[i].toInt() xor 23).toByte() }
        host.etag = "\"v2\""
        val expected = host.changed!!
        val result = TwwDownload(url, root, "client", expectedSize = bytes.size.toLong(), parallelConnections = 4).run()
        assertContentEquals(expected, result.readBytes())
    }
    @Test fun hostsThatIgnoreBoundedRangesFallBackWithoutLosingTheExistingPrefix() = fixture { host, root, url ->
        legacy(root, url, 700_003)
        host.rejectBounded = true
        assertContentEquals(bytes, download(root, url).run().readBytes())
        assertTrue(host.starts.isNotEmpty())
        // (the resume from the kept prefix; the four range connections end in any order, so not necessarily last)
        assertTrue("bytes=700003-" in host.requests)
    }
    @Test fun truncatedRangesStayIncompleteAndCanBeResumed() = fixture { host, root, url ->
        host.truncate = true
        assertFailsWith<TwwDownload.DownloadException> { download(root, url).run() }
        assertFalse(File(root, "client.archive").exists())
        host.truncate = false
        assertContentEquals(bytes, download(root, url).run().readBytes())
        assertTrue(host.starts.any { it > 0 && it % ((bytes.size + 3) / 4) != 0L })
    }
    @Test fun crashWhileAppendingAChunkResumesWithoutDuplicatingOrSkippingBytes() = fixture { _, root, url ->
        val size = (bytes.size + 3) / 4
        val prefix = 101_003
        legacy(root, url, prefix)
        File(root, "client.range-0-${size - 1}").writeBytes(bytes.copyOf(size))
        File(root, "client.ranges.properties").outputStream().use { out -> Properties().apply {
            setProperty("identity", "$url\n${bytes.size}\n\"v1\"")
        }.store(out, null) }
        assertContentEquals(bytes, download(root, url).run().readBytes())
    }
    @Test fun differentValidatorOnARangeCannotPoisonTheFinalArchive() = fixture { host, root, url ->
        legacy(root, url, 700_003)
        host.wrongHeader = true
        assertContentEquals(bytes, download(root, url).run().readBytes())
        assertTrue(host.starts.isNotEmpty())
        // (the resume from the kept prefix; the four range connections end in any order, so not necessarily last)
        assertTrue("bytes=700003-" in host.requests)
    }
    @Test fun rangeCacheCannotFollowLinksOutsideTheDownloadDirectory() = fixture { _, root, url ->
        val outside = Files.createTempFile("download-outside-", ".txt").toFile().apply { writeText("untouched") }
        try {
            Files.createSymbolicLink(File(root, "client.range-0-100").toPath(), outside.toPath())
            assertFailsWith<TwwDownload.DownloadException> { download(root, url).run() }
            assertEquals("untouched", outside.readText())
            assertFalse(File(root, "client.archive").exists())
        } finally { outside.delete() }
    }
    @Test fun wrongFinalHashNeverPromotesAParallelDownload() = fixture { _, root, url ->
        assertFailsWith<TwwDownload.DownloadException> {
            TwwDownload(url, root, "client", expectedSize = bytes.size.toLong(), expectedSha256 = "0".repeat(64), parallelConnections = 4).run()
        }
        assertFalse(File(root, "client.archive").exists())
        assertFalse(File(root, "client.part").exists())
    }
}
