package com.logie.wifecraft.bodyparts

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** A minimal PNG writer (8-bit RGBA, no filter), pure Kotlin: for part textures written at import and desktop dumps. */
object Png {
    fun encode(img: RgbaImage, level: Int = 6): ByteArray {
        val raw = ByteArray((img.width * 4 + 1) * img.height)
        for (y in 0 until img.height) {
            val row = y * (img.width * 4 + 1)
            raw[row] = 0
            System.arraycopy(img.rgba, y * img.width * 4, raw, row + 1, img.width * 4)
        }
        val def = Deflater(level)
        val z = ByteArrayOutputStream()
        try {
            def.setInput(raw); def.finish()
            val buf = ByteArray(65536)
            while (!def.finished()) z.write(buf, 0, def.deflate(buf))
        } finally { def.end() }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArray(13)
        be(ihdr, 0, img.width); be(ihdr, 4, img.height)
        ihdr[8] = 8; ihdr[9] = 6 // 8 bits, RGBA
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun be(b: ByteArray, at: Int, v: Int) { b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte() }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = ByteArray(4); be(len, 0, data.size); out.write(len)
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        val c = ByteArray(4); be(c, 0, crc.value.toInt()); out.write(c)
    }
}
