package com.logie.wifecraft.anim

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

internal class PackRateLimited(val retryAfter: Long?) :
    IOException("The author’s download server is still busy. Try again later.")

/** Waits out a busy download server: Retry-After when it says, else 15 s, 30 s, 60 s; three tries; the wait outlives one install. */
internal class PackRetry(private val now: () -> Long = System::currentTimeMillis) {
    @Volatile private var retryAt = 0L

    suspend fun <T> run(wait: suspend (Long) -> Unit, request: () -> T): T {
        for (attempt in 0..2) {
            val remaining = retryAt - now()
            if (remaining > 0) wait(remaining)
            try {
                return request().also { retryAt = 0 }
            } catch (limited: PackRateLimited) {
                val time = now()
                val fallback = 15_000L shl attempt
                val delay = maxOf(2_000L, limited.retryAfter ?: fallback)
                retryAt = time + delay.coerceAtMost(Long.MAX_VALUE - time)
                if (attempt == 2) throw limited
            }
        }
        error("Unreachable download attempt")
    }

    companion object {
        fun retryAfter(header: String?, now: Long = System.currentTimeMillis()): Long? {
            val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (value.all { it in '0'..'9' }) {
                val seconds = value.toLongOrNull() ?: Long.MAX_VALUE
                return seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
            }
            return runCatching {
                val deadline: Instant = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                (deadline.toEpochMilli() - now).coerceAtLeast(0L)
            }.getOrNull()
        }
    }
}

/**
 * How one download site lists a pack's files ([kind]: "itch" an itch.io page's upload button, "wicked" a wicked.cc page's
 * `/download/<id>` file cards) and the hosts it may use: [pageHosts] for the page and its download links, [fileHosts] for
 * where those redirect to (a "*" in an entry stands for one or more host-name characters: "*.example.com"). Only https on
 * its own port.
 */
data class PackRule(val kind: String, val pageHosts: List<String>, val fileHosts: List<String>) {
    fun pageAllowed(url: URL): Boolean = PackLinks.allowed(url, pageHosts)
    fun fileAllowed(url: URL): Boolean = PackLinks.allowed(url, pageHosts + fileHosts)
}

/** One pack the install list offers: its [name] and [author] as the page gives them, its [page], read by [rule]. */
data class PackSource(val id: String, val name: String, val author: String, val page: String, val rule: PackRule) {
    /** WickedWhims itself: saved straight to the library, and it carries the Sims skeleton the other packs need. */
    val isWickedWhims: Boolean get() = rule.kind == PackLinks.ITCH
}

/** One file a pack's page offers: [name] and [size] as the page shows them, [url] its download link. */
data class PackFile(val name: String, val size: String, val url: String)

/** The install list (assets/anim_packs.json): the rules by name and the packs. Only page addresses and names are in it. */
object PackCatalog {
    const val ASSET = "anim_packs.json"

    fun parse(json: String): List<PackSource> {
        val root = JSONObject(json)
        val rulesJson = root.getJSONObject("rules")
        val rules = rulesJson.keys().asSequence().associateWith { key ->
            val r = rulesJson.getJSONObject(key)
            fun list(name: String) = r.optJSONArray(name)?.let { a -> List(a.length()) { a.getString(it).lowercase() } }.orEmpty()
            PackRule(r.getString("kind"), list("pageHosts"), list("fileHosts"))
        }
        val packs = root.getJSONArray("packs")
        return List(packs.length()) { i ->
            val p = packs.getJSONObject(i)
            val rule = rules[p.getString("rule")] ?: throw IOException("Pack ${p.optString("id")} names an unknown rule")
            val source = PackSource(p.getString("id"), p.getString("name"), p.optString("author"), p.getString("page"), rule)
            require(rule.pageAllowed(URL(source.page))) { "Pack ${source.id}'s page is not on its rule's hosts" }
            source
        }
    }
}

/** Reading a pack page's links. Pure: no network. */
object PackLinks {
    const val ITCH = "itch"
    const val WICKED = "wicked"

