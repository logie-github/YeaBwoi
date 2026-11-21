package com.logie.wifecraft.creator

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The full-body sculpt's model: the sliders and their parts, the sides (Symmetry off), saves old and new, the drag handles. */
class SculptModelTest {
    // ---- the sliders

    @Test fun slidersKeepTheirPlacesAndAddTheirOwn() {
        // the first eleven where the engine and older saves have them (sculpt.h's SculptSlider), the new ones after
        val first = listOf("height", "shoulders", "arms", "bust", "bustLift", "waist", "belly", "hips", "butt", "thighs", "legLength")
        assertEquals(first, SculptSlider.entries.take(11).map { it.key })
        assertEquals(listOf("neck", "chest", "bustWidth", "bustSpacing", "shoulderSlope", "upperArms", "forearms", "calves", "buttLift", "bodyFat", "muscle"),
            SculptSlider.entries.drop(11).map { it.key })
        assertEquals(listOf("Height", "Shoulders", "Arms", "Bust Size", "Bust Lift", "Waist", "Belly", "Hips", "Butt", "Thighs", "Leg Length"),
            SculptSlider.entries.take(11).map { it.label })
        assertEquals(SculptSlider.entries.size, SculptSlider.entries.map { it.key }.toSet().size)
        assertFalse(SculptSlider.entries.any { it.key == BodySculpt.SIDES_KEY })
        // the skeleton's two are not the mesh's, and neither has sides
        assertEquals(setOf(SculptSlider.HEIGHT, SculptSlider.LEG_LENGTH), SculptSlider.entries.filter { !it.mesh }.toSet())
        assertFalse(SculptSlider.HEIGHT.sided || SculptSlider.LEG_LENGTH.sided || SculptSlider.BODY_FAT.sided || SculptSlider.MUSCLE.sided)
    }

    @Test fun rangesAndDefaults() {
        val s = BodySculpt()
        for (k in SculptSlider.entries) { assertEquals(0f, s[k]); assertEquals(0f, s.side(k)) }   // neutral at 0
        assertTrue(s.isNeutral)
        for (k in SculptSlider.entries) {
            assertEquals(1f, s.with(k, 5f)[k]); assertEquals(-1f, s.with(k, -5f)[k]); assertEquals(0f, s.with(k, Float.NaN)[k])
            assertEquals(0.33f, s.with(k, 0.333f)[k])
        }
        // the array: the sliders in order, then each side balance (sculpt.h kSculptValues), zeros for a neutral body
        val a = s.with(SculptSlider.CALVES, 0.4f).withSide(SculptSlider.HIPS, BodySide.LEFT, 0.6f).toArray()
        val n = SculptSlider.entries.size
        assertEquals(2 * n, a.size)
        assertEquals(0.4f, a[SculptSlider.CALVES.ordinal])
        assertEquals(0.3f, a[SculptSlider.HIPS.ordinal], 1e-6f)
        assertEquals(0.3f, a[n + SculptSlider.HIPS.ordinal], 1e-6f)
        assertTrue(BodySculpt().toArray().all { it == 0f })
    }

    @Test fun everyPartHasItsSliders() {
        for (k in SculptSlider.entries) assertTrue(k in k.part.sliders(true) && k in k.part.sliders(false), "$k in ${k.part}")
        assertEquals(SculptSlider.entries.size, SculptPart.entries.sumOf { it.sliders().size })
        assertEquals(SculptSlider.BUST_SIZE, SculptPart.CHEST.sliders(female = true).first())
        assertEquals(SculptSlider.CHEST, SculptPart.CHEST.sliders(female = false).first())   // a man's chest first
        // the tap-to-edit's regions to parts
        assertEquals(SculptPart.CHEST, SculptPart.of(BodyRegion.CHEST)); assertEquals(SculptPart.HIPS, SculptPart.of(BodyRegion.HIPS))
        assertEquals(SculptPart.LEGS, SculptPart.of(BodyRegion.LEGS)); assertEquals(SculptPart.ARMS, SculptPart.of(BodyRegion.ARMS))
        assertEquals(SculptPart.WAIST, SculptPart.of(BodyRegion.WAIST)); assertEquals(SculptPart.NECK, SculptPart.of(BodyRegion.NECK))
        for (r in listOf(BodyRegion.HEAD, BodyRegion.HAIR, BodyRegion.HANDS, BodyRegion.FEET, BodyRegion.NONE)) assertNull(SculptPart.of(r))
        assertEquals(SculptPart.HIPS.sliders(), CasTabs.slidersFor(BodyRegion.HIPS))
        assertTrue(CasTabs.slidersFor(BodyRegion.HEAD).isEmpty())
    }

