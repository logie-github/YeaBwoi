package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Posture
import com.logie.wifecraft.story.Vec3
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Cinematic mode's shot library (simulation/ShotLibrary.kt, assets/shot_library.json) and the director's use of it. */
class ShotLibraryTest {
    private val dt = 1f / 60f
    private val lib: ShotLibrary by lazy {
        val dir = System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets"
        ShotLibrary.parse(File(dir, "shot_library.json").readText())
    }

    /** A room 8 x 7 yards with a bed's box to +x; two people 0.8 yards apart near the middle, playing [action]. */
    private val walls = CameraClearance { eye, _ -> eye.x > -4f && eye.x < 4f && eye.y > -3.5f && eye.y < 3.5f }
    private val bedLo = Vec3(1.4f, -1.0f, 0f)
    private val bedHi = Vec3(3.6f, 1.0f, 1.3f)

    private fun stage(action: ShotAction?) = CinematicStage(4).apply {
        count = 2
        person(0, -0.4f, 0f, 0.6f, -0.4f, 0f, 1.25f)
        person(1, 0.4f, 0.1f, 0.6f, 0.4f, 0.1f, 1.25f)
        floorZ = 0f
        clearance = walls
        obstacles = listOf(bedLo to bedHi)
        camera(-3.5f, -3f, 2.2f, 0f, 0f, 1.0f, 55f)
        this.action = action
    }

    private fun director(settings: CinematicSettings = CinematicSettings(), seed: Long = 7) =
        CinematicDirector(settings, seed).also { it.library = lib }

    private fun libId(s: CinematicShot) = if (s.preset.startsWith(CinematicDirector.LIBRARY))
        s.preset.removePrefix(CinematicDirector.LIBRARY).substringBefore(':') else null

    private class Frame(val eye: Vec3, val shot: CinematicShot?, val spec: ShotSpec?, val now: ShotFrame?, val plain: ShotFrame, val t: Float)

    private fun run(d: CinematicDirector, st: CinematicStage, seconds: Float, each: (Int) -> Unit = {}): List<Frame> {
        val out = ArrayList<Frame>()
        for (k in 0 until (seconds / dt).toInt()) {
            each(k)
            assertTrue(d.update(dt, st))
            out += Frame(Vec3(d.eyeX, d.eyeY, d.eyeZ), d.shot, d.spec, d.frameNow, d.frameAt(d.shotTime), d.shotTime)
        }
        return out
    }

    // ---- the data

    @Test fun everyClassHasThreeToSixShotsThatExist() {
        for (cls in ShotClass.entries) {
            val set = lib.sets[cls]
            assertNotNull(set, "no set for $cls")
            assertTrue(set.size in 3..6, "$cls has ${set.size}")
            for (c in set) {
                assertTrue(c.shot in lib.shots, "$cls names a missing shot ${c.shot}")
                assertTrue(c.weight > 0f)
            }
            // every set can set the scene and come in close
            val sizes = lib.set(cls).map { it.first.size }.toSet()
            assertTrue(ShotSize.WIDE in sizes, "$cls has no wide shot")
            assertTrue(ShotSize.CLOSE in sizes || ShotSize.DETAIL in sizes, "$cls has no close shot")
        }
        for (s in lib.shots.values) {
            assertTrue(s.azimuth.lo >= 0f && s.azimuth.hi <= 180f, s.id)
            assertTrue(s.pitch.hi * Math.PI / 180 <= CinematicDirector.MAX_PITCH, s.id)
            assertTrue(s.dwell.lo >= 1f && s.fov.hi <= CinematicDirector.MAX_FOV, s.id)
        }
        assertEquals(lib.cadence.first(), ShotSize.WIDE)
    }

    @Test fun animationsAreClassifiedByTheirNamesThenTheirPostures() {
        assertEquals(ShotClass.FACING_LYING, lib.classify(listOf("Missionary_loop")))
        assertEquals(ShotClass.REAR, lib.classify(listOf("pack: doggy style 2")))
        assertEquals(ShotClass.RIDING, lib.classify(listOf("ReverseCowgirl_A")))
        assertEquals(ShotClass.ORAL, lib.classify(listOf("kneeling_blowjob_start")))
        assertEquals(ShotClass.MANUAL, lib.classify(listOf("handjob_stage_2")))
        assertEquals(ShotClass.KISS, lib.classify(listOf("Kiss on the couch")))
        assertEquals(ShotClass.SIDE_LYING, lib.classify(listOf("spooning")))
        assertEquals(ShotClass.STANDING, lib.classify(listOf("standing_against_wall")))
        // the more specific word wins (a kiss while seated is still a kiss; oral seated is oral)
        assertEquals(ShotClass.SEATED, lib.classify(listOf("Seated kiss")))
        assertEquals(ShotClass.ORAL, lib.classify(listOf("seated_oral")))
        assertEquals(ShotClass.PARTNER, lib.classify(listOf("pair:abc:0", "Animation 7")))
        assertEquals(ShotClass.SOLO, lib.classify(listOf("dance"), count = 1))
        // no word: the bodies
        assertEquals(ShotClass.REAR, lib.classify(listOf("x"), listOf(Posture.ALL_FOURS, Posture.KNEEL)))
        assertEquals(ShotClass.FACING_LYING, lib.classify(listOf("x"), listOf(Posture.LIE_BACK, Posture.KNEEL)))
        assertEquals(ShotClass.RIDING, lib.classify(listOf("x"), listOf(Posture.LIE_BACK, Posture.CROUCH)))
        assertEquals(ShotClass.ORAL, lib.classify(listOf("x"), listOf(Posture.STAND, Posture.KNEEL)))
        assertEquals(ShotClass.SEATED, lib.classify(listOf("x"), listOf(Posture.SIT, Posture.SIT)))
        assertEquals(ShotClass.STANDING, lib.classify(listOf("x"), listOf(Posture.STAND, Posture.STAND)))
        assertEquals(ShotClass.PARTNER, lib.classify(listOf("x"), listOf(Posture.UNKNOWN, Posture.STAND)))
    }

