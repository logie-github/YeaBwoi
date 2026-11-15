package com.logie.wifecraft.ui

import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/*
 * The creator screen's numbers and behaviour, without Compose or Android, so that JVM tests can run them.
 *
 * Everything here is WrathppCreate.lua's (Blizzard/app/src/main/assets/wrathpp/WrathppCreate.lua), which draws Shadowlands'
 * own creator (Blizzard_CharacterCreate and Blizzard_CharacterCustomize, build 9.2.7) on a screen 768 units high; each
 * constant says the Lua line it comes from. Positions are in the screen's units (768 high, as many wide as the aspect
 * gives); y goes DOWN here (the Lua's goes up: a y that is "up" in the Lua says so in its name or comment).
 */

/** Every size, place, colour and rule of the Lua, by name, with its line. */
object CL {
    // Lua 49-55: Shadowlands lays out for 1080 high; the screen is 768 high; SIZE draws all of it larger again.
    const val SIZE = 1.2f
    const val S = 768f / 1080f * SIZE
    const val SCREEN_H = 768f
    fun u(n: Float): Float = n * S
    fun u(n: Int): Float = n * S

    // Lua 68-74
    const val POP = 1.4f
    val ROW_PITCH = u(38 + 32)
    val ROW_H = ROW_PITCH
    const val ROW_W = 223
    const val NAME_W = 190
    val LIST_W = u(ROW_W + 34 + NAME_W)
    val GOLD = 0xFFD100          // (1, 0.82, 0) Lua 74
    const val GOLD_R = 1f; const val GOLD_G = 0.82f; const val GOLD_B = 0f
    const val GREY = 0.5f        // Lua 196, 1310: (0.5, 0.5, 0.5)

    // Fonts: Lua 185-199 (12 GameFontNormal, 14, 18, 22 outlined; shadow one unit down and right)
    const val FONT_SMALL = 12f; const val FONT_MED = 14f; const val FONT_LARGE = 18f; const val FONT_BUTTON = 22f
    const val SHADOW = 1f

    // Small button: Lua 202-220
    const val SMALL_BUTTON = 48; const val SMALL_ICON_W = 24; const val SMALL_ICON_H = 23

    // Masked (round) buttons: Lua 1076 (category), 1377 (race), 1409 (sex): size, ring size, chosen ring.
    // (Classes, Lua 1393, are dropped: no classes here.)
    data class Round(val w: Int, val h: Int, val ringW: Int, val ringH: Int, val chosen: Int, val kind: String, val ring: String)
    val CATEGORY = Round(104, 105, 108, 109, 93, "category", "charactercreate-ring-metallight")
    val RACE_ALLIANCE = Round(79, 79, 139, 140, 118, "race", "charactercreate-ring-alliance")
    val RACE_HORDE = Round(79, 79, 139, 140, 118, "race", "charactercreate-ring-horde")
    val SEX = Round(46, 46, 99, 100, 84, "sex", "charactercreate-ring-metaldark")
    const val RING_SELECT = "charactercreate-ring-select"
    const val DIM_OFF = 0.35f      // Lua 245
    const val DIM_NOT_DRAWN = 0.55f

    // Red button "128-RedButton": Lua 263-301 (250 x 66, left piece 114, right 292 of 128 high, caption 22, arrow 8 x 13, gap 12)
    const val NAV_W = 250; const val NAV_H = 66
    const val RED_PIECE_H = 128; const val RED_LEFT = 114; const val RED_RIGHT = 292
    const val ARROW_W = 8; const val ARROW_H = 13; const val ARROW_GAP = 12
    const val HELD_NUDGE = 2f      // Lua 294
    const val SMALL_HELD_NUDGE = 1f // Lua 214

    // Swatch: Lua 303-314 (84 x 20 brush stroke; the second colour 36 x 8 at +18, -2 from its left)
    const val SWATCH_W = 84; const val SWATCH_H = 20
    const val SWATCH2_W = 36; const val SWATCH2_H = 8; const val SWATCH2_DX = 18; const val SWATCH2_DY = 2

    // Nine slice "CharacterCreateDropdown": Lua 318-341. Corners set out from the frame by 30 sideways and 20 up/down.
    const val NS_OUT_X = 30; const val NS_OUT_Y = 20
    const val NS_CORNER_W = 62; const val NS_TOP_H = 52; const val NS_BOTTOM_H = 72