    private val uploadButton = Regex("""\bdata-upload_id="(\d+)"[^>]*\bdownload_btn\b""")
    private val fileCard = Regex("""<div class="file-card-name">\s*(.*?)\s*</div>(.*?)<a href="([^"]+)"[^>]*\bdownload-btn\b""",
        setOf(RegexOption.DOT_MATCHES_ALL))
    private val cardSize = Regex("""<div class="file-card-meta">\s*<span>\s*([^<]*?)\s*</span>""")
    private val downloadId = Regex("""/download/[A-Za-z0-9]{4,64}""")

    /** https on the default port, no user info, and the host one of [patterns] (exact, or under a "*." one). */
    fun allowed(url: URL, patterns: List<String>): Boolean {
        val host = url.host.lowercase()
        if (url.protocol != "https" || url.userInfo != null || (url.port != -1 && url.port != 443) || host.isEmpty()) return false
        return patterns.any { p -> glob(p).matches(host) }
    }

    /** A host pattern: "*" stands for one or more of a host name's characters ("*.itch.zone", "mirror.*.example.com"). */
    private fun glob(pattern: String): Regex =
        Regex(pattern.lowercase().split('*').joinToString("[a-z0-9.-]+") { Regex.escape(it) })

    /** The single public upload on an itch.io page; null when it lists none or several. */
    fun itchUpload(html: String): String? = uploadButton.findAll(html).map { it.groupValues[1] }.distinct().singleOrNull()

    /**
     * The files a wicked.cc pack page offers, in its order: each card's file name, size and link. A link is kept only when it
     * is the page's own address followed by `/download/<id>` (no other site, no other pack's file).
     */
    fun wickedFiles(html: String, page: URL): List<PackFile> {
        val base = page.toString().trimEnd('/')
        return fileCard.findAll(html).mapNotNull { m ->
            // a card without a button of its own would borrow the next card's link
            if (m.groupValues[2].contains("file-card-name")) return@mapNotNull null
            val href = unescape(m.groupValues[3].trim())
            val link = runCatching { URL(page, href) }.getOrNull() ?: return@mapNotNull null
            val text = link.toString()
            if (!text.startsWith("$base/download/") || !downloadId.matches(text.substring(base.length))) return@mapNotNull null
            val name = unescape(m.groupValues[1].replace(Regex("<[^>]*>"), "").trim()).ifEmpty { "file" }
            val size = cardSize.find(m.groupValues[2])?.groupValues?.get(1)?.let(::unescape).orEmpty()
            PackFile(name, size, text)
        }.distinctBy { it.url }.toList()
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">")

    /** The file to install without asking: the only one; null when the person has to choose. */
    fun single(files: List<PackFile>): PackFile? = files.singleOrNull()

    /** The cookies a page set ("name=value" pairs only), to send back with its download link as a browser does. */
    fun cookies(setCookie: List<String>?): String = setCookie.orEmpty()
        .mapNotNull { it.substringBefore(';').trim().takeIf { c -> c.contains('=') && c.none { ch -> ch == '\r' || ch == '\n' } } }
        .joinToString("; ")
}

/**
 * Downloads a pack's archive from its author's page ([PackSource]) and streams it into the animation importer. Nothing is
 * fetched until [files] is called (the person tapped Install); every address is checked against the pack's rule; a busy
 * server's Retry-After is waited out ([PackRetry]); the archive must be a ZIP, a 7z or a .package.
 */