    // ---- the director's choice

    @Test fun eachClassGetsItsOwnShotsAndNeverTheSameTwiceRunning() {
        for (cls in ShotClass.entries) {
            val d = director(seed = 31L + cls.ordinal)
            val frames = run(d, stage(ShotAction(cls)), 150f)
            val shots = d.history
            val allowed = lib.set(cls).map { it.first.id }.toSet()
            val ids = shots.mapNotNull(::libId)
            assertTrue(ids.size >= shots.size - 1 && ids.size >= 12, "$cls: ${shots.map { it.preset }}")
            assertTrue(ids.all { it in allowed }, "$cls used ${ids.filter { it !in allowed }}")
            for (i in 1 until ids.size) assertTrue(ids[i] != ids[i - 1], "$cls repeated ${ids[i]} at $i: $ids")
            // a variety: at least three of the set seen
            assertTrue(ids.toSet().size >= 3, "$cls: ${ids.toSet()}")
            assertTrue(frames.isNotEmpty())
        }
    }

    @Test fun classicStartsWideAndTheStylesWeighTheSizes() {
        val first = director(CinematicSettings(randomness = 0), seed = 3)
        run(first, stage(ShotAction(ShotClass.FACING_LYING)), 1f)
        assertEquals(ShotSize.WIDE, first.spec?.size, "the first shot sets the scene")
        fun share(style: ShotStyle, close: Boolean): Float {
            var n = 0; var hit = 0
            for (seed in 1L..6L) {
                val d = director(CinematicSettings(shotStyle = style), seed = seed)
                run(d, stage(ShotAction(ShotClass.PARTNER)), 120f)
                for (s in d.history) {
                    val sp = lib.shots[libId(s) ?: continue] ?: continue
                    n++
                    if ((sp.size == ShotSize.CLOSE || sp.size == ShotSize.DETAIL) == close) hit++
                }
            }
            return hit.toFloat() / n
        }
        val wideInWide = share(ShotStyle.WIDE, close = false)
        val closeInCloseUps = share(ShotStyle.CLOSE_UPS, close = true)
        val closeInWide = share(ShotStyle.WIDE, close = true)
        assertTrue(wideInWide > 0.75f, "Wide: $wideInWide wide or medium")
        assertTrue(closeInCloseUps > 0.4f, "Close-ups: $closeInCloseUps close")
        assertTrue(closeInCloseUps > closeInWide * 2f)
    }

    @Test fun theCameraKeepsOneSideOfTheLine() {
        for (seed in 1L..4L) {
            val d = director(seed = seed)
            val frames = run(d, stage(ShotAction(ShotClass.REAR)), 120f)
            val side = d.lineSide
            assertTrue(side != 0f)
            var checked = 0
            for (f in frames) {
                val sp = f.spec ?: continue
                val now = f.now ?: continue
                if (sp.crossLine) continue
                val s = f.shot ?: continue
                // judged once the shot is reached (a move between shots may swing round the far way)
                if (!s.cut && f.t < s.travel) continue
                val az = d.azimuthOf(now)
                if (abs(az) < 1e-3f) continue
                checked++
                assertTrue(az > 0f && az < 180f, "seed $seed: azimuth $az in ${sp.id}")
            }
            assertTrue(checked > 1000, "checked $checked")
            assertEquals(side, d.lineSide, "the side never changes")
        }
    }

    @Test fun everyEyeIsClearOfTheWallsTheBedAndTheFloor() {
        val st = stage(ShotAction(ShotClass.FACING_LYING))
        val d = director(CinematicSettings(randomness = 100), seed = 12)
        val frames = run(d, st, 180f)
        for ((k, f) in frames.withIndex()) {
            val e = f.eye
            assertTrue(e.x > -4.1f && e.x < 4.1f && e.y > -3.6f && e.y < 3.6f, "eye $e outside the room at $k")
            assertTrue(e.z >= CinematicDirector.FLOOR_GAP - 1e-3f, "eye under the floor gap $e")
            val inBed = e.x > bedLo.x && e.x < bedHi.x && e.y > bedLo.y && e.y < bedHi.y && e.z < bedHi.z
            assertFalse(inBed, "eye $e inside the bed at $k")
        }
        // the drifted frame itself passes the director's clearance test
        val focus = d.rawFocus(st)
        val bad = frames.count { f -> f.spec != null && f.now != null && !d.eyeGood(st, focus, f.now, emptyList()) }
        assertTrue(bad <= frames.size / 100, "$bad frames not clear")
    }