    // The foot: Lua 898-910 (46 in, 28 up); header Lua 813-827; sexes Lua 830-832
    const val FOOT_X = 46; const val FOOT_Y = 28
    const val HEADER_W = 92; const val HEADER_H = 100; const val HEADER_X = 3; const val HEADER_Y = 10
    const val HEADER_NAME_DX = 18; const val HEADER_NAME_DY = 2
    const val RACES_X = 68; const val RACES_Y = 106; const val RACE_PITCH = 79 + 18; const val RACE_COLUMN_DROP = 68
    const val RACES_PER_COLUMN = 5
    const val RACE_NAME_DX = 2; const val RACE_NAME_UP = 7
    const val SEX_PITCH = 46 + 22; const val SEX_TOP = 27

    // Customize: Lua 838-921
    const val CAT_RIGHT = 20; const val CAT_TOP = 166; const val CAT_OVERLAP = 21
    const val DICE_GAP = 9
    const val OPT_RIGHT = 33; const val OPT_TOP = 297
    val OPT_H = SCREEN_H - u(OPT_TOP) - u(FOOT_Y + NAV_H + 14)
    const val VIEW_X = 40; const val VIEW_Y = 30; const val VIEW_OVERLAP = 5; const val VIEW_TURN_GAP = 30
    val VIEW_W = u(48 * 5 - 5 * 4 + 30)

    // Name: Lua 857-883 (34 down; "Name" 18 white; the box 343 x 48, 2 under it; left piece 13, right 314)
    const val NAME_TOP = 34; const val NAME_TITLE_H = 20; const val NAME_GAP = 2; const val NAME_BOX_W = 343; const val NAME_BOX_H = 48
    const val NAME_LEFT_PIECE = 13; const val NAME_RIGHT_PIECE = 314; const val NAME_TEXT_INSET = 30; const val NAME_TEXT_H = 20

    // The zoom slider: Lua 997-1020
    const val SLIDER_W = 150; const val SLIDER_H = 30; const val SLIDER_GAP = 14; const val SLIDER_TRACK_H = 8; const val SLIDER_EDGE_H = 2
    const val SLIDER_THUMB = 26
    const val ZOOM_MAX = 3.4f

    // View buttons under it: Lua 1043-1059 (120 x 38, 2 in, 12 down, 46 apart; caption 14 gold)
    const val VIEWBTN_W = 120; const val VIEWBTN_H = 38; const val VIEWBTN_X = 2; const val VIEWBTN_Y = 12; const val VIEWBTN_PITCH = 46

    // Option rows: Lua 1101-1180 (frame 223 x 38 + name; box 150, next +4, previous -5, name ends 34 before previous; row +4 down)
    const val ROW_BOX_W = 150; const val ROW_BOX_H = 38; const val ROW_ARROW = 32
    const val ROW_NEXT_GAP = 4; const val ROW_PREV_GAP = 5; const val ROW_NAME_GAP = 34; const val ROW_TOP_PAD = 4
    const val ROW_LABEL_INSET = 12; const val CHECK_SIZE = 32; const val CHECK_TEXT_GAP = 6

    // Popout: Lua 1232-1347 (one column to 10, two to 24, three to 36, four beyond; 20 high lines; POP larger)
    const val POP_LINE = 20; const val POP_PAD_X = 6; const val POP_PAD_TOP = 12; const val POP_EXTRA_H = 32
    const val POP_NUMBER_X = 14; const val POP_NUMBER_W = 25; const val POP_MARK_W = 102; const val POP_MARK_H = 40; const val POP_MARK_DX = 4
    const val POP_SWATCH2_DX = 18; const val POP_SWATCH2_DY = 2
    const val POP_HANG_X = 3; const val POP_HANG_Y = 11; const val POP_MARGIN = 24
    fun p(n: Float): Float = u(n * POP)
    fun p(n: Int): Float = u(n * POP)

    // The scroller: Lua 712, 781, 764-777
    const val SLOP = 6f; const val KEEP = 0.1f
    const val WHEEL_ROWS = 1.5f
    const val FLING_MIN_START = 40f; const val FLING_MIN_STOP = 15f; const val FLING_DECAY = 4f; const val EASE_RATE = 14f
    const val FLING_LAST_SAMPLE = 0.06f; const val FLING_SPAN = 0.008f; const val BUSY_GAP = 0.25f

    // The spin area: Lua 963-965
    val SPIN_LEFT = u(68 + 79 + 40); const val SPIN_TOP = 110f
    val SPIN_RIGHT_INSET = LIST_W + u(60); val SPIN_BOTTOM_INSET = u(62 + 67 + 60)