    // ---- sides

    @Test fun oneSideAlone() {
        val s = BodySculpt().withSide(SculptSlider.HIPS, BodySide.LEFT, 0.6f)
        assertEquals(0.6f, s.on(SculptSlider.HIPS, BodySide.LEFT), 1e-6f)
        assertEquals(0f, s.on(SculptSlider.HIPS, BodySide.RIGHT), 1e-6f)
        assertTrue(s.isSided)
        // then the right to -0.2: the left kept
        val t = s.withSide(SculptSlider.HIPS, BodySide.RIGHT, -0.2f)
        assertEquals(0.6f, t.on(SculptSlider.HIPS, BodySide.LEFT), 0.011f)
        assertEquals(-0.2f, t.on(SculptSlider.HIPS, BodySide.RIGHT), 0.011f)
        // both sides together (Symmetry on) keep the difference, within -1..1
        val u = t.with(SculptSlider.HIPS, 0.8f)
        assertTrue(u.on(SculptSlider.HIPS, BodySide.LEFT) <= 1f && u.on(SculptSlider.HIPS, BodySide.RIGHT) >= -1f)
        assertTrue(u.on(SculptSlider.HIPS, BodySide.LEFT) > u.on(SculptSlider.HIPS, BodySide.RIGHT))
        // made alike again
        assertFalse(t.even(SculptSlider.HIPS).isSided)
        // a slider without sides takes both
        val b = BodySculpt().withSide(SculptSlider.BELLY, BodySide.LEFT, 0.5f)
        assertEquals(0.5f, b[SculptSlider.BELLY]); assertFalse(b.isSided)
    }

    // ---- saves

    @Test fun savesOldAndNew() {
        // a look saved before (the first eleven only, no sides) reads back exactly as it was
        val old = JSONObject("""{"bust":0.35,"hips":0.4,"height":-0.1,"legLength":0.2}""")
        val s = BodySculpt.fromJson(old)
        assertNotNull(s)
        assertEquals(0.35f, s[SculptSlider.BUST_SIZE]); assertEquals(0.4f, s[SculptSlider.HIPS]); assertEquals(-0.1f, s[SculptSlider.HEIGHT])
        assertFalse(s.isSided)
        assertEquals(old.keys().asSequence().toSet(), s.toJson().keys().asSequence().toSet())   // (written back with the same four keys)
        assertFalse(s.toJson().has(BodySculpt.SIDES_KEY))
        // new sliders and sides, saved and read back equal
        val n = BodySculpt().with(SculptSlider.CALVES, -0.3f).with(SculptSlider.MUSCLE, 0.7f).withSide(SculptSlider.BUST_WIDTH, BodySide.RIGHT, 0.5f)
        val j = n.toJson()
        assertTrue(j.has("calves") && j.has("muscle") && j.getJSONObject(BodySculpt.SIDES_KEY).has("bustWidth"))
        assertEquals(n, BodySculpt.fromJson(JSONObject(j.toString())))
        // in a whole appearance too
        val a = Appearance(1, Sex.FEMALE, mapOf(10 to 100), sculpt = n)
        assertEquals(n, Appearance.fromJson(JSONObject(a.toJson().toString())).sculpt)
        // nothing to save for a neutral body; unknown keys ignored
        assertNull(BodySculpt.fromJson(JSONObject("""{"sides":{},"wings":0.5}""")))
    }

