package com.logie.wifecraft.simulation

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Immersive mode's pure parts (simulation/Immersion.kt): settings and their migration, intensity, the HUD's timing, the mix, the sway. */
class ImmersionTest {
    @Test
    fun defaultsAreImmersive() {
        val d = ImmersiveSettings.DEFAULT
        assertFalse(d.lifeHud, "the life HUD is hidden by default")
        assertEquals(4, d.hudSeconds)
        assertFalse(d.autoFlow)
        assertTrue(d.eyeContact)
        assertFalse(d.particlesOn(false))
        assertTrue(d.particlesOn(true), "motes come with cinematic mode")
    }

    @Test
    fun migrationTurnsImmersiveOnOnceAndKeepsChoicesAfter() {
        // nothing kept (an existing player before this version, or a new one): immersive on, the life HUD hidden
        val first = ImmersiveSettings.migrate(null)!!
        assertEquals(ImmersiveSettings.VERSION, JSONObject(first).getInt("version"))
        assertFalse(ImmersiveSettings.parse(first).lifeHud)
        // an older version's text (the life HUD on) is moved over too
        val old = JSONObject().put("lifeHud", true).toString()
        assertFalse(ImmersiveSettings.parse(old).lifeHud)
        assertTrue(ImmersiveSettings.migrate(old) != null)
        // once current, the player's own choices stay (no second migration)
        val mine = ImmersiveSettings.DEFAULT.copy(lifeHud = true, breathing = 30, autoFlow = true).toJson().toString()
        assertNull(ImmersiveSettings.migrate(mine))
        val back = ImmersiveSettings.parse(mine)
        assertTrue(back.lifeHud); assertEquals(30, back.breathing); assertTrue(back.autoFlow)
        // broken text: the defaults; out of range: clamped
        assertEquals(ImmersiveSettings.DEFAULT, ImmersiveSettings.parse("{nope"))
        val wild = JSONObject(mine).put("hudSeconds", 99).put("voice", -5).toString()
        assertEquals(ImmersiveSettings.HUD_MAX_S, ImmersiveSettings.parse(wild).hudSeconds)
        assertEquals(0, ImmersiveSettings.parse(wild).voice)
    }

    @Test
    fun intensityClimbsWithTheStages() {
        assertEquals(IntensityModel.IDLE, IntensityModel.of(MomentProgress.IDLE))
        assertEquals(IntensityModel.SOLO, IntensityModel.of(MomentProgress(playing = true)))
        val s1 = IntensityModel.of(MomentProgress(true, partnered = true, stage = 1))
        val s3 = IntensityModel.of(MomentProgress(true, partnered = true, stage = 3))
        val s9 = IntensityModel.of(MomentProgress(true, partnered = true, stage = 9, pass = 9, seconds = 999f))
        assertTrue(s1 < s3 && s3 < s9, "$s1 < $s3 < $s9")
        assertTrue(s9 <= 1f)
        assertEquals(0.4f, s1, 1e-4f)
        // a start under its loop, an end easing back, later passes and time a little higher
        val start = IntensityModel.of(MomentProgress(true, true, 2, SegmentKind.START))
        val loop = IntensityModel.of(MomentProgress(true, true, 2, SegmentKind.LOOP))
        val end = IntensityModel.of(MomentProgress(true, true, 2, SegmentKind.END))
        assertTrue(start < loop && end < loop)
        assertTrue(IntensityModel.of(MomentProgress(true, true, 2, pass = 4)) > loop)
        // the flow's phases and the tempo
        assertEquals(IntensityModel.AFTERGLOW, IntensityModel.of(MomentProgress.IDLE, FlowPhase.AFTERGLOW, tempo = 2))
        assertEquals(IntensityModel.LEAD_IN, IntensityModel.of(MomentProgress.IDLE, FlowPhase.LEAD_IN))
        assertTrue(IntensityModel.of(MomentProgress(true, true, 1), FlowPhase.STAGE, 2) > s1)
        assertTrue(IntensityModel.of(MomentProgress(true, true, 1), FlowPhase.STAGE, -2) < s1)
        // every combination stays within 0..1
        for (st in 0..20) for (k in SegmentKind.entries) for (t in -5..5) for (ph in FlowPhase.entries) {
            val v = IntensityModel.of(MomentProgress(true, true, st, k, st, 5, st * 30f), ph, t)
            assertTrue(v in 0f..1f, "$v")
        }
    }

