package com.logie.wifecraft.ui

import kotlin.math.abs
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

    @Test fun racesFillAColumnOfFiveThenASecondOneLower() {
        val s0 = raceSlot(0, true); near(s0.x, 0f); near(s0.y, 0f)
        val s4 = raceSlot(4, true); near(s4.y, 4 * CL.u(97))
        val s5 = raceSlot(5, true); near(s5.x, CL.u(97)); near(s5.y, CL.u(68))
        val s6 = raceSlot(6, true); near(s6.y, CL.u(97) + CL.u(68))
        assertTrue(raceSlot(0, false).right)
        // a Horde column hangs from the right edge, its second column further in
        val w = 1365f
        val a = raceRect(raceSlot(0, true), w); val h = raceRect(raceSlot(0, false), w)
        near(a.x, CL.u(68)); near(h.right, w - CL.u(68))
        near(raceRect(raceSlot(5, false), w).x, h.x - CL.u(97))
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
}
