package com.logie.wifecraft.creator

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Bust & Butt designer's model (BustButtDesigner.kt): its groups, views, presets and their glide, Randomize, Reset, Mirror. */
class BustButtDesignerTest {
    @Test fun groupsPerSex() {
        assertEquals(listOf(DesignerGroup.BUST, DesignerGroup.BUTT), DesignerGroup.of(true))
        assertEquals(listOf(DesignerGroup.CHEST, DesignerGroup.GLUTES), DesignerGroup.of(false))
        // every slider the spec names is in its group, once
        assertTrue(DesignerGroup.BUST.sliders.containsAll(listOf(SculptSlider.BUST_SIZE, SculptSlider.BUST_WIDTH, SculptSlider.BUST_SPACING,
            SculptSlider.BUST_PROJECTION, SculptSlider.BUST_LIFT, SculptSlider.BUST_DROOP, SculptSlider.BUST_UPPER, SculptSlider.BUST_ROUND,
            SculptSlider.BUST_SIDE, SculptSlider.UNDERBUST)))
        assertTrue(DesignerGroup.BUTT.sliders.containsAll(listOf(SculptSlider.BUTT, SculptSlider.BUTT_WIDTH, SculptSlider.BUTT_LIFT,
            SculptSlider.BUTT_PROJECTION, SculptSlider.BUTT_ROUND, SculptSlider.CHEEK_GAP, SculptSlider.HIP_DIP, SculptSlider.UNDERBUTT,
            SculptSlider.HIPS, SculptSlider.WAIST, SculptSlider.THIGHS)))
        assertTrue(DesignerGroup.CHEST.sliders.containsAll(listOf(SculptSlider.CHEST, SculptSlider.PECS, SculptSlider.ABS)))
        for (g in DesignerGroup.entries) assertEquals(g.sliders.size, g.sliders.toSet().size, "$g")
        // a man's groups carry nothing of a woman's alone
        for (g in DesignerGroup.of(false)) assertTrue(g.sliders.all { it.forMen }, "$g")
        assertEquals("Size", DesignerGroup.BUST.labelOf(SculptSlider.BUST_SIZE, true))
        assertEquals("Glutes", DesignerGroup.GLUTES.labelOf(SculptSlider.BUTT, false))
    }

    @Test fun views() {
        assertEquals(DesignerView.SIDE, DesignerView.first(DesignerGroup.BUST))
        assertEquals(DesignerView.BACK, DesignerView.first(DesignerGroup.BUTT))
        assertEquals(180f, DesignerView.BACK.facing); assertEquals(90f, DesignerView.SIDE.facing)
        assertTrue(DesignerView.FRONT.focus(DesignerGroup.BUST) > DesignerView.FRONT.focus(DesignerGroup.BUTT))   // (the bust higher)
        for (v in DesignerView.entries) for (g in DesignerGroup.entries) assertTrue(v.zoom(g) >= 1f && v.focus(g) in 0.25f..0.95f)
    }

