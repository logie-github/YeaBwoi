package com.logie.wifecraft.simulation

import com.logie.wifecraft.anim.Anim
import com.logie.wifecraft.anim.BodyGeometry
import com.logie.wifecraft.anim.BoneTrack
import com.logie.wifecraft.anim.Hints
import com.logie.wifecraft.anim.PoseFixtures
import com.logie.wifecraft.anim.Requirement
import com.logie.wifecraft.anim.RequirementClassifier
import com.logie.wifecraft.sims.Channel
import com.logie.wifecraft.story.Vec3
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.*

/**
 * Cinematic auto-play: the planner (only animations that fit each place, no repeats, the dwell's bounds, the fallbacks, the
 * seed), the change's order under its cover (choose, cover, swap, cut, reveal), the forced cut's new shot, and the 4:3 frame's
 * maths and taps on many screens. Synthetic furniture and synthetic bodies (PoseFixtures).
 */
class CinematicAutoplayTest {
    private val F = PoseFixtures
    private val dt = 1f / 60f

    private fun moved(a: Anim, dx: Double, dy: Double): Anim = a.copy(tracks = a.tracks.map { t ->
        if (t.bone == "Root" && t.channel == Channel.POSITION) {
            val v = t.values.copyOf()
            for (k in t.times.indices) { v[k * 3] += (dx * BodyGeometry.REF_UNIT).toFloat(); v[k * 3 + 1] += (dy * BodyGeometry.REF_UNIT).toFloat() }
            BoneTrack(t.bone, t.channel, t.times, v)
        } else t
    })

    private fun req(vararg actors: Anim): Requirement = RequirementClassifier.classify(actors.map { BodyGeometry.frames(it) }, Hints())

    private fun piece(kind: FurnitureKind, x: Float, y: Float) = Furniture(kind.name, FurnitureSpec.standard(kind), x, y, 0f, 0f)

    // a room: a double bed, a chair, a table, and the open floor
    private val bed = AutoPlace("BED", SceneObject.BED, piece(FurnitureKind.BED_DOUBLE, 3f, 3f))
    private val chair = AutoPlace("CHAIR", SceneObject.CHAIR, piece(FurnitureKind.CHAIR, -3f, 2f))
    private val table = AutoPlace("TABLE", SceneObject.TABLE, piece(FurnitureKind.TABLE, 2f, -3f))
    private val floor = AutoPlace("floor", null, piece(FurnitureKind.FLOOR, -1f, -1f))
    private val places = listOf(bed, chair, table, floor)

    // two-person clips: standing (the floor only), head to feet lying (the floor or a bed), two lying on a bed's top, two on
    // a seat (a lap: one sitting, one sitting on them)
    private val standing = AutoClip("standing", "Standing", req(F.standing, moved(F.standing, 0.6, 0.0)))
    private val standing2 = AutoClip("standing2", "Standing close", req(F.standing, moved(F.standing, 0.5, 0.1)))
    private val lyingFloor = AutoClip("lying", "Lying", req(F.lyingBack(0.0), moved(F.lyingFront(0.0), -0.5, 0.0)))
    private val bedPair = AutoClip("bedpair", "On the bed", req(F.lyingBack(0.55), moved(F.lyingBack(0.55), 0.0, 0.6)))
    private val clips = listOf(standing, standing2, lyingFloor, bedPair)

    private fun options(cs: List<AutoClip> = clips, ps: List<AutoPlace> = places) = AutoplayPlanner.options(ps, cs)

