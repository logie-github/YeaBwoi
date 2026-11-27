package com.logie.wifecraft.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The stockings' synthesised sounds: the friction's parameters follow the tension and its slope; the rip is a deterministic burst. */
class StockSoundTest {
    @Test fun slackFabricHeldStillIsSilentAndAQuickPullIsLoudAndHigh() {
        assertTrue(StockSoundCore.friction(0f, 0f, true).silent)
        assertTrue(StockSoundCore.friction(0.3f, 0f, false).silent, "let go and still: nothing")
        val slow = StockSoundCore.friction(0.4f, 0.3f, true)
        val quick = StockSoundCore.friction(0.4f, 2.0f, true)
        assertTrue(quick.gain > slow.gain, "${quick.gain} vs ${slow.gain}")
        assertTrue(quick.centerHz > slow.centerHz, "a quick pull squeaks higher")
    }

    @Test fun pitchRisesAndTheBandNarrowsWithTension() {
        var lastHz = 0f; var lastQ = 0f
        for (i in 0..10) {
            val f = StockSoundCore.friction(i / 10f, 1.0f, true)
            assertTrue(f.centerHz >= lastHz && f.q >= lastQ, "at ${i / 10f}")
            lastHz = f.centerHz; lastQ = f.q
            assertTrue(f.centerHz in 300f..8000f && f.gain in 0f..0.8f)
        }
    }

    @Test fun theSpringbackIsSofterThanThePullAndATautHoldHums() {
        assertTrue(StockSoundCore.friction(0.6f, -2f, false).gain < StockSoundCore.friction(0.6f, 2f, true).gain)
        val hum = StockSoundCore.friction(0.9f, 0f, true)
        assertFalse(hum.silent); assertTrue(hum.gain < 0.15f)
        assertTrue(StockSoundCore.friction(5f, 99f, true).gain <= 0.8f && StockSoundCore.friction(-3f, -99f, true).gain <= 0.8f, "out of range input is clamped")
    }

    @Test fun theRipEnvelopeIsASharpAttackAFallAndARaggedTailThenNothing() {
        assertEquals(0f, StockSoundCore.ripEnvelope(-0.1f)); assertEquals(0f, StockSoundCore.ripEnvelope(0f))
        val peak = (0..420).maxOf { StockSoundCore.ripEnvelope(it / 1000f) }
        assertTrue(peak > 0.6f && peak <= 1f, "$peak")
        assertTrue(StockSoundCore.ripEnvelope(0.002f) > 0.5f * peak * 0.8f, "attack within 2 ms")
        assertTrue(StockSoundCore.ripEnvelope(0.03f) < StockSoundCore.ripEnvelope(0.003f), "falls")
        assertTrue(StockSoundCore.ripEnvelope(0.122f) > StockSoundCore.ripEnvelope(0.1f), "a crack in the tail")
        assertEquals(0f, StockSoundCore.ripEnvelope(0.5f))
        // the same every time
        for (t in listOf(0.001f, 0.05f, 0.2f, 0.4f)) assertEquals(StockSoundCore.ripEnvelope(t), StockSoundCore.ripEnvelope(t))
    }

    @Test fun theRenderedRipIsDeterministicForASeedDifferentForAnotherAndBounded() {
        val a = StockSoundCore.renderRip(7); val b = StockSoundCore.renderRip(7); val c = StockSoundCore.renderRip(8)
        assertContentEquals(a, b)
        assertFalse(a.contentEquals(c))
        assertEquals(StockSoundCore.ripSamples(), a.size)
        assertTrue(a.size in 8000..10000)
        assertTrue(a.maxOf { abs(it.toInt()) } in 8000..32767, "loud enough, not clipped past the range")
        assertTrue(abs(a.last().toInt()) < 600, "ends quiet: no click")
        val head = a.take(220).maxOf { abs(it.toInt()) }; val tail = a.takeLast(2000).maxOf { abs(it.toInt()) }
        assertTrue(head > tail, "the tear is loudest at the start")
    }

    @Test fun theFrictionVoiceRendersNoiseOnlyWhileTold_AndGlidesWithoutBlowingUp() {
        val v = FrictionVoice(1)
        val buf = ShortArray(2048)
        v.render(Friction(0f, 1500f, 2f), buf)
        assertTrue(buf.all { it.toInt() == 0 }, "silent until told")
        repeat(6) { v.render(Friction(0.6f, 3000f, 6f), buf) }
        val level = buf.maxOf { abs(it.toInt()) }
        assertTrue(level in 200..32767, "$level")
        repeat(30) { v.render(Friction(0f, 3000f, 6f), buf) }
        assertTrue(buf.maxOf { abs(it.toInt()) } < level / 4, "fades out when told silence")
        // same seed, same stream
        val a = FrictionVoice(5); val b = FrictionVoice(5); val x = ShortArray(512); val y = ShortArray(512)
        a.render(Friction(0.5f, 2000f, 3f), x); b.render(Friction(0.5f, 2000f, 3f), y)
        assertContentEquals(x, y)
    }
}
