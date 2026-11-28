package com.logie.wifecraft.creator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShapePresetsTest {
    @Test fun everyPresetSetsItsSlidersAndNothingElseOfTheMeshOnes() {
        for (p in BodyShapePreset.entries) {
            val s = BodyShapePreset.apply(BodySculpt().with(SculptSlider.HEIGHT, 0.3f).with(SculptSlider.LEG_LENGTH, -0.2f).with(SculptSlider.NECK, 0.9f), p)
            for ((k, v) in p.values) assertEquals(v, s[k], 1e-4f, "${p.label} ${k.key}")
            for (k in BodyShapePreset.SLIDERS) if (k !in p.values) assertEquals(0f, s[k], "${p.label} leaves ${k.key} at ${s[k]}")
            // the skeleton's sliders are the player's: kept
            assertEquals(0.3f, s[SculptSlider.HEIGHT]); assertEquals(-0.2f, s[SculptSlider.LEG_LENGTH])
            // within the sliders' range
            for (k in SculptSlider.entries) assertTrue(s[k] in -1f..1f)
            assertEquals(p, BodyShapePreset.of(s))
        }
    }

    @Test fun thePresetsAreDistinctAndSymmetric() {
        val shapes = BodyShapePreset.entries.map { BodyShapePreset.apply(BodySculpt(), it) }
        assertEquals(shapes.size, shapes.toSet().size)
        // a side balance an earlier edit made is cleared (both sides alike)
        val lopsided = BodySculpt().with(SculptSlider.BUTT, 0.2f).withSide(SculptSlider.BUTT, BodySide.LEFT, 0.6f)
        val s = BodyShapePreset.apply(lopsided, BodyShapePreset.ZZZ)
        assertEquals(0f, s.side(SculptSlider.BUTT)); assertEquals(s.on(SculptSlider.BUTT, BodySide.LEFT), s.on(SculptSlider.BUTT, BodySide.RIGHT))
    }

    @Test fun theZzzProportionsAreAFullButtAndBustOverASmallWaist() {
        val z = BodyShapePreset.ZZZ.values
        assertTrue(z.getValue(SculptSlider.BUTT) > BodyShapePreset.CURVY.values.getValue(SculptSlider.BUTT))
        assertTrue(z.getValue(SculptSlider.WAIST) < BodyShapePreset.CURVY.values.getValue(SculptSlider.WAIST))
        assertTrue(z.getValue(SculptSlider.THIGHS) > BodyShapePreset.ATHLETIC.values.getValue(SculptSlider.THIGHS))
    }

    @Test fun aBodyAtNoPresetIsNone() {
        assertNull(BodyShapePreset.of(BodySculpt().with(SculptSlider.BUST_SIZE, 0.123f)))
        // the neutral body is not the Natural preset (that one has a little droop and roundness)
        assertNotEquals(BodyShapePreset.NATURAL, BodyShapePreset.of(BodySculpt()))
    }

    @Test fun undoIsOneStepBecauseAPresetIsOneSculpt() {
        // a preset is a value: applying it twice is the same sculpt, so a glide's end and the undo's target are one state
        val a = BodyShapePreset.apply(BodySculpt(), BodyShapePreset.CURVY)
        assertEquals(a, BodyShapePreset.apply(a, BodyShapePreset.CURVY))
    }
}
