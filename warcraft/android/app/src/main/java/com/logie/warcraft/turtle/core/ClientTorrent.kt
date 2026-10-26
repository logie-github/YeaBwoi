// SPDX-License-Identifier: GPL-3.0-or-later

package com.logie.warcraft.turtle.core

import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * The OctoWoW client as OctoWoW publishes it: its torrent (`dl.octowow.st/download/client.torrent`,
 * the file its own launcher syncs the client from), read here for what the app needs and nothing
 * else: every file with its size and place in the stream of all of them, the SHA-1 of each piece
 * of that stream, and the web seeds that serve each file over HTTP (BEP 19), which the app
 * downloads from with ranges. Nothing is shared with other peers.
 *
 * The pieces are how a download, an imported client and an update are checked: a file is right
 * when every piece it's in hashes to the torrent's value.
 */
class ClientTorrent(
    /** The torrent's top folder name (the client's files are under it on the web seed). */
    val name: String,
    val pieceLength: Long,
    /** The SHA-1 of each piece, 20 bytes each. */
    val pieces: List<ByteArray>,
    /** In the torrent's order, which is the order of the pieces' stream. */
    val files: List<Entry>,
    val webSeeds: List<String>,
    /** The SHA-1 of the torrent's info dictionary (its identity: a new client gets a new one), hex. */
    val infoHash: String,
) {
    /**
     * A file at [offset] in the stream; [padding]: a BEP 47 padding file, zeros in the stream and
     * never on disk.
     */
    data class Entry(val path: String, val size: Long, val offset: Long, val padding: Boolean = false) {
        val end: Long get() = offset + size
    }

    /** All the files together, in bytes (padding left out). */
    val totalSize: Long get() = files.filter { !it.padding }.sumOf { it.size }

    /** The stream's length, padding in. */
    val streamSize: Long get() = files.lastOrNull()?.end ?: 0L

    /** The files that are the client (no padding). */
    val realFiles: List<Entry> get() = files.filter { !it.padding }

    /** Bytes [first, last) of the stream that piece [index] covers. */
    fun pieceRange(index: Int): LongRange {
        val start = index * pieceLength
        return start until minOf(start + pieceLength, streamSize)
    }

    /** The pieces [entry] is in (empty for an empty file). */
    fun piecesOf(entry: Entry): IntRange =
        if (entry.size == 0L) IntRange.EMPTY else (entry.offset / pieceLength).toInt()..((entry.end - 1) / pieceLength).toInt()

    /** The files piece [index] covers part of. */
    fun filesOf(index: Int): List<Entry> {
        val range = pieceRange(index)
        return files.filter { it.size > 0 && it.offset <= range.last && it.end > range.first }
    }

    /**
     * What a file's content is, as far as the torrent can tell: its size and the hashes of the
     * pieces it's in. The same in two torrents means the same bytes (barring a change that keeps
     * every piece's hash, which SHA-1 makes as good as impossible here).
     */
    fun fileId(entry: Entry): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(entry.size.toString().toByteArray())
        digest.update(entry.offset.rem(pieceLength).toString().toByteArray())
        for (index in piecesOf(entry)) digest.update(pieces[index])
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** [entry]'s address on [seed] (BEP 19: a seed ending in `/` has the torrent's name and the path after it). */
    fun url(seed: String, entry: Entry): String {
        val path = entry.path.split('/').joinToString("/") { encode(it) }
        return if (seed.endsWith("/")) seed + encode(name) + "/" + path else seed
    }

    /** The entry for [path], matched without regard to case (as Windows does), or null. */
    fun find(path: String): Entry? = realFiles.firstOrNull { it.path.equals(path, ignoreCase = true) }

    /**
     * Going from the client [old] described to this one: the files that are new or have another
     * size, which an update downloads whole; the others it checks, and fetches only what differs.
     */
    data class Changes(val fetch: List<Entry>, val gone: List<String>) {
        val bytes: Long get() = fetch.sumOf { it.size }
    }

    fun changesFrom(old: ClientTorrent): Changes {
        val before = old.realFiles.associateBy { it.path.lowercase() }
        return Changes(
            fetch = realFiles.filter { before[it.path.lowercase()]?.size != it.size },
            gone = old.realFiles.filter { find(it.path) == null }.map { it.path },
        )
    }

    companion object {
        /** OctoWoW's client torrent, which its own launcher reads. */
        const val OCTOWOW_URL = "https://dl.octowow.st/download/client.torrent"

        /** Larger than any client's torrent should be (OctoWoW's: 184 kB for 9.5 GB). */
        const val MAX_SIZE = 8L shl 20

        private fun encode(part: String) = URLEncoder.encode(part, "UTF-8").replace("+", "%20")

        fun parse(bytes: ByteArray): ClientTorrent {
            if (bytes.size > MAX_SIZE) throw IOException("The client's torrent is too large (${bytes.size} bytes)")
            val reader = Bencode(bytes)
            val top = reader.value() as? Map<*, *> ?: throw IOException("Not a torrent")
            val info = top["info"] as? Map<*, *> ?: throw IOException("The torrent has no info")
            val infoBytes = bytes.copyOfRange(reader.infoStart, reader.infoEnd)
            val infoHash = MessageDigest.getInstance("SHA-1").digest(infoBytes).joinToString("") { "%02x".format(it) }
            val name = text(info["name"]) ?: throw IOException("The torrent has no name")
            require(ClientManifest.isSafePath(name) && '/' !in name && '\\' !in name) { "Unsafe name in the torrent: $name" }
            val pieceLength = (info["piece length"] as? Long)?.takeIf { it in 1..(1L shl 30) } ?: throw IOException("Bad piece length")
            val pieceBytes = info["pieces"] as? ByteArray ?: throw IOException("The torrent has no pieces")
            if (pieceBytes.size % 20 != 0) throw IOException("Bad pieces")
            val pieces = (0 until pieceBytes.size / 20).map { pieceBytes.copyOfRange(it * 20, it * 20 + 20) }
            val list = info["files"] as? List<*> ?: throw IOException("Not a torrent of a folder")
            val files = mutableListOf<Entry>()
            val seen = HashSet<String>()
            var offset = 0L
            for (item in list) {
                val file = item as? Map<*, *> ?: throw IOException("Bad file in the torrent")
                val size = (file["length"] as? Long)?.takeIf { it >= 0 } ?: throw IOException("Bad file size in the torrent")
                val parts = (file["path"] as? List<*>)?.map { text(it) ?: throw IOException("Bad path in the torrent") }
                    ?: throw IOException("Bad path in the torrent")
                val path = parts.joinToString("/")
                val padding = text(file["attr"])?.contains('p') == true
                if (!padding) {
                    if (parts.isEmpty() || parts.any { '/' in it || '\\' in it } || !ClientManifest.isSafePath(path)) {
                        throw IOException("Unsafe path in the torrent: $path")
                    }
                    if (!seen.add(path.lowercase())) throw IOException("The torrent has $path twice")
                }
                files += Entry(path, size, offset, padding)
                offset += size
            }
            val expected = if (offset == 0L) 0L else (offset + pieceLength - 1) / pieceLength
            if (pieces.size.toLong() != expected) throw IOException("The torrent's pieces don't cover its files")
            val seeds = when (val list = top["url-list"]) {
                is ByteArray -> listOfNotNull(text(list))
                is List<*> -> list.mapNotNull { text(it) }
                else -> emptyList()
            }.filter { it.startsWith("https://") }
            return ClientTorrent(name, pieceLength, pieces, files, seeds, infoHash)
        }

        private fun text(value: Any?): String? = (value as? ByteArray)?.toString(Charsets.UTF_8)
    }

    /** A bencode reader that also notes where the top level's `info` value is (for its hash). */
    private class Bencode(private val data: ByteArray) {
        private var at = 0
        private var depth = 0
        var infoStart = -1
        var infoEnd = -1

        fun value(): Any {
            if (++depth > 32) throw IOException("The torrent is nested too deeply")
            if (at >= data.size) throw IOException("The torrent ends early")
            val result: Any = when (data[at].toInt().toChar()) {
                'i' -> {
                    at++
                    number('e')
                }
                'l' -> {
                    at++
                    val list = mutableListOf<Any>()
                    while (peek() != 'e') list += value()
                    at++
                    list
                }
                'd' -> {
                    at++
                    val map = LinkedHashMap<String, Any>()
                    while (peek() != 'e') {
                        val key = (value() as? ByteArray)?.toString(Charsets.UTF_8) ?: throw IOException("Bad key in the torrent")
                        val start = at
                        val value = value()
                        if (depth == 1 && key == "info") {
                            infoStart = start
                            infoEnd = at
                        }
                        map[key] = value
                    }
                    at++
                    map
                }
                in '0'..'9' -> {
                    val length = number(':')
                    if (length < 0 || length > data.size - at) throw IOException("Bad string in the torrent")
                    data.copyOfRange(at, at + length.toInt()).also { at += length.toInt() }
                }
                else -> throw IOException("Not bencode at $at")
            }
            depth--
            return result
        }

        private fun peek(): Char {
            if (at >= data.size) throw IOException("The torrent ends early")
            return data[at].toInt().toChar()
        }

        private fun number(end: Char): Long {
            val start = at
            while (at < data.size && data[at].toInt().toChar() != end) at++
            if (at >= data.size || at - start > 19) throw IOException("Bad number in the torrent")
            val value = String(data, start, at - start, Charsets.US_ASCII).toLongOrNull() ?: throw IOException("Bad number in the torrent")
            at++
            return value
        }
    }
}
