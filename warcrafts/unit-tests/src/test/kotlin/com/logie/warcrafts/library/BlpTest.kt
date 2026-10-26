package com.logie.warcrafts.library

import java.awt.image.Raster
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlpTest {
    /** A smooth picture with every channel changing, alpha included, and partial 8x8 blocks. */
    private fun gradient(w: Int, h: Int) = Image(w, h, IntArray(w * h) {
        val x = it % w
        val y = it / w
        val a = 255 - x * 200 / maxOf(1, w - 1)
        val r = x * 255 / maxOf(1, w - 1)
        val g = y * 255 / maxOf(1, h - 1)
        val b = (x + y) * 255 / maxOf(1, w + h - 2)
        (a shl 24) or (r shl 16) or (g shl 8) or b
    })

    /** A BLP's header fields and its mipmaps' offsets and sizes, checked against each other. */
    private class Parsed(val blp: ByteArray) {
        private val le = ByteBuffer.wrap(blp).order(ByteOrder.LITTLE_ENDIAN)
        val fields = IntArray(6) { le.getInt(4 + 4 * it) }
        val offsets = IntArray(16) { le.getInt(28 + 4 * it) }
        val sizes = IntArray(16) { le.getInt(92 + 4 * it) }
        val header = blp.copyOfRange(160, 160 + le.getInt(156))
        val count = sizes.count { it > 0 }
        fun mipmap(i: Int) = blp.copyOfRange(offsets[i], offsets[i] + sizes[i])
        fun jpeg(i: Int) = header + mipmap(i)
    }

    /**
     * A JPEG's 4 components as they are stored: no colour conversion, and not inverted the way
     * Pillow and others read a 4-component JPEG (as Adobe's CMYK).
     */
    private fun raster(jpeg: ByteArray): Raster {
        val reader = ImageIO.getImageReadersByFormatName("jpeg").next()
        ImageIO.createImageInputStream(ByteArrayInputStream(jpeg)).use { input ->
            reader.input = input
            try {
                return reader.readRaster(0, null)
            } finally {
                reader.dispose()
            }
        }
    }

    /** The JPEG's markers and each segment's bytes after its marker (the scan's data not included). */
    private fun segments(jpeg: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var i = 0
        while (i < jpeg.size) {
            assertEquals(0xFF, jpeg[i].toInt() and 0xFF, "a marker at $i")
            val m = jpeg[i + 1].toInt() and 0xFF
            if (m == 0xD8 || m == 0xD9) {
                out.add(m to ByteArray(0))
                i += 2
                continue
            }
            val n = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            out.add(m to jpeg.copyOfRange(i + 4, i + 2 + n))
            i += 2 + n
            if (m == 0xDA) {
                // The scan: to the EOI, the only marker in it (a 0xFF there is followed by 0).
                while (!(jpeg[i].toInt() and 0xFF == 0xFF && jpeg[i + 1].toInt() != 0)) i++
            }
        }
        return out
    }

    /** The mipmaps as the brief has them: each pixel's channels the rounded average of 2 by 2. */
    private fun halve(image: Image): Image {
        val w = maxOf(1, image.width / 2)
        val h = maxOf(1, image.height / 2)
        return Image(w, h, IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var out = 0
            for (shift in 0..24 step 8) {
                var sum = 0
                for ((dx, dy) in listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1)) {
                    val sx = minOf(2 * x + dx, image.width - 1)
                    val sy = minOf(2 * y + dy, image.height - 1)
                    sum += (image.argb[sy * image.width + sx] ushr shift) and 0xFF
                }
                out = out or (((sum + 2) / 4) shl shift)
            }
            out
        })
    }

    @Test
    fun theHeaderOffsetsAndSizes() {
        for ((w, h, levels) in listOf(Triple(37, 20, 6), Triple(1, 8, 4), Triple(64, 64, 7), Triple(1, 1, 1))) {
            val blp = Parsed(Blp.write(gradient(w, h)))
            assertEquals("BLP1", String(blp.blp, 0, 4, Charsets.ISO_8859_1))
            assertContentEquals(intArrayOf(0, 8, w, h, 4, 1), blp.fields)
            assertEquals(levels, blp.count, "${w}x$h")
            assertEquals(160 + blp.header.size, blp.offsets[0])
            for (i in 1 until blp.count) assertEquals(blp.offsets[i - 1] + blp.sizes[i - 1], blp.offsets[i])
            for (i in blp.count until 16) {
                assertEquals(0, blp.offsets[i])
                assertEquals(0, blp.sizes[i])
            }
            assertEquals(blp.blp.size, blp.offsets[blp.count - 1] + blp.sizes[blp.count - 1])

            // The shared header: SOI and the tables, nothing else (no JFIF, no Adobe marker).
            assertEquals(listOf(0xD8, 0xDB, 0xC4), segments(blp.header).map { it.first })
            var mw = w
            var mh = h
            for (i in 0 until blp.count) {
                // Each mipmap: its frame, its scan, the end.
                val parts = segments(blp.mipmap(i))
                assertEquals(listOf(0xC0, 0xDA, 0xD9), parts.map { it.first })
                val sof = parts[0].second.map { it.toInt() and 0xFF }
                assertEquals(listOf(8, mh shr 8, mh and 0xFF, mw shr 8, mw and 0xFF, 4, 1, 0x11, 0, 2, 0x11, 0, 3, 0x11, 0, 4, 0x11, 0), sof)
                val sos = parts[1].second.map { it.toInt() and 0xFF }
                assertEquals(listOf(4, 1, 0, 2, 0, 3, 0, 4, 0, 0, 63, 0), sos)
                mw = maxOf(1, mw / 2)
                mh = maxOf(1, mh / 2)
            }
        }
    }

    @Test
    fun theTablesAreAnnexKs() {
        val header = segments(Parsed(Blp.write(gradient(8, 8), quality = 50)).header)
        // Quality 50: the standard luminance table itself, in zigzag order.
        val dqt = header[1].second.map { it.toInt() and 0xFF }
        assertEquals(listOf(0, 16, 11, 12, 14, 12, 10, 16, 14, 13, 14, 18, 17, 16, 19, 24, 40), dqt.take(17))
        assertEquals(99, dqt.last())
        val dht = header[2].second.map { it.toInt() and 0xFF }
        assertEquals(listOf(0x00, 0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0) + (0..11), dht.take(29))
        assertEquals(listOf(0x10, 0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D, 0x01, 0x02, 0x03, 0x00), dht.drop(29).take(21))
        assertEquals(29 + 17 + 162, dht.size)
        // Quality 90: scaled by a fifth, rounded (16 to 3, 11 to 2).
        val q90 = segments(Parsed(Blp.write(gradient(8, 8))).header)[1].second
        assertEquals(listOf(3, 2, 2, 3), q90.drop(1).take(4).map { it.toInt() })
    }

    @Test
    fun everyMipmapIsAJpegOfItsBlueGreenRedAndAlpha() {
        for ((w, h) in listOf(37 to 20, 64 to 64, 1 to 8)) {
            val image = gradient(w, h)
            val blp = Parsed(Blp.write(image))
            var expected = image
            for (i in 0 until blp.count) {
                val raster = raster(blp.jpeg(i))
                assertEquals(4, raster.numBands)
                assertEquals(expected.width, raster.width)
                assertEquals(expected.height, raster.height)
                var worst = 0
                var total = 0L
                for (y in 0 until raster.height) for (x in 0 until raster.width) {
                    val p = expected.argb[y * expected.width + x]
                    for ((band, shift) in listOf(0 to 0, 1 to 8, 2 to 16, 3 to 24)) {
                        val d = abs(raster.getSample(x, y, band) - ((p ushr shift) and 0xFF))
                        worst = maxOf(worst, d)
                        total += d
                    }
                }
                val mean = total.toDouble() / (4 * raster.width * raster.height)
                assertTrue(worst <= 8 && mean <= 1.5, "${w}x$h mipmap $i: worst $worst, mean $mean")
                expected = halve(expected)
            }
        }
    }

    @Test
    fun plainColoursComeBackExactly() {
        // A flat block is its DC alone; quality 100 keeps it exact, any channel, any value. Stored
        // as they are: Blizzard's opaque textures (BTNFootman.blp) have 255 for every alpha.
        for (colour in listOf(0x00000000, 0xFFFFFFFF.toInt(), 0x80FF0040.toInt(), 0x12345678)) {
            val blp = Parsed(Blp.write(Image(10, 3, IntArray(30) { colour }), quality = 100))
            val raster = raster(blp.jpeg(0))
            for ((band, shift) in listOf(0 to 0, 1 to 8, 2 to 16, 3 to 24)) {
                assertEquals((colour ushr shift) and 0xFF, raster.getSample(9, 2, band), "colour ${colour.toUInt().toString(16)} band $band")
            }
        }
    }

    // DDS files, hand-built.

    private fun dds(w: Int, h: Int, fourCC: String?, data: ByteArray, bits: Int = 0, masks: IntArray = IntArray(4), flags: Int = 0): ByteArray {
        val b = ByteBuffer.allocate(128 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("DDS ".toByteArray()).putInt(124).putInt(0x1007).putInt(h).putInt(w).putInt(data.size).putInt(0).putInt(1)
        b.position(76)
        b.putInt(32)
        if (fourCC != null) b.putInt(0x4).put(fourCC.toByteArray()).putInt(0) else b.putInt(flags).putInt(0).putInt(bits)
        for (m in masks) b.putInt(m)
        b.putInt(0x1000)
        b.position(128)
        b.put(data)
        return b.array()
    }

    /** A DXT1 (or DXT3 or DXT5 colour) block: two 5:6:5 colours, then the 16 pixels' 2-bit indices. */
    private fun colourBlock(c0: Int, c1: Int, indices: IntArray): ByteArray {
        var bits = 0
        for (i in 0 until 16) bits = bits or (indices[i] shl (2 * i))
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(c0.toShort()).putShort(c1.toShort()).putInt(bits).array()
    }

    /** A DXT5 alpha block: the two alphas, then the 16 pixels' 3-bit indices. */
    private fun alphaBlock(a0: Int, a1: Int, indices: IntArray): ByteArray {
        var bits = 0L
        for (i in 0 until 16) bits = bits or (indices[i].toLong() shl (3 * i))
        return byteArrayOf(a0.toByte(), a1.toByte()) + ByteArray(6) { (bits ushr (8 * it)).toByte() }
    }

    private val ramp = IntArray(16) { it % 4 }

    @Test
    fun dxt1FourColours() {
        // Red first (the larger), blue second: 4 colours, the two between at a third and two thirds.
        val image = Dds.decode(dds(4, 4, "DXT1", colourBlock(0xF800, 0x001F, ramp)))
        assertEquals(4, image.width)
        assertContentEquals(intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFFAA0055.toInt(), 0xFF5500AA.toInt()), image.argb.copyOfRange(0, 4))
        assertContentEquals(image.argb.copyOfRange(0, 4), image.argb.copyOfRange(12, 16))
    }

    @Test
    fun dxt1ThreeColoursAndTransparent() {
        // Black first, not the larger: black, (132, 130, 132), their middle, and transparent.
        val image = Dds.decode(dds(4, 4, "DXT1", colourBlock(0x0000, 0x8410, ramp)))
        assertContentEquals(intArrayOf(0xFF000000.toInt(), 0xFF848284.toInt(), 0xFF424142.toInt(), 0x00000000), image.argb.copyOfRange(0, 4))
        // The same two colours the same way round, equal: still 3 colours.
        val same = Dds.decode(dds(4, 4, "DXT1", colourBlock(0xFFFF, 0xFFFF, ramp)))
        assertEquals(0, same.argb[3])
        assertEquals(0xFFFFFFFF.toInt(), same.argb[2])
    }

    @Test
    fun dxt5Alphas() {
        val indices = IntArray(16) { it % 8 }
        // 8 alphas: 70 and 0, six between.
        val eight = Dds.decode(dds(4, 4, "DXT5", alphaBlock(70, 0, indices) + colourBlock(0xFFFF, 0xFFFF, IntArray(16))))
        assertContentEquals(intArrayOf(70, 0, 60, 50, 40, 30, 20, 10), IntArray(8) { eight.argb[it] ushr 24 })
        assertEquals(0xFFFFFF, eight.argb[0] and 0xFFFFFF)
        // 6 alphas: 0 and 50, four between, then 0 and 255.
        val six = Dds.decode(dds(4, 4, "DXT5", alphaBlock(0, 50, indices) + colourBlock(0x0000, 0xFFFF, ramp)))
        assertContentEquals(intArrayOf(0, 50, 10, 20, 30, 40, 0, 255), IntArray(8) { six.argb[it] ushr 24 })
        // DXT5's colours are always 4, whichever is larger: black, white, a third and two thirds.
        assertContentEquals(intArrayOf(0x000000, 0xFFFFFF, 0x555555, 0xAAAAAA), IntArray(4) { six.argb[it] and 0xFFFFFF })
    }

    @Test
    fun dxt3Alphas() {
        val alpha = ByteArray(8) { (((2 * it + 1) shl 4) or (2 * it)).toByte() }   // pixels 0 to 15: alpha 0, 1, ... 15
        val image = Dds.decode(dds(4, 4, "DXT3", alpha + colourBlock(0x07E0, 0x0000, IntArray(16))))
        assertContentEquals(IntArray(16) { it * 17 }, IntArray(16) { image.argb[it] ushr 24 })
        assertEquals(0x00FF00, image.argb[5] and 0xFFFFFF)
    }

    @Test
    fun partialBlocksAreCut() {
        // 5 by 3: two blocks across, one down; the second block's first column only.
        val left = colourBlock(0xF800, 0x001F, IntArray(16) { 0 })
        val right = colourBlock(0xF800, 0x001F, IntArray(16) { 1 })
        val image = Dds.decode(dds(5, 3, "DXT1", left + right))
        assertEquals(15, image.argb.size)
        for (y in 0 until 3) {
            for (x in 0 until 4) assertEquals(0xFFFF0000.toInt(), image.argb[y * 5 + x])
            assertEquals(0xFF0000FF.toInt(), image.argb[y * 5 + 4])
        }
    }

    @Test
    fun uncompressedColours() {
        val pixel = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val bgra = Dds.decode(dds(1, 1, null, pixel, 32, intArrayOf(0xFF0000, 0xFF00, 0xFF, 0xFF000000.toInt()), flags = 0x41))
        assertEquals(0x40302010, bgra.argb[0])
        val rgba = Dds.decode(dds(1, 1, null, pixel, 32, intArrayOf(0xFF, 0xFF00, 0xFF0000, 0xFF000000.toInt()), flags = 0x41))
        assertEquals(0x40102030, rgba.argb[0])
        // No alpha pixels: opaque, whatever the fourth byte.
        val bgrx = Dds.decode(dds(1, 1, null, pixel, 32, intArrayOf(0xFF0000, 0xFF00, 0xFF, 0), flags = 0x40))
        assertEquals(0xFF302010.toInt(), bgrx.argb[0])
    }

    @Test
    fun otherFormatsAreRefused() {
        assertFailsWith<IllegalArgumentException> { Dds.decode(dds(4, 4, "ATI2", ByteArray(16))) }
        assertFailsWith<IllegalArgumentException> { Dds.decode(dds(4, 4, "DXT2", ByteArray(16))) }
        assertFailsWith<IllegalArgumentException> { Dds.decode(dds(1, 1, null, ByteArray(3), 24, intArrayOf(0xFF0000, 0xFF00, 0xFF, 0), flags = 0x40)) }
        assertFailsWith<IllegalArgumentException> { Dds.decode(dds(8, 8, "DXT5", ByteArray(16))) }   // 4 blocks, 1 there
        assertFailsWith<IllegalArgumentException> { Dds.decode(ByteArray(200)) }
        assertFailsWith<IllegalArgumentException> { Blp.fromDds(ByteArray(10)) }
    }

    @Test
    fun aDdsBecomesABlpOfItsPicture() {
        // 8 by 8 of DXT5 blocks, each its own colours and alphas.
        val blocks = (0 until 4).map { k ->
            alphaBlock(255 - 40 * k, 30 * k, IntArray(16) { (it + k) % 8 }) +
                colourBlock(0xF800 - 0x0800 * k, 0x001F + 0x0040 * k, IntArray(16) { (it / 4 + k) % 4 })
        }
        val dds = dds(8, 8, "DXT5", blocks.reduce { a, b -> a + b })
        val image = Dds.decode(dds)
        val blp = Parsed(Blp.fromDds(dds))
        assertContentEquals(intArrayOf(0, 8, 8, 8, 4, 1), blp.fields)
        assertEquals(4, blp.count)
        val raster = raster(blp.jpeg(0))
        var total = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val p = image.argb[y * 8 + x]
            for ((band, shift) in listOf(0 to 0, 1 to 8, 2 to 16, 3 to 24)) {
                total += abs(raster.getSample(x, y, band) - ((p ushr shift) and 0xFF))
            }
        }
        // Hard edges between the blocks: more JPEG error than a gradient's, still small.
        assertTrue(total / 256.0 <= 6, "mean ${total / 256.0}")
    }
}
