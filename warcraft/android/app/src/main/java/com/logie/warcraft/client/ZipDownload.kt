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
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Files out of a zip on a web server, by HTTP ranges ([RemoteZip]): the zip's directory, then one
 * file at a time, checked against the zip's CRC. A stored file carries on from what an earlier try
 * got; a compressed one starts again.
 */
class ZipDownload(
    private val url: String,
    /** The page a browser would come from, for servers that only send their files to those. */
    private val referer: String?,
    private val userAgent: String,
    /** Asked now and then; true stops the download (with an [IOException]). */
    private val cancelled: () -> Boolean = { false },
) {
    class Cancelled : IOException("cancelled")

    /** The zip's files. */
    fun directory(): List<RemoteZip.Entry> = RemoteZip.directory(size()) { from, length -> read(from, length) }

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
                    open(start + from, start + entry.size - 1).use { input ->
                        FileOutputStream(part, true).use { output -> copy(input, output, crc) { onBytes(from + it) } }
                    }
                }
            }
            8 -> {
                part.delete()
                open(start, start + entry.compressedSize - 1).use { raw ->
                    InflaterInputStream(Counted(raw, onBytes), Inflater(true), 1 shl 16).use { input ->
                        FileOutputStream(part).use { output -> copy(input, output, crc) {} }
                    }
                }
            }
            else -> throw IOException("Unsupported zip method ${entry.method}")
        }
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

    /** Counts what's read through it: the compressed bytes. */
    private class Counted(input: InputStream, private val onCount: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) onCount(++count) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) { count += it; onCount(count) } }
    }

    private fun connection(from: Long, to: Long): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            referer?.let { setRequestProperty("Referer", it) }
            setRequestProperty("Range", "bytes=$from-$to")
        }

    /** Bytes [from] to [to] of the zip. */
    private fun open(from: Long, to: Long): InputStream {
        val connection = connection(from, to)
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            throw IOException("HTTP $code")
        }
        return connection.inputStream
    }

    private fun read(from: Long, length: Int): ByteArray =
        open(from, from + length - 1).use { it.readBytes() }.also {
            if (it.size != length) throw IOException("short read")
        }

    /** The zip's size, from the server's answer for its first byte. */
    fun size(): Long {
        val connection = connection(0, 0)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) throw IOException("HTTP ${connection.responseCode}")
            val range = connection.getHeaderField("Content-Range") ?: throw IOException("no size")
            return range.substringAfterLast('/').trim().toLong()
        } finally {
            connection.disconnect()
        }
    }
}
