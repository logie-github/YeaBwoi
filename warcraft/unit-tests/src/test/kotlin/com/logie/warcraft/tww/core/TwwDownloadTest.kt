// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.tww.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TwwDownloadTest {
    private val archive = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write(ByteArray(512 * 1024) { (it * 31).toByte() })
            zip.closeEntry()
        }
    }.toByteArray()

    private fun fixture(run: (HttpServer, File, String) -> Unit) {
        val directory = Files.createTempDirectory("tww-download-test").toFile()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try { run(server, directory, "http://127.0.0.1:${server.address.port}") }
        finally { server.stop(0); directory.deleteRecursively() }
    }

    private fun HttpExchange.body(bytes: ByteArray, code: Int = 200) {
        responseHeaders.add("Content-Type", "application/octet-stream")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }

    private fun savedPartial(directory: File, url: String, bytes: ByteArray = archive, etag: String = "\"v1\""): Int {
        val from = bytes.size / 2
        File(directory, "repack.part").writeBytes(bytes.copyOf(from))
        File(directory, "repack.properties").outputStream().use { output ->
            Properties().apply {
                setProperty("source", url)
                setProperty("total", bytes.size.toString())
                setProperty("etag", etag)
            }.store(output, null)
        }
        return from
    }

    @Test fun `downloads real bytes and reuses only validated completed files`() = fixture { server, directory, base ->
        var requests = 0
        server.createContext("/archive") { exchange -> requests++; exchange.body(archive) }
        val progress = mutableListOf<TwwDownload.Progress>()
        val first = TwwDownload("$base/archive", directory, "repack").run(onProgress = { progress += it })
        assertContentEquals(archive, first.readBytes())
        assertFalse(File(directory, "repack.part").exists())
        assertEquals(archive.size.toLong(), progress.last().bytes)
        assertEquals(archive.size.toLong(), progress.last().total)
        assertEquals(1, requests)

        val again = TwwDownload("$base/archive", directory, "repack").run(onProgress = { progress += it })
        assertEquals(first, again)
        assertTrue(progress.last().reused)
        assertEquals(1, requests)

        first.writeBytes(ByteArray(archive.size))
        TwwDownload("$base/archive", directory, "repack").run()
        assertEquals(2, requests)
        assertContentEquals(archive, first.readBytes())
    }

    @Test fun `redirects retain server cookies`() = fixture { server, directory, base ->
        server.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Set-Cookie", "download_warning=token; Path=/")
            exchange.responseHeaders.add("Location", "/archive")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/archive") { exchange ->
            assertTrue(exchange.requestHeaders.getFirst("Cookie").orEmpty().contains("download_warning=token"))
            exchange.body(archive)
        }
        assertContentEquals(archive, TwwDownload("$base/start", directory, "repack").run().readBytes())
    }

    @Test fun `resumes partial only with matching Content-Range and validator`() = fixture { server, directory, base ->
        val source = "$base/archive"
        val from = savedPartial(directory, source)
        server.createContext("/archive") { exchange ->
            assertEquals("bytes=$from-", exchange.requestHeaders.getFirst("Range"))
            assertEquals("\"v1\"", exchange.requestHeaders.getFirst("If-Range"))
            exchange.responseHeaders.add("ETag", "\"v1\"")
            exchange.responseHeaders.add("Content-Range", "bytes $from-${archive.lastIndex}/${archive.size}")
            exchange.body(archive.copyOfRange(from, archive.size), 206)
        }
        assertContentEquals(archive, TwwDownload(source, directory, "repack").run().readBytes())
    }

    @Test fun `a server ignoring Range restarts without appending a second archive`() = fixture { server, directory, base ->
        val source = "$base/archive"
        savedPartial(directory, source)
        var requests = 0
        server.createContext("/archive") { exchange -> requests++; exchange.body(archive) }
        assertContentEquals(archive, TwwDownload(source, directory, "repack").run().readBytes())
        assertEquals(2, requests)
    }

    @Test fun `changed ETag or invalid Content-Range restarts safely`() = fixture { server, directory, base ->
        for (changedETag in listOf(false, true)) {
            val path = if (changedETag) "/etag" else "/range"
            val source = base + path
            val from = savedPartial(directory, source)
            var requests = 0
            server.createContext(path) { exchange ->
                requests++
                if (exchange.requestHeaders.getFirst("Range") != null) {
                    exchange.responseHeaders.add("ETag", if (changedETag) "\"v2\"" else "\"v1\"")
                    val offset = if (changedETag) from else 0
                    exchange.responseHeaders.add("Content-Range", "bytes $offset-${archive.lastIndex}/${archive.size}")
                    exchange.body(archive.copyOfRange(offset, archive.size), 206)
                } else exchange.body(archive)
            }
            assertContentEquals(archive, TwwDownload(source, directory, "repack").run().readBytes())
            assertEquals(2, requests)
        }
    }

    @Test fun `416 discards an unusable range and retries the whole file`() = fixture { server, directory, base ->
        val source = "$base/archive"
        savedPartial(directory, source)
        var requests = 0
        server.createContext("/archive") { exchange ->
            requests++
            if (exchange.requestHeaders.getFirst("Range") != null) {
                exchange.sendResponseHeaders(416, -1); exchange.close()
            } else exchange.body(archive)
        }
        assertContentEquals(archive, TwwDownload(source, directory, "repack").run().readBytes())
        assertEquals(2, requests)
    }

    @Test fun `missing validators cause a fresh download`() = fixture { server, directory, base ->
        val source = "$base/archive"
        savedPartial(directory, source, etag = "")
        server.createContext("/archive") { exchange ->
            assertEquals(null, exchange.requestHeaders.getFirst("Range"))
            exchange.body(archive)
        }
        assertContentEquals(archive, TwwDownload(source, directory, "repack").run().readBytes())
    }

    @Test fun `HTML access errors are never saved as successful archives`() = fixture { server, directory, base ->
        server.createContext("/archive") { exchange ->
            val bytes = "<html><body>Request access. You need permission.</body></html>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val error = assertFailsWith<TwwDownload.DownloadException> { TwwDownload("$base/archive", directory, "repack").run() }
        assertTrue(error.message.orEmpty().contains("permission"))
        assertFalse(File(directory, "repack.archive").exists())
    }

    @Test fun `unlabelled HTML and wrong hashes are deleted`() = fixture { server, directory, base ->
        server.createContext("/html") { exchange -> exchange.body("<html>Error</html>".toByteArray()) }
        assertFailsWith<TwwDownload.DownloadException> { TwwDownload("$base/html", directory, "repack").run() }
        assertFalse(File(directory, "repack.part").exists())
        server.createContext("/archive") { exchange -> exchange.body(archive) }
        val error = assertFailsWith<TwwDownload.DownloadException> {
            TwwDownload("$base/archive", directory, "repack", expectedSha256 = "0".repeat(64)).run()
        }
        assertTrue(error.message.orEmpty().contains("SHA-256"))
        assertFalse(File(directory, "repack.part").exists())
    }

    @Test fun `cancellation keeps a resumable partial and never promotes it`() = fixture { server, directory, base ->
        val source = "$base/archive"
        var cancel = false
        server.createContext("/archive") { exchange ->
            exchange.responseHeaders.add("ETag", "\"v1\"")
            exchange.body(archive)
        }
        assertFailsWith<TwwDownload.Cancelled> {
            TwwDownload(source, directory, "repack").run(cancelled = { cancel }, onProgress = { if (it.bytes > 0) cancel = true })
        }
        assertTrue(File(directory, "repack.part").isFile)
        assertFalse(File(directory, "repack.archive").exists())
        assertContentEquals(archive, TwwDownload(source, directory, "repack").run().readBytes())
    }

    @Test fun `Drive sharing URLs become download endpoints`() {
        assertEquals("https://drive.usercontent.google.com/download?id=abc_DEF-123&export=download",
            TwwDownload.normalize(URL("https://drive.google.com/file/d/abc_DEF-123/view")).toString())
    }

    @Test fun `Drive large file confirmation uses real hidden parameters and rejects foreign actions`() {
        val downloader = TwwDownload("https://drive.google.com/file/d/abc/view", File("unused"), "repack")
        val confirmation = downloader.confirmation(URL("https://drive.google.com/uc?id=abc"), """
            <html><form id="download-form" action="https://drive.usercontent.google.com/download" method="get">
              <input type="hidden" name="id" value="abc"><input type="hidden" name="export" value="download">
              <input type="hidden" name="confirm" value="t"><input type="hidden" name="uuid" value="one&amp;two">
            </form></html>
        """.trimIndent())
        assertNotNull(confirmation)
        assertEquals("drive.usercontent.google.com", confirmation.host)
        assertTrue(confirmation.query.contains("confirm=t"))
        assertTrue(confirmation.query.contains("uuid=one%26two"))
        assertFailsWith<TwwDownload.DownloadException> {
            downloader.confirmation(URL("https://drive.google.com/uc?id=abc"), """
                <form id="download-form" action="https://evil.invalid/download"><input name="confirm" value="x"></form>
            """.trimIndent())
        }
    }

    @Test fun `HTTP failures expose an actionable source error`() = fixture { server, directory, base ->
        for (code in listOf(403, 404, 429, 503)) {
            server.createContext("/$code") { exchange -> exchange.sendResponseHeaders(code, -1); exchange.close() }
            val error = assertFailsWith<TwwDownload.DownloadException> { TwwDownload("$base/$code", directory, "repack").run() }
            assertTrue(error.message.orEmpty().contains("$code"))
            assertFalse(File(directory, "repack.archive").exists())
        }
    }

    @Test fun `a body shorter than Content-Length is kept incomplete and never installed`() = fixture { server, directory, base ->
        server.createContext("/archive") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.responseHeaders.add("ETag", "\"v1\"")
            exchange.sendResponseHeaders(200, archive.size.toLong() + 100)
            exchange.responseBody.write(archive)
            exchange.close()
        }
        assertFailsWith<TwwDownload.DownloadException> { TwwDownload("$base/archive", directory, "repack").run() }
        assertFalse(File(directory, "repack.archive").exists())
        assertTrue(File(directory, "repack.part").length() <= archive.size)
    }

    @Test fun `unknown length transfer cannot exceed the expected size`() = fixture { server, directory, base ->
        server.createContext("/archive") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(archive) }
        }
        assertFailsWith<TwwDownload.DownloadException> {
            TwwDownload("$base/archive", directory, "repack", expectedSize = 16).run()
        }
        assertFalse(File(directory, "repack.archive").exists())
        assertTrue(File(directory, "repack.part").length() <= 16)
    }
}
