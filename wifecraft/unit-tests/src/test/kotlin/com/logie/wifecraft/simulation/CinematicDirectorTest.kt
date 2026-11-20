package com.logie.wifecraft.simulation

import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cinematic mode's camera director, look and settings (simulation/CinematicDirector.kt, CinematicSettings.kt). */
class CinematicDirectorTest {
    private val dt = 1f / 60f
    private val floor = 0f

    /** A room 8 x 7 yards (x -4..4, y -3.5..3.5), its walls known only to the walk test along the floor. */
    private val walls = CameraClearance { eye, _ -> eye.x > -4f && eye.x < 4f && eye.y > -3.5f && eye.y < 3.5f }
    private val bedLo = Vec3(1.0f, -1.0f, 0f)
    private val bedHi = Vec3(3.2f, 1.0f, 1.3f)

    /** Two people standing a yard apart near the middle (hips and heads), the camera across the room. */
    private fun stage(clear: CameraClearance? = walls, obstacles: Boolean = true) = CinematicStage(4).apply {
        count = 2
        person(0, -0.5f, 0f, 1.0f, -0.5f, 0f, 1.65f)
        person(1, 0.5f, 0.2f, 1.0f, 0.5f, 0.2f, 1.6f)
        floorZ = floor
        clearance = clear
        if (obstacles) this.obstacles = listOf(bedLo to bedHi)
        camera(-3.5f, -3f, 2.2f, 0f, 0f, 1.2f, 55f)
    }

    private class Run(val eyes: List<Vec3>, val ats: List<Vec3>, val shots: List<CinematicShot>)

    private fun run(director: CinematicDirector, st: CinematicStage, seconds: Float, each: (Int) -> Unit = {}): Run {
        val eyes = ArrayList<Vec3>(); val ats = ArrayList<Vec3>()
        val frames = (seconds / dt).toInt()
        for (k in 0 until frames) {
            each(k)
            assertTrue(director.update(dt, st))
            eyes += Vec3(director.eyeX, director.eyeY, director.eyeZ)
            ats += Vec3(director.atX, director.atY, director.atZ)
        }
        return Run(eyes, ats, director.history.toList())
    }

    private fun dist(a: Vec3, b: Vec3) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

    // ---- the shot list's rules

    @Test fun mixedShotsNeverRepeatTypeOrRecentPreset() {
        val d = CinematicDirector(CinematicSettings(style = CinematicStyle.MIXED), seed = 11)
        val r = run(d, stage(), 240f)
        val shots = r.shots
        assertTrue(shots.size >= 25, "shots ${shots.size}")
        for (i in 1 until shots.size) {
            if (shots[i].type == ShotType.HOLD) continue
            assertTrue(shots[i].type != shots[i - 1].type, "type repeated at $i: ${shots.map { it.type }}")
            val recent = shots.subList(maxOf(0, i - 3), i).map { it.preset }
            assertFalse(shots[i].preset in recent, "preset ${shots[i].preset} within the last three")
        }
        for (s in shots) assertTrue(s.seconds in 4f..9f, "seconds ${s.seconds}")
        // a mix: at least five kinds of shot in four minutes, no move is a cut in the mixed style unless no move was clear
        assertTrue(shots.map { it.type }.toSet().size >= 5, "kinds ${shots.map { it.type }.toSet()}")
        assertTrue(shots.count { it.cut } <= shots.size / 4, "cuts ${shots.count { it.cut }} of ${shots.size}")
        assertTrue(shots.none { it.type == ShotType.HOLD }, "a hold: nothing fitted")
    }

