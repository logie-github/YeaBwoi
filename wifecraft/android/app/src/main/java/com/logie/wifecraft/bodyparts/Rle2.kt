package com.logie.wifecraft.bodyparts

/** An image as 8-bit RGBA, rows top to bottom. */
class RgbaImage(val width: Int, val height: Int, val rgba: ByteArray) {
    init { require(width > 0 && height > 0 && rgba.size == width * height * 4) { "RGBA size ${rgba.size} for ${width}x$height" } }

    fun pixel(x: Int, y: Int): Int {
        val i = (y * width + x) * 4
        return ((rgba[i + 3].toInt() and 0xFF) shl 24) or ((rgba[i].toInt() and 0xFF) shl 16) or ((rgba[i + 1].toInt() and 0xFF) shl 8) or (rgba[i + 2].toInt() and 0xFF)
    }
}

/**
 * Decodes the Sims 4's textures to RGBA (the first mip level only):
 *
 * - RLE2 / RLES (0x3453CF95, also used for CAS diffuse): u32 "DXT5", u32 "RLE2" or "RLES", u16 width, u16 height,
 *   u16 mip count, u16 0, then per mip i32 command offset and the offsets of four (RLES: five) streams: [2] colour
 *   endpoints (4 bytes a block), [3] colour indices (4), [0] alpha endpoints (2), [1] alpha indices (6), RLES [4]
 *   specular (16). Commands are u16, op = low 2 bits, count = the rest, in blocks: op 0 transparent blocks (RLE2 white,
 *   RLES black), op 1 blocks taken from all streams (RLES also skips a specular block), op 2 opaque blocks (RLE2: colour
 *   only, alpha 255; RLES: all four streams). Blocks fill the image left to right, top to bottom, each a DXT5 block.
 * - LRLE (0x2BC04EDF): "LRLE", version 0 or "V002", u16 width, u16 height, u32 mip count, the mips' offsets, V002 a
 *   colour table (u32 n, n RGBA quads); then run-length commands producing pixels in 4x4 tiles (16 pixels, four rows of
 *   four), tiles left to right, top to bottom.
 * - DDS (0x00B2D882 "DST" images and plain DDS): a 128-byte header; DXT1, DXT5, DST1 and DST5 (DXT with the block
 *   fields regrouped into separate streams: DST1 endpoints then indices, DST5 alpha endpoints, colour endpoints, alpha
 *   indices, colour indices) and uncompressed 32-bit BGRA/RGBA.
 *
 * Sources: s4pi's RLEResource.cs and DSTResource.cs, CmarNYC's TS4SimRipper LRLE.cs; the DXT block layout is
 * Microsoft's BC1/BC3.
 */
object SimsTexture {
    const val TYPE_RLE = 0x3453CF95
    const val TYPE_LRLE = 0x2BC04EDF
    const val TYPE_DST = 0x00B2D882
    const val MAX_SIDE = 8192

    fun isTexture(type: Int) = type == TYPE_RLE || type == TYPE_LRLE || type == TYPE_DST

    /** What [bytes] hold, by their first bytes: "RLE2", "RLES", "LRLE", "DDS", "JPEG", "PNG" or "unknown". */
    fun kind(bytes: ByteArray): String = when {
        tag(bytes, 0) == "DXT5" && tag(bytes, 4) == "RLE2" -> "RLE2"
        tag(bytes, 0) == "DXT5" && tag(bytes, 4) == "RLES" -> "RLES"
        tag(bytes, 0) == "LRLE" -> "LRLE"
        tag(bytes, 0) == "DDS " -> "DDS"
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "JPEG"
        bytes.size > 3 && bytes[0] == 0x89.toByte() && tag(bytes, 1).startsWith("PNG") -> "PNG"
        else -> "unknown"
    }

    fun decode(bytes: ByteArray, key: String = "texture"): RgbaImage = when (val k = kind(bytes)) {
        "RLE2", "RLES" -> decodeRle(bytes, key, k == "RLES")
        "LRLE" -> decodeLrle(bytes, key)
        "DDS" -> decodeDds(bytes, key)
        else -> throw BodyPartFormatException("The texture $key is $k, not an RLE2, RLES, LRLE or DDS image.")
    }

    private fun tag(b: ByteArray, at: Int) = if (at + 4 <= b.size) String(b, at, 4, Charsets.ISO_8859_1) else ""