    @Test fun theDriftStaysWithinTheShotAndRandomnessZeroHoldsStill() {
        val d = director(CinematicSettings(randomness = 100), seed = 5)
        val frames = run(d, stage(ShotAction(ShotClass.STANDING)), 120f)
        var moved = 0f
        for (f in frames) {
            val sp = f.spec ?: continue
            val now = f.now ?: continue
            val deg = Math.toDegrees(abs(OrbitMath.wrap(now.yaw - f.plain.yaw)).toDouble()).toFloat()
            val limit = minOf(sp.azimuth.half, CinematicDirector.DRIFT_AZ_MAX) + sp.sway + 0.01f
            assertTrue(deg <= limit, "drift $deg over $limit in ${sp.id}")
            assertTrue(abs(now.distance / f.plain.distance - 1f) <= CinematicDirector.DRIFT_DIST + 1e-4f)
            moved = maxOf(moved, deg)
        }
        assertTrue(moved > 1f, "it drifts ($moved degrees)")
        // no randomness and no sway: exactly the shot
        val still = director(CinematicSettings(randomness = 0, shotStyle = ShotStyle.CLASSIC), seed = 5)
        for (f in run(still, stage(ShotAction(ShotClass.KISS)), 60f)) {
            val sp = f.spec ?: continue
            if (sp.sway > 0f) continue
            assertEquals(f.plain, f.now)
        }
    }

    @Test fun theSameSeedMakesTheSameFilmAndAnotherSeedAnother() {
        val a = run(director(seed = 77), stage(ShotAction(ShotClass.RIDING)), 60f)
        val b = run(director(seed = 77), stage(ShotAction(ShotClass.RIDING)), 60f)
        for (k in a.indices) assertEquals(a[k].eye, b[k].eye)
        val c = run(director(seed = 78), stage(ShotAction(ShotClass.RIDING)), 60f)
        assertTrue(a.indices.any { a[it].eye != c[it].eye })
    }

    @Test fun aNewStagePicksAgainOnTheSameSide() {
        val st = stage(ShotAction(ShotClass.ORAL, SegmentKind.START, "oral:start"))
        val d = director(seed = 9)
        run(d, st, 3f)
        val before = d.history.size
        val side = d.lineSide
        st.action = ShotAction(ShotClass.ORAL, SegmentKind.LOOP, "oral:loop")
        run(d, st, 1.2f)
        assertTrue(d.history.size > before, "no new shot on the stage change")
        assertEquals(side, d.lineSide)
        // the animation ends: back to the director's own mix
        st.action = null
        run(d, st, 1.2f)
        assertEquals(null, d.spec)
        assertEquals(null, libId(d.history.last()))
    }

    @Test fun smoothOnlyNeverCutsAndCutsAllowedDoes() {
        val smooth = director(CinematicSettings(allowCuts = false), seed = 4)
        run(smooth, stage(ShotAction(ShotClass.KISS)), 150f)
        assertTrue(smooth.history.none { it.cut && libId(it) != null }, "${smooth.history.map { it.preset to it.cut }}")
        val cuts = director(CinematicSettings(allowCuts = true), seed = 4)
        run(cuts, stage(ShotAction(ShotClass.KISS)), 150f)
        assertTrue(cuts.history.count { it.cut } >= 3, "${cuts.history.map { it.preset to it.cut }}")
        // the Cuts style still cuts everything; Slow orbit still only orbits (the library is not used)
        val all = director(CinematicSettings(style = CinematicStyle.CUTS), seed = 4)
        run(all, stage(ShotAction(ShotClass.KISS)), 60f)
        assertTrue(all.history.all { it.cut })
        val orbit = director(CinematicSettings(style = CinematicStyle.SLOW_ORBIT), seed = 4)
        run(orbit, stage(ShotAction(ShotClass.KISS)), 60f)
        assertTrue(orbit.history.all { it.type == ShotType.ORBIT && libId(it) == null })
    }

    @Test fun theNewSettingsKeepAndClamp() {
        val s = CinematicSettings(shotStyle = ShotStyle.CLOSE_UPS, randomness = 80, allowCuts = false)
        assertEquals(s, CinematicSettings.parse(s.toJson().toString()))
        val old = CinematicSettings.parse("""{"lighting":50,"style":"MIXED"}""")
        assertEquals(ShotStyle.CLASSIC, old.shotStyle); assertEquals(CinematicSettings.DEFAULT.randomness, old.randomness)
        assertEquals(100, CinematicSettings.parse("""{"randomness":400,"shotStyle":"NOPE"}""").randomness)
        assertEquals(ShotStyle.CLASSIC, CinematicSettings.parse("""{"shotStyle":"NOPE"}""").shotStyle)
    }
}