    @Test fun slowOrbitStyleOnlyOrbitsAndShotLengthsFollowSettings() {
        val d = CinematicDirector(CinematicSettings(style = CinematicStyle.SLOW_ORBIT, shortest = 6, longest = 7), seed = 3)
        val r = run(d, stage(), 90f)
        assertTrue(r.shots.isNotEmpty())
        assertTrue(r.shots.all { it.type == ShotType.ORBIT }, "${r.shots.map { it.type }}")
        assertTrue(r.shots.all { it.seconds in 6f..7f })
        // each orbit's arc: 20 to 60 degrees
        for (s in r.shots) {
            val arc = Math.toDegrees(abs(s.to.yaw - s.from.yaw).toDouble())
            assertTrue(arc in 19.9..60.1, "arc $arc")
            assertTrue(s.from.distance in 2f..4.01f, "distance ${s.from.distance}")
        }
    }

    @Test fun cutsStyleCutsAndMixedMovesSmoothly() {
        val cuts = run(CinematicDirector(CinematicSettings(style = CinematicStyle.CUTS), seed = 5), stage(), 60f)
        assertTrue(cuts.shots.all { it.cut })
        // the mixed style: no frame moves the eye more than a slow camera would (no jump), and its speed changes gently
        val mixed = run(CinematicDirector(CinematicSettings(style = CinematicStyle.MIXED), seed = 5), stage(), 120f)
        var worstStep = 0f
        var worstAccel = 0f
        for (k in 2 until mixed.eyes.size) {
            val v1 = dist(mixed.eyes[k - 1], mixed.eyes[k - 2]) / dt
            val v2 = dist(mixed.eyes[k], mixed.eyes[k - 1]) / dt
            worstStep = maxOf(worstStep, dist(mixed.eyes[k], mixed.eyes[k - 1]))
            worstAccel = maxOf(worstAccel, abs(v2 - v1) / dt)
        }
        assertEquals(0, mixed.shots.count { it.cut }, "a cut in the mixed style (no move was clear)")
        // under 4 yards a second, and no sudden change of speed
        assertTrue(worstStep < 0.07f, "a jump of $worstStep yards in a frame")
        assertTrue(worstAccel < 8f, "acceleration $worstAccel yd/s2")
    }

    // ---- clearance

    @Test fun everyEyeStaysInTheRoomOutOfTheBedAndAboveTheFloor() {
        val st = stage()
        val r = run(CinematicDirector(CinematicSettings(style = CinematicStyle.MIXED), seed = 21), st, 240f)
        for ((k, e) in r.eyes.withIndex()) {
            assertTrue(e.x > -4.1f && e.x < 4.1f && e.y > -3.6f && e.y < 3.6f, "eye $e outside the room at frame $k")
            assertTrue(e.z >= floor + CinematicDirector.FLOOR_GAP - 1e-3f && e.z <= floor + CinematicDirector.CEILING_H, "eye height $e")
            val inBed = e.x > bedLo.x && e.x < bedHi.x && e.y > bedLo.y && e.y < bedHi.y && e.z < bedHi.z
            assertFalse(inBed, "eye $e inside the bed at frame $k")
        }
    }

    @Test fun eyeBehindAWallOrInFurnitureOrTooSteepIsRejected() {
        val d = CinematicDirector(seed = 1)
        val st = stage()
        val focus = d.rawFocus(st)
        // clear: in the room, in front of them
        assertTrue(d.eyeGood(st, focus, ShotFrame(-2.5f, 0.3f, 2.5f, Vec3(0f, 0f, 0f), 50f), emptyList()))
        // through the wall at x = -4
        assertFalse(d.eyeGood(st, focus, ShotFrame(Math.PI.toFloat(), 0.3f, 6f, Vec3(0f, 0f, 0f), 50f), emptyList()))
        // inside the bed's box (to the +x side, low)
        assertFalse(d.eyeGood(st, focus, ShotFrame(0f, -0.05f, 2.1f, Vec3(0f, 0f, -0.4f), 50f), emptyList()))
        // looking straight down, or up from under the floor
        assertFalse(d.eyeGood(st, focus, ShotFrame(1f, 1.4f, 2f, Vec3(0f, 0f, 0f), 50f), emptyList()))
        assertFalse(d.eyeGood(st, focus, ShotFrame(1f, -0.6f, 3f, Vec3(0f, 0f, 0f), 50f), emptyList()))
        // into a person
        assertFalse(d.eyeGood(st, focus, ShotFrame(Math.PI.toFloat(), 0.4f, 0.55f, Vec3(0f, 0f, 0f), 50f), emptyList()))
    }