    private fun checkSide(r: Bin, w: Int, h: Int) {
        if (w !in 1..MAX_SIDE || h !in 1..MAX_SIDE) r.fail("claims a ${w}x$h image")
    }

    // ---------------------------------------------------------------- RLE2 / RLES

    private fun decodeRle(b: ByteArray, key: String, rles: Boolean): RgbaImage {
        val r = Bin(b, "texture $key")
        r.skip(8)
        val w = r.u16(); val h = r.u16(); val mips = r.u16(); r.u16()
        checkSide(r, w, h)
        if (mips < 1 || mips > 16) r.fail("claims $mips mip levels")
        val per = if (rles) 6 else 5
        val hdr = Array(mips) { IntArray(per) { r.i32() } }
        // header fields: [0] command, [1] colour endpoints (2), [2] colour indices (3), [3] alpha endpoints (0), [4] alpha indices (1), [5] specular (4)
        val cmdEnd = if (mips > 1) hdr[1][0] else hdr[0][1]
        var o2 = hdr[0][1]; var o3 = hdr[0][2]; var o0 = hdr[0][3]; var o1 = hdr[0][4]
        val bw = (w + 3) / 4; val bh = (h + 3) / 4
        val total = bw * bh
        val block = ByteArray(16)
        val tile = ByteArray(64)
        val out = ByteArray(w * h * 4)
        var bi = 0
        var c = hdr[0][0]
        if (c < 0 || cmdEnd > b.size || c > cmdEnd) r.fail("has its commands outside the data")
        fun need(at: Int, n: Int) { if (at < 0 || at + n > b.size) r.fail("has a block stream that runs past its end") }
        while (c + 1 < cmdEnd && bi < total) {
            val cmd = (b[c].toInt() and 0xFF) or ((b[c + 1].toInt() and 0xFF) shl 8)
            c += 2
            val op = cmd and 3
            val count = cmd ushr 2
            repeat(count) {
                if (bi >= total) r.fail("has more blocks than a ${w}x$h image")
                when (op) {
                    0 -> {
                        block.fill(0)
                        block[1] = 5 // alpha1 > alpha0: index 0 = alpha0 = 0
                        if (!rles) { block[8] = -1; block[9] = -1 } // colour0 white
                    }
                    1 -> {
                        need(o0, 2); need(o1, 6); need(o2, 4); need(o3, 4)
                        System.arraycopy(b, o0, block, 0, 2); System.arraycopy(b, o1, block, 2, 6)
                        System.arraycopy(b, o2, block, 8, 4); System.arraycopy(b, o3, block, 12, 4)
                        o0 += 2; o1 += 6; o2 += 4; o3 += 4
                    }
                    2 -> {
                        if (rles) {
                            need(o0, 2); need(o1, 6)
                            System.arraycopy(b, o0, block, 0, 2); System.arraycopy(b, o1, block, 2, 6)
                            o0 += 2; o1 += 6
                        } else {
                            block[0] = 0; block[1] = 5; for (i in 2..7) block[i] = -1 // index 7 = 255 in the 6-value mode
                        }
                        need(o2, 4); need(o3, 4)
                        System.arraycopy(b, o2, block, 8, 4); System.arraycopy(b, o3, block, 12, 4)
                        o2 += 4; o3 += 4
                    }
                    else -> r.fail("has unknown command $op")
                }
                decodeDxt5Block(block, 0, tile)
                putTile(out, w, h, bi % bw, bi / bw, tile)
                bi++
            }
        }
        if (bi != total) r.fail("fills $bi of the $total blocks of a ${w}x$h image")
        return RgbaImage(w, h, out)
    }

    // ---------------------------------------------------------------- DXT blocks (BC1 / BC3)