    @Test fun presetsAndTheirGlide() {
        val start = BodySculpt().with(SculptSlider.BUST_SIZE, -0.5f).with(SculptSlider.NECK, 0.3f).withSide(SculptSlider.BUST_WIDTH, BodySide.LEFT, 0.4f)
        for (g in DesignerGroup.entries) for (p in ShapePreset.entries) {
            val to = BustButtDesigner.applyPreset(start, p, g)
            for (k in g.sliders) { assertEquals(p.values(g)[k] ?: 0f, to[k], 1e-6f, "$g $p $k"); assertEquals(0f, to.side(k)) }
            assertEquals(0.3f, to[SculptSlider.NECK])                                   // (another part's slider kept)
            assertEquals(p, BustButtDesigner.presetOf(to, g), "$g $p")
            for (v in p.values(g).values) assertTrue(v in -1f..1f)
            // the glide: from start at 0 to the preset at 1, each step between them, the ends exact
            assertEquals(start, BustButtDesigner.tween(start, to, 0f, g.sliders))
            assertEquals(to, BustButtDesigner.tween(start, to, 1f, g.sliders))
            val mid = BustButtDesigner.tween(start, to, 0.5f, g.sliders)
            for (k in g.sliders) {
                val lo = minOf(start[k], to[k]) - 0.011f; val hi = maxOf(start[k], to[k]) + 0.011f
                assertTrue(mid[k] in lo..hi, "$g $p $k ${mid[k]}")
            }
        }
        // the presets differ from each other (a woman's bust and butt), and Natural is near the body as made
        assertEquals(ShapePreset.entries.size, ShapePreset.entries.map { it.values(DesignerGroup.BUST) }.toSet().size)
        assertTrue(ShapePreset.NATURAL.values(DesignerGroup.BUST).values.all { kotlin.math.abs(it) <= 0.25f })
        // eased: slow at the ends
        assertEquals(0f, BustButtDesigner.ease(0f)); assertEquals(1f, BustButtDesigner.ease(1f)); assertEquals(0.5f, BustButtDesigner.ease(0.5f))
        assertTrue(BustButtDesigner.ease(0.1f) < 0.1f && BustButtDesigner.ease(0.9f) > 0.9f)
    }

    @Test fun randomizeResetMirror() {
        val start = BodySculpt().with(SculptSlider.NECK, 0.3f)
        for (seed in 1..20) for (g in DesignerGroup.entries) {
            val r = BustButtDesigner.randomize(start, Random(seed), g)
            for (k in g.sliders) { assertTrue(kotlin.math.abs(r[k]) <= BustButtDesigner.RANDOM_REACH + 1e-3f, "$k ${r[k]}"); assertEquals(0f, r.side(k)) }
            assertEquals(0.3f, r[SculptSlider.NECK])
            assertEquals(r, BustButtDesigner.randomize(start, Random(seed), g))      // (the same seed, the same shape)
            val z = BustButtDesigner.reset(r, g)
            assertTrue(BustButtDesigner.isNeutral(z, g)); assertEquals(0.3f, z[SculptSlider.NECK])
        }
        val sided = BodySculpt().withSide(SculptSlider.BUTT_ROUND, BodySide.LEFT, 0.6f).withSide(SculptSlider.HIPS, BodySide.RIGHT, 0.4f)
        assertTrue(sided.isSided)
        val m = BustButtDesigner.mirror(sided, DesignerGroup.BUTT)
        assertTrue(!m.isSided); assertEquals(sided[SculptSlider.BUTT_ROUND], m[SculptSlider.BUTT_ROUND])
        // the balance (Mirror off): her left above, her right below, within -1..1
        val b = BodySculpt().with(SculptSlider.BUST_DROOP, 0.5f).balanced(SculptSlider.BUST_DROOP, 0.8f)
        assertEquals(1f, b.on(SculptSlider.BUST_DROOP, BodySide.LEFT), 1e-6f); assertEquals(0f, b.on(SculptSlider.BUST_DROOP, BodySide.RIGHT), 1e-6f)
        assertEquals(0f, BodySculpt().with(SculptSlider.CHEEK_GAP, 0.5f).balanced(SculptSlider.CHEEK_GAP, 0.5f).side(SculptSlider.CHEEK_GAP))   // (no sides)
        assertNull(BustButtDesigner.presetOf(sided, DesignerGroup.BUTT))
    }

    @Test fun aGlideIsOneUndo() {
        // the screen pushes once when a glide ends (ui/CasPanels.kt: onSculpt done), not per frame
        val h = CasHistory(BodySculpt())
        val to = BustButtDesigner.applyPreset(BodySculpt(), ShapePreset.CURVY, DesignerGroup.BUST)
        var t = 0f
        while (t < 1f) { BustButtDesigner.tween(BodySculpt(), to, t, DesignerGroup.BUST.sliders); t += 0.05f }
        h.push(BustButtDesigner.tween(BodySculpt(), to, 1f, DesignerGroup.BUST.sliders))
        assertEquals(2, h.size); assertEquals(BodySculpt(), h.undo())
    }
}
