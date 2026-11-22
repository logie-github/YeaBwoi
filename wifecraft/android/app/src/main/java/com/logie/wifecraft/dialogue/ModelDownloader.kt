package com.logie.wifecraft.dialogue

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches one big file (the dialogue model, 1 to 2.5 GB) from Hugging Face the way Options' "Download everything" fetches the
 * game's files: in the background, resumable, retried with backoff, checked at the end. The bytes go to `<file>.part` beside
 * the destination; a restart (the app closed, the phone rebooted, a dropped connection) asks the server for the rest with a
 * Range request and carries on from the part's length; when every byte is here its SHA-256 is compared with the one the
 * catalogue carries (a mismatch deletes the part: it is damaged or the wrong file), and only then is the file renamed into
 * place. Pure JVM (java.net): JVM-tested against a local server (ModelDownloaderTest).
 */
class ModelDownloader(
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val tries: Int = 6,
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000,
) {
    sealed class Result {
        data class Done(val file: File) : Result()
        /** Stopped by the caller; the part is kept for the next try. */
        object Cancelled : Result()
        data class Failed(val message: String, val retryLater: Boolean) : Result()
    }

    enum class Phase { DOWNLOADING, CHECKING }

    /** Bytes already on the phone for [dest]: the finished file's size, or the part's. */
    fun have(dest: File): Long = if (dest.isFile) dest.length() else File(dest.path + ".part").takeIf { it.isFile }?.length() ?: 0L

    fun download(
        url: String, dest: File, expectedBytes: Long, sha256: String?, minFreeBytes: Long = 300L * 1024 * 1024,
        cancelled: () -> Boolean = { false }, progress: (Phase, Long, Long) -> Unit = { _, _, _ -> },
    ): Result {
        dest.absoluteFile.parentFile?.mkdirs()
        if (dest.isFile && dest.length() == expectedBytes) {
            if (sha256 == null || verify(dest, sha256, cancelled, progress)) return Result.Done(dest)
            if (cancelled()) return Result.Cancelled
        }
        if (dest.isFile) dest.delete()
        val part = File(dest.path + ".part")
        var attempt = 0
        var lastError = "the download did not finish"
        while (attempt < tries) {
            if (cancelled()) return Result.Cancelled
            val have = if (part.isFile) part.length() else 0L
            if (expectedBytes > 0 && have > expectedBytes) { part.delete(); continue }
            val room = dest.absoluteFile.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (expectedBytes > 0 && room < expectedBytes - have + minFreeBytes) {
                return Result.Failed("not enough room: ${(expectedBytes - have + minFreeBytes) / 1_000_000} MB needed, ${room / 1_000_000} MB free", false)
            }
            when (val step = fetch(url, part, have, expectedBytes, cancelled) { done, total -> progress(Phase.DOWNLOADING, done, total) }) {
                is Step.Finished -> {
                    if (expectedBytes > 0 && part.length() != expectedBytes) {
                        lastError = "got ${part.length()} bytes, expected $expectedBytes"
                        part.delete()
                    } else {
                        if (sha256 != null && !verify(part, sha256, cancelled, progress)) {
                            return if (cancelled()) Result.Cancelled else { part.delete(); Result.Failed("the file is damaged (its checksum does not match); it was deleted, try again", true) }
                        }
                        if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
                        return Result.Done(dest)
                    }
                }
                is Step.Stopped -> return Result.Cancelled
                is Step.Fatal -> return Result.Failed(step.message, false)
                is Step.Retry -> lastError = step.message
            }
            attempt++
            if (attempt < tries) sleep(minOf(30_000L, 1000L shl attempt))
        }
        return Result.Failed(lastError, true)
    }

    private sealed class Step {
        object Finished : Step()
        object Stopped : Step()
        data class Retry(val message: String) : Step()
        data class Fatal(val message: String) : Step()
    }

    private fun fetch(url: String, part: File, have: Long, expected: Long, cancelled: () -> Boolean, onBytes: (Long, Long) -> Unit): Step {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Wifecraft")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = conn.responseCode
            var offset = have
            when {
                code == 416 -> {
                    // the part is as long as the file (or longer): nothing more to ask for
                    return if (expected > 0 && have == expected) Step.Finished else { part.delete(); Step.Retry("the server refused the range") }
                }
                code == 206 -> Unit
                code == 200 -> offset = 0L   // the server ignored the Range: start over
                code == 429 || code >= 500 -> return Step.Retry("the server answered $code")
                code == 401 || code == 403 || code == 404 -> return Step.Fatal("the server answered $code for the model's file (it may have moved)")
                else -> return Step.Retry("the server answered $code")
            }
            val total = if (expected > 0) expected else (conn.contentLengthLong.takeIf { it > 0 }?.plus(offset) ?: 0L)
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(offset)
                out.seek(offset)
                val buf = ByteArray(256 * 1024)
                var done = offset
                var lastReport = 0L
                conn.inputStream.use { input ->
                    while (true) {
                        if (cancelled()) return Step.Stopped
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastReport > 200_000_000L) { lastReport = now; onBytes(done, total) }
                    }
                }
                onBytes(done, total)
                if (total > 0 && done < total) return Step.Retry("the connection closed at $done of $total bytes")
            }
            return Step.Finished
        } catch (e: IOException) {
            return Step.Retry(e.message ?: "the connection failed")
        } finally {
            conn?.disconnect()
        }
    }

    fun verify(file: File, sha256: String, cancelled: () -> Boolean = { false }, progress: (Phase, Long, Long) -> Unit = { _, _, _ -> }): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        val total = file.length()
        var done = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                if (cancelled()) return false
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                progress(Phase.CHECKING, done, total)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.equals(sha256, ignoreCase = true)
    }
}
