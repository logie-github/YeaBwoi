package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The live scene's clock, camera, touches and menus' snapshots, with a fake clock (simulation/FixedStep, SceneCamera, LiveScene). */
class SceneMotionTest {
    private val ms = 1_000_000L

    // ---- the fixed step

    @Test fun sixtyAndOneHundredTwentyHertzRunTheSameSteps() {
        fun stepsOver(rateHz: Int, seconds: Int): Long {
            val clock = FixedStep()
            val frame = 1_000_000_000L / rateHz
            var t = 0L
            repeat(rateHz * seconds + 1) { clock.begin(t); t += frame }
            return clock.steps
        }
        val at60 = stepsOver(60, 5)
        val at120 = stepsOver(120, 5)
        val at90 = stepsOver(90, 5)
        assertTrue(abs(at120 - 600L) <= 1, "120 steps a second: $at120")
        assertTrue(abs(at60 - at120) <= 1, "60 Hz: $at60")
        assertTrue(abs(at90 - at120) <= 1, "90 Hz: $at90")
    }

    @Test fun aClockGoingBackOrAPauseNeverJumps() {
        val clock = FixedStep()
        assertEquals(0, clock.begin(1_000 * ms))           // the first frame: no time yet
        assertEquals(2, clock.begin(1_000 * ms + 16_667_000L))
        assertEquals(0, clock.begin(900 * ms))             // backwards: nothing
        assertEquals(1, clock.begin(5_900 * ms))           // five seconds in the background: one step, not 600
        clock.pause()
        assertEquals(0, clock.begin(9_000 * ms))           // after pause(): starts afresh
        assertTrue(clock.alpha in 0f..1f)
    }

    @Test fun aSlowFrameIsClampedAndAlphaStaysInRange() {
        val clock = FixedStep()
        clock.begin(0L)
        val due = clock.begin(200 * ms)                    // 200 ms > the pause gap? no: 0.2 < 0.25, clamped to 0.1 s
        assertEquals(12, due)                              // 0.1 s = 12 steps of 1/120 (the cap)
        var t = 200 * ms
        repeat(50) { t += 7 * ms; clock.begin(t); assertTrue(clock.alpha in 0f..1f) }
    }

    // ---- the camera's maths

    @Test fun theSpringConvergesWithoutOvershootAtAnyFrameRate() {
        for (dt in floatArrayOf(1f / 30f, 1f / 60f, 1f / 120f)) {
            val s = Spring(0f, 18f)
            var max = 0f
            var t = 0f
            while (t < 1f) { max = maxOf(max, s.update(1f, dt)); t += dt }
            assertTrue(max <= 1.0001f, "overshoot at $dt: $max")
            assertEquals(1f, s.value, 1e-3f)
        }
        // the same path whatever the frame rate (exact integration)
        val a = Spring(0f, 7f); val b = Spring(0f, 7f)
        repeat(30) { a.update(2f, 1f / 30f) }
        repeat(120) { b.update(2f, 1f / 120f) }
        assertEquals(a.value, b.value, 1e-3f)
    }

    @Test fun easingStartsAndEndsGently() {
        assertEquals(0f, easeInOut(0f)); assertEquals(1f, easeInOut(1f)); assertEquals(0.5f, easeInOut(0.5f), 1e-6f)
        assertTrue(easeInOut(0.05f) < 0.01f && easeInOut(0.95f) > 0.99f)
        var last = 0f
        for (i in 1..100) { val v = easeInOut(i / 100f); assertTrue(v >= last); last = v }
    }

    private val room = CameraPose(Vec3(0f, 0f, 3f), Vec3(10f, 0f, 2f), 50f)

