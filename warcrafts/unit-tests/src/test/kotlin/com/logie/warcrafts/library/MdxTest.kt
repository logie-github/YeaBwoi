package com.logie.warcrafts.library

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MdxTest {
    /** Little-endian bytes, with chunks and self-sized parts. */
    private class Out {
        val bytes = ByteArrayOutputStream()
        fun int(v: Int) = apply { bytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()) }
        fun float(v: Float) = int(java.lang.Float.floatToIntBits(v))
        fun tag(t: String) = apply { bytes.write(t.toByteArray(Charsets.ISO_8859_1)) }
        fun zeros(n: Int) = apply { bytes.write(ByteArray(n)) }
        fun raw(b: ByteArray) = apply { bytes.write(b) }
        fun chunk(t: String, body: Out.() -> Unit) = apply { val o = Out().apply(body).bytes.toByteArray(); tag(t); int(o.size); raw(o) }
        fun sized(body: Out.() -> Unit) = apply { val o = Out().apply(body).bytes.toByteArray(); int(o.size + 4); raw(o) }
        fun get(): ByteArray = bytes.toByteArray()
    }

    /** A KMTA track of two linear keys. */
    private fun Out.alphaTrack() = tag("KMTA").int(2).int(1).int(-1).int(0).float(1f).int(100).float(0.5f)

    private fun Out.geosetBody(version: Int) {
        tag("VRTX").int(3).zeros(36)
        tag("NRMS").int(3).zeros(36)
        tag("PTYP").int(1).int(4)
        tag("PCNT").int(1).int(3)
        tag("PVTX").int(3).raw(byteArrayOf(0, 0, 1, 0, 2, 0))
        tag("GNDX").int(3).raw(byteArrayOf(0, 0, 0))
        tag("MTGC").int(1).int(1)
        tag("MATS").int(1).int(0)
        int(0).int(0).int(0) // material, selection group, flags
        if (version >= 900) int(0).zeros(80) // level of detail, name
        float(1f).zeros(24) // extent
        int(1).float(2f).zeros(24) // one animation's extent
        tag("UVAS").int(1).tag("UVBS").int(3).zeros(24)
    }

    private fun model(version: Int, textureId: Int = 1): ByteArray = Out().tag("MDLX")
        .chunk("VERS") { int(version) }
        .chunk("MODL") { zeros(372) }
        .chunk("MTLS") {
            sized {
                int(0).int(0)
                if (version in 900 until 1100) zeros(80)
                tag("LAYS").int(1)
                sized {
                    int(2).int(0).int(if (version >= 1100) 0 else textureId).int(-1).int(0).float(1f)
                    if (version >= 900) float(1f)
                    if (version >= 1000) zeros(20)
                    if (version >= 1100) {
                        int(0).int(2)
                        int(textureId).int(0) // diffuse
                        int(7).int(1) // normal map
                    }
                    alphaTrack()
                    if (version >= 1000) tag("KFCA").int(1).int(0).int(-1).int(0).float(0f)
                }
            }
        }
        .chunk("TEXS") { int(0).zeros(260).int(0) }
        .chunk("GEOS") { sized { geosetBody(version) } }
        .apply { if (version >= 900) chunk("CORN") { zeros(8) } }
        .get()

    @Test
    fun `an 800 model is left as it is`() {
        assertEquals(800, Mdx.version(model(800)))
        assertNull(Mdx.toV800(model(800)))
    }

    @Test
    fun `a 1100 model becomes the 800 model of the same content`() {
        assertContentEquals(model(800), Mdx.toV800(model(1100)))
    }

    @Test
    fun `900 and 1000 models become 800 too`() {
        assertContentEquals(model(800), Mdx.toV800(model(900)))
        assertContentEquals(model(800), Mdx.toV800(model(1000)))
    }

    @Test
    fun `the layer's texture is the first of 1100's list`() {
        assertContentEquals(model(800, textureId = 5), Mdx.toV800(model(1100, textureId = 5)))
    }

    @Test
    fun `a file that isn't a model isn't converted`() {
        assertNull(Mdx.version(ByteArray(32)))
        assertFailsWith<Mdx.NotConvertible> { Mdx.toV800("not a model at all".toByteArray()) }
    }
}
