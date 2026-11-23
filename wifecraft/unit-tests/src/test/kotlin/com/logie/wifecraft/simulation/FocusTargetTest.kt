package com.logie.wifecraft.simulation

import com.logie.wifecraft.graphics.GraphicsSettings
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Depth of field's focus follows the subject: the band, its smoothing, the screen position and the option. */
class FocusTargetTest {
    private val npc = floatArrayOf(0f, 0f, 1.25f)   // her chest

    /** A camera [dist] yards from her chest at [yaw] about her, looking at [look] (default her), a little above. */
    private fun frame(yaw: Float, dist: Float, lookOffset: Float = 0f, pts: Boolean = true): FocusTarget.Input {
        val i = FocusTarget.Input()
        i.eyeX = npc[0] + cos(yaw) * dist; i.eyeY = npc[1] + sin(yaw) * dist; i.eyeZ = 1.7f
        // looking at a point beside her: an off-centre framing
        val rx = -sin(yaw); val ry = cos(yaw)
        i.atX = npc[0] + rx * lookOffset; i.atY = npc[1] + ry * lookOffset; i.atZ = 1.25f
        i.cx = npc[0]; i.cy = npc[1]; i.cz = npc[2]
        if (pts) { i.addPoint(0f, 0f, 1.62f); i.addPoint(0f, 0f, 1.25f); i.addPoint(0f, 0f, 0f) }
        return i
    }

    @Test fun focusIsTheDistanceToTheSubjectAtEveryOrbitAngle() {
        for (deg in listOf(0, 45, 90, 135, 180, 270)) {
            val t = FocusTarget()
            val i = frame(Math.toRadians(deg.toDouble()).toFloat(), 3f, lookOffset = 0.8f)
            assertTrue(t.update(0.016f, FocusKind.NPC, i))
            // the subject (her chest) is inside the sharp band whatever the angle, and the band is round the subject, not round the
            // distance to the looked-at point
            assertTrue(t.near < t.distance && t.distance < t.far, "angle $deg: ${t.near} ${t.distance} ${t.far}")
            // the depth of her chest along the view axis: 3 yards less the look offset's small foreshortening
            assertTrue(abs(t.distance - 3f) < 0.2f, "angle $deg: distance ${t.distance}")
        }
    }

    @Test fun anOffCentreSubjectStaysInTheBandAndReportsWhereItIs() {
        val t = FocusTarget()
        val i = frame(0f, 3f, lookOffset = 1.0f)
        t.update(0.016f, FocusKind.NPC, i)
        // the camera looks a yard to one side of her: she is off the middle, still on the screen, still sharp
        assertTrue(abs(t.screenX) > 0.3f, "x ${t.screenX}")
        assertTrue(t.onScreen)
        assertTrue(t.distance in t.near..t.far)
        // far to one side: off the screen, still a band
        val far = frame(0f, 3f, lookOffset = 3.5f)
        val u = FocusTarget(); u.update(0.016f, FocusKind.NPC, far)
        assertFalse(u.onScreen)
    }

    private fun depths(i: FocusTarget.Input): List<Float> {
        var fx = i.atX - i.eyeX; var fy = i.atY - i.eyeY; var fz = i.atZ - i.eyeZ
        val l = kotlin.math.sqrt(fx * fx + fy * fy + fz * fz); fx /= l; fy /= l; fz /= l
        return (0 until i.count).map { k -> FocusTarget.depth(i, i.pts[k * 3], i.pts[k * 3 + 1], i.pts[k * 3 + 2], fx, fy, fz) }
    }

    @Test fun theBandHoldsTheWholeBodyAndPadsIt() {
        val t = FocusTarget()
        // from above her head she is a standing body in depth: feet to head all inside the band
        val i = frame(0f, 2.5f)
        i.eyeZ = 4.0f; i.atZ = 1.0f
        t.update(0.016f, FocusKind.NPC, i)
        val d = depths(i)
        for (x in d) assertTrue(x in t.near..t.far, "$x outside ${t.near}..${t.far}")
        assertTrue(t.near < d.min() && t.far > d.max())
        assertTrue(t.far - t.near > 1.0f, "${t.near}..${t.far}")
    }

    @Test fun aCloseUpKeepsTheFaceDeepEnoughToBeSharp() {
        val t = FocusTarget()
        val i = frame(0.3f, 1.0f, pts = false)
        t.update(0.016f, FocusKind.CHAT, i)
        assertTrue(t.far - t.near >= 2 * FocusTarget.minHalf(t.distance) - 1e-4f, "${t.near}..${t.far}")
        assertEquals(1f, t.strength)
        // a far shot blurs less
        val w = FocusTarget(); w.update(0.016f, FocusKind.SHOT, frame(0.3f, 9f))
        assertTrue(w.strength < 0.7f && w.strength >= FocusTarget.MIN_STRENGTH, "${w.strength}")
        assertEquals(FocusTarget.MIN_STRENGTH, FocusTarget.strengthAt(40f))
        assertEquals(1f, FocusTarget.strengthAt(1f))
    }