    private fun eye(c: SceneCamera) = Vec3(c.eyeX, c.eyeY, c.eyeZ)
    private fun dist(a: Vec3, b: Vec3) = kotlin.math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    @Test fun theRoomToNpcCameraEasesOverAboutThreeHundredFiftyMsWithoutSnapping() {
        val cam = SceneCamera(room)
        val dt = 1f / 60f
        cam.update(dt, true, 6f, 1f, 1f)
        assertEquals(room.eye, eye(cam))
        cam.setNpc(true)
        var largest = 0f
        var prev = eye(cam)
        var frames = 0
        while (cam.npcAmount < 1f && frames < 100) {
            cam.update(dt, true, 6f, 1f, 1f); frames++
            largest = maxOf(largest, dist(prev, eye(cam))); prev = eye(cam)
        }
        assertTrue(frames in 20..23, "frames: $frames")              // 0.35 s at 60 Hz
        val total = dist(room.eye, eye(cam))
        // eased over ~21 frames, the steepest frame moves at most ~3/21 of the way (a snap would move all of it)
        assertTrue(largest < total / 5f, "a frame moved $largest of $total")
        // back to the room view, and a reversal halfway goes back smoothly from where it is
        cam.setNpc(false)
        repeat(10) { cam.update(dt, true, 6f, 1f, 1f) }
        val mid = eye(cam)
        cam.setNpc(true)
        cam.update(dt, true, 6f, 1f, 1f)
        assertTrue(dist(mid, eye(cam)) < total / 5f)
        repeat(60) { cam.update(dt, true, 6f, 1f, 1f) }
        // deselecting (no focus) takes it back to the room's view
        repeat(60) { cam.update(dt, false, 0f, 0f, 0f) }
        assertEquals(0f, dist(room.eye, eye(cam)), 1e-4f)
    }

    @Test fun theTargetGlidesAfterAWalkingNpcWithoutJitter() {
        val cam = SceneCamera(room)
        cam.setNpc(true)
        val dt = 1f / 60f
        repeat(60) { cam.update(dt, true, 6f, 0f, 1f) }
        // the NPC walks with a step's bob in its position (+-2 cm at 2 Hz): the camera's target moves smoothly
        var t = 0f
        var worstJerk = 0f
        var prevV = 0f
        var prevAt = cam.atX
        repeat(240) {
            t += dt
            val x = 6f + 1.25f * t + 0.02f * kotlin.math.sin(t * 12.566f)
            cam.update(dt, true, x, 0f, 1f)
            val v = (cam.atX - prevAt) / dt
            worstJerk = maxOf(worstJerk, abs(v - prevV)); prevV = v; prevAt = cam.atX
        }
        val x = 6f + 1.25f * t
        // a critically damped follow trails a steady walk by 2 v / omega and no more
        assertTrue(abs(cam.atX - x) < 2f * 1.25f / SceneCamera.TARGET_OMEGA + 0.05f, "it keeps up: ${cam.atX} vs $x")
        assertTrue(worstJerk < 0.2f, "speed changed by at most ${worstJerk} yd/s a frame")
    }

    @Test fun aFlickKeepsTurningWithFrictionAndLimitsSpringBack() {
        val cam = SceneCamera(room)
        cam.setNpc(true)
        val dt = 1f / 60f
        repeat(30) { cam.update(dt, true, 6f, 0f, 1f) }
        val before = cam.pose()
        cam.dragBy(200f, 0f, 1000f)
        cam.release(1500f, 0f, 1000f)
        val yaws = ArrayList<Float>()
        repeat(180) { cam.update(dt, true, 6f, 0f, 1f); yaws += kotlin.math.atan2(cam.eyeY - cam.atY, cam.eyeX - cam.atX) }
        val turnAfterRelease = abs(yaws.last() - yaws[5])
        assertTrue(turnAfterRelease > 0.3f, "the flick carried on: $turnAfterRelease")
        assertFalse(cam.moving, "it comes to rest")
        assertTrue(dist(before.eye, cam.pose().eye) > 0.5f)
        // pitch pushed far past its top while held gives a little, and springs back to the limit on release
        repeat(10) { cam.dragBy(0f, 400f, 1000f); cam.update(dt, true, 6f, 0f, 1f) }
        repeat(60) { cam.update(dt, true, 6f, 0f, 1f) }
        val pitchHeld = kotlin.math.asin(((cam.eyeZ - cam.atZ) / dist(eye(cam), Vec3(cam.atX, cam.atY, cam.atZ))).coerceIn(-1f, 1f))
        assertTrue(pitchHeld <= SceneCamera.MAX_PITCH + SceneCamera.PITCH_GIVE + 1e-3f, "held: $pitchHeld")
        cam.release(0f, 0f, 1000f)
        repeat(90) { cam.update(dt, true, 6f, 0f, 1f) }
        val pitch = kotlin.math.asin(((cam.eyeZ - cam.atZ) / dist(eye(cam), Vec3(cam.atX, cam.atY, cam.atZ))).coerceIn(-1f, 1f))
        assertEquals(SceneCamera.MAX_PITCH, pitch, 0.01f)
    }

