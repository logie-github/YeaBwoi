package com.logie.wifecraft.anim

import com.logie.wifecraft.simulation.CinematicFraming
import com.logie.wifecraft.simulation.CinematicStage
import com.logie.wifecraft.simulation.SceneSimulation
import com.logie.wifecraft.simulation.SpeechLayout
import com.logie.wifecraft.simulation.RoleDelta
import com.logie.wifecraft.story.Vec3
import kotlin.test.*

/** Options' Character size: the setting (default, range, what is kept), what it scales and what it leaves. */
class CharacterSizeTest {
    @Test fun theDefaultIsMatchTheRoomAndTheRangeIs80To180() {
        assertEquals(125, CharacterSize.MATCH_ROOM)
        assertEquals(125, CharacterSize.parse(null))
        assertEquals(125, CharacterSize.parse(""))
        assertEquals(125, CharacterSize.parse("big"))
        assertEquals(80, CharacterSize.MIN); assertEquals(180, CharacterSize.MAX)
        assertEquals(80, CharacterSize.clamp(10)); assertEquals(180, CharacterSize.clamp(900))
        assertEquals(80, CharacterSize.parse("20")); assertEquals(180, CharacterSize.parse("999"))
        assertTrue(CharacterSize.isMatch(125)); assertFalse(CharacterSize.isMatch(100))
        assertEquals(1.25f, CharacterSize.factor(125), 1e-6f)
        assertEquals(0.8f, CharacterSize.factor(10), 1e-6f)
    }

    @Test fun aKeptPercentComesBackAsItWas() {
        for (p in CharacterSize.MIN..CharacterSize.MAX step CharacterSize.STEP) assertEquals(p, CharacterSize.parse(CharacterSize.format(p)))
        assertEquals(100, CharacterSize.parse(" 100 "))
        // out of range is kept within it
        assertEquals("180", CharacterSize.format(500))
    }

    @Test fun nothingIsInForceUntilTheAppSetsItAndWithRestoresWhatWas() {
        assertEquals(1f, CharacterSize.active)
        CharacterSize.with(1.5f) {
            assertEquals(1.5f, CharacterSize.active)
            CharacterSize.with(0.9f) { assertEquals(0.9f, CharacterSize.active) }
            assertEquals(1.5f, CharacterSize.active)
        }
        assertEquals(1f, CharacterSize.active)
        try { CharacterSize.with(1.7f) { error("boom") } } catch (e: IllegalStateException) { }
        assertEquals(1f, CharacterSize.active)
        CharacterSize.force(Float.NaN); assertEquals(1f, CharacterSize.active)
        CharacterSize.apply(125); assertEquals(1.25f, CharacterSize.active, 1e-6f)
        CharacterSize.apply(100); assertEquals(1f, CharacterSize.active)
    }

    @Test fun thePairFrameTheBodyUnitAndTheBubblesGrowWithTheFactorAndNothingElseOfTheirs() {
        val base = SceneSimulation.HUMAN_BODY_UNIT
        for (f in listOf(0.8f, 1f, 1.3f, 1.8f)) CharacterSize.with(f) {
            assertEquals(HumanScale.PAIR_BASE * f, HumanScale.PAIR, 1e-6f)
            assertEquals(base * f, SceneSimulation.HUMAN_BODY_UNIT, 1e-4f)
            assertEquals(HumanScale.HEAD_FEMALE * f, HumanScale.head(true), 1e-5f)
            assertEquals(HumanScale.HEAD_MALE * f, HumanScale.head(false), 1e-5f)
            // a dwarf's and a gnome's bubble keep their size next to a human's
            assertEquals(HumanScale.HEAD_DWARF_MALE * f, HumanScale.head(3, false), 1e-5f)
            assertEquals(HumanScale.HEAD_GNOME_FEMALE * f, HumanScale.head(7, true), 1e-5f)
            assertEquals(HumanScale.head(true) * (HumanScale.HEAD_GNOME_FEMALE / HumanScale.HEAD_FEMALE), HumanScale.head(7, true), 1e-4f)
            // the race ratios are the same at every factor (the factor is on top of them)
            assertEquals(HumanScale.DWARF_MALE, HumanScale.bodyScale(3, false)); assertEquals(1f, HumanScale.bodyScale(1, true))
        }
        assertEquals(base, SceneSimulation.HUMAN_BODY_UNIT, 1e-6f)
        assertEquals(0.8139f, HumanScale.PAIR, 1e-6f)
    }

    @Test fun theSpeechBubblesHangHigherByTheFactor() {
        val at1 = SpeechLayout.humanHead(true, sitting = false, lying = false)
        val at13 = CharacterSize.with(1.3f) { SpeechLayout.humanHead(true, sitting = false, lying = false) }
        assertTrue(at13 > at1 * 1.2f, "$at13 vs $at1")
    }

    @Test fun theCinematicCamerasBodyHeightAndSpheresGrowWithTheBody() {
        assertEquals(1.8f, CinematicStage(2).bodyHeight, 1e-6f)
        assertEquals(1.8f * 1.3f, CharacterSize.with(1.3f) { CinematicStage(2).bodyHeight }, 1e-5f)
        val stage = CinematicStage(2).also { it.bodyHeight = 2.5f }
        assertEquals(2.5f, stage.bodyHeight)   // (what a caller sets is kept)
        // the share of the picture a body fills is what it was when the body, the eye and the target all grow by the factor
        val hip = Vec3(0f, 0f, 1.2f); val head = Vec3(0f, 0f, 1.9f)
        val eye = Vec3(3f, 0f, 1.6f); val at = Vec3(0f, 0f, 1.5f)
        val same = CinematicFraming.coverage(eye, at, 40f, 1.78f, hip, head)
        assertTrue(same in 0.01f..0.9f, "$same")
        for (f in listOf(0.8f, 1.3f, 1.8f)) {
            fun s(v: Vec3) = Vec3(v.x * f, v.y * f, v.z * f)
            val scaled = CharacterSize.with(f) { CinematicFraming.coverage(s(eye), s(at), 40f, 1.78f, s(hip), s(head)) }
            assertEquals(same, scaled, 0.03f, "factor $f")
        }
    }

    @Test fun aRememberedOffsetIsKeptAtSize1AndLaidOnTimesTheFactor() {
        val d = RoleDelta(0.3f, -0.2f, 0.1f, 25f)
        assertSame(d, d.scaled(1f))
        val s = d.scaled(1.3f)
        assertEquals(0.39f, s.dx, 1e-6f); assertEquals(-0.26f, s.dy, 1e-6f); assertEquals(0.13f, s.dz, 1e-6f); assertEquals(25f, s.dyawDeg)
        val back = s.scaled(1f / 1.3f)
        assertEquals(d.dx, back.dx, 1e-6f); assertEquals(d.dy, back.dy, 1e-6f); assertEquals(d.dz, back.dz, 1e-6f)
    }
}