    @Test fun onlyAnimationsThatFitAPlaceAreOffered() {
        val opts = options()
        assertTrue(opts.isNotEmpty())
        for (o in opts) assertTrue(FurnitureFit.compatible(o.clip.requirement, o.place.furniture), "${o.clip.id} on ${o.place.key}")
        // standing ones only on the floor; the bed's lying pair only on the bed; nothing two-person on a chair or table here
        assertEquals(setOf("floor"), opts.filter { it.clip.id == "standing" }.map { it.place.key }.toSet())
        assertTrue(opts.filter { it.clip.id == "bedpair" }.all { it.place.key == "BED" })
        assertTrue(opts.none { it.place.key == "CHAIR" && it.clip.id.startsWith("standing") })
        // and every beat the planner makes is one of them
        val planner = AutoplayPlanner(7)
        var last: AutoBeat? = null
        repeat(200) {
            val b = planner.next(opts, last, 50)!!
            assertFalse(b.fallback)
            assertTrue(FurnitureFit.compatible(b.clip!!.requirement, b.place!!.furniture))
            last = b
        }
    }

    @Test fun noAnimationPlaysTwiceRunningAndRecentOnesWait() {
        val opts = options()
        val planner = AutoplayPlanner(3)
        var last: AutoBeat? = null
        val seen = ArrayList<String>()
        repeat(300) {
            val b = planner.next(opts, last, 50)!!
            assertNotEquals(last?.clip?.id, b.clip?.id, "a repeat at $it")
            seen += b.clip!!.id
            last = b
        }
        // with a pool of many on one place, none of the last few comes back
        val many = (0 until 8).map { AutoClip("s$it", "S$it", req(F.standing, moved(F.standing, 0.5 + it * 0.02, 0.0))) }
        val one = AutoplayPlanner.options(listOf(floor), many)
        assertEquals(8, one.size)
        val p2 = AutoplayPlanner(11)
        last = null
        val ids = ArrayList<String>()
        repeat(100) { val b = p2.next(one, last, 50)!!; ids += b.clip!!.id; last = b }
        for (i in AutoplayPlanner.RECENT until ids.size)
            assertFalse(ids[i] in ids.subList(i - AutoplayPlanner.RECENT, i), "${ids[i]} came back within ${AutoplayPlanner.RECENT} at $i")
        // it goes from place to place too
        assertTrue(seen.toSet().size >= 3)
    }

    @Test fun eachDwellIsWithinItsBoundsAtEveryPace() {
        val p = AutoplayPlanner(5)
        for (pace in listOf(0, 25, 50, 75, 100)) {
            val r = AutoplayPlanner.dwellRange(pace)
            repeat(200) { val d = p.dwell(pace); assertTrue(d in r, "$d at $pace") }
        }
        assertEquals(25f, AutoplayPlanner.dwellRange(50).start, 1e-4f)
        assertEquals(90f, AutoplayPlanner.dwellRange(50).endInclusive, 1e-4f)
        // slow is longer, quick shorter
        assertTrue(AutoplayPlanner.dwellRange(0).start > AutoplayPlanner.dwellRange(100).endInclusive / 2)
        assertTrue(AutoplayPlanner.dwellRange(100).endInclusive < AutoplayPlanner.dwellRange(0).endInclusive)
    }

    @Test fun theSameSeedMakesTheSameSequence() {
        fun run(seed: Long): List<Triple<String?, String?, Float>> {
            val p = AutoplayPlanner(seed)
            var last: AutoBeat? = null
            return (0 until 50).map { val b = p.next(options(), last, 40)!!; last = b; Triple(b.place?.key, b.clip?.id, b.dwell) }
        }
        assertEquals(run(42), run(42))
        assertNotEquals(run(42), run(43))
    }

