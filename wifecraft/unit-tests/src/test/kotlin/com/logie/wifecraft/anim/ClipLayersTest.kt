package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The procedural bone clips' maths: easing, keyframe sampling, the loop's seam, the quaternions and the bake. */
class ClipLayersTest {
    private fun key(t: Float, pitch: Float, ease: Ease = Ease.SMOOTH) = PoseKey(t, pitch, 0f, 0f, ease)

    /** The angle (degrees) between two unit quaternions' rotations. */
    private fun angle(a: FloatArray, b: FloatArray): Float {
        val d = abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]).coerceIn(0f, 1f)
        return Math.toDegrees(2.0 * acos(d.toDouble())).toFloat()
    }

    private fun rotate(q: FloatArray, v: FloatArray): FloatArray {
        // v' = q v q^-1
        val (x, y, z, w) = q.toList()
        val tx = 2 * (y * v[2] - z * v[1]); val ty = 2 * (z * v[0] - x * v[2]); val tz = 2 * (x * v[1] - y * v[0])
        return floatArrayOf(v[0] + w * tx + (y * tz - z * ty), v[1] + w * ty + (z * tx - x * tz), v[2] + w * tz + (x * ty - y * tx))
    }

    @Test fun easingsStartAtZeroEndAtOneAndSmoothstepIsFlatAtBothEnds() {
        for (e in Ease.entries) {
            assertEquals(0f, ClipMath.ease(e, 0f), 1e-6f)
            if (e != Ease.HOLD) assertEquals(1f, ClipMath.ease(e, 1f), 1e-6f)
        }
        assertEquals(0.5f, ClipMath.ease(Ease.SMOOTH, 0.5f), 1e-6f)
        assertEquals(0.25f, ClipMath.ease(Ease.LINEAR, 0.25f), 1e-6f)
        assertTrue(ClipMath.ease(Ease.SMOOTH, 0.05f) < 0.01f)          // slow in
        assertTrue(ClipMath.ease(Ease.SMOOTH, 0.95f) > 0.99f)          // slow out
        assertEquals(0f, ClipMath.ease(Ease.HOLD, 0.99f))
        assertEquals(0f, ClipMath.ease(Ease.SMOOTH, -3f)); assertEquals(1f, ClipMath.ease(Ease.SMOOTH, 7f))   // clamped
    }

    @Test fun easeCodesRoundTripAndUnknownIsSmooth() {
        for (e in Ease.entries) assertEquals(e, Ease.of(e.code))
        assertEquals(Ease.SMOOTH, Ease.of(null)); assertEquals(Ease.SMOOTH, Ease.of("zzz"))
    }

    @Test fun aCurveSamplesItsKeysAndHoldsOutsideThem() {
        val tr = PoseTrack("x", listOf(key(1f, 10f), key(3f, 30f), key(4f, 0f)))
        assertEquals(10f, ClipMath.sample(tr, 0f, 5f, false)[0], 1e-4f)      // before the first key: the first holds
        assertEquals(10f, ClipMath.sample(tr, 1f, 5f, false)[0], 1e-4f)
        assertEquals(20f, ClipMath.sample(tr, 2f, 5f, false)[0], 1e-4f)      // smoothstep's middle is the middle
        assertEquals(30f, ClipMath.sample(tr, 3f, 5f, false)[0], 1e-4f)
        assertEquals(0f, ClipMath.sample(tr, 4f, 5f, false)[0], 1e-4f)
        assertEquals(0f, ClipMath.sample(tr, 4.9f, 5f, false)[0], 1e-4f)     // not a loop: the last holds
        // a key in unsorted order is sorted
        assertEquals(20f, ClipMath.sample(PoseTrack("x", listOf(key(3f, 30f), key(1f, 10f))), 2f, 5f, false)[0], 1e-4f)
    }

    @Test fun holdKeepsAKeysValueUntilTheNextOne() {
        val tr = PoseTrack("x", listOf(key(0f, 5f, Ease.HOLD), key(2f, 50f)))
        assertEquals(5f, ClipMath.sample(tr, 1.99f, 4f, false)[0], 1e-4f)
        assertEquals(50f, ClipMath.sample(tr, 2f, 4f, false)[0], 1e-4f)
    }

    @Test fun aLoopRunsFromItsLastKeyBackToItsFirstWithNoSeam() {
        val tr = PoseTrack("x", listOf(key(0f, 0f), key(2f, 40f), key(3f, 20f)))
        val d = 5f
        // at the last key the value is its own; then it runs back to the first, which is there again at the duration
        assertEquals(20f, ClipMath.sample(tr, 3f, d, true)[0], 1e-4f)
        assertEquals(10f, ClipMath.sample(tr, 4f, d, true)[0], 1e-4f)
        assertEquals(0f, ClipMath.sample(tr, d, d, true)[0], 1e-4f)
        // so the end of the loop meets its start: nothing jumps across the wrap
        val before = ClipMath.sample(tr, d - 1e-3f, d, true)[0]; val after = ClipMath.sample(tr, 0f, d, true)[0]
        assertEquals(after, before, 0.05f)
        // not looping: the last key holds instead
        assertEquals(20f, ClipMath.sample(tr, 4f, d, false)[0], 1e-4f)
    }

    @Test fun everyBakedLoopMeetsItselfAtTheWrap() {
        // the stage's own tracks (the table is the thing that has to loop): the last baked key and the first are within a frame's step
        val stage = com.logie.wifecraft.characters.DragonStages.PET_HEAD
        for (tr in stage.creatureTracks + stage.personTracks) {
            val n = ClipLayers.frames(stage.duration, stage.fps)
            val last = ClipMath.sample(tr, (n - 1) * stage.duration / n, stage.duration, true)
            val first = ClipMath.sample(tr, 0f, stage.duration, true)
            val step = ClipMath.sample(tr, (n - 2) * stage.duration / n, stage.duration, true)
            val jump = (0..2).maxOf { abs(last[it] - first[it]) }
            val frame = (0..2).maxOf { abs(step[it] - last[it]) }
            assertTrue(jump <= maxOf(frame, 0.5f) * 1.01f, "${tr.bone}: wrap jump $jump vs frame step $frame")
        }
    }

    @Test fun pitchTipsTheNoseDownYawTurnsLeftAndRollLiftsTheLeftSide() {
        val nose = floatArrayOf(1f, 0f, 0f); val left = floatArrayOf(0f, 1f, 0f)
        val p = rotate(ClipMath.quaternion(90f, 0f, 0f), nose)
        assertEquals(-1f, p[2], 1e-5f); assertEquals(0f, p[0], 1e-5f)               // +x goes to -z
        val y = rotate(ClipMath.quaternion(0f, 90f, 0f), nose)
        assertEquals(1f, y[1], 1e-5f)                                                // +x goes to +y: left
        val r = rotate(ClipMath.quaternion(0f, 0f, 90f), left)
        assertEquals(1f, r[2], 1e-5f)                                                // +y goes to +z: the left side lifts
        for (v in listOf(floatArrayOf(10f, 20f, 30f), floatArrayOf(-45f, 100f, 7f))) {
            val q = ClipMath.quaternion(v[0], v[1], v[2])
            assertEquals(1f, sqrt(q.sumOf { (it * it).toDouble() }).toFloat(), 1e-5f)
        }
        assertEquals(0f, angle(ClipMath.quaternion(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f)), 1e-3f)
    }

    private val tracks = listOf(PoseTrack("neck", listOf(key(0f, 0f), key(1f, 20f), key(2f, 0f))))

    @Test fun aBakedClipHasEvenKeysUnitQuaternionsAndTheTuningsDuration() {
        val a = ClipLayers.bake("c", "C", tracks, 2f, true, ClipTuning(speed = 2f, amount = 1f, fps = 10))
        assertEquals(1f, a.duration, 1e-6f); assertTrue(a.loop)                       // twice as fast: half as long
        val t = a.tracks.single()
        assertEquals(Channel.ROTATION, t.channel); assertEquals(20, t.times.size); assertEquals(80, t.values.size)
        assertEquals(0f, t.times[0]); assertEquals(0.05f, t.times[1] - t.times[0], 1e-5f)
        assertTrue(t.times.last() < a.duration)                                      // the loop's last key is short of the wrap
        for (i in 0 until 20) assertEquals(1f, sqrt((0..3).sumOf { (t.values[i * 4 + it] * t.values[i * 4 + it]).toDouble() }).toFloat(), 1e-5f)
        // the middle of the clip is the full 20 degrees (pitch about y: the quaternion's y is sin(10 degrees))
        val mid = 10 * 4
        assertEquals(Math.sin(Math.toRadians(10.0)).toFloat(), t.values[mid + 1], 1e-3f)
    }

    @Test fun amountScalesEveryAngleAndZeroIsNoMotion() {
        val full = ClipLayers.bake("c", "C", tracks, 2f, true, ClipTuning(amount = 1f)).tracks.single()
        val half = ClipLayers.bake("c", "C", tracks, 2f, true, ClipTuning(amount = 0.5f)).tracks.single()
        val none = ClipLayers.bake("c", "C", tracks, 2f, true, ClipTuning(amount = 0f)).tracks.single()
        val mid = full.times.size / 2 * 4
        assertEquals(angle(floatArrayOf(0f, 0f, 0f, 1f), full.values.copyOfRange(mid, mid + 4)) / 2, angle(floatArrayOf(0f, 0f, 0f, 1f), half.values.copyOfRange(mid, mid + 4)), 0.05f)
        for (i in none.times.indices) assertEquals(1f, none.values[i * 4 + 3], 1e-6f)
    }

    @Test fun aScalarBakesToAPositionTrackOfThatCurve() {
        val w = ClipLayers.bakeScalar("layer.hold", listOf(WeightKey(0f, 0f), WeightKey(1f, 1f), WeightKey(2f, 0f)), 2f, true, ClipTuning(fps = 10))
        assertEquals(Channel.POSITION, w.channel); assertEquals("layer.hold", w.bone)
        assertEquals(20, w.times.size); assertEquals(60, w.values.size)
        assertEquals(1f, w.values[10 * 3], 1e-5f)                                    // the key at 1 s
        assertEquals(0f, w.values[0], 1e-6f)
        assertTrue((0 until 20).all { w.values[it * 3 + 1] == 0f && w.values[it * 3 + 2] == 0f && w.values[it * 3] in 0f..1f })
        val k = ClipLayers.constant("k", 1f, 2f, 3f)
        assertEquals(listOf(1f, 2f, 3f), k.values.toList()); assertEquals(1, k.times.size)
    }

    @Test fun badInputsAreRefused() {
        assertFailsWith<IllegalArgumentException> { ClipTuning(speed = 0f) }
        assertFailsWith<IllegalArgumentException> { ClipTuning(amount = -1f) }
        assertFailsWith<IllegalArgumentException> { ClipTuning(speed = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { PoseTrack("x", emptyList()) }
        assertFailsWith<IllegalArgumentException> { PoseTrack("x", listOf(PoseKey(0f, Float.NaN, 0f, 0f))) }
        assertFailsWith<IllegalArgumentException> { ClipLayers.bake("c", "C", tracks, 0f, true) }
    }
}
