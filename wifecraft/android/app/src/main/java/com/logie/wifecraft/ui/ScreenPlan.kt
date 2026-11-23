package com.logie.wifecraft.ui

import kotlin.math.max
import kotlin.math.min

// Pure Kotlin, no android.* and no Compose (JVM-tested: unit-tests/.../ui/ScreenPlanTest): where everything drawn over the live
// scene goes. One top bar, one dock, one sheet, one toast line and the cast and camera rails, placed from one set of numbers
// (dp) so no composable computes a rectangle of its own and the tests can check what the screen shows. SceneChrome draws the
// result. The rules (slots, metrics, which panel may be open, what the dock holds in each mode) are in the README's "Scene
// screen layout".

/** A rectangle in dp, screen coordinates (left, top, right, bottom). */
data class ScreenRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w: Float get() = r - l
    val h: Float get() = b - t
    fun intersects(o: ScreenRect): Boolean = l < o.r && o.l < r && t < o.b && o.t < b
    fun inflate(d: Float): ScreenRect = ScreenRect(l - d, t - d, r + d, b + d)
    fun inside(o: ScreenRect): Boolean = l >= o.l - 0.01f && r <= o.r + 0.01f && t >= o.t - 0.01f && b <= o.b + 0.01f
}

/**
 * What the screen keeps free: the system's insets in dp ([l], [t], [r], [b]: the cut-out and the bars), the display cut-outs'
 * rectangles ([cutouts], screen coordinates), the keyboard's height ([ime]) and how far from each edge the system's gesture
 * zones reach ([gestureL], [gestureR]; [gestureB] the home gesture's).
 */
data class ScreenInsets(
    val l: Float = 0f, val t: Float = 0f, val r: Float = 0f, val b: Float = 0f,
    val cutouts: List<ScreenRect> = emptyList(), val ime: Float = 0f,
    val gestureL: Float = 24f, val gestureR: Float = 24f, val gestureB: Float = 16f,
)

/** Where a placed thing is. */
enum class ScreenSlot { TOP_START, TOP_END, CAST, CAMERA, DOCK, DOCK_ROW, DOCK_CONTEXT, SHEET, TOAST }

/** The one mode the scene is in: Build (Move is its sub-mode), JOI, Chat, or the cinematic. Exactly one at a time. */
enum class ScreenMode { NORMAL, BUILD, MOVE, JOI, CHAT, CINEMATIC }

/**
 * The one panel that may be open over the scene. [sheet] ones are drawn in the sheet slot (beside or above the scene, never over
 * its middle); [modal] ones are windows over everything, which close every sheet when they open.
 */
enum class ScreenPanel(val sheet: Boolean, val modal: Boolean) {
    NONE(false, false),
    TIME(true, false), MORE(true, false), ANIMATIONS(true, false), STEER(true, false), AUTOPLAY(true, false),
    BUILD_PANEL(true, false), MOVE_PANEL(true, false), CHAT_LOG(true, false),
    MENU(false, true), LIMITS(false, true), OPTIONS(false, true), LIFE(false, true), RADIAL(false, true), PICK(false, true), CONFIRM(false, true),
}

/** The sheet's side: at the right in landscape, above the dock in portrait. */
enum class SheetKind { SIDE, BOTTOM }

/** What the screen shows, for the plan. */
data class ScreenState(
    val mode: ScreenMode = ScreenMode.NORMAL,
    val panel: ScreenPanel = ScreenPanel.NONE,
    /** Dock targets for the mode now ([DockPlan.size]); 0: no dock. */
    val dockItems: Int = 0,
    /** The dock's smallest total width in dp ([DockPlan.minWidth]); a dock that needs more scrolls. */
    val dockWidth: Float = 0f,
    val castCount: Int = 0,
    val orbit: Boolean = false,
    val toast: Boolean = false,
    val context: Boolean = false,
    val ime: Boolean = false,
    /** A row over the dock is open ([ScreenSlot.DOCK_ROW]: Chat's chips, JOI's level picker or steer field). */
    val row: Boolean = false,
)

