package com.logie.wifecraft.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreatorLayoutTest {
    private fun near(a: Float, b: Float, eps: Float = 1e-3f) = assertTrue(abs(a - b) <= eps, "$a != $b")

    @Test fun unitsAreShadowlandsScaledToTheScreen() {
        near(CL.S, 768f / 1080f * 1.2f)
        near(CL.u(104), 104 * 768f / 1080f * 1.2f)
        near(CL.ROW_PITCH, CL.u(70))
        near(CL.LIST_W, CL.u(447))
        near(CL.OPT_H, 768f - CL.u(297) - CL.u(108))
    }

    @Test fun columnsFollowShadowlandsStride() {
        assertEquals(Columns(1, 10), columnsFor(10))
        assertEquals(Columns(2, 6), columnsFor(11))
        assertEquals(Columns(2, 12), columnsFor(24))
        assertEquals(Columns(3, 9), columnsFor(25))
        assertEquals(Columns(3, 12), columnsFor(36))
        assertEquals(Columns(4, 10), columnsFor(37))
        assertEquals(Columns(1, 1), columnsFor(1))
    }

    @Test fun columnsGrowWhereTheScreenHasNotTheLines() {
        // 10 choices in room for 4 lines: 3 columns of 4
        assertEquals(Columns(3, 4), columnsFor(10, 4))
        // room enough: unchanged
        assertEquals(Columns(2, 6), columnsFor(11, 20))
        // 37 choices in room for 5: 8 columns of 5
        assertEquals(Columns(8, 5), columnsFor(37, 5))
    }

    @Test fun popoutFillsDownwardsColumnByColumn() {
        val l = fillPopout(14, named = true, coloured = false, room = null)
        assertEquals(2, l.columns); assertEquals(7, l.stride)
        near(l.details, 108f)
        near(l.cellW, CL.p(14 + 108 + 14))
        near(l.cells[0].x, CL.p(6)); near(l.cells[0].y, CL.p(12))
        near(l.cells[6].y, CL.p(12) + 6 * CL.p(20))
        near(l.cells[7].x, CL.p(6) + l.cellW); near(l.cells[7].y, CL.p(12))
        near(l.width, 2 * l.cellW + CL.p(12))
        near(l.height, 7 * CL.p(20) + CL.p(32))
        assertEquals(14, l.cells.size)
    }

    @Test fun popoutColumnWidthsByWhatTheyHold() {
        near(fillPopout(5, true, false, null).details, 116f)       // one column: 116 whatever
        near(fillPopout(20, false, true, null).details, 79f)       // coloured
        near(fillPopout(20, true, false, null).details, 108f)      // named
        near(fillPopout(20, false, false, null).details, 42f)      // numbers only
    }

    @Test fun popoutWithRoomForFewLinesUsesMoreColumns() {
        val room = CL.p(32) + 5 * CL.p(20) + 1f
        val l = fillPopout(12, true, false, room)
        assertEquals(3, l.columns); assertEquals(4, l.stride)
    }

    @Test fun popoutHangsUnderTheBoxUnlessThereIsMoreRoomAbove() {
        val (under, room) = popoutSide(boxTop = 100f, boxBottom = 140f, screenH = 768f, choices = 6)
        assertTrue(under); assertTrue(room > 500f)
        val (under2, _) = popoutSide(boxTop = 600f, boxBottom = 640f, screenH = 768f, choices = 10)
        assertFalse(under2)
        val p = popoutPlace(true, 100f, 140f, 500f, 200f, 100f)
        near(p.left, 500f + CL.u(3) - 200f); near(p.top, 140f - CL.u(11))
        val q = popoutPlace(false, 600f, 640f, 500f, 200f, 100f)
        near(q.top, 600f + CL.u(11) - 100f)
    }

    @Test fun categoriesEndTwentyFromTheRightOverlapping() {
        val w = 1365f
        val last = categoryRect(4, 5, w)
        near(last.right, w - CL.u(20))
        near(categoryRect(3, 5, w).right, w - CL.u(20) - CL.u(83))
        near(categoryRowWidth(5), CL.u(104) + 4 * CL.u(83))
        val dice = diceRect(5, w)
        near(dice.right, categoryRect(0, 5, w).x - CL.u(9))
    }

    @Test fun viewButtonsOverlapByFiveAndTheTurnersStandOffThirty() {
        near(viewSmallRect(1).x - viewSmallRect(0).x, CL.u(43))
        near(viewSmallRect(3).x - viewSmallRect(2).x, CL.u(43) + CL.u(30))
        near(viewSmallRect(4).x - viewSmallRect(3).x, CL.u(43))
        near(viewSmallRect(0).x, CL.u(40)); near(viewSmallRect(0).y, CL.u(30))
        near(viewButtonRect(1).y - viewButtonRect(0).y, CL.u(46))
    }

    @Test fun nameBoxIsCentredBelowTheTitle() {
        val w = 1365f
        val t = nameTitleRect(w); val b = nameBoxRect(w)
        near(t.y, CL.u(34)); near(b.y, CL.u(34 + 20 + 2)); near((b.x * 2 + b.w), w)
    }

    @Test fun nineSliceSurroundsTheFrame() {
        val f = Rect(100f, 100f, 200f, 100f)
        val s = nineSlice(f).associate { it.piece.substringAfter("nineslice-") to it.rect }
        near(s.getValue("cornertopleft").x, 100f - CL.u(30)); near(s.getValue("cornertopleft").y, 100f - CL.u(20))
        near(s.getValue("cornerbottomright").right, 300f + CL.u(30)); near(s.getValue("cornerbottomright").bottom, 200f + CL.u(20))
        val c = s.getValue("center"); val tl = s.getValue("cornertopleft"); val br = s.getValue("cornerbottomright")
        near(c.x, tl.right); near(c.y, tl.bottom); near(c.right, br.x); near(c.bottom, br.y)
        assertEquals(9, nineSlice(f).size)
    }

    @Test fun rowPartsAreHitWhereShadowlandsPutsThem() {
        val g = RowGeometry(2)
        near(g.frame.y, 2 * CL.ROW_PITCH + CL.u(4))
        near(g.choice.right, CL.LIST_W - CL.u(36.5f))
        near(g.next.x, g.choice.right + CL.u(4)); near(g.previous.right, g.choice.x - CL.u(5))
        near(g.name.right, g.previous.x - CL.u(34))
        assertEquals(RowPart.CHOICE, g.hit(g.choice.x + 5f, g.frame.y + 5f, false))
        assertEquals(RowPart.NEXT, g.hit(g.next.x + 2f, g.next.y + 2f, false))
        assertEquals(RowPart.PREVIOUS, g.hit(g.previous.x + 2f, g.previous.y + 2f, false))
        assertEquals(RowPart.NONE, g.hit(1f, g.frame.y + 2f, false))
        assertEquals(RowPart.CHECK, g.hit(g.check.x + 2f, g.check.y + 2f, true))
        assertEquals(2, rowAt(2 * CL.ROW_PITCH + 3f, 5)); assertEquals(-1, rowAt(5 * CL.ROW_PITCH + 1f, 5))
        near(contentHeight(4), 4 * CL.ROW_PITCH)
    }

    @Test fun stepsGoRoundTheEnds() {
        assertEquals(0, stepIndex(4, 1, 5)); assertEquals(4, stepIndex(0, -1, 5)); assertEquals(2, stepIndex(1, 1, 5)); assertEquals(0, stepIndex(0, 1, 0))
        assertEquals(3, placeOf(listOf(10, 20, 30), 30)); assertEquals(0, placeOf(listOf(1), 9))
        assertEquals("", choiceLabel("Oak", true, 2)); assertEquals("Oak", choiceLabel("Oak", false, 2)); assertEquals("2", choiceLabel("", false, 2))
    }

    @Test fun categoriesPicturesAndOrder() {
        assertEquals("head", categoryArt("Face", false, false)); assertEquals("hair", categoryArt("Hair Style", false, false))
        assertEquals("body", categoryArt("Markings", true, true)); assertEquals("figure", categoryArt("Body", true, true)); assertEquals("body", categoryArt("Body", true, false))
        assertEquals("charactercreate-icon-customize-accessories-selected", categoryPiece("Accessories", false, false, true))
        assertEquals(listOf("Face", "Hair", "Markings", "Body"), bodyLast(listOf("Face", "Body", "Hair", "Markings")) { it })
        assertEquals(1f to 0.6f, categoryView(0f)); assertEquals(1.2f to 0.62f, categoryView(50f)); assertEquals(1.45f to 0.72f, categoryView(80f))
    }

    @Test fun racePiecesFindTheirArt() {
        val art = setOf("raceicon128-undead-male", "raceicon128-nightelf-female", "raceicon128-highmountain-male", "raceicon128-zandalari-female")
        assertEquals("raceicon128-undead-male", racePiece("Scourge", false) { it in art })
        assertEquals("raceicon128-nightelf-female", racePiece("NightElf", true) { it in art })
        assertEquals("raceicon128-highmountain-male", racePiece("HighmountainTauren", false) { it in art })
        assertEquals("raceicon128-zandalari-female", racePiece("ZandalariTroll", true) { it in art })
        assertNull(racePiece("Skyborne", true) { it in art })
        assertEquals("charactercreate-gendericon-female-selected", sexPiece(true, true))
    }

    // ---- scrolling ----

    @Test fun listStopsAtItsEndsAndReportsItsBar() {
        val s = ListScroll(view = 400f, content = 1000f)
        near(s.limit(), 600f)
        s.scrollTo(900f); near(s.target, 600f)
        s.scrollTo(-5f); near(s.target, 0f)
        assertNotNull(s.bar()); near(s.bar()!!.first, 160f)
        assertNull(ListScroll(400f, 300f).bar())
    }

    @Test fun easingGoesTheSameShareWhateverTheFrameRate() {
        fun run(step: Float): Float { val s = ListScroll(400f, 2000f); s.scrollTo(500f); var t = 0f; while (t < 0.2f - 1e-4f) { s.update(step); t += step }; return s.offset }
        val a = run(0.01f); val b = run(0.05f); val c = run(0.0125f)
        assertTrue(abs(a - b) < 6f, "$a $b"); assertTrue(abs(a - c) < 3f)
        assertTrue(a in 400f..500f)
        val s = ListScroll(400f, 2000f); s.scrollTo(500f); repeat(300) { s.update(0.016f) }
        near(s.offset, 500f); assertFalse(s.busy())
    }

    @Test fun aTapStaysATapAndADragMovesTheContentWithTheFinger() {
        val s = ListScroll(400f, 2000f)
        s.press(100f); s.drag(103f, 0.01f)
        assertTrue(s.tapped()); near(s.offset, 0f)
        s.release(0.02f); assertTrue(s.tapped())
        s.press(100f); s.drag(120f, 0.01f)     // past the slop: from here with the finger, without the jump
        assertFalse(s.tapped()); near(s.offset, 0f)
        s.drag(170f, 0.02f); near(s.offset, 50f)
        s.drag(-500f, 0.03f); near(s.offset, 0f)           // never past the start
    }

    @Test fun aFlickCarriesOnAndSlowsToAStop() {
        val s = ListScroll(400f, 5000f)
        s.press(0f)
        var y = 0f; var t = 0f
        repeat(10) { t += 0.01f; y += 5f; s.drag(y, t) }     // 500 units a second
        s.release(t + 0.01f)
        assertTrue(s.flinging); assertTrue(s.velocity > 400f)
        val before = s.offset
        repeat(10) { s.update(0.016f) }
        assertTrue(s.offset > before)
        repeat(400) { s.update(0.016f) }
        assertFalse(s.flinging); assertFalse(s.busy())
        // a finger that stopped before it lifted throws nothing
        val d = ListScroll(400f, 5000f); d.press(0f); d.drag(50f, 0.0f); d.drag(100f, 0.02f); d.release(0.5f)
        assertFalse(d.flinging)
    }

    @Test fun aFlingStopsAtTheEnd() {
        val s = ListScroll(400f, 500f)
        s.press(0f); var y = 0f; var t = 0f
        repeat(8) { t += 0.01f; y += 20f; s.drag(y, t) }
        s.release(t + 0.005f)
        repeat(200) { s.update(0.016f) }
        near(s.offset, 100f)
    }

    @Test fun revealScrollsOnlyAsFarAsNeeded() {
        val s = ListScroll(400f, 2000f)
        s.reveal(100f, 200f); near(s.target, 0f)
        s.reveal(500f, 600f); near(s.target, 200f)
    }

    // ---- the view ----

    @Test fun viewsSetZoomFocusAndFacing() {
        val v = ViewState()
        v.showView("Face"); near(v.zoomTarget, 3f); near(v.focusTarget, 0.76f)
        v.showView("Rear"); near(v.facing, 165f); assertEquals("Rear", v.view)
        v.showView("Rear"); near(v.facing, 75f)
        v.showView("Rear"); near(v.facing, 165f)
        v.showView("Full"); near(v.facing, -15f); near(v.zoomTarget, 1f)
        v.turn(10f); assertNull(v.view)
    }

    @Test fun zoomIsHeldBetweenOneAndTheMaximumAndEases() {
        val v = ViewState()
        v.setZoom(10f); near(v.zoomTarget, 3.4f)
        v.setZoom(0.2f); near(v.zoomTarget, 1f)
        v.setZoom(2f, 0.7f)
        repeat(200) { v.update(0.016f) }
        near(v.zoom, 2f); near(v.focusY, 0.7f)
        v.pinch(2f); near(v.zoomTarget, 3.4f)
    }

    @Test fun aFingerTurnsAndSpinCarriesOnAfterIt() {
        val v = ViewState(); v.facing = 0f
        v.fingerDown()
        repeat(10) { v.fingerMoved(10f, 0f, 0.016f); v.update(0.016f) }
        near(v.facing, 45f)
        v.fingerUp()
        val at = v.facing
        repeat(5) { v.update(0.016f) }
        assertTrue(v.facing > at)
        repeat(500) { v.update(0.016f) }
        assertTrue(abs(v.spin) <= 4f * 1.2f + 1e-3f || abs(v.spin) < 5f)
    }

    @Test fun heldTurnButtonsTurn140DegreesASecond() {
        val v = ViewState(); v.facing = 0f; v.heldTurn = 1
        repeat(10) { v.update(0.1f) }
        near(v.facing, 140f, 0.01f)
    }

    @Test fun focusMovesOnlyWhileZoomedAndStaysInRange() {
        val v = ViewState()
        v.fingerMoved(0f, 100f, 0.016f); near(v.focusY, 0.6f)
        v.setZoom(2f); repeat(200) { v.update(0.016f) }
        v.fingerMoved(0f, 100f, 0.016f)
        assertTrue(v.focusY < 0.6f && v.focusY >= 0.25f)
        v.fingerMoved(0f, 1e6f, 0.016f); near(v.focusY, 0.25f)
    }

    @Test fun cameraKeepsTheFocusPointWhereItWasWhileZooming() {
        val a = CreatorCamera.pose(1f, 0.6f); val b = CreatorCamera.pose(3f, 0.6f)
        assertTrue(b.fov < a.fov)
        // the world point at 0.6 of the screen's height stays the same
        fun point(p: com.logie.wifecraft.story.CameraPose): Float {
            val d = p.eye.x - p.at.x
            val v = 2f * d * kotlin.math.tan(Math.toRadians(p.fov / 2.0)).toFloat()
            return p.at.z + (0.6f - 0.5f) * v
        }
        near(point(a), point(b), 1e-3f)
        near(a.fov, CreatorCamera.FOV, 1e-3f)
    }

    @Test fun spinAreaAndVignetteKeepTheLuasShares() {
        val w = 1365f
        val r = spinRect(w)
        near(r.x, CL.u(68 + 79 + 40)); near(r.y, 110f)
        near(r.right, w - CL.LIST_W - CL.u(60))
        near(CL.VIGNETTE_TOP, 768f * 451f / 1080f); near(CL.vignetteSide(1920f), 703f)
        val o = optionPanel(w); near(o.right, w - CL.u(33)); near(o.y, CL.u(297))
        val back = navRect(false, w); val fin = navRect(true, w)
        near(back.x, CL.u(46)); near(fin.right, w - CL.u(46)); near(back.bottom, 768f - CL.u(28))
    }

    // ---- the screen's races, words, popout and art -------------------------------------------------------------------------------------

    @Test fun racesStandOnTheSideTheLuaOrTheTablesGive() {
        // the Lua's ALLIANCE table by file name, whatever case or spacing; others by the tables' faction
        assertTrue(isAllianceSide("NightElf", false)); assertTrue(isAllianceSide("Night_Elf", false)); assertTrue(isAllianceSide("DarkIronDwarf", false))
        assertFalse(isAllianceSide("Orc", false)); assertFalse(isAllianceSide("Scourge", false))
        assertTrue(isAllianceSide("SomeNewRace", true)); assertFalse(isAllianceSide("SomeNewRace", false))
        assertTrue(isAllianceSide("Pandaren", false))
    }

    // ---- the race step's grid, over a table of screens ------------------------------------------------------------------------------

    /** (width px, height px): the Fold's inner screen both ways, its cover screen both ways, and two ordinary ones. */
    private val screens = listOf(2184 to 1968, 1968 to 2184, 2520 to 1080, 1080 to 2520, 1920 to 1080, 1280 to 720)

    private fun overlap(a: Rect, b: Rect, eps: Float = 0.01f) = a.x < b.right - eps && b.x < a.right - eps && a.y < b.bottom - eps && b.y < a.bottom - eps
    private fun inside(r: Rect, o: Rect, eps: Float = 0.01f) = r.x >= o.x - eps && r.y >= o.y - eps && r.right <= o.right + eps && r.bottom <= o.bottom + eps

    private fun check(wpx: Int, hpx: Int, a: RaceCounts, h: RaceCounts) {
        val ppu = hpx / CL.SCREEN_H
        val width = wpx / ppu
        val tag = "${wpx}x$hpx classic ${a.classic}+${a.allied} / ${h.classic}+${h.allied}"
        val l = raceLayout(width, ppu, a, h)
        assertTrue(l.fits, "fits $tag")
        val again = raceLayout(width, ppu, a, h)
        assertEquals(l.cells, again.cells, "deterministic $tag"); assertEquals(l.scale, again.scale); assertEquals(l.mode, again.mode)
        assertEquals(a.total + h.total, l.cells.size, tag)
        if (l.cells.isEmpty()) return
        val whole = Rect(0f, 0f, width, CL.SCREEN_H)
        val minB = min(CL.u(79), RaceGrid.minTouchUnits(ppu))
        assertTrue(l.scale <= 1f + 1e-4f && l.button >= minB - 0.01f, "min size $tag: ${l.button} < $minB")
        for (c in l.cells) {
            assertTrue(inside(c.tile, l.safe) && inside(c.tile, whole), "$tag cell ${c.alliance}/${c.index} ${c.tile} outside safe ${l.safe}")
            assertTrue(inside(c.button, c.tile) && inside(c.name, c.tile), "$tag button/name leave the tile")
            for (r in l.reserved) assertFalse(overlap(c.tile, r.rect), "$tag cell ${c.alliance}/${c.index} on ${r.name}")
            assertFalse(overlap(c.tile, l.character), "$tag cell on the character")
        }
        for (i in l.cells.indices) for (j in i + 1 until l.cells.size) assertFalse(overlap(l.cells[i].tile, l.cells[j].tile), "$tag overlap $i $j")
        // one row grid per side: same row, same y; the rows are whole pitches apart; allied rows line up with the classic ones
        val pitch = l.rowPitch
        for (al in listOf(true, false)) {
            val mine = l.cells.filter { it.alliance == al }
            if (mine.isEmpty()) continue
            val y0 = mine.minOf { it.tile.y }
            for (c in mine) {
                val q = (c.tile.y - y0) / pitch
                assertTrue(abs(q - Math.round(q)) < 1e-3f, "$tag rows are on the grid")
                assertTrue(mine.filter { it.row == c.row && l.mode == RaceMode.SIDES }.all { abs(it.tile.y - c.tile.y) < 1e-3f }, "$tag same row, same y")
            }
            val classic = mine.filter { !it.allied }; val allied = mine.filter { it.allied }
            if (l.mode == RaceMode.SIDES) {
                // the classic races fill their columns first, the allied start new columns, and share the row grid
                if (classic.isNotEmpty() && allied.isNotEmpty()) assertTrue(allied.minOf { it.col } > classic.maxOf { it.col }, "$tag allied start a new column")
                for (r in allied.map { it.row }.distinct()) for (c in classic.filter { it.row == r }) assertTrue(abs(c.tile.y - allied.first { it.row == r }.tile.y) < 1e-3f, "$tag allied row $r aligned")
            } else if (classic.isNotEmpty() && allied.isNotEmpty()) {
                assertTrue(allied.minOf { it.row } > classic.maxOf { it.row }, "$tag allied start a new row group")
                val x = (classic + allied).map { it.col }.distinct().associateWith { col -> (classic + allied).first { it.col == col }.tile.x }
                for (c in classic + allied) assertTrue(abs(x.getValue(c.col) - c.tile.x) < 1e-3f, "$tag same column grid")
            }
        }
        if (l.mode == RaceMode.SIDES) {
            for (b in l.blocks) if (b.name == "alliance") assertTrue(b.rect.right <= width / 3f + 0.01f, "$tag alliance block in the outer third") else assertTrue(b.rect.x >= width * 2f / 3f - 0.01f, "$tag horde block in the outer third")
            // centred in the free height: as much room over the tallest block as under it
            val tall = l.cells.maxOf { it.tile.bottom }; val first = l.cells.minOf { it.tile.y }
            assertTrue(abs((first - l.safe.y) - (l.safe.bottom - tall)) < 0.01f, "$tag centred: ${first - l.safe.y} vs ${l.safe.bottom - tall}")
            // the middle third is the character's alone
            assertTrue(l.cells.none { overlap(it.tile, Rect(width / 3f + 0.01f, 0f, width / 3f - 0.02f, CL.SCREEN_H)) }, "$tag middle third free")
        }
        // the real sexes and foot rectangles are inside what is reserved
        assertTrue(l.cells.none { overlap(it.tile, sexRect(0, width)) || overlap(it.tile, sexRect(1, width)) || overlap(it.tile, navRect(false, width)) || overlap(it.tile, navRect(true, width)) })
    }

    @Test fun raceGridFitsEveryScreenAndCount() {
        for ((w, h) in screens) for (n in 10..30) {
            check(w, h, RaceCounts(5, n - 5), RaceCounts(5, n - 5))        // Wrath++'s five a side, then the allied races
            check(w, h, RaceCounts(5, n - 5), RaceCounts(5, max(0, n - 8))) // the sides unequal
            check(w, h, RaceCounts(min(n, 10), n - min(n, 10)), RaceCounts(0, n))
        }
        for ((w, h) in screens) { check(w, h, RaceCounts(0, 0), RaceCounts(0, 0)); check(w, h, RaceCounts(5, 0), RaceCounts(5, 0)); check(w, h, RaceCounts(13, 0), RaceCounts(1, 12)) }
    }

    @Test fun raceGridKeepsTheLuasSizeWhereItFitsAndScalesBelowOnlyToTheTouchTarget() {
        // 1280 x 720: the Lua's icons, seven rows, the classic column then the allied ones, as in the Lua's own screen
        val l = raceLayout(1365.3f, 720f / 768f, RaceCounts(5, 8), RaceCounts(5, 8))
        assertEquals(RaceMode.SIDES, l.mode); near(l.scale, 1f); near(l.button, CL.u(79)); near(l.rowPitch, CL.u(97))
        near(l.cell(true, 0)!!.button.x, CL.u(68)); near(l.cell(false, 0)!!.button.right, 1365.3f - CL.u(68))
        assertEquals(0, l.cell(true, 4)!!.col); assertEquals(1, l.cell(true, 5)!!.col); assertEquals(0, l.cell(true, 5)!!.row)
        // 7 mm at 420 dpi is 116 px: on the Fold's inner screen (2.56 px a unit) 45 units, 0.67 of the Lua's button; on 1080 high and below the Lua's own is smaller than that
        near(RaceGrid.minTouchUnits(1968f / 768f), 45.2f, 0.1f); near(RaceGrid.minScale(1968f / 768f), 0.671f, 0.002f)
        near(RaceGrid.minScale(1080f / 768f), 1f); near(RaceGrid.minScale(2520f / 768f), 0.524f, 0.003f)
        // the inner screen: shrunk before a column is added (allied 8 + classic 5 need three columns at the Lua's size; two fit)
        val inner = raceLayout(2184f / 1968f * 768f, 1968f / 768f, RaceCounts(5, 8), RaceCounts(5, 8))
        assertEquals(RaceMode.SIDES, inner.mode); assertTrue(inner.scale < 1f && inner.scale >= 0.67f)
        assertEquals(3, inner.cells.filter { it.alliance }.maxOf { it.col } + 1)
    }

    @Test fun aNarrowPortraitStillHoldsEveryRaceAndAFewerRacesLeaveRoom() {
        val ppu = 2520f / 768f
        val l = raceLayout(1080f / ppu, ppu, RaceCounts(5, 8), RaceCounts(5, 8))
        assertTrue(l.fits); assertTrue(l.cells.all { inside(it.tile, l.safe) })
        val big = raceLayout(1080f / ppu, ppu, RaceCounts(5, 25), RaceCounts(5, 25))
        assertEquals(RaceMode.ROWS, big.mode); assertTrue(big.fits)   // thirty a side does not fit down the sides: rows under the header
    }

    @Test fun wordsComeFromTheGlobalStringsOrTheLuasLiterals() {
        val none = CreatorStrings(null)
        assertEquals("Alliance", none.alliance); assertEquals("Horde", none.horde); assertEquals("Back", none.back); assertEquals("Name", none.name)
        assertEquals("Customize", none.customize); assertEquals("Finish", none.finish)
        val t = object : com.logie.wifecraft.creator.Tables {
            override fun rows(name: String) = if (name != "GlobalStrings") emptyList() else listOf(
                mapOf("BaseTag" to "FACTION_ALLIANCE", "TagText_lang" to "Alianza"), mapOf("BaseTag" to "BACK", "TagText_lang" to "Volver"),
                mapOf("BaseTag" to "NAME", "TagText_lang" to ""), mapOf("BaseTag" to "OTHER", "TagText_lang" to "x"))
        }
        val s = CreatorStrings(t)
        assertEquals("Alianza", s.alliance); assertEquals("Volver", s.back)
        assertEquals("Name", s.name)            // an empty string is no string
        assertEquals("Horde", s.horde)
        assertEquals("Customize", s.customize)  // the Lua's own literals, whatever the table says
        assertEquals(listOf("Full", "Face", "Torso", "Rear"), VIEWS.map { it.name })
    }

    @Test fun theBodyPictureIsATorsoOnTheFacesDiscInGoldOrGrey() {
        // a stand-in for the Face piece: a grey ring and disc with a bright head in the middle
        val w = 156; val h = 158
        val head = IntArray(w * h) { p ->
            val y = p / w; val x = p % w
            val r = kotlin.math.hypot(y - (h - 1) / 2.0, x - (w - 1) / 2.0)
            when { r > 74 -> 0; r > 60 -> (255 shl 24) or (200 shl 16) or (170 shl 8) or 90; r > 30 -> (255 shl 24) or (60 shl 16) or (60 shl 8) or 70; else -> (255 shl 24) or (250 shl 16) or (250 shl 8) or 250 }
        }
        val grey = BodyIcon.figure(head, w, h, false)
        val gold = BodyIcon.figure(head, w, h, true)
        assertEquals(w * h, grey.size)
        // the head is painted out: the middle of the disc is the figure now, not the stand-in's white head at the top of the disc
        val cx = w / 2; val cy = h / 2
        fun chan(c: Int, s: Int) = c shr s and 255
        val figure = grey[cy * w + cx]
        assertTrue(chan(figure, 24) == 255)
        val topOfDisc = grey[(cy - 24) * w + cx]
        assertTrue(chan(topOfDisc, 16) < 200, "the head is still there: ${chan(topOfDisc, 16)}")
        // chosen is gold (red and green over blue) with a glow, the other is neutral
        var gr = 0L; var gg = 0L; var gb = 0L; var nr = 0L; var nb = 0L; var n = 0
        for (y in cy - 20..cy + 20) for (x in cx - 10..cx + 10) { val a = gold[y * w + x]; val b = grey[y * w + x]; gr += chan(a, 16); gg += chan(a, 8); gb += chan(a, 0); nr += chan(b, 16); nb += chan(b, 0); n++ }
        assertTrue(gr > gb * 1.5, "gold not gold"); assertTrue(gg > gb)
        assertTrue(kotlin.math.abs(nr - nb) < n * 20, "grey not grey")
        assertTrue(gold != grey)
        // outside the disc stays clear
        assertEquals(0, chan(grey[0], 24))
    }

    @Test fun theTorsoIsAWomansWiderAtTheHipsAndShouldersThanTheWaist() {
        val (h, edge) = BodyIcon.torso(200)
        fun width(row: Int) = (0 until 200).count { edge[row * 200 + it] > 0.5 }
        val shoulders = width((0.20 * 199).toInt()); val waist = width((0.58 * 199).toInt()); val hips = width((0.78 * 199).toInt())
        assertTrue(waist < shoulders && waist < hips, "$shoulders $waist $hips")
        assertTrue(h.max() > 0.5)
    }

    @Test fun everyPieceTheLuaAsksForIsInTheArtTable() {
        val dir = System.getProperty("wifecraft.assets") ?: return
        val doc = org.json.JSONObject(java.io.File(dir, "creator_art.json").readText())
        val pieces = doc.getJSONObject("pieces")
        val wanted = listOf(
            "!charactercreatedropdown-nineslice-edgeleft", "!charactercreatedropdown-nineslice-edgeright", "128-redbutton-highlight",
            "128-redbutton-left", "128-redbutton-left-disabled", "128-redbutton-left-pressed", "128-redbutton-right", "128-redbutton-right-disabled",
            "128-redbutton-right-pressed", "_128-redbutton-center", "_128-redbutton-center-disabled", "_128-redbutton-center-pressed",
            "_charactercreatedropdown-nineslice-edgebottom", "_charactercreatedropdown-nineslice-edgetop",
            "charactercreate-customize-backbutton", "charactercreate-customize-backbutton-disabled", "charactercreate-customize-backbutton-down",
            "charactercreate-customize-dropdownbox", "charactercreate-customize-dropdownbox-hover", "charactercreate-customize-dropdownbox-open",
            "charactercreate-customize-nextbutton", "charactercreate-customize-nextbutton-disabled", "charactercreate-customize-nextbutton-down",
            "charactercreate-customize-palette", "charactercreate-customize-palette-half", "charactercreate-customize-palette-selected",
            "charactercreate-icon-alliance", "charactercreate-icon-horde", "charactercreate-icon-dice",
            "charactercreate-icon-customize-head", "charactercreate-icon-customize-head-selected", "charactercreate-icon-customize-hair",
            "charactercreate-icon-customize-hair-selected", "charactercreate-icon-customize-accessories", "charactercreate-icon-customize-accessories-selected",
            "charactercreate-icon-customize-body", "charactercreate-icon-customize-body-selected",
            "charactercreate-ring-alliance", "charactercreate-ring-horde", "charactercreate-ring-metaldark", "charactercreate-ring-metallight", "charactercreate-ring-select",
            "charactercreate-vignette-bottom", "charactercreate-vignette-sides", "charactercreate-vignette-top",
            "charactercreate-gendericon-female", "charactercreate-gendericon-female-selected", "charactercreate-gendericon-male", "charactercreate-gendericon-male-selected",
            "charactercreatedropdown-nineslice-center", "charactercreatedropdown-nineslice-cornerbottomleft", "charactercreatedropdown-nineslice-cornerbottomright",
            "charactercreatedropdown-nineslice-cornertopleft", "charactercreatedropdown-nineslice-cornertopright",
            "common-button-square-gray-down", "common-button-square-gray-up", "common-gray-button-entrybox-center", "common-gray-button-entrybox-left",
            "common-gray-button-entrybox-right", "common-icon-rotateleft", "common-icon-rotateright", "common-icon-undo", "common-icon-zoomin", "common-icon-zoomout",
            "common-icon-backarrow", "common-icon-backarrow-disable", "common-icon-forwardarrow", "common-icon-forwardarrow-disable",
        )
        val missing = wanted.filter { !pieces.has(it) }
        assertTrue(missing.isEmpty(), "missing: $missing")
        // the woman's Body picture is made on the phone from the Face piece; the switch's check box is plain files (ids only)
        assertFalse(pieces.has("charactercreate-icon-customize-figure"))
        val files = doc.getJSONObject("files")
        for (n in listOf("checkbox-up", "checkbox-down", "checkbox-highlight", "checkbox-check", "checkbox-check-disabled")) {
            val f = files.getJSONObject(n); assertTrue(f.getInt("f") > 0); assertEquals(32, f.getJSONArray("s").getInt(0))
        }
    }
}
