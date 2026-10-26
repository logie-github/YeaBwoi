// SPDX-License-Identifier: GPL-2.0-or-later

package com.logie.warcrafts.library

/**
 * A picture: [width] by [height] pixels, row by row from the top, each an ARGB colour (alpha in
 * the top byte, then red, green and blue).
 */
class Image(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "a ${width}x$height picture with ${argb.size} pixels" }
    }
}

/**
 * Reads a DDS texture's picture (its first mipmap), as the "Classic+" graphics mod has them:
 * DXT1 (BC1, with its 1-bit alpha) and DXT5 (BC3), and also DXT3 (BC2) and uncompressed 32-bit
 * colours (BGRA, RGBA or any other order its masks give), with or without the DX10 header.
 * Anything else, or a file cut short, is an [IllegalArgumentException].
 */
object Dds {
    private const val MAGIC = 0x20534444            // "DDS "
    private const val HEADER = 128
    private const val DX10_HEADER = 20
    private const val ALPHA_PIXELS = 0x1
    private const val FOURCC = 0x4
    private const val RGB = 0x40
    /** The largest side read: Direct3D's own limit, and it keeps width times height in range. */
    private const val MAX_SIDE = 16384

    private enum class Format { DXT1, DXT3, DXT5 }

    fun decode(bytes: ByteArray): Image {
        require(bytes.size >= HEADER && int(bytes, 0) == MAGIC) { "not a DDS file" }
        val height = int(bytes, 12)
        val width = int(bytes, 16)
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "a DDS of ${width}x$height" }
        val flags = int(bytes, 80)
        val fourCC = String(bytes, 84, 4, Charsets.ISO_8859_1)
        val argb = when {
            flags and FOURCC != 0 && fourCC == "DX10" -> {
                require(bytes.size >= HEADER + DX10_HEADER) { "a DDS cut short" }
                val start = HEADER + DX10_HEADER
                when (val dxgi = int(bytes, HEADER)) {
                    71, 72 -> blocks(bytes, start, width, height, Format.DXT1)
                    74, 75 -> blocks(bytes, start, width, height, Format.DXT3)
                    77, 78 -> blocks(bytes, start, width, height, Format.DXT5)
                    28, 29 -> pixels(bytes, start, width, height, 0xFF, 0xFF00, 0xFF0000, 0xFF000000.toInt())
                    87, 91 -> pixels(bytes, start, width, height, 0xFF0000, 0xFF00, 0xFF, 0xFF000000.toInt())
                    88, 93 -> pixels(bytes, start, width, height, 0xFF0000, 0xFF00, 0xFF, 0)
                    else -> throw IllegalArgumentException("a DDS in DXGI format $dxgi")
                }
            }
            flags and FOURCC != 0 -> when (fourCC) {
                "DXT1" -> blocks(bytes, HEADER, width, height, Format.DXT1)
                "DXT3" -> blocks(bytes, HEADER, width, height, Format.DXT3)
                "DXT5" -> blocks(bytes, HEADER, width, height, Format.DXT5)
                else -> throw IllegalArgumentException("a DDS in ${fourCC.trimEnd('\u0000')}")
            }
            flags and RGB != 0 && int(bytes, 88) == 32 -> pixels(
                bytes, HEADER, width, height, int(bytes, 92), int(bytes, 96), int(bytes, 100),
                if (flags and ALPHA_PIXELS != 0) int(bytes, 104) else 0,
            )
            else -> throw IllegalArgumentException("a DDS of ${int(bytes, 88)}-bit colours, flags ${flags.toString(16)}")
        }
        return Image(width, height, argb)
    }

    /**
     * Block-compressed colours, 4 by 4 pixels a block, row by row: DXT1's 8 bytes a block (two
     * 5:6:5 colours and a 2-bit index a pixel); DXT3's and DXT5's 16, their alpha (4 bits a pixel,
     * or two alphas and a 3-bit index a pixel) before such a colour block. DXT1 has 3 colours and
     * transparent black when its first colour isn't the larger; DXT3 and DXT5 always have 4.
     */
    private fun blocks(bytes: ByteArray, start: Int, width: Int, height: Int, format: Format): IntArray {
        val across = (width + 3) / 4
        val down = (height + 3) / 4
        val size = if (format == Format.DXT1) 8 else 16
        require(bytes.size.toLong() - start >= across.toLong() * down * size) { "a DDS cut short" }
        val out = IntArray(width * height)
        val colours = IntArray(4)
        val alphas = IntArray(16)
        val palette = IntArray(8)
        var at = start
        for (by in 0 until down) {
            for (bx in 0 until across) {
                when (format) {
                    Format.DXT1 -> alphas.fill(255)
                    Format.DXT3 -> for (i in 0 until 16) {
                        alphas[i] = ((bytes[at + i / 2].toInt() ushr (i % 2 * 4)) and 0xF) * 17
                    }
                    Format.DXT5 -> {
                        val a0 = bytes[at].toInt() and 0xFF
                        val a1 = bytes[at + 1].toInt() and 0xFF
                        palette[0] = a0
                        palette[1] = a1
                        if (a0 > a1) {
                            for (i in 2..7) palette[i] = ((8 - i) * a0 + (i - 1) * a1 + 3) / 7
                        } else {
                            for (i in 2..5) palette[i] = ((6 - i) * a0 + (i - 1) * a1 + 2) / 5
                            palette[6] = 0
                            palette[7] = 255
                        }
                        var indices = 0L
                        for (i in 0 until 6) indices = indices or ((bytes[at + 2 + i].toLong() and 0xFF) shl (8 * i))
                        for (i in 0 until 16) alphas[i] = palette[((indices ushr (3 * i)) and 7).toInt()]
                    }
                }
                val c = at + size - 8
                val c0 = short(bytes, c)
                val c1 = short(bytes, c + 2)
                colours[0] = rgb565(c0)
                colours[1] = rgb565(c1)
                val threeColours = format == Format.DXT1 && c0 <= c1
                if (threeColours) {
                    colours[2] = mix(colours[0], colours[1], 1, 1)
                    colours[3] = 0
                } else {
                    colours[2] = mix(colours[0], colours[1], 2, 1)
                    colours[3] = mix(colours[0], colours[1], 1, 2)
                }
                val indices = int(bytes, c + 4)
                for (i in 0 until 16) {
                    val x = bx * 4 + (i and 3)
                    val y = by * 4 + (i shr 2)
                    if (x >= width || y >= height) continue
                    val index = (indices ushr (2 * i)) and 3
                    out[y * width + x] = if (threeColours && index == 3) 0 else (alphas[i] shl 24) or colours[index]
                }
                at += size
            }
        }
        return out
    }

    /** A 5:6:5 colour as 8-bit red, green and blue, each widened by repeating its top bits. */
    private fun rgb565(c: Int): Int {
        val r = (c ushr 11) and 0x1F
        val g = (c ushr 5) and 0x3F
        val b = c and 0x1F
        return (((r shl 3) or (r ushr 2)) shl 16) or (((g shl 2) or (g ushr 4)) shl 8) or ((b shl 3) or (b ushr 2))
    }

    /** [wa] parts of colour [a] and [wb] of [b], rounded, channel by channel. */
    private fun mix(a: Int, b: Int, wa: Int, wb: Int): Int {
        val sum = wa + wb
        var out = 0
        for (shift in 0..16 step 8) {
            val v = (wa * ((a ushr shift) and 0xFF) + wb * ((b ushr shift) and 0xFF) + sum / 2) / sum
            out = out or (v shl shift)
        }
        return out
    }

    /**
     * Uncompressed 32-bit pixels, row by row with no padding, each channel where its mask says
     * (a mask of 0: no alpha, opaque).
     */
    private fun pixels(bytes: ByteArray, start: Int, width: Int, height: Int, r: Int, g: Int, b: Int, a: Int): IntArray {
        require(r != 0 || g != 0 || b != 0) { "a DDS with no colour masks" }
        require(bytes.size.toLong() - start >= width.toLong() * height * 4) { "a DDS cut short" }
        val red = Channel(r)
        val green = Channel(g)
        val blue = Channel(b)
        val alpha = Channel(a)
        val out = IntArray(width * height)
        for (i in out.indices) {
            val p = int(bytes, start + 4 * i)
            out[i] = (alpha.of(p, 255) shl 24) or (red.of(p, 0) shl 16) or (green.of(p, 0) shl 8) or blue.of(p, 0)
        }
        return out
    }

    /** A channel's bits in a pixel, as 0 to 255. */
    private class Channel(mask: Int) {
        private val shift = Integer.numberOfTrailingZeros(mask)
        private val max = if (mask == 0) 0L else (mask.toLong() and 0xFFFFFFFFL) ushr shift

        init {
            require(max and (max + 1) == 0L) { "a DDS colour mask of ${mask.toString(16)}" }
        }

        fun of(pixel: Int, missing: Int): Int =
            if (max == 0L) missing else ((((pixel.toLong() and 0xFFFFFFFFL) ushr shift and max) * 255 + max / 2) / max).toInt()
    }

    private fun short(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun int(b: ByteArray, at: Int) = short(b, at) or (short(b, at + 2) shl 16)
}