    @Test fun withoutFurnitureItStaysOnTheFloorAndWithoutClipsItPlaysTheBaseOnes() {
        // no furniture: everything on the floor
        val p = AutoplayPlanner(1)
        val floorOnly = AutoplayPlanner.options(listOf(floor), clips)
        var last: AutoBeat? = null
        repeat(40) { val b = p.next(floorOnly, last, 50)!!; assertEquals("floor", b.place!!.key); last = b }
        // nothing installed: the base partner animations, and the panel says so
        val a = CinematicAutoplay(AutoplayPlanner(2))
        a.options = emptyList(); a.installed = 0
        a.start()
        val swap = runUntilSwap(a)
        assertTrue(swap.beat.fallback)
        assertEquals(AutoplayNotice.NO_ANIMATIONS, a.notice)
        // installed but nothing fits here
        val b = CinematicAutoplay(AutoplayPlanner(2))
        b.options = emptyList(); b.installed = 5
        b.start(); runUntilSwap(b)
        assertEquals(AutoplayNotice.NOTHING_FITS, b.notice)
        // nobody to play with: nothing at all is started
        val c = CinematicAutoplay(AutoplayPlanner(2))
        c.options = options(); c.people = 1
        assertTrue(c.start().isEmpty())
        repeat(600) { assertTrue(c.tick(dt).isEmpty()) }
        assertEquals(0f, c.cover)
        assertEquals(AutoplayNotice.NO_PARTNER, c.notice)
        assertNull(AutoplayPlanner(1).next(options(), null, 50, people = 1))
    }

    private fun runUntilSwap(a: CinematicAutoplay): AutoStep.Swap {
        repeat(2000) { a.tick(dt).filterIsInstance<AutoStep.Swap>().firstOrNull()?.let { return it } }
        fail("no swap")
    }

    @Test fun everyChangeIsCoveredSwappedCutThenRevealed() {
        for (style in CutStyle.entries) {
            val a = CinematicAutoplay(AutoplayPlanner(9))
            a.options = options(); a.installed = clips.size; a.cutStyle = style; a.pace = 100
            a.start()
            var swaps = 0; var cuts = 0
            var swapAt = -1; var cutAt = -1
            var lastBeat: AutoBeat? = null
            val events = ArrayList<String>()
            for (frame in 0 until 60 * 60 * 6) {
                for (s in a.tick(dt)) when (s) {
                    is AutoStep.Swap -> {
                        // the picture is fully black when the next animation starts
                        assertEquals(1f, a.cover, "$style: swap under a full cover")
                        assertNotEquals(lastBeat?.clip?.id, s.beat.clip?.id)
                        assertTrue(s.teleport, "no walking shown by default")
                        swaps++; swapAt = frame; events += "swap"
                        lastBeat = s.beat
                        a.swapped(true)
                    }
                    is AutoStep.Cut -> {
                        assertEquals(1f, a.cover, "$style: the camera cuts while it is black")
                        assertTrue(swapAt in 0..frame); cuts++; cutAt = frame; events += "cut"
                    }
                }
                if (a.phase == AutoPhase.REVEAL && events.lastOrNull() == "cut") events += "reveal"
                if (a.phase == AutoPhase.PLAY && events.lastOrNull() == "reveal") events += "play"
            }
            assertTrue(swaps >= 3, "$style: $swaps changes in six minutes at the quickest pace")
            assertEquals(swaps, cuts, "$style: every change has its cut")
            assertTrue(cutAt >= swapAt)
            // the order of each change
            val joined = events.joinToString(",")
            assertTrue(joined.startsWith("swap,cut,reveal,play"), joined)
            assertFalse(Regex("swap,(?!cut)").containsMatchIn(joined), joined)
            // the trace too: choose, cover, swap, cut, reveal, play
            val t = a.trace.joinToString(",")
            assertTrue(t.startsWith("choose,cover,swap,cut,reveal,play"), t)
        }
    }

    @Test fun theCoverFadesQuicklyOrCutsHard() {
        val a = CinematicAutoplay(AutoplayPlanner(4))
        a.options = options(); a.cutStyle = CutStyle.FADE
        a.start()
        a.tick(0.06f)
        assertTrue(a.cover > 0f && a.cover < 1f, "half way through the quick fade: ${a.cover}")
        val h = CinematicAutoplay(AutoplayPlanner(4))
        h.options = options(); h.cutStyle = CutStyle.HARD
        h.start()
        h.tick(dt)
        assertEquals(1f, h.cover)
        assertTrue(CutStyle.FADE.fadeS in 0.08f..0.15f)
        // the black lasts the engine's pose crossfade and no more than about half a second with the quick fade
        val total = CutStyle.FADE.fadeS * 2 + CinematicAutoplay.BLACK_S + CinematicAutoplay.HOLD_S
        assertTrue(CinematicAutoplay.HOLD_S > JoinEase.JOIN && total < 0.65f)
    }