    @Test fun aTightRoomStillGetsShotsAndNoWideOneThatDoesNotFit() {
        // 4.4 x 4.4 yards: a wide establishing shot (6+ yards) can't be had in it
        val tight = CameraClearance { eye, _ -> abs(eye.x) < 2.2f && abs(eye.y) < 2.2f }
        val r = run(CinematicDirector(CinematicSettings(style = CinematicStyle.MIXED), seed = 8), stage(tight, obstacles = false), 120f)
        assertTrue(r.shots.size >= 12)
        assertTrue(r.shots.none { it.type == ShotType.WIDE }, "${r.shots.map { it.type }}")
        for (e in r.eyes) assertTrue(abs(e.x) < 2.35f && abs(e.y) < 2.35f, "eye $e")
    }

    // ---- framing

    @Test fun framingMathsProjectsAndFits() {
        val eye = Vec3(-3f, 0f, 1.5f)
        val at = Vec3(0f, 0f, 1.5f)
        val out = FloatArray(2)
        assertTrue(CinematicFraming.project(eye, at, 50f, 16f / 9f, at, out))
        assertEquals(0f, out[0], 1e-5f); assertEquals(0f, out[1], 1e-5f)
        // a point at the picture's top edge: tan(25 deg) * 3 above the middle; to the right: -y (looking +x, right is -y)
        val top = Vec3(0f, 0f, 1.5f + 3f * Math.tan(Math.toRadians(25.0)).toFloat())
        CinematicFraming.project(eye, at, 50f, 16f / 9f, top, out)
        assertEquals(1f, out[1], 1e-4f)
        CinematicFraming.project(eye, at, 50f, 16f / 9f, Vec3(0f, -1f, 1.5f), out)
        assertTrue(out[0] > 0f)
        // behind the camera: no place in the picture
        assertFalse(CinematicFraming.project(eye, at, 50f, 16f / 9f, Vec3(-5f, 0f, 1.5f), out))
        // margins and the field of view opened to fit
        val pts = listOf(Vec3(0f, 0f, 0.4f), Vec3(0f, 0f, 2.6f))
        assertFalse(CinematicFraming.inFrame(eye, at, 30f, 16f / 9f, pts, 0.1f))
        val f = assertNotNull(CinematicFraming.fit(eye, at, 30f, 70f, 16f / 9f, pts, 0.1f))
        assertTrue(f in 30f..70f && CinematicFraming.inFrame(eye, at, f, 16f / 9f, pts, 0.1f))
        assertNull(CinematicFraming.fit(eye, at, 30f, 40f, 16f / 9f, listOf(Vec3(0f, 0f, -3f)), 0.1f))
    }

    @Test fun peopleStayInTheShotsPicture() {
        val st = stage()
        val d = CinematicDirector(CinematicSettings(style = CinematicStyle.MIXED), seed = 13)
        var outside = 0
        var frames = 0
        for (k in 0 until (180f / dt).toInt()) {
            d.update(dt, st)
            val s = d.shot ?: continue
            // once a shot's move is over, everyone it frames is in the picture (a close-up and a shoulder shot: the heads)
            if (s.type == ShotType.HOLD || d.shotTime < s.travel + 0.6f) continue
            val pts = when (s.type) {
                ShotType.CLOSE_UP, ShotType.OVER_SHOULDER -> listOf()
                else -> (0 until st.count).flatMap { listOf(st.hip(it), st.head(it)) }
            }
            frames++
            if (!CinematicFraming.inFrame(Vec3(d.eyeX, d.eyeY, d.eyeZ), Vec3(d.atX, d.atY, d.atZ), d.fov, st.aspect, pts, 0.0f)) outside++
        }
        assertTrue(frames > 1000)
        assertTrue(outside <= frames / 50, "people out of the picture in $outside of $frames frames")
    }