/**
 * Writes a picture as a BLP1 texture the way Warcraft III 1.27's own are made, for the game to
 * read the "Classic+" mod's DDS textures (which it can't):
 *
 * - **The header** (little-endian): `BLP1`; compression 0 (JPEG), 8 alpha bits, the width and
 *   height, picture type 4 and subtype 1; the 16 mipmaps' offsets and sizes (0 for those not
 *   there); the size of the JPEG header the mipmaps share, and that header: the start of image
 *   and the quantization and Huffman tables. Then each mipmap's frame, scan and end of image. The
 *   game puts the two together into one JPEG. (Blizzard's own also have a JFIF marker and a comment
 *   in that header, end it with the frame's marker, and code the last three components with
 *   JPEG's chrominance Huffman tables: the same JPEG once put together.)
 * - **The mipmaps:** the picture, then each a half of the one before it (the average of 2 by 2
 *   pixels; a side of 1 stays 1), down to 1 by 1 or 16 of them.
 * - **Each a baseline JPEG** of 4 components (ids 1 to 4, none subsampled, no Adobe or JFIF
 *   marker, so no colour transform): blue, green, red and alpha, as Blizzard's are. Decoders that
 *   take a 4-component JPEG for Adobe's inverted CMYK (Pillow, most viewers) show each of them
 *   inverted (255 minus it); stored inverted, the game draws them white and team-coloured.
 * - **Its tables** are JPEG's standard (Annex K) luminance ones: the quantization table scaled
 *   for [write]'s quality the way libjpeg does, and the DC and AC Huffman tables, for every
 *   component. Blocks at the right and bottom edges are filled out by repeating the edge.
 */
