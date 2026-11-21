package com.logie.wifecraft.ui

import com.logie.wifecraft.creator.Tables
import com.logie.wifecraft.story.CameraPose
import com.logie.wifecraft.story.Vec3
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt
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
    const val RACES_X = 68; const val RACES_Y = 106; const val RACE_PITCH = 79 + 18
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

/**
 * Which side a race stands on. The Lua puts a race on the Alliance's side where its ALLIANCE table (Lua 47-48, by the race's file
 * name, upper case) says so and on the Horde's otherwise; the game's own tables give the faction of the races that table
 * does not know (the allied races of Battle for Azeroth on, a race of ChrRaces.Alliance 0), so a race is the Alliance's where
 * either says so.
 */
private val LUA_ALLIANCE = setOf("HUMAN", "DWARF", "NIGHTELF", "GNOME", "DRAENEI", "WORGEN", "VOIDELF", "LIGHTFORGEDDRAENEI", "HIGHORDER",
    "PANDAREN", "KULTIRAN", "DARKIRONDWARF", "MECHAGNOME")

fun raceToken(key: String): String = key.uppercase().filter { it in 'A'..'Z' }

fun isAllianceSide(key: String, tablesSayAlliance: Boolean): Boolean = raceToken(key) in LUA_ALLIANCE || tablesSayAlliance

// ---- The race step's grid ------------------------------------------------------------------------------------------------------------
//
// The Lua sets a side's races five to a column, a sixth and later in a second column 68 lower (Lua 1484-1487). Wifecraft lists every
// playable and allied race (26 and more), so its grid is worked out from the screen instead: the real width and height (any aspect:
// the activity may be rotated, or folded), the classic races first and the allied and other races after them, in columns that
// start at the same row as the classic ones (one row grid per side), as many rows to a column as the height gives, scaled down
// uniformly (to a touch target of 7 mm and no further) before another column is added, and never past the screen's bottom.

enum class RaceMode { SIDES, ROWS }

/** How many races a side has: the classic ones (Wrath++'s ten) and the allied and others. */
data class RaceCounts(val classic: Int, val allied: Int) {
    val total: Int get() = classic + allied
}

/** One race's place: [index] counts in its side's list (classic first), [tile] is its cell (button, name and the gap round them), [button] the round picture, [name] the label. */
data class RaceCell(val alliance: Boolean, val index: Int, val allied: Boolean, val col: Int, val row: Int, val tile: Rect, val button: Rect, val name: Rect)

data class NamedRect(val name: String, val rect: Rect)

class RaceLayout(
    val mode: RaceMode, val scale: Float, val gapFactor: Float, val width: Float,
    val cells: List<RaceCell>, val blocks: List<NamedRect>, val reserved: List<NamedRect>, val safe: Rect, val character: Rect,
    /** Smallest button the screen's pixels allow (units, 7 mm) and the one drawn. */
    val minButton: Float, val button: Float, val rowPitch: Float, val fits: Boolean,
) {
    fun cell(alliance: Boolean, index: Int): RaceCell? = cells.firstOrNull { it.alliance == alliance && it.index == index }
    fun at(x: Float, y: Float): RaceCell? = cells.firstOrNull { it.tile.contains(x, y) }
}

object RaceGrid {
    const val MIN_TOUCH_MM = 7f
    const val DEFAULT_DPI = 420f
    const val SCALE_STEP = 0.01f
    /** The share of the Lua's gap between neighbours kept, in the order tried (the Lua's own first). */
    val GAP_STEPS = floatArrayOf(1f, 0.6f, 0.3f)
    /** In rows under the title the races may take this much of the free height while the character is to have room, else all of it. */
    const val ROWS_CAP = 0.5f
    const val NAME_ZONE = 18         // the name's 11 below the button and the 7 left before the next (the Lua's 97 - 79)
    const val NAME_DROP = 11; const val NAME_OVERLAP = 7; const val NAME_H = 18
    const val BAND_GAP = 10

    /** 7 mm in the screen's units, [pxPerUnit] pixels to a unit. */
    fun minTouchUnits(pxPerUnit: Float, dpi: Float = DEFAULT_DPI): Float = MIN_TOUCH_MM / 25.4f * dpi / pxPerUnit

    /** The smallest scale: the touch target, but never more than the Lua's own size (the icons are not enlarged). */
    fun minScale(pxPerUnit: Float, dpi: Float = DEFAULT_DPI): Float = max(0.2f, min(1f, minTouchUnits(pxPerUnit, dpi) / CL.u(79)))

