package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JoiCameraTest {
    private fun body(facing: Float = 0f, x: Float = 0f, y: Float = 0f, h: Float = 1.7f) = JoiBody().also {
        it.set(Vec3(x, y, 1.62f * h / 1.7f), Vec3(x, y, 1.25f * h / 1.7f), Vec3(x, y, 0.95f * h / 1.7f), facing, h, 0f, true)
    }

    private fun angleOf(cam: JoiCamera, b: JoiBody): Float {
        val dx = cam.eyeX - cam.atX; val dy = cam.eyeY - cam.atY
        return JoiOrbit.wrap(atan2(dy, dx) - b.facing)
    }

    private fun run(cam: JoiCamera, b: JoiBody, seconds: Float, each: (Float) -> Unit = {}) {
        var t = 0f
        while (t < seconds) { cam.update(1 / 60f, b, 4f / 3f); t += 1 / 60f; each(t) }
    }

    @Test fun eachFramingShowsItsPointsInTheFourByThreeFrame() {
        for (f in listOf(JoiFraming.FACE, JoiFraming.TORSO, JoiFraming.LEGS)) {
            val b = body(); val cam = JoiCamera(5L); cam.framing = f
            var worst = 0
            run(cam, b, 40f) {
                val eye = Vec3(cam.eyeX, cam.eyeY, cam.eyeZ); val at = Vec3(cam.atX, cam.atY, cam.atZ)
                if (!CinematicFraming.inFrame(eye, at, cam.fov, 4f / 3f, JoiFramings.mustShow(f, b), 0.0f)) worst++
            }
            assertTrue(worst <= 2, "$f: ${worst} frames lost its points")
        }
    }

    @Test fun faceIsTighterThanTorsoIsTighterThanLegs() {
        fun dist(f: JoiFraming): Float {
            val cam = JoiCamera(5L); cam.framing = f; val b = body()
            cam.update(0.016f, b, 4f / 3f)
            return hypot(hypot(cam.eyeX - cam.atX, cam.eyeY - cam.atY), cam.eyeZ - cam.atZ)
        }
        assertTrue(dist(JoiFraming.FACE) < dist(JoiFraming.TORSO))
        assertTrue(dist(JoiFraming.TORSO) < dist(JoiFraming.LEGS))
    }

    @Test fun legsUpIsALowCameraLookingUp() {
        val cam = JoiCamera(3L); cam.framing = JoiFraming.LEGS; val b = body()
        run(cam, b, 20f) {
            assertTrue(cam.eyeZ < cam.atZ - 0.3f, "eye ${cam.eyeZ} at ${cam.atZ}")
            assertTrue(cam.eyeZ < 0.9f)
        }
    }

    @Test fun theCameraStaysInFrontOfHerWithinTheCone() {
        for (f in JoiFraming.entries) {
            val b = body(facing = 1.1f, x = 3f, y = -2f); val cam = JoiCamera(11L); cam.framing = f
            run(cam, b, 120f) { assertTrue(abs(angleOf(cam, b)) <= JoiFramings.CONE + 0.12f, "$f at ${angleOf(cam, b)}") }
        }
    }

    @Test fun whenSheTurnsTheCameraFollowsHerFront() {
        val b = body(0f); val cam = JoiCamera(2L); cam.framing = JoiFraming.TORSO
        run(cam, b, 10f)
        b.facing = 2.4f
        run(cam, b, 12f)
        assertTrue(abs(angleOf(cam, b)) <= JoiFramings.CONE + 0.12f)
    }

    @Test fun theCameraTracksHerPointsAsTheyMove() {
        val b = body(); val cam = JoiCamera(4L); cam.framing = JoiFraming.FACE
        run(cam, b, 3f)
        // a dance lifts and sways her head
        val moved = JoiBody().also { it.set(Vec3(0.25f, 0.2f, 1.45f), Vec3(0.1f, 0.1f, 1.15f), Vec3(0f, 0f, 0.9f), 0f, 1.7f, 0f, true) }
        run(cam, moved, 3f)
        assertEquals(0.25f, cam.atX, 0.12f)
        assertEquals(1.45f - 0.1f * 0.2f, cam.atZ, 0.12f)
    }

    @Test fun cutsHappenWithinEachFramingAtVariedAnglesAndHoldTimes() {
        val b = body(); val cam = JoiCamera(8L); cam.framing = JoiFraming.TORSO
        run(cam, b, 120f)
        val shots = cam.history
        assertTrue(shots.size in 12..40, "${shots.size} shots in 120 s")
        assertTrue(shots.all { it.framing == JoiFraming.TORSO })
        for (i in 1 until shots.size) assertTrue(abs(shots[i].yawOffset - shots[i - 1].yawOffset) >= JoiCutPlan.MIN_TURN - 1e-4f, "cut $i is no new angle")
        assertTrue(shots.map { it.seconds }.toSet().size > 5)
        assertTrue(shots.all { it.seconds in 3.4f..8.1f })
    }

    @Test fun autoMixesTheFramingsAndNeverRepeatsOne() {
        val b = body(); val cam = JoiCamera(6L); cam.framing = JoiFraming.AUTO
        run(cam, b, 240f)
        val fr = cam.history.map { it.framing }
        assertEquals(setOf(JoiFraming.FACE, JoiFraming.TORSO, JoiFraming.LEGS), fr.toSet())
        for (i in 1 until fr.size) assertNotEquals(fr[i - 1], fr[i])
        assertFalse(fr.any { it == JoiFraming.AUTO })
    }

    @Test fun changingTheFramingCutsAtOnce() {
        val b = body(); val cam = JoiCamera(1L); cam.framing = JoiFraming.FACE
        run(cam, b, 1f)
        cam.framing = JoiFraming.LEGS
        cam.update(1 / 60f, b, 4f / 3f)
        assertEquals(JoiFraming.LEGS, cam.shot!!.framing)
        assertTrue(cam.eyeZ < 1f)
    }

    @Test fun aWallInTheWayMovesTheEyeToAClearSpot() {
        val b = body(); val cam = JoiCamera(9L); cam.framing = JoiFraming.TORSO
        // a wall on her right: only an eye on her left (y > 0.5) sees her
        cam.visible = { eye, _ -> eye.y > 0.5f }
        run(cam, b, 60f) { }
        assertTrue(cam.history.isNotEmpty())
        cam.update(1 / 60f, b, 4f / 3f)
        assertTrue(cam.eyeY > 0.3f, "eye y ${cam.eyeY}")
    }

    @Test fun theSameSeedGivesTheSameCameraAndAnotherSeedAnother() {
        fun poses(seed: Long): List<Float> {
            val b = body(); val cam = JoiCamera(seed); cam.framing = JoiFraming.AUTO
            val out = ArrayList<Float>()
            run(cam, b, 30f) { out += cam.eyeX }
            return out
        }
        assertEquals(poses(3L), poses(3L))
        assertNotEquals(poses(3L), poses(4L))
    }

    @Test fun anUnusableBodyWritesNothing() {
        val cam = JoiCamera(1L)
        assertFalse(cam.update(0.016f, JoiBody().also { it.height = 0f }, 1.33f))
    }

    // ---- Sindragosa

    private fun dragon(): JoiBody = JoiBody().also { it.set(Vec3(2.15f, -0.35f, 1.75f), Vec3(1f, 0f, 1.5f), Vec3(-0.8f, 0f, 1.2f), 0f, 3f, 0f, false) }

    @Test fun theOrbitCirclesHerHeadRoundTheWholeTurn() {
        val b = dragon(); val cam = JoiCamera(2L); cam.creature = true; cam.creatureScale = 0.25f
        val azs = ArrayList<Float>()
        run(cam, b, 140f) { azs += cam.orbitAz }
        var turned = 0f
        for (i in 1 until azs.size) turned += JoiOrbit.wrap(azs[i] - azs[i - 1])
        assertTrue(turned > 2f * PI.toFloat() * 1.8f, "turned $turned rad in 140 s")
        // it looks at the head the whole time
        assertEquals(b.head.x, cam.atX, 0.1f)
        assertEquals(b.head.z, cam.atZ, 0.1f)
    }

    @Test fun theOrbitsRadiusAndHeightVaryWithinBoundsAndTheHumanFramingsAreIgnored() {
        val b = dragon(); val cam = JoiCamera(2L); cam.creature = true; cam.framing = JoiFraming.LEGS
        val r = ArrayList<Float>(); val h = ArrayList<Float>()
        run(cam, b, 200f) {
            val p = cam.orbitPose!!
            r += p.radius; h += p.height
        }
        assertTrue(r.min() >= 1.8f - 1e-3f && r.max() <= 3.4f + 1e-3f, "radius ${r.min()}..${r.max()}")
        assertTrue(r.max() - r.min() > 0.8f)
        assertTrue(h.min() >= -0.2f - 1e-3f && h.max() <= 1.8f + 1e-3f, "height ${h.min()}..${h.max()}")
        assertTrue(h.max() - h.min() > 0.8f)
        assertTrue(cam.history.isEmpty(), "no human cuts in the orbit")
    }

    @Test fun behindTheHeadTheEyeRisesAndDrawsBack() {
        val front = JoiOrbit.at(0f, 0f, 0f, 0.25f, 0f)
        var behindR = 0f; var behindH = 0f
        for (t in 0 until 200) {
            val p = JoiOrbit.at(t.toFloat(), PI.toFloat(), 0f, 0.25f, 0f)
            behindR = if (t == 0) p.radius else minOf(behindR, p.radius)
            behindH = if (t == 0) p.height else minOf(behindH, p.height)
        }
        assertTrue(behindR >= 3.3f - 1e-3f && behindH >= 1.8f - 1e-3f, "behind: r $behindR h $behindH")
        assertTrue(front.height < 1.8f || front.radius < 3.3f)
    }

    @Test fun theEyePassesBehindTheHeadQuickerThanInFront() {
        assertTrue(JoiOrbit.speed(0f, 1f) > 2.4f * JoiOrbit.speed(0f, 0f))
        assertEquals(0f, JoiOrbit.behind(0f, 0f))
        assertEquals(1f, JoiOrbit.behind(PI.toFloat(), 0f))
    }

    @Test fun theOrbitScalesWithTheCreature() {
        val a = JoiOrbit.at(10f, 0.3f, 0f, 0.25f, 1f)
        val b = JoiOrbit.at(10f, 0.3f, 0f, 0.5f, 1f)
        assertEquals(a.radius * 2f, b.radius, 1e-4f)
        assertEquals(a.height * 2f, b.height, 1e-4f)
    }

    @Test fun theOrbitSpeedIsAlwaysSlowAndNeverStill() {
        for (t in 0 until 300) {
            val w = JoiOrbit.speed(t.toFloat())
            val secondsPerTurn = 2f * PI.toFloat() / w
            assertTrue(secondsPerTurn in 44f..76f, "$secondsPerTurn at $t")
        }
    }
}
