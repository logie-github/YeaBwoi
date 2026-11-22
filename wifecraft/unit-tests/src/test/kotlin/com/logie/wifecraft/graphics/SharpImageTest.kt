package com.logie.wifecraft.graphics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Build mode's sharp image: a pure settings-in, settings-out override, and the governor kept off the render scale. */
class SharpImageTest {
    private val ultra = GraphicsSettings().withPreset(GraphicsPreset.ULTRA).copy(chroma = 20, grain = 15)

    @Test fun offOutsideBuildModeAndWithTheOptionOff() {
        assertEquals(ultra, SharpImage.apply(ultra, buildOn = false))
        assertEquals(ultra.copy(sharpInBuild = false), SharpImage.apply(ultra.copy(sharpInBuild = false), buildOn = true))
    }

    @Test fun takesTheBlursOffInBuildModeAndKeepsTheRest() {
        val s = SharpImage.apply(ultra, buildOn = true)
        assertEquals(0, s.dof); assertEquals(0, s.bloom); assertEquals(0, s.shafts)
        assertEquals(0, s.haze); assertEquals(0, s.chroma); assertEquals(0, s.grain)
        // everything else is the user's: the render scale above all
        assertEquals(125, s.renderScale); assertEquals(4, s.msaa); assertEquals(ultra.ao, s.ao)
        assertEquals(ultra.shadows, s.shadows); assertEquals(ultra.sharpen, s.sharpen)
        assertEquals(ultra, s.copy(dof = ultra.dof, bloom = ultra.bloom, shafts = ultra.shafts, haze = ultra.haze, chroma = 20, grain = 15))
    }

    @Test fun isPureAndRestoresExactly() {
        val before = ultra.copy()
        val sharp = SharpImage.apply(ultra, true)
        assertEquals(before, ultra)
        assertNotEquals(ultra, sharp)
        assertEquals(ultra, SharpImage.apply(ultra, false))   // Build ends: the user's own again
        assertEquals(sharp, SharpImage.apply(sharp, true))    // idempotent
    }

    @Test fun depthOfFieldIsOffEvenWhereTheEditorWouldKeepItOn() {
        val s = SharpImage.apply(ultra.copy(dofInEditor = true), true)
        assertEquals(0, s.dof)
    }

    @Test fun governorNeverLowersTheRenderScaleWhileSharp() {
        val steps = SharpImage.steps(ultra, true)
        assertFalse(QualityStep.RENDER_SCALE in steps)
        assertFalse(QualityStep.RENDER_SCALE_LOW in steps)
        // lowered as far as it can go: the scale is still the user's
        val all = SharpImage.lowered(ultra, true, steps.size)
        assertEquals(125, all.renderScale)
        assertEquals(0, all.ao); assertEquals(0, all.shadows)
        // outside Build mode the normal order, scale included
        assertTrue(QualityStep.RENDER_SCALE in SharpImage.steps(ultra, false))
        assertEquals(70, SharpImage.lowered(ultra, false, 99).renderScale)
    }

    @Test fun shadowsAndAoGoBeforeAnythingElseWhileSharp() {
        val steps = SharpImage.steps(ultra, true)
        assertEquals(QualityStep.AO_SHADOW_QUALITY, steps[0])
        assertEquals(QualityStep.AO_SHADOWS, steps[1])
        assertTrue(steps.indexOf(QualityStep.AO_SHADOWS) < steps.indexOf(QualityStep.MSAA))
        val two = SharpImage.lowered(ultra, true, 2)
        assertEquals(0, two.ao); assertEquals(0, two.shadows); assertEquals(4, two.msaa)
    }

    @Test fun cinematicGradeIsOffWhileSharpAndBarsAreNotItsConcern() {
        assertEquals(Triple(0f, 0f, 0f), SharpImage.cinematic(ultra, true, 0.8f, 0.4f, 0.3f))
        assertEquals(Triple(0.8f, 0.4f, 0.3f), SharpImage.cinematic(ultra, false, 0.8f, 0.4f, 0.3f))
        assertEquals(Triple(0.8f, 0.4f, 0.3f), SharpImage.cinematic(ultra.copy(sharpInBuild = false), true, 0.8f, 0.4f, 0.3f))
    }

    @Test fun theOptionIsKeptAndDefaultsOn() {
        assertTrue(GraphicsSettings().sharpInBuild)
        assertTrue(GraphicsSettings.parse(null).sharpInBuild)
        assertTrue(GraphicsSettings.parse("""{"preset":"HIGH"}""").sharpInBuild)
        val off = ultra.copy(sharpInBuild = false)
        assertFalse(GraphicsSettings.parse(off.toJson().toString()).sharpInBuild)
        // it is not an effect: a preset chip and the matching preset ignore it
        assertEquals(GraphicsPreset.ULTRA, GraphicsSettings().withPreset(GraphicsPreset.ULTRA).copy(sharpInBuild = false).withMatchingPreset().preset)
    }
}
