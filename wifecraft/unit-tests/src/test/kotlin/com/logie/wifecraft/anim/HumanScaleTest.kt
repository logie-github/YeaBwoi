package com.logie.wifecraft.anim

import com.logie.wifecraft.simulation.PairFit
import com.logie.wifecraft.simulation.PartnerMath
import com.logie.wifecraft.simulation.PartnerPair
import com.logie.wifecraft.simulation.PartnerSlot
import com.logie.wifecraft.simulation.PlaybackRole
import com.logie.wifecraft.simulation.SceneSimulation
import com.logie.wifecraft.simulation.SpeechLayout
import com.logie.wifecraft.story.Vec3
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HumanScaleTest {
    // standing heights engine-test measured (humanscale_test: the Head joint, the mean of the two stances), model units
    private val gnomeMan = 0.869f
    private val orcMan = 2.131f
    private val taurenMan = 2.891f
    private val nightElfWoman = 2.042f

    @Test fun everyRaceStandsAsTallAsAHumanOfItsSex() {
        assertEquals(1f, HumanScale.scale(HumanScale.MALE_HEIGHT, female = false), 1e-6f)
        assertEquals(1f, HumanScale.scale(HumanScale.FEMALE_HEIGHT, female = true), 1e-6f)
        for (h in listOf(gnomeMan, orcMan, taurenMan)) {
            assertEquals(HumanScale.MALE_HEIGHT, h * HumanScale.scale(h, female = false), 1e-4f)
        }
        assertEquals(HumanScale.FEMALE_HEIGHT, nightElfWoman * HumanScale.scale(nightElfWoman, female = true), 1e-4f)
        assertEquals(2.131f, HumanScale.scale(gnomeMan, false), 2e-3f)    // the maths: a gnome would be twice its size (it keeps its own)
        assertEquals(0.869f, HumanScale.scale(orcMan, false), 2e-3f)
        assertTrue(HumanScale.MALE_HEIGHT > HumanScale.FEMALE_HEIGHT)
    }

    @Test fun aModelThatCantBeMeasuredKeepsItsOwnSize() {
        assertEquals(1f, HumanScale.scale(0f, false))                     // no humanoid skeleton: the engine says 0
        assertEquals(1f, HumanScale.scale(Float.NaN, true))
        assertEquals(1f, HumanScale.scale(-1f, true))
        assertEquals(1f, HumanScale.scale(40f, false))
        assertEquals(HumanScale.MAX_SCALE, HumanScale.scale(0.2f, false))  // measured, but tiny: held at the bound
    }

    @Test fun onlyTheLiveSceneAndTheGhostAreAtHumanScale() {
        assertTrue(HumanScale.applies("3f2a:npc:actor-1"))
        assertTrue(HumanScale.applies(HumanScale.GHOST_KEY))
        assertFalse(HumanScale.applies("npc-preview"))                    // the character creator
        assertFalse(HumanScale.applies("creator"))
        assertFalse(HumanScale.applies("animation-preview:5d0c"))         // the animation library's preview
    }

    @Test fun aPairsSlotsAndItsRootMotionShareOneScale() {
        // a library pair 0.9 reference units apart: at human scale its two stand 0.9 x PAIR yards apart, the lift kept
        val a = Anim("x", "x", "Partner", 2f, true, emptyList(), "s:x", 0L)
        val pair = PairAnim("p", "Hold hands", "Partner", a, a.copy(id = "y", source = "s:y"),
            PairOffset(-0.45f, 0f, 0.1f, 0f), PairOffset(0.45f, 0f, 0f, 180f), true, emptySet(), emptyList(), true, "s:x+s:y", 0L)
        val p = PartnerPair.of(pair)
        assertEquals(PartnerSlot(-0.45f * HumanScale.PAIR, 0f, 0.1f, 0f), p.slotA)
        val out = FloatArray(4); val other = FloatArray(4)
        PartnerMath.place(3f, 4f, 0f, 1.1f, p.slotA, out)
        PartnerMath.place(3f, 4f, 0f, 1.1f, p.slotB, other)
        assertEquals(0.9f * HumanScale.PAIR, hypot(out[0] - other[0], out[1] - other[1]), 1e-5f)
        // the engine's root motion across the floor: a clip's step of s reference units moves any body s x PAIR yards
        // (the model-space step PAIR / scale, times the actor's scale): a gnome and an orc step alike
        for (scale in listOf(HumanScale.scale(gnomeMan, false), HumanScale.scale(orcMan, false))) {
            val modelStep = 0.3f * HumanScale.PAIR / scale
            assertEquals(0.3f * HumanScale.PAIR, modelStep * scale, 1e-6f)
        }
        // the old way: each body's own Root height over the reference's (1.4407) times its size: a gnome's step was a third
        // of an orc's (0.480 and 1.440 model units at sizes 1), the slots unscaled
        val gnomeOld = 0.3f * 0.480f / 1.4407f
        val orcOld = 0.3f * 1.440f / 1.4407f
        assertTrue(gnomeOld < orcOld * 0.4f)
    }

    @Test fun pairScaleIsTheHumansPelvisOverTheReferences() {
        // the human man's and woman's Root heights in the clips' stance (engine-test), over Retarget's reference 1.4407
        assertEquals((1.1299f + 1.2158f) / 2f / 1.4407f, HumanScale.PAIR, 2e-4f)
    }

    @Test fun speechBubblesHangAtOneHeightPerSex() {
        assertEquals(HumanScale.HEAD_MALE, SpeechLayout.humanHead(female = false, sitting = false, lying = false))
        assertEquals(HumanScale.HEAD_FEMALE, SpeechLayout.humanHead(female = true, sitting = false, lying = false))
        assertEquals(SpeechLayout.head(HumanScale.HEAD_MALE, sitting = true, lying = false),
            SpeechLayout.humanHead(female = false, sitting = true, lying = false))
        assertTrue(SpeechLayout.humanHead(true, sitting = false, lying = true) < SpeechLayout.humanHead(true, sitting = true, lying = false))
        // above the head joint of a human of that sex (1.848, 1.712), below twice it
        assertTrue(HumanScale.HEAD_MALE in 1.85f..2.2f)
        assertTrue(HumanScale.HEAD_FEMALE in 1.72f..2.05f)
    }

    // ChrRaces ids
    private val human = 1; private val orc = 2; private val dwarf = 3; private val gnome = 7; private val goblin = 9
    private val darkIron = 34; private val vulpera = 35; private val mechagnome = 37; private val earthenH = 84; private val earthenA = 85

    @Test fun dwarvesAndGnomesKeepTheirOwnSizeByRace() {
        for (r in listOf(dwarf, darkIron, earthenH, earthenA, gnome, mechagnome)) assertTrue(HumanScale.keepsOwnSize(r), "race $r")
        for (r in listOf(human, orc, 4, 5, 6, 8, goblin, 10, 11, 22, 24, 31, 32, vulpera, 52)) assertFalse(HumanScale.keepsOwnSize(r), "race $r")
        // their size over a human's of the same sex, as engine-test measured them (standing heights over the humans')
        assertEquals(1.4250f / HumanScale.MALE_HEIGHT, HumanScale.bodyScale(dwarf, false), 2e-4f)
        assertEquals(1.3199f / HumanScale.FEMALE_HEIGHT, HumanScale.bodyScale(dwarf, true), 2e-4f)
        assertEquals(0.8688f / HumanScale.MALE_HEIGHT, HumanScale.bodyScale(gnome, false), 2e-4f)
        assertEquals(0.8100f / HumanScale.FEMALE_HEIGHT, HumanScale.bodyScale(gnome, true), 2e-4f)
        assertEquals(HumanScale.bodyScale(gnome, true), HumanScale.bodyScale(mechagnome, true))   // the gnome's skeleton
        assertEquals(HumanScale.bodyScale(dwarf, false), HumanScale.bodyScale(darkIron, false))
        assertEquals(HumanScale.bodyScale(dwarf, false), HumanScale.bodyScale(earthenA, false))
        // everyone else stands a human's height: 1 (the orc, the goblin, the vulpera are scaled to it)
        for (r in listOf(human, orc, goblin, vulpera)) for (f in listOf(false, true)) assertEquals(1f, HumanScale.bodyScale(r, f))
        // a gnome is about half a human, a dwarf about three quarters
        assertTrue(HumanScale.bodyScale(gnome, false) in 0.45f..0.5f)
        assertTrue(HumanScale.bodyScale(dwarf, true) in 0.75f..0.8f)
    }

    @Test fun onlyGnomesArePositionedForTheirSize() {
        assertEquals(HumanScale.bodyScale(gnome, false), HumanScale.pairScale(gnome, false))
        assertEquals(HumanScale.bodyScale(mechagnome, true), HumanScale.pairScale(mechagnome, true))
        for (r in listOf(dwarf, darkIron, earthenA, human, orc)) assertEquals(1f, HumanScale.pairScale(r, false), "race $r")
    }

    @Test fun aDwarfsAndAGnomesBubbleHangsAtTheirOwnHead() {
        assertEquals(HumanScale.HEAD_MALE, HumanScale.head(orc, false))
        assertEquals(HumanScale.HEAD_FEMALE, HumanScale.head(goblin, true))
        assertEquals(HumanScale.HEAD_GNOME_FEMALE, HumanScale.head(mechagnome, true))
        assertEquals(HumanScale.HEAD_DWARF_MALE, HumanScale.head(darkIron, false))
        // above the skull's top engine-test measured (gnome 1.156 / 1.107, dwarf 1.559 / 1.506), well under a human's
        assertTrue(HumanScale.HEAD_GNOME_MALE in 1.156f..1.25f && HumanScale.HEAD_GNOME_FEMALE in 1.107f..1.2f)
        assertTrue(HumanScale.HEAD_DWARF_MALE in 1.559f..1.65f && HumanScale.HEAD_DWARF_FEMALE in 1.506f..1.6f)
        assertEquals(SpeechLayout.head(HumanScale.HEAD_GNOME_MALE, sitting = true, lying = false),
            SpeechLayout.humanHead(gnome, female = false, sitting = true, lying = false))
    }

    @Test fun aGnomesSlotComesTowardItsPartnerByItsSize() {
        // a library pair 0.757 reference units each side (engine-test's partner pose), at human scale +-0.616 yards
        val a = PartnerSlot.human(-0.757f, 0f, 0.1f, 0f)
        val b = PartnerSlot.human(0.757f, 0f, 0f, 180f)
        val g = HumanScale.pairScale(gnome, false)
        val (ga, hb) = PairFit.slots(a, b, g, 1f)
        assertEquals(b, hb)                                               // the human partner stays on its slot
        assertEquals(-0.757f * HumanScale.PAIR * g, ga.x, 1e-5f)          // the gnome at its size's share of the way
        assertEquals(0.1f, ga.z)                                          // the lift is the clip's (its own pelvis takes it)
        assertEquals(0f, ga.yawDeg)
        assertEquals(0.616f * (1f + g), hb.x - ga.x, 2e-3f)               // 0.905 yards apart instead of 1.232
        // the contact point is the slots' midpoint, wherever the pair puts it
        val (sa, sb) = PairFit.slots(PartnerSlot(0.2f, 0.4f, 0f, 0f), PartnerSlot(1.0f, 0.4f, 0f, 180f), 1f, 0.5f)
        assertEquals(0.2f, sa.x); assertEquals(0.8f, sb.x, 1e-6f); assertEquals(0.4f, sb.y, 1e-6f)
        // two gnomes: the whole pair a gnome-sized copy about its middle
        val (g1, g2) = PairFit.slots(a, b, g, g)
        assertEquals((b.x - a.x) * g, g2.x - g1.x, 1e-5f)
        // humans, orcs, dwarves: nothing moves (the same objects back)
        val same = PairFit.slots(a, b, 1f, HumanScale.pairScale(dwarf, true))
        assertTrue(same.first === a && same.second === b)
    }

    @Test fun anOrderedPerformanceAndAConversationFitTheGnome() {
        val g = HumanScale.pairScale(gnome, true)
        val roles = listOf(PlaybackRole("x:0", Vec3(0.5f, 0f, 0.2f), 3.14f), PlaybackRole("x:1", Vec3(-0.5f, 0.1f, 0f), 0f))
        val fitted = PairFit.roles(roles, floatArrayOf(1f, g))
        assertEquals(roles[0], fitted[0])
        assertEquals(-0.5f * g, fitted[1].offset.x, 1e-6f)
        assertEquals(0.05f + 0.05f * g, fitted[1].offset.y, 1e-6f)        // toward the midpoint's y (0.05)
        assertEquals(0f, fitted[1].offset.z); assertEquals("x:1", fitted[1].clip); assertEquals(0f, fitted[1].facing)
        assertTrue(PairFit.roles(roles, floatArrayOf(1f, 1f)) === roles)
        // one alone: about the anchor
        val solo = PairFit.roles(listOf(roles[0]), floatArrayOf(g))
        assertEquals(0.5f * g, solo[0].offset.x, 1e-6f); assertEquals(0.2f, solo[0].offset.z)
        // talking: a human and a gnome stand nearer than two humans, two gnomes nearer still
        val chat = SceneSimulation.CHAT_GAP
        assertEquals(chat, PairFit.gap(chat, 1f, 1f))
        assertEquals(chat * 0.5f * (1f + g), PairFit.gap(chat, 1f, g), 1e-6f)
        assertTrue(PairFit.gap(chat, g, g) < PairFit.gap(chat, 1f, g))
    }
}