    /**
     * Decodes one 8-byte DXT1 colour block at [off] into [out] (16 RGBA pixels, row by row). With [fourColour] (as in
     * DXT5's colour half) the block always uses four colours; otherwise colour0 <= colour1 selects three colours and
     * transparent black.
     */
    fun decodeDxt1Block(src: ByteArray, off: Int, out: ByteArray, fourColour: Boolean = false) {
        val c0 = (src[off].toInt() and 0xFF) or ((src[off + 1].toInt() and 0xFF) shl 8)
        val c1 = (src[off + 2].toInt() and 0xFF) or ((src[off + 3].toInt() and 0xFF) shl 8)
        val pal = IntArray(16)
        fun expand(c: Int, i: Int) {
            val r5 = (c shr 11) and 31; val g6 = (c shr 5) and 63; val b5 = c and 31
            pal[i * 4] = (r5 shl 3) or (r5 shr 2); pal[i * 4 + 1] = (g6 shl 2) or (g6 shr 4); pal[i * 4 + 2] = (b5 shl 3) or (b5 shr 2); pal[i * 4 + 3] = 255
        }
        expand(c0, 0); expand(c1, 1)
        if (fourColour || c0 > c1) {
            for (k in 0..2) {
                pal[8 + k] = (2 * pal[k] + pal[4 + k] + 1) / 3
                pal[12 + k] = (pal[k] + 2 * pal[4 + k] + 1) / 3
            }
            pal[11] = 255; pal[15] = 255
        } else {
            for (k in 0..2) pal[8 + k] = (pal[k] + pal[4 + k]) / 2
            pal[11] = 255
            pal[12] = 0; pal[13] = 0; pal[14] = 0; pal[15] = 0
        }
        for (p in 0 until 16) {
            val byte = src[off + 4 + p / 4].toInt() and 0xFF
            val idx = (byte shr ((p % 4) * 2)) and 3
            for (k in 0..3) out[p * 4 + k] = pal[idx * 4 + k].toByte()
        }
    }

    /** Decodes one 8-byte DXT5 alpha block at [off] into the alpha bytes of [out] (16 RGBA pixels). */
    fun decodeDxt5AlphaBlock(src: ByteArray, off: Int, out: ByteArray) {
        val a0 = src[off].toInt() and 0xFF
        val a1 = src[off + 1].toInt() and 0xFF
        val pal = IntArray(8)
        pal[0] = a0; pal[1] = a1
        if (a0 > a1) {
            for (i in 1..6) pal[i + 1] = ((7 - i) * a0 + i * a1 + 3) / 7
        } else {
            for (i in 1..4) pal[i + 1] = ((5 - i) * a0 + i * a1 + 2) / 5
            pal[6] = 0; pal[7] = 255
        }
        var bits = 0L
        for (i in 0 until 6) bits = bits or ((src[off + 2 + i].toLong() and 0xFF) shl (8 * i))
        for (p in 0 until 16) out[p * 4 + 3] = pal[((bits shr (3 * p)) and 7).toInt()].toByte()
    }

    /** One 16-byte DXT5 block: alpha half then colour half (always four colours). */
    fun decodeDxt5Block(src: ByteArray, off: Int, out: ByteArray) {
        decodeDxt1Block(src, off + 8, out, fourColour = true)
        decodeDxt5AlphaBlock(src, off, out)
    }

    private fun putTile(out: ByteArray, w: Int, h: Int, bx: Int, by: Int, tile: ByteArray) {
        for (py in 0..3) {
            val y = by * 4 + py
            if (y >= h) break
            for (px in 0..3) {
                val x = bx * 4 + px
                if (x >= w) break
                System.arraycopy(tile, (py * 4 + px) * 4, out, (y * w + x) * 4, 4)
            }
        }
    }

    // ---------------------------------------------------------------- LRLE