    @Test
    fun stageNumbersAreRead() {
        assertEquals(3, MomentProgress.stageOf("Stage 3 · Cowgirl"))
        assertEquals(12, MomentProgress.stageOf("author_pose_stage12_loop"))
        assertEquals(0, MomentProgress.stageOf("Make it rain"))
        assertEquals(0, MomentProgress.stageOf(null))
    }

    @Test
    fun intensityEasesRisingFasterThanFalling() {
        val e = IntensityEase(0.2f)
        repeat(60) { e.step(1f, 1f / 60f) }          // one second up
        val up = e.value - 0.2f
        val f = IntensityEase(1f)
        repeat(60) { f.step(0.2f, 1f / 60f) }         // one second down
        val down = 1f - f.value
        assertTrue(up > down, "rise $up, fall $down")
        assertTrue(e.value < 1f, "no jump")
        repeat(60 * 60) { e.step(1f, 1f / 60f) }
        assertEquals(1f, e.value, 1e-3f)
        // a huge or broken dt never overshoots
        val g = IntensityEase(0.2f)
        g.step(1f, 1e9f); assertTrue(g.value <= 1f)
        g.step(0.5f, Float.NaN); assertTrue(g.value in 0f..1f)
    }

    @Test
    fun hudShowsOnATapAndFadesAfterItsTime() {
        val h = HudReveal(4_000L)
        assertEquals(0f, h.alpha(0L), "hidden until a tap")
        h.tap(1_000L)
        assertEquals(1f, h.alpha(1_000L))
        assertEquals(1f, h.alpha(5_000L), "up for the hold time")
        val mid = h.alpha(5_000L + HudReveal.FADE_MS / 2)
        assertTrue(mid > 0.4f && mid < 0.6f, "$mid")
        assertEquals(0f, h.alpha(5_000L + HudReveal.FADE_MS))
        assertFalse(h.visible(6_000L))
        // another tap restarts it; a window open holds it; let go, the hold time again from then
        h.tap(10_000L)
        h.hold(true, 11_000L)
        assertEquals(1f, h.alpha(60_000L))
        assertEquals(Long.MAX_VALUE, h.remaining(60_000L))
        h.hold(false, 60_000L)
        assertEquals(1f, h.alpha(63_999L))
        assertEquals(0f, h.alpha(64_000L + HudReveal.FADE_MS))
        h.tap(70_000L); h.hide()
        assertEquals(0f, h.alpha(70_001L))
    }

    @Test
    fun softControlsFitThePhase() {
        assertTrue(SoftControl.SLOWER in SoftControl.offered(FlowPhase.STAGE))
        assertFalse(SoftControl.SLOWER in SoftControl.offered(FlowPhase.LEAD_IN))
        assertFalse(SoftControl.CONTINUE in SoftControl.offered(FlowPhase.DONE))
        // plain words only
        for (c in SoftControl.entries) assertTrue(c.label.all { it.isLetter() || it == ' ' }, c.label)
    }

    @Test
    fun mixFollowsTheIntensity() {
        assertEquals(IntensityMix.MUSIC_QUIET, IntensityMix.music(0f))
        assertEquals(1f, IntensityMix.music(1f))
        assertTrue(IntensityMix.ambience(1f) < IntensityMix.ambience(0f))
        assertEquals(1f, IntensityMix.voice(0.1f, 0), "Voice intensity 0: always full")
        assertTrue(IntensityMix.voice(0.1f, 100) < IntensityMix.voice(0.9f, 100))
        assertEquals(1f, IntensityMix.voice(1f, 100), 1e-5f)
        assertEquals(0f, IntensityMix.atmosphere(0))
    }

    @Test
    fun handheldSwayStaysSmallAndSmooth() {
        val o = FloatArray(3)
        var maxLen = 0f
        var maxStep = 0f
        val prev = FloatArray(3)
        for (i in 0..60 * 120) {
            HandheldSway.offset(i / 60.0, 1f, o)
            val len = sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2])
            maxLen = maxOf(maxLen, len)
            if (i > 0) maxStep = maxOf(maxStep, abs(o[0] - prev[0]) + abs(o[1] - prev[1]) + abs(o[2] - prev[2]))
            o.copyInto(prev)
        }
        assertTrue(maxLen <= HandheldSway.MAX_YARDS * 1.75f, "max $maxLen")
        assertTrue(maxLen > HandheldSway.MAX_YARDS * 0.3f, "it moves: $maxLen")
        assertTrue(maxStep < 0.002f, "smooth frame to frame: $maxStep")
        HandheldSway.offset(12.5, 0f, o)
        assertTrue(o.all { it == 0f })
    }
}