    // Turning and zoom: Lua 926-960, 1841-1863
    const val DRAG_DEGREES = 0.45f; const val TURN_RATE = 140f; const val SPIN_KEEP = 0.7f; const val SPIN_MIX = 0.3f
    const val SPIN_MIN = 4f; const val SPIN_DECAY = 3f; const val ZOOM_SHARE_RATE = 8f; const val ZOOM_SNAP = 0.002f
    const val FOCUS_MIN = 0.25f; const val FOCUS_MAX = 0.95f; const val ZOOM_STEP = 0.3f; const val WHEEL_ZOOM = 0.2f
    const val RESET_FACING = -15f; const val RESET_FOCUS = 0.6f

    // The vignette: Lua 791-808 (451 of 1080 down, 577 up, 703 of 1920 in from each side)
    val VIGNETTE_TOP = 768f * 451f / 1080f; val VIGNETTE_BOTTOM = 768f * 577f / 1080f
    fun vignetteSide(width: Float): Float = width * 703f / 1920f
}

// ---- Scrolling lists (Lua 666-785) ------------------------------------------------------------------------------------

/**
 * A list that scrolls under a finger: a drag moves the content with the finger one for one once it has moved past SLOP
 * (a tap stays a tap), carries on after a flick (decaying as a page on a phone), and eases (the same share of the way
 * each moment, whatever the frame rate) to a wheel or a reveal. [y] is "up positive", as the Lua's cursor: a finger
 * moving up the screen is a bigger y and a bigger offset (the content goes up); callers pass -pointerY.
 */
class ListScroll(view: Float, content: Float = view) {
    var view = view; private set
    var content = content; private set
    var offset = 0f; private set
    var target = 0f; private set
    var velocity = 0f; private set
    var dragging = false; private set
    var flinging = false; private set
    var moved = false; private set
    private var pressY = 0f
    private var pressOffset = 0f
    private val samples = ArrayList<FloatArray>()   // (time, offset)

    fun limit(): Float = max(0f, content - view)

    fun setContent(h: Float) {
        content = h
        target = min(target, limit())
        offset = min(offset, limit())
    }

    fun setView(h: Float) { view = h; setContent(content) }

    /** The bar's length and top (from the view's top), or null when everything fits (Lua 693-703). */
    fun bar(): Pair<Float, Float>? {
        val limit = limit()
        if (limit <= 0f) return null
        val length = max(24f, view * view / content)
        return length to (view - length) * (offset / limit)
    }

    fun scrollTo(o: Float) { target = max(0f, min(o, limit())); velocity = 0f; flinging = false }

    fun reveal(top: Float, bottom: Float) {
        if (top < target) scrollTo(top) else if (bottom > target + view) scrollTo(bottom - view)
    }

    /** The wheel: delta notches, three rows for two notches (Lua 781). */
    fun wheel(delta: Float) = scrollTo(target - delta * CL.ROW_H * CL.WHEEL_ROWS)

    fun busy(): Boolean = dragging || flinging || abs(target - offset) >= CL.BUSY_GAP

    fun tapped(): Boolean = !moved

    fun press(y: Float) {
        dragging = true; pressY = y; pressOffset = offset; moved = false; velocity = 0f; flinging = false
        target = offset
        samples.clear()
    }

    fun release(now: Float) {
        if (!dragging) return
        dragging = false
        velocity = 0f; flinging = false
        val last = samples.lastOrNull()
        // (a finger that stopped before it lifted throws nothing)
        if (moved && last != null && now - last[0] < CL.FLING_LAST_SAMPLE) {
            var first = samples.first()
            for (s in samples) if (now - s[0] <= CL.KEEP) { first = s; break }
            if (last[0] - first[0] > CL.FLING_SPAN) {
                velocity = (last[1] - first[1]) / (last[0] - first[0])
                flinging = abs(velocity) > CL.FLING_MIN_START
            }
        }
        target = offset
    }

    /** The finger moved to [y] (up positive) at [now] seconds. */
    fun drag(y: Float, now: Float) {
        if (!dragging) return
        if (!moved && abs(y - pressY) > CL.SLOP) {
            // from here on with the finger, without the slop's jump
            moved = true; pressY = y; pressOffset = offset
        }
        if (moved) {
            val o = max(0f, min(pressOffset + (y - pressY), limit()))
            offset = o; target = o
            samples.add(floatArrayOf(now, o))
            while (samples.size > 2 && now - samples[0][0] > CL.KEEP * 2) samples.removeAt(0)
        }
    }