    private fun decodeLrle(b: ByteArray, key: String): RgbaImage {
        val r = Bin(b, "texture $key")
        r.skip(4)
        val ver = r.i32()
        val v2 = ver == 0x32303056 // "V002"
        if (!v2 && ver != 0) r.fail("is LRLE version 0x%08X".format(ver))
        val w = r.u16(); val h = r.u16()
        checkSide(r, w, h)
        val mips = r.i32()
        if (mips !in 1..16) r.fail("claims $mips mip levels")
        val offs = IntArray(mips) { r.i32() }
        val table: Array<ByteArray> = if (v2) { val n = r.count(4, "colours"); Array(n) { r.bytes(4) } } else emptyArray()
        val base = r.p
        val end = if (mips > 1) base + offs[1] - offs[0] else b.size
        if (end > b.size || end < base) r.fail("has its first mip outside the data")
        val px = ByteArray(w * h * 4)
        var o = 0
        var p = base + offs[0]
        fun at(i: Int): Int { if (i >= end) r.fail("has a command cut short"); return b[i].toInt() and 0xFF }
        fun put(src: ByteArray, sOff: Int) { if (o + 4 > px.size) r.fail("has more pixels than a ${w}x$h image"); System.arraycopy(src, sOff, px, o, 4); o += 4 }
        fun color(i: Int): ByteArray = table.getOrNull(i) ?: r.fail("uses colour $i of ${table.size}")
        fun varint(firstShift: Int, firstBits: Int): Int {
            var v = (at(p) and 0x7F) shr firstShift
            var shift = firstBits
            while (at(p) and 0x80 != 0) { p++; v += (at(p) and 0x7F) shl shift; shift += 7; if (shift > 31) r.fail("has a run too long") }
            return v
        }
        if (v2) {
            while (p < end && o < px.size) {
                val c = at(p)
                when {
                    c and 3 == 3 -> { val n = varint(2, 5); p++; repeat(n) { if (p + 4 > end) r.fail("is cut short"); put(b, p); p += 4 } }
                    c and 7 == 6 -> { val n = varint(3, 4); p++; if (p + 4 > end) r.fail("is cut short"); repeat(n) { put(b, p) }; p += 4 }
                    c and 3 == 1 -> {
                        val n = varint(2, 5); p++
                        repeat(n) { val i = varint(0, 7); p++; put(color(i), 0) }
                    }
                    c and 7 == 2 -> { val n = varint(3, 4); p++; val i = at(p); p++; val col = color(i); repeat(n) { put(col, 0) } }
                    c and 7 == 4 -> {
                        val n = varint(3, 4); p++
                        val i = at(p) or (at(p + 1) shl 8); p += 2
                        val col = color(i); repeat(n) { put(col, 0) }
                    }
                    else -> r.fail("has unknown command 0x%02X".format(c))
                }
            }
        } else {
            while (p < end && o < px.size) {
                val c = at(p)
                when (c and 3) {
                    1 -> { val n = c shr 2; p++; repeat(n) { if (p + 4 > end) r.fail("is cut short"); put(b, p); p += 4 } }
                    2 -> { val n = varint(2, 5); p++; if (p + 4 > end) r.fail("is cut short"); repeat(n) { put(b, p) }; p += 4 }
                    0 -> { val n = varint(2, 5); p++; repeat(n) { if (o + 4 > px.size) r.fail("has too many pixels"); o += 4 } }
                    else -> {
                        val n = c shr 2; p++
                        val planes = ByteArray(n * 4)
                        var q = 0
                        while (q < planes.size) {
                            val d = at(p)
                            when {
                                d and 1 == 1 -> {
                                    var cnt = (d and 0x7F) shr 1
                                    if (d and 0x80 != 0) { p++; cnt += at(p) shl 6 }
                                    p++
                                    if (q + cnt > planes.size || p + cnt > end) r.fail("has a byte run past its end")
                                    System.arraycopy(b, p, planes, q, cnt); q += cnt; p += cnt
                                }
                                d and 2 == 2 -> {
                                    var cnt = (d and 0x7F) shr 2
                                    if (d and 0x80 != 0) { p++; cnt += at(p) shl 5 }
                                    p++
                                    if (q + cnt > planes.size) r.fail("has a repeat past its end")
                                    val v = at(p).toByte(); p++
                                    repeat(cnt) { planes[q++] = v }
                                }
                                else -> {
                                    var cnt = (d and 0x7F) shr 2
                                    if (d and 0x80 != 0) { p++; cnt += at(p) shl 5 }
                                    p++
                                    if (q + cnt > planes.size) r.fail("has a zero run past its end")
                                    q += cnt
                                }
                            }
                        }
                        for (i in 0 until n) {
                            if (o + 4 > px.size) r.fail("has too many pixels")
                            for (k in 0..3) px[o + k] = planes[i + k * n]
                            o += 4
                        }
                    }
                }
            }
        }
        if (o != px.size) r.fail("fills ${o / 4} of the ${w * h} pixels of a ${w}x$h image")
        // pixels come in 4x4 tiles; bytes are B, G, R, A (as TS4SimRipper hands them to a 32-bit ARGB bitmap)
        val out = ByteArray(px.size)
        val tilesX = (w + 3) / 4
        for (t in 0 until (w * h) / 16) {
            val tx = t % tilesX; val ty = t / tilesX
            for (i in 0 until 16) {
                val x = tx * 4 + i % 4; val y = ty * 4 + i / 4
                if (x >= w || y >= h) continue
                val s = (t * 16 + i) * 4; val d = (y * w + x) * 4
                out[d] = px[s + 2]; out[d + 1] = px[s + 1]; out[d + 2] = px[s]; out[d + 3] = px[s + 3]
            }
        }
        return RgbaImage(w, h, out)
    }