    // ---- determinism, the focus moving, furniture

    @Test fun theSameSeedMakesTheSameFilm() {
        val a = run(CinematicDirector(seed = 99), stage(), 40f)
        val b = run(CinematicDirector(seed = 99), stage(), 40f)
        assertEquals(a.shots, b.shots)
        for (k in a.eyes.indices) assertEquals(a.eyes[k], b.eyes[k])
        val c = run(CinematicDirector(seed = 100), stage(), 40f)
        assertTrue(a.shots.map { it.preset } != c.shots.map { it.preset })
    }

    @Test fun theShotFollowsPeopleWalkingWithoutAJump() {
        val st = stage()
        val d = CinematicDirector(CinematicSettings(style = CinematicStyle.SLOW_ORBIT), seed = 4)
        // they walk 2.5 yards along -y over 5 seconds, from 10 s in
        val r = run(d, st, 30f) { k ->
            val t = k * dt
            val y = if (t < 10f) 0f else -minOf(2.5f, (t - 10f) * 0.5f)
            st.person(0, -0.5f, y, 1.0f, -0.5f, y, 1.65f)
            st.person(1, 0.5f, y + 0.2f, 1.0f, 0.5f, y + 0.2f, 1.6f)
        }
        // the point looked at ends by them, and never jumps
        val last = r.ats.last()
        assertTrue(abs(last.y - (-2.4f)) < 0.5f, "looking at $last")
        for (k in 1 until r.ats.size) assertTrue(dist(r.ats[k], r.ats[k - 1]) < 0.08f, "at jumped at $k")
    }

    @Test fun furnitureInUseBringsItsViews() {
        val st = stage(obstacles = false)
        val bed = Furniture("bed", FurnitureSpec.standard(FurnitureKind.BED_DOUBLE), 2f, 0f, 0f, 0f)
        val people = listOf(Vec3(1.8f, -0.3f, 0.9f), Vec3(1.8f, 0.4f, 0.9f))
        val presets = FurnitureCameras.presets(bed, people, RoomBounds(Vec3(-4f, -3.5f, 0f), Vec3(4f, 3.5f, 3.4f)))
        st.person(0, 1.8f, -0.3f, 0.9f, 1.8f, -0.3f, 1.4f)
        st.person(1, 1.8f, 0.4f, 0.9f, 1.8f, 0.4f, 1.4f)
        st.furniture = FurnitureFocus("bed", Vec3(1.8f, 0.05f, 0.9f), presets, 1.5f)
        val r = run(CinematicDirector(CinematicSettings(style = CinematicStyle.MIXED), seed = 2), st, 180f)
        val furn = r.shots.filter { it.type == ShotType.FURNITURE }
        assertTrue(furn.size >= 3, "${r.shots.map { it.type }}")
        assertTrue(furn.map { it.preset }.toSet().size >= 2, "views ${furn.map { it.preset }}")
    }

    // ---- into cinematic mode and out again, through the scene's camera

    @Test fun enteringAndLeavingIsContinuousAndRestoresThePlayersCamera() {
        val room = CameraPose(Vec3(-3.5f, -3f, 2.2f), Vec3(0f, 0f, 1.2f), 55f)
        val cam = SceneCamera(room).also { it.floorZ = floor }
        val st = stage()
        val d = CinematicDirector(seed = 6)
        fun frame(on: Boolean): Vec3 {
            cam.setDirecting(on)
            if (on || cam.directAmount > 0f) {
                st.camera(cam.eyeX, cam.eyeY, cam.eyeZ, cam.atX, cam.atY, cam.atZ, cam.fov)
                if (d.update(dt, st)) cam.directPose(d.eyeX, d.eyeY, d.eyeZ, d.atX, d.atY, d.atZ, d.fov)
            }
            cam.update(dt, false, 0f, 0f, 0f)
            return Vec3(cam.eyeX, cam.eyeY, cam.eyeZ)
        }
        var prev = frame(false)
        assertEquals(room.eye, prev)
        var worst = 0f
        repeat((20f / dt).toInt()) { val e = frame(true); worst = maxOf(worst, dist(e, prev)); prev = e }
        assertEquals(1f, cam.directAmount)
        repeat((3f / dt).toInt()) { val e = frame(false); worst = maxOf(worst, dist(e, prev)); prev = e }
        assertEquals(0f, cam.directAmount)
        // back exactly on the player's camera, and no frame jumped on the way in, through the shots or out
        assertEquals(room.eye.x, cam.eyeX, 1e-4f); assertEquals(room.eye.y, cam.eyeY, 1e-4f); assertEquals(room.eye.z, cam.eyeZ, 1e-4f)
        assertEquals(room.fov, cam.fov, 1e-4f)
        assertTrue(worst < 0.15f, "a jump of $worst yards")
    }

