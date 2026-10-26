// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Warcraft III's films (`Movies\*.mpq`, which are AVI files): 800x368 MPEG-4 video (Simple
 * Profile: only I and P frames) under Blizzard's own tag `BLZ0`, and constant-rate MP3 sound. The
 * game plays them through DirectShow with its `blizzard.ax` (DivX 4), which under Wine draws
 * nothing: Wine's AVI splitter decodes the video itself to I420, which its renderers can't take,
 * and with Windows' splitter the 2001 decoder crashes. So the app plays them itself, with Android's
 * own MPEG-4 decoder ([com.logie.warcrafts.game.Wc3MoviePlayer]), reading each file with a few
 * bytes changed ([patches]):
 *
 * - the video's tag `BLZ0` as `XVID`, which Android's AVI reader (Media3's) knows for the same video;
 * - the sound's stream header in chunks: Media3 times a stream's chunks as its duration over its
 *   length, and these give their length in bytes (rate 16000 bytes a second), which would put all
 *   of the sound in the film's first second. Every sound chunk of a film is the same size, so the
 *   header becomes: a chunk's bytes over the bytes a second, as many as there are.
 */
object Wc3Movie {
    /** Blizzard's tag for the video, and the one Android's AVI reader takes for the same MPEG-4 video. */
    val BLIZZARD = "BLZ0".toByteArray(Charsets.ISO_8859_1)
    val ANDROID = "XVID".toByteArray(Charsets.ISO_8859_1)

    /** Bytes of the file read as [bytes] from [offset] on. */
    class Patch(val offset: Long, val bytes: ByteArray)

    /** What to read differently in [file] for Android to play it, or null if it isn't one of Blizzard's films. */
    fun patches(file: RandomAccessFile): List<Patch>? {
        val length = file.length()
        if (length < 12 || tag(read(file, 0, 12), 0) != "RIFF" || tag(read(file, 8, 4), 0) != "AVI ") return null
        // The top-level chunks: the headers' list and the index.
        var headers: Pair<Long, Int>? = null
        var index: Pair<Long, Int>? = null
        var at = 12L
        while (at + 8 <= length) {
            val head = read(file, at, 12)
            val name = tag(head, 0)
            val size = int(head, 4).toLong() and 0xffffffffL
            if (name == "LIST" && tag(head, 8) == "hdrl") headers = at + 12 to (size - 4).toInt()
            if (name == "idx1") index = at + 8 to size.toInt()
            at += 8 + size + (size and 1)
        }
        val (headerAt, headerSize) = headers ?: return null
        if (headerSize !in 1..(1 shl 20)) return null
        val header = read(file, headerAt, headerSize)
        val streams = streamHeaders(header)
        val video = streams.firstOrNull { it.type == "vids" } ?: return null
        val format = video.format ?: return null
        val compression = format + 16
        if (compression + 4 > header.size || !BLIZZARD.indices.all { header[compression + it] == BLIZZARD[it] }) return null
        val patches = arrayListOf(Patch(headerAt + compression, ANDROID))

        // The sound: its chunks' size and count from the index ("01wb" for the second stream).
        val audio = streams.withIndex().firstOrNull { it.value.type == "auds" }
        val (indexAt, indexSize) = index ?: return patches
        if (audio == null || indexSize <= 0) return patches
        val id = "%02dwb".format(audio.index)
        val entries = read(file, indexAt, indexSize)
        var count = 0
        var chunk = -1
        for (entry in 0 until indexSize / 16) {
            if (tag(entries, entry * 16) != id) continue
            val size = int(entries, entry * 16 + 12)
            if (chunk < 0) chunk = size
            // Chunks of different sizes: not evenly spaced in time, so the header is left as it is.
            if (size != chunk) return patches
            count++
        }
        val bytesPerSecond = audio.value.format?.let { int(header, it + 8) } ?: return patches
        if (count == 0 || chunk <= 0 || bytesPerSecond <= 0) return patches
        val stream = audio.value.header
        // strh: type, handler, flags, priority and language, initial frames, then scale, rate, start, length.
        patches += Patch(headerAt + stream + 20, ints(chunk, bytesPerSecond))
        patches += Patch(headerAt + stream + 32, ints(count))
        return patches
    }

    /** [bytes] (read from [position] in the file, [length] of them from [offset]) as Android reads them: with [patches]. */
    fun apply(bytes: ByteArray, offset: Int, length: Int, position: Long, patches: List<Patch>) {
        for (patch in patches) {
            for (i in patch.bytes.indices) {
                val at = patch.offset + i - position
                if (at in 0 until length) bytes[offset + at.toInt()] = patch.bytes[i]
            }
        }
    }

    /** Whether [path] (a file a program has open) is one of the game's films, in the game's folder [game]. */
    fun isFilm(path: String, game: String): Boolean {
        if (!path.startsWith("$game/")) return false
        val relative = path.substring(game.length + 1)
        val folder = relative.substringBefore('/', "")
        val name = relative.substringAfter('/', "")
        return folder.equals("Movies", ignoreCase = true) && name.isNotEmpty() && !name.contains('/') &&
            (name.endsWith(".mpq", ignoreCase = true) || name.endsWith(".avi", ignoreCase = true))
    }

    /** A stream's header (where its strh's data starts) and format (its strf's data), in the headers' list. */
    private class Stream(val type: String, val header: Int, val format: Int?)

    private fun streamHeaders(header: ByteArray): List<Stream> {
        val streams = ArrayList<Stream>()
        var at = 0
        while (at + 8 <= header.size) {
            val name = tag(header, at)
            val size = int(header, at + 4)
            if (name == "LIST") {
                at += 12
                continue
            }
            if (size < 0) break
            when (name) {
                "strh" -> if (at + 12 <= header.size) streams += Stream(tag(header, at + 8), at + 8, null)
                "strf" -> streams.lastOrNull()?.let { streams[streams.size - 1] = Stream(it.type, it.header, at + 8) }
            }
            at += 8 + size + (size and 1)
        }
        return streams
    }

    private fun read(file: RandomAccessFile, at: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        file.seek(at)
        file.readFully(out)
        return out
    }

    private fun ints(vararg values: Int): ByteArray {
        val buffer = ByteBuffer.allocate(4 * values.size).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) buffer.putInt(value)
        return buffer.array()
    }

    private fun int(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun tag(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.ISO_8859_1)
}