object Blp {
    private const val MIPMAPS = 16
    /** The BLP header before the JPEG header: magic, six fields, the offsets and sizes, its size. */
    private const val HEADER = 4 + 6 * 4 + MIPMAPS * 4 * 2 + 4

    /** The DDS texture [dds] as a BLP1. */
    fun fromDds(dds: ByteArray): ByteArray = write(Dds.decode(dds))

    /** [image] as a BLP1, its JPEGs at [quality] (1 to 100, as libjpeg's). */
    fun write(image: Image, quality: Int = 90): ByteArray {
        require(quality in 1..100) { "a JPEG quality of $quality" }
        require(image.width <= 65535 && image.height <= 65535) { "a ${image.width}x${image.height} picture, too big for a JPEG" }
        val table = quantization(quality)
        val divisors = FloatArray(64)
        for (i in 0 until 64) divisors[i] = 1f / (table[i] * AAN[i / 8] * AAN[i % 8] * 8f).toFloat()
        val header = Bytes(1024)
        header.marker(0xD8)
        header.marker(0xDB)
        header.short(2 + 1 + 64)
        header.byte(0)
        for (k in 0 until 64) header.byte(table[ZIGZAG[k]])
        header.marker(0xC4)
        header.short(2 + 1 + 16 + DC_VALUES.size + 1 + 16 + AC_VALUES.size)
        header.byte(0x00)
        for (n in DC_BITS) header.byte(n)
        for (v in DC_VALUES) header.byte(v)
        header.byte(0x10)
        for (n in AC_BITS) header.byte(n)
        for (v in AC_VALUES) header.byte(v)

        val mipmaps = ArrayList<Bytes>()
        var level = image
        while (true) {
            mipmaps.add(jpeg(level, divisors))
            if ((level.width == 1 && level.height == 1) || mipmaps.size == MIPMAPS) break
            level = half(level)
        }

        val out = Bytes(HEADER + header.size + mipmaps.sumOf { it.size })
        out.bytes("BLP1".toByteArray(Charsets.ISO_8859_1), 4)
        for (v in intArrayOf(0, 8, image.width, image.height, 4, 1)) out.intLe(v)
        var offset = HEADER + header.size
        for (i in 0 until MIPMAPS) {
            out.intLe(if (i < mipmaps.size) offset else 0)
            if (i < mipmaps.size) offset += mipmaps[i].size
        }
        for (i in 0 until MIPMAPS) out.intLe(if (i < mipmaps.size) mipmaps[i].size else 0)
        out.intLe(header.size)
        out.bytes(header.data, header.size)
        for (m in mipmaps) out.bytes(m.data, m.size)
        return out.data
    }

