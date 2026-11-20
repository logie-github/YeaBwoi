package com.logie.wifecraft.ui

import com.logie.wifecraft.simulation.CameraMode
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// Pure Kotlin, no android.* and no Compose (JVM-tested: unit-tests/.../ui/SceneHudTest): the live scene's HUD in the front
// menus' look (SceneSessionScreen draws it). What the action bar offers for the controlled NPC and in which order, its
// pages of six, the line of help, the clock, where the bars go on each of the Fold's screens, the camera's controls, and the
// Play animation window's category tabs.

/** What an action-bar slot does. */
enum class HudOrder { WALK, PLAY, PARTNER, SAY, CHAT, STOP_REPEAT, CLEAR_QUEUE, RELEASE, USE, FREE_WILL, LIFE }

/** One slot of the action bar: what it does, its words, [lit] while it is on (Walk), [target] the object it uses. */
data class HudSlot(val order: HudOrder, val label: String, val lit: Boolean = false, val target: String? = null) {
    /** Stable per slot (two objects' Use slots differ by their target). */
    val key: String get() = if (target == null) order.name else "${order.name}:$target"
}

/** What the camera button has on: the camera's [mode] and, at 0 or more, the furniture view shown ([SceneHud.nextCamera]). */
data class CameraChoice(val mode: CameraMode, val view: Int = -1)

/**
 * Where the HUD goes on a screen [w] x [h] dp: the cast as party frames down the left ([castSide]) or a strip under the top
 * bar; the action bar's [cols] x [rows] slots, each [slotW] wide, [barH] high with its gaps (the same whether the slots are
 * all there or not: the line above it never moves).
 */
data class HudLayout(
    val castSide: Boolean,
    val castW: Float,
    val castH: Float,
    val cols: Int,
    val rows: Int,
    val slotW: Float,
    val barW: Float,
    val barH: Float,
)

object SceneHud {
    /** Slots shown at once (one page of the bar). */
    const val SLOTS = 6
    const val SLOT_H = 48f
    const val GAP = 8f
    const val MARGIN = 8f
    /** The page arrows' column at the bar's end (kept even with one page: the bar never shifts). */
    const val PAGER_W = 48f
    const val TOP_H = 56f
    /** The narrowest slot for six in a row; narrower screens get two rows of three. */
    const val SLOT_MIN = 104f
    const val SLOT_MAX = 140f

    fun layout(w: Float, h: Float): HudLayout {
        val castSide = w >= 600f
        val room = max(0f, w - 2 * MARGIN - PAGER_W - GAP)
        val cols = if (room >= SLOTS * SLOT_MIN + (SLOTS - 1) * GAP) SLOTS else SLOTS / 2
        val rows = SLOTS / cols
        val slotW = min(SLOT_MAX, max(0f, (room - (cols - 1) * GAP) / cols))
        val barW = cols * slotW + (cols - 1) * GAP + GAP + PAGER_W
        val barH = rows * SLOT_H + (rows - 1) * GAP
        return HudLayout(castSide, if (castSide) 188f else 156f, if (castSide) 56f else 56f, cols, rows, slotW, barW, barH)
    }

    /**
     * The controlled NPC's orders, most used first: Walk (tap the floor), Play animation, Partner animation (with a
     * partner), Say, Chat with (with someone else in the scene), Stop repeat and Clear queue (while there is one), Release,
     * then the scene's objects ([uses]: target to words) and Free will. With the life simulation on ([life]), Actions (its
     * radial menu: Rest, Eat, Chat, Hug...) comes second, after Walk.
     */
    fun orders(
        walking: Boolean,
        partner: Boolean,
        others: Int,
        repeating: Boolean,
        queued: Int,
        uses: List<Pair<String, String>> = emptyList(),
        life: Boolean = false,
    ): List<HudSlot> = buildList {
        add(HudSlot(HudOrder.WALK, "Walk", lit = walking))
        if (life) add(HudSlot(HudOrder.LIFE, "Actions"))
        add(HudSlot(HudOrder.PLAY, "Play animation"))
        if (partner) add(HudSlot(HudOrder.PARTNER, "Partner animation"))
        add(HudSlot(HudOrder.SAY, "Say…"))
        if (others > 0) add(HudSlot(HudOrder.CHAT, "Chat with…"))
        if (repeating) add(HudSlot(HudOrder.STOP_REPEAT, "Stop repeat"))
        if (queued > 0) add(HudSlot(HudOrder.CLEAR_QUEUE, "Clear queue ($queued)"))
        add(HudSlot(HudOrder.RELEASE, "Release"))
        for ((target, words) in uses) add(HudSlot(HudOrder.USE, words, target = target))
        add(HudSlot(HudOrder.FREE_WILL, "Free will"))
    }

    fun pages(slots: Int): Int = max(1, ceil(slots / SLOTS.toFloat()).toInt())

    /** [page] kept within the pages there are (the bar shortened under it). */
    fun clampPage(page: Int, slots: Int): Int = page.coerceIn(0, pages(slots) - 1)

    /** The slots of [page] (at most [SLOTS]). */
    fun page(slots: List<HudSlot>, page: Int): List<HudSlot> = slots.drop(clampPage(page, slots.size) * SLOTS).take(SLOTS)