    // ---- drag handles

    @Test fun dragAxesPerPart() {
        for (p in SculptPart.entries) for (female in listOf(true, false)) for (lift in listOf(false, true)) {
            val a = SculptDrag.axes(p, female, lift)
            assertTrue(a.vertical != null || a.horizontal != null, "$p $female $lift")
        }
        assertEquals(SculptDrag.Axes(SculptSlider.BUST_SIZE, SculptSlider.BUST_WIDTH), SculptDrag.axes(SculptPart.CHEST, female = true, lift = false))
        assertEquals(SculptDrag.Axes(SculptSlider.BUST_LIFT, SculptSlider.BUST_SPACING), SculptDrag.axes(SculptPart.CHEST, female = true, lift = true))
        assertEquals(SculptSlider.CHEST, SculptDrag.axes(SculptPart.CHEST, female = false, lift = false).vertical)
        assertEquals(SculptDrag.Axes(SculptSlider.BUTT, SculptSlider.HIPS), SculptDrag.axes(SculptPart.HIPS, true, false))
        assertEquals(SculptSlider.BUTT_LIFT, SculptDrag.axes(SculptPart.HIPS, true, true).vertical)
        assertEquals(SculptDrag.Axes(SculptSlider.BELLY, SculptSlider.WAIST), SculptDrag.axes(SculptPart.WAIST, true, false))
        assertEquals(SculptDrag.Axes(SculptSlider.THIGHS, SculptSlider.CALVES), SculptDrag.axes(SculptPart.LEGS, true, false))
    }

    @Test fun dragToValue() {
        // half the stage's height sweeps -1..1: a quarter of it up from 0 is +1, an eighth +0.5; down the other way; clamped
        assertEquals(1f, SculptDrag.valueFor(0f, 250f, 1000f))
        assertEquals(0.5f, SculptDrag.valueFor(0f, 125f, 1000f))
        assertEquals(-0.5f, SculptDrag.valueFor(0f, -125f, 1000f))
        assertEquals(1f, SculptDrag.valueFor(0.8f, 400f, 1000f))
        assertEquals(0.3f, SculptDrag.valueFor(0.3f, Float.NaN, 1000f))
        assertEquals(0.3f, SculptDrag.valueFor(0.3f, 50f, 0f))
        assertEquals(BodySide.LEFT, SculptDrag.sideOf(4 or (1 shl 8))); assertEquals(BodySide.RIGHT, SculptDrag.sideOf(6 or (2 shl 8)))
        assertNull(SculptDrag.sideOf(4))
        assertEquals(BodyRegion.CHEST, BodyRegion.of(4 or (1 shl 8)))   // the region with the side's bits on it
        assertEquals("Bust Size +35", SculptDrag.label(SculptSlider.BUST_SIZE, 0.35f, null))
        assertEquals("Hips -20 · left", SculptDrag.label(SculptSlider.HIPS, -0.2f, BodySide.LEFT))
    }

