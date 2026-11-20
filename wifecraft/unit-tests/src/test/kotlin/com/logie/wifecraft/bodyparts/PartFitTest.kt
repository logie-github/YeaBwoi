package com.logie.wifecraft.bodyparts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PartFitTest {
    private fun image(w: Int, h: Int, f: (Int, Int) -> Int, alpha: Int = 255) =
        RgbaImage(w, h, ByteArray(w * h * 4).also { b ->
            for (y in 0 until h) for (x in 0 until w) {
                val c = f(x, y); val i = (y * w + x) * 4
                b[i] = (c shr 16).toByte(); b[i + 1] = (c shr 8).toByte(); b[i + 2] = c.toByte(); b[i + 3] = alpha.toByte()
            }
        })

    @Test
    fun partsShowOnlyOnABareLook() {
        assertEquals(PartDraw.NONE, PartDraw.visible(PartDraw.DEFAULT, bare = false))
        assertEquals(PartDraw.NONE, PartDraw.visible(PartDraw.ON, bare = false))
        assertEquals(PartDraw.DEFAULT, PartDraw.visible(PartDraw.DEFAULT, bare = true))
        assertEquals(PartDraw.ON, PartDraw.visible(PartDraw.ON, bare = true))
        assertEquals(PartDraw.NONE, PartDraw.visible(7, bare = true))
    }

    @Test
    fun racesFittedOnTheHarness() {
        assertEquals(1.1f, PartFits.forRace(1).scale)
        assertEquals(1.1f, PartFits.forRace(2).scale)
        assertEquals(PartFit(), PartFits.forRace(4))
        assertEquals(PartFit(), PartFits.forRace(6))
        assertEquals(PartFit(), PartFits.forRace(999))
    }

    @Test
    fun skinSampledAroundTheGivenPoints() {
        // left half green, right half red: points on the right sample red only
        val skin = image(16, 8, { x, _ -> if (x < 8) 0x00FF00 else 0xFF0000 })
        assertEquals(0xFF0000, PartSkin.sampleAt(skin, floatArrayOf(0.8f, 0.5f, 0.9f, 0.2f), radius = 1))
        assertEquals(0x00FF00, PartSkin.sampleAt(skin, floatArrayOf(0.1f, 0.5f), radius = 1))
        // half and half in linear light: 0.5 linear is 188 in sRGB
        val mixed = PartSkin.sampleAt(skin, floatArrayOf(0.1f, 0.5f, 0.9f, 0.5f), radius = 0)!!
        assertEquals(188, mixed shr 16 and 0xFF); assertEquals(188, mixed shr 8 and 0xFF); assertEquals(0, mixed and 0xFF)
        assertNull(PartSkin.sampleAt(skin, FloatArray(0)))
        assertNull(PartSkin.sampleAt(image(4, 4, { _, _ -> 0xFFFFFF }, alpha = 0), floatArrayOf(0.5f, 0.5f)))
    }

    @Test
    fun partTintedToTheSampledSkin() {
        val grey = image(4, 4, { _, _ -> 0x808080 })
        val part = ConvertedPart("p", "DEFAULT", "v", "Pelvis", floatArrayOf(0f, 0.93f, 0.07f), 1.5f, FloatArray(3),
            FloatArray(9), null, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f), listOf("Pelvis"), null, null, intArrayOf(0, 1, 2), 4, 4)
        val skin = image(8, 8, { _, _ -> 0x4A6526 })
        val out = PartSkin.tinted(grey, part, skin, floatArrayOf(0.5f, 0.5f))
        // a flat grey becomes the skin exactly (Recolor: its average turns into the target)
        for (i in 0 until 16) {
            assertEquals(0x4A, out.rgba[i * 4].toInt() and 0xFF)
            assertEquals(0x65, out.rgba[i * 4 + 1].toInt() and 0xFF)
            assertEquals(0x26, out.rgba[i * 4 + 2].toInt() and 0xFF)
        }
        // nothing to sample: the texture as it is
        assertSame(grey, PartSkin.tinted(grey, part, skin, FloatArray(0)))
        assertTrue(out !== grey)
    }
}
