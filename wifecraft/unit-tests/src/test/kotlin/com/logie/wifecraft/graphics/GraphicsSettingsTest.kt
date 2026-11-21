package com.logie.wifecraft.graphics

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphicsSettingsTest {
    @Test fun defaultsAreStandardWithEveryEffectOff() {
        val d = GraphicsSettings()
        assertEquals(GraphicsPreset.STANDARD, d.preset)
        assertEquals(60, d.targetFps)
        assertTrue(d.adaptive)
        // the renderer's array at Standard: scale 1, anisotropy 1, texture detail 1, aperture 0.5, the qualities, nothing else
        val v = d.toNative(timers = false)
        assertEquals(GraphicsSettings.NATIVE_COUNT, v.size)
        assertEquals(1f, v[0]); assertEquals(1f, v[2]); assertEquals(1f, v[3]); assertEquals(0.5f, v[8])
        val effects = listOf(1, 4, 5, 6, 7, 9, 10, 12, 13, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25)
        for (i in effects) assertEquals(0f, v[i], "index $i")
        assertEquals(GraphicsSettings.presetEffects(GraphicsPreset.STANDARD), GraphicsSettings())
        // nothing to lower at Standard but the render scale (the governor's last resort there)
        assertEquals(listOf(QualityStep.RENDER_SCALE, QualityStep.RENDER_SCALE_LOW), d.applicableSteps())
    }

    @Test fun persistsThroughJson() {
        val s = GraphicsSettings.presetEffects(GraphicsPreset.ULTRA).copy(preset = GraphicsPreset.ULTRA, targetFps = 120,
            adaptive = false, overlay = true, exposure = -5, dofInEditor = true, tone = ToneCurve.ACES)
        assertEquals(s, GraphicsSettings.parse(s.toJson().toString()))
        assertEquals(GraphicsSettings.DEFAULT, GraphicsSettings.parse(null))
        assertEquals(GraphicsSettings.DEFAULT, GraphicsSettings.parse("not json"))
        // a missing field keeps its default; out-of-range values are clamped
        val partial = GraphicsSettings.parse("""{"bloom": 400, "renderScale": 10, "msaa": 3, "targetFps": 100, "anisotropy": 12, "tone": "WHAT"}""")
        assertEquals(100, partial.bloom)
        assertEquals(GraphicsSettings.SCALE_MIN, partial.renderScale)
        assertEquals(2, partial.msaa)
        assertEquals(90, partial.targetFps)
        assertEquals(8, partial.anisotropy)
        assertEquals(ToneCurve.OFF, partial.tone)
        assertEquals(0, partial.dof)
    }

    @Test fun presetsKeepTheTargetAndSwitches() {
        val mine = GraphicsSettings(targetFps = 90, adaptive = false, overlay = true)
        val high = mine.withPreset(GraphicsPreset.HIGH)
        assertEquals(GraphicsPreset.HIGH, high.preset)
        assertEquals(90, high.targetFps); assertEquals(false, high.adaptive); assertEquals(true, high.overlay)
        assertEquals(GraphicsSettings.presetEffects(GraphicsPreset.HIGH).bloom, high.bloom)
        // Standard back: every effect off again
        assertEquals(GraphicsSettings().effects(), high.withPreset(GraphicsPreset.STANDARD).effects())
        // Ultra is fuller than High in every effect it has
        val u = GraphicsSettings.presetEffects(GraphicsPreset.ULTRA)
        val h = GraphicsSettings.presetEffects(GraphicsPreset.HIGH)
        assertTrue(u.msaa >= h.msaa && u.bloom >= h.bloom && u.dof >= h.dof && u.ao >= h.ao && u.shadows >= h.shadows)
        assertTrue(u.renderScale > 100 && h.shafts == 0 && u.shafts > 0)
    }

    @Test fun movingASliderMakesItCustomAndBackMatchesThePreset() {
        val high = GraphicsSettings().withPreset(GraphicsPreset.HIGH)
        val moved = high.copy(bloom = 90).withMatchingPreset()
        assertEquals(GraphicsPreset.CUSTOM, moved.preset)
        assertEquals(GraphicsPreset.HIGH, moved.copy(bloom = high.bloom).withMatchingPreset().preset)
        assertEquals(GraphicsPreset.STANDARD, GraphicsSettings(preset = GraphicsPreset.CUSTOM, targetFps = 120).withMatchingPreset().preset)
    }

    @Test fun stepsLowerInTheirOrderAndSkipWhatIsOff() {
        val u = GraphicsSettings().withPreset(GraphicsPreset.ULTRA)
        assertEquals(QualityStep.entries.toList(), u.applicableSteps())
        assertEquals(85, u.lowered(1).renderScale)
        assertEquals(0, u.lowered(2).aoQuality); assertEquals(0, u.lowered(2).shadowQuality)
        assertEquals(0, u.lowered(3).bloom); assertEquals(u.dof, u.lowered(3).dof)
        assertEquals(0, u.lowered(4).dof)
        assertEquals(0, u.lowered(5).msaa)
        val all = u.lowered(99)
        assertEquals(70, all.renderScale)
        assertEquals(0, all.ao + all.contact + all.shadows + all.skin + all.hair + all.bloom + all.dof + all.msaa + all.shafts + all.haze)
        assertEquals(0, all.eyes + all.thinSkin + all.sheenMax)
        // the tone curve, the vignette and the textures are never the governor's
        assertEquals(u.tone, all.tone); assertEquals(u.vignette, all.vignette); assertEquals(u.textureDetail, all.textureDetail)
        // High has no shafts or haze: that step is skipped, the count is shorter
        val h = GraphicsSettings().withPreset(GraphicsPreset.HIGH)
        assertTrue(QualityStep.SHAFTS_HAZE !in h.applicableSteps())
        assertTrue(QualityStep.RENDER_SCALE in h.applicableSteps())
        assertContentEquals(h.lowered(0).toNative(true), h.toNative(true))
    }

    @Test fun theNewPeopleEffectsAreOffInStandardOnInHighAndUltra() {
        val d = GraphicsSettings()
        assertEquals(0, d.eyes); assertEquals(0, d.thinSkin); assertEquals(false, d.sheenFollows)
        assertEquals(0f, SkinSheen.target(1f, d))
        val h = GraphicsSettings.presetEffects(GraphicsPreset.HIGH)
        val u = GraphicsSettings.presetEffects(GraphicsPreset.ULTRA)
        assertTrue(h.eyes > 0 && h.thinSkin > 0 && h.sheenFollows && h.sheenMax > 0)
        assertTrue(u.eyes >= h.eyes && u.thinSkin >= h.thinSkin && u.sheenMax >= h.sheenMax)
        // the renderer's array: eyes and thin skin at the end, in the engine's order
        val v = u.toNative(timers = false)
        assertEquals(u.eyes / 100f, v[24]); assertEquals(u.thinSkin / 100f, v[25])
        // kept and read back; out of range clamped
        val s = u.copy(preset = GraphicsPreset.ULTRA, sheenFollows = false, sheenMax = 77, textureDetail = 4)
        assertEquals(s, GraphicsSettings.parse(s.toJson().toString()))
        val bad = GraphicsSettings.parse("""{"eyes": 300, "thinSkin": -4, "sheenMax": 101, "textureDetail": 3}""")
        assertEquals(100, bad.eyes); assertEquals(0, bad.thinSkin); assertEquals(100, bad.sheenMax); assertEquals(2, bad.textureDetail)
        assertEquals(4, GraphicsSettings.parse("""{"textureDetail": 9}""").textureDetail)
        assertEquals(1, GraphicsSettings.parse("""{"textureDetail": 0}""").textureDetail)
        // moving one makes it Custom
        assertEquals(GraphicsPreset.CUSTOM, GraphicsSettings().withPreset(GraphicsPreset.HIGH).copy(eyes = 10).withMatchingPreset().preset)
    }

    @Test fun theGovernorLowersThinSkinThenSkinAndSheenThenEyesLast() {
        val u = GraphicsSettings().withPreset(GraphicsPreset.ULTRA)
        val steps = u.applicableSteps()
        val i = steps.indexOf(QualityStep.AO_SHADOWS)
        assertEquals(listOf(QualityStep.THIN_SKIN, QualityStep.SKIN, QualityStep.EYES), steps.subList(i + 1, steps.size))
        val noThin = u.lowered(i + 2)
        assertEquals(0, noThin.thinSkin); assertEquals(u.skin, noThin.skin); assertEquals(u.eyes, noThin.eyes)
        val noSkin = u.lowered(i + 3)
        assertEquals(0, noSkin.skin); assertEquals(0, noSkin.sheenMax); assertEquals(0f, SkinSheen.target(1f, noSkin))
        assertEquals(u.eyes, noSkin.eyes)
        assertEquals(0, u.lowered(i + 4).eyes)
        // sheen alone (no skin or hair shading) still counts as the skin step
        val sheenOnly = GraphicsSettings(sheenFollows = true, sheenMax = 50)
        assertTrue(QualityStep.SKIN in sheenOnly.applicableSteps())
        assertTrue(QualityStep.EYES !in sheenOnly.applicableSteps())
    }

    @Test fun skinSheenFollowsTheIntensitySmoothly() {
        val s = GraphicsSettings(sheenFollows = true, sheenMax = 60)
        assertEquals(0f, SkinSheen.target(0.2f, s))            // idle
        assertEquals(0f, SkinSheen.target(SkinSheen.START, s))
        assertEquals(0.6f, SkinSheen.target(1f, s), 1e-6f)
        assertEquals(0.6f, SkinSheen.target(SkinSheen.FULL, s), 1e-6f)
        val mid = SkinSheen.target(0.6f, s)
        assertTrue(mid > 0f && mid < 0.6f)
        var last = 0f
        for (k in 1..20) { val t = SkinSheen.target(0.35f + k * 0.03f, s); assertTrue(t >= last); last = t }
        assertEquals(0f, SkinSheen.target(Float.NaN, s))
        assertEquals(0f, SkinSheen.target(1f, s.copy(sheenFollows = false)))
        // eased: no jump in a frame, builds over seconds, lingers after
        val e = SkinSheen.Ease()
        val first = e.step(0.6f, 1f / 60f)
        assertTrue(first > 0f && first < 0.01f)
        repeat(60 * 6) { e.step(0.6f, 1f / 60f) }
        assertTrue(e.value > 0.35f && e.value < 0.6f)
        repeat(60 * 60) { e.step(0.6f, 1f / 60f) }
        assertEquals(0.6f, e.value, 0.01f)
        repeat(60 * 6) { e.step(0f, 1f / 60f) }
        assertTrue(e.value > 0.3f)                                // still there six seconds after
        assertEquals(e.value, SkinSheen.Ease(e.value).step(0f, Float.NaN))
        val big = SkinSheen.Ease(); big.step(1f, 100f)
        assertTrue(big.value < 0.1f)                             // a long pause is taken as half a second at most
    }
}