    @Test fun aDragOnTheBody() {
        val start = BodySculpt().with(SculptSlider.BUST_SIZE, 0.2f)
        val d = SculptDragSession(start, SculptPart.CHEST, female = true, lift = false, side = null, stagePx = 1000f, slopPx = 12f)
        assertFalse(d.move(3f, -5f))                     // within the slop: nothing yet
        assertNull(d.slider); assertNull(d.label)
        assertTrue(d.move(4f, -100f))                    // up: the bust's size
        assertEquals(SculptSlider.BUST_SIZE, d.slider)
        assertEquals(0.6f, d.sculpt[SculptSlider.BUST_SIZE], 1e-6f)
        assertEquals("Bust Size +60", d.label)
        d.move(200f, -100f)                              // the axis stays the drag's: sideways now does nothing to the width
        assertEquals(0f, d.sculpt[SculptSlider.BUST_WIDTH])
        d.move(0f, 300f)                                 // down past 0: a tick
        assertTrue(d.sculpt[SculptSlider.BUST_SIZE] < 0f); assertTrue(d.tick)
        d.move(0f, 2000f)                                // to the end: clamped
        assertEquals(-1f, d.sculpt[SculptSlider.BUST_SIZE])
        // sideways first: the width; a second finger: the lift from there
        val w = SculptDragSession(BodySculpt(), SculptPart.CHEST, true, false, null, 1000f, 12f)
        w.move(125f, 2f)
        assertEquals(SculptSlider.BUST_WIDTH, w.slider); assertEquals(0.5f, w.sculpt[SculptSlider.BUST_WIDTH])
        w.liftMode(true, 125f, 2f)
        w.move(125f, -123f)
        assertEquals(SculptSlider.BUST_LIFT, w.slider); assertEquals(0.5f, w.sculpt[SculptSlider.BUST_LIFT])
        assertEquals(0.5f, w.sculpt[SculptSlider.BUST_WIDTH])   // (what the first axis did is kept)
        // a part with one direction only takes it whichever way the finger first goes
        val h = SculptDragSession(BodySculpt(), SculptPart.HIPS, true, true, null, 1000f, 12f)
        h.move(40f, -5f)
        assertEquals(SculptSlider.BUTT_LIFT, h.slider)
        // the shoulders' slope: up squares them (less slope)
        val sl = SculptDragSession(BodySculpt(), SculptPart.SHOULDERS, true, false, null, 1000f, 12f)
        sl.move(0f, -125f)
        assertEquals(-0.5f, sl.sculpt[SculptSlider.SHOULDER_SLOPE])
    }

    @Test fun aOneSidedDrag() {
        val d = SculptDragSession(BodySculpt(), SculptPart.HIPS, true, false, BodySide.RIGHT, 1000f, 12f)
        d.move(250f, 0f)                                 // right: the hips' width, her right side alone
        assertEquals(SculptSlider.HIPS, d.slider)
        assertEquals(1f, d.sculpt.on(SculptSlider.HIPS, BodySide.RIGHT), 0.011f)
        assertEquals(0f, d.sculpt.on(SculptSlider.HIPS, BodySide.LEFT), 0.011f)
        assertEquals("Hips +100 · right", d.label)
        // a slider without sides moves both even with a side touched
        val b = SculptDragSession(BodySculpt(), SculptPart.WAIST, true, false, BodySide.LEFT, 1000f, 12f)
        b.move(0f, -125f)
        assertEquals(0.5f, b.sculpt[SculptSlider.BELLY]); assertFalse(b.sculpt.isSided)
    }

    @Test fun eachDragIsOneUndo() {
        val h = CasHistory(BodySculpt())
        var now = BodySculpt()
        // a drag: many moves, one push at its end (as the screen does)
        val d = SculptDragSession(now, SculptPart.WAIST, true, false, null, 1000f, 12f)
        for (y in 0..200 step 10) d.move(0f, -y.toFloat())
        now = d.sculpt; h.push(now)
        assertEquals(2, h.size)
        val e = SculptDragSession(now, SculptPart.HIPS, true, false, null, 1000f, 12f)
        e.move(100f, 0f); now = e.sculpt; h.push(now)
        assertEquals(now, h.current)
        assertEquals(d.sculpt, h.undo())
        assertEquals(BodySculpt(), h.undo())
        assertEquals(d.sculpt, h.redo())
        // reset a region, reset all
        val all = e.sculpt.with(SculptSlider.NECK, 0.3f)
        assertEquals(0f, all.reset(SculptPart.HIPS)[SculptSlider.HIPS]); assertEquals(0.3f, all.reset(SculptPart.HIPS)[SculptSlider.NECK])
        assertTrue(all.reset(SculptPart.HIPS).reset(SculptPart.WAIST).reset(SculptPart.NECK).isNeutral)
    }
}