/** One placed thing: [element] its id, [slot], [rect] in screen dp, [interactive] it takes touches, [draggable] it scrolls or repeats. */
data class ScreenPlaced(val element: String, val slot: ScreenSlot, val rect: ScreenRect, val interactive: Boolean = true,
                        val draggable: Boolean = false)

object ScreenPlan {
    /** Distance from the safe area's edge (and the gap between neighbours). */
    const val M = 16f
    const val GAP = 8f
    const val TOUCH = 48f
    const val TOP_PAD = 8f
    const val BAR_H = 48f
    const val CAST_H = 56f
    const val CAST_W = 156f
    const val CONTEXT_H = 28f
    const val TOAST_H = 40f
    const val TOAST_W = 480f
    const val CAMERA_W = 100f
    const val KEYS_H = 100f
    const val MODE_W = 104f
    const val PLACE_FULL = 240f
    const val PLACE_CLOCK = 150f
    const val PLACE_COMPACT = 96f
    const val DOCK_MAX_W = 780f
    const val SHEET_MIN_H = 96f
    const val SIDE_MIN = 320f
    const val SIDE_MAX = 400f
    /** A control that repeats or scrolls keeps this far from a screen edge past the gesture zone. */
    const val GESTURE_PAD = 8f

    fun wide(w: Float, h: Float): Boolean = w > h && w >= 600f
    fun sheetFor(w: Float, h: Float): SheetKind = if (wide(w, h)) SheetKind.SIDE else SheetKind.BOTTOM

    /** The scene's middle, which no chrome may cover (the characters stand there; the Build gizmo too). */
    fun keepOut(w: Float, h: Float): ScreenRect =
        if (wide(w, h)) ScreenRect(0.30f * w, 0.30f * h, 0.55f * w, 0.70f * h) else ScreenRect(0.33f * w, 0.30f * h, 0.62f * w, 0.50f * h)

    /**
     * The middle for [mode]: in Chat and JOI her face is the picture and the camera frames her above the bottom stack (the dock, its row
     * and the line over them), so the free zone is the upper part of the screen; the other modes keep [keepOut]'s.
     */
    fun keepOut(w: Float, h: Float, mode: ScreenMode): ScreenRect =
        if (mode != ScreenMode.CHAT && mode != ScreenMode.JOI) keepOut(w, h)
        else if (wide(w, h)) ScreenRect(0.30f * w, 0.30f * h, 0.55f * w, 0.50f * h) else ScreenRect(0.33f * w, 0.30f * h, 0.62f * w, 0.45f * h)

    /** How much of the screen's height the bottom stack (dock, row, line) covers, 0..1, from [plan]: the chat camera frames her above it. */
    fun bottomShare(plan: List<ScreenPlaced>, h: Float): Float {
        val top = plan.filter { it.slot == ScreenSlot.DOCK || it.slot == ScreenSlot.DOCK_ROW || it.slot == ScreenSlot.DOCK_CONTEXT }.minOfOrNull { it.rect.t } ?: return 0f
        return ((h - top) / h).coerceIn(0f, 1f)
    }

    /** Whether [mode] shows the cast and the camera's buttons. */
    fun showsCast(mode: ScreenMode): Boolean = mode != ScreenMode.JOI && mode != ScreenMode.CHAT
    fun showsPlace(mode: ScreenMode): Boolean = mode != ScreenMode.JOI && mode != ScreenMode.CHAT

    private class Bar(val a: Float, val b: Float)

    /** The top bar's free runs: [left, right] less every cut-out that reaches into the bar's band. */
    private fun runs(left: Float, right: Float, top: Float, bottom: Float, cutouts: List<ScreenRect>): List<Bar> {
        val band = ScreenRect(left, top, right, bottom)
        var out = listOf(Bar(left, right))
        for (c in cutouts.filter { it.inflate(GAP).intersects(band) }.sortedBy { it.l }) {
            val g = c.inflate(GAP)
            out = out.flatMap { run ->
                if (g.r <= run.a || g.l >= run.b) listOf(run)
                else listOfNotNull(Bar(run.a, min(run.b, g.l)).takeIf { it.b - it.a >= TOUCH }, Bar(max(run.a, g.r), run.b).takeIf { it.b - it.a >= TOUCH })
            }
        }
        return out
    }