    @Test fun theZoomButtonsEaseAndKeepTheLimits() {
        val cam = SceneCamera(room)
        cam.setNpc(true)
        val dt = 1f / 60f
        repeat(30) { cam.update(dt, true, 6f, 0f, 1f) }
        val start = dist(eye(cam), Vec3(cam.atX, cam.atY, cam.atZ))
        cam.zoomBy(1.25f)
        cam.update(dt, true, 6f, 0f, 1f)
        val first = dist(eye(cam), Vec3(cam.atX, cam.atY, cam.atZ))
        assertTrue(first < start && first > start / 1.25f + 0.1f, "one frame does part of it: $start -> $first")
        repeat(60) { cam.update(dt, true, 6f, 0f, 1f) }
        assertEquals(start / 1.25f, dist(eye(cam), Vec3(cam.atX, cam.atY, cam.atZ)), 0.01f)
        repeat(30) { cam.zoomBy(2f) }
        repeat(120) { cam.update(dt, true, 6f, 0f, 1f) }
        assertEquals(SceneCamera.MIN_DISTANCE, dist(eye(cam), Vec3(cam.atX, cam.atY, cam.atZ)), 0.01f)
    }

    // ---- touches

    @Test fun aTapStaysATapWithinTheSlopAndADragStartsWithoutAJump() {
        val g = TouchGesture(slop = 20f)
        g.down(100f, 100f, 0L)
        assertFalse(g.move(110f, 105f, 16L))
        assertTrue(g.up(120L), "a small wobble is still a tap")

        g.down(100f, 100f, 0L)
        assertFalse(g.move(125f, 100f, 16L))              // crossing the slop: the orbit starts here, unmoved
        assertEquals(TouchGesture.Kind.DRAG, g.kind)
        assertTrue(g.move(130f, 100f, 32L))
        assertEquals(5f, g.dx)                             // only the move after crossing
        assertFalse(g.up(48L), "a drag is never a tap")

        g.down(100f, 100f, 0L)
        g.multi()
        assertFalse(g.move(200f, 100f, 16L), "a pinch never orbits")
        assertFalse(g.up(30L), "nor taps")

        g.down(100f, 100f, 0L)
        assertFalse(g.up(900L), "a long press isn't a tap")
    }

    @Test fun theReleaseVelocityIsTheLastMomentsAndZeroAfterAStop() {
        val g = TouchGesture(slop = 8f)
        val v = FloatArray(2)
        g.down(0f, 0f, 0L)
        var x = 0f
        for (t in 16L..160L step 16L) { x += 16f; g.move(x, 0f, t) }   // 1000 px/s
        g.velocity(165L, v)
        assertEquals(1000f, v[0], 60f)
        assertEquals(0f, v[1], 1e-3f)
        g.velocity(400L, v)                                             // held still before lifting
        assertEquals(0f, v[0])
    }

    @Test fun twoFingersTurnTheShortWayRound() {
        assertEquals(0.2f, TwoFingers.turn(3.1f, -2.983185f), 1e-3f)
        assertEquals(-0.2f, TwoFingers.turn(-2.983185f, 3.1f), 1e-3f)
        assertEquals(10f, TwoFingers.span(0f, 0f, 6f, 8f))
    }

    // ---- the live scene: frames, interpolation and the menus' snapshots

    private class Recorder : LiveScene.Sink {
        val xs = HashMap<Int, Float>()
        val activities = ArrayList<Pair<Int, Activity>>()
        var camera = 0
        override fun camera(eyeX: Float, eyeY: Float, eyeZ: Float, atX: Float, atY: Float, atZ: Float, fov: Float) { camera++ }
        override fun place(index: Int, x: Float, y: Float, z: Float, facing: Float, furniture: SceneObject?, sx: Float, sy: Float, sz: Float) { xs[index] = x }
        override fun activity(index: Int, activity: Activity) { activities += index to activity }
    }

