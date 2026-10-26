// SPDX-License-Identifier: GPL-3.0-or-later
package com.logie.warcraft.tww.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Bounded range batches keep the existing .part a contiguous, backwards-compatible prefix.
 * Workers write separate chunks; only the coordinator appends validated chunks, in order.
 * At most four 32 MiB chunks are staged, rather than a second copy of the entire archive.
 */
internal class TwwRangeDownload(
    private val url: URL,
    private val directory: File,
    private val key: String,
    private val total: Long,
    private val etag: String,
    private val connections: Int,
    private val chunkBytes: Long = 32L * 1024 * 1024,
) {
    class Unsupported(message: String) : IOException(message)
    private val part = File(directory, "$key.part")
    private val anchor = File(directory, "$key.ranges.properties")
    private val live = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val aborted = AtomicBoolean(false)
    private val transferred = AtomicLong(0)
    private val cachePattern = Regex(Regex.escape(key) + "\\.range-(\\d+)-(\\d+)")
    private data class Chunk(val start: Long, val end: Long, val file: File) {
        val size get() = end - start + 1
    }

    // A connection still being set up can make disconnect() throw (the JDK's HttpURLConnection, with no
    // client yet); the others are still closed, and the aborted flag stops that one when it's up. The set
    // is walked as it is (its iterator takes connections leaving meanwhile): toList() reads its size first
    // and throws when the last one leaves before it's read.
    fun cancel() { aborted.set(true); for (connection in live) runCatching { connection.disconnect() } }

    fun run(check: () -> Unit, progress: (TwwDownload.Progress) -> Unit) {
        require(connections in 2..4 && total > 0 && chunkBytes > 0)
        safe(part); safe(anchor)
        val identity = "$url\n$total\n$etag"
        val previous = Properties().apply {
            if (anchor.isFile) runCatching { anchor.inputStream().use { load(it) } }
        }.getProperty("identity")
        if (previous != identity) clear(directory, key)
        safe(anchor)
        val temp = safe(File(directory, "$key.ranges.properties.new"))
        temp.outputStream().use { output -> Properties().apply { setProperty("identity", identity) }.store(output, null) }
        if (!temp.renameTo(anchor)) throw IOException("Cannot save parallel download progress")
        val pool = Executors.newFixedThreadPool(connections)
        val started = System.nanoTime()
        fun checked() { check(); if (aborted.get() || Thread.currentThread().isInterrupted) throw TwwDownload.Cancelled() }
        try {
            while (part.length() < total) {
                checked()
                val prefix = part.length()
                val cached = directory.listFiles().orEmpty().mapNotNull { file ->
                    val match = cachePattern.matchEntire(file.name) ?: return@mapNotNull null
                    safe(file)
                    val start = match.groupValues[1].toLongOrNull()
                    val end = match.groupValues[2].toLongOrNull()
                    if (start == null || end == null || start < 0 || end < start || end >= total ||
                        end - start + 1 > chunkBytes || file.length() > end - start + 1 || end < prefix) {
                        if (!file.delete()) throw IOException("Cannot clear an obsolete download chunk")
                        null
                    } else Chunk(start, end, file)
                }.sortedBy { it.start }
                // Resume a chunk even if Android stopped the coordinator halfway through appending it.
                var next = prefix
                val batch = mutableListOf<Chunk>()
                val size = minOf(chunkBytes, ((total - prefix + connections - 1) / connections).coerceAtLeast(1))
                repeat(connections) {
                    if (next < total) {
                        val chunk = cached.firstOrNull { it.start <= next && next <= it.end }
                            ?: Chunk(next, minOf(total - 1, next + size - 1), safe(File(directory,
                                "$key.range-$next-${minOf(total - 1, next + size - 1)}")))
                        batch += chunk
                        next = chunk.end + 1
                    }
                }
                val amounts = batch.map { AtomicLong(it.file.length().coerceAtLeast(prefix - it.start).coerceAtMost(it.size)) }
                val futures = batch.mapIndexed { index, chunk -> pool.submit<Unit> { fetch(chunk, amounts[index], ::checked) } }
                fun report() {
                    checked()
                    val done = (prefix + batch.indices.sumOf { index ->
                        (amounts[index].get() - (prefix - batch[index].start).coerceAtLeast(0)).coerceAtLeast(0)
                    }).coerceAtMost(total)
                    val seconds = (System.nanoTime() - started).coerceAtLeast(1).toDouble() / 1_000_000_000
                    val speed = (transferred.get() / seconds).toLong()
                    progress(TwwDownload.Progress(done, total, speed,
                        if (speed > 0) ((total - done).coerceAtLeast(0) + speed - 1) / speed else null,
                        connections = batch.size))
                }
                // Callbacks run only on the coordinator, never concurrently on network workers.
                while (futures.any { !it.isDone }) {
                    futures.filter { it.isDone }.forEach { it.get() }
                    report()
                    Thread.sleep(200)
                }
                futures.forEach { it.get() }
                report()
                for (chunk in batch) {
                    checked()
                    val offset = part.length() - chunk.start
                    if (offset !in 0..chunk.size || chunk.file.length() != chunk.size)
                        throw IOException("A parallel download chunk is incomplete")
                    chunk.file.inputStream().use { input ->
                        var skip = offset
                        while (skip > 0) {
                            val skipped = input.skip(skip)
                            if (skipped <= 0) throw IOException("Cannot resume a download chunk")
                            skip -= skipped
                        }
                        FileOutputStream(part, true).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                checked()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    if (part.length() != chunk.end + 1) throw IOException("Incomplete parallel download assembly")
                    if (!chunk.file.delete()) throw IOException("Cannot remove a completed download chunk")
                }
            }
        } catch (error: ExecutionException) {
            val cause = error.cause
            if (cause is IOException) throw cause
            throw IOException("Parallel download failed: ${cause?.message ?: error.message}", cause ?: error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TwwDownload.Cancelled()
        } finally {
            cancel()
            pool.shutdownNow()
            // Only the coordinator writes .part; workers own their range files until they exit.
            var interrupted = false
            while (!pool.isTerminated) {
                try { pool.awaitTermination(1, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun fetch(chunk: Chunk, amount: AtomicLong, check: () -> Unit) {
        safe(chunk.file)
        if (chunk.file.length() == chunk.size) return
        val offset = chunk.file.length()
        val first = chunk.start + offset
        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "WorldOfWarcraft-LocalInstaller/1")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Range", "bytes=$first-${chunk.end}")
            setRequestProperty("If-Range", etag)
        }
        live += connection
        try {
            check()
            val status = connection.responseCode
            if (status in setOf(200, 301, 302, 303, 307, 308, 412, 416, 429, 503))
                throw Unsupported("The host cannot serve concurrent validated ranges (HTTP $status)")
            if (status != 206) throw IOException("The parallel download host returned HTTP $status. Retry later")
            if (connection.getHeaderField("Content-Range") != "bytes $first-${chunk.end}/$total" ||
                connection.getHeaderField("ETag") != etag ||
                connection.contentLengthLong != chunk.size - offset ||
                connection.getHeaderField("Content-Encoding")?.let { !it.equals("identity", true) } == true)
                throw Unsupported("The host changed or returned an invalid byte range")
            connection.inputStream.use { input ->
                FileOutputStream(chunk.file, true).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var done = offset
                    while (true) {
                        check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (done + count > chunk.size) throw Unsupported("A download range exceeded its requested size")
                        output.write(buffer, 0, count)
                        done += count
                        amount.set(done)
                        transferred.addAndGet(count.toLong())
                    }
                    output.fd.sync()
                    if (done != chunk.size) throw IOException("Incomplete parallel download range. Retry to resume")
                }
            }
        } finally {
            connection.disconnect()
            live -= connection
        }
    }

    private fun safe(file: File): File = file.also {
        if (Files.isSymbolicLink(it.toPath()) || it.exists() && !it.isFile)
            throw IOException("Unsafe parallel download file: ${it.name}")
    }

    companion object {
        fun clear(directory: File, key: String) {
            val pattern = Regex(Regex.escape(key) + "(?:\\.range-\\d+-\\d+|\\.ranges\\.properties(?:\\.new)?)")
            directory.listFiles().orEmpty().filter { pattern.matches(it.name) }.forEach {
                if (Files.isSymbolicLink(it.toPath()) || !it.isFile || !it.delete())
                    throw IOException("Cannot safely clear parallel download progress: ${it.name}")
            }
        }
    }
}