    /** Every frame, [elapsed] seconds: the fling carrying on, the ease to a target. */
    fun update(elapsed: Float) {
        if (elapsed <= 0f) return
        val limit = limit()
        if (dragging) return
        if (flinging) {
            // on with the speed it was let go at, slowing as a page on a phone does, stopping at the ends
            offset += velocity * elapsed
            velocity *= exp(-elapsed * CL.FLING_DECAY)
            if (offset <= 0f || offset >= limit) { offset = max(0f, min(offset, limit)); velocity = 0f }
            if (abs(velocity) < CL.FLING_MIN_STOP) { flinging = false; velocity = 0f }
            target = offset
        } else if (offset != target) {
            val gap = target - offset
            offset = if (abs(gap) < 0.25f) target else offset + gap * (1f - exp(-elapsed * CL.EASE_RATE))
        }
    }
}

// ---- The popout's columns (Lua 1277-1320) -------------------------------------------------------------------------------

data class Columns(val columns: Int, val stride: Int)

/** Shadowlands' getNumColumnsAndStride: one column to 10 choices, two to 24, three to 36, four beyond; more where the screen has not the lines ([most]). */
fun columnsFor(n: Int, most: Int? = null): Columns {
    var columns = if (n > 36) 4 else if (n > 24) 3 else if (n > 10) 2 else 1
    var stride = ceil(n.toFloat() / columns).toInt()
    if (most != null && most >= 1 && stride > most) {
        columns = ceil(n.toFloat() / most).toInt()
        stride = ceil(n.toFloat() / columns).toInt()
    }
    return Columns(columns, stride)
}

data class PopCell(val index: Int, val x: Float, val y: Float, val w: Float, val h: Float)

/** The popout filled: its size and where each entry (index from 0) goes, from the popout's top left. */
data class PopLayout(val columns: Int, val stride: Int, val details: Float, val cellW: Float, val width: Float, val height: Float, val cells: List<PopCell>)

/**
 * fillPopout: [n] choices, whether any has a name ([named]) or a swatch ([coloured]), [room] the height there is for it
 * (units) or null. A column is as wide as its number and what follows it.
 */
fun fillPopout(n: Int, named: Boolean, coloured: Boolean, room: Float?): PopLayout {
    val most = if (room != null) floor((room - CL.p(CL.POP_EXTRA_H)) / CL.p(CL.POP_LINE)).toInt() else null
    val (columns, stride) = columnsFor(n, most)
    var details = 116f
    if (columns > 1) details = if (coloured) (25 + 36 + 18).toFloat() else if (named) 108f else 42f
    val width = CL.p(14 + details + 14)
    val cells = List(n) { k ->
        PopCell(k, CL.p(CL.POP_PAD_X) + (k / stride) * width, CL.p(CL.POP_PAD_TOP) + (k % stride) * CL.p(CL.POP_LINE), width, CL.p(CL.POP_LINE))
    }
    return PopLayout(columns, stride, details, width, columns * width + CL.p(CL.POP_PAD_X) * 2, stride * CL.p(CL.POP_LINE) + CL.p(CL.POP_EXTRA_H), cells)
}

data class PopPlacement(val under: Boolean, val room: Float, val left: Float, val top: Float)

/**
 * Lua 1331-1343: under the box as Shadowlands hangs it, or over it where there is more room there. [boxTop], [boxBottom],
 * [boxRight] are the choice box's edges (y down); returns the popout's top left given its [width] and [height], which
 * depend on the room (so the caller asks with [stride] of the whole list first).
 */
fun popoutSide(boxTop: Float, boxBottom: Float, screenH: Float, choices: Int): Pair<Boolean, Float> {
    val below = (screenH - boxBottom) + CL.u(CL.POP_HANG_Y) - CL.u(CL.POP_MARGIN)
    val above = boxTop + CL.u(CL.POP_HANG_Y) - CL.u(CL.POP_MARGIN)
    val stride = columnsFor(choices).stride
    val under = (stride * CL.p(CL.POP_LINE) + CL.p(CL.POP_EXTRA_H) <= below) || (below >= above)
    return under to (if (under) below else above)
}

fun popoutPlace(under: Boolean, boxTop: Float, boxBottom: Float, boxRight: Float, width: Float, height: Float): PopPlacement {
    val left = boxRight + CL.u(CL.POP_HANG_X) - width
    val top = if (under) boxBottom - CL.u(CL.POP_HANG_Y) else boxTop + CL.u(CL.POP_HANG_Y) - height
    return PopPlacement(under, 0f, left, top)
}

// ---- Rectangles of the panels ---------------------------------------------------------------------------------------------

data class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
    fun contains(px: Float, py: Float) = px >= x && px < x + w && py >= y && py < y + h
    fun offset(dx: Float, dy: Float) = Rect(x + dx, y + dy, w, h)
}

/** Lua 1486-1487: the k-th (from 0) race button of a side in its column, from the column's top corner (x right for the Horde). */
data class RaceSlot(val k: Int, val x: Float, val y: Float, val right: Boolean)