    /** The gap from the screen's edge to the first column: the Lua's 59 (68 less half the gap) at full size, less as the icons get smaller. */
    fun margin(scale: Float): Float = CL.u(8) + CL.u(51) * ((scale - 0.5f) / 0.5f).coerceIn(0f, 1f)
}

/** The Wrath++'s ten (the Lua's own and the first on its screen), by file name. */
private val CLASSIC_RACES = setOf("HUMAN", "DWARF", "NIGHTELF", "GNOME", "DRAENEI", "ORC", "SCOURGE", "UNDEAD", "TAUREN", "TROLL", "BLOODELF")

fun isClassicRace(key: String): Boolean = raceToken(key) in CLASSIC_RACES

/** A side's races with the classic ones first, each group in the order given. */
fun <T> raceOrder(items: List<T>, keyOf: (T) -> String): List<T> = items.filter { isClassicRace(keyOf(it)) } + items.filter { !isClassicRace(keyOf(it)) }

fun <T> raceCounts(items: List<T>, keyOf: (T) -> String): RaceCounts { val c = items.count { isClassicRace(keyOf(it)) }; return RaceCounts(c, items.size - c) }

/** The columns (rows in rows mode) a group of [n] takes where [per] fit in one, and how many of them each is filled to (even, not full then short). */
private fun split(n: Int, per: Int): Pair<Int, Int> { if (n <= 0) return 0 to 0; val groups = (n + per - 1) / per; return groups to (n + groups - 1) / groups }

private fun placeSides(width: Float, a: RaceCounts, h: RaceCounts, mode: RaceMode, s: Float, gap: Float, top: Float, bottom: Float, cap: Float, force: Boolean): List<RaceCell>? {
    val w = CL.u(79) * s
    val tileH = w + CL.u(RaceGrid.NAME_ZONE)
    val tileW = w + CL.u(RaceGrid.NAME_ZONE) * gap
    val margin = RaceGrid.margin(s)
    val availH = bottom - top
    val eps = 1e-3f
    val out = ArrayList<RaceCell>()
    fun cellAt(alliance: Boolean, index: Int, allied: Boolean, col: Int, row: Int, y0: Float): RaceCell {
        val x = if (alliance) margin + col * tileW else width - margin - (col + 1) * tileW
        val tile = Rect(x, y0 + row * tileH, tileW, tileH)
        val button = Rect(x + (tileW - w) / 2f, tile.y, w, w)
        val name = Rect(x, button.bottom - CL.u(RaceGrid.NAME_OVERLAP), tileW, CL.u(RaceGrid.NAME_H))
        return RaceCell(alliance, index, allied, col, row, tile, button, name)
    }
    if (mode == RaceMode.SIDES) {
        val rowsFit = floor(availH / tileH + eps).toInt()
        if (rowsFit < 1) return null
        val colsFit = floor((width / 3f - margin) / tileW + eps).toInt()
        val parts = listOf(true to a, false to h).map { (al, n) ->
            val (cc, rc) = split(n.classic, rowsFit); val (ce, re) = split(n.allied, rowsFit); Triple(al, n, listOf(cc, rc, ce, re))
        }
        if (!force && parts.any { it.third[0] + it.third[2] > colsFit }) return null
        val gridRows = parts.maxOf { max(it.third[1], it.third[3]) }
        val y0 = top + (availH - gridRows * tileH) / 2f
        for ((al, n, g) in parts) {
            for (i in 0 until n.classic) out += cellAt(al, i, false, i / g[1], i % g[1], y0)
            for (j in 0 until n.allied) out += cellAt(al, n.classic + j, true, g[0] + j / g[3], j % g[3], y0)
        }
    } else {
        val colsFit = floor((width - 2 * margin) / tileW + eps).toInt()
        if (colsFit < 1) return null
        val bandRows = listOf(a, h).map { n -> (n.classic + colsFit - 1) / colsFit to (n.allied + colsFit - 1) / colsFit }
        val rowsA = bandRows[0].first + bandRows[0].second; val rowsH = bandRows[1].first + bandRows[1].second
        val gapY = if (rowsA > 0 && rowsH > 0) CL.u(RaceGrid.BAND_GAP) else 0f
        if (!force && (rowsA + rowsH) * tileH + gapY > availH * cap + eps) return null
        for ((al, n, rc) in listOf(Triple(true, a, bandRows[0].first), Triple(false, h, bandRows[1].first))) {
            val y0 = top + if (al) 0f else rowsA * tileH + gapY
            for (i in 0 until n.classic) out += cellAt(al, i, false, i % colsFit, i / colsFit, y0)
            for (j in 0 until n.allied) out += cellAt(al, n.classic + j, true, j % colsFit, rc + j / colsFit, y0)
        }
    }
    return out
}

