package com.logie.wifecraft.graphics

import com.logie.wifecraft.anim.CharacterSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Finger size: the pad radius mapping, its independence from the body, the setting's persistence and the touch-size modulation. */
class FingerSizeTest {
    private fun near(a: Float, b: Float, eps: Float = 1e-6f) = assertTrue(kotlin.math.abs(a - b) <= eps, "$a vs $b")

    @Test fun aPadIsElevenMillimetresInYardsAtSizeOne() {
        near(FingerSize.padRadius(100, 1f), 0.011f / 0.9144f)
        near(FingerSize.padMetres(100), 0.011f)
    }

    @Test fun theSliderScalesTheFingerFromHalfToDouble() {
        near(FingerSize.padRadius(200, 1f), 2 * FingerSize.padRadius(100, 1f))
        near(FingerSize.padRadius(50, 1f), 0.5f * FingerSize.padRadius(100, 1f))
        near(FingerSize.padRadius(10, 1f), FingerSize.padRadius(50, 1f), 1e-9f)
        near(FingerSize.padRadius(900, 1f), FingerSize.padRadius(200, 1f), 1e-9f)
    }

    @Test fun characterSizeMovesTheWorldNotTheFinger() {
        // 125%: people (and the real world they stand in) are drawn 1.25 times larger, so a real metre of finger is 1.25 times more units
        near(FingerSize.padRadius(100, 1.25f), 1.25f * FingerSize.padRadius(100, 1f))
        // in real metres the pad is the same at every Character size: radius / worldPerMetre
        for (c in listOf(0.8f, 1f, 1.25f, 1.8f)) near(FingerSize.padRadius(100, c) / FingerSize.worldPerMetre(c), 0.011f, 1e-7f)
        CharacterSize.with(1.25f) { near(FingerSize.padRadius(100), 1.25f * 0.011f / 0.9144f) }
        near(FingerSize.padRadius(100, Float.NaN), FingerSize.padRadius(100, 1f)); near(FingerSize.padRadius(100, -2f), FingerSize.padRadius(100, 1f))
    }

    @Test fun theFingerDoesNotShrinkWithTheBody() {
        // there is no body-size parameter at all: a gnome and an orc woman get the same pad (a gnome's body scale is ~0.6 of a human's)
        val human = FingerSize.padRadius(100, 1.25f)
        val gnome = FingerSize.padRadius(100, 1.25f)
        assertEquals(human, gnome)
        // and against a gnome the finger is bigger in proportion to the body: pad / body height
        assertTrue(human / (0.6f * 1.9f) > human / (1.0f * 1.9f))
    }

    @Test fun touchSizeModulatesALittleWithinLimits() {
        near(FingerSize.touchScale(0f, 8f), 1f); near(FingerSize.touchScale(50f, 0f), 1f); near(FingerSize.touchScale(Float.NaN, 8f), 1f)
        near(FingerSize.touchScale(9f * 8f, 8f), 1f)                                   // a typical tip: no change
        assertTrue(FingerSize.touchScale(14f * 8f, 8f) > 1f && FingerSize.touchScale(5f * 8f, 8f) < 1f)
        near(FingerSize.touchScale(1000f, 8f), FingerSize.TOUCH_SCALE_MAX); near(FingerSize.touchScale(1f, 8f), FingerSize.TOUCH_SCALE_MIN)
        near(FingerSize.padRadius(100, 1f, 5f), 1.4f * FingerSize.padRadius(100, 1f)); near(FingerSize.padRadius(100, 1f, 0.1f), 0.8f * FingerSize.padRadius(100, 1f))
    }

    @Test fun theSettingPersistsClampsAndSurvivesPresets() {
        val s = GraphicsSettings(fingerSize = 150)
        assertEquals(150, GraphicsSettings.parse(s.toJson().toString()).fingerSize)
        assertEquals(100, GraphicsSettings.parse("{}").fingerSize)
        assertEquals(200, GraphicsSettings(fingerSize = 999).clamped().fingerSize)
        assertEquals(50, GraphicsSettings(fingerSize = -4).clamped().fingerSize)
        // a preset change keeps it, and it does not make the preset "custom"
        assertEquals(150, s.withPreset(GraphicsPreset.HIGH).fingerSize)
        assertEquals(GraphicsPreset.STANDARD, GraphicsSettings(fingerSize = 150).withMatchingPreset().preset)
        assertEquals(GraphicsSettings.NATIVE_COUNT, GraphicsSettings(fingerSize = 150).toNative(false).size)
    }
}