    @Test fun aRefusedChangeIsTriedAgainUnderTheSameCoverThenTheBaseOnes() {
        val a = CinematicAutoplay(AutoplayPlanner(6))
        a.options = options(); a.installed = clips.size
        a.start()
        var s = runUntilSwap(a)
        val tried = ArrayList<AutoBeat>()
        tried += s.beat
        repeat(CinematicAutoplay.MAX_TRIES) {
            val next = a.swapped(false).filterIsInstance<AutoStep.Swap>().single()
            assertEquals(1f, a.cover)
            tried += next.beat
            s = next
        }
        assertTrue(tried.last().fallback, "the base animations last")
        a.swapped(true)
        // and the picture comes back
        repeat(120) { a.tick(dt) }
        assertEquals(AutoPhase.PLAY, a.phase)
        assertEquals(0f, a.cover)
    }

    @Test fun pauseStayAndNextControlTheSequence() {
        val a = CinematicAutoplay(AutoplayPlanner(8))
        a.options = options()
        a.start()
        runUntilSwap(a); a.swapped(true)
        repeat(60) { a.tick(dt) }
        assertEquals(AutoPhase.PLAY, a.phase)
        a.paused = true
        repeat(60 * 200) { assertTrue(a.tick(dt).isEmpty()) }
        assertEquals(AutoPhase.PLAY, a.phase)
        a.paused = false
        a.stay = true
        repeat(60 * 200) { a.tick(dt) }
        assertEquals(AutoPhase.PLAY, a.phase)
        a.stay = false
        assertEquals(0f, a.elapsed)
        a.next()
        assertEquals(AutoPhase.COVER, a.phase)
        runUntilSwap(a)
    }

    @Test fun showMovementWalksInTheOpenAndCutsAsTheyStart() {
        val a = CinematicAutoplay(AutoplayPlanner(12))
        a.options = options(); a.showMovement = true
        a.start()
        val s = runUntilSwap(a)
        assertTrue(s.beat.newPlace)
        assertFalse(s.teleport)
        a.swapped(true)
        repeat(30) { a.tick(dt) }
        assertEquals(AutoPhase.WALK, a.phase)
        assertEquals(0f, a.cover)
        a.arrived()
        var cut = false
        repeat(60) { for (st in a.tick(dt)) if (st is AutoStep.Cut) { cut = true; assertEquals(1f, a.cover) } }
        assertTrue(cut)
    }

    @Test fun theFloorSpotIsClearOfTheFurniture() {
        val pieces = places.filter { !it.floor }.map { it.furniture }
        val start = Vec3(3f, 3f, 0f)     // on the bed
        val spot = AutoplayPlanner.floorSpot(listOf(start, Vec3(0f, 0f, 0f)), pieces, 0f)!!
        assertEquals(Vec3(0f, 0f, 0f), spot)
        val only = AutoplayPlanner.floorSpot(listOf(start), pieces, 0.5f)!!
        assertEquals(0.5f, only.z)
        for (f in pieces) {
            val (lx, ly) = f.toLocal(only.x, only.y)
            assertFalse(abs(lx) < f.spec.halfX && abs(ly) < f.spec.halfY, "inside ${f.id}")
        }
    }

    // ------------------------------------------------------------------------------------------------------ the new shot

    private val lib: ShotLibrary by lazy {
        ShotLibrary.parse(File(System.getProperty("wifecraft.assets") ?: "../android/app/src/main/assets", "shot_library.json").readText())
    }