    /** Everything on the screen [w] x [h] dp, inside the insets, none over another. */
    fun plan(w: Float, h: Float, ins: ScreenInsets, s: ScreenState): List<ScreenPlaced> {
        val out = ArrayList<ScreenPlaced>()
        val wide = wide(w, h)
        val left = ins.l + M
        val right = w - ins.r - M
        val dragL = max(left, ins.gestureL + GESTURE_PAD)
        val dragR = min(right, w - ins.gestureR - GESTURE_PAD)
        val top = ins.t + TOP_PAD
        val barB = top + BAR_H
        val bottomEdge = h - max(ins.b, ins.ime)
        val typing = s.ime || ins.ime > 0f

        // ---- the top bar: a cluster at each end, round the cut-out
        val runs = runs(left, right, top, barB, ins.cutouts)
        val first = runs.firstOrNull()
        val last = runs.lastOrNull()
        var endL = last?.b ?: right
        if (first != null && last != null && s.mode == ScreenMode.NORMAL) {
            val cw = if (wide) 96f else TOUCH
            if (last.b - cw >= (if (first === last) first.a + TOUCH + GAP else last.a)) {
                out += ScreenPlaced("cinematic", ScreenSlot.TOP_END, ScreenRect(last.b - cw, top, last.b, barB))
                endL = last.b - cw - GAP
            }
        }
        if (first != null) {
            out += ScreenPlaced("menu", ScreenSlot.TOP_START, ScreenRect(first.a, top, first.a + TOUCH, barB))
            var x = first.a + TOUCH + GAP
            if (s.mode != ScreenMode.NORMAL && x + MODE_W <= (if (first === last) endL else first.b)) {
                out += ScreenPlaced("mode", ScreenSlot.TOP_START, ScreenRect(x, top, x + MODE_W, barB))
                x += MODE_W + GAP
            }
            if (showsPlace(s.mode)) {
                val limit = if (first === last) endL else first.b
                val avail = limit - x
                if (avail >= PLACE_FULL) out += ScreenPlaced("place", ScreenSlot.TOP_START, ScreenRect(x, top, x + min(avail, 260f), barB))
                else if (avail >= PLACE_CLOCK) out += ScreenPlaced("place.clock", ScreenSlot.TOP_START, ScreenRect(x, top, x + min(avail, 170f), barB))
                else if (avail >= PLACE_COMPACT) out += ScreenPlaced("place.compact", ScreenSlot.TOP_START, ScreenRect(x, top, x + min(avail, 132f), barB))
            }
        }
        var below = barB

        // ---- the bottom: the dock, a row over it (Chat's chips, JOI's level or steer) and the line over them
        var floor = bottomEdge - GAP     // the lowest the sheet and rails may reach
        val dockOn = s.dockItems > 0 && (!typing || s.mode == ScreenMode.CHAT)
        val dockW = min(DOCK_MAX_W, right - left)
        val dl = if (wide) left + (right - left - dockW) / 2f else left
        var stackT = bottomEdge - (if (typing) GAP else M)     // the top of what is placed so far (or the bottom of what is to come)
        if (dockOn) {
            val dockT = stackT - TOUCH
            out += ScreenPlaced("dock", ScreenSlot.DOCK, ScreenRect(dl, dockT, dl + dockW, stackT))
            stackT = dockT
            floor = dockT - GAP
        }
        // the row: over the dock; while typing it is kept in portrait and for the field that is typed in (JOI's steer takes the dock's place);
        // a landscape screen with the keyboard up has no room for Chat's chips
        if (s.row && (!typing || !wide || s.mode != ScreenMode.CHAT)) {
            val rowB = if (dockOn) stackT - GAP else stackT
            out += ScreenPlaced("row", ScreenSlot.DOCK_ROW, ScreenRect(dl, rowB - TOUCH, dl + dockW, rowB))
            stackT = rowB - TOUCH
            floor = stackT - GAP
        }
        if (s.context && !typing) {
            val ctxB = if (stackT < bottomEdge - M - 0.5f) stackT - GAP / 2f else bottomEdge - M
            out += ScreenPlaced("context", ScreenSlot.DOCK_CONTEXT, ScreenRect(dl, ctxB - CONTEXT_H, dl + dockW, ctxB), interactive = false)
            floor = ctxB - CONTEXT_H - GAP
        }

        // ---- the cast: a strip under the bar (portrait) or a rail down the left (landscape)
        var castR = left
        if (s.castCount > 0 && showsCast(s.mode)) {
            if (wide) {
                val ct = barB + GAP
                if (floor - ct >= CAST_H) {
                    out += ScreenPlaced("cast", ScreenSlot.CAST, ScreenRect(dragL, ct, dragL + CAST_W, floor), draggable = true)
                    castR = dragL + CAST_W
                }
            } else {
                val ct = barB + GAP
                out += ScreenPlaced("cast", ScreenSlot.CAST, ScreenRect(dragL, ct, dragR, ct + CAST_H), draggable = true)
                below = ct + CAST_H
            }
        }

        // ---- the sheet: at the right (landscape) or above the dock (portrait); dropped where it would not fit
        var sheetL = right
        val sheetOpen = s.panel.sheet
        if (sheetOpen) {
            if (wide) {
                val sw = min(SIDE_MAX, max(SIDE_MIN, 0.38f * w)).coerceAtMost(right - left)
                val st = barB + GAP
                if (floor - st >= SHEET_MIN_H) {
                    out += ScreenPlaced("sheet", ScreenSlot.SHEET, ScreenRect(right - sw, st, right, floor))
                    sheetL = right - sw
                }
            } else {
                val minTop = if (typing) below + GAP else max(below + GAP, keepOut(w, h, s.mode).b + GAP)
                val st = max(minTop, floor - 0.45f * h)
                if (floor - st >= SHEET_MIN_H) {
                    out += ScreenPlaced("sheet", ScreenSlot.SHEET, ScreenRect(left, st, right, floor))
                    floor = st - GAP
                }
            }
        }

        // ---- the toast, one line under the bar (and the strip)
        var toastB = below
        if (s.toast && !typing) {
            val tt = below + GAP
            val lo = if (wide) castR + GAP else left
            val cameraOn = wide && showsCast(s.mode) && !sheetOpen
            val hi = if (wide && sheetL < right) sheetL - GAP else if (cameraOn) dragR - CAMERA_W - GAP else right
            val tw = min(TOAST_W, hi - lo)
            if (tw >= 160f) {
                val x = lo + (hi - lo - tw) / 2f
                var r = ScreenRect(x, tt, x + tw, tt + TOAST_H)
                for (c in ins.cutouts) if (r.intersects(c.inflate(GAP))) r = ScreenRect(r.l, c.b + GAP, r.r, c.b + GAP + TOAST_H)
                out += ScreenPlaced("toast", ScreenSlot.TOAST, r, interactive = false)
                if (!wide) toastB = r.b
            }
        }

        // ---- the camera: its mode button, and while orbiting, its up, down and zoom keys (not while typing or a side sheet is open)
        if (showsCast(s.mode) && !typing && !(wide && sheetOpen)) {
            val ct = (if (wide) barB else toastB) + GAP
            val cl = dragR - CAMERA_W
            val room = floor - ct
            if (room >= TOUCH) {
                out += ScreenPlaced("camera", ScreenSlot.CAMERA, ScreenRect(cl, ct, dragR, ct + TOUCH))
                if (s.orbit && room >= TOUCH + GAP + KEYS_H) {
                    out += ScreenPlaced("camera.keys", ScreenSlot.CAMERA, ScreenRect(cl, ct + TOUCH + GAP, dragR, ct + TOUCH + GAP + KEYS_H), draggable = true)
                }
            }
        }
        return out
    }

    /** The placed thing called [element] (the first), or null. */
    fun find(plan: List<ScreenPlaced>, element: String): ScreenRect? = plan.firstOrNull { it.element == element }?.rect
}
