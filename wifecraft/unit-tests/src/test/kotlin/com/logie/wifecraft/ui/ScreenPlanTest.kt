package com.logie.wifecraft.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenPlanTest {
    private val sizes = listOf(360f to 800f, 412f to 915f, 800f to 360f, 915f to 412f, 600f to 960f)

    private fun insetsFor(w: Float, h: Float, cutout: Boolean, ime: Float = 0f): ScreenInsets =
        if (w > h) ScreenInsets(l = if (cutout) 48f else 0f, cutouts = if (cutout) listOf(ScreenRect(0f, h / 2 - 40f, 48f, h / 2 + 40f)) else emptyList(), ime = ime)
        else ScreenInsets(t = 0f, cutouts = if (cutout) listOf(ScreenRect(w / 2 - 50f, 0f, w / 2 + 50f, 40f)) else emptyList(), ime = ime)

    /** Every dock the modes have, with the width it needs. */
    private fun dockFor(mode: ScreenMode): DockPlan = when (mode) {
        ScreenMode.BUILD, ScreenMode.MOVE -> DockModel.build()
        ScreenMode.JOI -> DockModel.joi()
        ScreenMode.CHAT -> DockModel.chat()
        ScreenMode.CINEMATIC -> DockPlan(DockKind.NONE, emptyList())
        ScreenMode.NORMAL -> DockModel.normal(SceneHud.orders(true, true, 2, true, 2, listOf("BED" to "Rest on bed"), life = true, performing = true, joi = true, chatMode = true), false, false)
    }

    /** The row over the dock is open in Chat (its chips) and, as a level picker or the steer field, in JOI. */
    private fun rowsFor(mode: ScreenMode): List<Boolean> = if (mode == ScreenMode.CHAT || mode == ScreenMode.JOI) listOf(false, true) else listOf(false)

    private fun states(): List<Triple<ScreenState, Boolean, Float>> {
        val out = ArrayList<Triple<ScreenState, Boolean, Float>>()
        for (mode in ScreenMode.entries) for (panel in ScreenPanel.entries.filter { !it.modal }) {
            if (panel != ScreenPanel.NONE && PanelRules.open(ScreenPanel.NONE, panel, mode) != panel) continue
            val dock = dockFor(mode)
            for (cast in listOf(0, 1, 3, 6)) for (orbit in listOf(false, true)) for (toast in listOf(false, true))
                for (context in listOf(false, true)) for (ime in listOf(false, true)) for (row in rowsFor(mode)) {
                    if (ime && mode != ScreenMode.CHAT && panel != ScreenPanel.STEER && panel != ScreenPanel.BUILD_PANEL && panel != ScreenPanel.NONE) continue
                    out += Triple(ScreenState(mode, panel, dock.size, dock.minWidth, cast, orbit, toast, context, ime, row), ime, 0f)
                }
        }
        return out
    }

    private fun forEachScreen(check: (String, Float, Float, ScreenInsets, ScreenState, List<ScreenPlaced>) -> Unit) {
        for ((w, h) in sizes) for (cutout in listOf(false, true)) for ((s, ime, _) in states()) {
            val ins = insetsFor(w, h, cutout, if (ime) (if (w > h) 150f else 300f) else 0f)
            check("${w}x$h cutout=$cutout $s", w, h, ins, s, ScreenPlan.plan(w, h, ins, s))
        }
    }

    private fun safe(w: Float, h: Float, ins: ScreenInsets) = ScreenRect(ins.l, ins.t, w - ins.r, h - maxOf(ins.b, ins.ime))

    @Test
    fun theCombinationsTestedAreManyAndEveryModeIsAmongThem() {
        val all = states()
        assertTrue(all.size > 700, "only ${all.size} states")
        for (m in ScreenMode.entries) assertTrue(all.any { it.first.mode == m && it.first.panel != ScreenPanel.NONE } || m == ScreenMode.JOI || m == ScreenMode.NORMAL, "$m")
        for (p in ScreenPanel.entries.filter { it.sheet }) assertTrue(all.any { it.first.panel == p }, "no state with $p")
    }

    @Test
    fun noOverlapsInsideTheSafeAreaAndOutsideEveryCutout() = forEachScreen { name, w, h, ins, _, plan ->
        val area = safe(w, h, ins)
        for (p in plan) {
            assertTrue(p.rect.w > 0f && p.rect.h > 0f, "$name: ${p.element} has no size")
            assertTrue(p.rect.inside(area), "$name: ${p.element} ${p.rect} leaves the safe area $area")
            for (c in ins.cutouts) assertFalse(p.rect.intersects(c), "$name: ${p.element} is over a cut-out")
        }
        for (i in plan.indices) for (j in i + 1 until plan.size) {
            assertFalse(plan[i].rect.intersects(plan[j].rect), "$name: ${plan[i].element} ${plan[i].rect} overlaps ${plan[j].element} ${plan[j].rect}")
        }
    }

    @Test
    fun touchTargetsAre48dpAndKeepAGapFromTheirNeighbours() = forEachScreen { name, _, _, _, _, plan ->
        val touch = plan.filter { it.interactive }
        for (p in touch) {
            val bigEnough = p.rect.h >= ScreenPlan.TOUCH - 0.01f && (p.rect.w >= ScreenPlan.TOUCH - 0.01f)
            assertTrue(bigEnough, "$name: ${p.element} is ${p.rect.w} x ${p.rect.h}")
        }
        for (i in touch.indices) for (j in i + 1 until touch.size) {
            assertFalse(touch[i].rect.inflate(ScreenPlan.GAP / 2f - 0.01f).intersects(touch[j].rect.inflate(ScreenPlan.GAP / 2f - 0.01f)),
                "$name: ${touch[i].element} and ${touch[j].element} are closer than ${ScreenPlan.GAP}")
        }
    }

    @Test
    fun nothingCoversTheScenesMiddleWhileNobodyIsTyping() = forEachScreen { name, w, h, _, s, plan ->
        if (s.ime) return@forEachScreen
        val box = ScreenPlan.keepOut(w, h, s.mode)
        for (p in plan) assertFalse(p.rect.intersects(box), "$name: ${p.element} ${p.rect} covers the middle $box")
    }

    @Test
    fun theSheetIsBesideTheSceneInLandscapeAndAboveTheDockInPortrait() {
        for ((w, h) in sizes) {
            val kind = ScreenPlan.sheetFor(w, h)
            assertEquals(if (w > h) SheetKind.SIDE else SheetKind.BOTTOM, kind)
            val s = ScreenState(ScreenMode.NORMAL, ScreenPanel.MORE, 6, 328f, 3, true, false, true, false)
            val plan = ScreenPlan.plan(w, h, ScreenInsets(), s)
            val sheet = assertNotNull(ScreenPlan.find(plan, "sheet"), "no sheet at ${w}x$h")
            if (kind == SheetKind.SIDE) {
                assertTrue(sheet.l >= 0.55f * w, "the side sheet starts at ${sheet.l} of $w")
                assertTrue(sheet.w in 320f..400f)
            } else {
                assertTrue(sheet.h <= 0.45f * h + 0.01f, "bottom sheet ${sheet.h} high of $h")
                assertTrue(sheet.b <= ScreenPlan.find(plan, "context")!!.t)
            }
        }
    }

    @Test
    fun handsOffTheEdgesWhereTheSystemSwipes() = forEachScreen { name, w, h, ins, _, plan ->
        for (p in plan.filter { it.draggable }) {
            assertTrue(p.rect.l >= ins.gestureL + 1f && p.rect.r <= w - ins.gestureR - 1f, "$name: ${p.element} ${p.rect} is in the gesture zone")
        }
        for (p in plan) assertTrue(p.rect.b <= h - maxOf(ins.b, ins.ime) - 8f + 0.01f, "$name: ${p.element} is too near the bottom edge")
        plan.firstOrNull { it.element == "dock" }?.let {
            if (ins.ime == 0f) assertTrue(h - it.rect.b >= 16f - 0.01f, "$name: the dock is ${h - it.rect.b} from the bottom")
        }
    }

    @Test
    fun onePanelAtATimeAndBackClosesThemInOrder() {
        val all = ScreenPanel.entries
        for (mode in ScreenMode.entries) for (open in all) for (next in all) {
            val r = PanelRules.open(open, next, mode)
            assertTrue(r == next || r == open || r == ScreenPanel.NONE, "$open then $next in $mode gave $r")
            // the result is a single panel by construction; a modal one asked for replaces any sheet
            if (next.modal && next != ScreenPanel.NONE) assertEquals(next, r)
        }
        assertEquals(ScreenPanel.NONE, PanelRules.open(ScreenPanel.MORE, ScreenPanel.MORE, ScreenMode.NORMAL))
        assertEquals(ScreenPanel.TIME, PanelRules.open(ScreenPanel.MORE, ScreenPanel.TIME, ScreenMode.NORMAL))
        assertEquals(ScreenPanel.MORE, PanelRules.open(ScreenPanel.MORE, ScreenPanel.BUILD_PANEL, ScreenMode.NORMAL), "Build's panel only in Build")
        assertEquals(ScreenPanel.NONE, PanelRules.onSelect(ScreenPanel.MORE))
        assertEquals(ScreenPanel.ANIMATIONS, PanelRules.onSelect(ScreenPanel.ANIMATIONS))
        assertEquals(ScreenPanel.NONE, PanelRules.onMode())
        assertEquals(BackStep.CLOSE_PANEL, PanelRules.back(ScreenPanel.MENU, ScreenMode.BUILD, true, true))
        assertEquals(BackStep.LEAVE_MODE, PanelRules.back(ScreenPanel.NONE, ScreenMode.CHAT, true, true))
        assertEquals(BackStep.LEAVE_CINEMATIC, PanelRules.back(ScreenPanel.NONE, ScreenMode.NORMAL, true, true))
        assertEquals(BackStep.RELEASE_NPC, PanelRules.back(ScreenPanel.NONE, ScreenMode.NORMAL, false, true))
        assertEquals(BackStep.NONE, PanelRules.back(ScreenPanel.NONE, ScreenMode.NORMAL, false, false))
    }

    @Test
    fun everyControlTheSceneHadIsStillThereWithAPlaceAndFewTaps() {
        val ids = ScreenFeatures.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "an id is used twice")
        assertTrue(ids.size >= 60, "the registry shrank to ${ids.size}")
        for (f in ScreenFeatures.all) {
            assertTrue(ScreenFeatures.places.any { f.slot.contains(it) }, "${f.id} has no place: ${f.slot}")
            assertTrue(f.taps <= 4, "${f.id} takes ${f.taps} taps")
            if (f.frequent) assertTrue(f.taps <= 4)
        }
        // each order of the action bar is in the dock or behind More, in every situation
        for (performing in listOf(false, true)) for (pair in listOf(false, true)) {
            val slots = SceneHud.orders(true, true, 2, repeating = true, queued = 2, uses = listOf("BED" to "Rest on bed", "TABLE" to "Read a book"),
                life = true, performing = true, joi = true, chatMode = true)
            val dock = DockModel.normal(slots, performing, pair)
            val reachable = dock.items.mapNotNull { it.order } + dock.more.map { it.order }
            for (o in HudOrder.entries) assertTrue(o in reachable, "$o is nowhere ($performing, $pair)")
        }
    }

    @Test
    fun theDockHoldsAtMostSixTargetsAndNeverPages() {
        for (partner in listOf(false, true)) for (life in listOf(false, true)) for (joi in listOf(false, true)) for (chat in listOf(false, true))
            for (performing in listOf(false, true)) for (queued in listOf(0, 2)) {
                val slots = SceneHud.orders(true, partner, 2, repeating = performing, queued = queued, life = life, performing = performing, joi = joi, chatMode = chat)
                val dock = DockModel.normal(slots, performing, performing && partner)
                assertTrue(dock.size <= DockModel.MAX, "${dock.size} targets for $slots")
                assertTrue(dock.minWidth <= 328f + 0.01f, "the dock needs ${dock.minWidth} dp")
                assertEquals(performing, dock.kind == DockKind.PLAYING)
                if (!performing) assertEquals(HudOrder.WALK, dock.items.first().order)
            }
        val playing = DockModel.normal(SceneHud.orders(false, true, 1, true, 2, performing = true), true, true)
        assertEquals(listOf("STOP", "switch", "SKIP", "STOP_REPEAT", "more").sorted(), playing.items.map { it.id }.sorted())
        assertEquals(DockKind.NONE, DockModel.normal(emptyList(), false, false).kind)
        assertTrue(DockModel.joi().minWidth <= 328f)
    }

    @Test
    fun theTopBarFitsA360DpPhoneWithTheClockAndWiderOnesWithThePlaceToo() {
        val s = ScreenState(ScreenMode.NORMAL, dockItems = 5, dockWidth = 250f, castCount = 3)
        val plan = ScreenPlan.plan(360f, 800f, ScreenInsets(), s)
        val clock = assertNotNull(ScreenPlan.find(plan, "place.clock"), "the clock pill collapsed")
        assertTrue(clock.w >= 132f, "the clock pill is ${clock.w} wide")
        val wider = ScreenPlan.plan(412f, 915f, ScreenInsets(), s)
        val place = assertNotNull(ScreenPlan.find(wider, "place"), "the place pill collapsed at 412")
        assertTrue(place.w >= 80f + 132f, "the pill is ${place.w} wide")
        assertNotNull(ScreenPlan.find(plan, "menu")); assertNotNull(ScreenPlan.find(plan, "cinematic"))
        // with a mode on, the pill shrinks to the clock; the way out of the mode stays
        val busy = ScreenPlan.plan(360f, 800f, ScreenInsets(), s.copy(mode = ScreenMode.BUILD))
        assertNotNull(ScreenPlan.find(busy, "mode")); assertNull(ScreenPlan.find(busy, "cinematic"))
        assertTrue((ScreenPlan.find(busy, "place.clock") ?: ScreenPlan.find(busy, "place.compact"))!!.w >= ScreenPlan.PLACE_COMPACT)
        // beside a notch in the middle, the bar splits round it
        val notch = ScreenPlan.plan(360f, 800f, ScreenInsets(cutouts = listOf(ScreenRect(130f, 0f, 230f, 40f))), s)
        assertTrue(ScreenPlan.find(notch, "menu")!!.r <= 130f - 8f)
        assertTrue(ScreenPlan.find(notch, "cinematic")!!.l >= 230f + 8f)
        // a mode chip is always there while a mode runs, in every size
        for ((w, h) in sizes) for (m in ScreenMode.entries.filter { it != ScreenMode.NORMAL }) {
            assertNotNull(ScreenPlan.find(ScreenPlan.plan(w, h, ScreenInsets(), ScreenState(m)), "mode"), "no way out of $m at ${w}x$h")
        }
    }

    @Test
    fun theToastShowsOneLineAndExpires() {
        val q = ToastQueue()
        assertNull(q.at(0))
        q.show("Rowan: refused", true, 0)
        q.show("Accepted", false, 100)
        assertEquals("Accepted", q.at(200)?.text)
        assertNull(q.at(100 + ToastQueue.LIFE_MS))
        q.show("A prop has no surface", true, 0)
        assertNotNull(q.at(ToastQueue.LIFE_MS + 1)); assertNull(q.at(ToastQueue.PROBLEM_MS))
        q.show("same", false, 0); q.show("same", false, 2000)
        assertNotNull(q.at(4000)); q.show(" ", false, 5000); assertEquals("same", q.at(4500)?.text)
    }

    @Test
    fun theChromeHidesInTheCinematicAndTheWayOutOfAModeNeverDoes() {
        assertTrue(ChromeVisibility.shown(true, false, 100, ScreenMode.CINEMATIC, false))
        assertFalse(ChromeVisibility.shown(true, false, ChromeVisibility.PEEK_MS, ScreenMode.CINEMATIC, false))
        assertTrue(ChromeVisibility.shown(true, false, 60_000, ScreenMode.CINEMATIC, true), "a panel keeps it up")
        assertTrue(ChromeVisibility.shown(true, false, 60_000, ScreenMode.BUILD, false), "Build keeps it up")
        assertFalse(ChromeVisibility.shown(false, true, 9_000, ScreenMode.NORMAL, false))
        assertTrue(ChromeVisibility.shown(false, false, 99_000, ScreenMode.NORMAL, false))
        for (m in ScreenMode.entries) assertEquals(m != ScreenMode.NORMAL, ChromeVisibility.exitChip(m))
    }
}
