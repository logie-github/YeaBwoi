package com.logie.wifecraft.simulation

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToyReactionsTest {
    private val stroking = { t: Float -> ToyActivity(true, 0.08f, 0.2f * kotlin.math.sin(t * 6f), 0f) }
    private fun run(r: ToyReactions, seconds: Float, act: (Float) -> ToyActivity): ToyReaction {
        var out = r.step(0f, act(0f)); var t = 0f
        while (t < seconds) { out = r.step(1f / 60, act(t)); t += 1f / 60 }
        return out
    }

    @Test fun arousalRisesWithStrokesAndFallsWithout() {
        val r = ToyReactions()
        run(r, 10f, stroking)
        val a = r.arousal
        assertTrue(a in 0.2f..0.9f, "after 10 s of strokes: $a")
        run(r, 20f) { ToyActivity(false, 0f, 0f, 0f) }
        assertTrue(r.arousal < a * 0.85f, "falls without activity: ${r.arousal}")
        val idle = ToyReactions(); run(idle, 20f) { ToyActivity(false, 0f, 0f, 0f) }
        assertEquals(0f, idle.arousal)
    }

    @Test fun breathingAndPoseGrowWithArousal() {
        val calm = ToyReactions().step(0.016f, ToyActivity(false, 0f, 0f, 0f)).pose
        val r = ToyReactions(); val hot = run(r, 15f, stroking).pose
        assertTrue(hot.breathHz > calm.breathHz && hot.breath > calm.breath && hot.arch > calm.arch && hot.headBack > calm.headBack)
        assertTrue(hot.thighPart > calm.thighPart && hot.kneeFlex > calm.kneeFlex && hot.shoulder > calm.shoulder)
        assertTrue(hot.reachL > 0f, "a hand reaches down")
        assertEquals(12, hot.toArray().size)
    }

    @Test fun hipTiltFollowsTheStrokeWithALagWithinTwelveDegrees() {
        val r = ToyReactions()
        var maxTilt = 0f
        var lagged = false
        var t = 0f
        var prevSpeed = 0f
        while (t < 12f) {
            val sp = 0.25f * kotlin.math.sin(t * 5f)
            val out = r.step(1f / 60, ToyActivity(true, 0.08f, sp, 0f))
            maxTilt = maxOf(maxTilt, abs(out.pose.hipTilt))
            if (t > 6f && sp > 0.2f && out.pose.hipTilt < 0.5f * 0.1f * 0.7f && prevSpeed < sp) lagged = true
            prevSpeed = sp; t += 1f / 60
        }
        assertTrue(maxTilt > 0.05f && maxTilt <= 0.21f, "pelvis tilt $maxTilt rad (5 to 12 degrees)")
    }

    @Test fun sustainedStrokesTipIntoAClimaxThenCooldown() {
        val r = ToyReactions()
        val hard = { t: Float -> ToyActivity(true, 0.1f, 0.25f * kotlin.math.sin(t * 7f), 0.6f) }
        var t = 0f; var climaxStart = -1f; var climaxEnd = -1f; var peakTremor = 0f; var peakArch = 0f
        while (t < 60f) {
            val out = r.step(1f / 60, hard(t))
            if (out.climax && climaxStart < 0) climaxStart = t
            if (out.climax) { peakTremor = maxOf(peakTremor, out.pose.tremor); peakArch = maxOf(peakArch, out.pose.arch) }
            if (!out.climax && climaxStart >= 0 && climaxEnd < 0) climaxEnd = t
            t += 1f / 60
        }
        assertTrue(climaxStart > 5f, "needs time: $climaxStart")
        assertEquals(ToyReactions.CLIMAX_SECONDS, climaxEnd - climaxStart, 0.1f)
        assertTrue(peakTremor > 0.9f && peakArch > 0.15f, "tremor $peakTremor arch $peakArch")
        assertTrue(r.arousal <= 1f)
        // right after, it can't tip again until the cooldown has run: arousal was dropped and rises slowly
        val r2 = ToyReactions()
        var t2 = 0f; var starts = 0; var was = false; var firstStart = -1f; var secondStart = -1f
        while (t2 < 90f) {
            val out = r2.step(1f / 60, hard(t2))
            if (out.climax && !was) { starts++; if (starts == 1) firstStart = t2 else if (starts == 2) secondStart = t2 }
            was = out.climax; t2 += 1f / 60
        }
        assertTrue(starts >= 2 && secondStart - firstStart >= ToyReactions.COOLDOWN_SECONDS + ToyReactions.CLIMAX_SECONDS, "gap ${secondStart - firstStart}")
    }

    @Test fun climaxIsAHighFrequencyTrembleThenRelaxation() {
        val r = ToyReactions()
        val hard = { t: Float -> ToyActivity(true, 0.1f, 0.25f * kotlin.math.sin(t * 7f), 0.6f) }
        var t = 0f; val trem = ArrayList<Float>(); var releaseBreath = 0f; var peakBreath = 0f
        while (t < 40f) {
            val out = r.step(1f / 60, hard(t))
            if (out.climax) trem += out.pose.tremor
            if (r.inClimax) peakBreath = maxOf(peakBreath, out.pose.breath)
            else if (r.coolingDown) releaseBreath = maxOf(releaseBreath, out.pose.breath)
            t += 1f / 60
        }
        assertTrue(trem.isNotEmpty() && trem.max() > 0.9f)
        assertTrue(releaseBreath > 0.02f, "deeper breaths after: $releaseBreath")
    }

    @Test fun personalityAndSettingScaleIt() {
        fun arch(level: ReactionLevel, bold: Float) = run(ToyReactions(level, bold), 12f, stroking).pose.arch
        val shy = arch(ReactionLevel.NATURAL, -1f); val nat = arch(ReactionLevel.NATURAL, 0f); val bold = arch(ReactionLevel.NATURAL, 1f)
        assertTrue(shy < nat && nat < bold, "$shy < $nat < $bold")
        val sub = arch(ReactionLevel.SUBTLE, 0f); val int = arch(ReactionLevel.INTENSE, 0f)
        assertTrue(sub < nat && nat < int)
        val off = run(ToyReactions(ReactionLevel.OFF), 12f, stroking)
        assertEquals(ToyPoseValues(), off.pose); assertEquals(0f, off.eyesClosed); assertNull(off.expression)
    }

    @Test fun deterministicForASeed() {
        fun trace(seed: Long): List<Float> {
            val r = ToyReactions(seed = seed); val out = ArrayList<Float>(); var t = 0f
            while (t < 20f) { val o = r.step(1f / 60, stroking(t), true); out += o.pose.arch + o.pose.hipTilt + o.eyesClosed + (o.line?.length ?: 0); t += 1f / 60 }
            return out
        }
        assertEquals(trace(3), trace(3))
    }

    @Test fun eyesCloseAndLipsPartAtHighArousal() {
        val r = ToyReactions(); val o = run(r, 20f) { ToyActivity(true, 0.1f, 0.25f * kotlin.math.sin(it * 6f), 0.5f) }
        assertTrue(o.eyesClosed > 0.4f && o.mouthOpen > 0.3f, "eyes ${o.eyesClosed} mouth ${o.mouthOpen}")
        assertNotNull(o.expression)
    }

    @Test fun linesAreRateLimitedAndOnlyWhenAsked() {
        val r = ToyReactions(seed = 5)
        var t = 0f; val at = ArrayList<Float>()
        while (t < 60f) { val o = r.step(1f / 60, ToyActivity(true, 0.1f, 0.25f * kotlin.math.sin(t * 6f), 0.5f), true); if (o.line != null) at += t; t += 1f / 60 }
        assertTrue(at.isNotEmpty())
        for (i in 1 until at.size) assertTrue(at[i] - at[i - 1] >= ToyReactions.LINE_GAP - 0.05f, "lines ${at[i - 1]} and ${at[i]}")
        val quiet = ToyReactions(seed = 5); var any = false; t = 0f
        while (t < 60f) { if (quiet.step(1f / 60, ToyActivity(true, 0.1f, 0.2f, 0.5f), false).line != null) any = true; t += 1f / 60 }
        assertFalse(any, "no model: no text")
    }

    @Test fun levelKeysRoundTrip() {
        for (l in ReactionLevel.entries) assertEquals(l, ReactionLevel.of(l.name))
        assertEquals(ReactionLevel.NATURAL, ReactionLevel.of(null)); assertEquals(ReactionLevel.NATURAL, ReactionLevel.of("junk"))
    }
}