fun raceSlot(k: Int, alliance: Boolean): RaceSlot {
    val n = k + 1
    val column = if (n > CL.RACES_PER_COLUMN) 1 else 0
    val line = if (n > CL.RACES_PER_COLUMN) n - CL.RACES_PER_COLUMN - 1 else n - 1
    return RaceSlot(k, column * CL.u(CL.RACE_PITCH).toFloat(), line * CL.u(CL.RACE_PITCH) + column * CL.u(CL.RACE_COLUMN_DROP), !alliance)
}

/** The screen rectangle of a race button, for a screen [width] units wide. */
fun raceRect(slot: RaceSlot, width: Float): Rect {
    val w = CL.u(79)
    val colX = if (slot.right) width - CL.u(CL.RACES_X) - w else CL.u(CL.RACES_X)
    val x = if (slot.right) colX - slot.x else colX + slot.x
    return Rect(x, CL.u(CL.RACES_Y) + slot.y, w, w)
}

/** Lua 1602: the categories' round buttons in a row ending 20 from the right; k from 0 of [n]; the button's rectangle. */
fun categoryRect(k: Int, n: Int, width: Float): Rect {
    val w = CL.u(CL.CATEGORY.w); val h = CL.u(CL.CATEGORY.h)
    val right = width - CL.u(CL.CAT_RIGHT) - (n - 1 - k) * CL.u(CL.CATEGORY.w - CL.CAT_OVERLAP)
    return Rect(right - w, CL.u(CL.CAT_TOP), w, h)
}

/** The width of the category row (Lua 1609). */
fun categoryRowWidth(n: Int): Float = CL.u(CL.CATEGORY.w) + (n - 1) * CL.u(CL.CATEGORY.w - CL.CAT_OVERLAP)

fun diceRect(n: Int, width: Float): Rect {
    val row = categoryRect(0, n, width)
    val s = CL.u(CL.SMALL_BUTTON)
    return Rect(row.x - CL.u(CL.DICE_GAP) - s, CL.u(CL.CAT_TOP) + (CL.u(CL.CATEGORY.h) - s) / 2f, s, s)
}

fun optionPanel(width: Float) = Rect(width - CL.u(CL.OPT_RIGHT) - CL.LIST_W, CL.u(CL.OPT_TOP), CL.LIST_W, CL.OPT_H)

/** The five small view buttons (Lua 980-992): reset, zoom out, zoom in (touching, 5 over), turn left (30 after), turn right. */
fun viewSmallRect(i: Int): Rect {
    val s = CL.u(CL.SMALL_BUTTON); val pitch = s - CL.u(CL.VIEW_OVERLAP)
    val x = CL.u(CL.VIEW_X) + when (i) { 0 -> 0f; 1 -> pitch; 2 -> 2 * pitch; 3 -> 3 * pitch + CL.u(CL.VIEW_TURN_GAP); else -> 4 * pitch + CL.u(CL.VIEW_TURN_GAP) }
    return Rect(x, CL.u(CL.VIEW_Y), s, s)
}

fun sliderRect(): Rect {
    val last = viewSmallRect(4)
    val h = CL.u(CL.SLIDER_H)
    return Rect(last.right + CL.u(CL.SLIDER_GAP), last.y + (last.h - h) / 2f, CL.u(CL.SLIDER_W), h)
}

fun viewButtonRect(k: Int): Rect = Rect(CL.u(CL.VIEW_X) + CL.u(CL.VIEWBTN_X), CL.u(CL.VIEW_Y) + CL.u(CL.SMALL_BUTTON) + CL.u(CL.VIEWBTN_Y) + k * CL.u(CL.VIEWBTN_PITCH), CL.u(CL.VIEWBTN_W), CL.u(CL.VIEWBTN_H))

fun sexRect(i: Int, width: Float): Rect {
    val total = CL.u(CL.SEX_PITCH + 46)   // 46 * 2 + 22
    val s = CL.u(46)
    return Rect((width - total) / 2f + i * CL.u(CL.SEX_PITCH), CL.u(CL.SEX_TOP) + 0f, s, s)
}

fun navRect(forward: Boolean, width: Float): Rect {
    val w = CL.u(CL.NAV_W); val h = CL.u(CL.NAV_H)
    return Rect(if (forward) width - CL.u(CL.FOOT_X) - w else CL.u(CL.FOOT_X), CL.SCREEN_H - CL.u(CL.FOOT_Y) - h, w, h)
}