    private fun pair(x: Float, y: Float, cls: ShotClass) = CinematicStage(2).apply {
        count = 2
        person(0, x - 0.35f, y, 1.1f, x - 0.35f, y, 1.82f)
        person(1, x + 0.35f, y, 1.1f, x + 0.35f, y, 1.82f)
        facing[0] = 0f; facing[1] = PI.toFloat()
        floorZ = 0f
        clearance = CameraClearance { eye, _ -> abs(eye.x) < 9f && abs(eye.y) < 9f }
        lineYaw = 0f
        camera(x - 3.5f, y - 3f, 2.2f, x, y, 1.0f, 55f)
        action = ShotAction(cls)
    }

    @Test fun everyChangeCutsToANewShot() {
        val classes = listOf(ShotClass.STANDING, ShotClass.FACING_LYING, ShotClass.KISS, ShotClass.REAR, ShotClass.SEATED)
        for (seed in 1L..6L) {
            val d = CinematicDirector(CinematicSettings(), seed)
            d.library = lib
            var st = pair(0f, 0f, classes[0])
            repeat(120) { d.update(dt, st) }
            for (k in 1..12) {
                val before = d.shot
                // a change of animation (and every third one, of place: the pair somewhere else)
                val moved = k % 3 == 0
                st = pair(if (moved) (k % 2) * 2f else 0f, if (moved) 1f else 0f, classes[k % classes.size])
                st.action = ShotAction(classes[k % classes.size], SegmentKind.LOOP, "anim$k")
                d.cutNow(resetSide = moved)
                d.update(dt, st)
                val after = d.shot!!
                assertNotSame(before, after, "seed $seed change $k: a new shot")
                assertTrue(after.cut, "seed $seed change $k: reached by a cut, never a move")
                assertTrue(d.freshAfter(before, after) || after.type == ShotType.HOLD,
                    "seed $seed change $k: ${before?.preset} ${before?.size} -> ${after.preset} ${after.size}")
                assertTrue(d.lineSide != 0f)
                // the camera is at the new shot at once (no blend from the last)
                val f = d.frameNow!!
                assertEquals(after.from.yaw, f.yaw, 0.35f)
                repeat(90) { d.update(dt, st) }
            }
            assertEquals(12, d.forcedCuts)
        }
    }

    // --------------------------------------------------------------------------------------------------- the 4:3 frame

    @Test fun theFrameIsTheLargest43RectangleCentredOnAnyScreen() {
        val screens = listOf(
            2048 to 1536,   // a 4:3 tablet: the whole screen
            1536 to 2048,   // the same held tall
            2400 to 1080,   // a 20:9 phone, landscape
            1080 to 2400,   // and portrait
            1920 to 1080,   // 16:9
            2176 to 1812,   // a fold's inner screen, open (nearer square than 4:3)
            1812 to 2176,
            904 to 2316,    // a fold's cover screen
            1600 to 1210,   // within 2% of 4:3: whole
            1000 to 1000,
            3 to 1,
        )
        for ((w, h) in screens) {
            val r = FilmFrame.fit(w, h, CinematicFrame.AUTO.aspect)
            assertTrue(r.x >= 0 && r.y >= 0 && r.x + r.width <= w && r.y + r.height <= h, "$w x $h: $r inside")
            assertTrue(FilmFrame.centred(w, h, r), "$w x $h: $r centred")
            val a = w.toFloat() / h
            if (abs(a - 4f / 3f) <= FilmFrame.SNAP * 4f / 3f) assertEquals(FilmFrame.Rect(0, 0, w, h), r, "$w x $h: whole")
            else {
                assertEquals(4f / 3f, r.aspect, 4f / 3f * 0.01f + 2f / minOf(r.width, r.height), "$w x $h: $r is 4:3")
                // it touches two opposite edges: the largest that fits
                assertTrue(r.width == w || r.height == h, "$w x $h: $r largest")
                // bars at the sides on a wider screen, above and below on a narrower one
                if (a > 4f / 3f) assertEquals(h, r.height) else assertEquals(w, r.width)
            }
        }
        // the fold's inner screen: thin bars above and below (about 5% each)
        val fold = FilmFrame.fit(2176, 1812, 4f / 3f)
        assertEquals(2176, fold.width); assertTrue(fold.y in 80..100)
        // Full screen is the screen; Wide on a 4:3 screen bars above and below
        assertEquals(FilmFrame.Rect(0, 0, 2400, 1080), FilmFrame.fit(2400, 1080, CinematicFrame.FULL.aspect))
        val wide = FilmFrame.fit(2048, 1536, CinematicFrame.WIDE.aspect)
        assertEquals(2048, wide.width); assertEquals(1152, wide.height)
        assertEquals(FilmFrame.Rect(0, 0, 0, 0), FilmFrame.fit(0, 0, 4f / 3f))
    }