    /** The line over the bar: what a tap does now. */
    fun hint(selected: Boolean, partner: Boolean, cast: Int, walking: Boolean): String = when {
        !selected -> if (cast > 0) "Tap an NPC to direct them." else ""
        walking && !partner && cast > 1 -> "Tap the floor to walk there, another NPC to make them the partner."
        walking -> "Tap the floor to walk there."
        !partner && cast > 1 -> "Tap another NPC to make them the partner."
        else -> ""
    }

    /** The clock on the top bar, as the game's: "9:05 PM", or "21:05" on a 24-hour phone. */
    fun clock(hour: Int, minute: Int, h24: Boolean): String {
        val m = minute.coerceIn(0, 59).toString().padStart(2, '0')
        val hr = hour.coerceIn(0, 23)
        if (h24) return "${hr.toString().padStart(2, '0')}:$m"
        val h12 = if (hr % 12 == 0) 12 else hr % 12
        return "$h12:$m ${if (hr < 12) "AM" else "PM"}"
    }

    /** The camera controls' gap and width: the camera button over two columns of 48 dp buttons (up and down, zoom). */
    const val CAMERA_GAP = 4f
    const val CAMERA_W = 2 * SLOT_H + CAMERA_GAP

    /** The camera button's words for [mode]. */
    fun cameraLabel(mode: CameraMode): String = when (mode) {
        CameraMode.ROOM -> "Room view"
        CameraMode.ORBIT -> "Orbit"
        CameraMode.FOLLOW -> "Follow"
    }

    /** What the camera button says it does: its mode and the next one a tap gives. */
    fun cameraDescription(mode: CameraMode, canFollow: Boolean): String =
        "Camera: ${cameraLabel(mode)}. Tap for ${cameraLabel(mode.next(canFollow))}."

    /** Where the camera controls start from the top, dp: under the top bar, and under the cast's strip when it has one. */
    fun cameraTop(layout: HudLayout): Float = if (layout.castSide) TOP_H + 4f else TOP_H + layout.castH + GAP

    /**
     * The mode a drag or pinch on the view gives: the room's view becomes the orbit (it starts exactly on the room's view,
     * so nothing jumps); an orbit or Follow stays as it is.
     */
    fun cameraForTouch(mode: CameraMode): CameraMode = if (mode == CameraMode.ROOM) CameraMode.ORBIT else mode

    /**
     * The mode once the controlled NPC is let go ([selected] false): Follow becomes the orbit where it is (the view holds);
     * otherwise it stays.
     */
    fun cameraAfterRelease(mode: CameraMode, selected: Boolean): CameraMode =
        if (!selected && mode == CameraMode.FOLLOW) CameraMode.ORBIT else mode

    /**
     * The camera button's next choice when the controlled NPC is on furniture with [views] furniture views: Room view,
     * Orbit, Follow (with someone to follow), then each furniture view in turn ([CameraChoice.view], shown in the room's
     * mode), then the room's view again. An orbit begun from a furniture view (a drag on it) goes on to the next view.
     * Without views it is [CameraMode.next].
     */
    fun nextCamera(current: CameraChoice, canFollow: Boolean, views: Int): CameraChoice {
        val view = current.view.takeIf { it in 0 until views } ?: -1
        if (view >= 0) return if (view + 1 < views) CameraChoice(CameraMode.ROOM, view + 1) else CameraChoice(CameraMode.ROOM)
        val next = current.mode.next(canFollow)
        return if (next == CameraMode.ROOM && views > 0) CameraChoice(CameraMode.ROOM, 0) else CameraChoice(next)
    }

    /** The camera button's words for [choice] ([viewName]: the furniture view's name, "Foot", "Head"...). */
    fun cameraLabel(choice: CameraChoice, viewName: String?): String =
        if (choice.view >= 0 && viewName != null) viewName else cameraLabel(choice.mode)

    /** What the camera button says it does with furniture views [viewNames]: the choice now and the next a tap gives. */
    fun cameraDescription(choice: CameraChoice, canFollow: Boolean, viewNames: List<String>): String {
        val next = nextCamera(choice, canFollow, viewNames.size)
        return "Camera: ${cameraLabel(choice, viewNames.getOrNull(choice.view))}. " +
            "Tap for ${cameraLabel(next, viewNames.getOrNull(next.view))}."
    }

    /** The two-person animations' own tab in the Play animation window. */
    const val PARTNER_TAB = "Partner"
    const val ALL_TAB = "All"

    /**
     * The Play animation window's tabs: All and every [groups] in order, and Partner (the two-person ones) when there are
     * any; Partner first when the controlled NPC has a partner ([partnerFirst]), last otherwise. A group already named
     * Partner is the Partner tab.
     */
    fun animationTabs(groups: List<String>, hasPairs: Boolean, partnerFirst: Boolean): List<String> {
        val rest = groups.distinct().filterNot { it.equals(PARTNER_TAB, ignoreCase = true) || it.equals(ALL_TAB, ignoreCase = true) }
        return when {
            !hasPairs -> listOf(ALL_TAB) + rest
            partnerFirst -> listOf(PARTNER_TAB, ALL_TAB) + rest
            else -> listOf(ALL_TAB) + rest + PARTNER_TAB
        }
    }

    /** Whether an animation of [group] for [people] NPCs belongs under [tab]. */
    fun inTab(tab: String, group: String, people: Int): Boolean = when (tab) {
        ALL_TAB -> true
        PARTNER_TAB -> people == 2
        else -> group == tab
    }
}
