// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

import java.security.MessageDigest

/**
 * Blizzard's classic Warcraft III installers, as its legacy downloaders fetch them
 * (`us.battle.net/download/getLegacy?product=WAR3|W3XP`). Each downloader is a small Windows program
 * carrying a torrent description: the installer's files (`Installer.exe`, `Installer Tome.mpq`,
 * DirectX's redistributable), a SHA-1 for every 256 KB piece of them end to end, and a "direct
 * download" address on Blizzard's CDN ("disable p2p": plain HTTP), followed by an Akamai access token
 * made for that download (`bcac=expires=…~access=<the installer's folder on the CDN>~md5=…`). The CDN serves the
 * installer piece by piece: `<direct download>/<piece number>`, the token sent as a cookie (found by
 * trying it against Blizzard's server: the pieces' SHA-1s match). [parse] reads all that; [segments]
 * says which file bytes each piece holds. Plain Kotlin, unit-tested.
 */
object BlizzardDownload {
    /** The legacy downloaders: Reign of Chaos, then The Frozen Throne (its installer needs the first). */
    enum class Product(val code: String) { ROC("WAR3"), TFT("W3XP") }

    fun downloaderUrl(product: Product, locale: String = "enUS"): String =
        "https://us.battle.net/download/getLegacy?product=${product.code}&locale=$locale&os=WIN"

    data class FileEntry(val path: String, val length: Long)

    /** An installer: its folder [name], [files] in torrent order, its CDN address, token and pieces. */
    class Package(
        val name: String,
        val directDownload: String,
        /** The cookie the CDN wants (`bcac=…`), or null if the downloader had none. */
        val token: String?,
        val launchTarget: String,
        val pieceLength: Int,
        val pieces: List<ByteArray>,
        val files: List<FileEntry>,
    ) {
        val totalLength: Long get() = files.sumOf { it.length }
    }

    /** The torrent description inside a downloader program, or null if there's none. */
    fun parse(downloader: ByteArray): Package? {
        val start = indexOf(downloader, "d8:announce".toByteArray()).takeIf { it >= 0 } ?: return null
        val root = runCatching { Bencode(downloader).decode(start).first }.getOrNull() as? Map<*, *> ?: return null
        val info = root["info"] as? Map<*, *> ?: return null
        val name = (info["name"] as? ByteArray)?.decodeToString() ?: return null
        val pieceLength = (info["piece length"] as? Long)?.toInt() ?: return null
        val piecesBytes = info["pieces"] as? ByteArray ?: return null
        val files = (info["files"] as? List<*>)?.mapNotNull { file ->
            val entry = file as? Map<*, *> ?: return@mapNotNull null
            val length = entry["length"] as? Long ?: return@mapNotNull null
            val path = (entry["path"] as? List<*>)?.joinToString("/") { (it as ByteArray).decodeToString() } ?: return@mapNotNull null
            FileEntry(path, length)
        } ?: return null
        val direct = (root["direct download"] as? ByteArray)?.decodeToString() ?: return null
        val launch = (root["launch target"] as? ByteArray)?.decodeToString() ?: "$name/Installer.exe"
        val token = TOKEN.find(String(downloader, start, downloader.size - start, Charsets.ISO_8859_1))?.value
        return Package(name, direct.trimEnd('/'), token, launch, pieceLength, piecesBytes.toList().chunked(20).map { it.toByteArray() }, files)
    }

    private val TOKEN = Regex("bcac=expires=[0-9]+~access=[!-~&&[^~]]+~md5=[0-9a-f]{32}")

    /** Where piece [index] is on the CDN. */
    fun pieceUrl(pkg: Package, index: Int): String = "${pkg.directDownload}/$index"

    /** How many bytes piece [index] holds (the last is shorter). */
    fun pieceSize(pkg: Package, index: Int): Int =
        minOf(pkg.pieceLength.toLong(), pkg.totalLength - index.toLong() * pkg.pieceLength).toInt()

    /** A run of a piece's bytes in one file: [length] bytes from [pieceOffset] go to [fileOffset] of file [file]. */
    data class Segment(val file: Int, val fileOffset: Long, val pieceOffset: Int, val length: Int)

    /** The files piece [index] fills, in order. */
    fun segments(pkg: Package, index: Int): List<Segment> {
        val start = index.toLong() * pkg.pieceLength
        val end = start + pieceSize(pkg, index)
        val out = mutableListOf<Segment>()
        var fileStart = 0L
        for ((i, file) in pkg.files.withIndex()) {
            val fileEnd = fileStart + file.length
            val from = maxOf(start, fileStart)
            val to = minOf(end, fileEnd)
            if (from < to) out += Segment(i, from - fileStart, (from - start).toInt(), (to - from).toInt())
            fileStart = fileEnd
        }
        return out
    }

    fun sha1(bytes: ByteArray, length: Int): ByteArray =
        MessageDigest.getInstance("SHA-1").apply { update(bytes, 0, length) }.digest()

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** BitTorrent's encoding: integers (Long), strings (ByteArray), lists, dictionaries (String keys). */
    private class Bencode(private val b: ByteArray) {
        fun decode(at: Int): Pair<Any, Int> = when (b[at].toInt().toChar()) {
            'i' -> {
                val end = find('e'.code.toByte(), at)
                String(b, at + 1, end - at - 1).toLong() to end + 1
            }
            'l' -> {
                var i = at + 1
                val list = mutableListOf<Any>()
                while (b[i] != 'e'.code.toByte()) {
                    val (value, next) = decode(i)
                    list += value
                    i = next
                }
                list to i + 1
            }
            'd' -> {
                var i = at + 1
                val map = linkedMapOf<String, Any>()
                while (b[i] != 'e'.code.toByte()) {
                    val (key, afterKey) = decode(i)
                    val (value, next) = decode(afterKey)
                    map[(key as ByteArray).decodeToString()] = value
                    i = next
                }
                map to i + 1
            }
            else -> {
                val colon = find(':'.code.toByte(), at)
                val length = String(b, at, colon - at).toInt()
                b.copyOfRange(colon + 1, colon + 1 + length) to colon + 1 + length
            }
        }

        private fun find(byte: Byte, from: Int): Int {
            var i = from
            while (b[i] != byte) i++
            return i
        }
    }
}