/**
 * The race step's layout for a screen [width] units wide (768 high: [CL.SCREEN_H]), [pxPerUnit] pixels to a unit (the height in
 * pixels over 768), with each side's counts. Tried in this order, the first that fits taking the largest scale from 1 (the Lua's
 * icon size) down to the touch target's minimum:
 *  1. SIDES: the Alliance's races in the outer left third, the Horde's in the outer right, each side's classic races in the
 *     column or columns next to the screen's edge and its allied races in further columns toward the middle, every column
 *     with as many rows as the free height holds, all on one row grid, the block vertically centred and its rows top-aligned;
 *  2. ROWS (a narrow portrait): the two sides in bands of rows under the header, the Alliance's over the Horde's, the allied
 *     rows after the classic ones on the same column grid, taking no more than half the free height;
 *  3. SIDES and then ROWS (all the height) with the gap between neighbours narrowed (to 0.6, then 0.3 of the Lua's).
 * Nothing scrolls. [RaceLayout.fits] is false only where none of these holds the races at the minimum size (then the cells are the
 * last one's, past the bottom).
 */
fun raceLayout(width: Float, pxPerUnit: Float, alliance: RaceCounts, horde: RaceCounts, dpi: Float = RaceGrid.DEFAULT_DPI): RaceLayout {
    val sMin = RaceGrid.minScale(pxPerUnit, dpi)
    val top = CL.u(CL.HEADER_Y + CL.HEADER_H)
    val bottom = CL.SCREEN_H - CL.u(CL.FOOT_Y + CL.NAV_H + 14)
    val scales = ArrayList<Float>()
    var s = 1f
    while (s > sMin + 1e-4f) { scales += s; s -= RaceGrid.SCALE_STEP }
    scales += sMin
    class Try(val mode: RaceMode, val gap: Float, val cap: Float)
    val tries = listOf(Try(RaceMode.SIDES, 1f, 1f), Try(RaceMode.ROWS, 1f, RaceGrid.ROWS_CAP)) +
        RaceGrid.GAP_STEPS.drop(1).map { Try(RaceMode.SIDES, it, 1f) } + RaceGrid.GAP_STEPS.map { Try(RaceMode.ROWS, it, 1f) }
    var cells: List<RaceCell>? = null; var chosen: Triple<Try, Float, Boolean>? = null
    search@ for (t in tries) for (sc in scales) {
        val c = placeSides(width, alliance, horde, t.mode, sc, t.gap, top, bottom, t.cap, false)
        if (c != null) { cells = c; chosen = Triple(t, sc, true); break@search }
    }
    if (cells == null) {
        val last = tries.last()
        cells = placeSides(width, alliance, horde, last.mode, sMin, last.gap, top, bottom, 1f, true) ?: emptyList()
        chosen = Triple(last, sMin, false)
    }
    val (t, sc, fits) = chosen!!
    val blocks = listOf(true, false).mapNotNull { al ->
        val mine = cells.filter { it.alliance == al }
        if (mine.isEmpty()) null else NamedRect(if (al) "alliance" else "horde", Rect(mine.minOf { it.tile.x }, mine.minOf { it.tile.y }, mine.maxOf { it.tile.right } - mine.minOf { it.tile.x }, mine.maxOf { it.tile.bottom } - mine.minOf { it.tile.y }))
    }
    val sexL = sexRect(0, width); val sexR = sexRect(1, width)
    val reserved = listOf(
        NamedRect("header", Rect(0f, 0f, width, top)), NamedRect("foot", Rect(0f, bottom, width, CL.SCREEN_H - bottom)),
        NamedRect("sexMale", sexL), NamedRect("sexFemale", sexR), NamedRect("back", navRect(false, width)), NamedRect("customize", navRect(true, width)),
    )
    val character = if (t.mode == RaceMode.SIDES) Rect(width / 3f, top, width / 3f, bottom - top) else {
        val low = max(top, blocks.maxOfOrNull { it.rect.bottom } ?: top)
        Rect(0f, min(low, bottom), width, max(0f, bottom - low))
    }
    val w = CL.u(79) * sc
    return RaceLayout(t.mode, sc, t.gap, width, cells, blocks, reserved, Rect(0f, top, width, bottom - top), character,
        RaceGrid.minTouchUnits(pxPerUnit, dpi), w, w + CL.u(RaceGrid.NAME_ZONE), fits)
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

private val RACE_ATLAS_IDS = mapOf(
    1 to "human", 2 to "orc", 3 to "dwarf", 4 to "nightelf", 5 to "undead", 6 to "tauren",
    7 to "gnome", 8 to "troll", 9 to "goblin", 10 to "bloodelf", 11 to "draenei", 22 to "worgen",
    24 to "pandaren", 25 to "pandaren", 26 to "pandaren", 27 to "nightborne", 28 to "highmountain",
    29 to "voidelf", 30 to "lightforged", 31 to "zandalari", 32 to "kultiran", 34 to "darkirondwarf",
    35 to "vulpera", 36 to "magharorc", 37 to "mechagnome",
)

/** Lua 1488-1490: the race's piece "raceicon128-<art>-<sex>"; [has] says which pieces the art has; null where none. */
fun racePiece(key: String, female: Boolean, raceId: Int = 0, has: (String) -> Boolean): String? {
    val n = key.lowercase().filter { it in 'a'..'z' }
    val sex = if (female) "female" else "male"
    val candidates = listOfNotNull(RACE_ATLAS_IDS[raceId], RACE_ART[n], n, n.removeSuffix("race"), n.removePrefix("the"))
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

/**
 * The Create-a-Character shell's camera (ui/CasScreen.kt): [CreatorCamera]'s, moved sideways so that the character stands at
 * [centreX] (a share of the view's width from its left: the middle of the space between the tab rail and the panel) of a view
 * [aspect] wide over high. The engine's camera looks along -x with +y to the screen's right, so the camera moves the other way.
 */
object CasCameraLayout {
    fun pose(zoom: Float, focusY: Float, centreX: Float, aspect: Float, height: Float = 1f): CameraPose {
        val p = CreatorCamera.pose(zoom, focusY, height)
        val d = p.eye.x - p.at.x
        val visibleW = 2f * d * tan(Math.toRadians(p.fov / 2.0)).toFloat() * max(aspect, 0.1f)
        val shift = -(centreX.coerceIn(0f, 1f) - 0.5f) * visibleW
        return CameraPose(Vec3(p.eye.x, shift, p.eye.z), Vec3(p.at.x, shift, p.at.z), p.fov)
    }

    /** Where on the screen (a share of its width) an object at the camera's look-at height and y = 0 is drawn by [p]. */
    fun screenX(p: CameraPose, aspect: Float): Float {
        val d = p.eye.x - p.at.x
        val visibleW = 2f * d * tan(Math.toRadians(p.fov / 2.0)).toFloat() * max(aspect, 0.1f)
        return 0.5f + (0f - p.eye.y) / visibleW
    }
}

/**
 * Where the shell's parts go on a [w] x [h] dp screen (landscape: the rail down the left, the panel on the right, the stage
 * between; portrait: the stage on top, the rail across under it, the panel below). [outfit]: the stage takes the panel's
 * place too (the creator passes false since the Outfit tab is a panel like the others). Pure (no layout jumps: every box is fixed by the screen's size alone).
 */
data class CasBox(val x: Float, val y: Float, val w: Float, val h: Float)

data class CasLayout(val top: CasBox, val rail: CasBox, val stage: CasBox, val panel: CasBox, val cameras: CasBox) {
    companion object {
        const val TOP = 52f
        const val RAIL_W = 76f
        const val RAIL_H = 66f
        const val GAP = 6f

        fun of(w: Float, h: Float, outfit: Boolean): CasLayout = if (w >= h) {
            val panelW = (w * 0.36f).coerceIn(300f, 460f)
            val rail = CasBox(GAP, GAP, RAIL_W, h - 2 * GAP)
            val top = CasBox(RAIL_W + 2 * GAP, GAP, w - RAIL_W - 3 * GAP, TOP)
            val panel = CasBox(w - panelW - GAP, TOP + 2 * GAP, panelW, h - TOP - 3 * GAP)
            val stageRight = if (outfit) w - GAP else panel.x - GAP
            val stage = CasBox(RAIL_W + 2 * GAP, TOP + 2 * GAP, stageRight - RAIL_W - 2 * GAP, h - TOP - 3 * GAP)
            val camW = min(6 * 56f, stage.w)
            CasLayout(top, rail, stage, panel, CasBox(stage.x + (stage.w - camW) / 2f, h - 64f - GAP, camW, 64f))
        } else {
            val top = CasBox(GAP, GAP, w - 2 * GAP, TOP)
            val stageH = ((h - TOP) * 0.46f).coerceAtLeast(260f)
            val stage = CasBox(GAP, TOP + 2 * GAP, w - 2 * GAP, if (outfit) h - TOP - RAIL_H - 4 * GAP else stageH)
            val rail = CasBox(GAP, stage.y + stage.h + GAP, w - 2 * GAP, RAIL_H)
            val panel = CasBox(GAP, rail.y + RAIL_H + GAP, w - 2 * GAP, h - rail.y - RAIL_H - 2 * GAP)
            CasLayout(top, rail, stage, panel, CasBox(w - 58f - GAP, stage.y + 8f, 58f, min(6 * 62f, stage.h - 16f)))
        }
    }
}

// ---- Words ----------------------------------------------------------------------------------------------------------------------------------

/**
 * The creator's words, from where Wrath++ takes them. Its Lua asks the client's global strings for the faction names, Back and
 * Name (FACTION_ALLIANCE, FACTION_HORDE, BACK, NAME: Lua 820, 866, 900, 904) and has a literal for each where the client has none;
 * "Customize", "Finish" and the four views are its own literals and stay exactly as the Lua has them (Lua 898, 906, 1025-1028).
 * Here the strings are the game's GlobalStrings table (BaseTag, TagText_lang) when the host has fetched it, else the Lua's literals.
 */
class CreatorStrings(tables: Tables?) {
    private val found: Map<String, String> = run {
        val out = HashMap<String, String>()
        if (tables != null) for (r in tables.rows("GlobalStrings")) {
            val tag = r["BaseTag"] ?: continue
            if (tag in TAGS) { val text = r["TagText_lang"].orEmpty(); if (text.isNotEmpty()) out.putIfAbsent(tag, text) }
            if (out.size == TAGS.size) break
        }
        out
    }
    private fun of(tag: String, literal: String) = found[tag] ?: literal
    val alliance get() = of("FACTION_ALLIANCE", LUA_ALLIANCE_NAME)
    val horde get() = of("FACTION_HORDE", LUA_HORDE_NAME)
    val back get() = of("BACK", LUA_BACK)
    val name get() = of("NAME", LUA_NAME)
    val customize get() = LUA_CUSTOMIZE
    val finish get() = LUA_FINISH

    companion object {
        private val TAGS = setOf("FACTION_ALLIANCE", "FACTION_HORDE", "BACK", "NAME")
        // WrathppCreate.lua's own literals
        const val LUA_ALLIANCE_NAME = "Alliance"; const val LUA_HORDE_NAME = "Horde"; const val LUA_BACK = "Back"; const val LUA_NAME = "Name"
        const val LUA_CUSTOMIZE = "Customize"; const val LUA_FINISH = "Finish"
    }
}

// ---- The woman's Body tab picture (body_icon.py) -----------------------------------------------------------------------------------------

/**
 * Wrath++'s "charactercreate-icon-customize-figure": the Body tab of a woman, which Shadowlands has no picture for. Ported from
 * realm/warcraft/wrathpp/tools/body_icon.py (a woman's torso in the relief of the category pictures, lit from the top left, gold
 * with a glow where chosen, grey where not, on the Face picture's own ring and disc with its head painted out), so that the phone
 * makes it from the Face piece it has cut from the game's sheet: nothing of it is a file. Differences from the Python, all
 * invisible at 104 x 105: the faint facets' random numbers are java.util.Random's (the shapes and places are the Python's only
 * in kind), the figure is made smaller by area averaging where Pillow uses Lanczos, and Pillow's blur is a true Gaussian here.
 * Colours are ARGB ints, not premultiplied, as Android's Bitmap.getPixels gives them.
 */
object BodyIcon {
    private fun smooth(x: Double): Double { val c = x.coerceIn(0.0, 1.0); return c * c * (3 - 2 * c) }
    private fun clip01(x: Double) = x.coerceIn(0.0, 1.0)

    private val KS = doubleArrayOf(0.00, 0.06, 0.13, 0.20, 0.30, 0.40, 0.50, 0.58, 0.68, 0.78, 0.88, 1.00)
    private val WS = doubleArrayOf(0.20, 0.24, 0.60, 0.66, 0.60, 0.53, 0.44, 0.42, 0.52, 0.62, 0.60, 0.50)

    private fun interp(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        if (x <= xs[0]) return ys[0]
        for (i in 1 until xs.size) if (x <= xs[i]) { val t = (x - xs[i - 1]) / (xs[i] - xs[i - 1]); return ys[i - 1] + t * (ys[i] - ys[i - 1]) }
        return ys.last()
    }

    /** A Gaussian blur of a [w] x [h] plane, edges extended, [sigma] in pixels. */
    fun blur(a: DoubleArray, w: Int, h: Int, sigma: Double): DoubleArray {
        if (sigma <= 0.0) return a.copyOf()
        val r = max(1, ceil(sigma * 3).toInt())
        val k = DoubleArray(2 * r + 1) { kotlin.math.exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        val sum = k.sum(); for (i in k.indices) k[i] /= sum
        val tmp = DoubleArray(a.size); val out = DoubleArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var v = 0.0
            for (i in -r..r) v += k[i + r] * a[y * w + (x + i).coerceIn(0, w - 1)]
            tmp[y * w + x] = v
        }
        for (y in 0 until h) for (x in 0 until w) {
            var v = 0.0
            for (i in -r..r) v += k[i + r] * tmp[(y + i).coerceIn(0, h - 1) * w + x]
            out[y * w + x] = v
        }
        return out
    }

    /** torso_height: the figure's height above the disc and its soft outline, [n] x [n] (row major). */
    fun torso(n: Int): Pair<DoubleArray, DoubleArray> {
        // the half-width down the figure, eased (Pillow's 8-bit blur of the profile, n/40)
        val prof = DoubleArray(n) { floor(interp(it / (n - 1.0), KS, WS) * 255) }
        val half = blur(prof, 1, n, n / 40.0).map { round(it) / 255.0 }
        val height = DoubleArray(n * n); val edge = DoubleArray(n * n)
        for (i in 0 until n) {
            val v = i / (n - 1.0)
            for (j in 0 until n) {
                val u = (j / (n - 1.0) - 0.5) * 2
                val hw = half[i]
                var inside = hw - abs(u)
                inside -= smooth((v - 0.84) / 0.08) * smooth((0.06 - abs(u)) / 0.04) * 0.2
                val e = smooth(inside / 0.06) * smooth(v / 0.03) * smooth((1 - v) / 0.02)
                var h = sqrt(clip01(inside / max(hw, 1e-3))) * 0.55
                for (s in intArrayOf(-1, 1)) {
                    val d = sqrt(((u - 0.30 * s) / 0.27).pow(2) + ((v - 0.33) / 0.13).pow(2))
                    h += 0.62 * clip01(1 - d).pow(1.3)
                }
                h += 0.18 * clip01(1 - sqrt((u / 0.36).pow(2) + ((v - 0.66) / 0.16).pow(2))).pow(1.5)
                for (s in intArrayOf(-1, 1)) {
                    val d = sqrt(((u - 0.42 * s) / 0.25).pow(2) + ((v - 0.80) / 0.14).pow(2))
                    h += 0.22 * clip01(1 - d).pow(1.4)
                }
                h -= 0.10 * clip01(1 - sqrt((u / 0.035).pow(2) + ((v - 0.62) / 0.03).pow(2)))
                h -= 0.05 * clip01(1 - sqrt((u / 0.04).pow(2) + ((v - 0.47) / 0.10).pow(2)))
                height[i * n + j] = h * e; edge[i * n + j] = e
            }
        }
        return height to edge
    }

    private val GOLD_T = doubleArrayOf(0.0, 0.35, 0.7, 0.92, 1.0)
    private val GOLD = arrayOf(doubleArrayOf(70.0, 45.0, 0.0), doubleArrayOf(170.0, 120.0, 0.0), doubleArrayOf(226.0, 186.0, 14.0), doubleArrayOf(255.0, 236.0, 90.0), doubleArrayOf(255.0, 250.0, 170.0))
    private val GREY = arrayOf(doubleArrayOf(40.0, 40.0, 42.0), doubleArrayOf(98.0, 97.0, 96.0), doubleArrayOf(150.0, 148.0, 146.0), doubleArrayOf(205.0, 204.0, 202.0), doubleArrayOf(245.0, 245.0, 245.0))

    /** shade: lit relief colours (r, g, b interleaved, 0..255) of a height field, the rim darker. */
    fun shade(h: DoubleArray, edge: DoubleArray, n: Int, selected: Boolean): DoubleArray {
        val ramp = if (selected) GOLD else GREY
        val g = DoubleArray(n * n) { h[it] * n * 0.06 }
        fun at(i: Int, j: Int) = g[i.coerceIn(0, n - 1) * n + j.coerceIn(0, n - 1)]
        // numpy.gradient: central differences inside, one-sided at the ends
        fun dRow(i: Int, j: Int) = if (i == 0) at(1, j) - at(0, j) else if (i == n - 1) at(n - 1, j) - at(n - 2, j) else (at(i + 1, j) - at(i - 1, j)) / 2
        fun dCol(i: Int, j: Int) = if (j == 0) at(i, 1) - at(i, 0) else if (j == n - 1) at(i, n - 1) - at(i, n - 2) else (at(i, j + 1) - at(i, j - 1)) / 2
        val ll = sqrt(0.55 * 0.55 + 0.65 * 0.65 + 0.52 * 0.52)
        val lx = -0.55 / ll; val ly = -0.65 / ll; val lz = 0.52 / ll
        val hn = sqrt(lx * lx + ly * ly + (lz + 1) * (lz + 1)); val hx = lx / hn; val hy = ly / hn; val hz = (lz + 1) / hn
        // the faint broad facets of the stone
        val rng = java.util.Random(7)
        val px = DoubleArray(26); val py = DoubleArray(26)
        for (k in 0 until 26) { px[k] = rng.nextDouble() * n; py[k] = rng.nextDouble() * n }
        val shift = DoubleArray(26) { (rng.nextDouble() - 0.5) * 0.06 }
        val out = DoubleArray(n * n * 3)
        for (i in 0 until n) for (j in 0 until n) {
            val gy = dRow(i, j); val gx = dCol(i, j)
            val nl = sqrt(gx * gx + gy * gy + 1.0)
            val nx = -gx / nl; val ny = -gy / nl; val nz = 1 / nl
            val lit = clip01(nx * lx + ny * ly + nz * lz)
            val spec = clip01(nx * hx + ny * hy + nz * hz).pow(24)
            var cell = 0; var best = Double.MAX_VALUE
            for (k in 0 until 26) { val d = (i - py[k]).pow(2) + (j - px[k]).pow(2); if (d < best) { best = d; cell = k } }
            val t = clip01(clip01(0.18 + 0.82 * lit) + shift[cell])
            val rim = smooth((edge[i * n + j] - 0.02) / 0.5).pow(0.6)
            for (c in 0 until 3) {
                val v = (interp(t, GOLD_T, DoubleArray(5) { ramp[it][c] }) + spec * 60).coerceIn(0.0, 255.0)
                out[(i * n + j) * 3 + c] = v * (0.45 + 0.55 * rim)
            }
        }
        return out
    }

    /** disc_only: the piece's ring and disc with its head painted out, in ARGB. */
    fun discOnly(piece: IntArray, pw: Int, ph: Int): IntArray {
        val n = if (max(ph, pw) < 200) 256.0 * max(ph, pw) / 158.0 else max(ph, pw).toDouble()
        val inner = 0.118 * n; val outer = 0.148 * n
        val cx = (pw - 1) / 2.0; val cy = (ph - 1) / 2.0
        val rr = DoubleArray(pw * ph) { kotlin.math.hypot((it / pw) - cy, (it % pw) - cx) }
        val prof = ArrayList<DoubleArray>()
        for (r0 in 0..(outer.toInt() + 1)) {
            val centre = if (r0 >= inner) r0.toDouble() else inner
            val band = ArrayList<Int>()
            for (p in rr.indices) if (abs(rr[p] - centre) < 1.0) band += p
            if (band.isEmpty()) { prof += doubleArrayOf(0.0, 0.0, 0.0); continue }
            val lum = band.map { p -> val c = piece[p]; ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3.0 }
            val sorted = lum.sorted()
            // numpy.percentile(lum, 60), linear
            val pos = 0.6 * (sorted.size - 1); val lo = floor(pos).toInt(); val hi = min(lo + 1, sorted.size - 1)
            val cut = sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
            var cnt = 0; val acc = DoubleArray(3)
            for ((q, p) in band.withIndex()) if (lum[q] <= cut) { val c = piece[p]; acc[0] += c shr 16 and 255; acc[1] += c shr 8 and 255; acc[2] += c and 255; cnt++ }
            prof += DoubleArray(3) { if (cnt > 0) acc[it] / cnt else 0.0 }
        }
        val out = IntArray(piece.size)
        for (p in piece.indices) {
            val m = prof[round(rr[p]).toInt().coerceIn(0, prof.size - 1)]
            val w = smooth((outer - rr[p]) / (0.02 * n))
            val c = piece[p]
            fun mix(k: Int, own: Int) = (m[k] * w + own * (1 - w)).toInt().coerceIn(0, 255)
            out[p] = (c ushr 24 shl 24) or (mix(0, c shr 16 and 255) shl 16) or (mix(1, c shr 8 and 255) shl 8) or mix(2, c and 255)
        }
        return out
    }

    /** Area average of a [sw] x [sh] plane to [dw] x [dh]. */
    private fun resize(a: DoubleArray, sw: Int, sh: Int, dw: Int, dh: Int): DoubleArray {
        val out = DoubleArray(dw * dh)
        for (y in 0 until dh) for (x in 0 until dw) {
            val x0 = x * sw.toDouble() / dw; val x1 = (x + 1) * sw.toDouble() / dw
            val y0 = y * sh.toDouble() / dh; val y1 = (y + 1) * sh.toDouble() / dh
            var acc = 0.0; var wsum = 0.0
            var sy = floor(y0).toInt()
            while (sy < y1 && sy < sh) {
                val wy = min(y1, sy + 1.0) - max(y0, sy.toDouble())
                var sx = floor(x0).toInt()
                while (sx < x1 && sx < sw) {
                    val wx = min(x1, sx + 1.0) - max(x0, sx.toDouble())
                    acc += a[sy * sw + sx] * wx * wy; wsum += wx * wy
                    sx++
                }
                sy++
            }
            out[y * dw + x] = if (wsum > 0) acc / wsum else 0.0
        }
        return out
    }

    /**
     * figure_piece: [head] is the Face picture's pixels (ARGB, [pw] x [ph]); the Body picture, chosen (gold, glowing) or not
     * (grey), the same size.
     */
    fun figure(head: IntArray, pw: Int, ph: Int, selected: Boolean, sizeFrac: Double = 0.255): IntArray {
        val n = if (max(ph, pw) < 200) 256.0 * max(ph, pw) / 158.0 else max(ph, pw).toDouble()
        val base = discOnly(head, pw, ph)
        val m = round(n * sizeFrac).toInt()
        val big = m * 2
        val (h, edge) = torso(big)
        val rgb = shade(h, edge, big, selected)
        val fw = m; val fh = (m * 1.18).toInt()
        // premultiplied colours made smaller, then straight again
        val chan = Array(3) { c -> resize(DoubleArray(big * big) { rgb[it * 3 + c] * edge[it] }, big, big, fw, fh) }
        val alpha = resize(edge, big, big, fw, fh)
        val x0 = Math.floorDiv(pw - fw, 2); val y0 = Math.floorDiv(ph - fh, 2) + (n * 0.005).toInt()
        // working canvas: straight RGB (0..255) and alpha (0..1)
        val r = DoubleArray(pw * ph); val g = DoubleArray(pw * ph); val b = DoubleArray(pw * ph); val a = DoubleArray(pw * ph)
        for (p in base.indices) { val c = base[p]; a[p] = (c ushr 24) / 255.0; r[p] = (c shr 16 and 255).toDouble(); g[p] = (c shr 8 and 255).toDouble(); b[p] = (c and 255).toDouble() }
        fun over(p: Int, sr: Double, sg: Double, sb: Double, sa: Double) {
            if (sa <= 0.0) return
            val oa = sa + a[p] * (1 - sa)
            r[p] = (sr * sa + r[p] * a[p] * (1 - sa)) / oa; g[p] = (sg * sa + g[p] * a[p] * (1 - sa)) / oa; b[p] = (sb * sa + b[p] * a[p] * (1 - sa)) / oa
            a[p] = oa
        }
        fun placed(dx: Int, dy: Int): DoubleArray {
            val plane = DoubleArray(pw * ph)
            for (y in 0 until fh) for (x in 0 until fw) {
                val px = x0 + dx + x; val py = y0 + dy + y
                if (px in 0 until pw && py in 0 until ph) plane[py * pw + px] = alpha[y * fw + x]
            }
            return plane
        }
        if (selected) {                                   // the glow round the figure
            val glow = blur(placed(0, 0), pw, ph, n / 40.0)
            for (p in glow.indices) over(p, 255.0, 210.0, 40.0, floor(glow[p] * 255) * 0.8 / 255.0)
        }
        val shadow = blur(placed(2, 2), pw, ph, 3.0)       // and a shadow, two down and right
        for (p in shadow.indices) over(p, 0.0, 0.0, 0.0, floor(shadow[p] * 255) * 0.6 / 255.0)
        for (y in 0 until fh) for (x in 0 until fw) {
            val px = x0 + x; val py = y0 + y
            if (px !in 0 until pw || py !in 0 until ph) continue
            val al = alpha[y * fw + x]
            if (al <= 0.0) continue
            over(py * pw + px, chan[0][y * fw + x] / al, chan[1][y * fw + x] / al, chan[2][y * fw + x] / al, al)
        }
        return IntArray(pw * ph) { p ->
            val al = (a[p] * 255 + 0.5).toInt().coerceIn(0, 255)
            (al shl 24) or (r[p].toInt().coerceIn(0, 255) shl 16) or (g[p].toInt().coerceIn(0, 255) shl 8) or b[p].toInt().coerceIn(0, 255)
        }
    }
}