    /** The next mipmap: each pixel the average of 2 by 2 of [image]'s (a side of 1 stays 1). */
    private fun half(image: Image): Image {
        val w = image.width
        val h = image.height
        val width = maxOf(1, w / 2)
        val height = maxOf(1, h / 2)
        val src = image.argb
        val out = IntArray(width * height)
        val m = 0x00FF00FF
        for (y in 0 until height) {
            val row0 = minOf(2 * y, h - 1) * w
            val row1 = minOf(2 * y + 1, h - 1) * w
            for (x in 0 until width) {
                val x0 = minOf(2 * x, w - 1)
                val x1 = minOf(2 * x + 1, w - 1)
                val a = src[row0 + x0]
                val b = src[row0 + x1]
                val c = src[row1 + x0]
                val d = src[row1 + x1]
                // Red and blue, then alpha and green, two channels at a time, each with room to add.
                val rb = (((a and m) + (b and m) + (c and m) + (d and m) + 0x00020002) ushr 2) and m
                val ag = ((((a ushr 8) and m) + ((b ushr 8) and m) + ((c ushr 8) and m) + ((d ushr 8) and m) + 0x00020002) ushr 2) and m
                out[y * width + x] = rb or (ag shl 8)
            }
        }
        return Image(width, height, out)
    }

    /** One mipmap's part of its JPEG: the frame, the scan of its 4 components, the end of image. */
    private fun jpeg(image: Image, divisors: FloatArray): Bytes {
        val w = image.width
        val h = image.height
        val out = Bytes(maxOf(1024, w * h))
        out.marker(0xC0)
        out.short(8 + 3 * 4)
        out.byte(8)
        out.short(h)
        out.short(w)
        out.byte(4)
        for (id in 1..4) {
            out.byte(id)
            out.byte(0x11)
            out.byte(0)
        }
        out.marker(0xDA)
        out.short(6 + 2 * 4)
        out.byte(4)
        for (id in 1..4) {
            out.byte(id)
            out.byte(0x00)
        }
        out.byte(0)
        out.byte(63)
        out.byte(0)

        val px = image.argb
        val blocks = FloatArray(4 * 64)
        val coefficients = IntArray(64)
        val dc = IntArray(4)
        for (by in 0 until (h + 7) / 8) {
            for (bx in 0 until (w + 7) / 8) {
                for (y in 0 until 8) {
                    val row = minOf(by * 8 + y, h - 1) * w
                    for (x in 0 until 8) {
                        val p = px[row + minOf(bx * 8 + x, w - 1)]
                        val i = y * 8 + x
                        // Blue, green, red, alpha, less JPEG's 128.
                        blocks[i] = ((p and 0xFF) - 128).toFloat()
                        blocks[64 + i] = (((p ushr 8) and 0xFF) - 128).toFloat()
                        blocks[128 + i] = (((p ushr 16) and 0xFF) - 128).toFloat()
                        blocks[192 + i] = ((p ushr 24) - 128).toFloat()
                    }
                }
                for (c in 0 until 4) {
                    val at = c * 64
                    dct(blocks, at)
                    for (k in 0 until 64) {
                        val n = ZIGZAG[k]
                        // Rounded to the nearest as libjpeg does (the offset makes the conversion's
                        // truncation a floor), then within what the Huffman tables have codes for.
                        val q = (blocks[at + n] * divisors[n] + 16384.5f).toInt() - 16384
                        coefficients[k] = if (q > 1023) 1023 else if (q < -1023) -1023 else q
                    }
                    encode(out, coefficients, coefficients[0] - dc[c])
                    dc[c] = coefficients[0]
                }
            }
        }
        out.flushBits()
        out.marker(0xD9)
        return out
    }

