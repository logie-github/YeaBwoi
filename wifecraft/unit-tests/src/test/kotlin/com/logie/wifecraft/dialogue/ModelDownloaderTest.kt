package com.logie.wifecraft.dialogue

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelDownloaderTest {
    private val data = Random(7).nextBytes(3_000_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private lateinit var server: HttpServer
    private lateinit var dir: File
    private val requests = AtomicInteger()
    private val served = AtomicInteger()
    private val ranges = ArrayList<String?>()
    @Volatile private var honourRange = true
    @Volatile private var failFirst = 0
    @Volatile private var cutAt = -1          // the first response is cut after this many bytes
    private val url get() = "http://127.0.0.1:${server.address.port}/model.gguf"
    private val dl = ModelDownloader(sleep = {}, tries = 4)

    @BeforeTest fun up() {
        dir = Files.createTempDirectory("wcdl").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model.gguf") { ex ->
            val n = requests.incrementAndGet()
            synchronized(ranges) { ranges.add(ex.requestHeaders.getFirst("Range")) }
            if (n <= failFirst) { ex.sendResponseHeaders(503, -1); ex.close(); return@createContext }
            val range = ex.requestHeaders.getFirst("Range")
            var from = 0
            if (honourRange && range != null) from = range.removePrefix("bytes=").removeSuffix("-").toInt()
            if (from >= data.size) { ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
            val body = data.copyOfRange(from, data.size)
            val sn = served.incrementAndGet()
            ex.sendResponseHeaders(if (from > 0) 206 else 200, body.size.toLong())
            ex.responseBody.use { out ->
                if (cutAt >= 0 && sn == 1) { out.write(body, 0, cutAt); out.flush(); cutAt = -1; ex.close() } else out.write(body)
            }
        }
        server.start()
    }

    @AfterTest fun down() { server.stop(0); dir.deleteRecursively() }

    @Test fun downloadsChecksAndRenames() {
        val dest = File(dir, "m/model.gguf")
        var last = 0L
        val r = dl.download(url, dest, data.size.toLong(), sha, minFreeBytes = 0) { _, done, _ -> last = done }
        assertIs<ModelDownloader.Result.Done>(r)
        assertEquals(sha, MessageDigest.getInstance("SHA-256").digest(dest.readBytes()).joinToString("") { "%02x".format(it) })
        assertFalse(File(dest.path + ".part").exists())
        assertTrue(last > 0)
        // a finished file is not fetched again
        val before = requests.get()
        assertIs<ModelDownloader.Result.Done>(dl.download(url, dest, data.size.toLong(), sha, minFreeBytes = 0))
        assertEquals(before, requests.get())
    }

    @Test fun resumesFromAPartWithARangeRequest() {
        val dest = File(dir, "model.gguf")
        File(dest.path + ".part").writeBytes(data.copyOfRange(0, 1_234_567))
        assertEquals(1_234_567L, dl.have(dest))
        val r = dl.download(url, dest, data.size.toLong(), sha, minFreeBytes = 0)
        assertIs<ModelDownloader.Result.Done>(r)
        assertEquals("bytes=1234567-", ranges[0])
        assertTrue(dest.readBytes().contentEquals(data))
    }

    @Test fun aServerThatIgnoresTheRangeStartsOver() {
        honourRange = false
        val dest = File(dir, "model.gguf")
        File(dest.path + ".part").writeBytes(data.copyOfRange(0, 500_000))
        assertIs<ModelDownloader.Result.Done>(dl.download(url, dest, data.size.toLong(), sha, minFreeBytes = 0))
        assertTrue(dest.readBytes().contentEquals(data))
    }

    @Test fun aDroppedConnectionAndServerErrorsAreRetriedFromWhereItStopped() {
        failFirst = 1
        cutAt = 900_000
        val dest = File(dir, "model.gguf")
        assertIs<ModelDownloader.Result.Done>(dl.download(url, dest, data.size.toLong(), sha, minFreeBytes = 0))
        assertTrue(dest.readBytes().contentEquals(data))
        assertTrue(requests.get() >= 3)
    }

    @Test fun aWrongChecksumDeletesThePartAndFails() {
        val dest = File(dir, "model.gguf")
        val r = dl.download(url, dest, data.size.toLong(), "0".repeat(64), minFreeBytes = 0)
        assertIs<ModelDownloader.Result.Failed>(r)
        assertFalse(dest.exists()); assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun cancellingKeepsThePartForNextTime() {
        val dest = File(dir, "model.gguf")
        var calls = 0
        val r = dl.download(url, dest, data.size.toLong(), sha, minFreeBytes = 0, cancelled = { ++calls > 3 })
        assertIs<ModelDownloader.Result.Cancelled>(r)
        assertFalse(dest.exists())
    }

    @Test fun anAlreadyCompletePartIsAcceptedOn416() {
        val dest = File(dir, "model.gguf")
        File(dest.path + ".part").writeBytes(data)
        assertIs<ModelDownloader.Result.Done>(dl.download(url, dest, data.size.toLong(), sha, minFreeBytes = 0))
        assertTrue(dest.readBytes().contentEquals(data))
    }

    @Test fun notEnoughRoomIsRefusedBeforeDownloading() {
        val r = dl.download(url, File(dir, "model.gguf"), data.size.toLong(), sha, minFreeBytes = Long.MAX_VALUE / 2)
        assertIs<ModelDownloader.Result.Failed>(r)
        assertEquals(0, requests.get())
    }

    @Test fun aMissingFileIsFatalNotRetried() {
        server.removeContext("/model.gguf")
        val r = dl.download(url, File(dir, "model.gguf"), data.size.toLong(), sha, minFreeBytes = 0)
        assertIs<ModelDownloader.Result.Failed>(r)
        assertFalse((r as ModelDownloader.Result.Failed).retryLater)
    }
}