    @Test fun snapshotsComeOnlyOnChangeAndAtMostTenASecond() {
        val live = LiveScene(listOf("a", "b"), setOf(SceneObject.CHAIR), emptyMap(), room, 0f, 0f, 0f, 0f, Random(3))
        val sink = Recorder()
        val snaps = ArrayList<Long>()
        var t = 0L
        live.command { it.setSpot(SceneObject.CHAIR, InteractionSpot(Vec3(1f, 1f, 0f), Vec3(1f, 1f, 0.5f), 0f)) }
        live.running = true
        repeat(600) { live.frame(t, sink) { snaps += t }; t += 16_667_000L }     // ten seconds at 60 Hz
        assertEquals(600, sink.camera)
        assertTrue(snaps.isNotEmpty())
        assertTrue(snaps.size < 60, "only on change: ${snaps.size} in 600 frames")
        for (i in 1 until snaps.size) assertTrue(snaps[i] - snaps[i - 1] >= LiveScene.SNAPSHOT_MS * ms, "too close: ${snaps[i] - snaps[i - 1]}")
        // a refused order comes back as the notice; a done one clears it
        var last: SceneSnapshot? = null
        live.order { sim -> if (sim.use("a", SceneObject.CHAIR) && sim.use("b", SceneObject.CHAIR)) null else "Chair is in use." }
        repeat(12) { live.frame(t, sink) { last = it }; t += 16_667_000L }
        assertEquals("Chair is in use.", last?.notice)
        assertFalse(last!!.available(SceneObject.CHAIR, "b"))
        assertTrue(last!!.available(SceneObject.CHAIR, "a"))
    }

    @Test fun walkingIsInterpolatedSmoothlyAt144Hz() {
        val live = LiveScene(listOf("a"), emptySet(), emptyMap(), room, 0f, 0f, 0f, 0f, Random(1))
        val sink = Recorder()
        live.running = true
        live.order { sim -> sim.chat("a", "a"); null }   // no-op order, then walk somewhere by free will
        var t = 0L
        val frame = 1_000_000_000L / 144
        val xs = ArrayList<Float>()
        repeat(144 * 6) { live.frame(t, sink) { }; xs += sink.xs.getValue(0); t += frame }
        // between frames, a walker moves at most its speed times the frame time (plus rounding): no step-sized jumps
        val maxStep = SceneSimulation.WALK_SPEED / 144f * 1.05f + 1e-4f
        for (i in 1 until xs.size) assertTrue(abs(xs[i] - xs[i - 1]) <= maxStep, "frame $i moved ${abs(xs[i] - xs[i - 1])}")
        assertTrue(sink.activities.isNotEmpty() && sink.activities.first().second == Activity.IDLE)
    }

    @Test fun theStageTransformMatchesTheEmbassyTurn() {
        // the embassy's: a scene point p is drawn at origin + (p.y, -p.x)
        val live = LiveScene(listOf("a"), emptySet(), emptyMap(), room, 9f, 0f, 1.5f, (-Math.PI / 2).toFloat(), Random(1))
        val sink = object : LiveScene.Sink {
            var at = Vec3(0f, 0f, 0f)
            override fun camera(eyeX: Float, eyeY: Float, eyeZ: Float, atX: Float, atY: Float, atZ: Float, fov: Float) {}
            override fun place(index: Int, x: Float, y: Float, z: Float, facing: Float, furniture: SceneObject?, sx: Float, sy: Float, sz: Float) { at = Vec3(x, y, z) }
            override fun activity(index: Int, activity: Activity) {}
        }
        live.frame(0L, sink) { }
        // the first person starts at (-1.2, -0.6) in the scene
        assertEquals(9f - 0.6f, sink.at.x, 1e-4f)
        assertEquals(1.2f, sink.at.y, 1e-4f)
        assertEquals(1.5f, sink.at.z, 1e-4f)
        assertTrue(hypot(sink.at.x, sink.at.y) > 0f)
    }
}