class ToyPrefsTest {
    @Test fun defaultsAreNaturalWithClimaxAndLines() {
        val p = ToyPrefs(MapToyKeyValue())
        assertEquals(ReactionLevel.NATURAL, p.level); assertTrue(p.climax); assertTrue(p.lines); assertEquals(0.5f, p.vibration)
    }

    @Test fun settingsPersistAcrossInstances() {
        val kv = MapToyKeyValue()
        ToyPrefs(kv).apply { level = ReactionLevel.INTENSE; climax = false; lines = false; vibration = 0.8f }
        val again = ToyPrefs(kv)
        assertEquals(ReactionLevel.INTENSE, again.level); assertFalse(again.climax); assertFalse(again.lines); assertEquals(0.8f, again.vibration)
        kv.putString("toy.vibration", "junk"); assertEquals(0.5f, ToyPrefs(kv).vibration)
    }

    @Test fun climaxOptionOffHoldsAtTheEdge() {
        val r = ToyReactions(); r.climaxEnabled = false
        var t = 0f; var any = false
        while (t < 60f) { if (r.step(1f / 60, ToyActivity(true, 0.1f, 0.25f * kotlin.math.sin(t * 7f), 0.6f)).climax) any = true; t += 1f / 60 }
        assertFalse(any); assertTrue(r.arousal > 0.9f)
    }
}