    @Test fun theBandGlidesToANewSubjectWithoutPopping() {
        val t = FocusTarget()
        t.update(0.016f, FocusKind.NPC, frame(0f, 2f))
        val first = t.distance
        val before = t.near
        // the camera cuts four yards back: the goal jumps, the band does not
        val jump = frame(0f, 6f)
        t.update(1f / 60f, FocusKind.NPC, jump)
        assertTrue(t.distance > first + 3f)   // the asked distance
        assertTrue(abs(t.near - before) < 0.6f, "popped: $before -> ${t.near}")
        // it settles within a fifth to a quarter of a second
        var s = 0
        while (s < 60) { t.update(1f / 60f, FocusKind.NPC, jump); s++ }
        val goal = FocusTarget().also { it.update(0.016f, FocusKind.NPC, jump) }
        assertTrue(abs(t.near - goal.near) < 0.01f && abs(t.far - goal.far) < 0.01f, "${t.near}..${t.far} vs ${goal.near}..${goal.far}")
        // at 0.2 s it has done at least 70 % of the way
        val u = FocusTarget(); u.update(0.016f, FocusKind.NPC, frame(0f, 2f))
        val start = u.near
        repeat(12) { u.update(1f / 60f, FocusKind.NPC, jump) }
        val done = (u.near - start) / (goal.near - start)
        assertTrue(done in 0.7f..1.0f, "after 0.2 s: $done")
    }

    @Test fun theSameWhateverTheFrameRate() {
        val a = FocusTarget(); val b = FocusTarget()
        a.update(0.016f, FocusKind.NPC, frame(0f, 2f)); b.update(0.016f, FocusKind.NPC, frame(0f, 2f))
        val to = frame(0f, 5f)
        repeat(30) { a.update(1f / 60f, FocusKind.NPC, to) }
        repeat(15) { b.update(1f / 30f, FocusKind.NPC, to) }
        assertTrue(abs(a.near - b.near) < 1e-3f && abs(a.far - b.far) < 1e-3f)
    }

    @Test fun aPairIsFocusedOnTheMidpointOfTheirChests() {
        val out = FloatArray(3)
        FocusTarget.pairCentre(0f, 0f, 1.25f, 1f, 2f, 1.15f, out)
        assertEquals(0.5f, out[0]); assertEquals(1f, out[1]); assertEquals(1.2f, out[2], 1e-5f)
        // too far apart to be one subject: the selected one
        FocusTarget.pairCentre(0f, 0f, 1.25f, 20f, 0f, 1.25f, out)
        assertEquals(0f, out[0]); assertEquals(1.25f, out[2])
    }

    @Test fun aPairsBandCoversBothPeople() {
        val i = frame(0.5f, 3f, pts = false)
        i.cx = 0.5f; i.cy = 1f; i.cz = 1.2f
        for (p in listOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f))) { i.addPoint(p[0], p[1], 1.62f); i.addPoint(p[0], p[1], 0f) }
        val t = FocusTarget(); t.update(0.016f, FocusKind.PAIR, i)
        for ((k, d) in depths(i).withIndex()) assertTrue(d in t.near..t.far, "point $k at $d outside ${t.near}..${t.far}")
    }

    @Test fun noSubjectOrTheOptionOffGivesNoBand() {
        val t = FocusTarget()
        assertFalse(t.update(0.016f, FocusKind.NONE, frame(0f, 2f)))
        assertFalse(t.active)
        assertFalse(t.update(0.016f, FocusKind.NPC, frame(0f, 2f), enabled = false))
        assertFalse(t.active)
        assertTrue(t.update(0.016f, FocusKind.NPC, frame(0f, 2f)))
        // a camera on top of its look-at point has no direction: no band rather than a NaN
        val bad = frame(0f, 2f); bad.atX = bad.eyeX; bad.atY = bad.eyeY; bad.atZ = bad.eyeZ
        assertFalse(t.update(0.016f, FocusKind.NPC, bad))
        // back on: taken as it is, not glided from a stale band
        assertTrue(t.update(0.016f, FocusKind.NPC, frame(0f, 6f)))
        assertTrue(abs(t.distance - 6f) < 0.3f && t.far > 6f)
    }

    @Test fun theOptionIsOnByDefaultAndKept() {
        assertTrue(GraphicsSettings().focusFollows)
        val off = GraphicsSettings().copy(focusFollows = false)
        assertFalse(GraphicsSettings.parse(off.toJson().toString()).focusFollows)
        assertFalse(off.withPreset(com.logie.wifecraft.graphics.GraphicsPreset.HIGH).focusFollows)
        assertTrue(GraphicsSettings.parse("{}").focusFollows)
    }
}