fun nameTitleRect(width: Float): Rect { val w = CL.u(CL.NAME_BOX_W); return Rect((width - w) / 2f, CL.u(CL.NAME_TOP), w, CL.u(CL.NAME_TITLE_H)) }
fun nameBoxRect(width: Float): Rect { val w = CL.u(CL.NAME_BOX_W); return Rect((width - w) / 2f, CL.u(CL.NAME_TOP + CL.NAME_TITLE_H + CL.NAME_GAP), w, CL.u(CL.NAME_BOX_H)) }

fun spinRect(width: Float): Rect = Rect(CL.SPIN_LEFT, CL.SPIN_TOP, width - CL.SPIN_RIGHT_INSET - CL.SPIN_LEFT, CL.SCREEN_H - CL.SPIN_BOTTOM_INSET - CL.SPIN_TOP)

/** The nine pieces of the dropdown frame around [f] (Lua 321-329): corners set out, edges between, middle inside. Names are the pieces' ends. */
data class Slice(val piece: String, val rect: Rect)

fun nineSlice(f: Rect): List<Slice> {
    val ox = CL.u(CL.NS_OUT_X); val oy = CL.u(CL.NS_OUT_Y)
    val cw = CL.u(CL.NS_CORNER_W); val th = CL.u(CL.NS_TOP_H); val bh = CL.u(CL.NS_BOTTOM_H)
    val tl = Rect(f.x - ox, f.y - oy, cw, th)
    val tr = Rect(f.right + ox - cw, f.y - oy, cw, th)
    val bl = Rect(f.x - ox, f.bottom + oy - bh, cw, bh)
    val br = Rect(f.right + ox - cw, f.bottom + oy - bh, cw, bh)
    return listOf(
        Slice("charactercreatedropdown-nineslice-center", Rect(tl.right, tl.bottom, tr.x - tl.right, br.y - tl.bottom)),
        Slice("_charactercreatedropdown-nineslice-edgetop", Rect(tl.right, tl.y, tr.x - tl.right, th)),
        Slice("_charactercreatedropdown-nineslice-edgebottom", Rect(bl.right, bl.y, br.x - bl.right, bh)),
        Slice("!charactercreatedropdown-nineslice-edgeleft", Rect(tl.x, tl.bottom, cw, bl.y - tl.bottom)),
        Slice("!charactercreatedropdown-nineslice-edgeright", Rect(tr.x, tr.bottom, cw, br.y - tr.bottom)),
        Slice("charactercreatedropdown-nineslice-cornertopleft", tl),
        Slice("charactercreatedropdown-nineslice-cornertopright", tr),
        Slice("charactercreatedropdown-nineslice-cornerbottomleft", bl),
        Slice("charactercreatedropdown-nineslice-cornerbottomright", br),
    )
}

// ---- An option row (Lua 1120-1180) ---------------------------------------------------------------------------------------------

/** What a touch in a row hits. */
enum class RowPart { NONE, PREVIOUS, NEXT, CHOICE, CHECK }

/** One row's parts, in the list's own coordinates (x from the list's left, y from the content's top), for row [k] from 0. */
class RowGeometry(val k: Int) {
    val frame = Rect(0f, k * CL.ROW_PITCH + CL.u(CL.ROW_TOP_PAD), CL.LIST_W, CL.u(CL.ROW_BOX_H))
    /** Lua 1128: the box's right edge is (223 - 150) / 2 in from the row's right. */
    val choice = Rect(frame.right - CL.u((CL.ROW_W - CL.ROW_BOX_W) / 2f) - CL.u(CL.ROW_BOX_W), frame.y, CL.u(CL.ROW_BOX_W), CL.u(CL.ROW_BOX_H))
    val next = Rect(choice.right + CL.u(CL.ROW_NEXT_GAP), frame.y + (frame.h - CL.u(CL.ROW_ARROW)) / 2f, CL.u(CL.ROW_ARROW), CL.u(CL.ROW_ARROW))
    val previous = Rect(choice.x - CL.u(CL.ROW_PREV_GAP) - CL.u(CL.ROW_ARROW), next.y, CL.u(CL.ROW_ARROW), CL.u(CL.ROW_ARROW))
    /** The name ends 34 before the previous arrow and runs from the row's left. */
    val name = Rect(0f, frame.y + (frame.h - CL.u(16)) / 2f, previous.x - CL.u(CL.ROW_NAME_GAP), CL.u(16))
    /** The switch's box where the previous arrow would be... Lua 1146: its left at the row's right less 223. */
    val check = Rect(frame.right - CL.u(CL.ROW_W), frame.y + (frame.h - CL.u(CL.CHECK_SIZE)) / 2f, CL.u(CL.CHECK_SIZE), CL.u(CL.CHECK_SIZE))

