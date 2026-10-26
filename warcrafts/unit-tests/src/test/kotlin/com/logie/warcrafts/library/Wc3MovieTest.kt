package com.logie.warcrafts.library

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Wc3MovieTest {
    private class Out {
        val bytes = ByteArrayOutputStream()
        fun int(v: Int) = apply { bytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()) }
        fun short(v: Int) = apply { bytes.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()) }
        fun tag(t: String) = apply { bytes.write(t.toByteArray(Charsets.ISO_8859_1)) }
        fun zeros(n: Int) = apply { bytes.write(ByteArray(n)) }
        fun chunk(t: String, body: Out.() -> Unit) = apply { val o = Out().apply(body).bytes.toByteArray(); tag(t).int(o.size); bytes.write(o) }
        fun list(type: String, body: Out.() -> Unit) = apply { val o = Out().apply(body).bytes.toByteArray(); tag("LIST").int(o.size + 4).tag(type); bytes.write(o) }
    }

    /** An AVI laid out as Blizzard's films: a video stream (BLZ0) and an MP3 stream of byte-counted, same-size chunks. */
    private fun film(video: String = "BLZ0", audioChunks: List<Int> = listOf(834, 834, 834)): File {
        val body = Out()
            .list("hdrl") {
                chunk("avih") { zeros(56) }
                list("strl") {
                    chunk("strh") { tag("vids").tag("divx").int(0).int(0).int(0).int(1).int(24).int(0).int(3).zeros(20) }
                    chunk("strf") { int(40).int(800).int(368).short(1).short(24).tag(video).int(883200).zeros(16) }
                }
                list("strl") {
                    chunk("strh") { tag("auds").int(0).int(0).int(0).int(0).int(1).int(16000).int(0).int(audioChunks.sum()).zeros(20) }
                    chunk("strf") { short(0x55).short(2).int(44100).int(16000).short(1).short(0).short(0) }
                }
            }
            .list("movi") { for (size in audioChunks) chunk("01wb") { zeros(size) } }
            .chunk("idx1") { var at = 4; for (size in audioChunks) { tag("01wb").int(0x10).int(at).int(size); at += 8 + size } }
            .bytes.toByteArray()
        val file = File.createTempFile("film", ".mpq")
        file.deleteOnExit()
        file.writeBytes(Out().tag("RIFF").int(body.size + 4).tag("AVI ").bytes.toByteArray() + body)
        return file
    }

    private fun read(file: File, patches: List<Wc3Movie.Patch>): ByteArray {
        val bytes = file.readBytes()
        // Read in small pieces, as a player does, to see changes that cross a piece's edge.
        var at = 0
        while (at < bytes.size) {
            val n = minOf(7, bytes.size - at)
            Wc3Movie.apply(bytes, at, n, at.toLong(), patches)
            at += n
        }
        return bytes
    }

    private fun int(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).int

    @Test
    fun `Blizzard's video tag is read as XVID`() {
        val file = film()
        val patches = RandomAccessFile(file, "r").use { Wc3Movie.patches(it) }
        assertNotNull(patches)
        val read = read(file, patches)
        val original = file.readBytes()
        val at = String(original, Charsets.ISO_8859_1).indexOf("BLZ0")
        assertEquals("XVID", String(read, at, 4, Charsets.ISO_8859_1))
    }

    @Test
    fun `the sound's header counts its chunks, each one chunk's bytes long in time`() {
        val file = film(audioChunks = List(5) { 834 })
        val read = read(file, RandomAccessFile(file, "r").use { Wc3Movie.patches(it) }!!)
        val text = String(read, Charsets.ISO_8859_1)
        val strh = text.indexOf("auds")
        assertEquals(834, int(read, strh + 20)) // scale: a chunk's bytes
        assertEquals(16000, int(read, strh + 24)) // rate: bytes a second
        assertEquals(5, int(read, strh + 32)) // length: the chunks
    }

    @Test
    fun `chunks of different sizes leave the sound's header as it is`() {
        val file = film(audioChunks = listOf(834, 835, 834))
        val patches = RandomAccessFile(file, "r").use { Wc3Movie.patches(it) }!!
        assertEquals(1, patches.size)
        val read = read(file, patches)
        assertContentEquals(file.readBytes().copyOfRange(0, 12), read.copyOfRange(0, 12))
    }

    @Test
    fun `a film that isn't Blizzard's isn't changed`() {
        val file = film(video = "XVID")
        assertNull(RandomAccessFile(file, "r").use { Wc3Movie.patches(it) })
        val other = File.createTempFile("other", ".mpq").apply { deleteOnExit(); writeBytes("MPQ\u001a not a film".toByteArray()) }
        assertNull(RandomAccessFile(other, "r").use { Wc3Movie.patches(it) })
    }

    @Test
    fun `the game's films are the files in its Movies folder`() {
        val game = "/data/data/com.logie.warcrafts/files/games/wc3"
        assertTrue(Wc3Movie.isFilm("$game/Movies/HumanOp.mpq", game))
        assertTrue(Wc3Movie.isFilm("$game/movies/introx.MPQ", game))
        assertFalse(Wc3Movie.isFilm("$game/War3.mpq", game))
        assertFalse(Wc3Movie.isFilm("$game/Movies/sub/x.mpq", game))
        assertFalse(Wc3Movie.isFilm("/elsewhere/Movies/HumanOp.mpq", game))
    }
}