object PackDownload {
    private val progressInterval = TimeUnit.MILLISECONDS.toNanos(250)
    private val retry = PackRetry()
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android)"

    data class Progress(val bytes: Long = 0, val total: Long? = null)

    /** A page's files ([PackLinks]) and the cookies it set. */
    class Listing(val files: List<PackFile>, val cookies: String)

    suspend fun files(pack: PackSource, wait: suspend (Long) -> Unit): Listing = retry.run(wait) { list(pack) }

    suspend fun open(
        pack: PackSource, file: PackFile, cookies: String,
        wait: suspend (Long) -> Unit, onProgress: (Progress) -> Unit, onDownloaded: () -> Unit,
    ): InputStream = retry.run(wait) { fetch(pack, file, cookies, onProgress, onDownloaded) }

    private fun list(pack: PackSource): Listing {
        val page = URL(pack.page)
        if (!pack.rule.pageAllowed(page)) throw IOException("The pack's page is not on an allowed site.")
        val (html, cookies) = text(page, "GET", 1024 * 1024, pack.page, "")
        val files = when (pack.rule.kind) {
            PackLinks.ITCH -> {
                val upload = PackLinks.itchUpload(html) ?: throw IOException("The author’s page did not list a single public download.")
                listOf(PackFile(pack.name, "", "${pack.page.trimEnd('/')}/file/$upload?source=game_download"))
            }
            PackLinks.WICKED -> PackLinks.wickedFiles(html, page)
            else -> throw IOException("This pack's site is not supported.")
        }
        if (files.isEmpty()) throw IOException("The author’s page did not list a download.")
        return Listing(files, cookies)
    }

    private fun fetch(pack: PackSource, file: PackFile, cookies: String, onProgress: (Progress) -> Unit, onDownloaded: () -> Unit): InputStream {
        val start = URL(file.url)
        if (!pack.rule.pageAllowed(start)) throw IOException("The download link is not on the pack's site.")
        val archiveUrl = if (pack.rule.kind == PackLinks.ITCH) {
            // itch.io hands the archive's address out for a POST from its page
            val (response, _) = text(start, "POST", 16 * 1024, pack.page, cookies)
            val link = runCatching { JSONObject(response).getString("url") }.getOrNull()
                ?: throw IOException("The download site did not provide an archive link.")
            runCatching { URL(link) }.getOrNull() ?: throw IOException("The download site returned an invalid archive link.")
        } else start
        return archive(pack, archiveUrl, cookies, onProgress, onDownloaded)
    }

    private fun text(url: URL, method: String, maxBytes: Int, referer: String, cookies: String): Pair<String, String> {
        val connection = connect(url, method, referer, cookies)
        try {
            val status = connection.responseCode
            checkRateLimit(connection, status)
            if (status != 200) throw IOException("The download site returned HTTP $status.")
            val set = PackLinks.cookies(connection.headerFields?.entries?.firstOrNull { it.key.equals("Set-Cookie", true) }?.value)
            val body = connection.inputStream.use { input ->
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
            return body to set
        } finally {
            connection.disconnect()
        }
    }

    private fun archive(pack: PackSource, start: URL, cookies: String, onProgress: (Progress) -> Unit, onDownloaded: () -> Unit): InputStream {
        var url = start
        repeat(5) {
            if (!pack.rule.fileAllowed(url)) throw IOException("The download site sent the file from a site that is not allowed.")
            // the page's cookies go back only to the page's own site
            val connection = connect(url, "GET", pack.page, if (pack.rule.pageAllowed(url)) cookies else "")
            var handedOff = false
            try {
                val status = connection.responseCode
                checkRateLimit(connection, status)
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
                        input.mark(8)
                        val head = ByteArray(6)
                        var n = 0
                        while (n < head.size) { val r = input.read(head, n, head.size - n); if (r < 0) break; n += r }
                        if (!isArchive(head, n)) {
                            throw IOException(if (connection.contentType?.startsWith("text/html") == true)
                                "The download site sent a web page instead of the file. Try again later."
                            else "The download site did not send a ZIP, 7z or .package file.")
                        }
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

    /** A ZIP, a 7z or a Sims 4 package, by its first bytes. */
    fun isArchive(head: ByteArray, n: Int = head.size): Boolean {
        fun starts(vararg b: Int) = n >= b.size && b.indices.all { (head[it].toInt() and 0xFF) == b[it] }
        return starts(0x50, 0x4B, 0x03, 0x04) || starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) || starts(0x44, 0x42, 0x50, 0x46)
    }

    private fun checkRateLimit(connection: HttpURLConnection, status: Int) {
        val header = connection.getHeaderField("Retry-After")
        if (status == 429 || (status == 503 && header != null)) throw PackRateLimited(PackRetry.retryAfter(header))
    }

    private fun connect(url: URL, method: String, referer: String, cookies: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept-Encoding", "identity")
            if (cookies.isNotEmpty()) setRequestProperty("Cookie", cookies)
            if (method == "POST") setRequestProperty("X-Requested-With", "XMLHttpRequest")
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
            throw IOException("The download was interrupted. Try installing again.")
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