    fun hit(x: Float, y: Float, isSwitch: Boolean): RowPart = when {
        isSwitch -> if (check.contains(x, y) || Rect(check.x, frame.y, frame.right - check.x, frame.h).contains(x, y)) RowPart.CHECK else RowPart.NONE
        previous.contains(x, y) -> RowPart.PREVIOUS
        next.contains(x, y) -> RowPart.NEXT
        choice.contains(x, y) -> RowPart.CHOICE
        else -> RowPart.NONE
    }
}

/** The row under a point of the list (x from the list's left, y from the content's top), or -1. */
fun rowAt(y: Float, rows: Int): Int {
    if (y < 0f) return -1
    val k = floor(y / CL.ROW_PITCH).toInt()
    return if (k in 0 until rows) k else -1
}

fun contentHeight(rows: Int): Float = rows * CL.ROW_PITCH

/** The label in the box (Lua 1209-1212): nothing where a colour shows, the choice's name, else its number (from 1). */
fun choiceLabel(name: String, hasSwatch: Boolean, at: Int): String = when {
    hasSwatch -> ""
    name.isNotEmpty() -> name
    at > 0 -> at.toString()
    else -> ""
}

/** The place of a choice among [ids], from 1, or 0 (Lua's `at`). */
fun placeOf(ids: List<Int>, id: Int): Int = ids.indexOf(id) + 1

/** The next or previous place round the ends, from 0 (Lua 1183-1189). */
fun stepIndex(at0: Int, delta: Int, n: Int): Int = if (n < 1) 0 else (((at0 + delta) % n) + n) % n

// ---- Categories -------------------------------------------------------------------------------------------------------------------

/** Lua 1066, 1091-1096: the picture a category has, by its first word; a woman's Body her own figure where the art has it. */
fun categoryArt(name: String, female: Boolean, hasFigure: Boolean): String {
    val word = Regex("^(\\p{L}+)").find(name)?.value ?: ""
    var art = when (word) { "Face" -> "head"; "Hair" -> "hair"; "Accessories" -> "accessories"; "Body" -> "body"; "Markings" -> "body"; else -> "body" }
    if (word == "Body" && female && hasFigure) art = "figure"
    return art
}

fun categoryPiece(name: String, female: Boolean, hasFigure: Boolean, chosen: Boolean): String =
    "charactercreate-icon-customize-" + categoryArt(name, female, hasFigure) + (if (chosen) "-selected" else "")

/** Lua 1589-1593: the Body tab last in the row, the others in their own order. */
fun <T> bodyLast(row: List<T>, nameOf: (T) -> String): List<T> = row.filter { nameOf(it) != "Body" } + row.filter { nameOf(it) == "Body" }

/** Lua 1083-1084: Shadowlands brings the view in for the face's categories and back out for the body's. (zoom, focusY) */
fun categoryView(zoom: Float): Pair<Float, Float> = if (zoom >= 80f) 1.45f to 0.72f else if (zoom >= 50f) 1.2f to 0.62f else 1f to 0.6f

// ---- Race and sex pieces ------------------------------------------------------------------------------------------------------------

private val RACE_ART = mapOf("scourge" to "undead", "highmountaintauren" to "highmountain", "lightforgeddraenei" to "lightforged", "zandalaritroll" to "zandalari",
    "magharorc" to "magharorc", "darkirondwarf" to "darkirondwarf")

/** Lua 1488-1490: the race's piece "raceicon128-<art>-<sex>"; [has] says which pieces the art has; null where none. */
fun racePiece(key: String, female: Boolean, has: (String) -> Boolean): String? {
    val n = key.lowercase().filter { it in 'a'..'z' }
    val sex = if (female) "female" else "male"
    val candidates = listOfNotNull(RACE_ART[n], n, n.removeSuffix("race"), n.removePrefix("the"))
    return candidates.map { "raceicon128-$it-$sex" }.firstOrNull(has)
}

fun sexPiece(female: Boolean, chosen: Boolean): String = "charactercreate-gendericon-" + (if (female) "female" else "male") + (if (chosen) "-selected" else "")

// ---- The view: turning, zoom, camera (Lua 923-1059, 1837-1863) ---------------------------------------------------------------------------

data class ViewDef(val name: String, val zoom: Float, val focus: Float, val facing: Float?, val again: Float? = null)

val VIEWS = listOf(
    ViewDef("Full", 1f, 0.6f, -15f),
    ViewDef("Face", 3.0f, 0.76f, null),
    ViewDef("Torso", 1.9f, 0.6f, null),
    ViewDef("Rear", 2.1f, 0.44f, 165f, 75f),
)