    /**
     * A block's Huffman codes, each with its value's bits: its DC as [dcDiff] from the last one, then
     * its ACs run-length coded.
     */
    private fun encode(out: Bytes, coefficients: IntArray, dcDiff: Int) {
        val dcBits = bitLength(dcDiff)
        out.bits((DC.code[dcBits] shl dcBits) or magnitude(dcDiff, dcBits), DC.length[dcBits] + dcBits)
        var run = 0
        for (k in 1 until 64) {
            val v = coefficients[k]
            if (v == 0) {
                run++
                continue
            }
            while (run > 15) {
                out.bits(AC.code[0xF0], AC.length[0xF0])
                run -= 16
            }
            val n = bitLength(v)
            val symbol = (run shl 4) or n
            out.bits((AC.code[symbol] shl n) or magnitude(v, n), AC.length[symbol] + n)
            run = 0
        }
        if (run > 0) out.bits(AC.code[0], AC.length[0])
    }

    private fun bitLength(v: Int) = 32 - Integer.numberOfLeadingZeros(if (v < 0) -v else v)

    /** JPEG's [n] bits for [v]: itself if positive, else one less than it in [n] bits. */
    private fun magnitude(v: Int, n: Int) = (if (v < 0) v - 1 else v) and ((1 shl n) - 1)

    /**
     * The forward DCT of the 8 by 8 block at [at] in place (Arai, Agui and Nakajima's, as libjpeg's
     * jfdctflt.c): each coefficient comes out multiplied by 8 and its row's and column's [AAN]
     * factors, which the quantization divisors take back out.
     */
    private fun dct(d: FloatArray, at: Int) {
        for (pass in 0..1) {
            val step = if (pass == 0) 1 else 8
            val next = if (pass == 0) 8 else 1
            for (line in 0 until 8) {
                val o = at + line * next
                val tmp0 = d[o] + d[o + 7 * step]
                val tmp7 = d[o] - d[o + 7 * step]
                val tmp1 = d[o + step] + d[o + 6 * step]
                val tmp6 = d[o + step] - d[o + 6 * step]
                val tmp2 = d[o + 2 * step] + d[o + 5 * step]
                val tmp5 = d[o + 2 * step] - d[o + 5 * step]
                val tmp3 = d[o + 3 * step] + d[o + 4 * step]
                val tmp4 = d[o + 3 * step] - d[o + 4 * step]

                val tmp10 = tmp0 + tmp3
                val tmp13 = tmp0 - tmp3
                val tmp11 = tmp1 + tmp2
                val tmp12 = tmp1 - tmp2
                d[o] = tmp10 + tmp11
                d[o + 4 * step] = tmp10 - tmp11
                val z1 = (tmp12 + tmp13) * 0.707106781f
                d[o + 2 * step] = tmp13 + z1
                d[o + 6 * step] = tmp13 - z1

                val odd10 = tmp4 + tmp5
                val odd11 = tmp5 + tmp6
                val odd12 = tmp6 + tmp7
                val z5 = (odd10 - odd12) * 0.382683433f
                val z2 = 0.541196100f * odd10 + z5
                val z4 = 1.306562965f * odd12 + z5
                val z3 = odd11 * 0.707106781f
                val z11 = tmp7 + z3
                val z13 = tmp7 - z3
                d[o + 5 * step] = z13 + z2
                d[o + 3 * step] = z13 - z2
                d[o + step] = z11 + z4
                d[o + 7 * step] = z11 - z4
            }
        }
    }