    @Test fun nobodyToFilmWritesNothing() {
        val st = CinematicStage(2).apply { count = 0; floorZ = 0f }
        assertFalse(CinematicDirector(seed = 1).update(dt, st))
    }

    // ---- the settings and the look

    @Test fun settingsKeepAndClamp() {
        val s = CinematicSettings(lighting = 40, style = CinematicStyle.CUTS, letterbox = false, grain = true, hideWords = false,
            shortest = 5, longest = 12, motion = 130)
        assertEquals(s, CinematicSettings.parse(s.toJson().toString()))
        assertEquals(CinematicSettings.DEFAULT, CinematicSettings.parse(null))
        assertEquals(CinematicSettings.DEFAULT, CinematicSettings.parse("{broken"))
        val wild = CinematicSettings.parse("""{"lighting":400,"shortest":20,"longest":1,"motion":5,"style":"NOPE"}""")
        assertEquals(100, wild.lighting); assertEquals(CinematicSettings.SHOT_MAX, wild.shortest)
        assertEquals(CinematicSettings.SHOT_MAX, wild.longest); assertEquals(CinematicSettings.MOTION_MIN, wild.motion)
        assertEquals(CinematicStyle.MIXED, wild.style)
        assertEquals(70, CinematicSettings.DEFAULT.lighting)
    }

    @Test fun lookFadesInAndOut() {
        val look = CinematicLook()
        look.settings = CinematicSettings(lighting = 70, grain = true, letterbox = true)
        assertEquals(0f, look.mood())
        look.on = true
        var t = 0f
        var prev = 0f
        while (look.step(dt)) {
            t += dt
            assertTrue(look.mood() >= prev - 1e-6f); prev = look.mood()
        }
        assertEquals(CinematicLook.FADE_S, t, 2 * dt)
        assertEquals(0.7f, look.mood(), 1e-5f)
        assertEquals(1f, look.letterbox()); assertEquals(CinematicLook.GRAIN, look.grain())
        look.on = false
        repeat((CinematicLook.FADE_S / dt).toInt() + 2) { look.step(dt) }
        assertEquals(0f, look.mood()); assertFalse(look.showing)
        // half way it is half way (eased)
        look.on = true
        repeat((CinematicLook.FADE_S / 2 / dt).toInt()) { look.step(dt) }
        assertEquals(0.35f, look.mood(), 0.03f)
    }

    @Test fun floorReachAsksOncePerDirection() {
        var asked = 0
        val reach = FloorReach(CameraClearance { eye, _ -> asked++; hypot(eye.x, eye.y) < 3f })
        reach.reset(Vec3(0f, 0f, 0f), 0.5f, 0f)
        assertTrue(reach.clear(Vec3(2f, 0.1f, 1.5f)))
        val once = asked
        assertTrue(reach.clear(Vec3(2.4f, 0.12f, 2f)))   // the same direction: asked already
        assertEquals(once, asked)
        assertFalse(reach.clear(Vec3(4f, 0.2f, 1f)))
        assertTrue(reach.clear(Vec3(0.3f, 0.1f, 1f)))   // beside the focus: always
    }
}
