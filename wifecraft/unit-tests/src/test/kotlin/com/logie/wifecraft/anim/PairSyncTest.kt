package com.logie.wifecraft.anim

import com.logie.wifecraft.sims.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** PairSync: the clips of one performance kept on one clock (the audit's 16 Anarcis stages whose clips differ by more than a frame). */
class PairSyncTest {
    /** A clip [seconds] long turning its Root about z from 0 to [endDeg] degrees over evenly spaced [keys] keys. */
    private fun clip(name: String, seconds: Float, endDeg: Double, keys: Int = 5, loop: Boolean = false): Anim {
        val times = FloatArray(keys) { seconds * it / (keys - 1) }
        val values = FloatArray(keys * 4)
        for (i in 0 until keys) {
            val q = PoseFixtures.rotZ(endDeg * i / (keys - 1))
            for (c in 0..3) values[i * 4 + c] = q[c].toFloat()
        }
        val pos = BoneTrack("Root", Channel.POSITION, floatArrayOf(0f, seconds), floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f))
        return Anim(name, name, "", seconds, loop, listOf(BoneTrack("Root", Channel.ROTATION, times, values), pos), "t:$name", 0L)
    }

    @Test fun clipsWithinAFrameAreLeftAsTheyAre() {
        val a = clip("a", 20f, 90.0); val b = clip("b", 20.02f, 90.0)
        val (x, y) = PairSync.synced(a, b)
        assertSame(a, x); assertSame(b, y)
        assertEquals(listOf(PairSync.Way.SAME, PairSync.Way.SAME), PairSync.ways(listOf(a, b)))
    }

    @Test fun aShorterClipThatIsNoLoopHoldsItsLastPoseToTheEnd() {
        val a = clip("a", 20f, 90.0); val b = clip("b", 22f, 30.0)
        assertEquals(listOf(PairSync.Way.HOLD, PairSync.Way.SAME), PairSync.ways(listOf(a, b)))
        val (x, y) = PairSync.synced(a, b)
        assertSame(b, y)
        assertEquals(22f, x.duration)
        for (t in x.tracks) {
            val per = if (t.channel == Channel.ROTATION) 4 else 3
            val n = t.times.size
            assertEquals(22f, t.times[n - 1], "${t.bone} ends at the longer clip's end")
            for (c in 0 until per) assertEquals(t.values[(n - 2) * per + c], t.values[(n - 1) * per + c], "the last pose held")
            // the clip's own keys are where they were
            assertEquals(20f, t.times[n - 2])
        }
    }

    @Test fun aShorterLoopIsStretchedOverItsCyclesToTheLongerClipsLength() {
        // a loop (its last pose its first: a full turn) 20 s against 20.4 s: one cycle, 2 % slower
        val a = clip("a", 20f, 360.0, loop = false); val b = clip("b", 20.4f, 90.0)
        assertTrue(PairSync.endIsStart(a))
        assertEquals(PairSync.Way.LOOP, PairSync.ways(listOf(a, b))[0])
        val x = PairSync.synced(a, b).first
        assertEquals(20.4f, x.duration)
        val rot = x.tracks.first { it.channel == Channel.ROTATION }
        assertEquals(5, rot.times.size)
        assertEquals(20.4f, rot.times.last(), 1e-4f)
        assertEquals(10.2f, rot.times[2], 1e-4f)
        // 10 s against 20.6 s: two cycles, each 3 % slower, the second starting where the first ended
        val c = clip("c", 10f, 360.0, loop = true); val d = clip("d", 20.6f, 90.0)
        val y = PairSync.synced(c, d).first
        val r = y.tracks.first { it.channel == Channel.ROTATION }
        assertEquals(9, r.times.size, "two cycles of five keys, sharing the key where one ends and the next begins")
        assertEquals(10.3f, r.times[4], 1e-4f)
        assertEquals(20.6f, r.times.last(), 1e-4f)
        for (i in 1 until r.times.size) assertTrue(r.times[i] > r.times[i - 1], "keys in order")
        assertEquals(20.6f, y.duration)
    }

    @Test fun aLoopFlagMakesItALoopEvenWhenItsEndIsNotItsStart() {
        val a = clip("a", 10f, 45.0, loop = true); val b = clip("b", 12f, 45.0)
        assertEquals(PairSync.Way.LOOP, PairSync.ways(listOf(a, b))[0])
        assertEquals(12f, PairSync.synced(a, b).first.duration)
    }
}
