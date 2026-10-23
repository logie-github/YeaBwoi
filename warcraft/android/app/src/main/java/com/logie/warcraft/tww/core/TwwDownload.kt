// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.tww.core

import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Properties

/**
 * Downloads third-party archives into a temporary folder, never an installed component.
 * A .part and its HTTP validators survive cancellation and process death. Completed files are
 * reused only after their saved length and SHA-256 have been checked. A locally calculated hash
 * detects disk changes; it is not an assertion of publisher authenticity.
 */
class TwwDownload(
    private val source: String,
    private val directory: File,
    private val key: String,
    private val expectedSize: Long? = null,
    private val expectedSha256: String? = null,
    private val parallelConnections: Int = 1,
) {
    data class Progress(
        val bytes: Long,
        val total: Long?,
        val bytesPerSecond: Long,
        val etaSeconds: Long?,
        val reused: Boolean = false,
        val connections: Int = 1,
    ) {
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    }

    class Cancelled : IOException("Download cancelled. Retry to resume where the server supports it.")
    class DownloadException(message: String) : IOException(message)

    @Volatile private var stopped = false
    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var ranges: TwwRangeDownload? = null
    private var parallelAllowed = true
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private val part get() = File(directory, "$key.part")
    private val target get() = File(directory, "$key.archive")
    private val metadata get() = File(directory, "$key.properties")

    init {
        require(parallelConnections in 1..4) { "Use one to four download connections" }
        require(key.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid download identifier" }
        require(expectedSize == null || expectedSize > 0) { "Invalid expected download size" }
        require(expectedSha256 == null || expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid SHA-256" }
        require(URL(source).protocol in setOf("http", "https")) { "Only HTTP downloads are supported" }
    }

    /** Interrupts a blocked network read as well as the next copy block. The .part is retained. */
    fun cancel() {
        stopped = true
        active?.disconnect()
        ranges?.cancel()
    }

    /** Check the real acquisition response before another large component is downloaded.
     * A validated complete cache works even while its remote host is unavailable.
     * This does not mark an installation complete or write any download files.
     */
    fun checkAvailable(cancelled: () -> Boolean = { false }) {
        fun check() { if (stopped || cancelled()) throw Cancelled() }
        check()
        if (listOf(directory, target, metadata).any { java.nio.file.Files.isSymbolicLink(it.toPath()) }) {
            throw DownloadException("The temporary download folder and files must not be symbolic links")
        }
        val saved = readMetadata()
        val savedSize = saved.getProperty("completeSize")?.toLongOrNull()
        val savedHash = saved.getProperty("sha256")
        if (saved.getProperty("source") == source && target.isFile && savedSize != null && savedSize > 0 &&
            target.length() == savedSize && savedHash != null &&
            (expectedSize == null || expectedSize == savedSize) &&
            (expectedSha256 == null || savedHash.equals(expectedSha256, true)) &&
            savedHash.equals(hash(target, ::check), true)) return
        try {
            val connection = resolve(normalize(URL(source)), 0, null, ::check)
            val total = if (connection.responseCode == 206) {
                val range = parseRange(connection.getHeaderField("Content-Range"))
                if (range == null || range.first != 0L) throw DownloadException("The download server returned an invalid byte range")
                range.third
            } else connection.contentLengthLong.takeIf { it >= 0 }
            if (total != null && total <= 0) throw DownloadException("The download is empty")
            if (expectedSize != null && total != null && total != expectedSize) {
                throw DownloadException("The source now has $total bytes; expected $expectedSize. The download was not installed")
            }
            check()
        } finally {
            active?.disconnect()
            active = null
        }
    }

    fun run(cancelled: () -> Boolean = { false }, onProgress: (Progress) -> Unit = {}): File {
        fun check() { if (stopped || cancelled()) throw Cancelled() }
        check()
        if (!directory.isDirectory && !directory.mkdirs()) throw DownloadException("Cannot create temporary download folder: ${directory.path}")
        if (java.nio.file.Files.isSymbolicLink(directory.toPath())) throw DownloadException("The temporary download folder must not be a symbolic link")
        listOf(part, target, metadata).forEach {
            if (java.nio.file.Files.isSymbolicLink(it.toPath())) throw DownloadException("A temporary download file is a symbolic link: ${it.name}")
        }
        var saved = readMetadata()
        if (saved.getProperty("source") != source) {
            remove(part); remove(target); remove(metadata)
            saved = Properties()
        }
        if (target.isFile) {
            val length = saved.getProperty("completeSize")?.toLongOrNull()
            val savedHash = saved.getProperty("sha256")
            if (length != null && length == target.length() && savedHash != null && length > 0 &&
                (expectedSize == null || expectedSize == length) &&
                savedHash.equals(hash(target, ::check), true) &&
                (expectedSha256 == null || savedHash.equals(expectedSha256, true))) {
                TwwRangeDownload.clear(directory, key)
                onProgress(Progress(length, length, 0, 0, reused = true))
                return target
            }
            remove(target)
        }
        var from = part.length()
        val previousTotal = saved.getProperty("total")?.toLongOrNull()
        val validator = saved.getProperty("etag")?.takeIf { !it.startsWith("W/") && it.isNotBlank() }
            ?: saved.getProperty("lastModified")?.takeIf { it.isNotBlank() }
        if (from > 0 && (validator == null || previousTotal == null || from > previousTotal ||
                    (expectedSize != null && previousTotal != expectedSize))) {
            remove(part)
            from = 0
        }
        try {
            // A completed body may have reached disk just before Android killed the process.
            if (from > 0 && from == previousTotal) return finish(saved, ::check, onProgress)
            var connection = resolve(normalize(URL(source)), from, if (from > 0) validator else null, ::check)
            if (from > 0 && !validResume(connection, from, previousTotal, saved)) {
                connection.disconnect()
                remove(part)
                from = 0
                connection = resolve(normalize(URL(source)), 0, null, ::check)
            }
            active = connection
            try {
                val code = connection.responseCode
                if (code != 200 && code != 206) throw statusError(code)
                val encoding = connection.getHeaderField("Content-Encoding")
                if (encoding != null && !encoding.equals("identity", true)) throw DownloadException("The download server returned an encoded body; byte validation is not possible")
                val range = parseRange(connection.getHeaderField("Content-Range"))
                if (code == 206 && (range == null || range.first != from)) throw DownloadException("The download server returned an invalid byte range")
                val bodySize = connection.contentLengthLong.takeIf { it >= 0 }
                if (range != null && bodySize != null && bodySize != range.second - range.first + 1) {
                    throw DownloadException("The download server returned inconsistent byte-range lengths")
                }
                val total = if (code == 206) range!!.third else bodySize
                if (total != null && total <= 0) throw DownloadException("The download is empty")
                if (expectedSize != null && total != null && total != expectedSize) {
                    throw DownloadException("The source now has $total bytes; expected $expectedSize. The download was not installed")
                }
                val previousMetadata = saved
                saved = Properties().apply {
                    setProperty("source", source)
                    total?.let { setProperty("total", it.toString()) }
                    (connection.getHeaderField("ETag") ?: previousMetadata.getProperty("etag").takeIf { from > 0 })
                        ?.let { setProperty("etag", it) }
                    (connection.getHeaderField("Last-Modified") ?: previousMetadata.getProperty("lastModified").takeIf { from > 0 })
                        ?.let { setProperty("lastModified", it) }
                }
                writeMetadata(saved)
                val strongTag = saved.getProperty("etag")?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') && !it.startsWith("W/") }
                if (parallelAllowed && parallelConnections > 1 && total != null && total - from >= 4L * 1024 * 1024 &&
                    strongTag != null && !isGoogle(connection.url.host)) {
                    val resolved = connection.url
                    connection.disconnect()
                    active = null
                    val worker = TwwRangeDownload(resolved, directory, key, total, strongTag, parallelConnections)
                    ranges = worker
                    try {
                        worker.run(::check, onProgress)
                    } catch (_: TwwRangeDownload.Unsupported) {
                        // The prefix stays contiguous, including progress from earlier app versions.
                        parallelAllowed = false
                        TwwRangeDownload.clear(directory, key)
                        return run(cancelled, onProgress)
                    } finally { ranges = null }
                    return finish(saved, ::check, onProgress)
                }
                var done = from
                val start = System.nanoTime()
                var lastReport = 0L
                fun report(final: Boolean = false) {
                    val now = System.nanoTime()
                    if (!final && lastReport != 0L && now - lastReport < 250_000_000L) return
                    check()
                    val seconds = (now - start).coerceAtLeast(1).toDouble() / 1_000_000_000
                    val speed = ((done - from) / seconds).toLong()
                    onProgress(Progress(done, total ?: expectedSize, speed,
                        (total ?: expectedSize)?.takeIf { speed > 0 }?.let { ((it - done).coerceAtLeast(0) + speed - 1) / speed }))
                    lastReport = now
                }
                report()
                connection.inputStream.use { input ->
                    FileOutputStream(part, from > 0).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            check()
                            val count = input.read(buffer)
                            if (count < 0) break
                            val limit = total ?: expectedSize
                            if (limit != null && done + count > limit) throw DownloadException("The download exceeded its declared size")
                            output.write(buffer, 0, count)
                            done += count
                            report()
                        }
                        output.fd.sync()
                    }
                }
                check()
                report(final = true)
                if ((total != null && done != total) || (expectedSize != null && done != expectedSize)) {
                    throw DownloadException("Incomplete download: received $done of ${total ?: expectedSize} bytes. Retry to resume")
                }
                saved.setProperty("total", done.toString())
                writeMetadata(saved)
                return finish(saved, ::check, onProgress)
            } finally {
                connection.disconnect()
                active = null
            }
        } catch (e: IOException) {
            check()
            if (e is DownloadException || e is Cancelled) throw e
            throw DownloadException("Download failed: ${e.message ?: e.javaClass.simpleName}. Retry to resume where supported")
        }
    }

    private fun finish(saved: Properties, check: () -> Unit, progress: (Progress) -> Unit): File {
        check()
        if (!isArchive(part)) {
            remove(part); remove(metadata)
            throw DownloadException("The source did not return a supported archive (ZIP, 7z, RAR, tar, gzip, bzip2 or xz). It may be a sign-in, permission or error page")
        }
        val sha = hash(part, check)
        if (expectedSha256 != null && !sha.equals(expectedSha256, true)) {
            remove(part); remove(metadata)
            throw DownloadException("SHA-256 validation failed. The damaged download was deleted; retry to download again")
        }
        val size = part.length()
        if (expectedSize != null && expectedSize != size) throw DownloadException("Incomplete download: received $size of $expectedSize bytes")
        check()
        saved.setProperty("source", source)
        saved.setProperty("completeSize", size.toString())
        saved.setProperty("sha256", sha)
        // Save completion proof first. A crash before rename still leaves a resumable .part.
        writeMetadata(saved)
        if (!part.renameTo(target)) throw DownloadException("Cannot finalize temporary download: ${target.path}")
        TwwRangeDownload.clear(directory, key)
        progress(Progress(size, size, 0, 0))
        return target
    }

    private fun resolve(initial: URL, from: Long, validator: String?, check: () -> Unit): HttpURLConnection {
        var url = initial
        var confirmations = 0
        repeat(12) {
            check()
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "WorldOfWarcraft-LocalInstaller/1")
                setRequestProperty("Accept-Encoding", "identity")
                if (from > 0) {
                    setRequestProperty("Range", "bytes=$from-")
                    validator?.let { setRequestProperty("If-Range", it) }
                }
                cookies.get(url.toURI(), emptyMap()).forEach { (name, values) -> setRequestProperty(name, values.joinToString("; ")) }
            }
            active = connection
            try {
                val code = connection.responseCode
                check()
                cookies.put(url.toURI(), connection.headerFields.filterKeys { it != null })
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val next = connection.getHeaderField("Location") ?: throw DownloadException("The source returned a redirect without a destination")
                    url = checkedDestination(url, URL(url, next))
                    connection.disconnect()
                } else {
                    if (code == 416 && from > 0) return connection
                    if (code !in setOf(200, 206)) throw statusError(code)
                    val contentType = connection.contentType.orEmpty().substringBefore(';').lowercase()
                    if (contentType == "text/html" || contentType == "application/xhtml+xml") {
                        val html = connection.inputStream.use { input ->
                            val bytes = input.readNBytesCompat(MAX_HTML + 1)
                            if (bytes.size > MAX_HTML) throw DownloadException("The source returned an oversized web page instead of an archive")
                            String(bytes, Charsets.UTF_8)
                        }
                        connection.disconnect()
                        if (!isGoogle(url.host) || ++confirmations > 3) throw htmlError(html)
                        url = confirmation(url, html) ?: throw htmlError(html)
                    } else return connection
                }
            } catch (e: Exception) {
                connection.disconnect()
                active = null
                throw e
            }
        }
        throw DownloadException("The source redirected too many times")
    }

    internal fun confirmation(url: URL, html: String): URL? {
        val doc = Jsoup.parse(html, url.toString())
        val form = doc.selectFirst("form#download-form") ?: doc.select("form").firstOrNull {
            it.attr("action").contains("download") && it.select("input[name=confirm]").isNotEmpty()
        }
        if (form != null && form.attr("method").lowercase().let { it.isEmpty() || it == "get" }) {
            val action = checkedDestination(url, URL(url, form.attr("action")))
            val parameters = form.select("input[name]").filter { !it.hasAttr("disabled") }.joinToString("&") {
                encode(it.attr("name")) + "=" + encode(it.attr("value"))
            }
            return checkedDestination(url, URL(action.toString() + (if (action.query == null) "?" else "&") + parameters))
        }
        val link = doc.select("a[href]").firstOrNull {
            it.attr("href").contains("confirm=") && (it.attr("href").contains("download") || it.attr("href").contains("/uc?"))
        } ?: return null
        return checkedDestination(url, URL(url, link.attr("href")))
    }

    private fun checkedDestination(previous: URL, next: URL): URL {
        if (next.protocol !in setOf("http", "https") || (previous.protocol == "https" && next.protocol != "https")) {
            throw DownloadException("The source returned an unsafe download redirect")
        }
        if (isGoogle(previous.host) && !isGoogle(next.host)) throw DownloadException("Google Drive redirected outside its download hosts")
        if (next.host.equals("accounts.google.com", true)) throw DownloadException("Google Drive requires sign-in. Open the source in a browser or import an already downloaded folder")
        return next
    }

    private fun validResume(connection: HttpURLConnection, from: Long, total: Long?, saved: Properties): Boolean {
        if (connection.responseCode != 206) return false
        val range = parseRange(connection.getHeaderField("Content-Range")) ?: return false
        if (range.first != from || range.third != total) return false
        for ((property, header) in listOf("etag" to "ETag", "lastModified" to "Last-Modified")) {
            val before = saved.getProperty(property)
            val after = connection.getHeaderField(header)
            if (before != null && after != null && before != after) return false
        }
        return true
    }

    private fun statusError(code: Int): DownloadException = DownloadException(when (code) {
        401, 403 -> "Download access was denied (HTTP $code). The source may require sign-in, permission or a quota reset. Open it in a browser or import a downloaded folder"
        404, 410 -> "The download source is unavailable or was removed (HTTP $code). Check the source link or import a downloaded folder"
        429 -> "The download source has reached a quota or rate limit (HTTP 429). Retry later or import a downloaded folder"
        416 -> "The server rejected the saved download range (HTTP 416). Clear the temporary download and retry"
        else -> "The download server returned HTTP $code. Retry later or open the source in a browser"
    })

    private fun htmlError(html: String): DownloadException {
        val text = Jsoup.parse(html).text().lowercase()
        return DownloadException(when {
            text.contains("quota") || text.contains("too many users") || text.contains("download limit") -> "Google Drive has temporarily blocked this file because its download quota was reached. Retry after Google Drive restores access; repeated retries cannot reset the quota."
            text.contains("request access") || text.contains("access denied") || text.contains("permission") -> "Google Drive requires access permission. Open the source in a browser or import a downloaded folder"
            text.contains("sign in") || text.contains("sign-in") -> "The source requires sign-in. Open it in a browser or import an already downloaded folder"
            text.contains("does not exist") || text.contains("not found") || text.contains("trashed") -> "The download file is unavailable or was removed"
            else -> "The source returned a web page instead of an archive. It may be unavailable, restricted, or require a browser confirmation"
        })
    }

    private fun readMetadata(): Properties = Properties().apply {
        if (metadata.isFile) runCatching { metadata.inputStream().use { load(it) } }
    }

    private fun writeMetadata(properties: Properties) {
        val temp = File(directory, "$key.properties.new")
        if (java.nio.file.Files.isSymbolicLink(temp.toPath())) throw DownloadException("Unsafe temporary metadata file")
        temp.outputStream().use { properties.store(it, "Download validators; no game files are bundled") }
        if (!temp.renameTo(metadata)) { temp.delete(); throw DownloadException("Cannot save download progress") }
    }

    private fun remove(file: File) {
        if (file.exists() && !file.delete()) throw DownloadException("Cannot remove temporary file: ${file.name}")
    }

    companion object {
        private const val MAX_HTML = 2 * 1024 * 1024
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun isGoogle(host: String): Boolean = host.lowercase().let {
            it == "drive.google.com" || it == "drive.usercontent.google.com" || it == "docs.google.com" ||
                it == "accounts.google.com" || it.endsWith(".googleusercontent.com")
        }

        internal fun normalize(url: URL): URL {
            if (!url.host.equals("drive.google.com", true)) return url
            val id = Regex("/file/d/([A-Za-z0-9_-]+)").find(url.path)?.groupValues?.get(1)
                ?: url.query?.split('&')?.firstOrNull { it.startsWith("id=") }?.substringAfter("id=")
            if (id == null || !id.matches(Regex("[A-Za-z0-9_-]+"))) throw DownloadException("The Google Drive link has no valid file identifier")
            return URL("https://drive.usercontent.google.com/download?id=${encode(id)}&export=download")
        }

        private fun parseRange(value: String?): Triple<Long, Long, Long>? {
            val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value.orEmpty()) ?: return null
            val (start, end, total) = match.destructured
            val first = start.toLongOrNull() ?: return null
            val last = end.toLongOrNull() ?: return null
            val size = total.toLongOrNull() ?: return null
            return if (first <= last && last < size) Triple(first, last, size) else null
        }

        private fun hash(file: File, check: () -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun isArchive(file: File): Boolean {
            val head = file.inputStream().use { it.readNBytesCompat(512) }
            fun prefix(vararg bytes: Int): Boolean = head.size >= bytes.size && bytes.indices.all { (head[it].toInt() and 255) == bytes[it] }
            return prefix(0x50, 0x4b, 3, 4) || prefix(0x50, 0x4b, 5, 6) || prefix(0x50, 0x4b, 7, 8) ||
                prefix(0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c) || prefix(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07) ||
                prefix(0x1f, 0x8b) || prefix(0xfd, 0x37, 0x7a, 0x58, 0x5a, 0) || prefix(0x42, 0x5a, 0x68) ||
                (head.size > 262 && String(head, 257, 5, Charsets.US_ASCII) == "ustar")
        }

        /** InputStream.readNBytes is unavailable on older supported Android releases. */
        private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(minOf(limit, 8192))
            val buffer = ByteArray(minOf(limit, 8192))
            while (out.size() < limit) {
                val count = read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
    }
}