    @Test fun tapsMapIntoTheFrameAndTheBarsTakeNone() {
        val r = FilmFrame.fit(2400, 1080, 4f / 3f)
        assertEquals(1440 / 2f to 540f, FilmFrame.tap(r, 1200f, 540f))
        assertNull(FilmFrame.tap(r, 100f, 540f))          // the left bar
        assertNull(FilmFrame.tap(r, 2390f, 540f))         // the right bar
        assertEquals(0f to 0f, FilmFrame.tap(r, r.x.toFloat(), 0f))
        val n = FilmFrame.normalised(r, (r.x + r.width * 0.25f), r.height * 0.75f)!!
        assertEquals(0.25f, n.first, 1e-4f); assertEquals(0.75f, n.second, 1e-4f)
        val tall = FilmFrame.fit(1080, 2400, 4f / 3f)
        assertNull(FilmFrame.tap(tall, 540f, 10f))
        assertEquals(540f to 405f, FilmFrame.tap(tall, 540f, 1200f))
    }

    @Test fun theFilmBarsAreThinInTheFrame() {
        // in a 4:3 frame, Auto's picture leaves about 3.4% at the top and the bottom (the engine draws 2.39 bars at 1)
        val share = FilmFrame.barShare(4f / 3f, CinematicFrame.AUTO.picture)
        assertTrue(share in 0.02f..0.05f, "$share")
        val k = FilmFrame.barScale(4f / 3f, CinematicFrame.AUTO.picture)
        assertEquals(share, k * CinematicLook.bars(4f / 3f, CinematicLook.ASPECT), 1e-5f)
        // the look's letterbox number for the engine, in the mode, eased fully in
        val look = CinematicLook()
        look.settings = CinematicSettings(frame = CinematicFrame.AUTO)
        look.viewAspect = 4f / 3f
        look.on = true
        repeat(200) { look.step(0.05f) }
        assertEquals(k, look.letterbox(), 1e-5f)
        // Full screen keeps the wide film bars; a view wider than the picture has none
        look.settings = CinematicSettings(frame = CinematicFrame.FULL)
        assertEquals(1f, look.letterbox(), 1e-5f)
        assertEquals(0f, FilmFrame.barScale(2.0f, CinematicFrame.AUTO.picture))
        look.settings = CinematicSettings(letterbox = false)
        assertEquals(0f, look.letterbox())
    }

    @Test fun theSettingsKeepTheirNewOptions() {
        val s = CinematicSettings(autoplay = false, cutStyle = CutStyle.HARD, showMovement = true, pace = 80, frame = CinematicFrame.WIDE)
        assertEquals(s, CinematicSettings.parse(s.toJson().toString()))
        // an older file: auto-play on, a quick fade, Auto 4:3
        val old = CinematicSettings.parse("""{"lighting":50,"style":"CUTS"}""")
        assertTrue(old.autoplay); assertEquals(CutStyle.FADE, old.cutStyle); assertEquals(CinematicFrame.AUTO, old.frame)
        assertEquals(50, old.pace); assertFalse(old.showMovement)
        assertEquals(100, CinematicSettings.parse("""{"pace":400}""").pace)
    }
}