    /** The standard luminance table (natural order) scaled for [quality] as libjpeg does, 1 to 255. */
    private fun quantization(quality: Int): IntArray {
        val scale = if (quality < 50) 5000 / quality else 200 - quality * 2
        return IntArray(64) { ((LUMINANCE[it] * scale + 50) / 100).coerceIn(1, 255) }
    }

    /** A Huffman table's code and its length for each symbol, from its count of codes of each length. */
    private class Huffman(bits: IntArray, values: IntArray) {
        val code = IntArray(256)
        val length = IntArray(256)

        init {
            var c = 0
            var k = 0
            for (len in 1..16) {
                repeat(bits[len - 1]) {
                    code[values[k]] = c
                    length[values[k]] = len
                    k++
                    c++
                }
                c = c shl 1
            }
        }
    }

    /** A growing byte buffer, and a scan's bits into it (a 0xFF byte followed by 0). */
    private class Bytes(capacity: Int) {
        var data = ByteArray(capacity)
        var size = 0
        private var bitBuffer = 0L
        private var bitCount = 0

        fun byte(v: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v.toByte()
        }

        fun short(v: Int) {
            byte(v ushr 8)
            byte(v)
        }

        fun intLe(v: Int) {
            byte(v)
            byte(v ushr 8)
            byte(v ushr 16)
            byte(v ushr 24)
        }

        fun marker(m: Int) {
            byte(0xFF)
            byte(m)
        }

        fun bytes(b: ByteArray, n: Int) {
            if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
            System.arraycopy(b, 0, data, size, n)
            size += n
        }

        /** The low [n] bits (up to 32) of [v], first the highest, written 32 at a time. */
        fun bits(v: Int, n: Int) {
            bitBuffer = (bitBuffer shl n) or (v.toLong() and ((1L shl n) - 1))
            bitCount += n
            if (bitCount >= 32) {
                bitCount -= 32
                if (size + 8 > data.size) data = data.copyOf(data.size * 2)
                val word = (bitBuffer ushr bitCount).toInt()
                scanByte(word ushr 24)
                scanByte((word ushr 16) and 0xFF)
                scanByte((word ushr 8) and 0xFF)
                scanByte(word and 0xFF)
            }
        }

        /** The last bits of a scan, its last byte filled out with 1 bits. */
        fun flushBits() {
            val pad = (8 - bitCount % 8) % 8
            bitBuffer = (bitBuffer shl pad) or ((1L shl pad) - 1)
            bitCount += pad
            if (size + 8 > data.size) data = data.copyOf(data.size * 2)
            while (bitCount > 0) {
                bitCount -= 8
                scanByte(((bitBuffer ushr bitCount) and 0xFF).toInt())
            }
        }

        private fun scanByte(b: Int) {
            data[size++] = b.toByte()
            if (b == 0xFF) data[size++] = 0
        }
    }

    /** The DCT's scale factors: 1, and cos(k pi / 16) times the square root of 2. */
    private val AAN = doubleArrayOf(1.0, 1.387039845, 1.306562965, 1.175875602, 1.0, 0.785694958, 0.541196100, 0.275899379)

    /** Where each coefficient in zigzag order is in the block. */
    private val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
    )

    /** JPEG's (Annex K) luminance quantization table, in natural order. */
    private val LUMINANCE = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99,
    )

    /** JPEG's (Annex K) luminance DC Huffman table: codes of each length 1 to 16, then the symbols. */
    private val DC_BITS = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
    private val DC_VALUES = IntArray(12) { it }

    /** JPEG's (Annex K) luminance AC Huffman table. */
    private val AC_BITS = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D)
    private val AC_VALUES = intArrayOf(
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
        0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08, 0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0,
        0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
        0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
        0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
        0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
        0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5,
        0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
        0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
        0xF9, 0xFA,
    )

    private val DC = Huffman(DC_BITS, DC_VALUES)
    private val AC = Huffman(AC_BITS, AC_VALUES)
}
