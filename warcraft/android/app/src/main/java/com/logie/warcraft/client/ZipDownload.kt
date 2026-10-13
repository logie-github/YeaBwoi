// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.client

import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Files out of a zip on a web server, by HTTP ranges ([RemoteZip]): the zip's directory, then one
 * file at a time ([fetch]), checked against the zip's CRC, or all of them in one pass ([extractAll]).
 * A stored file carries on from what an earlier try got; a compressed one starts again.
 */
class ZipDownload(
    private val source: Source,
    /** Asked now and then; true stops the download (with an [IOException]). */
    private val cancelled: () -> Boolean = { false },
) {
    /** Where the zip's bytes come from. */
    interface Source {
        /** The zip's size. */
        fun size(): Long

        /** Bytes [from] to [to] of the zip (to its end when [to] is null). */
        fun open(from: Long, to: Long?): InputStream
    }

    constructor(
        url: String,
        /** The page a browser would come from, for servers that only send their files to those. */
        referer: String?,
        userAgent: String,
        cancelled: () -> Boolean = { false },
    ) : this(Http(url, userAgent) { referer?.let { mapOf("Referer" to it) }.orEmpty() }, cancelled)

    class Cancelled : IOException("cancelled")

    /** The zip's files. */
    fun directory(): List<RemoteZip.Entry> = RemoteZip.directory(source.size()) { from, length -> read(from, length) }

    /**
     * Downloads [entry] into [target], through `<target>.part`; [onBytes] gets how much of the
     * entry has come (its compressed size, in all).
     */
    fun fetch(entry: RemoteZip.Entry, target: File, onBytes: (Long) -> Unit) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val start = RemoteZip.dataStart(entry, read(entry.localHeader, 30))
        val crc = CRC32()
        when (entry.method) {
            0 -> {
                if (part.length() > entry.size) part.delete()
                if (part.length() > 0) part.inputStream().use { input -> copy(input, OutputStream.nullOutputStream(), crc) {} }
                val from = part.length()
                if (from < entry.size) {
                    source.open(start + from, start + entry.size - 1).use { input ->
                        FileOutputStream(part, true).use { output -> copy(input, output, crc) { onBytes(from + it) } }
                    }
                }
            }
            8 -> {
                part.delete()
                source.open(start, start + entry.compressedSize - 1).use { raw ->
                    InflaterInputStream(Counted(raw, onBytes), Inflater(true), 1 shl 16).use { input ->
                        FileOutputStream(part).use { output -> copy(input, output, crc) {} }
                    }
                }
            }
            else -> throw IOException("Unsupported zip method ${entry.method}")
        }
        finish(entry, part, target, crc)
    }

    /**
     * Downloads the files among [entries] into [folder], each at its path in the zip, in one pass
     * over the zip from the first file not there yet: files there at their full size (from an
     * earlier try, which a dropped connection ends) are kept. [onBytes] gets how much of the
     * files' compressed size has come, of [total][totalOf].
     */
    fun extractAll(entries: List<RemoteZip.Entry>, folder: File, onBytes: (Long) -> Unit) {
        val files = entries.filter { !it.name.endsWith("/") }.sortedBy { it.localHeader }
        val targets = files.map { target(folder, it.name) }
        fun complete(index: Int) = targets[index].isFile && targets[index].length() == files[index].size
        val first = files.indices.firstOrNull { !complete(it) } ?: return
        var done = (0 until first).sumOf { files[it].compressedSize }
        onBytes(done)
        var position = files[first].localHeader
        source.open(position, null).use { input ->
            for (index in first until files.size) {
                val entry = files[index]
                skip(input, entry.localHeader - position)
                val dataStart = RemoteZip.dataStart(entry, readFully(input, 30))
                skip(input, dataStart - entry.localHeader - 30)
                position = dataStart + entry.compressedSize
                val data = Bounded(input, entry.compressedSize)
                if (complete(index)) {
                    skip(data, entry.compressedSize)
                } else {
                    val target = targets[index]
                    target.parentFile?.mkdirs()
                    val part = File(target.path + ".part")
                    val crc = CRC32()
                    val before = done
                    val counted = Counted(data) { onBytes(before + it) }
                    FileOutputStream(part).use { output ->
                        when (entry.method) {
                            0 -> copy(counted, output, crc) {}
                            8 -> copy(InflaterInputStream(counted, Inflater(true), 1 shl 16), output, crc) {}
                            else -> throw IOException("Unsupported zip method ${entry.method}")
                        }
                    }
                    // What the inflater left unread of the entry (its end) is passed over.
                    skip(data, data.left)
                    finish(entry, part, target, crc)
                }
                done += entry.compressedSize
                onBytes(done)
            }
        }
    }

    /** The files' size in the zip, which [extractAll] counts to. */
    fun totalOf(entries: List<RemoteZip.Entry>): Long = entries.filter { !it.name.endsWith("/") }.sumOf { it.compressedSize }

    private fun finish(entry: RemoteZip.Entry, part: File, target: File, crc: CRC32) {
        if (part.length() != entry.size || crc.value != entry.crc) {
            part.delete()
            throw IOException("${target.name} came damaged")
        }
        if (!part.renameTo(target)) throw IOException("Couldn't keep ${target.name}")
    }

    private fun copy(input: InputStream, output: OutputStream, crc: CRC32, onBytes: (Long) -> Unit) {
        val buffer = ByteArray(1 shl 16)
        var total = 0L
        var reported = 0L
        while (true) {
            if (cancelled()) throw Cancelled()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            crc.update(buffer, 0, n)
            total += n
            if (total - reported >= 1 shl 20) {
                onBytes(total)
                reported = total
            }
        }
        onBytes(total)
    }

    private fun skip(input: InputStream, count: Long) {
        if (count < 0) throw IOException("The zip's files overlap")
        val buffer = ByteArray(1 shl 16)
        var left = count
        while (left > 0) {
            if (cancelled()) throw Cancelled()
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw IOException("The download ended early")
            left -= n
        }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = input.read(bytes, got, length - got)
            if (n < 0) throw IOException("The download ended early")
            got += n
        }
        return bytes
    }

    /** Counts what's read through it: the compressed bytes. */
    private class Counted(input: InputStream, private val onCount: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) onCount(++count) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) { count += it; onCount(count) } }
    }

    /** The next [length] bytes of [input], and no more; it ends early if [input] does. */
    private class Bounded(input: InputStream, length: Long) : FilterInputStream(input) {
        var left = length
            private set

        override fun read(): Int {
            if (left <= 0) return -1
            return super.read().also { if (it >= 0) left-- }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }

        override fun close() {} // The zip's stream goes on to the next file.
    }

    private fun read(from: Long, length: Int): ByteArray =
        source.open(from, from + length - 1).use { it.readBytes() }.also {
            if (it.size != length) throw IOException("short read")
        }

    /** The zip's size. */
    fun size(): Long = source.size()

    /**
     * A zip on a web server. Redirects are followed here, with [headers] asked again for each
     * address (a token for one server must not go on to the next).
     */
    class Http(
        private val url: String,
        private val userAgent: String,
        private val headers: (URL) -> Map<String, String> = { emptyMap() },
    ) : Source {
        private fun connection(from: Long, to: Long?): HttpURLConnection {
            var current = URI(url).toURL()
            repeat(MAX_REDIRECTS + 1) {
                val connection = (current.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", userAgent)
                    for ((key, value) in headers(current)) setRequestProperty(key, value)
                    setRequestProperty("Range", "bytes=$from-${to ?: ""}")
                }
                val code = connection.responseCode
                if (code !in 300..399) return connection
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                current = URL(current, location ?: throw IOException("HTTP $code"))
            }
            throw IOException("Too many redirects")
        }

        override fun open(from: Long, to: Long?): InputStream {
            val connection = connection(from, to)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect()
                throw IOException("HTTP $code")
            }
            return connection.inputStream
        }

        /** From the server's answer for the zip's first byte. */
        override fun size(): Long {
            val connection = connection(0, 0)
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) throw IOException("HTTP ${connection.responseCode}")
                val range = connection.getHeaderField("Content-Range") ?: throw IOException("no size")
                return range.substringAfterLast('/').trim().toLong()
            } finally {
                connection.disconnect()
            }
        }

        private companion object {
            const val MAX_REDIRECTS = 5
        }
    }

    companion object {
        /**
         * Where a file of the zip goes in [folder]: its path, which mustn't lead out of the folder.
         */
        fun target(folder: File, name: String): File {
            val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty() || parts.any { it == ".." } || name.startsWith("/")) throw IOException("A file of the zip has a bad name: $name")
            return File(folder, parts.joinToString(File.separator))
        }
    }
}
