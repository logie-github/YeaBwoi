package com.logie.wifecraft.creator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** HD creature textures: the BLP reader and writer, the enlargement and the per-texture cache with its switch. */
class HdTexturesTest {
    private fun image(w: Int, h: Int, f: (Int, Int) -> Int): Blp.Image {
        val px = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val v = f(x, y)
            px[(y * w + x) * 4] = v.toByte(); px[(y * w + x) * 4 + 1] = (v / 2).toByte(); px[(y * w + x) * 4 + 2] = (255 - v).toByte(); px[(y * w + x) * 4 + 3] = 255.toByte()
        }
        return Blp.Image(w, h, px)
    }

    private fun tmp(): File = kotlin.io.path.createTempDirectory("hdtex").toFile()

    @Test fun aBgraBlpRoundTrips() {
        val img = image(8, 4) { x, y -> x * 20 + y }
        val back = assertNotNull(Blp.decode(Blp.encode(img)))
        assertEquals(8, back.width); assertEquals(4, back.height)
        assertContentEquals(img.rgba, back.rgba)
    }

    @Test fun aDxt1BlockIsRead() {
        // 4x4 DXT1: colour 0 pure red (0xF800), colour 1 pure blue (0x001F), every texel index 0 (red)
        val b = ByteArray(148 + 8)
        "BLP2".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        b[4] = 1; b[8] = 2; b[9] = 0; b[10] = 0
        b[12] = 4; b[16] = 4
        b[20] = 148.toByte(); b[84] = 8
        b[148] = 0; b[149] = 0xF8.toByte(); b[150] = 0x1F; b[151] = 0
        val img = assertNotNull(Blp.decode(b))
        assertEquals(255, img.rgba[0].toInt() and 255); assertEquals(0, img.rgba[2].toInt() and 255); assertEquals(255, img.rgba[3].toInt() and 255)
    }

    @Test fun somethingElseIsNotABlp() {
        assertEquals(null, Blp.decode(ByteArray(300)))
        assertEquals(null, Blp.decode(ByteArray(10)))
    }

    @Test fun enlargingDoublesTheSizeAndKeepsAFlatColour() {
        val flat = image(16, 16) { _, _ -> 100 }
        val big = Upscale.enlarge(flat, 2, 0.5f)
        assertEquals(32, big.width); assertEquals(32, big.height)
        for (i in 0 until 32 * 32) {
            assertEquals(100, big.rgba[i * 4].toInt() and 255, "texel $i")
            assertEquals(255, big.rgba[i * 4 + 3].toInt() and 255)
        }
    }

    @Test fun anEdgeStaysBetweenItsTwoColoursAndSharpensNotRings() {
        val step = image(16, 16) { x, _ -> if (x < 8) 40 else 200 }
        val big = Upscale.enlarge(step, 2, 0.5f)
        val row = (0 until 32).map { big.rgba[(10 * 32 + it) * 4].toInt() and 255 }
        assertEquals(40, row[0]); assertEquals(200, row[31])
        assertTrue(row.zipWithNext().all { (a, b) -> b >= a - 25 }, "no big ringing: $row")
        // the sharpened edge is steeper than the plain one
        val plain = Upscale.enlarge(step, 2, 0f)
        val slope = { r: Blp.Image -> (14..17).sumOf { (r.rgba[(10 * 32 + it + 1) * 4].toInt() and 255) - (r.rgba[(10 * 32 + it) * 4].toInt() and 255) } }
        assertTrue(slope(big) >= slope(plain))
    }

    private fun setup(): Triple<File, File, File> {
        val root = tmp(); val game = File(root, "game").apply { mkdirs() }
        val src = File(game, "124089"); src.writeBytes(Blp.encode(image(32, 32) { x, y -> (x * 7 + y * 3) % 256 }))
        return Triple(File(root, "hdtex"), game, src)
    }

    @Test fun theSwitchOffGivesTheGamesFileBackAndMakesNothing() {
        val (dir, game, src) = setup()
        val cache = HdTextureCache(dir, game) { false }
        assertEquals(124089, cache.resolve(124089, src))
        assertEquals(mapOf(11 to 124089), cache.map(mapOf(11 to 124089)) { src })
        assertFalse(cache.has(124089))
        assertEquals(1, game.listFiles()!!.size)
    }

    @Test fun anHdTextureIsMadeOnceAndKeptPerTexture() {
        val (dir, game, src) = setup()
        var on = true
        val cache = HdTextureCache(dir, game) { on }
        val id = cache.resolve(124089, src)
        assertTrue(id >= HdTextureCache.FIRST_ID && id != 124089)
        val made = File(game, id.toString())
        assertTrue(made.isFile)
        val hd = assertNotNull(Blp.decode(made.readBytes()))
        assertEquals(64, hd.width)
        // the game's own file is untouched
        assertEquals(32, Blp.decode(src.readBytes())!!.width)
        assertTrue(cache.has(124089))
        // a second ask doesn't make it again, and a new cache over the same folders finds it
        val stamp = made.lastModified(); made.setLastModified(stamp - 5000)
        assertEquals(id, cache.resolve(124089, src))
        assertEquals(stamp - 5000, made.lastModified())
        assertEquals(id, HdTextureCache(dir, game) { true }.resolve(124089, null))
        // switched off: the game's file again, and the HD one is kept for the next time
        on = false
        assertEquals(124089, cache.resolve(124089, src))
        assertTrue(made.isFile)
    }

    @Test fun aNewVersionOfThePassMakesItAgain() {
        val (dir, game, src) = setup()
        val cache = HdTextureCache(dir, game) { true }
        val id = cache.resolve(124089, src)
        File(dir, "124089.v${HdTextureCache.VERSION}").delete()
        assertFalse(cache.has(124089))
        assertEquals(id, cache.resolve(124089, src))
        assertTrue(cache.has(124089))
    }

    @Test fun bigOrUnreadableOrMissingTexturesAreLeftAlone() {
        val (dir, game, _) = setup()
        val cache = HdTextureCache(dir, game) { true }
        val big = File(game, "5"); big.writeBytes(Blp.encode(Blp.Image(1024, 4, ByteArray(1024 * 4 * 4))))
        assertEquals(5, cache.resolve(5, big))
        val junk = File(game, "6"); junk.writeBytes(ByteArray(500))
        assertEquals(6, cache.resolve(6, junk))
        assertEquals(7, cache.resolve(7, null))
        assertEquals(2_000_000_005, cache.resolve(2_000_000_005, big))   // the app's own file ids aren't the game's
    }

    @Test fun idsAreStableDistinctAndAboveTheImports() {
        val (dir, game, _) = setup()
        val a = HdTextureCache(dir, game) { true }
        val ids = (124080..124099).map { a.idOf(it) }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it >= HdTextureCache.FIRST_ID && it < Int.MAX_VALUE })
        assertEquals(ids, (124080..124099).map { HdTextureCache(dir, game) { true }.idOf(it) })
        assertNotEquals(ids[0], ids[1])
    }
}