/** The character's turn, zoom and view, moved as the Lua driver moves them. */
class ViewState {
    var zoom = 1f; var focusY = 0.6f
    var zoomTarget = 1f; var focusTarget = 0.6f
    var facing = CL.RESET_FACING          // degrees
    var spin = 0f                         // degrees a second after a flick
    var turning = false
    var view: String? = null; var viewAgain = false
    var heldTurn = 0                      // -1 turn left held, 1 right, 0 none

    fun setZoom(z: Float, focus: Float? = null) {
        zoomTarget = max(1f, min(z, CL.ZOOM_MAX))
        if (focus != null) focusTarget = focus
    }

    fun nudgeZoom(delta: Float) = setZoom(zoomTarget + delta)

    /** Pinch: the zoom times [factor]. */
    fun pinch(factor: Float) { view = null; setZoom(zoomTarget * factor) }

    fun turn(degrees: Float) { facing += degrees; view = null }

    fun reset() { setZoom(1f, CL.RESET_FOCUS); spin = 0f; facing = CL.RESET_FACING }

    /** Lua 1030-1042: a view's button (Rear tapped again: side on, and again: back to the rear). */
    fun showView(name: String) {
        val v = VIEWS.firstOrNull { it.name == name } ?: return
        viewAgain = if (view == name && v.again != null) !viewAgain else false
        setZoom(v.zoom, v.focus); spin = 0f
        val face = if (viewAgain) v.again else v.facing
        if (face != null) facing = face
        view = name
    }

    fun fingerDown() { turning = true; spin = 0f }
    fun fingerUp() { turning = false }

    /** A finger moved (dx, dy in pixels, dy down) in [elapsed] seconds on a screen [heightUnits] high (Lua 1837-1848). */
    fun fingerMoved(dx: Float, dyUp: Float, elapsed: Float, heightUnits: Float = CL.SCREEN_H) {
        if (dx != 0f) turn(dx * CL.DRAG_DEGREES)
        if (elapsed > 0f) spin = CL.SPIN_KEEP * spin + CL.SPIN_MIX * (dx * CL.DRAG_DEGREES / elapsed)
        if (zoom > 1.05f && dyUp != 0f) {
            focusY = max(CL.FOCUS_MIN, min(CL.FOCUS_MAX, focusY - dyUp / (heightUnits * zoom)))
            focusTarget = focusY
        }
    }

    /** Every frame (Lua 1849-1863). */
    fun update(elapsed: Float) {
        if (!turning && abs(spin) > CL.SPIN_MIN) {
            turn(spin * elapsed)
            spin *= max(0f, 1f - elapsed * CL.SPIN_DECAY)
        }
        if (heldTurn != 0) { spin = 0f; turn(heldTurn * CL.TURN_RATE * elapsed) }
        if (abs(zoomTarget - zoom) > CL.ZOOM_SNAP || abs(focusTarget - focusY) > CL.ZOOM_SNAP) {
            val share = min(1f, elapsed * CL.ZOOM_SHARE_RATE)
            zoom += (zoomTarget - zoom) * share
            focusY += (focusTarget - focusY) * share
            if (abs(zoomTarget - zoom) <= CL.ZOOM_SNAP) zoom = zoomTarget
        }
    }
}

/**
 * Lua 933-944 makes the model frame larger around the screen point (middle, focusY of the height) so that the character
 * comes nearer there. A camera does the same by narrowing its field of view and moving its look-at so that the world point
 * that stood at [focusY] (from the bottom) of the screen at zoom 1 stays there.
 */
object CreatorCamera {
    const val FOV = 32f
    const val BASE_CENTRE = 1.0f      // z of the middle of the full view
    const val BASE_DISTANCE = 4.6f    // the full view: a figure two high fills the screen's height with room over
    fun pose(zoom: Float, focusY: Float, height: Float = 1f): CameraPose {
        val z = max(1f, zoom)
        val scale = max(0.5f, height)
        val d = BASE_DISTANCE * scale
        val centre = BASE_CENTRE * scale
        val half = tan(Math.toRadians(FOV / 2.0)).toFloat()
        val v = 2f * d * half                         // the height of the world the view takes in
        val point = centre + (focusY - 0.5f) * v      // the world point at focusY of the screen
        val fov = (2.0 * Math.toDegrees(atan((half / z).toDouble()))).toFloat()
        val v2 = 2f * d * tan(Math.toRadians(fov / 2.0)).toFloat()
        val at = point - (focusY - 0.5f) * v2
        return CameraPose(Vec3(d, 0f, at), Vec3(0f, 0f, at), fov)
    }
}