    // ---------------------------------------------------------------- DDS / DST

    private fun decodeDds(b: ByteArray, key: String): RgbaImage {
        val r = Bin(b, "texture $key")
        if (b.size < 128) r.fail("is shorter than a DDS header")
        val hd = r.absolute(12)
        val h = hd.i32(); val w = hd.i32()
        checkSide(r, w, h)
        val pf = r.absolute(80)
        val pfFlags = pf.i32()
        val fourcc = String(b, 84, 4, Charsets.ISO_8859_1)
        val bits = r.absolute(88).i32()
        val data = 128
        val bw = (w + 3) / 4; val bh = (h + 3) / 4; val blocks = bw * bh
        val out = ByteArray(w * h * 4)
        val tile = ByteArray(64)
        val blk = ByteArray(16)
        val dataSize = b.size - data
        fun need(n: Int) { if (n > dataSize) r.fail("holds $dataSize bytes of image data, a ${w}x$h $fourcc image needs $n") }
        when {
            pfFlags and 4 != 0 && fourcc == "DXT1" -> { need(blocks * 8); for (i in 0 until blocks) { decodeDxt1Block(b, data + i * 8, tile); putTile(out, w, h, i % bw, i / bw, tile) } }
            pfFlags and 4 != 0 && fourcc == "DXT5" -> { need(blocks * 16); for (i in 0 until blocks) { decodeDxt5Block(b, data + i * 16, tile); putTile(out, w, h, i % bw, i / bw, tile) } }
            pfFlags and 4 != 0 && fourcc == "DST1" -> {
                need(blocks * 8)
                val ends = data; val idx = data + dataSize / 2
                for (i in 0 until blocks) {
                    System.arraycopy(b, ends + i * 4, blk, 0, 4); System.arraycopy(b, idx + i * 4, blk, 4, 4)
                    decodeDxt1Block(blk, 0, tile); putTile(out, w, h, i % bw, i / bw, tile)
                }
            }
            pfFlags and 4 != 0 && fourcc == "DST5" -> {
                need(blocks * 16)
                val a0 = data; val c0 = a0 + (dataSize shr 3); val a1 = c0 + (dataSize shr 2); val c1 = a1 + (6 * dataSize shr 4)
                if (c1 + blocks * 4 > b.size) r.fail("has DST5 streams past its end")
                for (i in 0 until blocks) {
                    System.arraycopy(b, a0 + i * 2, blk, 0, 2); System.arraycopy(b, a1 + i * 6, blk, 2, 6)
                    System.arraycopy(b, c0 + i * 4, blk, 8, 4); System.arraycopy(b, c1 + i * 4, blk, 12, 4)
                    decodeDxt5Block(blk, 0, tile); putTile(out, w, h, i % bw, i / bw, tile)
                }
            }
            pfFlags and 0x40 != 0 && bits == 32 -> {
                need(w * h * 4)
                val rm = r.absolute(92).i32(); val gm = r.absolute(96).i32(); val bm = r.absolute(100).i32(); val am = r.absolute(104).i32()
                val bb = r.absolute(data)
                for (i in 0 until w * h) {
                    val px = bb.i32()
                    out[i * 4] = channel(px, rm); out[i * 4 + 1] = channel(px, gm); out[i * 4 + 2] = channel(px, bm)
                    out[i * 4 + 3] = if (am == 0) -1 else channel(px, am)
                }
            }
            else -> r.fail("is a DDS image in format '${fourcc.trim { it < ' ' }}' (flags 0x%X, %d bits); DXT1, DXT5, DST1, DST5 and 32-bit are read".format(pfFlags, bits))
        }
        return RgbaImage(w, h, out)
    }

    private fun channel(px: Int, mask: Int): Byte {
        if (mask == 0) return 0
        val shift = Integer.numberOfTrailingZeros(mask)
        return ((px and mask) ushr shift).toByte()
    }
}
