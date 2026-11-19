package com.logie.wifecraft.anim

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Resolves the author's public itch.io upload and streams its ZIP into the animation importer. */
object WickedWhimsDownload {
    private const val PAGE = "https://turbodriver.itch.io/wickedwhims"
    private val uploadButton = Regex("""\bdata-upload_id="(\d+)"[^>]*\bdownload_btn\b""")
    private val progressInterval = TimeUnit.MILLISECONDS.toNanos(250)

    data class Progress(val bytes: Long = 0, val total: Long? = null)

    fun open(onProgress: (Progress) -> Unit, onDownloaded: () -> Unit): InputStream {
        val page = metadata(URL(PAGE), "GET", 512 * 1024)
        val uploadId = uploadButton.findAll(page).map { it.groupValues[1] }.distinct().singleOrNull()
            ?: throw IOException("The author’s page did not list a single public WickedWhims download.")
        val response = metadata(URL("$PAGE/file/$uploadId?source=game_download"), "POST", 16 * 1024)
        val link = runCatching { JSONObject(response).getString("url") }.getOrNull()
            ?: throw IOException("The download site did not provide an archive link.")
        val url = runCatching { URL(link) }.getOrNull()
            ?: throw IOException("The download site returned an invalid archive link.")
        return archive(url, onProgress, onDownloaded)
    }

    private fun metadata(url: URL, method: String, maxBytes: Int): String {
        val connection = connect(url, method)
        try {
            if (connection.responseCode != 200) throw IOException("The download site returned HTTP ${connection.responseCode}.")
            return connection.inputStream.use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > maxBytes) throw IOException("The download site returned an oversized response.")
                    bytes.write(buffer, 0, count)
                }
                bytes.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun archive(start: URL, onProgress: (Progress) -> Unit, onDownloaded: () -> Unit): InputStream {
        var url = start
        repeat(5) {
            if (!isArchiveHost(url)) throw IOException("The download site returned an unexpected archive host.")
            val connection = connect(url, "GET")
            var handedOff = false
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val next = connection.getHeaderField("Location")
                        ?: throw IOException("The archive redirect did not provide a destination.")
                    url = URL(url, next)
                } else {
                    if (status != 200) throw IOException("The archive server returned HTTP $status.")
                    val total = connection.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                    if (total != null && total > ArchiveLimits().maxEntryBytes) {
                        throw IOException("The archive exceeds the animation import size limit.")
                    }
                    val input = BufferedInputStream(connection.inputStream)
                    try {
                        input.mark(4)
                        val zip = input.read() == 0x50 && input.read() == 0x4b && input.read() == 3 && input.read() == 4
                        if (!zip) throw IOException("The download site did not send a ZIP archive.")
                        input.reset()
                        onProgress(Progress(total = total))
                        val stream = downloadStream(connection, input, total, onProgress, onDownloaded)
                        handedOff = true
                        return stream
                    } finally {
                        if (!handedOff) input.close()
                    }
                }
            } finally {
                if (!handedOff) connection.disconnect()
            }
        }
        throw IOException("The archive server redirected too many times.")
    }

    private fun connect(url: URL, method: String): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        useCaches = false
        requestMethod = method
        connectTimeout = 20_000
        readTimeout = 30_000
        setRequestProperty("Referer", PAGE)
        setRequestProperty("Accept-Encoding", "identity")
        if (method == "POST") setRequestProperty("X-Requested-With", "XMLHttpRequest")
    }

    private fun isArchiveHost(url: URL): Boolean {
        val host = url.host.lowercase()
        return url.protocol == "https" && url.userInfo == null && (url.port == -1 || url.port == 443) &&
            (host.endsWith(".itch.zone") || host.endsWith(".itch.io") ||
                host.startsWith("itchio-mirror.") && host.endsWith(".r2.cloudflarestorage.com"))
    }

    private fun downloadStream(
        connection: HttpURLConnection,
        input: InputStream,
        total: Long?,
        onProgress: (Progress) -> Unit,
        onDownloaded: () -> Unit,
    ): InputStream = object : FilterInputStream(input) {
        private var bytes = 0L
        private var lastUpdate = System.nanoTime()
        private var finished = false

        override fun read(): Int = transfer { super.read() }.also { report(if (it < 0) -1 else 1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            transfer { super.read(buffer, offset, length) }.also(::report)

        private fun transfer(read: () -> Int): Int = try {
            read()
        } catch (_: IOException) {
            throw IOException("The WickedWhims download was interrupted. Try installing again.")
        }

        private fun report(count: Int) {
            if (finished) return
            if (count < 0) {
                if (total != null && bytes != total) throw IOException("The download ended early. Try installing again.")
                finished = true
                onDownloaded()
                return
            }
            bytes += count
            val now = System.nanoTime()
            if (now - lastUpdate >= progressInterval) {
                lastUpdate = now
                onProgress(Progress(bytes, total))
            }
        }

        override fun close() {
            try {
                super.close()
            } finally {
                connection.disconnect()
            }
        }
    }
}
